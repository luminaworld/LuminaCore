package core.luminaworld.modules.CoordDirectionViewer

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.entity.Player

class CoordDirectionViewerModule(plugin: LuminaCore) : LuminaModule(plugin, "CoordDirectionViewer") {

    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(CoordDirectionViewerListener(plugin, this), plugin)
    }

    override fun onDisable() {}

    fun showCoordinatesAndDirection(player: Player) {
        val loc = player.location
        val x = loc.blockX
        val y = loc.blockY
        val z = loc.blockZ

        // แปลงค่า yaw ให้อยู่ในช่วง 0 - 360 และคำนวณทิศทางหันหน้า
        var yaw = loc.yaw
        yaw = (yaw % 360 + 360) % 360

        val directionKey = when {
            yaw in 45.0..135.0 -> "messages.dir-west"
            yaw in 135.0..225.0 -> "messages.dir-north"
            yaw in 225.0..315.0 -> "messages.dir-east"
            else -> "messages.dir-south"
        }
        val defaultDir = when {
            yaw in 45.0..135.0 -> "West"
            yaw in 135.0..225.0 -> "North"
            yaw in 225.0..315.0 -> "East"
            else -> "South"
        }
        val directionString = config?.getString(directionKey, defaultDir) ?: defaultDir

        val msgTemplate = config?.getString("messages.info", "%prefix% &eLocation: &aX=%x%, Y=%y%, Z=%z% &7| &eDirection: &a%direction%") ?: ""
        val finalMsg = msgTemplate
            .replace("%x%", x.toString())
            .replace("%y%", y.toString())
            .replace("%z%", z.toString())
            .replace("%direction%", directionString)

        sendNotification(player, finalMsg)
    }
}
