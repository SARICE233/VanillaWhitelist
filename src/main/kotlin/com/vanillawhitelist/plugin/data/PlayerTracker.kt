package com.vanillawhitelist.plugin.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.logging.Level

/**
 * 玩家事件追踪器
 *
 * 监听 Bukkit 玩家事件并推送到 WebSocket。
 *
 * 优化点：
 * 1. 移除本地 joinTimestamps，复用 [WorldTracker.joinTimestamps]，避免数据重复
 * 2. 玩家事件（join/quit/death/dimension_change）即时推送，保证实时性
 * 3. 玩家离场统计先在主线程采集不可变快照，再异步执行 DB 查询与推送，
 *    避免异步线程持有 Player 引用（use-after-free 风险）及阻塞主线程
 */
class PlayerTracker(private val plugin: VanillaWhitelistPlugin) : Listener {

    private val gson = Gson()

    // ── Join ──────────────────────────────────────────────────────────

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        // 会话时间戳由 WorldTracker 统一维护，此处仅推送事件
        val json = JsonObject().apply {
            addProperty("type", "player_event")
            addProperty("event", "join")
            addProperty("player_name", player.name)
            addProperty("player_uuid", player.uniqueId.toString())
        }
        plugin.wsServer.send(gson.toJson(json))

        if (plugin.pluginConfig.debug) {
            plugin.logger.info("Player joined: ${player.name} (${player.uniqueId})")
        }
    }

    // ── Quit ──────────────────────────────────────────────────────────

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val uuid = player.uniqueId
        // 从 WorldTracker 读取会话时长（WorldTracker.onPlayerQuit 也会触发，顺序由 Bukkit 事件优先级保证）
        val joinTime = plugin.worldTracker.joinTimestamps[uuid]
        val playtimeSeconds = if (joinTime != null) {
            (System.currentTimeMillis() - joinTime) / 1000
        } else {
            -1L // 未知（可能插件在玩家加入后才加载）
        }

        val json = JsonObject().apply {
            addProperty("type", "player_event")
            addProperty("event", "leave")
            addProperty("player_name", player.name)
            addProperty("player_uuid", uuid.toString())
            addProperty("playtime_seconds", playtimeSeconds)
        }
        plugin.wsServer.send(gson.toJson(json))

        // 在主线程抓取统计快照：getStatistic/firstPlayed 等实体读取不是线程安全的，
        // 异步任务只接收不可变快照，严禁把 Player 对象带进异步线程
        // （玩家退出后主线程随时可能回收该对象，存在 use-after-free 风险）
        val snapshot = QuitPlayerSnapshot(
            uuid = uuid.toString(),
            name = player.name,
            deaths = player.getStatistic(Statistic.DEATHS),
            kills = player.getStatistic(Statistic.MOB_KILLS) +
                player.getStatistic(Statistic.PLAYER_KILLS),
            walkOneCm = player.getStatistic(Statistic.WALK_ONE_CM),
            firstPlayed = player.firstPlayed,
            lastSeen = player.lastSeen
        )

        // DB 查询与 JSON 序列化异步执行，避免阻塞主线程
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            pushPlayerStatsOnQuit(snapshot)
        })

        if (plugin.pluginConfig.debug) {
            plugin.logger.info("Player left: ${player.name} (session: ${playtimeSeconds}s)")
        }
    }

    // ── Dimension Change ──────────────────────────────────────────────

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        val player = event.player

        val json = JsonObject().apply {
            addProperty("type", "player_event")
            addProperty("event", "dimension_change")
            addProperty("player_name", player.name)
            addProperty("player_uuid", player.uniqueId.toString())
            addProperty("from", event.from.environment.toDisplayName())
            addProperty("to", player.world.environment.toDisplayName())
        }

        plugin.wsServer.send(gson.toJson(json))
    }

    // ── Death ─────────────────────────────────────────────────────────

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player

        // Paper 1.21+: DamageSource → DamageType → NamespacedKey
        val causeNsKey = event.damageSource.damageType.key
        val cause = causeNsKey.key // 仅取 key 部分（去掉 "minecraft:" 前缀）

        val json = JsonObject().apply {
            addProperty("type", "player_event")
            addProperty("event", "death")
            addProperty("player_name", player.name)
            addProperty("player_uuid", player.uniqueId.toString())
            addProperty("cause", cause)
        }

        plugin.wsServer.send(gson.toJson(json))

        if (plugin.pluginConfig.debug) {
            plugin.logger.info("Player died: ${player.name} (cause: $cause)")
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────

    /**
     * 玩家离场统计快照（在主线程采集的不可变数据，供异步任务使用）
     */
    private data class QuitPlayerSnapshot(
        val uuid: String,
        val name: String,
        val deaths: Int,
        val kills: Int,
        val walkOneCm: Int,
        val firstPlayed: Long,
        val lastSeen: Long
    )

    /**
     * 玩家离开时推送该玩家的累计统计数据（异步执行）
     * 注意：此方法内含 DB 查询，必须在异步线程调用；入参为主线程采集好的快照
     */
    private fun pushPlayerStatsOnQuit(snap: QuitPlayerSnapshot) {
        try {
            val uuid = snap.uuid
            val playerObj = JsonObject().apply {
                addProperty("uuid", uuid)
                addProperty("name", snap.name)
                addProperty("playtime_seconds", plugin.worldTracker.getPlayerPlaytimeSeconds(uuid))
                addProperty("deaths", snap.deaths)
                addProperty("kills", snap.kills)
                addProperty("blocks_broken", plugin.worldTracker.getPlayerBlocksBroken(uuid))
                addProperty("blocks_placed", plugin.worldTracker.getPlayerBlocksPlaced(uuid))
                addProperty("distance_walked",
                    Math.round(snap.walkOneCm / 100.0 * 10.0) / 10.0
                )
                addProperty("achievements_count", plugin.worldTracker.getPlayerAdvancementsCount(uuid))
                addProperty("first_join",
                    java.time.Instant.ofEpochMilli(snap.firstPlayed).toString()
                )
                addProperty("last_join",
                    java.time.Instant.ofEpochMilli(snap.lastSeen).toString()
                )
            }

            val batch = JsonObject().apply {
                addProperty("type", "player_stats_batch")
                add("players", com.google.gson.JsonArray().apply { add(playerObj) })
            }
            plugin.wsServer.send(gson.toJson(batch))

        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error pushing quit stats for ${snap.name}: ${e.message}", e)
        }
    }

    private fun World.Environment.toDisplayName(): String = when (this) {
        World.Environment.NORMAL -> "overworld"
        World.Environment.NETHER -> "the_nether"
        World.Environment.THE_END -> "the_end"
        else -> name.lowercase()
    }
}