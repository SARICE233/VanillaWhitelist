package com.vanillawhitelist.plugin.ws

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/**
 * WebSocket 消息路由器
 *
 * 负责解析收到的 JSON 消息，按 type 分发到不同的处理方法。
 *
 * 优化点：
 * 1. 白名单玩家名解析在异步线程执行（getOfflinePlayer(String) 可能同步查 Mojang，
 *    主线程调用会卡服数秒）；解析完成后白名单读写仍回主线程
 * 2. 单连接替换在认证成功后执行（closeOtherConnections），防止未认证连接挤掉合法网站端
 */
class MessageHandler(private val plugin: VanillaWhitelistPlugin) {

    private val gson = Gson()

    fun handleMessage(session: WsSession, message: String) {
        val json: JsonObject = try {
            JsonParser.parseString(message).asJsonObject
        } catch (e: JsonParseException) {
            session.send(buildError("", "INVALID_JSON", "Invalid JSON format"))
            return
        }

        val type = json.get("type")?.asString ?: run {
            session.send(buildError("", "MISSING_TYPE", "Missing 'type' field"))
            return
        }

        if (plugin.pluginConfig.debug) {
            plugin.logger.info("Received message type: $type")
        }

        when (type) {
            "auth"             -> handleAuth(session, json)
            "ping"             -> handlePing(session)
            "whitelist_add"    -> handleWhitelistAdd(session, json)
            "whitelist_remove" -> handleWhitelistRemove(session, json)
            else -> {
                if (!session.authenticated) {
                    session.send(
                        buildError(jsonId(json), "NOT_AUTHENTICATED", "Please authenticate first")
                    )
                } else {
                    plugin.logger.warning("Unknown message type: $type")
                }
            }
        }
    }

    // ── Auth ──────────────────────────────────────────────────────────

    private fun handleAuth(session: WsSession, json: JsonObject) {
        val id = jsonId(json)

        // 如果已经认证过，直接返回成功
        if (session.authenticated) {
            session.send(buildAuthResult(id, true))
            return
        }

        val secret = json.get("secret")?.asString ?: ""
        val expectedSecret = plugin.pluginConfig.websocketSecret

        if (secret == expectedSecret) {
            session.authenticated = true
            session.cancelAuthTimeout()

            // 认证成功后才执行单连接替换——若在 onOpen 时就踢旧连接，
            // 未认证者反复握手即可挤掉合法网站端（DoS）
            plugin.wsServer.closeOtherConnections(session.webSocket)

            session.send(buildAuthResult(id, true))
            plugin.logger.info("WebSocket client authenticated successfully.")

            // 认证成功，补发网站离线期间缓冲的消息
            val buffered = plugin.wsServer.bufferSize()
            if (buffered > 0) {
                plugin.wsServer.flushBuffer()
            }
        } else {
            session.send(buildAuthResult(id, false, "INVALID_SECRET"))
            session.close(4003, "Invalid secret")
            plugin.logger.warning("WebSocket auth failed: invalid secret.")
        }
    }

    // ── Ping/Pong ─────────────────────────────────────────────────────

    private fun handlePing(session: WsSession) {
        session.send("""{"type":"pong"}""")
    }

    // ── Whitelist Add ─────────────────────────────────────────────────

    private fun handleWhitelistAdd(session: WsSession, json: JsonObject) {
        val id = jsonId(json)
        val playerName = json.get("player_name")?.asString ?: ""
        val playerUuid = json.get("player_uuid")?.asString

        // 权限检查
        if (!session.authenticated) {
            session.send(buildWhitelistResult(id, "whitelist_add", false, playerName, "NOT_AUTHENTICATED"))
            return
        }

        // 名字合法性校验
        val nameError = validatePlayerName(playerName)
        if (nameError != null) {
            session.send(buildWhitelistResult(id, "whitelist_add", false, playerName, nameError))
            return
        }

        val server = plugin.server

        // 解析玩家：UUID 仅查本地（无网络）；玩家名解析可能同步查 Mojang，
        // 必须放异步线程——getOfflinePlayer(String) 在主线程调用会卡服数秒
        val future = CompletableFuture<OfflinePlayer>()
        if (playerUuid != null) {
            val uuid = try {
                UUID.fromString(playerUuid)
            } catch (e: IllegalArgumentException) {
                session.send(buildWhitelistResult(id, "whitelist_add", false, playerName, "INVALID_UUID"))
                return
            }
            future.complete(server.getOfflinePlayer(uuid))
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                try {
                    @Suppress("DEPRECATION")
                    future.complete(server.getOfflinePlayer(playerName))
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            })
        }

        future.thenAccept { offlinePlayer ->
            // 白名单读写必须回主线程
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try {
                    // 检查服务器是否启用了白名单
                    if (!server.hasWhitelist()) {
                        session.send(buildWhitelistResult(id, "whitelist_add", false, playerName, "WHITELIST_DISABLED"))
                        return@Runnable
                    }

                    // 检查是否已在白名单中
                    if (offlinePlayer.isWhitelisted) {
                        session.send(buildWhitelistResult(id, "whitelist_add", false, playerName, "ALREADY_WHITELISTED"))
                        return@Runnable
                    }

                    // 执行添加
                    offlinePlayer.setWhitelisted(true)
                    plugin.logger.info("Added player to whitelist: $playerName (${offlinePlayer.uniqueId})")
                    session.send(buildWhitelistResult(id, "whitelist_add", true, playerName))

                } catch (e: Exception) {
                    plugin.logger.log(Level.WARNING, "Failed to add whitelist: ${e.message}", e)
                    session.send(buildWhitelistResult(id, "whitelist_add", false, playerName, "INTERNAL_ERROR"))
                }
            })
        }.exceptionally { e ->
            plugin.logger.log(Level.WARNING, "Failed to resolve player '$playerName': ${e.message}")
            session.send(buildWhitelistResult(id, "whitelist_add", false, playerName, "PLAYER_LOOKUP_FAILED"))
            null
        }
    }

    // ── Whitelist Remove ──────────────────────────────────────────────

    private fun handleWhitelistRemove(session: WsSession, json: JsonObject) {
        val id = jsonId(json)
        val playerName = json.get("player_name")?.asString ?: ""

        if (!session.authenticated) {
            session.send(buildWhitelistResult(id, "whitelist_remove", false, playerName, "NOT_AUTHENTICATED"))
            return
        }

        val nameError = validatePlayerName(playerName)
        if (nameError != null) {
            session.send(buildWhitelistResult(id, "whitelist_remove", false, playerName, nameError))
            return
        }

        // 异步解析玩家名（getOfflinePlayer(String) 可能同步查 Mojang，严禁在主线程调用）
        val future = CompletableFuture<OfflinePlayer>()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                @Suppress("DEPRECATION")
                future.complete(plugin.server.getOfflinePlayer(playerName))
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        })

        future.thenAccept { offlinePlayer ->
            // 白名单读写必须回主线程
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try {
                    val server = plugin.server

                    if (!server.hasWhitelist()) {
                        session.send(buildWhitelistResult(id, "whitelist_remove", false, playerName, "WHITELIST_DISABLED"))
                        return@Runnable
                    }

                    if (!offlinePlayer.isWhitelisted) {
                        session.send(buildWhitelistResult(id, "whitelist_remove", false, playerName, "NOT_WHITELISTED"))
                        return@Runnable
                    }

                    offlinePlayer.setWhitelisted(false)
                    plugin.logger.info("Removed player from whitelist: $playerName")
                    session.send(buildWhitelistResult(id, "whitelist_remove", true, playerName))

                } catch (e: Exception) {
                    plugin.logger.log(Level.WARNING, "Failed to remove whitelist: ${e.message}", e)
                    session.send(buildWhitelistResult(id, "whitelist_remove", false, playerName, "INTERNAL_ERROR"))
                }
            })
        }.exceptionally { e ->
            plugin.logger.log(Level.WARNING, "Failed to resolve player '$playerName': ${e.message}")
            session.send(buildWhitelistResult(id, "whitelist_remove", false, playerName, "PLAYER_LOOKUP_FAILED"))
            null
        }
    }

    // ── Validation ────────────────────────────────────────────────────

    /**
     * 验证玩家名合法性：3-16 字符，仅字母、数字、下划线
     * 返回 null 表示合法，否则返回错误码字符串
     */
    private fun validatePlayerName(name: String): String? {
        if (name.isEmpty()) return "PLAYER_NAME_EMPTY"
        if (name.length < 3 || name.length > 16) return "PLAYER_NAME_INVALID_LENGTH"
        if (!name.matches(Regex("^[a-zA-Z0-9_]+$"))) return "PLAYER_NAME_INVALID_CHARS"
        return null
    }

    // ── JSON Builders ─────────────────────────────────────────────────

    private fun jsonId(json: JsonObject): String =
        json.get("id")?.asString ?: ""

    private fun buildAuthResult(id: String, success: Boolean, error: String? = null): String {
        return JsonObject().apply {
            addProperty("type", "auth_result")
            addProperty("id", id)
            addProperty("success", success)
            if (error != null) addProperty("error", error)
        }.let { gson.toJson(it) }
    }

    /** 构造错误响应 JSON（Gson 序列化，转义安全；WsSession 异常兜底也复用此方法） */
    fun buildError(id: String, error: String, message: String): String {
        return JsonObject().apply {
            addProperty("type", "error")
            addProperty("id", id)
            addProperty("error", error)
            addProperty("message", message)
        }.let { gson.toJson(it) }
    }

    private fun buildWhitelistResult(
        id: String, action: String, success: Boolean,
        playerName: String, error: String? = null
    ): String {
        return JsonObject().apply {
            addProperty("type", "whitelist_result")
            addProperty("id", id)
            addProperty("action", action)
            addProperty("success", success)
            addProperty("player_name", playerName)
            if (error != null) addProperty("error", error)
        }.let { gson.toJson(it) }
    }
}
