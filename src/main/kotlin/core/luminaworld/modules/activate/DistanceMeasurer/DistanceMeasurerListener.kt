package core.luminaworld.modules.activate.DistanceMeasurer

import core.luminaworld.LuminaCore
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

class DistanceMeasurerListener(
    private val plugin: LuminaCore,
    private val module: DistanceMeasurerModule
) : Listener {

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val action = event.action
        val item = player.inventory.itemInMainHand

        // สนใจการคลิกขวาปกติที่บล็อกขณะเปิดโหมดวัดระยะทางและถือ Compass
        if (action == Action.RIGHT_CLICK_BLOCK) {
            if (module.isEnabled && module.isMeasureModeEnabled(player) && item.type == Material.COMPASS) {
                // หากกดย่ออยู่จะถือเป็นคีย์ลัดสลับโหมด ซึ่งตรวจจับใน SneakTriggerListener แล้ว
                // ดังนั้นตรวจจับเฉพาะตอนที่ผู้เล่นไม่ได้กดย่อตัวเท่านั้น
                if (!player.isSneaking) {
                    event.isCancelled = true
                    val clickedBlock = event.clickedBlock ?: return
                    
                    if (module.checkPermission(player)) {
                        module.handleBlockClick(player, clickedBlock.location)
                    }
                }
            }
        }
    }
}
