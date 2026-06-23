package core.luminaworld.modules.features.ConfirmDrop

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class ConfirmDropModule(plugin: LuminaCore) : LuminaModule(plugin, "ConfirmDrop") {
    private var listener: ConfirmDropListener? = null

    override fun onEnable() {
        listener = ConfirmDropListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.cleanup()
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
    }
}
