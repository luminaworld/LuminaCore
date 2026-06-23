package core.luminaworld.modules.features.AutoEatReplenish

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class AutoEatReplenishModule(plugin: LuminaCore) : LuminaModule(plugin, "AutoEatReplenish") {
    private var listener: AutoEatReplenishListener? = null

    override fun onEnable() {
        listener = AutoEatReplenishListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
    }
}
