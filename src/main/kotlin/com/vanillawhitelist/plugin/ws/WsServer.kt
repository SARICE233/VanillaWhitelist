package com.vanillawhitelist.plugin.ws

import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.logging.Level

class WsServer(private val plugin: VanillaWhitelistPlugin) {

    private var server: WebSocketServerImpl? = null
    private var running = false

    val isRunning: Boolean get() = running

    fun start() {
        val config = plugin.pluginConfig
        if (!config.websocketEnabled) {
            plugin.logger.info("WebSocket server is disabled in config, not starting.")
            return
        }

        try {
            server = WebSocketServerImpl(InetSocketAddress(config.websocketHost, config.websocketPort))
            server?.start()
            running = true
            plugin.logger.info("WebSocket server started on ${config.websocketHost}:${config.websocketPort}")
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to start WebSocket server: ${e.message}", e)
            running = false
        }
    }

    fun stop() {
        running = false
        try {
            // Close all active sessions
            server?.connections?.forEach { conn ->
                WsSession.remove(conn)
            }
            server?.stop(1000)
            plugin.logger.info("WebSocket server stopped.")
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error stopping WebSocket server: ${e.message}", e)
        }
    }

    fun restart() {
        plugin.logger.info("Restarting WebSocket server...")
        stop()
        start()
    }

    /**
     * 发送消息：有活跃连接时直接发送，否则缓冲到 SQLite
     */
    fun send(json: String) {
        if (hasConnections()) {
            server?.connections?.forEach { conn ->
                if (conn.isOpen) {
                    conn.send(json)
                }
            }
        } else {
            // 网站离线，缓冲到数据库
            val queueSize = plugin.database.enqueueMessage(json)
            if (plugin.pluginConfig.debug) {
                plugin.logger.info("No WebSocket connection, buffered message (queue size: $queueSize)")
            }
        }
    }

    /**
     * 补发所有缓冲的消息（认证成功后调用）
     */
    fun flushBuffer() {
        val messages = plugin.database.dequeueAll()
        if (messages.isEmpty()) return

        plugin.logger.info("Flushing ${messages.size} buffered messages to connected client...")
        server?.connections?.forEach { conn ->
            if (conn.isOpen) {
                messages.forEach { conn.send(it) }
            }
        }
        plugin.logger.info("Flushed ${messages.size} messages.")
    }

    fun bufferSize(): Int = plugin.database.queueSize()

    /**
     * 检查是否有活跃连接
     */
    fun hasConnections(): Boolean {
        return server?.connections?.any { it.isOpen } == true
    }

    // 内部 WebSocket Server 实现
    private inner class WebSocketServerImpl(address: InetSocketAddress) : WebSocketServer(address) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            plugin.logger.info("New WebSocket connection from ${conn.remoteSocketAddress}")

            // 单连接限制：关闭旧连接
            val oldConnections = connections.filter { it != conn && it.isOpen }
            if (oldConnections.isNotEmpty()) {
                plugin.logger.warning(
                    "Closing ${oldConnections.size} old connection(s) — only one active connection allowed"
                )
                oldConnections.forEach { old ->
                    old.close(4001, "Replaced by new connection")
                    WsSession.remove(old)
                }
            }

            // 创建会话并启动认证超时
            val session = WsSession(plugin, conn)
            session.startAuthTimeout()
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            plugin.logger.info(
                "WebSocket closed: ${conn.remoteSocketAddress} " +
                    "(code=$code, reason=$reason, remote=$remote)"
            )
            WsSession.remove(conn)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val session = WsSession.get(conn)
            if (session != null) {
                session.handleMessage(message)
            } else {
                plugin.logger.warning("Message from unknown session: ${conn.remoteSocketAddress}")
                conn.close(4000, "No active session")
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            plugin.logger.log(Level.WARNING, "WebSocket error: ${ex.message}", ex)
        }

        override fun onStart() {
            plugin.logger.info("WebSocket server socket bound successfully.")
        }
    }
}
