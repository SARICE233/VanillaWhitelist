package com.vanillawhitelist.plugin.ws

import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.logging.Level

/**
 * WebSocket 服务器封装
 *
 * 优化点：
 * 1. send() 方法在多线程环境下并发调用时使用连接快照，避免迭代器并发修改异常
 * 2. flushBuffer 限制单次补发数量，避免大队列阻塞认证后的实时数据流
 * 3. 缓冲队列大小由 DatabaseManager 触发器上限保护，enqueueMessage 不会无限增长
 * 4. 单连接替换在认证成功后执行（onOpen 时不踢旧连接），防止未认证者反复握手挤掉合法网站端
 * 5. 消息仅推送给已认证连接，未认证连接收不到任何服务器数据
 * 6. connectionLostTimeout 半开连接检测，避免消息被发进死连接而不进缓冲队列
 */
class WsServer(private val plugin: VanillaWhitelistPlugin) {

    private var server: WebSocketServerImpl? = null
    private var running = false

    /** 单次 flush 最大消息数，防止大队列阻塞实时推送 */
    private val MAX_FLUSH_BATCH = 500

    /** 允许同时存在的未认证连接上限，防止握手洪水耗尽资源 */
    private val MAX_PENDING_CONNECTIONS = 8

    /** 默认密钥占位符（与 config.yml 默认值一致），检测到则拒绝启动 */
    private val DEFAULT_SECRET_PLACEHOLDER = "change-me-to-a-random-string"

    val isRunning: Boolean get() = running

    fun start() {
        val config = plugin.pluginConfig
        if (!config.websocketEnabled) {
            plugin.logger.info("WebSocket server is disabled in config, not starting.")
            return
        }

        // 安全检查：默认/空/过短密钥拒绝启动，防止未授权访问
        if (config.websocketSecret.isBlank() ||
            config.websocketSecret == DEFAULT_SECRET_PLACEHOLDER ||
            config.websocketSecret.length < 16
        ) {
            plugin.logger.severe("============================================================")
            plugin.logger.severe(" WebSocket secret is unset or too weak (min 16 chars)!")
            plugin.logger.severe(" Set a strong random 'websocket.secret' in config.yml.")
            plugin.logger.severe(" WebSocket server will NOT start.")
            plugin.logger.severe("============================================================")
            running = false
            return
        }

        try {
            server = WebSocketServerImpl(InetSocketAddress(config.websocketHost, config.websocketPort)).apply {
                // 半开连接检测：底层 ping/pong，超时自动触发 onClose，
                // 避免网站端网络中断后消息被发进死连接（而不进缓冲队列）
                connectionLostTimeout = 60
            }
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
            // 快照连接列表，避免并发修改
            server?.connections?.toList()?.forEach { conn ->
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
     * 发送消息：有已认证连接时直接发送，否则缓冲到 SQLite。
     * Java-WebSocket 库的 send() 是非阻塞的（内部排队），可安全在异步线程调用。
     * 仅推送给已认证连接——未认证连接收不到任何服务器数据。
     */
    fun send(json: String) {
        if (hasConnections()) {
            // 快照连接列表，避免并发迭代异常
            val conns = server?.connections?.toList() ?: return
            for (conn in conns) {
                if (conn.isOpen && WsSession.get(conn)?.authenticated == true) {
                    try {
                        conn.send(json)
                    } catch (e: Exception) {
                        plugin.logger.log(Level.WARNING, "WS send failed to ${conn.remoteSocketAddress}: ${e.message}")
                    }
                }
            }
        } else {
            // 网站离线，缓冲到数据库（DB 触发器会自动裁剪超额记录）
            val queueSize = plugin.database.enqueueMessage(json)
            if (plugin.pluginConfig.debug && queueSize % 100 == 0 && queueSize > 0) {
                plugin.logger.info("Buffered message (queue size: $queueSize)")
            }
        }
    }

    /**
     * 补发缓冲的消息（认证成功后调用）
     * 单次最多补发 [MAX_FLUSH_BATCH] 条，剩余等下次调用，避免阻塞实时数据流
     */
    fun flushBuffer() {
        val messages = plugin.database.dequeueAll()
        if (messages.isEmpty()) return

        val toFlush = if (messages.size > MAX_FLUSH_BATCH) {
            plugin.logger.warning(
                "Buffer has ${messages.size} messages, flushing first $MAX_FLUSH_BATCH and re-queuing rest"
            )
            // 把超出部分重新入队（在尾部，下次 flush 取出）
            messages.drop(MAX_FLUSH_BATCH).reversed().forEach { plugin.database.enqueueMessage(it) }
            messages.take(MAX_FLUSH_BATCH)
        } else {
            messages
        }

        plugin.logger.info("Flushing ${toFlush.size} buffered messages to connected client...")
        val conns = server?.connections?.toList() ?: return
        for (conn in conns) {
            if (conn.isOpen && WsSession.get(conn)?.authenticated == true) {
                for (msg in toFlush) {
                    try {
                        conn.send(msg)
                    } catch (e: Exception) {
                        plugin.logger.log(Level.WARNING, "WS flush send failed: ${e.message}")
                        break
                    }
                }
            }
        }
        plugin.logger.info("Flushed ${toFlush.size} messages.")
    }

    fun bufferSize(): Int = plugin.database.queueSize()

    /**
     * 检查是否有已认证的活跃连接。
     * 未认证连接不算——消息不应推送给它们，离线期间应转入缓冲队列。
     */
    fun hasConnections(): Boolean {
        return server?.connections?.any { it.isOpen && WsSession.get(it)?.authenticated == true } == true
    }

    /**
     * 关闭除 [keep] 之外的所有连接，实现单连接替换。
     * 必须在新连接认证成功后调用（见 MessageHandler.handleAuth）——
     * 若在 onOpen 时就踢旧连接，任何人反复握手即可挤掉合法网站端（DoS）。
     */
    fun closeOtherConnections(keep: WebSocket) {
        val others = server?.connections?.filter { it != keep && it.isOpen } ?: return
        if (others.isNotEmpty()) {
            plugin.logger.info(
                "Closing ${others.size} connection(s) — replaced by newly authenticated connection"
            )
            others.forEach { old ->
                old.close(4001, "Replaced by new connection")
                WsSession.remove(old)
            }
        }
    }

    // 内部 WebSocket Server 实现
    private inner class WebSocketServerImpl(address: InetSocketAddress) : WebSocketServer(address) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            plugin.logger.info("New WebSocket connection from ${conn.remoteSocketAddress}")

            // 限制未认证连接数量，防止握手洪水耗尽资源；
            // 注意：此处不能踢旧连接！单连接替换在认证成功后进行
            // （见 MessageHandler.handleAuth → closeOtherConnections）
            val pendingCount = connections.count {
                it.isOpen && WsSession.get(it)?.authenticated != true
            }
            if (pendingCount > MAX_PENDING_CONNECTIONS) {
                plugin.logger.warning(
                    "Rejecting ${conn.remoteSocketAddress}: too many pending connections ($pendingCount)"
                )
                conn.close(4004, "Too many pending connections")
                return
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