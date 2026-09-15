package com.vanillawhitelist.plugin.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.World
import java.util.concurrent.Callable
import java.util.logging.Level

/**
 * 数据采集器
 *
 * 负责定期采集服务器状态、世界统计、玩家统计数据，
 * 并通过 WebSocket 推送给已连接的网站客户端。
 *
 * 优化点：
 * 1. 服务器状态采集（含 TPS/MSPT/内存/玩家坐标）保留在主线程，
 *    因为 Bukkit.getTPS() 等读取快、玩家坐标访问必须在主线程
 * 2. 玩家统计与世界统计采集改为异步线程执行，避免 DB 查询阻塞主线程
 * 3. 异步任务中的世界/玩家数据通过 callSyncMethod 切回主线程抓取不可变快照，
 *    再回到异步线程做 DB 查询与 JSON 序列化——getOnlinePlayers/getStatistic/
 *    world.loadedChunks 等访问都不是线程安全的，严禁在异步线程直接调用
 * 4. 数据推送通过 WsServer.send 异步发送，WebSocket 库本身非阻塞
 */
class StatsCollector(private val plugin: VanillaWhitelistPlugin) {

    private val gson = Gson()

    private var serverStatsTask: org.bukkit.scheduler.BukkitTask? = null
    private var worldStatsTask: org.bukkit.scheduler.BukkitTask? = null
    private var playerStatsTask: org.bukkit.scheduler.BukkitTask? = null

    fun startPeriodicTasks() {
        val config = plugin.pluginConfig

        // 服务器状态：在主线程执行（读取 TPS/玩家坐标必须主线程，但操作很轻）
        serverStatsTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable { collectAndPushServerStats() },
            100L, // 5 seconds initial delay
            config.pushIntervalSeconds * 20L
        )

        // 世界统计：异步执行（仅读 worldTracker 内存值，不读 DB，但仍异步以保持一致）
        worldStatsTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            Runnable { collectAndPushWorldStats() },
            200L,
            config.worldStatsIntervalSeconds * 20L
        )

        // 玩家统计：异步执行（含大量 DB 查询，必须异步）
        playerStatsTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            Runnable {
                collectAndPushPlayerStatsAsync()
                collectAndPushPlayerAdvancements(true)
            },
            300L,
            config.playerStatsIntervalSeconds * 20L
        )

        plugin.logger.info(
            "Periodic tasks started: server=${config.pushIntervalSeconds}s, " +
                "world=${config.worldStatsIntervalSeconds}s, player=${config.playerStatsIntervalSeconds}s"
        )
    }

    fun cancelTasks() {
        serverStatsTask?.cancel()
        worldStatsTask?.cancel()
        playerStatsTask?.cancel()
    }

    // ── Server Stats（主线程，轻量级）──────────────────────────────────

    fun collectAndPushServerStats() {
        if (!plugin.transport.isRunning || !plugin.transport.hasConnections()) return

        try {
            val tpsArray = Bukkit.getTPS()
            val tps = if (tpsArray.isNotEmpty()) tpsArray[0] else 0.0
            val mspt = Bukkit.getAverageTickTime()
            val runtime = Runtime.getRuntime()
            val memoryUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val memoryMax = runtime.maxMemory() / (1024 * 1024)
            val loadedChunks = Bukkit.getWorlds().sumOf { it.loadedChunks.size }
            val entityCount = Bukkit.getWorlds().sumOf { it.entityCount }
            val onlinePlayers = Bukkit.getOnlinePlayers()

            val playersArray = JsonArray()
            for (player in onlinePlayers) {
                playersArray.add(JsonObject().apply {
                    addProperty("name", player.name)
                    addProperty("uuid", player.uniqueId.toString())
                    addProperty("dimension", player.world.environment.toDisplayName())
                    addProperty("x", Math.round(player.location.x * 10.0) / 10.0)
                    addProperty("y", Math.round(player.location.y * 10.0) / 10.0)
                    addProperty("z", Math.round(player.location.z * 10.0) / 10.0)
                })
            }

            val json = JsonObject().apply {
                addProperty("type", "server_stats")
                addProperty("server_id", plugin.pluginConfig.serverId)
                addProperty("tps", Math.round(tps * 10.0) / 10.0)
                addProperty("mspt", Math.round(mspt * 10.0) / 10.0)
                addProperty("memory_used", memoryUsed)
                addProperty("memory_max", memoryMax)
                addProperty("loaded_chunks", loadedChunks)
                addProperty("entity_count", entityCount)
                addProperty("online_count", onlinePlayers.size)
                addProperty("uptime_seconds",
                    java.lang.management.ManagementFactory.getRuntimeMXBean().uptime / 1000)
                add("players", playersArray)
            }

            plugin.transport.send(gson.toJson(json))

            // 顺带做一次性能告警检查（内部有冷却）
            checkAlerts(tps, mspt, memoryUsed, memoryMax)

            if (plugin.pluginConfig.debug) {
                plugin.logger.info("Server stats pushed: TPS=${"%.1f".format(tps)}, " +
                    "MSPT=${"%.1f".format(mspt)}, Players=${onlinePlayers.size}")
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error collecting server stats: ${e.message}", e)
        }
    }

    // ── 成就明细（异步线程，快照走主线程）──────────────────────────────

    private val advSignatures = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Int>()

    /**
     * 成就明细：每位在线玩家的完整成就 id 列表。
     *
     * @param onlyChanged true 时只推自上次以来有变化的玩家（定时推送用）
     *                    false 时推全部在线玩家（网站刚连上时给一份基准）
     */
    fun collectAndPushPlayerAdvancements(onlyChanged: Boolean) {
        if (!plugin.transport.isRunning || !plugin.transport.hasConnections()) return

        // 玩家成就进度必须在主线程读
        val snapshot: Map<java.util.UUID, Pair<String, List<String>>> = try {
            Bukkit.getScheduler().callSyncMethod(plugin, Callable {
                val all = Bukkit.advancementIterator().asSequence().toList()
                Bukkit.getOnlinePlayers().associate { p ->
                    p.uniqueId to (p.name to all.filter { adv ->
                        p.getAdvancementProgress(adv).isDone
                    }.map { it.key.toString() })
                }
            }).get()
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error snapshotting advancements: " + e.message, e)
            return
        }

        try {
            val playersArray = JsonArray()
            for ((uuid, data) in snapshot) {
                val name = data.first
                val ids = data.second
                val sig = ids.hashCode()
                val prev = advSignatures.put(uuid, sig)
                if (onlyChanged && prev != null && prev == sig) continue
                val idsArray = JsonArray()
                ids.forEach { idsArray.add(it) }
                playersArray.add(JsonObject().apply {
                    addProperty("uuid", uuid.toString())
                    addProperty("name", name)
                    addProperty("total", ids.size)
                    add("advancements", idsArray)
                })
            }
            if (onlyChanged && playersArray.size() == 0) return

            val json = JsonObject().apply {
                addProperty("type", "player_advancements")
                add("players", playersArray)
            }
            plugin.transport.send(gson.toJson(json))
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error collecting advancements: " + e.message, e)
        }
    }

    // ── 性能告警 ──────────────────────────────────────────────────────

    private var lastAlertAt = 0L
    private var lastAlertSeverity = 0

    /**
     * 性能告警检查。超过阈值且不在冷却期内时推送 performance_alert。
     * 严重级别升级（warning → critical）会立即告警，不受冷却限制。
     */
    private fun checkAlerts(tps: Double, mspt: Double, memoryUsed: Long, memoryMax: Long) {
        val cfg = plugin.pluginConfig
        if (!cfg.alertsEnabled) return

        val alerts = JsonArray()
        var severity = 0

        if (cfg.tpsCritical > 0 && tps < cfg.tpsCritical) {
            severity = 2
            alerts.add(alertItem("tps", tps, cfg.tpsCritical))
        } else if (cfg.tpsWarning > 0 && tps < cfg.tpsWarning) {
            severity = maxOf(severity, 1)
            alerts.add(alertItem("tps", tps, cfg.tpsWarning))
        }

        val memPct = if (memoryMax <= 0L) 0.0 else memoryUsed * 100.0 / memoryMax
        if (cfg.memoryPercentCritical > 0 && memPct > cfg.memoryPercentCritical) {
            severity = 2
            alerts.add(alertItem("memory_percent", memPct, cfg.memoryPercentCritical))
        } else if (cfg.memoryPercentWarning > 0 && memPct > cfg.memoryPercentWarning) {
            severity = maxOf(severity, 1)
            alerts.add(alertItem("memory_percent", memPct, cfg.memoryPercentWarning))
        }

        if (severity == 0) {
            lastAlertSeverity = 0
            return
        }

        val now = System.currentTimeMillis()
        val escalated = severity > lastAlertSeverity
        if (!escalated && now - lastAlertAt < cfg.alertCooldownSeconds * 1000L) return

        lastAlertAt = now
        lastAlertSeverity = severity

        val sevName = if (severity == 2) "critical" else "warning"
        val json = JsonObject().apply {
            addProperty("type", "performance_alert")
            addProperty("severity", sevName)
            add("alerts", alerts)
            addProperty("tps", Math.round(tps * 10.0) / 10.0)
            addProperty("mspt", Math.round(mspt * 10.0) / 10.0)
            addProperty("memory_used", memoryUsed)
            addProperty("memory_max", memoryMax)
        }
        plugin.transport.send(gson.toJson(json))
        plugin.logger.warning("Performance alert pushed: severity=" + sevName)
    }

    private fun alertItem(metric: String, value: Double, threshold: Double): JsonObject =
        JsonObject().apply {
            addProperty("metric", metric)
            addProperty("value", Math.round(value * 10.0) / 10.0)
            addProperty("threshold", threshold)
        }

    // ── World Stats（异步线程）─────────────────────────────────────────

    fun collectAndPushWorldStats() {
        if (!plugin.transport.isRunning || !plugin.transport.hasConnections()) return

        // 切回主线程快照世界数据：world.loadedChunks 要访问世界区块表，异步读取不安全。
        // callSyncMethod 阻塞当前异步线程，直到主线程执行完毕返回结果
        val worldSnapshots: List<WorldSnapshot> = try {
            Bukkit.getScheduler().callSyncMethod(plugin, Callable {
                Bukkit.getWorlds().map { world ->
                    WorldSnapshot(
                        name = world.name,
                        environment = world.environment,
                        loadedChunkCount = world.loadedChunks.size
                    )
                }
            }).get()
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error snapshotting worlds: ${e.message}", e)
            return
        }

        try {
            // 以下为纯内存数据拼装（worldTracker 计数器均为 AtomicLong，线程安全）
            val worldsArray = JsonArray()
            for (snap in worldSnapshots) {
                worldsArray.add(JsonObject().apply {
                    addProperty("name", snap.name)
                    addProperty("type", snap.environment.toDisplayName())
                    addProperty("explored_chunks", snap.loadedChunkCount)
                    addProperty("total_blocks_placed", plugin.worldTracker.getWorldBlocksPlaced(snap.name))
                    addProperty("total_blocks_broken", plugin.worldTracker.getWorldBlocksBroken(snap.name))
                    addProperty("total_players_joined", plugin.worldTracker.totalJoins)
                    addProperty("total_advancements", plugin.worldTracker.totalAdvancements)
                })
            }

            val json = JsonObject().apply {
                addProperty("type", "world_stats")
                add("worlds", worldsArray)
            }

            plugin.transport.send(gson.toJson(json))
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error collecting world stats: ${e.message}", e)
        }
    }

    // ── Player Stats（异步线程，含 DB 查询）────────────────────────────

    /**
     * 异步采集玩家统计。通过 callSyncMethod 切回主线程快照在线玩家基础信息，
     * 然后在异步线程执行 DB 查询和 JSON 序列化。
     */
    private fun collectAndPushPlayerStatsAsync() {
        if (!plugin.transport.isRunning || !plugin.transport.hasConnections()) return

        // 本方法运行在异步线程：getOnlinePlayers 遍历与 getStatistic 读取都不是
        // 线程安全的，必须切回主线程抓取快照（callSyncMethod 会阻塞当前线程直到主线程执行完毕）
        val snapshots: List<PlayerSnapshot> = try {
            Bukkit.getScheduler().callSyncMethod(plugin, Callable {
                Bukkit.getOnlinePlayers().map { player ->
                    PlayerSnapshot(
                        uuid = player.uniqueId.toString(),
                        name = player.name,
                        deaths = player.getStatistic(Statistic.DEATHS),
                        mobKills = player.getStatistic(Statistic.MOB_KILLS),
                        playerKills = player.getStatistic(Statistic.PLAYER_KILLS),
                        walkOneCm = player.getStatistic(Statistic.WALK_ONE_CM),
                        firstPlayed = player.firstPlayed,
                        lastSeen = player.lastSeen
                    )
                }
            }).get()
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error snapshotting players: ${e.message}", e)
            return
        }

        if (snapshots.isEmpty()) return

        // 异步任务由 runTaskTimerAsynchronously 触发，此处直接执行 DB 查询
        try {
            val playersArray = JsonArray()
            for (snap in snapshots) {
                playersArray.add(JsonObject().apply {
                    addProperty("uuid", snap.uuid)
                    addProperty("name", snap.name)
                    addProperty("playtime_seconds", plugin.worldTracker.getPlayerPlaytimeSeconds(snap.uuid))
                    addProperty("deaths", snap.deaths)
                    addProperty("kills", snap.mobKills + snap.playerKills)
                    addProperty("blocks_broken", plugin.worldTracker.getPlayerBlocksBroken(snap.uuid))
                    addProperty("blocks_placed", plugin.worldTracker.getPlayerBlocksPlaced(snap.uuid))
                    addProperty("distance_walked",
                        Math.round(snap.walkOneCm / 100.0 * 10.0) / 10.0
                    )
                    addProperty("achievements_count", plugin.worldTracker.getPlayerAdvancementsCount(snap.uuid))
                    addProperty("first_join",
                        java.time.Instant.ofEpochMilli(snap.firstPlayed).toString()
                    )
                    addProperty("last_join",
                        java.time.Instant.ofEpochMilli(snap.lastSeen).toString()
                    )
                })
            }

            val json = JsonObject().apply {
                addProperty("type", "player_stats_batch")
                add("players", playersArray)
            }
            plugin.transport.send(gson.toJson(json))
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error collecting player stats: ${e.message}", e)
        }
    }

    /**
     * 玩家数据快照（在主线程采集，传递给异步任务使用）
     */
    private data class PlayerSnapshot(
        val uuid: String,
        val name: String,
        val deaths: Int,
        val mobKills: Int,
        val playerKills: Int,
        val walkOneCm: Int,
        val firstPlayed: Long,
        val lastSeen: Long
    )

    /**
     * 世界数据快照（在主线程采集，传递给异步任务使用）
     */
    private data class WorldSnapshot(
        val name: String,
        val environment: World.Environment,
        val loadedChunkCount: Int
    )

    // ── Helpers ───────────────────────────────────────────────────────

    private fun World.Environment.toDisplayName(): String = when (this) {
        World.Environment.NORMAL -> "overworld"
        World.Environment.NETHER -> "the_nether"
        World.Environment.THE_END -> "the_end"
        else -> name.lowercase()
    }
}