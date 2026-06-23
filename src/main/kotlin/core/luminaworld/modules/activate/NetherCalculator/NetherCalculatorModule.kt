package core.luminaworld.modules.activate.NetherCalculator

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList

class NetherCalculatorModule(plugin: LuminaCore) : LuminaModule(plugin, "NetherCalculator") {
    private var listener: NetherCalculatorListener? = null

    override fun onEnable() {
        listener = NetherCalculatorListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
    }

    fun calculateCoordinates(player: Player) {
        val loc = player.location
        val world = player.world
        val env = world.environment

        when (env) {
            World.Environment.NORMAL -> {
                // จาก Overworld ไป Nether (หาร 8)
                val netherX = loc.blockX / 8
                val netherZ = loc.blockZ / 8
                val msgTemplate = config?.getString("messages.overworld-to-nether", "%prefix% §eNether Equivalent: §aX=%x%, Z=%z%") ?: ""
                val finalMsg = msgTemplate.replace("%x%", netherX.toString()).replace("%z%", netherZ.toString())
                sendNotification(player, finalMsg)
            }
            World.Environment.NETHER -> {
                // จาก Nether ไป Overworld (คูณ 8)
                val overworldX = loc.blockX * 8
                val overworldZ = loc.blockZ * 8
                val msgTemplate = config?.getString("messages.nether-to-overworld", "%prefix% §eOverworld Equivalent: §aX=%x%, Z=%z%") ?: ""
                val finalMsg = msgTemplate.replace("%x%", overworldX.toString()).replace("%z%", overworldZ.toString())
                sendNotification(player, finalMsg)
            }
            else -> {
                val invalidMsg = config?.getString("messages.invalid-world", "%prefix% §cYou can only use this in Overworld or Nether.") ?: ""
                sendNotification(player, invalidMsg)
            }
        }
    }
}
