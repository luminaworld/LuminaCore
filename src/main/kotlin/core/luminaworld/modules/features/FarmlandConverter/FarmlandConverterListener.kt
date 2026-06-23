package core.luminaworld.modules.features.FarmlandConverter

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.Damageable
import core.luminaworld.LuminaCore
import java.util.Random

class FarmlandConverterListener(private val plugin: LuminaCore, private val module: FarmlandConverterModule) : Listener {

    private val random = Random()

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (!module.isEnabled) return
        val player = event.player
        val action = event.action

        if (action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return // ทำงานเฉพาะมือหลัก

        val block = event.clickedBlock ?: return
        if (block.type != Material.FARMLAND) return

        val item = player.inventory.itemInMainHand
        if (!item.type.name.endsWith("_HOE")) return

        // ตรวจสิทธิ์ Permission ประจำระบบ
        if (!module.checkPermission(player)) return

        event.isCancelled = true

        val config = module.config ?: return
        val targetMaterialName = config.getString("settings.to-material", "DIRT") ?: "DIRT"
        val targetMaterial = try {
            Material.valueOf(targetMaterialName)
        } catch (e: Exception) {
            Material.DIRT
        }

        val blockLoc = block.location
        // เปลี่ยนแปลงข้อมูลบล็อกบน Region Scheduler ของพิกัดนี้
        plugin.server.regionScheduler.execute(plugin, blockLoc) {
            if (block.type != Material.FARMLAND) return@execute

            block.type = targetMaterial

            // ลดความทนทานจอบ (คิดผลลัพธ์ของ Unbreaking)
            val durabilityCost = config.getInt("settings.durability-cost", 1)
            val unbreakingLevel = item.getEnchantmentLevel(Enchantment.UNBREAKING)
            var applyDamage = true

            if (unbreakingLevel > 0) {
                val chance = 1.0 / (unbreakingLevel + 1.0)
                if (random.nextDouble() >= chance) {
                    applyDamage = false
                }
            }

            if (applyDamage) {
                val meta = item.itemMeta
                if (meta is Damageable) {
                    meta.damage = meta.damage + durabilityCost
                    if (meta.damage >= item.type.maxDurability) {
                        // จอบพังเสียหาย - ตั้ง amount = 0 และอัปเดตใน inventory
                        item.amount = 0
                        player.inventory.setItemInMainHand(item)
                        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f)
                    } else {
                        // อัปเดต meta กลับลง inventory จริงๆ
                        item.itemMeta = meta
                        player.inventory.setItemInMainHand(item)
                    }
                }
            }

            // สปอนฝุ่นดินแตกกระจาย
            val particlesEnabled = config.getBoolean("settings.particles.enabled", true)
            if (particlesEnabled) {
                val particleName = config.getString("settings.particles.type", "BLOCK") ?: "BLOCK"
                val count = config.getInt("settings.particles.count", 15)
                val speed = config.getDouble("settings.particles.speed", 0.05)
                try {
                    val pType = org.bukkit.Particle.valueOf(particleName)
                    block.world.spawnParticle(pType, blockLoc.add(0.5, 0.5, 0.5), count, 0.3, 0.2, 0.3, speed, block.blockData)
                } catch (e: Exception) {}
            }

            // เล่นเสียงขูดบล็อก
            val audioEnabled = config.getBoolean("settings.audio.enabled", true)
            if (audioEnabled) {
                val soundName = config.getString("settings.audio.sound", "BLOCK_GRAVEL_BREAK") ?: "BLOCK_GRAVEL_BREAK"
                val volume = config.getDouble("settings.audio.volume", 0.8).toFloat()
                val pitch = config.getDouble("settings.audio.pitch", 0.9).toFloat() // ขุดจอบระดับ pitch ทุ้มเล็กน้อย
                try {
                    val sound = Sound.valueOf(soundName)
                    block.world.playSound(blockLoc, sound, volume, pitch)
                } catch (e: Exception) {}
            }

            // ส่งการแจ้งเตือน
            val msg = config.getString("messages.converted", "%prefix% &aแปลงดินเพาะปลูก Farmland กลับเป็นดินธรรมดาเรียบร้อย!") ?: ""
            if (msg.isNotEmpty()) {
                player.scheduler.execute(plugin, {
                    module.sendNotification(player, msg)
                }, null, 0L)
            }
        }
    }
}
