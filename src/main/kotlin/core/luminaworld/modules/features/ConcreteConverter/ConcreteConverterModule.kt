package core.luminaworld.modules.features.ConcreteConverter

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class ConcreteConverterModule(plugin: LuminaCore) : LuminaModule(plugin, "ConcreteConverter") {
    private var listener: ConcreteConverterListener? = null

    override fun onEnable() {
        listener = ConcreteConverterListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
    }
}
