package core.luminaworld

import core.luminaworld.module.ModuleManager
import core.luminaworld.command.ModuleCommand
import org.bukkit.plugin.java.JavaPlugin

class LuminaCore : JavaPlugin() {

    companion object {
        lateinit var instance: LuminaCore
            private set
    }

    var moduleManager: ModuleManager? = null
        private set

    override fun onEnable() {
        instance = this
        
        // บันทึกและโหลด Config หลัก
        saveDefaultConfig()
        reloadConfig()
        
        logger.info("[LuminaCore] Plugin is enabling...")
        
        // เริ่มระบบจัดการโมดูล
        moduleManager = ModuleManager(this)
        moduleManager?.loadModules()

        // ลงทะเบียนคำสั่งและ alias ทั้งหมด
        val commandExecutor = ModuleCommand(this)
        val commands = arrayOf("luminacore", "luminaris", "luminaworld", "llw", "lc")
        for (cmd in commands) {
            getCommand(cmd)?.apply {
                setExecutor(commandExecutor)
                tabCompleter = commandExecutor
            }
        }

        logger.info("[LuminaCore] Plugin enabled successfully.")
    }

    override fun onDisable() {
        logger.info("[LuminaCore] Plugin is disabling...")
        
        // ปิดการทำงานโมดูลย่อยทั้งหมด
        moduleManager?.disableModules()
        moduleManager = null

        logger.info("[LuminaCore] Plugin disabled.")
    }

    /**
     * ดึงและแทนที่ข้อความหลักจาก config
     */
    fun getMsg(path: String, def: String): String {
        val msg = config.getString("messages.$path", def)
        val prefix = config.getString("settings.prefix", "[LuminaCore]") ?: "[LuminaCore]"
        return (msg ?: def).replace("%prefix%", prefix).replace("&", "§")
    }
}
