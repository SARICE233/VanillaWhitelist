package com.vanillawhitelist.plugin.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import org.bukkit.Statistic
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * 玩家事件追踪器
 *
 * 监听 Bukkit 玩家事件并推送到 WebSocket。
 * 维护加入时间戳用于计算会话时长。
 */
class PlayerTracker(private val plugin: VanillaWhitelistPlugin) : Listener {

    private val gson = Gson()

    /** 记录每个在线玩家的加入时间戳 (epoch millis) */
    private val joinTimestamps = ConcurrentHashMap<UUID, Long>()

    // ── Join ──────────────────────────────────────────────────────────

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        joinTimestamps[player.uniqueId] = System.currentTimeMillis()

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
        val joinTime = joinTimestamps.remove(player.uniqueId)
        val playtimeSeconds = if (joinTime != null) {
            (System.currentTimeMillis() - joinTime) / 1000
        } else {
            -1L // 未知（可能插件在玩家加入后才加载）
        }

        val json = JsonObject().apply {
            addProperty("type", "player_event")
            addProperty("event", "leave")
            addProperty("player_name", player.name)
            addProperty("player_uuid", player.uniqueId.toString())
            addProperty("playtime_seconds", playtimeSeconds)
        }

        plugin.wsServer.send(gson.toJson(json))

        // 玩家离开时额外推送一次该玩家的统计数据
        pushPlayerStatsOnQuit(player)

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
     * 玩家离开时推送该玩家的累计统计数据
     */
    private fun pushPlayerStatsOnQuit(player: org.bukkit.entity.Player) {
        try {
            val uuid = player.uniqueId.toString()
            val playerObj = JsonObject().apply {
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
            }

            val batch = JsonObject().apply {
                addProperty("type", "player_stats_batch")
                add("players", com.google.gson.JsonArray().apply { add(playerObj) })
            }
            plugin.wsServer.send(gson.toJson(batch))

        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error pushing quit stats for ${player.name}: ${e.message}", e)
        }
    }

    private fun World.Environment.toDisplayName(): String = when (this) {
        World.Environment.NORMAL -> "overworld"
        World.Environment.NETHER -> "the_nether"
        World.Environment.THE_END -> "the_end"
        else -> name.lowercase()
    }
}
