package core.luminaworld.modules.features.ColorSign

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class ColorSignModule(plugin: LuminaCore) : LuminaModule(plugin, "ColorSign") {
    private var listener: ColorSignListener? = null

    override fun onEnable() {
        listener = ColorSignListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
    }
}
