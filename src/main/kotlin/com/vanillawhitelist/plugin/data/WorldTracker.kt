package com.vanillawhitelist.plugin.data

import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 世界统计追踪器
 *
 * 追踪服务器级别的累计统计数据（通过 SQLite 持久化）：
 * - 总加入人次
 * - 总破坏/放置方块数
 * - 玩家在线时长
 * - 玩家完成进度数
 */
class WorldTracker(private val plugin: VanillaWhitelistPlugin) : Listener {

    private val KEY_TOTAL_JOINS = "total_joins"
    private val KEY_TOTAL_BLOCKS_BROKEN = "total_blocks_broken"
    private val KEY_TOTAL_BLOCKS_PLACED = "total_blocks_placed"
    private val KEY_TOTAL_ADVANCEMENTS = "total_advancements"

    private val _totalJoins = AtomicLong(0)
    private val _totalBlocksBroken = AtomicLong(0)
    private val _totalBlocksPlaced = AtomicLong(0)
    private val _totalAdvancements = AtomicLong(0)

    /** 玩家加入时间戳，用于计算会话在线时长 */
    private val joinTimestamps = ConcurrentHashMap<UUID, Long>()

    val totalJoins: Long get() = _totalJoins.get()
    val totalBlocksBroken: Long get() = _totalBlocksBroken.get()
    val totalBlocksPlaced: Long get() = _totalBlocksPlaced.get()
    val totalAdvancements: Long get() = _totalAdvancements.get()

    init {
        _totalJoins.set(plugin.database.getLong(KEY_TOTAL_JOINS))
        _totalBlocksBroken.set(plugin.database.getLong(KEY_TOTAL_BLOCKS_BROKEN))
        _totalBlocksPlaced.set(plugin.database.getLong(KEY_TOTAL_BLOCKS_PLACED))
        _totalAdvancements.set(plugin.database.getLong(KEY_TOTAL_ADVANCEMENTS))
        plugin.logger.info(
            "WorldTracker: loaded joins=$totalJoins, broken=$totalBlocksBroken, " +
                "placed=$totalBlocksPlaced, advancements=$totalAdvancements"
        )
    }

    // ── Join / Quit（在线时长）─────────────────────────────────────────

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val newJoins = _totalJoins.incrementAndGet()
        joinTimestamps[event.player.uniqueId] = System.currentTimeMillis()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.database.setLong(KEY_TOTAL_JOINS, newJoins)
        })
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        val joinTime = joinTimestamps.remove(uuid) ?: return
        val sessionSeconds = (System.currentTimeMillis() - joinTime) / 1000
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.database.incrementLong(playerPlaytimeKey(uuid.toString()), sessionSeconds)
        })
    }

    // ── Block Break / Place ────────────────────────────────────────────

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.isCancelled) return
        val newTotal = _totalBlocksBroken.incrementAndGet()
        val uuid = event.player.uniqueId.toString()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.database.setLong(KEY_TOTAL_BLOCKS_BROKEN, newTotal)
            plugin.database.incrementLong(playerBlocksBrokenKey(uuid))
        })
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.isCancelled) return
        val newTotal = _totalBlocksPlaced.incrementAndGet()
        val uuid = event.player.uniqueId.toString()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.database.setLong(KEY_TOTAL_BLOCKS_PLACED, newTotal)
            plugin.database.incrementLong(playerBlocksPlacedKey(uuid))
        })
    }

    // ── Advancement ────────────────────────────────────────────────────

    @EventHandler
    fun onPlayerAdvancementDone(event: PlayerAdvancementDoneEvent) {
        // 过滤掉配方解锁（recipe 类型 advancement），只统计真正的进度
        val key = event.advancement.key
        if (key.namespace == "minecraft" && key.key.startsWith("recipes/")) return

        val newTotal = _totalAdvancements.incrementAndGet()
        val uuid = event.player.uniqueId.toString()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.database.setLong(KEY_TOTAL_ADVANCEMENTS, newTotal)
            plugin.database.incrementLong(playerAdvancementsKey(uuid))
        })
    }

    // ── Public Query Methods ───────────────────────────────────────────

    /**
     * 查询玩家累计在线时长（秒），包含当前会话
     */
    fun getPlayerPlaytimeSeconds(uuid: String): Long {
        val cumulative = plugin.database.getLong(playerPlaytimeKey(uuid))
        val playerUuid = runCatching { UUID.fromString(uuid) }.getOrNull() ?: return cumulative
        val joinTime = joinTimestamps[playerUuid] ?: return cumulative
        return cumulative + (System.currentTimeMillis() - joinTime) / 1000
    }

    /** 查询某个玩家累计破坏方块数 */
    fun getPlayerBlocksBroken(uuid: String): Long =
        plugin.database.getLong(playerBlocksBrokenKey(uuid))

    /** 查询某个玩家累计放置方块数 */
    fun getPlayerBlocksPlaced(uuid: String): Long =
        plugin.database.getLong(playerBlocksPlacedKey(uuid))

    /** 查询某个玩家累计完成进度数 */
    fun getPlayerAdvancementsCount(uuid: String): Long =
        plugin.database.getLong(playerAdvancementsKey(uuid))

    // ── DB Key Helpers ─────────────────────────────────────────────────

    private fun playerPlaytimeKey(uuid: String) = "player_${uuid}_playtime"
    private fun playerBlocksBrokenKey(uuid: String) = "player_${uuid}_blocks_broken"
    private fun playerBlocksPlacedKey(uuid: String) = "player_${uuid}_blocks_placed"
    private fun playerAdvancementsKey(uuid: String) = "player_${uuid}_advancements"
}
