package core.luminaworld.modules.features.ConcreteConverter

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemSpawnEvent
import core.luminaworld.LuminaCore
import java.lang.StringBuilder

class ConcreteConverterListener(private val plugin: LuminaCore, private val module: ConcreteConverterModule) : Listener {

    @EventHandler
    fun onItemSpawn(event: ItemSpawnEvent) {
        if (!module.isEnabled) return
        val entity = event.entity
        val stack = entity.itemStack
        val material = stack.type

        if (!material.name.endsWith("_CONCRETE_POWDER")) return

        val config = module.config ?: return
        val targetSeconds = config.getInt("settings.convert-delay-seconds", 10)

        // เริ่มงานแบบวนซ้ำบน Entity Scheduler เพื่อความปลอดภัยตามโครงสร้าง Folia/Paper
        var secondsInWater = 0
        entity.scheduler.runAtFixedRate(plugin, { task ->
            if (!entity.isValid) {
                task.cancel()
                return@runAtFixedRate
            }

            // ตรวจสอบสิทธิ์ Permission ของผู้โยนไอเทม (ถ้ามี)
            val throwerUuid = entity.thrower
            if (throwerUuid != null) {
                val player = plugin.server.getPlayer(throwerUuid)
                if (player != null && !module.checkPermission(player)) {
                    // หากไม่มี Permission ให้ข้ามและปล่อยไว้อย่างนั้น
                    return@runAtFixedRate
                }
            }

            val loc = entity.location
            val block = loc.block
            val isWater = block.type == Material.WATER || (block.blockData as? org.bukkit.block.data.Waterlogged)?.isWaterlogged == true
            
            val onlySource = config.getBoolean("settings.only-in-water-source", false)
            val isValidWater = if (isWater) {
                if (onlySource) {
                    val levelData = block.blockData as? org.bukkit.block.data.Levelled
                    levelData == null || levelData.level == 0
                } else {
                    true
                }
            } else {
                false
            }

            if (isValidWater) {
                secondsInWater++

                // แสดงผลหลอดความคืบหน้า (Progress Bar)
                val showProgress = config.getBoolean("settings.progress-bar.enabled", true)
                if (showProgress) {
                    val progressMsg = formatProgressBar(secondsInWater, targetSeconds)
                    val range = config.getDouble("settings.progress-bar.range", 5.0)
                    val nearby = entity.world.getNearbyEntities(loc, range, range, range) { it is Player }
                    for (p in nearby) {
                        (p as Player).sendActionBar(module.parseToComponent(progressMsg))
                    }
                }

                // เล่นเสียง Tick เสียง Pitch สูงขึ้นตามวินาทีที่เพิ่ม
                val audioEnabled = config.getBoolean("settings.audio.enabled", true)
                if (audioEnabled) {
                    val tickSoundName = config.getString("settings.audio.tick-sound", "BLOCK_BREWING_STAND_BREW") ?: "BLOCK_BREWING_STAND_BREW"
                    val volume = config.getDouble("settings.audio.tick-volume", 0.5).toFloat()
                    val pitchStart = config.getDouble("settings.audio.tick-pitch-start", 0.6).toFloat()
                    val pitchEnd = config.getDouble("settings.audio.tick-pitch-end", 1.8).toFloat()
                    val ratio = secondsInWater.toFloat() / targetSeconds.toFloat()
                    val pitch = pitchStart + ratio * (pitchEnd - pitchStart)

                    try {
                        val sound = Sound.valueOf(tickSoundName)
                        entity.world.playSound(loc, sound, volume, pitch)
                    } catch (e: Exception) {}
                }

                if (secondsInWater >= targetSeconds) {
                    // ทำการแปลงสภาพไอเทม
                    val concreteType = getConcreteForPowder(material)
                    if (concreteType != null) {
                        val currentStack = entity.itemStack
                        currentStack.type = concreteType
                        entity.itemStack = currentStack

                        // เอฟเฟกต์พาร์ทิเคิลน้ำกระเซ็น
                        val particleName = config.getString("settings.particles.type", "SPLASH") ?: "SPLASH"
                        val particleCount = config.getInt("settings.particles.count", 15)
                        val particleSpeed = config.getDouble("settings.particles.speed", 0.1)
                        try {
                            val pType = org.bukkit.Particle.valueOf(particleName)
                            entity.world.spawnParticle(pType, loc, particleCount, 0.2, 0.2, 0.2, particleSpeed)
                        } catch (e: Exception) {}

                        // เล่นเสียงเปลี่ยนสภาพสำเร็จ
                        if (audioEnabled) {
                            val successSoundName = config.getString("settings.audio.success-sound", "BLOCK_LAVA_EXTINGUISH") ?: "BLOCK_LAVA_EXTINGUISH"
                            val successVolume = config.getDouble("settings.audio.success-volume", 1.0).toFloat()
                            val successPitch = config.getDouble("settings.audio.success-pitch", 1.0).toFloat()
                            try {
                                val sound = Sound.valueOf(successSoundName)
                                entity.world.playSound(loc, sound, successVolume, successPitch)
                            } catch (e: Exception) {}
                        }
                    }
                    task.cancel()
                }
            } else {
                secondsInWater = 0 // รีเซ็ตหากขึ้นเหนือน้ำ
            }
        }, {}, 20L, 20L)
    }

    private fun getConcreteForPowder(powder: Material): Material? {
        val name = powder.name
        if (name.endsWith("_CONCRETE_POWDER")) {
            val concreteName = name.substring(0, name.length - "_POWDER".length)
            return try {
                Material.valueOf(concreteName)
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    private fun formatProgressBar(count: Int, total: Int): String {
        val config = module.config ?: return ""
        val charCompleted = config.getString("settings.progress-bar.char-completed", "|") ?: "|"
        val charRemaining = config.getString("settings.progress-bar.char-remaining", "|") ?: "|"
        val colorCompleted = config.getString("settings.progress-bar.color-completed", "&a") ?: "&a"
        val colorRemaining = config.getString("settings.progress-bar.color-remaining", "&7") ?: "&7"
        val colorBracket = config.getString("settings.progress-bar.color-bracket", "&e") ?: "&e"
        val colorNumber = config.getString("settings.progress-bar.color-number", "&b") ?: "&b"
        val colorText = config.getString("settings.progress-bar.color-text", "&7") ?: "&7"
        
        val defaultFormat = "%color_bracket%[ %completed%%remaining% %color_bracket%] %color_number%%count%/%total% วินาที %color_text%(ผงคอนกรีตกำลังแข็งตัว...)"
        val format = config.getString("settings.progress-bar.format", defaultFormat) ?: defaultFormat

        val compSb = StringBuilder()
        compSb.append(colorCompleted)
        for (i in 1..count) {
            compSb.append(charCompleted)
        }
        val completedStr = compSb.toString()

        val remSb = StringBuilder()
        remSb.append(colorRemaining)
        val remainingCount = total - count
        for (i in 1..remainingCount) {
            remSb.append(charRemaining)
        }
        val remainingStr = remSb.toString()

        val prefix = plugin.config.getString("settings.prefix", "[LuminaCore]") ?: "[LuminaCore]"

        return format
            .replace("%completed%", completedStr)
            .replace("%remaining%", remainingStr)
            .replace("%count%", count.toString())
            .replace("%total%", total.toString())
            .replace("%needed%", remainingCount.toString())
            .replace("%prefix%", prefix)
            .replace("%color_bracket%", colorBracket)
            .replace("%color_number%", colorNumber)
            .replace("%color_text%", colorText)
    }
}
