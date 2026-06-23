package core.luminaworld.modules.CreativeMonitor

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CreativeMonitorModule(plugin: LuminaCore) : LuminaModule(plugin, "CreativeMonitor") {
    private var listener: CreativeMonitorListener? = null
    val cooldowns = ConcurrentHashMap<UUID, Long>()

    override fun onEnable() {
        listener = CreativeMonitorListener(this)
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
