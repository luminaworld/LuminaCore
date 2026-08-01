package core.luminaworld.modules.minigames.ChatGames

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

class ChatGamesDatabase(private val module: ChatGamesModule) {
    private val dbService get() = module.plugin.databaseService
    private val tableName get(): String {
        val prefix = dbService?.tablePrefix ?: "lumina_"
        val baseName = module.chatConfig.config.getString("table_name", "chatgames") ?: "chatgames"
        return "$prefix$baseName"
    }

    /**
     * สร้างตารางฐานข้อมูลย่อยของ ChatGames หากยังไม่มี
     */
    fun createTable() {
        dbService?.runAsync { conn ->
            val sql = if (isMySQL(conn)) {
                """
                CREATE TABLE IF NOT EXISTS $tableName (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    player_name VARCHAR(16) NOT NULL,
                    points INT DEFAULT 0,
                    fastest_time DOUBLE DEFAULT -1.0,
                    total_wins INT DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """.trimIndent()
            } else {
                """
                CREATE TABLE IF NOT EXISTS $tableName (
                    player_uuid TEXT PRIMARY KEY,
                    player_name TEXT NOT NULL,
                    points INTEGER DEFAULT 0,
                    fastest_time REAL DEFAULT -1.0,
                    total_wins INTEGER DEFAULT 0
                );
                """.trimIndent()
            }
            
            try {
                conn.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
            } catch (e: SQLException) {
                module.plugin.logger.severe("[ChatGames-DB] Failed to create table: ${e.message}")
            }
        }
    }

    /**
     * ตรวจสอบว่าเป็นฐานข้อมูล MySQL หรือไม่ (หากไม่ใช่ จะมองเป็น SQLite)
     */
    private fun isMySQL(conn: Connection): Boolean {
        return conn.metaData.databaseProductName.contains("MySQL", ignoreCase = true)
    }

    /**
     * บันทึกชัยชนะและสถิติของผู้เล่นแบบ Asynchronous
     */
    fun recordWin(playerUuid: UUID, playerName: String, pointsToAdd: Int, timeTaken: Double) {
        dbService?.runAsync { conn ->
            val selectSql = "SELECT points, fastest_time, total_wins FROM $tableName WHERE player_uuid = ?"
            var existingPoints = 0
            var existingFastest = -1.0
            var existingWins = 0
            var hasRecord = false

            try {
                conn.prepareStatement(selectSql).use { stmt ->
                    stmt.setString(1, playerUuid.toString())
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            existingPoints = rs.getInt("points")
                            existingFastest = rs.getDouble("fastest_time")
                            existingWins = rs.getInt("total_wins")
                            hasRecord = true
                        }
                    }
                }

                val newPoints = existingPoints + pointsToAdd
                val newWins = existingWins + 1
                val newFastest = if (existingFastest <= 0.0) {
                    timeTaken
                } else {
                    Math.min(existingFastest, timeTaken)
                }

                if (hasRecord) {
                    val updateSql = "UPDATE $tableName SET player_name = ?, points = ?, fastest_time = ?, total_wins = ? WHERE player_uuid = ?"
                    conn.prepareStatement(updateSql).use { stmt ->
                        stmt.setString(1, playerName)
                        stmt.setInt(2, newPoints)
                        stmt.setDouble(3, newFastest)
                        stmt.setInt(4, newWins)
                        stmt.setString(5, playerUuid.toString())
                        stmt.executeUpdate()
                    }
                } else {
                    val insertSql = "INSERT INTO $tableName (player_uuid, player_name, points, fastest_time, total_wins) VALUES (?, ?, ?, ?, ?)"
                    conn.prepareStatement(insertSql).use { stmt ->
                        stmt.setString(1, playerUuid.toString())
                        stmt.setString(2, playerName)
                        stmt.setInt(3, newPoints)
                        stmt.setDouble(4, newFastest)
                        stmt.setInt(5, newWins)
                        stmt.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                module.plugin.logger.severe("[ChatGames-DB] Failed to record win for $playerName: ${e.message}")
            }
        }
    }

    /**
     * ดึงแต้มของผู้เล่น
     */
    fun getPoints(playerUuid: UUID, callback: (Int) -> Unit) {
        dbService?.runAsync { conn ->
            val sql = "SELECT points FROM $tableName WHERE player_uuid = ?"
            var points = 0
            try {
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, playerUuid.toString())
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            points = rs.getInt("points")
                        }
                    }
                }
            } catch (e: SQLException) {
                module.plugin.logger.severe("[ChatGames-DB] Failed to get points for $playerUuid: ${e.message}")
            }
            callback(points)
        }
    }

    /**
     * แก้ไขแต้มของผู้เล่น (บวก/ลบ/กำหนดค่าใหม่)
     */
    fun updatePoints(playerUuid: UUID, playerName: String, amount: Int, type: String, callback: (Int) -> Unit) {
        dbService?.runAsync { conn ->
            val selectSql = "SELECT points FROM $tableName WHERE player_uuid = ?"
            var currentPoints = 0
            var hasRecord = false

            try {
                conn.prepareStatement(selectSql).use { stmt ->
                    stmt.setString(1, playerUuid.toString())
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            currentPoints = rs.getInt("points")
                            hasRecord = true
                        }
                    }
                }

                val newPoints = when (type.uppercase()) {
                    "ADD" -> currentPoints + amount
                    "REMOVE" -> Math.max(0, currentPoints - amount)
                    "SET" -> Math.max(0, amount)
                    else -> currentPoints
                }

                if (hasRecord) {
                    val updateSql = "UPDATE $tableName SET player_name = ?, points = ? WHERE player_uuid = ?"
                    conn.prepareStatement(updateSql).use { stmt ->
                        stmt.setString(1, playerName)
                        stmt.setInt(2, newPoints)
                        stmt.setString(3, playerUuid.toString())
                        stmt.executeUpdate()
                    }
                } else {
                    val insertSql = "INSERT INTO $tableName (player_uuid, player_name, points, fastest_time, total_wins) VALUES (?, ?, ?, -1.0, 0)"
                    conn.prepareStatement(insertSql).use { stmt ->
                        stmt.setString(1, playerUuid.toString())
                        stmt.setString(2, playerName)
                        stmt.setInt(3, newPoints)
                        stmt.executeUpdate()
                    }
                }
                callback(newPoints)
            } catch (e: SQLException) {
                module.plugin.logger.severe("[ChatGames-DB] Failed to update points for $playerName: ${e.message}")
                callback(0)
            }
        }
    }

    /**
     * ลบข้อมูลผู้เล่นออกจากฐานข้อมูล
     */
    fun deletePlayer(playerName: String, callback: (Boolean) -> Unit) {
        dbService?.runAsync { conn ->
            val sql = "DELETE FROM $tableName WHERE player_name = ?"
            var success = false
            try {
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, playerName)
                    val affected = stmt.executeUpdate()
                    success = affected > 0
                }
            } catch (e: SQLException) {
                module.plugin.logger.severe("[ChatGames-DB] Failed to delete player $playerName: ${e.message}")
            }
            callback(success)
        }
    }

    /**
     * ดึงรายชื่อ 10 อันดับแรกที่มีคะแนนสูงสุด
     * คืนค่า List ของ Triple(อันดับ, ชื่อผู้เล่น, แต้ม)
     */
    fun getTopPoints(callback: (List<Triple<Int, String, Int>>) -> Unit) {
        dbService?.runAsync { conn ->
            val sql = "SELECT player_name, points FROM $tableName ORDER BY points DESC LIMIT 10"
            val result = ArrayList<Triple<Int, String, Int>>()
            try {
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        var spot = 1
                        while (rs.next()) {
                            val name = rs.getString("player_name")
                            val points = rs.getInt("points")
                            result.add(Triple(spot++, name, points))
                        }
                    }
                }
            } catch (e: SQLException) {
                module.plugin.logger.severe("[ChatGames-DB] Failed to fetch top points: ${e.message}")
            }
            callback(result)
        }
    }

    /**
     * ดึงรายชื่อ 10 อันดับแรกที่มีเวลาพิมพ์เร็วที่สุด
     * คืนค่า List ของ Triple(อันดับ, ชื่อผู้เล่น, เวลาเป็นวินาที)
     */
    fun getTopFastest(callback: (List<Triple<Int, String, Double>>) -> Unit) {
        dbService?.runAsync { conn ->
            val sql = "SELECT player_name, fastest_time FROM $tableName WHERE fastest_time > 0 ORDER BY fastest_time ASC LIMIT 10"
            val result = ArrayList<Triple<Int, String, Double>>()
            try {
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        var spot = 1
                        while (rs.next()) {
                            val name = rs.getString("player_name")
                            val time = rs.getDouble("fastest_time")
                            result.add(Triple(spot++, name, time))
                        }
                    }
                }
            } catch (e: SQLException) {
                module.plugin.logger.severe("[ChatGames-DB] Failed to fetch top fastest typers: ${e.message}")
            }
            callback(result)
        }
    }
}
