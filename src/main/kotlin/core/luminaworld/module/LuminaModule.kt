package core.luminaworld.module

import core.luminaworld.LuminaCore
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.io.File
import java.nio.file.Files

abstract class LuminaModule(val plugin: LuminaCore, val name: String) : Listener {
    var isEnabled: Boolean = false
    var config: YamlConfiguration? = null
        protected set
    val configFile: File = File(plugin.dataFolder, "$name.yml")

    /**
     * โหลดข้อมูลการตั้งค่าเฉพาะของโมดูลย่อย
     */
    open fun loadConfig() {
        try {
            val classPackage = javaClass.`package`.name.replace(".", "/")
            val resourcePath = "$classPackage/$name.yml"

            if (!configFile.exists()) {
                // ค้นหาไฟล์ตั้งค่าดีฟอลต์ในแพ็คเกจเดียวกับคลาสลูก
                val inputStream = javaClass.classLoader.getResourceAsStream(resourcePath)
                if (inputStream != null) {
                    if (!plugin.dataFolder.exists()) {
                        plugin.dataFolder.mkdirs()
                    }
                    Files.copy(inputStream, configFile.toPath())
                } else {
                    plugin.logger.warning("Could not find default configuration resource for module: $name at $resourcePath")
                }
            } else {
                // ตรวจสอบและผสานคีย์ใหม่ๆ จาก JAR ลงในไฟล์ในเซิร์ฟเวอร์
                plugin.updateConfig(configFile, resourcePath)
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
     * ล้าง ActionBar Task ของผู้เล่นที่ออกจากเซิร์ฟเวอร์เพื่อป้องกัน Memory Leak
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        plugin.activeActionBarTasks.remove(uuid)?.cancel()
    }

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
            val durationSec = config?.getDouble("settings.actionbar-duration", 0.0) ?: 0.0
            if (durationSec > 0.0) {
                sendPersistentActionBar(player, component, durationSec)
            } else {
                player.sendActionBar(component)
            }
        } else {
            player.sendMessage(component)
        }
    }

    /**
     * ส่งข้อความ ActionBar คงอยู่ชั่วคราวตามระยะเวลาที่กำหนด (ปลอดภัยสำหรับ Folia)
     */
    private fun sendPersistentActionBar(player: org.bukkit.entity.Player, component: net.kyori.adventure.text.Component, durationSec: Double) {
        val uuid = player.uniqueId
        
        // ยกเลิก Task การส่ง ActionBar เดิมที่กำลังทำงานอยู่
        plugin.activeActionBarTasks[uuid]?.cancel()
        
        player.sendActionBar(component)
        
        val maxRuns = (durationSec / 1.0).toInt()
        if (maxRuns <= 1) return
        
        var runCount = 1
        val intervalTicks = 20L // 1 วินาที
        
        val task = player.scheduler.runAtFixedRate(plugin, { scheduledTask ->
            if (!player.isOnline || runCount >= maxRuns) {
                scheduledTask.cancel()
                plugin.activeActionBarTasks.remove(uuid)
                return@runAtFixedRate
            }
            player.sendActionBar(component)
            runCount++
        }, {
            plugin.activeActionBarTasks.remove(uuid)
        }, intervalTicks, intervalTicks)
        
        if (task != null) {
            plugin.activeActionBarTasks[uuid] = task
        }
    }

    /**
     * แปลง String ข้อความสีให้เป็น Component ของ Adventure
     */
    fun parseToComponent(input: String): net.kyori.adventure.text.Component {
        var formatted = input
        
        // แปลง HEX สีแบบ &#xxxxxx -> <#xxxxxx>
        formatted = HEX_REGEX.replace(formatted) { matchResult ->
            "<#${matchResult.groupValues[1]}>"
        }
        
        // แปลงรหัสสีดั้งเดิม & ให้กลายเป็น MiniMessage tags
        for ((legacy, tag) in LEGACY_COLOR_MAP) {
            formatted = formatted.replace(legacy, tag)
        }
        
        return try {
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(formatted)
        } catch (e: Exception) {
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(input)
        }
    }

    companion object {
        private val HEX_REGEX = Regex("&#([A-Fa-f0-9]{6})")
        private val LEGACY_COLOR_MAP = mapOf(
            "&0" to "<black>", "&1" to "<dark_blue>", "&2" to "<dark_green>", "&3" to "<dark_aqua>",
            "&4" to "<dark_red>", "&5" to "<dark_purple>", "&6" to "<gold>", "&7" to "<gray>",
            "&8" to "<dark_gray>", "&9" to "<blue>", "&a" to "<green>", "&b" to "<aqua>",
            "&c" to "<red>", "&d" to "<light_purple>", "&e" to "<yellow>", "&f" to "<white>",
            "&k" to "<obfuscated>", "&l" to "<bold>", "&m" to "<strikethrough>",
            "&n" to "<underlined>", "&o" to "<italic>", "&r" to "<reset>"
        )
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

