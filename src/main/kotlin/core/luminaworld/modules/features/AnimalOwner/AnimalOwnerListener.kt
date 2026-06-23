package core.luminaworld.modules.features.AnimalOwner

import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import core.luminaworld.LuminaCore

class AnimalOwnerListener(private val plugin: LuminaCore, private val module: AnimalOwnerModule) : Listener {

    @EventHandler
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        if (!module.isEnabled) return
        val player = event.player

        // ทำงานเฉพาะคลิกมือหลัก (ป้องกันการยิงเรียกเหตุการณ์เบิ้ลซ้ำในมือหลักและมือรอง)
        if (event.hand != EquipmentSlot.HAND) return

        val entity = event.rightClicked
        if (player.isSneaking && entity is Tameable) {
            // ยกเลิกเหตุการณ์ขึ้นขี่หรือเปิดกระเป๋าสัมภาระของม้า
            event.isCancelled = true

            // ตรวจสอบสิทธิ์การใช้งาน
            if (!module.checkPermission(player)) return

            val config = module.config ?: return
            val allowedTypes = config.getStringList("settings.allowed-types")
            val typeName = entity.type.name

            if (allowedTypes.isNotEmpty() && !allowedTypes.contains(typeName)) return

            val isTamed = entity.isTamed
            val owner = entity.owner

            val maxHealth = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            val currentHealth = entity.health
            val customName = if (entity.customName != null) {
                entity.customName
            } else {
                entity.type.name.lowercase().replaceFirstChar { it.uppercase() }
            }

            if (isTamed && owner != null) {
                val ownerName = owner.name ?: owner.uniqueId.toString()
                val msg = config.getString("messages.has-owner", "%prefix% &e%pet_name% เป็นสัตว์เลี้ยงของ &a%owner% &7(&c%health%/%max_health% HP&7)") ?: ""
                module.sendNotification(player, msg
                    .replace("%pet_name%", customName ?: "")
                    .replace("%owner%", ownerName)
                    .replace("%health%", String.format("%.1f", currentHealth))
                    .replace("%max_health%", String.format("%.1f", maxHealth))
                )
            } else {
                val msg = config.getString("messages.no-owner", "%prefix% &7%pet_name% ตัวนี้ยังไม่มีเจ้าของ (เป็นสัตว์ป่า)") ?: ""
                module.sendNotification(player, msg
                    .replace("%pet_name%", customName ?: "")
                )
            }

            // เล่นเสียงแจ้งผล
            playCheckSound(player)
        }
    }

    private fun playCheckSound(player: Player) {
        val config = module.config ?: return
        val enabled = config.getBoolean("settings.audio.enabled", true)
        if (!enabled) return

        val soundName = config.getString("settings.audio.sound", "ENTITY_EXPERIENCE_ORB_PICKUP") ?: "ENTITY_EXPERIENCE_ORB_PICKUP"
        val volume = config.getDouble("settings.audio.volume", 0.6).toFloat()
        val pitch = config.getDouble("settings.audio.pitch", 1.2).toFloat()

        try {
            val sound = Sound.valueOf(soundName)
            player.playSound(player.location, sound, volume, pitch)
        } catch (e: Exception) {}
    }
}
