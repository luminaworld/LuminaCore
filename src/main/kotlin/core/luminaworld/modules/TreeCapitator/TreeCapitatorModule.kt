package core.luminaworld.modules.TreeCapitator

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TreeCapitatorModule(plugin: LuminaCore) : LuminaModule(plugin, "TreeCapitator") {

    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()
    private var listener: TreeCapitatorListener? = null

    override fun onEnable() {
        listener = TreeCapitatorListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener = null
        activePlayers.clear()
    }

    fun isModeEnabled(player: Player): Boolean {
        return activePlayers.contains(player.uniqueId)
    }

    fun toggleMode(player: Player) {
        val uuid = player.uniqueId
        if (activePlayers.contains(uuid)) {
            activePlayers.remove(uuid)
            val msg = config?.getString("messages.mode-disabled", "%prefix% &cTree Capitator mode disabled!") ?: ""
            sendNotification(player, msg)
        } else {
            activePlayers.add(uuid)
            val msg = config?.getString("messages.mode-enabled", "%prefix% &aTree Capitator mode enabled!") ?: ""
            sendNotification(player, msg)
        }
    }
}
