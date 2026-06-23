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
     * รองรับสีแบบ Legacy (&a), Hex (&#ff0000) และ MiniMessage (<gradient:red:blue>)
     */
    fun sendNotification(player: org.bukkit.entity.Player, msg: String) {
        val style = config?.getString("settings.message-style", "CHAT") ?: "CHAT"
        if (style.equals("NONE", ignoreCase = true)) return
        
        val prefix = plugin.config.getString("settings.prefix", "[LuminaCore]") ?: "[LuminaCore]"
        val formattedMsg = msg.replace("%prefix%", prefix)
        val component = parseToComponent(formattedMsg)
        
        if (style.equals("ACTIONBAR", ignoreCase = true)) {
            player.sendActionBar(component)
        } else {
            player.sendMessage(component)
        }
    }

    /**
     * แปลง String ข้อความสีให้เป็น Component ของ Adventure
     */
    fun parseToComponent(input: String): net.kyori.adventure.text.Component {
        var formatted = input
        
        // แปลง HEX สีแบบ &#xxxxxx -> <#xxxxxx>
        val hexRegex = Regex("&#([A-Fa-f0-9]{6})")
        formatted = hexRegex.replace(formatted) { matchResult ->
            "<#${matchResult.groupValues[1]}>"
        }
        
        // แปลงรหัสสีดั้งเดิม & ให้กลายเป็น MiniMessage tags
        val legacyMap = mapOf(
            "&0" to "<black>", "&1" to "<dark_blue>", "&2" to "<dark_green>", "&3" to "<dark_aqua>",
            "&4" to "<dark_red>", "&5" to "<dark_purple>", "&6" to "<gold>", "&7" to "<gray>",
            "&8" to "<dark_gray>", "&9" to "<blue>", "&a" to "<green>", "&b" to "<aqua>",
            "&c" to "<red>", "&d" to "<light_purple>", "&e" to "<yellow>", "&f" to "<white>",
            "&k" to "<obfuscated>", "&l" to "<bold>", "&m" to "<strikethrough>",
            "&n" to "<underlined>", "&o" to "<italic>", "&r" to "<reset>"
        )
        for ((legacy, tag) in legacyMap) {
            formatted = formatted.replace(legacy, tag)
        }
        
        return try {
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(formatted)
        } catch (e: Exception) {
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(input)
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
        player.sendMessage(parseToComponent(noPermMsg))
        return false
    }
}

