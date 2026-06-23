package core.luminaworld.modules.features.AnimalOwner

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class AnimalOwnerModule(plugin: LuminaCore) : LuminaModule(plugin, "AnimalOwner") {
    private var listener: AnimalOwnerListener? = null

    override fun onEnable() {
        listener = AnimalOwnerListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
    }
}
