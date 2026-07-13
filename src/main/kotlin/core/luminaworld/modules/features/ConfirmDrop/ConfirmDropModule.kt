package core.luminaworld.modules.features.ConfirmDrop

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class ConfirmDropModule(plugin: LuminaCore) : LuminaModule(plugin, "ConfirmDrop") {
    private var listener: ConfirmDropListener? = null

    override fun onEnable() {
        listener = ConfirmDropListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)

        val desc = config?.getString("settings.description", "ระบบย้ำถามเพื่อยืนยันก่อนทิ้งไอเทมสำคัญ") ?: "ระบบย้ำถามเพื่อยืนยันก่อนทิ้งไอเทมสำคัญ"
        core.luminaworld.settings.PlayerSettingsManager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = name,
                displayName = "ระบบยืนยันทิ้งไอเทม (ConfirmDrop)",
                material = org.bukkit.Material.DIAMOND_SWORD,
                description = desc
            )
        )
    }

    override fun onDisable() {
        listener?.cleanup()
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null

        core.luminaworld.settings.PlayerSettingsManager.unregisterSettingsOfModule(name)
    }
}
