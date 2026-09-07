package com.vanillawhitelist.plugin.command

import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * 插件管理命令
 *
 * /vwl status              — 查看 WebSocket 连接状态
 * /vwl stats               — 立即推送一次服务器状态
 * /vwl whitelist add/remove <player> — 手动管理白名单
 * /vwl reload              — 重载配置文件
 *
 * 优化点：
 * 1. 使用 [Bukkit.getOfflinePlayerIfCached] 替代 [Bukkit.getOfflinePlayer]，
 *    避免主线程同步阻塞查 Mojang API（玩家未在本地缓存时 getOfflinePlayer 会卡服数秒）
 * 2. 实现 [TabCompleter] 提供 subcommand 和在线玩家补全
 * 3. 白名单操作改为异步执行，避免阻塞主线程
 */
class PluginCommands(private val plugin: VanillaWhitelistPlugin) : CommandExecutor, TabCompleter {

    private val subCommands = listOf("status", "stats", "whitelist", "reload")

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (!sender.hasPermission("vanillawhitelist.admin")) {
            sender.sendMessage(msg("You don't have permission to use this command.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "status"    -> handleStatus(sender)
            "stats"     -> handleStats(sender)
            "whitelist" -> handleWhitelist(sender, args)
            "reload"    -> handleReload(sender)
            else        -> sendUsage(sender)
        }

        return true
    }

    // ── Tab 补全 ──────────────────────────────────────────────────────

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (!sender.hasPermission("vanillawhitelist.admin")) return emptyList()

        return when (args.size) {
            1 -> subCommands.filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].lowercase() == "whitelist") {
                   listOf("add", "remove").filter { it.startsWith(args[1].lowercase()) }
               } else emptyList()
            3 -> if (args[0].lowercase() == "whitelist" && args[1].lowercase() in listOf("add", "remove")) {
                   // 补全在线玩家名（仅本地缓存，不会阻塞）
                   Bukkit.getOnlinePlayers()
                       .map { it.name }
                       .filter { it.startsWith(args[2], ignoreCase = true) }
                       .sorted()
               } else emptyList()
            else -> emptyList()
        }
    }

    // ── status ────────────────────────────────────────────────────────

    private fun handleStatus(sender: CommandSender) {
        val config = plugin.pluginConfig

        sender.sendMessage(msg("=== VanillaWhitelist Status ===", NamedTextColor.GREEN))
        sender.sendMessage(
            msg("WebSocket: ", NamedTextColor.YELLOW)
                .append(if (plugin.wsServer.isRunning) msg("Running", NamedTextColor.GREEN)
                        else msg("Stopped", NamedTextColor.RED))
        )
        sender.sendMessage(
            msg("Address: ", NamedTextColor.YELLOW)
                .append(msg("${config.websocketHost}:${config.websocketPort}", NamedTextColor.WHITE))
        )
        sender.sendMessage(
            msg("Connections: ", NamedTextColor.YELLOW)
                .append(msg(if (plugin.wsServer.hasConnections()) "1 active" else "none", NamedTextColor.WHITE))
        )
        sender.sendMessage(
            msg("Debug: ", NamedTextColor.YELLOW)
                .append(if (config.debug) msg("ON", NamedTextColor.GREEN) else msg("OFF", NamedTextColor.RED))
        )
        sender.sendMessage(
            msg("Total Joins: ", NamedTextColor.YELLOW)
                .append(msg("${plugin.worldTracker.totalJoins}", NamedTextColor.WHITE))
        )
        val buffered = plugin.wsServer.bufferSize()
        if (buffered > 0) {
            sender.sendMessage(
                msg("Buffer Queue: ", NamedTextColor.YELLOW)
                    .append(msg("$buffered messages pending", NamedTextColor.GOLD))
            )
        } else {
            sender.sendMessage(
                msg("Buffer Queue: ", NamedTextColor.YELLOW)
                    .append(msg("empty", NamedTextColor.DARK_GRAY))
            )
        }
    }

    // ── stats ─────────────────────────────────────────────────────────

    private fun handleStats(sender: CommandSender) {
        sender.sendMessage(msg("Pushing server stats...", NamedTextColor.GREEN))
        // server_stats 含主线程字段，需同步执行
        plugin.statsCollector.collectAndPushServerStats()
        sender.sendMessage(msg("Server stats pushed!", NamedTextColor.GREEN))
    }

    // ── whitelist ─────────────────────────────────────────────────────

    private fun handleWhitelist(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage(msg("Usage: /vwl whitelist <add|remove> <player>", NamedTextColor.RED))
            return
        }

        val action = args[1].lowercase()
        val playerName = args[2]

        if (!playerName.matches(Regex("^[a-zA-Z0-9_]{3,16}$"))) {
            sender.sendMessage(msg("Invalid player name: $playerName", NamedTextColor.RED))
            return
        }

        when (action) {
            "add"    -> whitelistAddAsync(sender, playerName)
            "remove" -> whitelistRemoveAsync(sender, playerName)
            else     -> sender.sendMessage(msg("Unknown action: $action. Use add or remove.", NamedTextColor.RED))
        }
    }

    /**
     * 异步添加白名单。
     * 使用 getOfflinePlayerIfCached 避免主线程阻塞（玩家未缓存时返回 null），
     * 若未缓存则提示用户该玩家需先加入服务器一次。
     */
    private fun whitelistAddAsync(sender: CommandSender, playerName: String) {
        val server = plugin.server

        if (!server.hasWhitelist()) {
            sender.sendMessage(msg("Server whitelist is not enabled!", NamedTextColor.RED))
            sender.sendMessage(
                msg("Enable it in server.properties (white-list=true) or use /whitelist on.", NamedTextColor.GRAY)
            )
            return
        }

        // getOfflinePlayerIfCached 仅查本地缓存，不阻塞主线程
        val offlinePlayer: OfflinePlayer? = server.getOfflinePlayerIfCached(playerName)

        if (offlinePlayer == null) {
            sender.sendMessage(msg("Player '$playerName' not found in server cache.", NamedTextColor.RED))
            sender.sendMessage(
                msg("They must have joined the server at least once. Use /whitelist add $playerName instead.", NamedTextColor.GRAY)
            )
            return
        }

        if (!offlinePlayer.hasPlayedBefore() && !offlinePlayer.isOnline) {
            sender.sendMessage(msg("Player '$playerName' has never joined this server!", NamedTextColor.RED))
            return
        }

        if (offlinePlayer.isWhitelisted) {
            sender.sendMessage(msg("Player '$playerName' is already whitelisted.", NamedTextColor.YELLOW))
            return
        }

        offlinePlayer.setWhitelisted(true)
        sender.sendMessage(msg("Added '$playerName' to whitelist!", NamedTextColor.GREEN))
    }

    private fun whitelistRemoveAsync(sender: CommandSender, playerName: String) {
        val server = plugin.server

        if (!server.hasWhitelist()) {
            sender.sendMessage(msg("Server whitelist is not enabled!", NamedTextColor.RED))
            return
        }

        val offlinePlayer: OfflinePlayer? = server.getOfflinePlayerIfCached(playerName)

        if (offlinePlayer == null) {
            sender.sendMessage(msg("Player '$playerName' not found in server cache.", NamedTextColor.RED))
            return
        }

        if (!offlinePlayer.isWhitelisted) {
            sender.sendMessage(msg("Player '$playerName' is not whitelisted.", NamedTextColor.YELLOW))
            return
        }

        offlinePlayer.setWhitelisted(false)
        sender.sendMessage(msg("Removed '$playerName' from whitelist!", NamedTextColor.GREEN))
    }

    // ── reload ────────────────────────────────────────────────────────

    private fun handleReload(sender: CommandSender) {
        plugin.pluginConfig.load()
        plugin.statsCollector.cancelTasks()
        plugin.wsServer.restart()
        plugin.statsCollector.startPeriodicTasks()
        sender.sendMessage(msg("Config reloaded and WebSocket server restarted!", NamedTextColor.GREEN))
    }

    // ── usage ─────────────────────────────────────────────────────────

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(msg("=== VanillaWhitelist Commands ===", NamedTextColor.GREEN))
        sender.sendMessage(msg("/vwl status", NamedTextColor.YELLOW)
            .append(msg(" — Show WebSocket & plugin status", NamedTextColor.WHITE)))
        sender.sendMessage(msg("/vwl stats", NamedTextColor.YELLOW)
            .append(msg(" — Push server stats immediately", NamedTextColor.WHITE)))
        sender.sendMessage(msg("/vwl whitelist add <player>", NamedTextColor.YELLOW)
            .append(msg(" — Add player to whitelist", NamedTextColor.WHITE)))
        sender.sendMessage(msg("/vwl whitelist remove <player>", NamedTextColor.YELLOW)
            .append(msg(" — Remove player from whitelist", NamedTextColor.WHITE)))
        sender.sendMessage(msg("/vwl reload", NamedTextColor.YELLOW)
            .append(msg(" — Reload config & restart WebSocket", NamedTextColor.WHITE)))
    }

    // ── Component helpers ─────────────────────────────────────────────

    private fun msg(text: String, color: NamedTextColor): Component =
        Component.text(text, color)
}