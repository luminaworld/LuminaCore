package core.luminaworld.modules.features.AutoEatReplenish

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.EquipmentSlot
import core.luminaworld.LuminaCore

class AutoEatReplenishListener(private val plugin: LuminaCore, private val module: AutoEatReplenishModule) : Listener {

    @EventHandler
    fun onItemConsume(event: PlayerItemConsumeEvent) {
        if (!module.isEnabled) return
        val player = event.player
        if (!module.checkPermission(player)) return

        val item = event.item
        val material = item.type

        // ตรวจสอบว่าสิ่งที่กินเข้าไปเป็นอาหารที่กินได้ (ไม่รวมของแปลกอื่นๆ เช่น ยาโพชั่น)
        if (!material.isEdible) return

        // หากปริมาณอาหารเหลือชิ้นสุดท้าย กำลังจะหมดลงหลังจากกินเสร็จ
        if (item.amount <= 1) {
            val hand = if (player.inventory.itemInMainHand == item) EquipmentSlot.HAND else EquipmentSlot.OFF_HAND
            scheduleReplenish(player, hand, material)
        }
    }

    private fun scheduleReplenish(player: Player, hand: EquipmentSlot, previousMaterial: Material) {
        player.scheduler.execute(plugin, {
            val inventory = player.inventory
            val currentItem = if (hand == EquipmentSlot.HAND) inventory.itemInMainHand else inventory.itemInOffHand

            // หากช่องมือนั้นว่างเปล่าแล้วจริงๆ (อาหารหมดเรียบร้อย)
            if (currentItem.type == Material.AIR) {
                val targetSlot = findReplenishFood(player, previousMaterial, hand)
                if (targetSlot != -1) {
                    val replacement = inventory.getItem(targetSlot) ?: return@execute

                    if (hand == EquipmentSlot.HAND) {
                        inventory.setItemInMainHand(replacement)
                    } else {
                        inventory.setItemInOffHand(replacement)
                    }
                    inventory.setItem(targetSlot, null)

                    // เล่นเสียง
                    playReplenishSound(player)

                    val config = module.config ?: return@execute
                    val msg = config.getString("messages.replenished", "%prefix% &aดึงอาหาร %item% มาใส่ในมือเรียบร้อย!") ?: ""
                    if (msg.isNotEmpty()) {
                        module.sendNotification(player, msg.replace("%item%", replacement.type.name))
                    }
                }
            }
        }, null, 1L)
    }

    private fun findReplenishFood(player: Player, previousMaterial: Material, hand: EquipmentSlot): Int {
        val inv = player.inventory
        val activeSlot = if (hand == EquipmentSlot.HAND) inv.heldItemSlot else -1

        // 1. ตรวจสอบอาหารประเภทเดียวกันก่อน
        for (i in 0..35) {
            if (i == activeSlot) continue
            val item = inv.getItem(i) ?: continue
            if (item.type == previousMaterial && item.amount > 0) {
                return i
            }
        }

        val config = module.config ?: return -1

        // 2. ค้นหาตามลำดับความต้องการสำรอง (Fallback Priority)
        val priorityList = config.getStringList("settings.fallback-priority")
        for (foodName in priorityList) {
            val mat = try { Material.valueOf(foodName) } catch (e: Exception) { null } ?: continue
            for (i in 0..35) {
                if (i == activeSlot) continue
                val item = inv.getItem(i) ?: continue
                if (item.type == mat && item.amount > 0) {
                    return i
                }
            }
        }

        // 3. ค้นหาจากอาหารที่กินได้ (Edible) ใดๆ ในกระเป๋าที่ปลอดภัย
        val allowUnsafe = config.getBoolean("settings.allow-unsafe-food", false)
        val blacklist = config.getStringList("settings.unsafe-food-blacklist")

        for (i in 0..35) {
            if (i == activeSlot) continue
            val item = inv.getItem(i) ?: continue
            val mat = item.type
            if (mat.isEdible && item.amount > 0) {
                val isUnsafe = blacklist.contains(mat.name)
                if (allowUnsafe || !isUnsafe) {
                    return i
                }
            }
        }

        return -1
    }

    private fun playReplenishSound(player: Player) {
        val config = module.config ?: return
        val enabled = config.getBoolean("settings.audio.enabled", true)
        if (!enabled) return

        val soundName = config.getString("settings.audio.sound", "ENTITY_ITEM_PICKUP") ?: "ENTITY_ITEM_PICKUP"
        val volume = config.getDouble("settings.audio.volume", 0.5).toFloat()
        val pitch = config.getDouble("settings.audio.pitch", 1.0).toFloat()

        try {
            val sound = Sound.valueOf(soundName)
            player.playSound(player.location, sound, volume, pitch)
        } catch (e: Exception) {}
    }
}
