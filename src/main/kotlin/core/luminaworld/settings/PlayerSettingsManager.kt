package core.luminaworld.settings

import core.luminaworld.LuminaCore
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class PlayerSettingOption(
    val moduleName: String,
    val key: String,
    val displayName: String,
    val material: Material,
    val description: String,
    val defaultValue: Boolean = true
)

object PlayerSettingsManager : Listener {
    private lateinit var plugin: LuminaCore
    
    // จัดเก็บตัวเลือกทั้งหมดที่ลงทะเบียนจากโมดูลต่างๆ
    val registeredSettings = CopyOnWriteArrayList<PlayerSettingOption>()
    
    // Cache เก็บข้อมูลสถานะผู้เล่น: { UUID : { SettingKey : Enabled } }
    private val settingsCache = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Boolean>>()

    /**
     * เริ่มต้นระบบฐานข้อมูลและลงทะเบียน Event Listener
     */
    fun initialize(plugin: LuminaCore) {
        this.plugin = plugin
        plugin.server.pluginManager.registerEvents(this, plugin)
        
        // สร้างตารางในฐานข้อมูลแบบ Async
        plugin.databaseService?.runAsync { conn ->
            val sql = """
                CREATE TABLE IF NOT EXISTS lumina_player_settings (
                    uuid VARCHAR(36) NOT NULL,
                    setting_key VARCHAR(64) NOT NULL,
                    setting_value VARCHAR(128) NOT NULL,
                    PRIMARY KEY (uuid, setting_key)
                );
            """.trimIndent()
            conn.createStatement().use { stmt ->
                stmt.execute(sql)
            }
        }
    }

    /**
     * ลงทะเบียนตัวเลือกการตั้งค่าจากโมดูลย่อย
     */
    fun registerSetting(option: PlayerSettingOption) {
        // ป้องกันการลงทะเบียนซ้ำ
        if (registeredSettings.none { it.key.equals(option.key, ignoreCase = true) }) {
            registeredSettings.add(option)
        }
    }

    /**
     * ยกเลิกการลงทะเบียนตัวเลือกการตั้งค่าทั้งหมดของโมดูลนั้นๆ (ตอนโมดูล disable)
     */
    fun unregisterSettingsOfModule(moduleName: String) {
        registeredSettings.removeIf { it.moduleName.equals(moduleName, ignoreCase = true) }
    }

    /**
     * ดึงค่าตั้งค่าดีฟอลต์สำหรับ Setting Key ที่ลงทะเบียนไว้
     */
    private fun getDefaultValue(key: String): Boolean {
        val option = registeredSettings.find { it.key.equals(key, ignoreCase = true) }
        return option?.defaultValue ?: true
    }

    /**
     * ตรวจสอบว่าระบบหรือตัวเลือกนั้นๆ ถูกเปิดใช้งานในระดับผู้เล่นรายบุคคลหรือไม่
     */
    fun isSettingEnabled(player: Player, key: String): Boolean {
        val uuid = player.uniqueId
        val playerSettings = settingsCache[uuid] ?: return getDefaultValue(key)
        return playerSettings[key.lowercase()] ?: getDefaultValue(key)
    }

    /**
     * ตั้งค่าข้อมูลให้กับผู้เล่นและบันทึกลงฐานข้อมูลแบบ Async
     */
    fun setSettingEnabled(player: Player, key: String, enabled: Boolean) {
        val uuid = player.uniqueId
        val playerSettings = settingsCache.getOrPut(uuid) { ConcurrentHashMap() }
        playerSettings[key.lowercase()] = enabled

        plugin.databaseService?.runAsync { conn ->
            val dbType = plugin.config.getString("database.type", "SQLite") ?: "SQLite"
            val finalSql = if (dbType.equals("MySQL", ignoreCase = true)) {
                """
                    INSERT INTO lumina_player_settings (uuid, setting_key, setting_value)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value);
                """.trimIndent()
            } else {
                """
                    INSERT INTO lumina_player_settings (uuid, setting_key, setting_value)
                    VALUES (?, ?, ?)
                    ON CONFLICT(uuid, setting_key) DO UPDATE SET setting_value = excluded.setting_value;
                """.trimIndent()
            }

            conn.prepareStatement(finalSql).use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, key.lowercase())
                ps.setString(3, enabled.toString())
                ps.executeUpdate()
            }
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId
        
        plugin.databaseService?.runAsync { conn ->
            val sql = "SELECT setting_key, setting_value FROM lumina_player_settings WHERE uuid = ?;"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uuid.toString())
                val rs = ps.executeQuery()
                val playerSettings = ConcurrentHashMap<String, Boolean>()
                while (rs.next()) {
                    val key = rs.getString("setting_key").lowercase()
                    val value = rs.getString("setting_value").toBoolean()
                    playerSettings[key] = value
                }
                settingsCache[uuid] = playerSettings
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        settingsCache.remove(uuid)
    }
}
