package com.vanillawhitelist.plugin

import com.vanillawhitelist.plugin.command.PluginCommands
import com.vanillawhitelist.plugin.config.PluginConfig
import com.vanillawhitelist.plugin.data.PlayerTracker
import com.vanillawhitelist.plugin.data.StatsCollector
import com.vanillawhitelist.plugin.data.WorldTracker
import com.vanillawhitelist.plugin.ws.MessageHandler
import com.vanillawhitelist.plugin.ws.WsServer
import org.bukkit.plugin.java.JavaPlugin

class VanillaWhitelistPlugin : JavaPlugin() {

    lateinit var pluginConfig: PluginConfig
        private set
    lateinit var database: DatabaseManager
        private set
    lateinit var wsServer: WsServer
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

        // 4. 初始化 WebSocket 服务器
        wsServer = WsServer(this)

        // 5. 初始化数据追踪器
        playerTracker = PlayerTracker(this)
        worldTracker = WorldTracker(this)
        statsCollector = StatsCollector(this)

        // 6. 注册命令
        getCommand("vanillawhitelist")?.setExecutor(PluginCommands(this))
        getCommand("vwl")?.setExecutor(PluginCommands(this))

        // 7. 注册事件监听器
        server.pluginManager.registerEvents(playerTracker, this)
        server.pluginManager.registerEvents(worldTracker, this)

        // 8. 启动 WebSocket 服务器
        if (pluginConfig.websocketEnabled) {
            wsServer.start()
        } else {
            logger.info("WebSocket server is disabled in config.")
        }

        // 9. 启动定时任务
        statsCollector.startPeriodicTasks()

        logger.info("VanillaWhitelist Plugin v${pluginMeta.version} enabled!")
    }

    override fun onDisable() {
        statsCollector.cancelTasks()
        wsServer.stop()
        database.close()
        logger.info("VanillaWhitelist Plugin disabled!")
    }
}
