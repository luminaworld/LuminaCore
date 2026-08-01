package core.luminaworld.database

import core.luminaworld.LuminaCore
import org.bukkit.Bukkit
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class DatabaseService(private val plugin: LuminaCore) {
    private var connection: Connection? = null
    private var dbType: String = "SQLite"

    // ค่าคอนฟิก MySQL
    private var mysqlHost = "localhost"
    private var mysqlPort = 3306
    private var mysqlDatabase = "minecraft"
    private var mysqlUser = "root"
    private var mysqlPassword = ""
    private var mysqlParameters = "?autoReconnect=true&useSSL=false&useUnicode=true&characterEncoding=UTF-8"
    
    // คำนำหน้าตารางฐานข้อมูล
    var tablePrefix = "lumina_"

    // ค่าคอนฟิก SQLite
    private var sqliteFilename = "lumina-database"

    /**
     * เริ่มต้นโหลดการตั้งค่าและทดสอบการเชื่อมต่อ
     */
    fun initialize() {
        val config = plugin.config
        dbType = config.getString("database.type", "SQLite") ?: "SQLite"
        
        sqliteFilename = config.getString("database.SQLite.filename", "lumina-database") ?: "lumina-database"
        
        mysqlHost = config.getString("database.MySQL.host", "localhost") ?: "localhost"
        mysqlPort = config.getInt("database.MySQL.port", 3306)
        mysqlDatabase = config.getString("database.MySQL.database", "minecraft") ?: "minecraft"
        mysqlUser = config.getString("database.MySQL.user", "root") ?: "root"
        mysqlPassword = config.getString("database.MySQL.password", "") ?: ""
        mysqlParameters = config.getString("database.MySQL.connection-parameters", "?autoReconnect=true&useSSL=false&useUnicode=true&characterEncoding=UTF-8")
            ?: "?autoReconnect=true&useSSL=false&useUnicode=true&characterEncoding=UTF-8"
        tablePrefix = config.getString("database.MySQL.table-prefix", "lumina_") ?: "lumina_"

        try {
            val conn = getConnection()
            if (conn != null && !conn.isClosed) {
                plugin.logger.info("§6[Lumina-DB] §aเชื่อมต่อฐานข้อมูลกลางสำเร็จแล้ว (ประเภท: $dbType)")
            } else {
                plugin.logger.severe("§6[Lumina-DB] §cไม่สามารถเริ่มต้นการเชื่อมต่อฐานข้อมูลกลางได้")
            }
        } catch (e: Exception) {
            plugin.logger.severe("§6[Lumina-DB] §cเกิดข้อผิดพลาดในการตรวจสอบเชื่อมต่อฐานข้อมูล: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * ดึง Connection ฐานข้อมูล (ต่อใหม่หากขาดการเชื่อมต่อ)
     */
    @Synchronized
    fun getConnection(): Connection? {
        try {
            // หากมี Connection อยู่แล้วและใช้งานได้ปกติ ให้ส่งคืนทันที
            connection?.let {
                if (!it.isClosed && it.isValid(2)) {
                    return it
                }
            }

            // จัดการการเชื่อมต่อตามประเภทฐานข้อมูล
            if (dbType.equals("MySQL", ignoreCase = true)) {
                Class.forName("com.mysql.cj.jdbc.Driver")
                val url = "jdbc:mysql://$mysqlHost:$mysqlPort/$mysqlDatabase$mysqlParameters"
                connection = DriverManager.getConnection(url, mysqlUser, mysqlPassword)
            } else {
                Class.forName("org.sqlite.JDBC")
                val dbDir = File(plugin.dataFolder, "database")
                if (!dbDir.exists()) {
                    dbDir.mkdirs()
                }
                val dbFile = File(dbDir, "$sqliteFilename.db")
                connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            }
            return connection
        } catch (e: Exception) {
            plugin.logger.severe("§6[Lumina-DB] §cเกิดข้อผิดพลาดในการดึงการเชื่อมต่อ: ${e.message}")
            return null
        }
    }

    /**
     * สั่งรันชุดคำสั่ง SQL แบบ Asynchronously เพื่อไม่ให้บล็อก Main Thread (ปลอดภัยบน Folia)
     */
    fun runAsync(task: (Connection) -> Unit) {
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            val conn = getConnection()
            if (conn != null) {
                try {
                    task(conn)
                } catch (e: SQLException) {
                    plugin.logger.severe("§6[Lumina-DB] §cเกิดข้อผิดพลาดขณะรัน SQL Async: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                plugin.logger.warning("§6[Lumina-DB] §cไม่สามารถประมวลผลคำสั่งได้เนื่องจากไม่พบคอนเนกชันฐานข้อมูล")
            }
        }
    }

    /**
     * ปิดการเชื่อมต่อฐานข้อมูลทั้งหมด
     */
    @Synchronized
    fun shutdown() {
        try {
            connection?.let {
                if (!it.isClosed) {
                    it.close()
                    plugin.logger.info("§6[Lumina-DB] §eปิดการเชื่อมต่อฐานข้อมูลกลางเรียบร้อยแล้ว")
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("§6[Lumina-DB] §cเกิดข้อผิดพลาดขณะปิดฐานข้อมูล: ${e.message}")
        } finally {
            connection = null
        }
    }
}
