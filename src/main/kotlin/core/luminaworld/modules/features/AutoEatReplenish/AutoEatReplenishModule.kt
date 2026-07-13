package core.luminaworld.modules.features.AutoEatReplenish

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class AutoEatReplenishModule(plugin: LuminaCore) : LuminaModule(plugin, "AutoEatReplenish") {
    private var listener: AutoEatReplenishListener? = null

    override fun onEnable() {
        listener = AutoEatReplenishListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)

        val desc = config?.getString("settings.description", "ระบบช่วยเติมอาหารใส่ในมืออัตโนมัติเมื่อกินจนหมด") ?: "ระบบช่วยเติมอาหารใส่ in มืออัตโนมัติเมื่อกินจนหมด"
        core.luminaworld.settings.PlayerSettingsManager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = name,
                displayName = "ระบบเติมอาหารอัตโนมัติ (AutoEatReplenish)",
                material = org.bukkit.Material.COOKED_BEEF,
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
