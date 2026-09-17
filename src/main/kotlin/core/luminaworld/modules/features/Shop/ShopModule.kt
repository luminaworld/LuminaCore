package core.luminaworld.modules.features.Shop

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files

class ShopModule(plugin: LuminaCore) : LuminaModule(plugin, "Shop") {
    private var commands: ShopCommands? = null

    override val configFile: File
        get() = File(File(plugin.dataFolder, "features/Shop"), "Shop.yml")

    override fun loadConfig() {
        try {
            val parentFolder = configFile.parentFile
            if (!parentFolder.exists()) {
                parentFolder.mkdirs()
            }

            val resourcePath = "core/luminaworld/modules/features/Shop/Shop.yml"

            if (!configFile.exists()) {
                val inputStream = javaClass.classLoader.getResourceAsStream(resourcePath)
                if (inputStream != null) {
                    Files.copy(inputStream, configFile.toPath())
                } else {
                    plugin.logger.warning("Could not find default configuration resource for module: Shop at $resourcePath")
                }
            } else {
                plugin.updateConfig(configFile, resourcePath)
            }

            if (configFile.exists()) {
                config = YamlConfiguration.loadConfiguration(configFile)
                isEnabled = config?.getBoolean("settings.enabled", true) ?: true
            } else {
                isEnabled = false
            }
        } catch (e: Exception) {
            plugin.logger.severe("Error loading config for module Shop: ${e.message}")
            isEnabled = false
        }
    }

    override fun onEnable() {
        // เริ่มต้นการดึงระบบตัวโหลดการตั้งค่า
        ShopManager(plugin)
        
        // เริ่มต้นการดึงระบบจัดการ GUI
        ShopGUIManager(plugin)

        // ลงทะเบียนระบบคำสั่งทั้งหมด
        commands = ShopCommands(plugin).apply {
            register()
        }
    }

    override fun onDisable() {
        // ยกเลิกคำสั่งทั้งหมด
        commands?.unregister()
        commands = null
    }
}
