package core.luminaworld.modules.features.AnimalAutoHeal

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Animals
import org.bukkit.entity.Damageable
import org.bukkit.entity.Player
import io.papermc.paper.threadedregions.scheduler.ScheduledTask

class AnimalAutoHealModule(plugin: LuminaCore) : LuminaModule(plugin, "AnimalAutoHeal") {
    
    private var healTask: ScheduledTask? = null
    private var listener: AnimalAutoHealListener? = null

    override fun onEnable() {
        val newListener = AnimalAutoHealListener(plugin, this)
        plugin.server.pluginManager.registerEvents(newListener, plugin)
        this.listener = newListener
        startHealTask()
    }

    override fun onDisable() {
        healTask?.cancel()
        healTask = null
        listener = null
    }

    private fun startHealTask() {
        val intervalSeconds = config?.getDouble("settings.heal-interval-seconds", 2.0) ?: 2.0
        val intervalTicks = (intervalSeconds * 20).toLong()
        
        healTask = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { _ ->
            for (player in plugin.server.onlinePlayers) {
                val loc = player.location
                // รันงานในเธรด Region ของผู้เล่น เพื่อความปลอดภัยสูงสุดบน Folia
                plugin.server.regionScheduler.execute(plugin, loc) {
                    healNearbyAnimals(player)
                }
            }
        }, 20L, intervalTicks)
    }

    private fun healNearbyAnimals(player: Player) {
        if (!isEnabled) return
        
        // ตรวจสอบ permission ของผู้เล่นรอบตัว
        if (!checkPermission(player)) return

        val config = this.config ?: return
        val range = config.getDouble("settings.activation-range", 16.0)
        val healAmount = config.getDouble("settings.heal-amount", 1.0)
        val cooldownMs = (config.getDouble("settings.combat-cooldown-seconds", 10.0) * 1000).toLong()
        val allowedTypes = config.getStringList("settings.allowed-entities")
        
        val now = System.currentTimeMillis()
        val activeListener = listener ?: return

        val nearby = player.world.getNearbyEntities(player.location, range, range, range)
        for (entity in nearby) {
            if (entity is Animals && entity is Damageable) {
                val typeName = entity.type.name
                if (allowedTypes.isNotEmpty() && !allowedTypes.contains(typeName)) continue

                val currentHealth = entity.health
                val maxHealth = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                if (currentHealth >= maxHealth) continue

                // ตรวจเช็คคูลดาวน์ดาเมจล่าสุด
                val lastDamage = activeListener.lastDamageTime[entity.uniqueId] ?: 0L
                if (now - lastDamage < cooldownMs) continue

                // เพิ่มเลือดเอนทิตี
                val newHealth = (currentHealth + healAmount).coerceAtMost(maxHealth)
                entity.health = newHealth

                // เอฟเฟกต์หัวใจสปอน
                val particleName = config.getString("settings.particles.type", "HEART") ?: "HEART"
                val particleCount = config.getInt("settings.particles.count", 3)
                val particleSpeed = config.getDouble("settings.particles.speed", 0.02)
                try {
                    val pType = org.bukkit.Particle.valueOf(particleName)
                    entity.world.spawnParticle(pType, entity.eyeLocation, particleCount, 0.3, 0.3, 0.3, particleSpeed)
                } catch (e: Exception) {}

                // เล่นเสียงเอฟเฟกต์ โดย Pitch จะแปรผันตามปริมาณเลือดปัจจุบัน (ยิ่งเลือดเต็มเสียงยิ่งแหลมสูง)
                val audioEnabled = config.getBoolean("settings.audio.enabled", true)
                if (audioEnabled) {
                    val soundName = config.getString("settings.audio.sound", "ENTITY_EXPERIENCE_ORB_PICKUP") ?: "ENTITY_EXPERIENCE_ORB_PICKUP"
                    val volume = config.getDouble("settings.audio.volume", 0.6).toFloat()
                    val pitchStart = config.getDouble("settings.audio.pitch-start", 0.5).toFloat()
                    val pitchEnd = config.getDouble("settings.audio.pitch-end", 1.5).toFloat()
                    
                    val healthRatio = (newHealth / maxHealth).toFloat()
                    val pitch = pitchStart + healthRatio * (pitchEnd - pitchStart)

                    try {
                        val sound = Sound.valueOf(soundName)
                        player.playSound(entity.location, sound, volume, pitch)
                    } catch (e: Exception) {}
                }
            }
        }
    }
}
