package core.luminaworld

import core.luminaworld.module.ModuleManager
import core.luminaworld.command.ModuleCommand
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LuminaCore : JavaPlugin() {

    companion object {
        lateinit var instance: LuminaCore
            private set
    }

    var moduleManager: ModuleManager? = null
        private set

    val activeActionBarTasks = ConcurrentHashMap<UUID, ScheduledTask>()
    val suspendedPlayers = ConcurrentHashMap.newKeySet<UUID>()

    var isStandalone: Boolean = false
        private set
    var standaloneModuleName: String? = null
        private set
    var standaloneModuleClass: String? = null
        private set

    override fun onEnable() {
        instance = this
        
        // ตรวจสอบ Standalone Mode
        val standaloneProps = getResource("standalone.properties")
        if (standaloneProps != null) {
            try {
                val properties = java.util.Properties()
                standaloneProps.use { properties.load(it) }
                standaloneModuleName = properties.getProperty("module.name")
                standaloneModuleClass = properties.getProperty("module.class")
                if (!standaloneModuleName.isNullOrBlank() && !standaloneModuleClass.isNullOrBlank()) {
                    isStandalone = true
                }
            } catch (e: Exception) {
                logger.severe("[LuminaCore] Failed to load standalone properties: ${e.message}")
            }
        }

        // บันทึกและโหลด Config (ข้ามในโหมด Standalone)
        if (!isStandalone) {
            saveDefaultConfig()
            updateConfig(File(dataFolder, "config.yml"), "config.yml")
            reloadConfig()
        }
        
        // พิมพ์ข้อความต้อนรับ ASCII Art และรายละเอียดของปลั๊กอิน
        val version = description.version
        val author = "Loma0531"
        
        if (isStandalone) {
            server.consoleSender.sendMessage("§e===================================================")
            server.consoleSender.sendMessage("§a [Lumina-$standaloneModuleName] Standalone Plugin is enabling...")
            server.consoleSender.sendMessage("§a - Version: §f$version")
            server.consoleSender.sendMessage("§a - Author: §f$author")
            server.consoleSender.sendMessage("§e===================================================")
        } else {
            val githubUrl = "https://github.com/luminaworld/LuminaCore"
            server.consoleSender.sendMessage("§b  _                    _              ____               ")
            server.consoleSender.sendMessage("§b | |   _   _ _ __ ___ (_)_ __   __ _ / ___|___  _ __ ___ ")
            server.consoleSender.sendMessage("§b | |  | | | | '_ ` _ \\| | '_ \\ / _` | |   / _ \\| '__/ _ \\")
            server.consoleSender.sendMessage("§b | |__| |_| | | | | | | | | | | (_| | |__| (_) | | |  __/")
            server.consoleSender.sendMessage("§b |_____\\__,_|_| |_| |_|_|_| |_|\\__,_|\\____\\___/|_|  \\___|")
            server.consoleSender.sendMessage("§b                                                         ")
            server.consoleSender.sendMessage("§a [LuminaCore] Plugin is enabling...")
            server.consoleSender.sendMessage("§a - Version: §f$version")
            server.consoleSender.sendMessage("§a - Author: §f$author")
            server.consoleSender.sendMessage("§a - GitHub: §b$githubUrl")
            server.consoleSender.sendMessage("§e===================================================")
        }

        // เริ่มต้นการตรวจสอบ License Key ก่อนโหลดคอมโพเนนต์ของปลั๊กอิน
        core.luminaworld.license.LicenseManager.verifyLicense(this) { success ->
            if (success) {
                startPluginComponents()
            }
        }
    }

    /**
     * เริ่มการทำงานของคอมโพเนนต์หลักในปลั๊กอินหลังจากผ่านการยืนยัน License แล้ว
     */
    private fun startPluginComponents() {
        if (!isStandalone) {
            // เริ่มระบบตรวจสอบการอัปเดตแบบ Asynchronous
            core.luminaworld.updater.UpdateChecker.checkForUpdates(this)
        }
        
        // เริ่มระบบจัดการโมดูล
        moduleManager = ModuleManager(this)
        moduleManager?.loadModules()

        if (!isStandalone) {
            // ลงทะเบียนคำสั่งและ alias ทั้งหมด
            val commandExecutor = ModuleCommand(this)
            val commands = arrayOf("luminacore", "luminaris", "luminaworld", "llw", "lc")
            for (cmd in commands) {
                getCommand(cmd)?.apply {
                    setExecutor(commandExecutor)
                    tabCompleter = commandExecutor
                }
            }

            // ลงทะเบียน Listener ส่วนกลางในการดักฟังปุ่มลัดการกดย่อตัว
            server.pluginManager.registerEvents(core.luminaworld.listener.SneakTriggerListener(this), this)
            // ลงทะเบียน Listener ตรวจเช็คการแจ้งเตือนอัปเดตแก่ผู้เล่นที่เข้าเซิร์ฟเวอร์
            server.pluginManager.registerEvents(core.luminaworld.listener.PlayerJoinListener(this), this)
        }
    }

    override fun onDisable() {
        if (isStandalone) {
            logger.info("[Lumina-$standaloneModuleName] Standalone Plugin is disabling...")
        } else {
            logger.info("[LuminaCore] Plugin is disabling...")
        }
        
        // ยกเลิกและล้าง Task ของ ActionBar ทั้งหมด
        activeActionBarTasks.values.forEach { it.cancel() }
        activeActionBarTasks.clear()
        
        // ปิดการทำงานโมดูลย่อยทั้งหมด
        moduleManager?.disableModules()
        moduleManager = null

        if (isStandalone) {
            logger.info("[Lumina-$standaloneModuleName] Standalone Plugin disabled.")
        } else {
            logger.info("[LuminaCore] Plugin disabled.")
        }
    }

    /**
     * ดึงและแทนที่ข้อความหลักจาก config
     */
    fun getMsg(path: String, def: String): String {
        val msg = config.getString("messages.$path", def)
        val prefix = config.getString("settings.prefix", "[LuminaCore]") ?: "[LuminaCore]"
        return (msg ?: def).replace("%prefix%", prefix).replace("&", "§")
    }

    /**
     * ตรวจสอบและผสานคีย์ใหม่ๆ จาก JAR เข้ากับไฟล์ Config ปัจจุบันในเครื่อง
     */
    fun updateConfig(configFile: File, resourceName: String) {
        try {
            if (!configFile.exists()) return
            
            val resourceStream = getResource(resourceName) ?: return
            val defaultReader = InputStreamReader(resourceStream, StandardCharsets.UTF_8)
            val defaultConfig = YamlConfiguration.loadConfiguration(defaultReader)
            val currentConfig = YamlConfiguration.loadConfiguration(configFile)
            
            var updated = false
            for (key in defaultConfig.getKeys(true)) {
                if (!defaultConfig.isConfigurationSection(key)) {
                    if (!currentConfig.contains(key)) {
                        currentConfig.set(key, defaultConfig.get(key))
                        updated = true
                    }
                }
            }
            
            if (updated) {
                currentConfig.save(configFile)
                logger.info("[LuminaCore] Automatically added missing configuration keys to ${configFile.name}")
            }
        } catch (e: Exception) {
            logger.severe("[LuminaCore] Failed to auto-update config ${configFile.name}: ${e.message}")
        }
    }
}

