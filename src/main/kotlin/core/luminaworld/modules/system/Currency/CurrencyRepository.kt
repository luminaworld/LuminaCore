package core.luminaworld.modules.system.Currency

import core.luminaworld.LuminaCore
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** SQL layer only; all access is dispatched through DatabaseService asynchronously. */
class CurrencyRepository(private val plugin: LuminaCore) {
    private val db get() = plugin.databaseService
    private val prefix get() = db?.tablePrefix ?: "lumina_"
    private val balancesTable get() = "${prefix}currency_balances"
    private val transactionsTable get() = "${prefix}currency_transactions"

    fun createTables() {
        db?.runAsync { conn ->
            try {
                conn.createStatement().use { statement ->
                    statement.execute("""CREATE TABLE IF NOT EXISTS $balancesTable (
                        currency_id VARCHAR(64) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(64) NOT NULL, amount_minor BIGINT NOT NULL DEFAULT 0,
                        updated_at BIGINT NOT NULL, PRIMARY KEY (currency_id, player_uuid))""")
                    statement.execute("""CREATE TABLE IF NOT EXISTS $transactionsTable (
                        id INTEGER PRIMARY KEY ${if (isMySql(conn)) "AUTO_INCREMENT" else "AUTOINCREMENT"},
                        currency_id VARCHAR(64) NOT NULL, operation VARCHAR(16) NOT NULL,
                        actor_uuid VARCHAR(36), from_uuid VARCHAR(36), to_uuid VARCHAR(36),
                        amount_minor BIGINT NOT NULL, reason VARCHAR(128), created_at BIGINT NOT NULL)""")
                    
                    if (isMySql(conn)) {
                        // สำหรับ MySQL: ตรวจสอบและสร้าง index โดยไม่มี IF NOT EXISTS เพื่อหลีกเลี่ยง Syntax error
                        var indexExists = false
                        try {
                            conn.metaData.getIndexInfo(null, null, balancesTable, false, false).use { rs ->
                                while (rs.next()) {
                                    if ("idx_lumina_currency_top".equals(rs.getString("INDEX_NAME"), ignoreCase = true)) {
                                        indexExists = true
                                        break
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                        if (!indexExists) {
                            statement.execute("CREATE INDEX idx_lumina_currency_top ON $balancesTable(currency_id, amount_minor)")
                        }
                    } else {
                        // สำหรับ SQLite หรืออื่นๆ: ใช้ IF NOT EXISTS ได้ตามปกติ
                        statement.execute("CREATE INDEX IF NOT EXISTS idx_lumina_currency_top ON $balancesTable(currency_id, amount_minor)")
                    }
                }
            } catch (e: SQLException) { plugin.logger.severe("[Currency] Cannot create database tables: ${e.message}") }
        }
    }

    fun getBalance(player: UUID, currency: String, callback: (Long) -> Unit) = db?.runAsync { conn ->
        callback(readBalance(conn, player, currency))
    }

    fun change(player: CurrencyPlayer, currency: String, amount: Long, operation: String, actor: UUID?, reason: String?, minBalance: Long, maxBalance: Long, callback: (CurrencyOperationResult) -> Unit) = db?.runAsync { conn ->
        synchronized(conn) {
            try {
                val old = readBalance(conn, player.uuid, currency)
                val new = when (operation) { "GIVE" -> Math.addExact(old, amount); "TAKE" -> old - amount; else -> amount }
                if (new < minBalance) { callback(CurrencyOperationResult(CurrencyResult.INSUFFICIENT_FUNDS, old)); return@runAsync }
                if (maxBalance > 0L && new > maxBalance) { callback(CurrencyOperationResult(CurrencyResult.LIMIT_EXCEEDED, old)); return@runAsync }
                writeBalance(conn, player, currency, new)
                log(conn, currency, operation, actor, if (operation == "TAKE") player.uuid else null, if (operation == "GIVE") player.uuid else null, amount, reason)
                callback(CurrencyOperationResult(CurrencyResult.SUCCESS, new))
            } catch (e: Exception) { plugin.logger.severe("[Currency] Change failed: ${e.message}"); callback(CurrencyOperationResult(CurrencyResult.DATABASE_ERROR)) }
        }
    }

    fun transfer(from: CurrencyPlayer, to: CurrencyPlayer, currency: String, amount: Long, minBalance: Long, maxBalance: Long, callback: (CurrencyOperationResult, Long) -> Unit) = db?.runAsync { conn ->
        synchronized(conn) {
            val oldAutoCommit = conn.autoCommit
            try {
                conn.autoCommit = false
                val senderBalance = readBalance(conn, from.uuid, currency)
                if (senderBalance - amount < minBalance) { conn.rollback(); callback(CurrencyOperationResult(CurrencyResult.INSUFFICIENT_FUNDS, senderBalance), 0); return@runAsync }
                val receiverBalance = readBalance(conn, to.uuid, currency)
                val newReceiverBalance = Math.addExact(receiverBalance, amount)
                if (maxBalance > 0L && newReceiverBalance > maxBalance) { conn.rollback(); callback(CurrencyOperationResult(CurrencyResult.LIMIT_EXCEEDED, senderBalance), 0); return@runAsync }
                
                writeBalance(conn, from, currency, senderBalance - amount)
                writeBalance(conn, to, currency, newReceiverBalance)
                log(conn, currency, "TRANSFER", from.uuid, from.uuid, to.uuid, amount, "player-transfer")
                conn.commit()
                callback(CurrencyOperationResult(CurrencyResult.SUCCESS, senderBalance - amount), newReceiverBalance)
            } catch (e: Exception) {
                try { conn.rollback() } catch (_: Exception) { }
                plugin.logger.severe("[Currency] Transfer failed: ${e.message}")
                callback(CurrencyOperationResult(CurrencyResult.DATABASE_ERROR), 0)
            } finally { try { conn.autoCommit = oldAutoCommit } catch (_: Exception) { } }
        }
    }

    fun top(currency: String, limit: Int, callback: (List<CurrencyLeaderboardEntry>) -> Unit) = db?.runAsync { conn ->
        val rows = mutableListOf<CurrencyLeaderboardEntry>()
        try {
            conn.prepareStatement("SELECT player_name, amount_minor FROM $balancesTable WHERE currency_id = ? ORDER BY amount_minor DESC, player_name ASC LIMIT ?").use { st ->
                st.setString(1, currency); st.setInt(2, limit)
                st.executeQuery().use { rs -> while (rs.next()) rows += CurrencyLeaderboardEntry(rs.getString(1), rs.getLong(2)) }
            }
        } catch (e: SQLException) { plugin.logger.warning("[Currency] Cannot refresh leaderboard: ${e.message}") }
        callback(rows)
    }

    private fun readBalance(conn: Connection, player: UUID, currency: String): Long {
        conn.prepareStatement("SELECT amount_minor FROM $balancesTable WHERE currency_id = ? AND player_uuid = ?").use { st ->
            st.setString(1, currency); st.setString(2, player.toString()); st.executeQuery().use { rs -> return if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    private fun writeBalance(conn: Connection, player: CurrencyPlayer, currency: String, amount: Long) {
        val exists = conn.prepareStatement("SELECT 1 FROM $balancesTable WHERE currency_id = ? AND player_uuid = ?").use { st ->
            st.setString(1, currency); st.setString(2, player.uuid.toString()); st.executeQuery().use { it.next() }
        }
        val now = System.currentTimeMillis()
        val sql = if (exists) "UPDATE $balancesTable SET player_name = ?, amount_minor = ?, updated_at = ? WHERE currency_id = ? AND player_uuid = ?" else
            "INSERT INTO $balancesTable (player_name, amount_minor, updated_at, currency_id, player_uuid) VALUES (?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { st ->
            st.setString(1, player.name); st.setLong(2, amount); st.setLong(3, now); st.setString(4, currency); st.setString(5, player.uuid.toString()); st.executeUpdate()
        }
    }

    private fun log(conn: Connection, currency: String, operation: String, actor: UUID?, from: UUID?, to: UUID?, amount: Long, reason: String?) {
        conn.prepareStatement("INSERT INTO $transactionsTable (currency_id, operation, actor_uuid, from_uuid, to_uuid, amount_minor, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { st ->
            st.setString(1, currency); st.setString(2, operation); st.setString(3, actor?.toString()); st.setString(4, from?.toString()); st.setString(5, to?.toString()); st.setLong(6, amount); st.setString(7, reason); st.setLong(8, System.currentTimeMillis()); st.executeUpdate()
        }
    }
    private fun isMySql(conn: Connection) = conn.metaData.databaseProductName.contains("mysql", true)
}
