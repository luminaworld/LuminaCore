package core.luminaworld.modules.features.ConfirmDrop

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import core.luminaworld.LuminaCore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.lang.StringBuilder

class ConfirmDropListener(private val plugin: LuminaCore, private val module: ConfirmDropModule) : Listener {

    class PendingDrop(
        val itemStack: ItemStack,
        val task: ScheduledTask,
        val startTime: Long
    )

    private val pendingDrops = ConcurrentHashMap<UUID, PendingDrop>()

    @EventHandler
    fun onPlayerDrop(event: PlayerDropItemEvent) {
        if (!module.isEnabled) return
        val player = event.player
        if (!module.checkPermission(player)) return

        val itemDrop = event.itemDrop
        val stack = itemDrop.itemStack

        if (!shouldProtect(stack)) return

        val uuid = player.uniqueId
        val now = System.currentTimeMillis()
        val pending = pendingDrops[uuid]
        val config = module.config ?: return
        val delaySeconds = config.getDouble("settings.confirm-delay-seconds", 3.0)
        val delayMs = (delaySeconds * 1000).toLong()

        // หากผู้เล่นเพิ่งขอทิ้งไอเทมชนิดเดิมภายในเวลาจำกัด -> ยืนยันการทิ้งสำเร็จ
        if (pending != null && isSimilar(pending.itemStack, stack) && (now - pending.startTime) < delayMs) {
            pending.task.cancel()
            pendingDrops.remove(uuid)
            
            // เล่นเสียงยืนยันสำเร็จ
            playConfirmSound(player, true)
            return
        }

        // ยกเลิกการทิ้งครั้งแรก
        event.isCancelled = true
        pending?.task?.cancel()

        // เล่นเสียงแจ้งเตือนครั้งแรก
        playConfirmSound(player, false)

        val totalTicks = (delaySeconds * 20).toLong()
        val intervalTicks = 5L
        var elapsedTicks = 0L

        var taskRef: ScheduledTask? = null
        taskRef = player.scheduler.runAtFixedRate(plugin, { task ->
            elapsedTicks += intervalTicks
            if (elapsedTicks >= totalTicks || !player.isOnline) {
                pendingDrops.remove(uuid)
                task.cancel()
                return@runAtFixedRate
            }

            val remainingTicks = totalTicks - elapsedTicks
            val remainingSeconds = remainingTicks.toDouble() / 20.0
            
            // วาดหลอดพลังแสดงเวลากดซ้ำแบบถอยหลังแอนิเมชัน
            showConfirmProgressBar(player, remainingTicks, totalTicks, stack, remainingSeconds)
        }, {
            pendingDrops.remove(uuid)
        }, 0L, intervalTicks)

        if (taskRef != null) {
            pendingDrops[uuid] = PendingDrop(stack.clone(), taskRef, now)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        pendingDrops[uuid]?.task?.cancel()
        pendingDrops.remove(uuid)
    }

    /**
     * เคลียร์ task ทั้งหมดที่ค้างอยู่ เรียกตอน onDisable
     */
    fun cleanup() {
        for ((_, pending) in pendingDrops) {
            pending.task.cancel()
        }
        pendingDrops.clear()
    }

    private fun shouldProtect(stack: ItemStack): Boolean {
        val config = module.config ?: return false
        val material = stack.type
        val name = material.name

        val protectEnchantedOnly = config.getBoolean("settings.protect-enchanted-only", true)
        if (protectEnchantedOnly && stack.enchantments.isEmpty() && material != Material.ELYTRA) {
            val protectMaterials = config.getStringList("settings.protect-materials")
            if (!protectMaterials.contains(name)) {
                return false
            }
        }

        val protectMaterials = config.getStringList("settings.protect-materials")
        if (protectMaterials.contains(name)) return true

        val isArmor = name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || 
                      name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
        val isTool = name.endsWith("_PICKAXE") || name.endsWith("_AXE") || 
                     name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.endsWith("_SWORD")
        val isElytra = material == Material.ELYTRA
        val isNetheriteOrDiamond = name.startsWith("NETHERITE_") || name.startsWith("DIAMOND_")

        if (isElytra) return true
        if (isArmor && isNetheriteOrDiamond) return true
        if (isTool && isNetheriteOrDiamond) return true

        return false
    }

    private fun isSimilar(a: ItemStack, b: ItemStack): Boolean {
        return a.type == b.type && a.amount == b.amount && a.itemMeta == b.itemMeta
    }

    private fun showConfirmProgressBar(player: Player, remainingTicks: Long, totalTicks: Long, stack: ItemStack, timeSeconds: Double) {
        val config = module.config ?: return
        val enabled = config.getBoolean("settings.progress-bar.enabled", true)
        if (!enabled) return

        val charCompleted = config.getString("settings.progress-bar.char-completed", "|") ?: "|"
        val charRemaining = config.getString("settings.progress-bar.char-remaining", "|") ?: "|"
        val colorCompleted = config.getString("settings.progress-bar.color-completed", "&c") ?: "&c"
        val colorRemaining = config.getString("settings.progress-bar.color-remaining", "&7") ?: "&7"
        val colorBracket = config.getString("settings.progress-bar.color-bracket", "&e") ?: "&e"
        val colorNumber = config.getString("settings.progress-bar.color-number", "&b") ?: "&b"
        val colorText = config.getString("settings.progress-bar.color-text", "&7") ?: "&7"

        val defaultFormat = "%color_bracket%[ %completed%%remaining% %color_bracket%] %color_number%%time% วินาที %color_text%(กดย้ำทิ้งไอเทม %item% อีกครั้งเพื่อทิ้ง)"
        val format = config.getString("settings.progress-bar.format", defaultFormat) ?: defaultFormat

        // คำนวณความก้าวหน้าถอยหลัง (Completed หมายถึงเวลาที่เหลืออยู่)
        val compCount = ((remainingTicks.toDouble() / totalTicks.toDouble()) * 10).toInt().coerceIn(0, 10)
        
        val compSb = StringBuilder()
        compSb.append(colorCompleted)
        for (i in 1..compCount) {
            compSb.append(charCompleted)
        }
        val completedStr = compSb.toString()

        val remSb = StringBuilder()
        remSb.append(colorRemaining)
        val remainingCount = 10 - compCount
        for (i in 1..remainingCount) {
            remSb.append(charRemaining)
        }
        val remainingStr = remSb.toString()

        val prefix = plugin.config.getString("settings.prefix", "[LuminaCore]") ?: "[LuminaCore]"
        val itemName = if (stack.hasItemMeta() && stack.itemMeta.hasDisplayName()) {
            stack.itemMeta.displayName
        } else {
            stack.type.name
        }

        val formattedSeconds = String.format("%.1f", timeSeconds)
        val finalMsg = format
            .replace("%completed%", completedStr)
            .replace("%remaining%", remainingStr)
            .replace("%time%", formattedSeconds)
            .replace("%item%", itemName)
            .replace("%prefix%", prefix)
            .replace("%color_bracket%", colorBracket)
            .replace("%color_number%", colorNumber)
            .replace("%color_text%", colorText)

        player.sendActionBar(module.parseToComponent(finalMsg))
    }

    private fun playConfirmSound(player: Player, isSuccess: Boolean) {
        val config = module.config ?: return
        val enabled = config.getBoolean("settings.audio.enabled", true)
        if (!enabled) return

        val section = if (isSuccess) "success" else "warning"
        val soundName = config.getString("settings.audio.$section-sound", if (isSuccess) "ENTITY_EXPERIENCE_ORB_PICKUP" else "BLOCK_NOTE_BLOCK_BASS") ?: "BLOCK_NOTE_BLOCK_BASS"
        val volume = config.getDouble("settings.audio.$section-volume", 0.6).toFloat()
        val pitch = config.getDouble("settings.audio.$section-pitch", if (isSuccess) 1.2 else 0.5).toFloat()

        try {
            val sound = Sound.valueOf(soundName)
            player.playSound(player.location, sound, volume, pitch)
        } catch (e: Exception) {}
    }
}
