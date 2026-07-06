package com.vanillawhitelist.plugin.command

import com.vanillawhitelist.plugin.VanillaWhitelistPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

/**
 * 插件管理命令
 *
 * /vwl status              — 查看 WebSocket 连接状态
 * /vwl stats               — 立即推送一次服务器状态
 * /vwl whitelist add/remove <player> — 手动管理白名单
 * /vwl reload              — 重载配置文件
 */
class PluginCommands(private val plugin: VanillaWhitelistPlugin) : CommandExecutor {

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
            "add"    -> whitelistAdd(sender, playerName)
            "remove" -> whitelistRemove(sender, playerName)
            else     -> sender.sendMessage(msg("Unknown action: $action. Use add or remove.", NamedTextColor.RED))
        }
    }

    private fun whitelistAdd(sender: CommandSender, playerName: String) {
        val server = plugin.server

        if (!server.hasWhitelist()) {
            sender.sendMessage(msg("Server whitelist is not enabled!", NamedTextColor.RED))
            sender.sendMessage(
                msg("Enable it in server.properties (white-list=true) or use /whitelist on.", NamedTextColor.GRAY)
            )
            return
        }

        val offlinePlayer = server.getOfflinePlayer(playerName)

        // 检查玩家是否曾经加入过服务器
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

    private fun whitelistRemove(sender: CommandSender, playerName: String) {
        val server = plugin.server

        if (!server.hasWhitelist()) {
            sender.sendMessage(msg("Server whitelist is not enabled!", NamedTextColor.RED))
            return
        }

        val offlinePlayer = server.getOfflinePlayer(playerName)

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

    private fun Component.append(other: Component): Component =
        this.append(other)
}
