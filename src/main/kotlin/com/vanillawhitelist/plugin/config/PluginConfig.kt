package com.vanillawhitelist.plugin.config

import org.bukkit.plugin.java.JavaPlugin

class PluginConfig(private val plugin: JavaPlugin) {

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

    var debug: Boolean = false
        private set

    fun load() {
        plugin.saveDefaultConfig()
        plugin.reloadConfig()
        val config = plugin.config

        websocketPort = config.getInt("websocket.port", 25585)
        websocketSecret = config.getString("websocket.secret", "change-me-to-a-random-string")
            ?: "change-me-to-a-random-string"
        websocketEnabled = config.getBoolean("websocket.enabled", true)
        websocketHost = config.getString("websocket.host", "0.0.0.0") ?: "0.0.0.0"

        pushIntervalSeconds = config.getInt("stats.push-interval-seconds", 30)
        worldStatsIntervalSeconds = config.getInt("stats.world-stats-interval-seconds", 300)
        playerStatsIntervalSeconds = config.getInt("stats.player-stats-interval-seconds", 600)

        debug = config.getBoolean("debug", false)

        plugin.logger.info("Config loaded: ws=${websocketHost}:${websocketPort}, debug=${debug}")
    }
}
