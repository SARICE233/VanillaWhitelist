package com.vanillawhitelist.plugin.config

import org.bukkit.plugin.java.JavaPlugin

class PluginConfig(private val plugin: JavaPlugin) {

    /**
     * 连接模式：
     * - server = 本端监听端口，网站主动连过来（默认，需要开放端口）
     * - client = 本端主动连网站，不需要开放任何入站端口
     */
    var websocketMode: String = "server"
        private set

    /** 出站模式（mode=client）下要连接的网站地址，如 wss://example.com/vwl */
    var websocketUrl: String = ""
        private set

    var websocketPort: Int = 25585
        private set
    var websocketSecret: String = "change-me-to-a-random-string"
        private set
    var websocketEnabled: Boolean = true
        private set
    var websocketHost: String = "0.0.0.0"
        private set

    var pushIntervalSeconds: Int = 30
        private set
    var worldStatsIntervalSeconds: Int = 300
        private set
    var playerStatsIntervalSeconds: Int = 600
        private set

    var serverId: String = "main"
        private set

    var alertsEnabled: Boolean = true
        private set
    var tpsWarning: Double = 15.0
        private set
    var tpsCritical: Double = 10.0
        private set
    var memoryPercentWarning: Double = 85.0
        private set
    var memoryPercentCritical: Double = 95.0
        private set
    var alertCooldownSeconds: Int = 300
        private set

    var debug: Boolean = false
        private set

    fun load() {
        plugin.saveDefaultConfig()
        plugin.reloadConfig()
        val config = plugin.config

        websocketMode = config.getString("websocket.mode", "server") ?: "server"
        websocketUrl = config.getString("websocket.url", "") ?: ""
        websocketPort = config.getInt("websocket.port", 25585)
        websocketSecret = config.getString("websocket.secret", "change-me-to-a-random-string")
            ?: "change-me-to-a-random-string"
        websocketEnabled = config.getBoolean("websocket.enabled", true)
        websocketHost = config.getString("websocket.host", "0.0.0.0") ?: "0.0.0.0"

        pushIntervalSeconds = config.getInt("stats.push-interval-seconds", 30)
        worldStatsIntervalSeconds = config.getInt("stats.world-stats-interval-seconds", 300)
        playerStatsIntervalSeconds = config.getInt("stats.player-stats-interval-seconds", 600)

        serverId = config.getString("server.id", "main") ?: "main"

        alertsEnabled = config.getBoolean("alerts.enabled", true)
        tpsWarning = config.getDouble("alerts.tps-warning", 15.0)
        tpsCritical = config.getDouble("alerts.tps-critical", 10.0)
        memoryPercentWarning = config.getDouble("alerts.memory-percent-warning", 85.0)
        memoryPercentCritical = config.getDouble("alerts.memory-percent-critical", 95.0)
        alertCooldownSeconds = config.getInt("alerts.cooldown-seconds", 300)

        debug = config.getBoolean("debug", false)

        val target = if (websocketMode.equals("client", ignoreCase = true)) websocketUrl else (websocketHost + ":" + websocketPort)
        plugin.logger.info("Config loaded: mode=" + websocketMode + ", target=" + target + ", debug=" + debug)
    }
}
