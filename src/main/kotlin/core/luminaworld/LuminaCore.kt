package core.luminaworld

import core.luminaworld.module.ModuleManager
import core.luminaworld.command.ModuleCommand
import core.luminaworld.modules.system.Currency.CurrencyService
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

    var commandManager: core.luminaworld.command.CommandManager? = null
        private set

    var databaseService: core.luminaworld.database.DatabaseService? = null
        private set

    /**
     * API ของระบบสกุลเงิน LuminaCore (ไม่เกี่ยวข้องกับ Vault).
     * โมดูลอื่นควรเรียก API นี้แทนการเข้าฐานข้อมูลโดยตรง.
     */
    var currencyService: CurrencyService? = null
        internal set

    var playerSettingsGUI: core.luminaworld.settings.PlayerSettingsGUI? = null
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
        // เริ่มระบบฐานข้อมูลส่วนกลาง
        databaseService = core.luminaworld.database.DatabaseService(this)
        databaseService?.initialize()

        if (!isStandalone) {
            // เริ่มระบบตรวจสอบการอัปเดตแบบ Asynchronous
            core.luminaworld.updater.UpdateChecker.checkForUpdates(this)
        }

        // เริ่มระบบจัดการคำสั่งแบบไดนามิก
        commandManager = core.luminaworld.command.CommandManager(this)

        // เริ่มระบบตัวแปรส่วนกลางของปลั๊กอิน (PlaceholderAPI Expansion)
        core.luminaworld.placeholder.LuminaPlaceholderManager.initialize(this)

        // เริ่มระบบจัดการโมดูล
        moduleManager = ModuleManager(this)
        moduleManager?.loadModules()
        commandManager?.syncServerCommands()

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

            // เริ่มระบบตั้งค่าผู้เล่น
            core.luminaworld.settings.PlayerSettingsManager.initialize(this)
            val gui = core.luminaworld.settings.PlayerSettingsGUI(this)
            server.pluginManager.registerEvents(gui, this)
            playerSettingsGUI = gui

            val settingsExecutor = core.luminaworld.settings.PlayerSettingsCommand(this, gui)
            getCommand("setting")?.setExecutor(settingsExecutor)
            getCommand("settings")?.setExecutor(settingsExecutor)

            // ลงทะเบียน Listener สำหรับ GUI ส่วนกลาง
            server.pluginManager.registerEvents(core.luminaworld.gui.LuminaGUIListener(this), this)
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

        // ปิดระบบตัวแปรส่วนกลางของปลั๊กอิน
        core.luminaworld.placeholder.LuminaPlaceholderManager.shutdown()

        // ปิดและล้างคำสั่งไดนามิกทั้งหมด
        commandManager?.unregisterAll()
        commandManager?.syncServerCommands()
        commandManager = null

        // ปิดระบบฐานข้อมูลกลาง
        databaseService?.shutdown()
        databaseService = null

        if (!isStandalone) {
            getCommand("setting")?.setExecutor(null)
            getCommand("settings")?.setExecutor(null)
            playerSettingsGUI = null

            // ยกเลิกการลงทะเบียน Command Executor เพื่อป้องกัน memory leak ในกรณี reload ปลั๊กอิน
            val commands = arrayOf("luminacore", "luminaris", "luminaworld", "llw", "lc")
            for (cmd in commands) {
                getCommand(cmd)?.apply {
                    setExecutor(null)
                    tabCompleter = null
                }
            }
        }

        if (isStandalone) {
            logger.info("[Lumina-$standaloneModuleName] Standalone Plugin disabled.")
        } else {
            logger.info("[LuminaCore] Plugin disabled.")
        }

        // ล้างข้อมูลปลั๊กอินออกจาก Paper/Leaf เพื่อไม่ให้เกิด duplicate identifier เมื่อโหลดใหม่
        cleanupPaperPluginManager()
    }

    private fun cleanupPaperPluginManager() {
        try {
            val paperPluginManagerClass = Class.forName("io.papermc.paper.plugin.manager.PaperPluginManagerImpl")
            val getInstanceMethod = paperPluginManagerClass.getMethod("getInstance")
            val pluginManager = getInstanceMethod.invoke(null)

            val instanceManagerField = paperPluginManagerClass.getDeclaredField("instanceManager")
            instanceManagerField.isAccessible = true
            val instanceManager = instanceManagerField.get(pluginManager)

            // ล้างจาก plugins List
            val pluginsField = instanceManager.javaClass.getDeclaredField("plugins")
            pluginsField.isAccessible = true
            val pluginsList = pluginsField.get(instanceManager) as? MutableList<Any>
            pluginsList?.remove(this)

            // ล้างจาก lookupNames Map
            val lookupNamesField = instanceManager.javaClass.getDeclaredField("lookupNames")
            lookupNamesField.isAccessible = true
            val lookupNamesMap = lookupNamesField.get(instanceManager) as? MutableMap<Any, Any>
            if (lookupNamesMap != null) {
                val nameKey = description.name.lowercase().replace(" ", "_")
                val originalKey = description.name
                lookupNamesMap.remove(nameKey)
                lookupNamesMap.remove(originalKey)
            }
        } catch (e: Exception) {
            // ละเว้นหากเกิดข้อผิดพลาดในการใช้ Reflection หรือทำงานบนระบบอื่นที่ไม่ใช่ Paper/Leaf
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

            // ตรวจสอบตัวเลือกการอัปเดตคอนฟิกอัตโนมัติจาก config.yml ของเซิร์ฟเวอร์
            val mainConfigFile = File(dataFolder, "config.yml")
            var autoUpdateEnabled = true
            var updateMode = "MERGE"

            if (mainConfigFile.exists()) {
                val mainConfig = YamlConfiguration.loadConfiguration(mainConfigFile)
                autoUpdateEnabled = mainConfig.getBoolean("config-update.enabled", true)
                updateMode = mainConfig.getString("config-update.mode", "MERGE")?.uppercase() ?: "MERGE"
            }

            if (!autoUpdateEnabled || updateMode == "NONE") {
                return
            }

            val resourceStream = getResource(resourceName) ?: return

            // โหมดเขียนทับไฟล์คอนฟิกเดิมทั้งหมด (WRITE_OVER)
            if (updateMode == "WRITE_OVER") {
                try {
                    configFile.outputStream().use { output ->
                        resourceStream.copyTo(output)
                    }
                    resourceStream.close()
                    logger.info("[LuminaCore] Config file ${configFile.name} has been overwritten (WRITE_OVER mode).")
                } catch (e: Exception) {
                    logger.severe("[LuminaCore] Failed to overwrite config ${configFile.name}: ${e.message}")
                }
                return
            }

            // โหมดผสานคีย์ตั้งค่าเฉพาะส่วนต่างที่มาใหม่ (MERGE)
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

                // คัดลอกและอัปเดตคอมเมนต์ของคีย์คอนฟิกเพื่อรักษาคอมเมนต์จาก JAR
                try {
                    if (currentConfig.contains(key)) {
                        currentConfig.setComments(key, defaultConfig.getComments(key))
                        currentConfig.setInlineComments(key, defaultConfig.getInlineComments(key))
                    }
                } catch (e: NoSuchMethodError) {
                    // ข้ามกรณีเซิร์ฟเวอร์รุ่นเก่าไม่มี API ตัวนี้
                }
            }

            // คัดลอกคอมเมนต์หัวไฟล์
            try {
                currentConfig.setComments("", defaultConfig.getComments(""))
            } catch (e: Exception) {}

            // เซฟคอนฟิกเมื่อมีการเปลี่ยนค่า หรือเพื่อรีเฟรชคอมเมนต์ใหม่
            currentConfig.save(configFile)
            if (updated) {
                logger.info("[LuminaCore] Automatically added missing configuration keys to ${configFile.name}")
            }
        } catch (e: Exception) {
            logger.severe("[LuminaCore] Failed to auto-update config ${configFile.name}: ${e.message}")
        }
    }
}
