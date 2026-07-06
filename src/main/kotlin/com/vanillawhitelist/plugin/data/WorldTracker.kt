package com.vanillawhitelist.plugin.data

import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * 世界统计追踪器
 *
 * 追踪服务器级别的累计统计数据，通过 SQLite 持久化保证重启不丢失。
 */
class WorldTracker(private val plugin: VanillaWhitelistPlugin) : Listener {

    private val KEY_TOTAL_JOINS = "total_joins"

    private val _totalJoins = AtomicLong(0)

    /** 服务器累计加入人次（从 DB 加载，跨重启持久化） */
    val totalJoins: Long get() = _totalJoins.get()

    init {
        // 从数据库恢复计数
        _totalJoins.set(plugin.database.getLong(KEY_TOTAL_JOINS))
        plugin.logger.info("WorldTracker: loaded totalJoins = $totalJoins from database")
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val newValue = _totalJoins.incrementAndGet()
        // 异步写 DB，不阻塞主线程
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.database.setLong(KEY_TOTAL_JOINS, newValue)
        })
    }
}
