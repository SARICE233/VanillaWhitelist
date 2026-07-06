package com.vanillawhitelist.plugin

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.logging.Level

/**
 * SQLite 数据库管理器
 *
 * 提供数据持久化能力：
 * - kv_store:    键值存储（如 totalJoins 计数器）
 * - message_queue: 消息缓冲队列（网站离线时暂存，重连后补发）
 */
class DatabaseManager(private val plugin: VanillaWhitelistPlugin) {

    private var connection: Connection? = null

    // ── 生命周期 ──────────────────────────────────────────────────────

    fun open() {
        try {
            Class.forName("org.sqlite.JDBC")
            val dbFile = File(plugin.dataFolder, "data.db")
            // 确保父目录存在
            dbFile.parentFile?.mkdirs()
            connection = DriverManager.getConnection("jdbc:sqlite:$dbFile")
            createTables()
            plugin.logger.info("SQLite database opened: ${dbFile.absolutePath}")
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to open SQLite database: ${e.message}", e)
        }
    }

    fun close() {
        try {
            connection?.close()
            connection = null
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Error closing SQLite database: ${e.message}", e)
        }
    }

    val isOpen: Boolean get() = connection != null && connection?.isClosed == false

    // ── 建表 ──────────────────────────────────────────────────────────

    private fun createTables() {
        execute(
            """
            CREATE TABLE IF NOT EXISTS kv_store (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """
        )
        execute(
            """
            CREATE TABLE IF NOT EXISTS message_queue (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                message    TEXT    NOT NULL
            )
            """
        )
        // 为 message_queue 创建索引加速查询
        execute("CREATE INDEX IF NOT EXISTS idx_msg_created ON message_queue(created_at)")
    }

    // ── Key-Value 操作 ────────────────────────────────────────────────

    fun getLong(key: String, default: Long = 0L): Long {
        val conn = connection ?: return default
        return try {
            val stmt = conn.prepareStatement("SELECT value FROM kv_store WHERE key = ?")
            stmt.setString(1, key)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getString("value").toLong() else default
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "DB getLong($key) failed: ${e.message}", e)
            default
        }
    }

    fun setLong(key: String, value: Long) {
        execute(
            "INSERT OR REPLACE INTO kv_store (key, value) VALUES (?, ?)",
            key, value.toString()
        )
    }

    fun incrementLong(key: String, delta: Long = 1L): Long {
        val conn = connection ?: return delta
        return try {
            // 使用 SQLite 的原子操作
            execute(
                "INSERT INTO kv_store (key, value) VALUES (?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + ?",
                key, delta.toString(), delta.toString()
            )
            getLong(key)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "DB incrementLong($key) failed: ${e.message}", e)
            delta
        }
    }

    // ── 消息队列操作 ──────────────────────────────────────────────────

    /** 将消息加入缓冲队列，返回队列中的消息总数 */
    fun enqueueMessage(json: String): Int {
        val conn = connection ?: return 0
        return try {
            val stmt = conn.prepareStatement(
                "INSERT INTO message_queue (created_at, message) VALUES (?, ?)"
            )
            stmt.setLong(1, System.currentTimeMillis())
            stmt.setString(2, json)
            stmt.executeUpdate()

            // 返回当前队列大小
            val countStmt = conn.createStatement()
            val rs = countStmt.executeQuery("SELECT COUNT(*) FROM message_queue")
            if (rs.next()) rs.getInt(1) else 0
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "DB enqueueMessage failed: ${e.message}", e)
            0
        }
    }

    /** 取出所有缓冲消息（从旧到新），并清空队列 */
    fun dequeueAll(): List<String> {
        val conn = connection ?: return emptyList()
        return try {
            val messages = mutableListOf<String>()
            val stmt = conn.prepareStatement(
                "SELECT message FROM message_queue ORDER BY id ASC"
            )
            val rs = stmt.executeQuery()
            while (rs.next()) {
                messages.add(rs.getString("message"))
            }
            // 清空队列
            execute("DELETE FROM message_queue")
            messages
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "DB dequeueAll failed: ${e.message}", e)
            emptyList()
        }
    }

    fun queueSize(): Int {
        val conn = connection ?: return 0
        return try {
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT COUNT(*) FROM message_queue")
            if (rs.next()) rs.getInt(1) else 0
        } catch (e: Exception) {
            0
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────

    private fun execute(sql: String, vararg params: String) {
        val conn = connection ?: return
        try {
            if (params.isEmpty()) {
                conn.createStatement().use { it.execute(sql) }
            } else {
                conn.prepareStatement(sql).use { stmt ->
                    params.forEachIndexed { i, p -> stmt.setString(i + 1, p) }
                    stmt.execute()
                }
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "DB execute failed: ${e.message}", e)
        }
    }
}
