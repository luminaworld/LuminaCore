package core.luminaworld.modules.activate.VeinMiner

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class VeinMinerModule(plugin: LuminaCore) : LuminaModule(plugin, "VeinMiner") {

    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()
    private var listener: VeinMinerListener? = null

    // Cache config values — reset เมื่อ reload()
    var maxBlocks: Int = 64
        private set
    var requireSneak: Boolean = false
        private set
    var allowedPickaxes: List<String> = emptyList()
        private set
    var allowedBlocks: List<String> = emptyList()
        private set

    override fun onEnable() {
        refreshConfigCache()
        listener = VeinMinerListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
        activePlayers.clear()
    }

    private fun refreshConfigCache() {
        maxBlocks = config?.getInt("settings.max-blocks", 64) ?: 64
        requireSneak = config?.getBoolean("settings.require-sneak", false) ?: false
        allowedPickaxes = config?.getStringList("settings.allowed-pickaxes") ?: emptyList()
        allowedBlocks = config?.getStringList("settings.allowed-blocks") ?: emptyList()
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
