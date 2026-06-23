package core.luminaworld.modules.activate.GlowingTwerk

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerToggleSneakEvent
import java.util.concurrent.CompletableFuture

class GlowingTwerkListener(private val module: GlowingTwerkModule) : Listener {
    private val growableMaterials = HashSet<Material>()

    init {
        cacheMaterials()
    }

    private fun cacheMaterials() {
        growableMaterials.clear()
        val list = module.config?.getStringList("growable-blocks") ?: emptyList()
        for (s in list) {
            try {
                val mat = Material.valueOf(s.uppercase())
                growableMaterials.add(mat)
            } catch (ignored: IllegalArgumentException) {
            }
        }
    }

    @EventHandler
    fun onPlayerSneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking) return
        if (!module.isEnabled) return

        val player = event.player
        val uuid = player.uniqueId
        val world = player.world

        // 1. ตรวจสอบโลกที่อนุญาต
        val allowedWorlds = module.config?.getStringList("settings.allowed-worlds") ?: emptyList()
        if (allowedWorlds.isNotEmpty() && !allowedWorlds.contains(world.name)) {
            sendMessage(player, "world-disabled", "Glowing Twerk is not allowed in this world!")
            return
        }

        // 2. ตรวจสอบระดับ Permission และรัศมี
        val maxRadius = module.config?.getInt("settings.max-radius", 32) ?: 32
        var radius = -1
        for (r in maxRadius downTo 1) {
            if (player.hasPermission("luminaris.glowing.$r")) {
                radius = r
                break
            }
        }

        // หากไม่มีสิทธิ์ใช้งานเลย
        if (radius == -1) {
            sendMessage(player, "no-permission", "You do not have permission to use Glowing Twerk!")
            return
        }

        // 3. ตรวจสอบคูลดาวน์
        val now = System.currentTimeMillis()
        val lastUse = module.cooldowns.getOrDefault(uuid, 0L)
        val cooldownSec = module.config?.getDouble("settings.cooldown", 5.0) ?: 5.0
        val cooldownMs = (cooldownSec * 1000).toLong()

        if (now - lastUse < cooldownMs) {
            sendMessage(player, "cooldown", "Please wait before using Luminaris power again!")
            return
        }

        // ตั้งคูลดาวน์ล่วงหน้า
        module.cooldowns[uuid] = now

        // 4. คำนวณโอกาส
        val growthChance = module.config?.getDouble("settings.growth-chance", 50.0) ?: 50.0
        if (kotlin.random.Random.nextDouble() * 100.0 > growthChance) {
            return
        }

        // 5. สแกนและเร่งการเติบโตแบบ Thread-safe ของ Folia
        val playerLoc = player.location
        val px = playerLoc.blockX
        val py = playerLoc.blockY
        val pz = playerLoc.blockZ

        val soundEnabled = module.config?.getBoolean("settings.sound-enabled", true) ?: true
        val particleEnabled = module.config?.getBoolean("settings.particle-enabled", true) ?: true
        val particleAmount = module.config?.getInt("settings.particle-amount", 10) ?: 10
        val particleName = module.config?.getString("settings.particle", "HAPPY_VILLAGER") ?: "HAPPY_VILLAGER"

        val particleType = try {
            Particle.valueOf(particleName.uppercase())
        } catch (e: Exception) {
            Particle.HAPPY_VILLAGER
        }

        val targetLocations = ArrayList<Location>()

        // 1. สแกนหาพิกัดพืชรอบตัวผู้เล่นในแนวระนาบก่อน โดยประมวลผลบน Region thread ของตัวผู้เล่นเอง
        Bukkit.getRegionScheduler().execute(module.plugin, playerLoc) {
            for (x in px - radius..px + radius) {
                for (z in pz - radius..pz + radius) {
                    for (y in py - 2..py + 2) {
                        val block = world.getBlockAt(x, y, z)
                        if (growableMaterials.contains(block.type)) {
                            targetLocations.add(block.location)
                        }
                    }
                }
            }

            if (targetLocations.isEmpty()) return@execute

            // 2. ส่งงานแก้ไขสถานะและโตพืชไปยัง Region Scheduler ของบล็อกแต่ละอัน (ป้องกัน Thread safety ของ Folia)
            val futures = ArrayList<CompletableFuture<Boolean>>()
            for (loc in targetLocations) {
                val future = CompletableFuture<Boolean>()
                Bukkit.getRegionScheduler().execute(module.plugin, loc) {
                    try {
                        val block = loc.block
                        var grown = false

                        val blockData = block.blockData
                        if (blockData is Ageable) {
                            if (blockData.age < blockData.maximumAge) {
                                blockData.age = Math.min(blockData.maximumAge, blockData.age + 1)
                                block.blockData = blockData
                                grown = true
                            }
                        } else {
                            // การใช้งานเมธอดสะกดถูกต้อง: applyBoneMeal
                            if (block.applyBoneMeal(BlockFace.UP)) {
                                grown = true
                            }
                        }

                        if (grown) {
                            if (soundEnabled) {
                                world.playSound(loc, Sound.ITEM_BONE_MEAL_USE, 1.0f, 1.0f)
                            }
                            if (particleEnabled) {
                                world.spawnParticle(particleType, loc.clone().add(0.5, 0.5, 0.5), particleAmount, 0.25, 0.25, 0.25, 0.05)
                            }
                            future.complete(true)
                        } else {
                            future.complete(false)
                        }
                    } catch (e: Exception) {
                        future.complete(false)
                    }
                }
                futures.add(future)
            }

            // 3. รอให้ผลลัพธ์ทั้งหมดทำเสร็จสิ้น แล้วส่งข้อความตอบกลับผู้เล่นทาง Entity Scheduler ของเขา
            CompletableFuture.allOf(*futures.toTypedArray())
                .thenAccept {
                    var count = 0
                    for (f in futures) {
                        if (f.join()) count++
                    }

                    if (count > 0) {
                        val finalCount = count
                        player.scheduler.execute(module.plugin, {
                            val msgTemplate = module.config?.getString("messages.success") ?: "%prefix% You grew {count} plants using Luminaris power!"
                            val prefix = module.plugin.config.getString("settings.prefix", "[LuminaCore]") ?: "[LuminaCore]"
                            val formatted = msgTemplate.replace("%prefix%", prefix)
                                .replace("{count}", finalCount.toString())
                                .replace("&", "§")
                            
                            if (module.config?.getBoolean("settings.chat-messages-enabled", true) == true && msgTemplate.isNotBlank()) {
                                player.sendMessage(formatted)
                            }
                        }, null, 0)
                    }
                }
        }
    }

    private fun sendMessage(player: Player, configPath: String, def: String) {
        val msgTemplate = module.config?.getString("messages.$configPath") ?: def
        if (msgTemplate.isBlank()) return
        if (module.config?.getBoolean("settings.chat-messages-enabled", true) == false) return

        val prefix = module.plugin.config.getString("settings.prefix", "[LuminaCore]") ?: "[LuminaCore]"
        val formatted = msgTemplate.replace("%prefix%", prefix).replace("&", "§")
        player.sendMessage(formatted)
    }
}
