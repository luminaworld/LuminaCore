package core.luminaworld.modules.features.AutoReplenish

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class AutoReplenishModule(plugin: LuminaCore) : LuminaModule(plugin, "AutoReplenish") {
    private var listener: AutoReplenishListener? = null

    override fun onEnable() {
        listener = AutoReplenishListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
    }
}
