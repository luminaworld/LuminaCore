package core.luminaworld.modules.system.DynamicPricing

import core.luminaworld.LuminaCore
import core.luminaworld.modules.features.Shop.ShopManager
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class DynamicPricingService(private val plugin: LuminaCore) {
    companion object {
        lateinit var instance: DynamicPricingService
            private set
    }

    // Cache ในหน่วยความจำเพื่อความรวดเร็ว: ID -> Pair(BuyCount, SellCount)
    private val transactionCache = ConcurrentHashMap<String, Pair<Int, Int>>()

    private val dbType get() = ShopManager.instance.shopConfig?.getString("database.type", "SQLITE")?.uppercase() ?: "SQLITE"
    private val dbFileName get() = ShopManager.instance.shopConfig?.getString("database.file-name", "shop.db") ?: "shop.db"
    private val prefix get() = ShopManager.instance.shopConfig?.getString("database.table-prefix", "luminashop_") ?: "luminashop_"
    private val tableName get() = "${prefix}dynamic_pricing"

    // ไฟล์ SQLite แยกเฉพาะสำหรับโมดูลร้านค้า
    private val sqliteFile by lazy {
        val folder = File(plugin.dataFolder, "features/Shop")
        if (!folder.exists()) folder.mkdirs()
        File(folder, dbFileName)
    }

    init {
        instance = this
        initializeDatabase()
    }

    private fun getSQLiteConnection(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${sqliteFile.absolutePath}")
    }

    /**
     * รันโพรเซสฐานข้อมูลแบบ Asynchronous:
     * - หากเป็น SQLITE: แยกไฟล์ SQLite .db เป็นของโมดูลร้านค้าเองเสมอ
     * - หากเป็น MYSQL: บังคับใช้ฐานข้อมูล MySQL กลางของ LuminaCore
     */
    private fun runAsyncDb(action: (Connection) -> Unit) {
        if (dbType == "MYSQL") {
            plugin.databaseService?.runAsync { conn -> action(conn) }
        } else {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                try {
                    getSQLiteConnection().use { conn -> action(conn) }
                } catch (e: Exception) {
                    plugin.logger.severe("SQLite DynamicPricing database error: ${e.message}")
                }
            })
        }
    }

    private fun initializeDatabase() {
        runAsyncDb { conn ->
            val sql = """
                CREATE TABLE IF NOT EXISTS $tableName (
                    item_id VARCHAR(64) NOT NULL,
                    buy_count INT NOT NULL DEFAULT 0,
                    sell_count INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (item_id)
                );
            """.trimIndent()
            conn.createStatement().use { stmt ->
                stmt.execute(sql)
            }
            
            // โหลดข้อมูลเก่าเข้า Cache
            val loadSql = "SELECT item_id, buy_count, sell_count FROM $tableName;"
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(loadSql)
                while (rs.next()) {
                    val itemId = rs.getString("item_id").lowercase()
                    val buys = rs.getInt("buy_count")
                    val sells = rs.getInt("sell_count")
                    transactionCache[itemId] = Pair(buys, sells)
                }
            }
        }
    }

    /**
     * ดึงยอดสุทธิการซื้อขาย (buyCount - sellCount)
     */
    fun getNetTransactions(itemId: String): Int {
        val stats = transactionCache[itemId.lowercase()] ?: return 0
        return stats.first - stats.second
    }

    /**
     * คำนวณราคาซื้อแปรผัน
     */
    fun getModifiedBuyPrice(itemId: String, basePrice: Double, maxStock: Int, buyPriceScaling: Double): Double {
        if (maxStock <= 0 || basePrice <= 0.0) return basePrice
        val net = getNetTransactions(itemId)
        val multiplier = 1.0 + (net.toDouble() / maxStock.toDouble())
        val calculatedPrice = basePrice * multiplier
        val maxPrice = basePrice * (1.0 + (buyPriceScaling / 100.0))
        val minPrice = basePrice * 0.5
        return max(minPrice, min(maxPrice, calculatedPrice))
    }

    /**
     * คำนวณราคาขายแปรผัน
     */
    fun getModifiedSellPrice(itemId: String, basePrice: Double, maxStock: Int, sellPriceScaling: Double): Double {
        if (maxStock <= 0 || basePrice <= 0.0) return basePrice
        val net = getNetTransactions(itemId)
        val multiplier = 1.0 + (net.toDouble() / maxStock.toDouble())
        val calculatedPrice = basePrice * multiplier
        val minPrice = basePrice * (sellPriceScaling / 100.0)
        val maxPrice = basePrice * 2.0
        return max(minPrice, min(maxPrice, calculatedPrice))
    }

    /**
     * บันทึกรายการธุรกรรมแบบ Asynchronous
     */
    fun recordTransaction(itemId: String, amount: Int, isBuy: Boolean) {
        val key = itemId.lowercase()
        val current = transactionCache[key] ?: Pair(0, 0)
        val updated = if (isBuy) {
            Pair(current.first + amount, current.second)
        } else {
            Pair(current.first, current.second + amount)
        }
        transactionCache[key] = updated

        runAsyncDb { conn ->
            val upsertSql = if (dbType == "MYSQL") {
                """
                INSERT INTO $tableName (item_id, buy_count, sell_count)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE buy_count = buy_count + ?, sell_count = sell_count + ?;
                """.trimIndent()
            } else {
                """
                INSERT INTO $tableName (item_id, buy_count, sell_count)
                VALUES (?, ?, ?)
                ON CONFLICT(item_id) DO UPDATE SET buy_count = buy_count + ?, sell_count = sell_count + ?;
                """.trimIndent()
            }

            conn.prepareStatement(upsertSql).use { stmt ->
                stmt.setString(1, key)
                stmt.setInt(2, if (isBuy) amount else 0)
                stmt.setInt(3, if (isBuy) 0 else amount)
                stmt.setInt(4, if (isBuy) amount else 0)
                stmt.setInt(5, if (isBuy) 0 else amount)
                stmt.executeUpdate()
            }
        }
    }
}
