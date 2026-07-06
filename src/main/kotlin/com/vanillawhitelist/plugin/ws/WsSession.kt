package com.vanillawhitelist.plugin.ws

import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import org.bukkit.Bukkit
import org.java_websocket.WebSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * 单个 WebSocket 连接的会话管理
 *
 * 每个连接对应一个 WsSession，维护认证状态和超时管理。
 */
class WsSession(
    private val plugin: VanillaWhitelistPlugin,
    private val conn: WebSocket
) {

    companion object {
        private val sessions = ConcurrentHashMap<WebSocket, WsSession>()

        fun get(conn: WebSocket): WsSession? = sessions[conn]

        fun remove(conn: WebSocket) {
            val session = sessions.remove(conn)
            session?.cancelAuthTimeout()
        }
    }

    /** 是否已通过认证 */
    @Volatile
    var authenticated = false

    /** 认证超时任务 */
    private var authTimeoutTask: org.bukkit.scheduler.BukkitTask? = null

    init {
        sessions[conn] = this
    }

    /**
     * 启动 10 秒认证超时计时器
     * 超时后若未认证则关闭连接
     */
    fun startAuthTimeout() {
        cancelAuthTimeout()
        authTimeoutTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!authenticated) {
                plugin.logger.warning(
                    "Auth timeout for ${conn.remoteSocketAddress}, closing connection."
                )
                conn.close(4002, "Authentication timeout (10s)")
                remove(conn)
            }
        }, 200L) // 10 seconds = 200 ticks
    }

    fun cancelAuthTimeout() {
        authTimeoutTask?.cancel()
        authTimeoutTask = null
    }

    /**
     * 处理收到的消息，委托给 MessageHandler
     */
    fun handleMessage(message: String) {
        try {
            plugin.messageHandler.handleMessage(this, message)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error handling message: ${e.message}", e)
            send("""{"type":"error","error":"INTERNAL_ERROR","message":"${e.message?.replace("\"", "\\\"")}"}""")
        }
    }

    fun send(json: String) {
        if (conn.isOpen) {
            conn.send(json)
        }
    }

    fun close(code: Int, reason: String) {
        conn.close(code, reason)
        remove(conn)
    }
}
