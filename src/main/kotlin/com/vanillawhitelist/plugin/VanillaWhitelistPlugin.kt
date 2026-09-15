package com.vanillawhitelist.plugin

import com.vanillawhitelist.plugin.command.PluginCommands
import com.vanillawhitelist.plugin.config.PluginConfig
import com.vanillawhitelist.plugin.data.PlayerTracker
import com.vanillawhitelist.plugin.data.StatsCollector
import com.vanillawhitelist.plugin.data.WorldTracker
import com.vanillawhitelist.plugin.ws.MessageHandler
import com.vanillawhitelist.plugin.ws.Transport
import com.vanillawhitelist.plugin.ws.WsClient
import com.vanillawhitelist.plugin.ws.WsServer
import org.bukkit.plugin.java.JavaPlugin

class VanillaWhitelistPlugin : JavaPlugin() {

    lateinit var pluginConfig: PluginConfig
        private set
    lateinit var database: DatabaseManager
        private set
    /** 传输层：按配置在「入站 (server)」与「出站 (client)」之间切换 */
    lateinit var transport: Transport
        private set
    lateinit var messageHandler: MessageHandler
        private set
    lateinit var statsCollector: StatsCollector
        private set
    lateinit var playerTracker: PlayerTracker
        private set
    lateinit var worldTracker: WorldTracker
        private set

    override fun onEnable() {
        // 1. 加载配置
        pluginConfig = PluginConfig(this)
        pluginConfig.load()

        // 2. 初始化数据库
        database = DatabaseManager(this)
        database.open()

        // 3. 初始化消息处理器
        messageHandler = MessageHandler(this)

        // 4. 初始化传输层（按 mode 选择入站或出站）
        transport = if (pluginConfig.websocketMode.equals("client", ignoreCase = true)) {
            WsClient(this)
        } else {
            WsServer(this)
        }

        // 5. 初始化数据追踪器
        playerTracker = PlayerTracker(this)
        worldTracker = WorldTracker(this)
        statsCollector = StatsCollector(this)

        // 6. 注册命令（同时作为 TabCompleter）
        val commands = PluginCommands(this)
        getCommand("vanillawhitelist")?.let { cmd ->
            cmd.setExecutor(commands)
            cmd.tabCompleter = commands
        }
        getCommand("vwl")?.let { cmd ->
            cmd.setExecutor(commands)
            cmd.tabCompleter = commands
        }

        // 7. 注册事件监听器
        server.pluginManager.registerEvents(playerTracker, this)
        server.pluginManager.registerEvents(worldTracker, this)

        // 8. 启动传输层
        if (pluginConfig.websocketEnabled) {
            transport.start()
        } else {
            logger.info("WebSocket is disabled in config.")
        }

        // 9. 启动定时任务（含 WorldTracker 的异步 flush 任务）
        statsCollector.startPeriodicTasks()
        worldTracker.startFlushTask()

        logger.info("VanillaWhitelist Plugin v${pluginMeta.version} enabled!")
    }

    override fun onDisable() {
        // 先停止 flush 任务并执行最后一次 flush，确保数据落盘
        worldTracker.stopFlushTask()
        statsCollector.cancelTasks()
        transport.stop()
        database.close()
        logger.info("VanillaWhitelist Plugin disabled!")
    }
}