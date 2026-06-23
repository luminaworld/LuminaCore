package core.luminaworld.modules.activate.GlowingTwerk

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GlowingTwerkModule(plugin: LuminaCore) : LuminaModule(plugin, "GlowingTwerk") {
    private var listener: GlowingTwerkListener? = null
    val cooldowns = ConcurrentHashMap<UUID, Long>()

    override fun onEnable() {
        listener = GlowingTwerkListener(this)
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
