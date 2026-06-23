package core.luminaworld.modules.system.BlockNotifier

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BlockNotifierModule(plugin: LuminaCore) : LuminaModule(plugin, "BlockNotifier") {
    private var listener: BlockNotifierListener? = null
    val cooldowns = ConcurrentHashMap<UUID, Long>()

    override fun onEnable() {
        listener = BlockNotifierListener(this)
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
