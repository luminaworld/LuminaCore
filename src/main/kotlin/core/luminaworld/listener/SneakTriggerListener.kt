package core.luminaworld.listener

import core.luminaworld.LuminaCore
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SneakTriggerListener(private val plugin: LuminaCore) : Listener {

    private val sneakHistory = ConcurrentHashMap<UUID, MutableList<Long>>()
    
    @EventHandler
    fun onPlayerSneak(event: PlayerToggleSneakEvent) {
        val player = event.player
        
        // สนใจเฉพาะตอน "เริ่มกดย่อตัว" เท่านั้น (ซึ่งรองรับ Geyser และปุ่ม Ctrl)
        if (!event.isSneaking) return
        
        val uuid = player.uniqueId
        val now = System.currentTimeMillis()
        
        val history = sneakHistory.computeIfAbsent(uuid) { ArrayList() }
        history.add(now)
        
        // ล้างข้อมูลประวัติที่เก่ากว่า 3.5 วินาที
        history.removeIf { now - it > 3500 }
        
        // เมื่อกดย่อตัวครบ 10 ครั้ง
        if (history.size >= 10) {
            history.clear()
            handleSneakTrigger(player)
        }
    }
    
    private fun handleSneakTrigger(player: Player) {
        val item = player.inventory.itemInMainHand
        val type = item.type
        val manager = plugin.moduleManager ?: return
        
        // 1. ถือ ขวาน -> TreeCapitator
        if (type.name.endsWith("_AXE")) {
            val module = manager.getModule("TreeCapitator")
            if (module != null && module.isEnabled) {
                if (module.checkPermission(player)) {
                    val treeCap = module as? core.luminaworld.modules.TreeCapitator.TreeCapitatorModule
                    treeCap?.toggleMode(player)
                }
            }
        }
        
        // 2. ถือ เมล็ดพืช -> AutoPlanter
        else if (isSeedItem(type)) {
            val module = manager.getModule("AutoPlanter")
            if (module != null && module.isEnabled) {
                if (module.checkPermission(player)) {
                    val planter = module as? core.luminaworld.modules.AutoPlanter.AutoPlanterModule
                    planter?.runPlanting(player)
                }
            }
        }
        
        // 3. ถือ คบเพลิง -> SpawnChecker
        else if (type == Material.TORCH || type == Material.SOUL_TORCH || type == Material.REDSTONE_TORCH) {
            val module = manager.getModule("SpawnChecker")
            if (module != null && module.isEnabled) {
                if (module.checkPermission(player)) {
                    val checker = module as? core.luminaworld.modules.SpawnChecker.SpawnCheckerModule
                    checker?.checkSpawnPoints(player)
                }
            }
        }
        
        // 4. ถือ นาฬิกา -> TimeWeatherViewer
        else if (type == Material.CLOCK) {
            val module = manager.getModule("TimeWeatherViewer")
            if (module != null && module.isEnabled) {
                if (module.checkPermission(player)) {
                    val viewer = module as? core.luminaworld.modules.TimeWeatherViewer.TimeWeatherViewerModule
                    viewer?.showTimeAndWeather(player)
                }
            }
        }
        
        // 5. ถือ เข็มทิศ (กดย่อ 10 ครั้ง) -> CoordDirectionViewer
        else if (type == Material.COMPASS) {
            val module = manager.getModule("CoordDirectionViewer")
            if (module != null && module.isEnabled) {
                if (module.checkPermission(player)) {
                    val viewer = module as? core.luminaworld.modules.CoordDirectionViewer.CoordDirectionViewerModule
                    viewer?.showCoordinatesAndDirection(player)
                }
            }
        }
        
        // 6. ถือ Obsidian -> NetherCalculator
        else if (type == Material.OBSIDIAN) {
            val module = manager.getModule("NetherCalculator")
            if (module != null && module.isEnabled) {
                if (module.checkPermission(player)) {
                    val calc = module as? core.luminaworld.modules.NetherCalculator.NetherCalculatorModule
                    calc?.calculateCoordinates(player)
                }
            }
        }
        
        // 8. ถือ ที่ขุด -> VeinMiner
        else if (type.name.endsWith("_PICKAXE")) {
            val module = manager.getModule("VeinMiner")
            if (module != null && module.isEnabled) {
                if (module.checkPermission(player)) {
                    val miner = module as? core.luminaworld.modules.VeinMiner.VeinMinerModule
                    miner?.toggleMode(player)
                }
            }
        }
    }
    
    // ดักฟังปุ่มลัด Shift + คลิกขวา สำหรับ DistanceMeasurer
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val action = event.action
        
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            val item = player.inventory.itemInMainHand
            if (item.type == Material.COMPASS && player.isSneaking) {
                event.isCancelled = true
                val manager = plugin.moduleManager ?: return
                val module = manager.getModule("DistanceMeasurer")
                if (module != null && module.isEnabled) {
                    if (module.checkPermission(player)) {
                        val measurer = module as? core.luminaworld.modules.DistanceMeasurer.DistanceMeasurerModule
                        measurer?.toggleMeasureMode(player)
                    }
                }
            }
        }
    }
    
    private fun isSeedItem(material: Material): Boolean {
        val name = material.name
        return name.endsWith("_SEEDS") || name == "CARROT" || name == "POTATO" || 
               name == "SWEET_BERRIES" || name == "PITCHER_POD" || name == "COCOA_BEANS" ||
               name == "NETHER_WART"
    }
}
