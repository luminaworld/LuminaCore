package core.luminaworld.modules.features.FarmlandConverter

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class FarmlandConverterModule(plugin: LuminaCore) : LuminaModule(plugin, "FarmlandConverter") {
    private var listener: FarmlandConverterListener? = null

    override fun onEnable() {
        listener = FarmlandConverterListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
    }
}
