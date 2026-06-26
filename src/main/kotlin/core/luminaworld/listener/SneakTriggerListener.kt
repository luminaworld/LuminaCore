package core.luminaworld.listener

import core.luminaworld.LuminaCore
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.block.Action
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SneakTriggerListener(private val plugin: LuminaCore) : Listener {

    class SneakSession(
        val material: Material,
        var count: Int,
        var lastTime: Long
    )

    private val playerSessions = ConcurrentHashMap<UUID, SneakSession>()
    private val triggerCooldowns = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>>()

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

        // ถ้าถือเข็มทิศและระบบวัดระยะทาง (DistanceMeasurer) กำลังใช้งานอยู่ ให้ปิดการทำงานของพิกัดทันที
        if (material == Material.COMPASS) {
            val distanceMeasurer = manager.getModule("DistanceMeasurer") as? core.luminaworld.modules.activate.DistanceMeasurer.DistanceMeasurerModule
            if (distanceMeasurer != null && distanceMeasurer.isEnabled && distanceMeasurer.isMeasureModeEnabled(player)) {
                return
            }
        }

        val module = manager.getModule(moduleName)
        
        // ตรวจสอบว่าโมดูลนั้นๆ ถูกเปิดใช้งานและทำงานอยู่หรือไม่
        if (module == null || !module.isEnabled) return
        
        val now = System.currentTimeMillis()
        
        // ตรวจสอบคูลดาวน์จากการใช้ระบบครั้งล่าสุด (ป้องกันหลอดสะสมใหม่ทับพิกัดเดิมขณะรัวปุ่ม)
        val lastTriggerTime = triggerCooldowns[uuid]?.get(moduleName) ?: 0L
        val cooldownSec = module.config?.getDouble("settings.cooldown", 3.0) ?: 3.0
        val cooldownMs = (cooldownSec * 1000).toLong()
        
        if (now - lastTriggerTime < cooldownMs) {
            return
        }

        // ตรวจสอบสิทธิ์ Permission ก่อนเริ่มเก็บสะสมความก้าวหน้า
        if (!module.checkPermission(player)) return

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
            showProgressBar(player, 1, requiredSneaks, moduleName, moduleDisplayName)
            playTickSound(player, 1, requiredSneaks, moduleName)
        } else {
            // ย่อตัวสะสมต่อเนื่องในเวลาที่กำหนด
            session.count += 1
            session.lastTime = now
            val currentCount = session.count
            
            showProgressBar(player, currentCount, requiredSneaks, moduleName, moduleDisplayName)
            
            if (currentCount >= requiredSneaks) {
                // ครบกำหนด -> ล้างเซสชันและสั่งเริ่มทำงาน
                playerSessions.remove(uuid)
                triggerModule(player, moduleName)
                
                // บันทึกเวลาคูลดาวน์การทริกเกอร์ระบบสำเร็จ
                triggerCooldowns.computeIfAbsent(uuid) { ConcurrentHashMap() }[moduleName] = now
                
                playSuccessSound(player, moduleName)
            } else {
                playTickSound(player, currentCount, requiredSneaks, moduleName)
            }
        }
    }

    private fun triggerModule(player: Player, moduleName: String) {
        val manager = plugin.moduleManager ?: return
        
        when (moduleName) {
            "TreeCapitator" -> {
                val treeCap = manager.getModule("TreeCapitator") as? core.luminaworld.modules.activate.TreeCapitator.TreeCapitatorModule
                treeCap?.toggleMode(player)
            }
            "AutoPlanter" -> {
                val planter = manager.getModule("AutoPlanter") as? core.luminaworld.modules.activate.AutoPlanter.AutoPlanterModule
                planter?.runPlanting(player)
            }
            "SpawnChecker" -> {
                val checker = manager.getModule("SpawnChecker") as? core.luminaworld.modules.activate.SpawnChecker.SpawnCheckerModule
                checker?.checkSpawnPoints(player)
            }
            "TimeWeatherViewer" -> {
                val viewer = manager.getModule("TimeWeatherViewer") as? core.luminaworld.modules.activate.TimeWeatherViewer.TimeWeatherViewerModule
                viewer?.showTimeAndWeather(player)
            }
            "CoordDirectionViewer" -> {
                val viewer = manager.getModule("CoordDirectionViewer") as? core.luminaworld.modules.activate.CoordDirectionViewer.CoordDirectionViewerModule
                viewer?.showCoordinatesAndDirection(player)
            }
            "NetherCalculator" -> {
                val calc = manager.getModule("NetherCalculator") as? core.luminaworld.modules.activate.NetherCalculator.NetherCalculatorModule
                calc?.calculateCoordinates(player)
            }
            "VeinMiner" -> {
                val miner = manager.getModule("VeinMiner") as? core.luminaworld.modules.activate.VeinMiner.VeinMinerModule
                miner?.toggleMode(player)
            }
        }
    }

    private fun showProgressBar(player: Player, count: Int, total: Int, moduleName: String, systemName: String) {
        val manager = plugin.moduleManager ?: return
        val module = manager.getModule(moduleName)
        val config = module?.config
        
        // ตรวจสอบเปิด/ปิด Progress Bar สำหรับโมดูลนี้
        val enabled = config?.getBoolean("settings.progress-bar.enabled", true) ?: true
        if (!enabled) return

        // ดึงชื่อระบบย่อยจาก config หรือใช้ค่าเริ่มต้นจากโค้ด
        val systemNameFromConfig = config?.getString("settings.system-name")
        val finalSystemName = systemNameFromConfig ?: systemName

        // ดึงการตั้งค่าหลอดความก้าวหน้าอย่างละเอียด
        val charCompleted = config?.getString("settings.progress-bar.char-completed", "|") ?: "|"
        val charRemaining = config?.getString("settings.progress-bar.char-remaining", "|") ?: "|"
        val colorCompleted = config?.getString("settings.progress-bar.color-completed", "&a") ?: "&a"
        val colorRemaining = config?.getString("settings.progress-bar.color-remaining", "&7") ?: "&7"
        val colorBracket = config?.getString("settings.progress-bar.color-bracket", "&e") ?: "&e"
        val colorNumber = config?.getString("settings.progress-bar.color-number", "&b") ?: "&b"
        val colorText = config?.getString("settings.progress-bar.color-text", "&7") ?: "&7"
        
        val defaultFormat = "%color_text%(ย่ออีก %needed% ครั้งเพื่อใช้ %system%) %color_number%%count%/%total% %color_bracket%[ %completed%%remaining% %color_bracket%]"
        val format = config?.getString("settings.progress-bar.format", defaultFormat) ?: defaultFormat

        // คำนวณ completed string
        val compSb = java.lang.StringBuilder()
        compSb.append(colorCompleted)
        for (i in 1..count) {
            compSb.append(charCompleted)
        }
        val completedStr = compSb.toString()

        // คำนวณ remaining string
        val remSb = java.lang.StringBuilder()
        remSb.append(colorRemaining)
        val remainingCount = total - count
        for (i in 1..remainingCount) {
            remSb.append(charRemaining)
        }
        val remainingStr = remSb.toString()

        val prefix = plugin.config.getString("settings.prefix", "[LuminaCore]") ?: "[LuminaCore]"

        // ทำการแทนที่ Placeholders
        val finalMsg = format
            .replace("%completed%", completedStr)
            .replace("%remaining%", remainingStr)
            .replace("%count%", count.toString())
            .replace("%total%", total.toString())
            .replace("%needed%", remainingCount.toString())
            .replace("%system%", finalSystemName)
            .replace("%prefix%", prefix)
            .replace("%color_bracket%", colorBracket)
            .replace("%color_number%", colorNumber)
            .replace("%color_text%", colorText)
            .replace("&", "§")

        player.sendActionBar(finalMsg)
    }

    private fun playTickSound(player: Player, count: Int, total: Int, moduleName: String) {
        val manager = plugin.moduleManager ?: return
        val module = manager.getModule(moduleName)
        val config = module?.config

        // ตรวจสอบสวิตช์เปิดปิดเสียง
        val enabled = config?.getBoolean("settings.audio.enabled", true) ?: true
        if (!enabled) return

        val tickEnabled = config?.getBoolean("settings.audio.tick-enabled", true) ?: true
        if (!tickEnabled) return

        // ดึงการกำหนดค่าเสียงและ Pitch
        val soundName = config?.getString("settings.audio.tick-sound", "BLOCK_NOTE_BLOCK_PLING") ?: "BLOCK_NOTE_BLOCK_PLING"
        val volume = config?.getDouble("settings.audio.tick-volume", 0.5) ?: 0.5
        val pitchStart = config?.getDouble("settings.audio.tick-pitch-start", 0.5) ?: 0.5
        val pitchEnd = config?.getDouble("settings.audio.tick-pitch-end", 2.0) ?: 2.0

        val sound = try {
            Sound.valueOf(soundName)
        } catch (e: Exception) {
            Sound.BLOCK_NOTE_BLOCK_PLING // fallback ถ้าเขียนสะกดผิด
        }

        // คำนวณระดับความถี่ของ Pitch (ไล่เสียงตามน้ำหนักความก้าวหน้า)
        val ratio = count.toFloat() / total.toFloat()
        val pitch = pitchStart.toFloat() + ratio * (pitchEnd.toFloat() - pitchStart.toFloat())

        player.playSound(player.location, sound, volume.toFloat(), pitch)
    }

    private fun playSuccessSound(player: Player, moduleName: String) {
        val manager = plugin.moduleManager ?: return
        val module = manager.getModule(moduleName)
        val config = module?.config

        val enabled = config?.getBoolean("settings.audio.enabled", true) ?: true
        if (!enabled) return

        val successEnabled = config?.getBoolean("settings.audio.success-enabled", true) ?: true
        if (!successEnabled) return

        val soundName = config?.getString("settings.audio.success-sound", "ENTITY_PLAYER_LEVELUP") ?: "ENTITY_PLAYER_LEVELUP"
        val volume = config?.getDouble("settings.audio.success-volume", 0.8) ?: 0.8
        val pitch = config?.getDouble("settings.audio.success-pitch", 1.0) ?: 1.0

        val sound = try {
            Sound.valueOf(soundName)
        } catch (e: Exception) {
            Sound.ENTITY_PLAYER_LEVELUP
        }

        player.playSound(player.location, sound, volume.toFloat(), pitch.toFloat())
    }

    private fun getShortcutSettings(moduleName: String): Pair<Int, Double> {
        val manager = plugin.moduleManager ?: return Pair(10, 2.5)
        val module = manager.getModule(moduleName)

        val defaultReq = plugin.config.getInt("shortcut.required-sneaks", 10)
        val defaultInterval = plugin.config.getDouble("shortcut.reset-interval", 2.5)
        val overrideGlobal = plugin.config.getBoolean("shortcut.override-global", false)

        if (overrideGlobal) {
            return Pair(defaultReq, defaultInterval)
        }

        // ใช้ takeIf { it > 0 } เพราะ YamlConfiguration.getInt() คืน 0 เมื่อ key ไม่พบ
        // ซึ่งทำให้ req ?: defaultReq ได้ค่า 0 แทน defaultReq
        val req = module?.config?.getInt("settings.required-sneaks", 0)?.takeIf { it > 0 }
        val interval = module?.config?.getDouble("settings.reset-interval", 0.0)?.takeIf { it > 0.0 }

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
            material == Material.OBSIDIAN -> Triple("NetherCalculator", "ระบบคำนวณพิกัด Nether/Overworld", "ออบซิเดียน")
            name.endsWith("_PICKAXE") -> Triple("VeinMiner", "ระบบขุดแร่รอบตัว", "ที่ขุดแร่")
            else -> null
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val action = event.action
        
        // สนใจเฉพาะการใช้มือหลักเท่านั้น ป้องกันปัญหากดใช้งานเบิ้ลจากมือหลักและมือรอง
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            val item = player.inventory.itemInMainHand
            if (item.type == Material.COMPASS && player.isSneaking) {
                event.isCancelled = true
                val manager = plugin.moduleManager ?: return
                val module = manager.getModule("DistanceMeasurer")
                if (module != null && module.isEnabled) {
                    if (module.checkPermission(player)) {
                        val measurer = module as? core.luminaworld.modules.activate.DistanceMeasurer.DistanceMeasurerModule
                        measurer?.toggleMeasureMode(player)
                    }
                }
            }
        }
    }
    
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        playerSessions.remove(uuid)
        triggerCooldowns.remove(uuid)
    }

    private fun isSeedItem(material: Material): Boolean {
        val name = material.name
        return name.endsWith("_SEEDS") || name == "CARROT" || name == "POTATO" || 
               name == "SWEET_BERRIES" || name == "PITCHER_POD" || name == "COCOA_BEANS" ||
               name == "NETHER_WART"
    }
}
