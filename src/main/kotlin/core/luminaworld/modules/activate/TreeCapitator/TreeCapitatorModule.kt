package core.luminaworld.modules.activate.TreeCapitator

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TreeCapitatorModule(plugin: LuminaCore) : LuminaModule(plugin, "TreeCapitator") {

    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()
    private var listener: TreeCapitatorListener? = null

    // Cache config values — reset เมื่อ reload()
    var maxBlocks: Int = 128
        private set
    var breakLeaves: Boolean = true
        private set
    var replantSapling: Boolean = true
        private set
    var allowedAxes: List<String> = emptyList()
        private set
    var allowedBlocks: List<String> = emptyList()
        private set

    override fun onEnable() {
        refreshConfigCache()
        listener = TreeCapitatorListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
        activePlayers.clear()
    }

    /**
     * ล้างและโหลดค่า config cache ใหม่ — ถูกเรียกทั้งตอน onEnable และตอน reload()
     */
    private fun refreshConfigCache() {
        maxBlocks = config?.getInt("settings.max-blocks", 128) ?: 128
        breakLeaves = config?.getBoolean("settings.break-leaves", true) ?: true
        replantSapling = config?.getBoolean("settings.replant-sapling", true) ?: true
        allowedAxes = config?.getStringList("settings.allowed-axes") ?: emptyList()
        allowedBlocks = config?.getStringList("settings.allowed-blocks") ?: emptyList()
    }

    override fun reload() {
        super.reload()
    }

    fun isModeEnabled(player: Player): Boolean {
        return activePlayers.contains(player.uniqueId)
    }

    fun toggleMode(player: Player) {
        val uuid = player.uniqueId
        if (activePlayers.contains(uuid)) {
            clearPlayer(player)
            val msg = config?.getString("messages.mode-disabled", "%prefix% &cTree Capitator mode disabled!") ?: ""
            sendNotification(player, msg)
        } else {
            activePlayers.add(uuid)
            val msg = config?.getString("messages.mode-enabled", "%prefix% &aTree Capitator mode enabled!") ?: ""
            sendNotification(player, msg)
        }
    }

    fun clearPlayer(player: Player) {
        val uuid = player.uniqueId
        activePlayers.remove(uuid)
    }
}
