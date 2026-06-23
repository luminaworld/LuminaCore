package core.luminaworld.modules.features.NetherSponge

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList

class NetherSpongeModule(plugin: LuminaCore) : LuminaModule(plugin, "NetherSponge") {
    private var listener: NetherSpongeListener? = null

    override fun onEnable() {
        listener = NetherSpongeListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
    }
}
