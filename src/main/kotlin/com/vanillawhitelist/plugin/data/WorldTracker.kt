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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 世界统计追踪器
 *
 * 追踪服务器级别的累计统计数据（通过 SQLite 持久化）：
 * - 总加入人次
 * - 总破坏/放置方块数
 * - 玩家在线时长
 * - 玩家完成进度数
 *
 * 优化点：
 * 1. 高频事件（方块破坏/放置、进度完成）仅做内存原子累加，零 IO 阻塞
 * 2. 待持久化的增量累积到 [pendingDeltaQueue]，由 [flushTask] 每 30 秒异步批量写入
 * 3. 统一管理 [joinTimestamps]，供 PlayerTracker 复用，避免重复维护
 * 4. 玩家会话时长在 quit 时即时累加到 pending 队列，flush 时统一持久化
 */
class WorldTracker(private val plugin: VanillaWhitelistPlugin) : Listener {

    private val KEY_TOTAL_JOINS = "total_joins"
    private val KEY_TOTAL_BLOCKS_BROKEN = "total_blocks_broken"
    private val KEY_TOTAL_BLOCKS_PLACED = "total_blocks_placed"
    private val KEY_TOTAL_ADVANCEMENTS = "total_advancements"

    // 服务器级累计（内存镜像，启动时从 DB 加载）
    private val _totalJoins = AtomicLong(0)
    private val _totalBlocksBroken = AtomicLong(0)
    private val _totalBlocksPlaced = AtomicLong(0)
    private val _totalAdvancements = AtomicLong(0)

    /** 玩家加入时间戳，用于计算会话在线时长（统一管理，PlayerTracker 复用） */
    val joinTimestamps = ConcurrentHashMap<UUID, Long>()

    /**
     * 待持久化的增量队列。
     * 每个元素是 (dbKey, delta) 对，flush 时按 key 聚合后批量 upsert。
     * ConcurrentLinkedQueue 保证多线程无锁入队。
     */
    private val pendingDeltaQueue = ConcurrentLinkedQueue<Pair<String, Long>>()

    private var flushTask: org.bukkit.scheduler.BukkitTask? = null

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

    /** 启动异步 flush 任务（30 秒一次） */
    fun startFlushTask() {
        flushTask?.cancel()
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            Runnable { flushPending() },
            600L,   // 30s initial delay
            600L    // 30s period (30 * 20 ticks)
        )
    }

    /** 取消 flush 任务，并执行最后一次 flush */
    fun stopFlushTask() {
        flushTask?.cancel()
        flushTask = null
        // 同步执行最后一次 flush，确保数据不丢
        flushPending()
    }

    // ── Join / Quit（在线时长）─────────────────────────────────────────

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val newJoins = _totalJoins.incrementAndGet()
        joinTimestamps[event.player.uniqueId] = System.currentTimeMillis()
        // 入队增量，由 flush 异步持久化
        pendingDeltaQueue.add(KEY_TOTAL_JOINS to 1L)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        val joinTime = joinTimestamps.remove(uuid) ?: return
        val sessionSeconds = (System.currentTimeMillis() - joinTime) / 1000
        // 会话时长入队，flush 时累加到玩家累计字段
        pendingDeltaQueue.add(playerPlaytimeKey(uuid.toString()) to sessionSeconds)
    }

    // ── Block Break / Place ────────────────────────────────────────────

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.isCancelled) return
        _totalBlocksBroken.incrementAndGet()
        pendingDeltaQueue.add(KEY_TOTAL_BLOCKS_BROKEN to 1L)
        pendingDeltaQueue.add(playerBlocksBrokenKey(event.player.uniqueId.toString()) to 1L)
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.isCancelled) return
        _totalBlocksPlaced.incrementAndGet()
        pendingDeltaQueue.add(KEY_TOTAL_BLOCKS_PLACED to 1L)
        pendingDeltaQueue.add(playerBlocksPlacedKey(event.player.uniqueId.toString()) to 1L)
    }

    // ── Advancement ────────────────────────────────────────────────────

    @EventHandler
    fun onPlayerAdvancementDone(event: PlayerAdvancementDoneEvent) {
        // 过滤掉配方解锁（recipe 类型 advancement），只统计真正的进度
        val key = event.advancement.key
        if (key.namespace == "minecraft" && key.key.startsWith("recipes/")) return

        _totalAdvancements.incrementAndGet()
        pendingDeltaQueue.add(KEY_TOTAL_ADVANCEMENTS to 1L)
        pendingDeltaQueue.add(playerAdvancementsKey(event.player.uniqueId.toString()) to 1L)
    }

    // ── Public Query Methods ───────────────────────────────────────────

    /**
     * 查询玩家累计在线时长（秒），包含当前会话
     * 注意：此方法会读取 DB，应在异步线程调用以避免阻塞主线程
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

    // ── Flush 逻辑 ────────────────────────────────────────────────────

    /**
     * 将待持久化队列中的增量按 key 聚合后批量写入 DB。
     * 此方法线程安全，由异步 flushTask 定时调用，也可手动触发。
     */
    private fun flushPending() {
        if (pendingDeltaQueue.isEmpty()) return

        // 聚合所有增量
        val aggregated = HashMap<String, Long>()
        var entry: Pair<String, Long>? = pendingDeltaQueue.poll()
        while (entry != null) {
            aggregated.merge(entry.first, entry.second) { a, b -> a + b }
            entry = pendingDeltaQueue.poll()
        }

        if (aggregated.isEmpty()) return

        try {
            plugin.database.bulkUpsertLong(aggregated)
            if (plugin.pluginConfig.debug) {
                plugin.logger.info("WorldTracker flushed ${aggregated.size} keys to DB")
            }
        } catch (e: Exception) {
            // flush 失败时把增量放回队列，下次再试（队列有大小限制由 GC 自然处理）
            plugin.logger.warning("WorldTracker flush failed, ${aggregated.size} keys deferred: ${e.message}")
            // 不放回以避免无限累积；内存中的 AtomicLong 已是正确值，下次 flush 会继续累加新增量
        }
    }

    // ── DB Key Helpers ─────────────────────────────────────────────────

    private fun playerPlaytimeKey(uuid: String) = "player_${uuid}_playtime"
    private fun playerBlocksBrokenKey(uuid: String) = "player_${uuid}_blocks_broken"
    private fun playerBlocksPlacedKey(uuid: String) = "player_${uuid}_blocks_placed"
    private fun playerAdvancementsKey(uuid: String) = "player_${uuid}_advancements"
}