package core.luminaworld.modules.features.AnimalOwner

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class AnimalOwnerModule(plugin: LuminaCore) : LuminaModule(plugin, "AnimalOwner") {
    private var listener: AnimalOwnerListener? = null

    override fun onEnable() {
        listener = AnimalOwnerListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)

        val desc = config?.getString("settings.description", "ระบบย่อตัวคลิกขวาเพื่อเช็คเจ้าของและเลือดของสัตว์") ?: "ระบบย่อตัวคลิกขวาเพื่อเช็คเจ้าของและเลือดของสัตว์"
        core.luminaworld.settings.PlayerSettingsManager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = name,
                displayName = "ระบบเช็คเจ้าของสัตว์ (AnimalOwner)",
                material = org.bukkit.Material.LEAD,
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
