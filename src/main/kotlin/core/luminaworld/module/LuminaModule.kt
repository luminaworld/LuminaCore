package core.luminaworld.module

import core.luminaworld.LuminaCore
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files

abstract class LuminaModule(val plugin: LuminaCore, val name: String) {
    var isEnabled: Boolean = false
    var config: YamlConfiguration? = null
        protected set
    val configFile: File = File(plugin.dataFolder, "$name.yml")

    /**
     * โหลดข้อมูลการตั้งค่าเฉพาะของโมดูลย่อย
     */
    open fun loadConfig() {
        try {
            if (!configFile.exists()) {
                // ค้นหาไฟล์ตั้งค่าดีฟอลต์ในแพ็คเกจเดียวกับคลาสลูก
                val inputStream = javaClass.getResourceAsStream("$name.yml")
                if (inputStream != null) {
                    if (!plugin.dataFolder.exists()) {
                        plugin.dataFolder.mkdirs()
                    }
                    Files.copy(inputStream, configFile.toPath())
                } else {
                    plugin.logger.warning("Could not find default configuration resource for module: $name")
                }
            }

            if (configFile.exists()) {
                config = YamlConfiguration.loadConfiguration(configFile)
                
                // สวิตช์เปิด/ปิดโมดูลจาก Config ย่อยของตัวเองเท่านั้น
                val enabledInModule = config?.getBoolean("settings.enabled", true) ?: true
                
                isEnabled = enabledInModule
            } else {
                isEnabled = false
            }
        } catch (e: Exception) {
            plugin.logger.severe("Error loading config for module $name: ${e.message}")
            isEnabled = false
        }
    }

    abstract fun onEnable()
    abstract fun onDisable()

    /**
     * รีโหลดการตั้งค่าของโมดูล
     */
    open fun reload() {
        if (isEnabled) {
            try {
                onDisable()
            } catch (e: Exception) {
                plugin.logger.severe("Error disabling module $name during reload: ${e.message}")
            }
        }

        loadConfig()

        if (isEnabled) {
            try {
                onEnable()
            } catch (e: Exception) {
                plugin.logger.severe("Error enabling module $name during reload: ${e.message}")
            }
        }
    }

    /**
     * ส่งข้อความแจ้งเตือนหาผู้เล่นตามสไตล์ที่กำหนดใน Config ย่อย (CHAT หรือ ACTIONBAR)
     */
    fun sendNotification(player: org.bukkit.entity.Player, msg: String) {
        val style = config?.getString("settings.message-style", "CHAT") ?: "CHAT"
        val prefix = plugin.config.getString("settings.prefix", "[LuminaCore]") ?: "[LuminaCore]"
        val formattedMsg = msg.replace("%prefix%", prefix).replace("&", "§")
        
        if (style.equals("ACTIONBAR", ignoreCase = true)) {
            player.sendActionBar(formattedMsg)
        } else {
            player.sendMessage(formattedMsg)
        }
    }

    /**
     * ตรวจสอบว่าผู้เล่นมีสิทธิ์ในการเข้าถึงระบบย่อยนี้หรือไม่
     */
    fun checkPermission(player: org.bukkit.entity.Player): Boolean {
        val usePermission = config?.getBoolean("settings.use-permission", false) ?: false
        if (!usePermission) return true
        
        val permissionNode = config?.getString("settings.permission", "luminacore.module.${name.lowercase()}") 
            ?: "luminacore.module.${name.lowercase()}"
            
        if (player.hasPermission(permissionNode) || player.isOp) return true
        
        val noPermMsg = plugin.getMsg("no-permission", "%prefix% &cYou do not have permission to execute this.")
        player.sendMessage(noPermMsg)
        return false
    }
}
