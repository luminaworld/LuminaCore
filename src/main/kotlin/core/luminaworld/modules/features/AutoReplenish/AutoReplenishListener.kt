package core.luminaworld.modules.features.AutoReplenish

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.EquipmentSlot
import core.luminaworld.LuminaCore

class AutoReplenishListener(private val plugin: LuminaCore, private val module: AutoReplenishModule) : Listener {

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!module.isEnabled) return
        val player = event.player
        if (!module.checkPermission(player)) return

        val config = module.config ?: return
        if (!config.getBoolean("settings.check-blocks", true)) return

        val item = event.itemInHand
        if (item.amount <= 1) {
            scheduleReplenishCheck(player, event.hand, item.type)
        }
    }

    @EventHandler
    fun onItemBreak(event: PlayerItemBreakEvent) {
        if (!module.isEnabled) return
        val player = event.player
        if (!module.checkPermission(player)) return

        val config = module.config ?: return
        if (!config.getBoolean("settings.check-broken-tools", true)) return

        val broken = event.brokenItem
        val material = broken.type

        // ตรวจหาว่าไอเทมที่กำลังจะพังอยู่ในมือไหน
        val hand = if (player.inventory.itemInMainHand.type == material) EquipmentSlot.HAND else EquipmentSlot.OFF_HAND
        scheduleReplenishCheck(player, hand, material)
    }

    @EventHandler
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (!module.isEnabled) return
        val player = event.player
        if (!module.checkPermission(player)) return

        val config = module.config ?: return
        if (!config.getBoolean("settings.check-buckets", true)) return

        val item = event.itemStack ?: return
        scheduleReplenishCheck(player, event.hand, item.type)
    }

    @EventHandler
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (!module.isEnabled) return
        val player = event.player
        if (!module.checkPermission(player)) return

        val config = module.config ?: return
        if (!config.getBoolean("settings.check-buckets", true)) return

        val item = event.itemStack ?: return
        scheduleReplenishCheck(player, event.hand, item.type)
    }

    @EventHandler
    fun onItemConsume(event: PlayerItemConsumeEvent) {
        if (!module.isEnabled) return
        val player = event.player
        if (!module.checkPermission(player)) return

        val item = event.item
        if (item.amount <= 1) {
            // ค้นหามือที่ใช้กิน
            val hand = if (player.inventory.itemInMainHand == item) EquipmentSlot.HAND else EquipmentSlot.OFF_HAND
            scheduleReplenishCheck(player, hand, item.type)
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (!module.isEnabled) return
        val player = event.player
        if (!module.checkPermission(player)) return

        val item = event.item ?: return
        val material = item.type

        // ตรวจเช็คการใช้งานไอเทมปา/ใช้งานทั่วไป เช่น หิมะ ไข่ ยาปา
        val config = module.config ?: return
        val checkProjectiles = config.getBoolean("settings.check-projectiles", true)
        val isProjectile = material == Material.SNOWBALL || material == Material.EGG || 
                           material == Material.ENDER_PEARL || material == Material.EXPERIENCE_BOTTLE ||
                           material.name.endsWith("_POTION")

        if (checkProjectiles && isProjectile && item.amount <= 1) {
            scheduleReplenishCheck(player, event.hand ?: EquipmentSlot.HAND, material)
        }
    }

    private fun scheduleReplenishCheck(player: Player, hand: EquipmentSlot, previousMaterial: Material) {
        if (previousMaterial == Material.AIR) return

        // ดีเลย์ 1 tick เพื่อให้ Bukkit จัดการหักไอเทมและอัปเดตช่องเสร็จสิ้นก่อน
        player.scheduler.execute(plugin, {
            val inventory = player.inventory
            val currentItem = if (hand == EquipmentSlot.HAND) inventory.itemInMainHand else inventory.itemInOffHand

            val isNowEmpty = currentItem.type == Material.AIR
            val isBucketReplenish = isBucket(previousMaterial) && currentItem.type == Material.BUCKET

            if (isNowEmpty || isBucketReplenish) {
                val targetSlot = findReplenishItem(player, previousMaterial, hand)
                if (targetSlot != -1) {
                    val replacement = inventory.getItem(targetSlot) ?: return@execute

                    if (isNowEmpty) {
                        if (hand == EquipmentSlot.HAND) {
                            inventory.setItemInMainHand(replacement)
                        } else {
                            inventory.setItemInOffHand(replacement)
                        }
                        inventory.setItem(targetSlot, null)
                    } else if (isBucketReplenish) {
                        // สลับเปลี่ยนถังเปล่าในมือด้วยถังบรรจุน้ำ/ลาวาชิ้นถัดไป
                        val emptyBucket = currentItem.clone()
                        if (hand == EquipmentSlot.HAND) {
                            inventory.setItemInMainHand(replacement)
                        } else {
                            inventory.setItemInOffHand(replacement)
                        }
                        inventory.setItem(targetSlot, emptyBucket)
                    }

                    // เล่นเสียงตอนเติมของ
                    playReplenishSound(player)

                    val config = module.config ?: return@execute
                    val msg = config.getString("messages.replenished", "%prefix% &aเติม %item% ในมือให้อัตโนมัติ!") ?: ""
                    if (msg.isNotEmpty()) {
                        module.sendNotification(player, msg.replace("%item%", previousMaterial.name))
                    }
                }
            }
        }, null, 1L)
    }

    private fun findReplenishItem(player: Player, material: Material, hand: EquipmentSlot): Int {
        val inv = player.inventory
        val activeSlot = if (hand == EquipmentSlot.HAND) inv.heldItemSlot else -1

        for (i in 0..35) {
            if (i == activeSlot) continue
            val item = inv.getItem(i) ?: continue
            if (item.type == material && item.amount > 0) {
                return i
            }
        }
        return -1
    }

    private fun isBucket(material: Material): Boolean {
        return material == Material.WATER_BUCKET || material == Material.LAVA_BUCKET ||
               material == Material.MILK_BUCKET || material == Material.POWDER_SNOW_BUCKET ||
               material.name.endsWith("_BUCKET")
     }

    private fun playReplenishSound(player: Player) {
        val config = module.config ?: return
        val enabled = config.getBoolean("settings.audio.enabled", true)
        if (!enabled) return

        val soundName = config.getString("settings.audio.sound", "ITEM_ARMOR_EQUIP_GENERIC") ?: "ITEM_ARMOR_EQUIP_GENERIC"
        val volume = config.getDouble("settings.audio.volume", 0.6).toFloat()
        val pitch = config.getDouble("settings.audio.pitch", 1.2).toFloat()

        try {
            val sound = Sound.valueOf(soundName)
            player.playSound(player.location, sound, volume, pitch)
        } catch (e: Exception) {}
    }
}
