package core.luminaworld.modules.features.FarmlandConverter

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class FarmlandConverterModule(plugin: LuminaCore) : LuminaModule(plugin, "FarmlandConverter") {
    private var listener: FarmlandConverterListener? = null

    override fun onEnable() {
        listener = FarmlandConverterListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)

        val desc = config?.getString("settings.description", "ระบบช่วยแปลงดิน Farmland คืนเป็นดินธรรมดาด้วยการคลิกขวาด้วยจอบ") ?: "ระบบช่วยแปลงดิน Farmland คืนเป็นดินธรรมดาด้วยการคลิกขวาด้วยจอบ"
        core.luminaworld.settings.PlayerSettingsManager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = name,
                displayName = "ระบบแปลงดินทำนา (FarmlandConverter)",
                material = org.bukkit.Material.FARMLAND,
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
