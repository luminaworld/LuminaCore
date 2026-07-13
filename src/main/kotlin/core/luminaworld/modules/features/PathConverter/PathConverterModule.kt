package core.luminaworld.modules.features.PathConverter

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class PathConverterModule(plugin: LuminaCore) : LuminaModule(plugin, "PathConverter") {
    private var listener: PathConverterListener? = null

    override fun onEnable() {
        listener = PathConverterListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)

        val desc = config?.getString("settings.description", "ระบบช่วยแปลง Dirt Path คืนเป็นดินธรรมดาด้วยการคลิกขวาด้วยพลั่ว") ?: "ระบบช่วยแปลง Dirt Path คืนเป็นดินธรรมดาด้วยการคลิกขวาด้วยพลั่ว"
        core.luminaworld.settings.PlayerSettingsManager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = name,
                displayName = "ระบบแปลงเส้นทางดิน (PathConverter)",
                material = org.bukkit.Material.DIRT_PATH,
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
