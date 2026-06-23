package core.luminaworld.modules.features.PathConverter

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class PathConverterModule(plugin: LuminaCore) : LuminaModule(plugin, "PathConverter") {
    private var listener: PathConverterListener? = null

    override fun onEnable() {
        listener = PathConverterListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
    }
}
