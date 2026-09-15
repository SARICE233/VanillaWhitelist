package com.vanillawhitelist.plugin.ws

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vanillawhitelist.plugin.IMPL
import com.vanillawhitelist.plugin.PROTOCOL_VERSION
import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import com.vanillawhitelist.plugin.stampProtocolVersion
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * 出站模式（mode=client）：本端主动连网站，**不需要开放任何入站端口**。
 *
 * 消息协议与入站模式完全一致，只是连接发起方反过来：
 * 本端发 auth、被网站校验；随后本端推送数据，网站可下发白名单操作。
 *
 * 断线自动重连（指数退避，最长 60 秒）；重连后补发离线期间缓冲的消息。
 */
class WsClient(private val plugin: VanillaWhitelistPlugin) : Transport, Peer {

    private val gson = Gson()
    private var client: ClientImpl? = null
    private var running = false
    private var backoffMs = 1000L
    private var thread: Thread? = null

    @Volatile
    override var authenticated = false

    override val isRunning: Boolean get() = running

    private inner class ClientImpl(uri: URI) : WebSocketClient(uri) {
        override fun onOpen(handshakedata: ServerHandshake?) {
            plugin.logger.info("[VWL] 已连接网站: " + plugin.pluginConfig.websocketUrl)
            backoffMs = 1000L
            sendAuth()
        }

        override fun onMessage(message: String?) {
            if (message != null) handleMessage(message)
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            authenticated = false
            plugin.logger.info("[VWL] 网站连接已关闭: code=" + code)
        }

        override fun onError(ex: Exception?) {
            authenticated = false
            plugin.logger.log(Level.WARNING, "[VWL] 网站连接出错: " + (ex?.message ?: ""))
        }
    }

    override fun start() {
        if (running) return
        val url = plugin.pluginConfig.websocketUrl
        if (url.isBlank()) {
            plugin.logger.severe("[VWL] mode=client 但 websocket.url 为空，无法启动 WebSocket")
            return
        }
        running = true
        thread = Thread({ reconnectLoop() }, "VWL-WS-Client").apply { isDaemon = true; start() }
    }

    private fun reconnectLoop() {
        while (running) {
            try {
                val c = ClientImpl(URI(plugin.pluginConfig.websocketUrl))
                // 半开连接检测：底层 ping/pong
                c.connectionLostTimeout = 60
                client = c
                if (!c.connectBlocking(15, TimeUnit.SECONDS)) {
                    throw IllegalStateException("连接超时")
                }
                while (running && c.isOpen) Thread.sleep(500)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running) plugin.logger.info("[VWL] 出站连接中断: " + e.message)
            }
            authenticated = false
            if (!running) break
            try {
                Thread.sleep(backoffMs)
            } catch (e: InterruptedException) {
                break
            }
            backoffMs = minOf(backoffMs * 2, 60_000L)
        }
    }

    /** 出站模式下由本端发起认证 */
    private fun sendAuth() {
        val o = JsonObject().apply {
            addProperty("type", "auth")
            addProperty("id", "auth-" + System.currentTimeMillis())
            addProperty("secret", plugin.pluginConfig.websocketSecret)
            addProperty("protocol_version", PROTOCOL_VERSION)
            addProperty("impl", IMPL)
            addProperty("impl_version", plugin.pluginMeta.version)
        }
        client?.send(stampProtocolVersion(gson.toJson(o)))
    }

    private fun handleMessage(message: String) {
        val json = try {
            JsonParser.parseString(message).asJsonObject
        } catch (e: Exception) {
            return
        }
        val type = if (json.has("type") && !json.get("type").isJsonNull) json.get("type").asString else null
        if (type == "auth_result") {
            val ok = json.has("success") && json.get("success").asBoolean
            if (ok) {
                authenticated = true
                plugin.logger.info("[VWL] 网站认证通过（出站模式）")
                flushBuffer()
                try {
                    plugin.statsCollector.collectAndPushServerStats()
                    plugin.statsCollector.collectAndPushPlayerAdvancements(false)
                } catch (e: Exception) {
                    plugin.logger.log(Level.WARNING, "[VWL] 初始状态推送失败: " + e.message)
                }
            } else {
                val err = if (json.has("error")) json.get("error").asString else "UNKNOWN"
                plugin.logger.severe("[VWL] 网站拒绝认证: " + err + "（请检查两侧 secret 是否一致）")
                client?.close()
            }
            return
        }
        // 其余消息（白名单操作、ping 等）交给共用的消息处理层
        plugin.messageHandler.handleMessage(this, message)
    }

    override fun send(json: String) {
        val payload = stampProtocolVersion(json)
        val c = client
        if (c != null && c.isOpen && authenticated) {
            try {
                c.send(payload)
            } catch (e: Exception) {
                authenticated = false
            }
            return
        }
        // 网站不在线：入队，重连后补发
        plugin.database.enqueueMessage(payload)
    }

    override fun hasConnections(): Boolean {
        val c = client
        return authenticated && c != null && c.isOpen
    }

    override fun bufferSize(): Int = plugin.database.queueSize()

    override fun flushBuffer() {
        val c = client ?: return
        if (!c.isOpen || !authenticated) return
        val messages = plugin.database.dequeueAll()
        if (messages.isEmpty()) return
        plugin.logger.info("[VWL] 补发离线期间缓冲的 " + messages.size + " 条消息")
        for (msg in messages) {
            try {
                c.send(msg)
            } catch (e: Exception) {
                break
            }
        }
    }

    override fun restart() {
        stop()
        start()
    }

    override fun close() {
        authenticated = false
        try { client?.close() } catch (e: Exception) { }
    }

    override fun stop() {
        running = false
        authenticated = false
        try { client?.close() } catch (e: Exception) { }
        client = null
        thread?.interrupt()
        thread = null
    }
}
