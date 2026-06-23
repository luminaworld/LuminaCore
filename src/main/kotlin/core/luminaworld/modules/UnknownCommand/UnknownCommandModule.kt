package core.luminaworld.modules.UnknownCommand

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class UnknownCommandModule(plugin: LuminaCore) : LuminaModule(plugin, "UnknownCommand") {
    private var listener: UnknownCommandListener? = null
    val cooldowns = ConcurrentHashMap<UUID, Long>()

    override fun onEnable() {
        listener = UnknownCommandListener(this)
        listener?.let {
            plugin.server.pluginManager.registerEvents(it, plugin)
        }
    }

    override fun onDisable() {
        listener?.let {
            HandlerList.unregisterAll(it)
            listener = null
        }
        cooldowns.clear()
    }
}
