package core.luminaworld.listener

import core.luminaworld.LuminaCore
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SneakTriggerListener(private val plugin: LuminaCore) : Listener {

    // คลาสเก็บข้อมูลเซสชันความคืบหน้าการย่อตัวของผู้เล่น
    class SneakSession(
        val material: Material,
        var count: Int,
        var lastTime: Long
    )

    private val playerSessions = ConcurrentHashMap<UUID, SneakSession>()

    @EventHandler
    fun onPlayerSneak(event: PlayerToggleSneakEvent) {
        val player = event.player
        
        // สนใจเฉพาะตอน "เริ่มกดย่อตัว" เท่านั้น (ซึ่งรองรับ Bedrock Geyser และการสลับปุ่มย่อใน Java)
        if (!event.isSneaking) return
        
        val uuid = player.uniqueId
        val item = player.inventory.itemInMainHand
        val material = item.type
        
        // ค้นหาโมดูลและชื่อระบบตามไอเทมในมือหลัก
        val moduleInfo = getModuleForHand(material) ?: return
        val moduleName = moduleInfo.first
        val moduleDisplayName = moduleInfo.second
        
        val manager = plugin.moduleManager ?: return
        val module = manager.getModule(moduleName)
        
        // ตรวจสอบว่าโมดูลนั้นๆ ถูกเปิดใช้งานและทำงานอยู่หรือไม่
        if (module == null || !module.isEnabled) return
        
        // ตรวจสอบสิทธิ์ Permission ก่อนเริ่มเก็บสะสมความก้าวหน้า
        if (!module.checkPermission(player)) return

        val now = System.currentTimeMillis()
        
        // ดึงการตั้งค่า required-sneaks และ reset-interval (วินาที)
        val settings = getShortcutSettings(moduleName)
        val requiredSneaks = settings.first
        val resetInterval = settings.second
        val resetIntervalMs = (resetInterval * 1000).toLong()

        val session = playerSessions[uuid]
        
        if (session == null || session.material != material || (now - session.lastTime) > resetIntervalMs) {
            // เริ่มเซสชันการย่อตัวใหม่ (กรณีย่อครั้งแรก เปลี่ยนไอเทม หรือเว้นช่วงนานเกินกำหนด)
            val newSession = SneakSession(material, 1, now)
            playerSessions[uuid] = newSession
            showProgressBar(player, 1, requiredSneaks, moduleDisplayName)
            playTickSound(player, 1, requiredSneaks)
        } else {
            // ย่อตัวสะสมต่อเนื่องในเวลาที่กำหนด
            session.count += 1
            session.lastTime = now
            val currentCount = session.count
            
            showProgressBar(player, currentCount, requiredSneaks, moduleDisplayName)
            
            if (currentCount >= requiredSneaks) {
                // ครบกำหนด -> ล้างเซสชันและสั่งเริ่มทำงาน
                playerSessions.remove(uuid)
                triggerModule(player, moduleName)
                playSuccessSound(player)
            } else {
                playTickSound(player, currentCount, requiredSneaks)
            }
        }
    }

    private fun triggerModule(player: Player, moduleName: String) {
        val manager = plugin.moduleManager ?: return
        
        when (moduleName) {
            "TreeCapitator" -> {
                val treeCap = manager.getModule("TreeCapitator") as? core.luminaworld.modules.TreeCapitator.TreeCapitatorModule
                treeCap?.toggleMode(player)
            }
            "AutoPlanter" -> {
                val planter = manager.getModule("AutoPlanter") as? core.luminaworld.modules.AutoPlanter.AutoPlanterModule
                planter?.runPlanting(player)
            }
            "SpawnChecker" -> {
                val checker = manager.getModule("SpawnChecker") as? core.luminaworld.modules.SpawnChecker.SpawnCheckerModule
                checker?.checkSpawnPoints(player)
            }
            "TimeWeatherViewer" -> {
                val viewer = manager.getModule("TimeWeatherViewer") as? core.luminaworld.modules.TimeWeatherViewer.TimeWeatherViewerModule
                viewer?.showTimeAndWeather(player)
            }
            "CoordDirectionViewer" -> {
                val viewer = manager.getModule("CoordDirectionViewer") as? core.luminaworld.modules.CoordDirectionViewer.CoordDirectionViewerModule
                viewer?.showCoordinatesAndDirection(player)
            }
            "NetherCalculator" -> {
                val calc = manager.getModule("NetherCalculator") as? core.luminaworld.modules.NetherCalculator.NetherCalculatorModule
                calc?.calculateCoordinates(player)
            }
            "VeinMiner" -> {
                val miner = manager.getModule("VeinMiner") as? core.luminaworld.modules.VeinMiner.VeinMinerModule
                miner?.toggleMode(player)
            }
        }
    }

    private fun showProgressBar(player: Player, count: Int, total: Int, systemName: String) {
        // สร้างหลอดความก้าวหน้า เช่น [ ||||...... ]
        val sb = StringBuilder()
        sb.append("§e[ ")
        
        for (i in 1..total) {
            if (i <= count) {
                sb.append("§a|") // ส่วนที่ก้าวหน้าแล้ว
            } else {
                sb.append("§7|") // ส่วนที่เหลือ
            }
        }
        
        sb.append("§e ] ")
        sb.append("§b$count/$total ")
        
        val needed = total - count
        if (needed > 0) {
            sb.append("§7(ย่ออีก $needed ครั้งเพื่อใช้ $systemName)")
        } else {
            sb.append("§a(เปิดทำงานสำเร็จ!)")
        }
        
        player.sendActionBar(sb.toString())
    }

    private fun playTickSound(player: Player, count: Int, total: Int) {
        // คำนวณระดับเสียง (Pitch) ให้สูงขึ้นตามความก้าวหน้า (จาก 0.5 ถึง 2.0)
        val pitch = 0.5f + (count.toFloat() / total.toFloat()) * 1.5f
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, pitch)
    }

    private fun playSuccessSound(player: Player) {
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.0f)
    }

    private fun getShortcutSettings(moduleName: String): Pair<Int, Double> {
        val manager = plugin.moduleManager ?: return Pair(10, 2.5)
        val module = manager.getModule(moduleName)
        
        val req = module?.config?.getInt("settings.required-sneaks")
        val interval = module?.config?.getDouble("settings.reset-interval")
        
        val defaultReq = plugin.config.getInt("shortcut.required-sneaks", 10)
        val defaultInterval = plugin.config.getDouble("shortcut.reset-interval", 2.5)
        
        return Pair(req ?: defaultReq, interval ?: defaultInterval)
    }

    private fun getModuleForHand(material: Material): Triple<String, String, String>? {
        val name = material.name
        return when {
            name.endsWith("_AXE") -> Triple("TreeCapitator", "ระบบตัดไม้หมดต้น", "ขวาน")
            isSeedItem(material) -> Triple("AutoPlanter", "ระบบปลูกพืชรอบตัว", "เมล็ดพืช")
            material == Material.TORCH || material == Material.SOUL_TORCH || material == Material.REDSTONE_TORCH -> 
                Triple("SpawnChecker", "ระบบเช็คจุดเกิดมอนสเตอร์", "คบเพลิง")
            material == Material.CLOCK -> Triple("TimeWeatherViewer", "ระบบแสดงเวลาและอากาศ", "นาฬิกา")
            material == Material.COMPASS -> Triple("CoordDirectionViewer", "ระบบแสดงพิกัดและทิศทาง", "เข็มทิศ")
            material == Material.OBSIDIAN -> Triple("NetherCalculator", "ระบบคำนวณพิกัด Nether/Overworld", " Obsidian")
            name.endsWith("_PICKAXE") -> Triple("VeinMiner", "ระบบขุดแร่รอบตัว", "ที่ขุดแร่")
            else -> null
        }
    }

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
