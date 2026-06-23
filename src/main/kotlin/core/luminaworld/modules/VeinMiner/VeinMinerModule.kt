package core.luminaworld.modules.VeinMiner

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class VeinMinerModule(plugin: LuminaCore) : LuminaModule(plugin, "VeinMiner") {

    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()
    private var listener: VeinMinerListener? = null

    override fun onEnable() {
        listener = VeinMinerListener(plugin, this)
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
            val msg = config?.getString("messages.mode-disabled", "%prefix% &cVein Miner mode disabled!") ?: ""
            sendNotification(player, msg)
        } else {
            activePlayers.add(uuid)
            val msg = config?.getString("messages.mode-enabled", "%prefix% &aVein Miner mode enabled!") ?: ""
            sendNotification(player, msg)
        }
    }
}
