package core.luminaworld.modules.features.AnimalAutoHeal

import org.bukkit.entity.Animals
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import core.luminaworld.LuminaCore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AnimalAutoHealListener(
    private val plugin: LuminaCore,
    private val module: AnimalAutoHealModule
) : Listener {

    // เก็บเวลาโดนโจมตีล่าสุดของเอนทิตีป้องกันการฮีลระหว่างต่อสู้
    val lastDamageTime = ConcurrentHashMap<UUID, Long>()

    @EventHandler
    fun onEntityDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity is Animals) {
            lastDamageTime[entity.uniqueId] = System.currentTimeMillis()
        }
    }
}
