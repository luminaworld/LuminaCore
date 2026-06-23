package core.luminaworld.modules.activate.SpawnChecker

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList

class SpawnCheckerModule(plugin: LuminaCore) : LuminaModule(plugin, "SpawnChecker") {
    private var listener: SpawnCheckerListener? = null

    override fun onEnable() {
        listener = SpawnCheckerListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
    }

    fun checkSpawnPoints(player: Player) {
        val radius = config?.getInt("settings.radius", 8) ?: 8
        val playerLoc = player.location
        val world = player.world
        val pX = playerLoc.blockX
        val pY = playerLoc.blockY
        val pZ = playerLoc.blockZ

        val checkingMsg = config?.getString("messages.checking", "%prefix% §eScanning spawnable locations...") ?: ""
        sendNotification(player, checkingMsg)

        val spawnableLocations = ArrayList<Location>()

        // สแกนพื้นที่ในรัศมีรอบตัวผู้เล่น
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                for (y in -4..4) {
                    val block = world.getBlockAt(pX + x, pY + y, pZ + z)
                    if (isSpawnable(block)) {
                        spawnableLocations.add(block.location.add(0.5, 0.1, 0.5))
                    }
                }
            }
        }

        if (spawnableLocations.isEmpty()) {
            val notFoundMsg = config?.getString("messages.not-found", "%prefix% §cNo spawnable locations found around you.") ?: ""
            sendNotification(player, notFoundMsg)
            return
        }

        val foundMsg = config?.getString("messages.found", "%prefix% §aFound %amount% spawnable locations! Displaying particles.") ?: ""
        sendNotification(player, foundMsg.replace("%amount%", spawnableLocations.size.toString()))

        // สปอนอนุภาค Flame ทุกวินาทีเป็นเวลา 5 วินาที
        for (i in 0..4) {
            player.scheduler.runDelayed(plugin, { _ ->
                for (loc in spawnableLocations) {
                    player.spawnParticle(Particle.FLAME, loc, 1, 0.0, 0.0, 0.0, 0.0)
                }
            }, null, i * 20L)
        }
    }

    private fun isSpawnable(block: Block): Boolean {
        val above = block.getRelative(0, 1, 0)
        val below = block.getRelative(0, -1, 0)

        if (block.type != Material.AIR && block.type != Material.CAVE_AIR) return false
        if (above.type != Material.AIR && above.type != Material.CAVE_AIR) return false
        if (!below.type.isSolid) return false

        // ตรวจสอบระดับแสงบล็อก (แสงประดิษฐ์)
        return block.lightFromBlocks.toInt() == 0
    }
}
