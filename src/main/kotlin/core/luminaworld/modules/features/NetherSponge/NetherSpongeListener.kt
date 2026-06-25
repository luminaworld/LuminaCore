package core.luminaworld.modules.features.NetherSponge

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemSpawnEvent
import core.luminaworld.LuminaCore

class NetherSpongeListener(private val plugin: LuminaCore, private val module: NetherSpongeModule) : Listener {

    @EventHandler
    fun onItemSpawn(event: ItemSpawnEvent) {
        if (!module.isEnabled) return
        val entity = event.entity
        val world = entity.world

        // ตรวจเช็คว่าอยู่ในมิตินรก (Nether) หรือไม่
        if (world.environment != World.Environment.NETHER) return

        val stack = entity.itemStack
        if (stack.type == Material.WET_SPONGE) {
            val config = module.config ?: return
            val delaySeconds = config.getInt("settings.delay-seconds", 3)

            if (delaySeconds <= 0) {
                drySponge(entity)
            } else {
                entity.scheduler.runDelayed(plugin, { _ ->
                    drySponge(entity)
                }, null, delaySeconds * 20L)
            }
        }
    }

    private fun drySponge(entity: Item) {
        if (!entity.isValid) return
        val stack = entity.itemStack
        if (stack.type != Material.WET_SPONGE) return

        // ตรวจเช็คสิทธิ์ Permission ของผู้โยน (ถ้ามี)
        val throwerUuid = entity.thrower
        if (throwerUuid != null) {
            val player = plugin.server.getPlayer(throwerUuid)
            if (player != null && !module.checkPermission(player)) {
                return
            }
        }

        // แปลงร่างฟองน้ำเปียกเป็นฟองน้ำแห้ง
        stack.type = Material.SPONGE
        entity.itemStack = stack

        val world = entity.world
        val loc = entity.location
        val config = module.config ?: return

        // ปล่อยเอฟเฟกต์ไอน้ำเดือดพล่าน
        val particlesEnabled = config.getBoolean("settings.particles.enabled", true)
        if (particlesEnabled) {
            val pName = config.getString("settings.particles.type", "SMOKE") ?: "SMOKE"
            val count = config.getInt("settings.particles.count", 8)
            val speed = config.getDouble("settings.particles.speed", 0.05)
            try {
                val pType = Particle.valueOf(pName)
                world.spawnParticle(pType, loc.add(0.0, 0.2, 0.0), count, 0.1, 0.1, 0.1, speed)
            } catch (e: Exception) {}
        }

        // เล่นเสียงน้ำเดือดแห้งหาย
        val audioEnabled = config.getBoolean("settings.audio.enabled", true)
        if (audioEnabled) {
            val soundName = config.getString("settings.audio.sound", "BLOCK_LAVA_EXTINGUISH") ?: "BLOCK_LAVA_EXTINGUISH"
            val volume = config.getDouble("settings.audio.volume", 0.8).toFloat()
            val pitch = config.getDouble("settings.audio.pitch", 1.2).toFloat()
            try {
                val sound = Sound.valueOf(soundName)
                world.playSound(loc, sound, volume, pitch)
            } catch (e: Exception) {}
        }

        // แจ้งเตือนผู้โยนไอเทม
        val msg = config.getString("messages.dried", "%prefix% &eฟองน้ำเปียกถูกความร้อนของนรกระเหยน้ำกลายเป็นฟองน้ำธรรมดาทันที!") ?: ""
        if (msg.isNotEmpty() && throwerUuid != null) {
            val player = plugin.server.getPlayer(throwerUuid)
            if (player != null) {
                module.sendNotification(player, msg)
            }
        }
    }
}

