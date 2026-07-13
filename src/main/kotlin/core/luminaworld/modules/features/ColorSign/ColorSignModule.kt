package core.luminaworld.modules.features.ColorSign

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class ColorSignModule(plugin: LuminaCore) : LuminaModule(plugin, "ColorSign") {
    private var listener: ColorSignListener? = null

    override fun onEnable() {
        listener = ColorSignListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)

        val desc = config?.getString("settings.description", "ระบบช่วยเขียนข้อความสี/รูปแบบต่างๆ บนป้าย") ?: "ระบบช่วยเขียนข้อความสี/รูปแบบต่างๆ บนป้าย"
        core.luminaworld.settings.PlayerSettingsManager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = name,
                displayName = "ระบบป้ายสีสัน (ColorSign)",
                material = org.bukkit.Material.OAK_SIGN,
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
