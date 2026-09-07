package com.vanillawhitelist.plugin

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Level

/**
 * SQLite 数据库管理器
 *
 * 提供数据持久化能力：
 * - kv_store:       键值存储（如 totalJoins 计数器）
 * - message_queue:  消息缓冲队列（网站离线时暂存，重连后补发）
 *
 * 优化点：
 * 1. 所有 PreparedStatement/ResultSet 使用 .use { } 自动关闭，杜绝资源泄漏
 * 2. 全方法级 synchronized 保护：SQLite 单连接下，bulkUpsertLong 会切换
 *    autoCommit=false 开事务，若其他线程并发执行语句会被卷入该事务，
 *    rollback 时殃及无辜——因此每个公共方法整体互斥（SQLite 单写者，开销可忽略）
 * 3. 消息队列硬上限 [MAX_QUEUE_SIZE]，防止网站长期离线时无限增长
 * 4. 提供 [bulkUpsertLong] 批量写入接口，减少高频事件 IO 次数
 * 5. 启用 WAL 模式提升并发读写性能
 */
class DatabaseManager(private val plugin: VanillaWhitelistPlugin) {

    private var connection: Connection? = null
    private final val lock = Any()

    /** 消息队列硬上限，超出时丢弃最旧消息 */
    private val MAX_QUEUE_SIZE = 10000

    // ── 生命周期 ──────────────────────────────────────────────────────

    fun open() {
        try {
            Class.forName("org.sqlite.JDBC")
            val dbFile = File(plugin.dataFolder, "data.db")
            dbFile.parentFile?.mkdirs()
            connection = DriverManager.getConnection("jdbc:sqlite:$dbFile").apply {
                // 启用 WAL 模式，提升并发读写性能
                createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
                // NORMAL synchronous 在 WAL 下足够安全，且大幅提升写入速度
                createStatement().use { it.execute("PRAGMA synchronous=NORMAL") }
                autoCommit = true
            }
            createTables()
            plugin.logger.info("SQLite database opened: ${dbFile.absolutePath}")
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to open SQLite database: ${e.message}", e)
        }
    }

    fun close() {
        synchronized(lock) {
            try {
                connection?.close()
                connection = null
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Error closing SQLite database: ${e.message}", e)
            }
        }
    }

    val isOpen: Boolean
        get() = synchronized(lock) { connection != null && connection?.isClosed == false }

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
        execute("CREATE INDEX IF NOT EXISTS idx_msg_created ON message_queue(created_at)")
        // 消息队列大小限制触发器：超出上限时删除最旧消息
        execute(
            """
            CREATE TRIGGER IF NOT EXISTS trg_msg_queue_limit
            AFTER INSERT ON message_queue
            BEGIN
                DELETE FROM message_queue
                WHERE id IN (
                    SELECT id FROM message_queue
                    ORDER BY id DESC
                    LIMIT -1 OFFSET $MAX_QUEUE_SIZE
                );
            END
            """
        )
    }

    // ── Key-Value 操作 ────────────────────────────────────────────────

    fun getLong(key: String, default: Long = 0L): Long {
        synchronized(lock) {
            val conn = connection ?: return default
            return try {
                conn.prepareStatement("SELECT value FROM kv_store WHERE key = ?").use { stmt ->
                    stmt.setString(1, key)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("value").toLong() else default
                    }
                }
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "DB getLong($key) failed: ${e.message}", e)
                default
            }
        }
    }

    fun setLong(key: String, value: Long) {
        synchronized(lock) {
            val conn = connection ?: return
            try {
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO kv_store (key, value) VALUES (?, ?)"
                ).use { stmt ->
                    stmt.setString(1, key)
                    stmt.setString(2, value.toString())
                    stmt.execute()
                }
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "DB setLong($key) failed: ${e.message}", e)
            }
        }
    }

    fun incrementLong(key: String, delta: Long = 1L): Long {
        synchronized(lock) {
            val conn = connection ?: return delta
            return try {
                conn.prepareStatement(
                    "INSERT INTO kv_store (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + ?"
                ).use { stmt ->
                    stmt.setString(1, key)
                    stmt.setString(2, delta.toString())
                    stmt.setString(3, delta.toString())
                    stmt.execute()
                }
                // synchronized 可重入，嵌套调用安全
                getLong(key)
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "DB incrementLong($key) failed: ${e.message}", e)
                delta
            }
        }
    }

    /**
     * 批量 upsert 多个键值（单事务），用于高频事件 flush。
     * 整体在锁内执行，保证事务期间不会有其他线程的语句混入本连接。
     * @param entries key 到增量值的映射
     */
    fun bulkUpsertLong(entries: Map<String, Long>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val conn = connection ?: return
            try {
                conn.autoCommit = false
                conn.prepareStatement(
                    "INSERT INTO kv_store (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + ?"
                ).use { stmt ->
                    for ((key, delta) in entries) {
                        val deltaStr = delta.toString()
                        stmt.setString(1, key)
                        stmt.setString(2, deltaStr)
                        stmt.setString(3, deltaStr)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                try { conn.rollback() } catch (_: Exception) {}
                plugin.logger.log(Level.WARNING, "DB bulkUpsertLong failed: ${e.message}", e)
            } finally {
                try { conn.autoCommit = true } catch (_: Exception) {}
            }
        }
    }

    // ── 消息队列操作 ──────────────────────────────────────────────────

    /** 将消息加入缓冲队列，返回队列中的消息总数 */
    fun enqueueMessage(json: String): Int {
        synchronized(lock) {
            val conn = connection ?: return 0
            return try {
                conn.prepareStatement(
                    "INSERT INTO message_queue (created_at, message) VALUES (?, ?)"
                ).use { stmt ->
                    stmt.setLong(1, System.currentTimeMillis())
                    stmt.setString(2, json)
                    stmt.executeUpdate()
                }
                // 触发器已自动裁剪超额记录；synchronized 可重入，嵌套调用安全
                queueSize()
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "DB enqueueMessage failed: ${e.message}", e)
                0
            }
        }
    }

    /** 取出所有缓冲消息（从旧到新），并清空队列 */
    fun dequeueAll(): List<String> {
        synchronized(lock) {
            val conn = connection ?: return emptyList()
            return try {
                val messages = mutableListOf<String>()
                conn.prepareStatement("SELECT message FROM message_queue ORDER BY id ASC").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) messages.add(rs.getString("message"))
                    }
                }
                conn.createStatement().use { it.execute("DELETE FROM message_queue") }
                messages
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "DB dequeueAll failed: ${e.message}", e)
                emptyList()
            }
        }
    }

    fun queueSize(): Int {
        synchronized(lock) {
            val conn = connection ?: return 0
            return try {
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM message_queue").use { rs ->
                        if (rs.next()) rs.getInt(1) else 0
                    }
                }
            } catch (e: Exception) {
                0
            }
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────

    private fun execute(sql: String) {
        synchronized(lock) {
            val conn = connection ?: return
            try {
                conn.createStatement().use { it.execute(sql) }
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "DB execute failed: ${e.message}", e)
            }
        }
    }
}