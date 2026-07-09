package com.vanillawhitelist.plugin.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.World
import java.util.logging.Level

/**
 * 数据采集器
 *
 * 负责定期采集服务器状态、世界统计、玩家统计数据，
 * 并通过 WebSocket 推送给已连接的网站客户端。
 */
class StatsCollector(private val plugin: VanillaWhitelistPlugin) {

    private val gson = Gson()

    private var serverStatsTask: org.bukkit.scheduler.BukkitTask? = null
    private var worldStatsTask: org.bukkit.scheduler.BukkitTask? = null
    private var playerStatsTask: org.bukkit.scheduler.BukkitTask? = null

    fun startPeriodicTasks() {
        val config = plugin.pluginConfig

        // 服务器状态：初始延迟 5 秒，按配置间隔推送
        serverStatsTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable { collectAndPushServerStats() },
            100L, // 5 seconds initial delay
            config.pushIntervalSeconds * 20L
        )

        // 世界统计：初始延迟 10 秒
        worldStatsTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable { collectAndPushWorldStats() },
            200L,
            config.worldStatsIntervalSeconds * 20L
        )

        // 玩家统计：初始延迟 15 秒
        playerStatsTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable { collectAndPushPlayerStats() },
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

    // ── Server Stats ──────────────────────────────────────────────────

    fun collectAndPushServerStats() {
        if (!plugin.wsServer.isRunning || !plugin.wsServer.hasConnections()) return

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
                addProperty("server_id", "main")
                addProperty("tps", Math.round(tps * 10.0) / 10.0)
                addProperty("mspt", Math.round(mspt * 10.0) / 10.0)
                addProperty("memory_used", memoryUsed)
                addProperty("memory_max", memoryMax)
                addProperty("loaded_chunks", loadedChunks)
                addProperty("entity_count", entityCount)
                addProperty("online_count", onlinePlayers.size)
                add("players", playersArray)
            }

            plugin.wsServer.send(gson.toJson(json))

            if (plugin.pluginConfig.debug) {
                plugin.logger.info("Server stats pushed: TPS=${"%.1f".format(tps)}, " +
                    "MSPT=${"%.1f".format(mspt)}, Players=${onlinePlayers.size}")
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error collecting server stats: ${e.message}", e)
        }
    }

    // ── World Stats ───────────────────────────────────────────────────

    fun collectAndPushWorldStats() {
        if (!plugin.wsServer.isRunning || !plugin.wsServer.hasConnections()) return

        try {
            val worldsArray = JsonArray()
            for (world in Bukkit.getWorlds()) {
                worldsArray.add(JsonObject().apply {
                    addProperty("name", world.name)
                    addProperty("type", world.environment.toDisplayName())
                    addProperty("explored_chunks", world.loadedChunks.size)
                    addProperty("total_blocks_placed", plugin.worldTracker.totalBlocksPlaced)
                    addProperty("total_blocks_broken", plugin.worldTracker.totalBlocksBroken)
                    addProperty("total_players_joined", plugin.worldTracker.totalJoins)
                    addProperty("total_advancements", plugin.worldTracker.totalAdvancements)
                })
            }

            val json = JsonObject().apply {
                addProperty("type", "world_stats")
                add("worlds", worldsArray)
            }

            plugin.wsServer.send(gson.toJson(json))
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error collecting world stats: ${e.message}", e)
        }
    }

    // ── Player Stats ──────────────────────────────────────────────────

    fun collectAndPushPlayerStats() {
        if (!plugin.wsServer.isRunning || !plugin.wsServer.hasConnections()) return

        try {
            val playersArray = JsonArray()
            val onlinePlayers = Bukkit.getOnlinePlayers()

            for (player in onlinePlayers) {
                val uuid = player.uniqueId.toString()
                playersArray.add(JsonObject().apply {
                    addProperty("uuid", uuid)
                    addProperty("name", player.name)
                    addProperty("playtime_seconds", plugin.worldTracker.getPlayerPlaytimeSeconds(uuid))
                    addProperty("deaths", player.getStatistic(Statistic.DEATHS))
                    addProperty("kills",
                        player.getStatistic(Statistic.MOB_KILLS) +
                        player.getStatistic(Statistic.PLAYER_KILLS)
                    )
                    addProperty("blocks_broken", plugin.worldTracker.getPlayerBlocksBroken(uuid))
                    addProperty("blocks_placed", plugin.worldTracker.getPlayerBlocksPlaced(uuid))
                    addProperty("distance_walked",
                        Math.round(player.getStatistic(Statistic.WALK_ONE_CM) / 100.0 * 10.0) / 10.0
                    )
                    addProperty("achievements_count", plugin.worldTracker.getPlayerAdvancementsCount(uuid))
                    addProperty("first_join",
                        java.time.Instant.ofEpochMilli(player.firstPlayed).toString()
                    )
                    addProperty("last_join",
                        java.time.Instant.ofEpochMilli(player.lastSeen).toString()
                    )
                })
            }

            if (playersArray.size() > 0) {
                val json = JsonObject().apply {
                    addProperty("type", "player_stats_batch")
                    add("players", playersArray)
                }
                plugin.wsServer.send(gson.toJson(json))
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error collecting player stats: ${e.message}", e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun World.Environment.toDisplayName(): String = when (this) {
        World.Environment.NORMAL -> "overworld"
        World.Environment.NETHER -> "the_nether"
        World.Environment.THE_END -> "the_end"
        else -> name.lowercase()
    }
}
