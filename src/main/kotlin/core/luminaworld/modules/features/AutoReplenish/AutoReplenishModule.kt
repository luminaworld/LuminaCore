package core.luminaworld.modules.features.AutoReplenish

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class AutoReplenishModule(plugin: LuminaCore) : LuminaModule(plugin, "AutoReplenish") {
    private var listener: AutoReplenishListener? = null

    override fun onEnable() {
        listener = AutoReplenishListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)

        val desc = config?.getString("settings.description", "ระบบช่วยเติมบล็อกหรือขวาน/พลั่วใส่ในมืออัตโนมัติเมื่อวางจนหมดหรือพัง") ?: "ระบบช่วยเติมบล็อกหรือขวาน/พลั่วใส่ในมืออัตโนมัติเมื่อวางจนหมดหรือพัง"
        core.luminaworld.settings.PlayerSettingsManager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = name,
                displayName = "ระบบเติมบล็อกอุปกรณ์อัตโนมัติ (AutoReplenish)",
                material = org.bukkit.Material.OAK_PLANKS,
                description = desc
            )
        )
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null

        core.luminaworld.settings.PlayerSettingsManager.unregisterSettingsOfModule(name)
    }
}
