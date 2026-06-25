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

    // ตัวแปรเก็บค่าคอนฟิกที่ทำการแคชเพื่อเพิ่มประสิทธิภาพในการเข้าถึงและลดอาการดีเลย์
    private var confirmDelaySeconds: Double = 3.0
    private var protectEnchantedOnly: Boolean = true
    private val protectMaterials = HashSet<String>()
    private var progressBarEnabled: Boolean = true
    private var charCompleted: String = "|"
    private var charRemaining: String = "|"
    private var colorCompleted: String = "&c"
    private var colorRemaining: String = "&7"
    private var colorBracket: String = "&e"
    private var colorNumber: String = "&b"
    private var colorText: String = "&7"
    private var progressFormat: String = ""
    private var audioEnabled: Boolean = true
    private var warningSound: String = "BLOCK_NOTE_BLOCK_BASS"
    private var warningVolume: Float = 0.8f
    private var warningPitch: Float = 0.5f
    private var successSound: String = "minecraft:entity.ender_eye.death"
    private var successVolume: Float = 0.6f
    private var successPitch: Float = 1.2f

    init {
        loadSettings()
    }

    /**
     * โหลดและแคชการตั้งค่าจากโมดูลเพื่อหลีกเลี่ยงการเปิดอ่านไฟล์ซ้ำในกระบวนการทำงานหลัก
     */
    private fun loadSettings() {
        val config = module.config ?: return
        confirmDelaySeconds = config.getDouble("settings.confirm-delay-seconds", 3.0)
        protectEnchantedOnly = config.getBoolean("settings.protect-enchanted-only", true)
        
        protectMaterials.clear()
        val materialsList = config.getStringList("settings.protect-materials")
        for (mat in materialsList) {
            protectMaterials.add(mat.uppercase())
        }

        progressBarEnabled = config.getBoolean("settings.progress-bar.enabled", true)
        charCompleted = config.getString("settings.progress-bar.char-completed", "|") ?: "|"
        charRemaining = config.getString("settings.progress-bar.char-remaining", "|") ?: "|"
        colorCompleted = config.getString("settings.progress-bar.color-completed", "&c") ?: "&c"
        colorRemaining = config.getString("settings.progress-bar.color-remaining", "&7") ?: "&7"
        colorBracket = config.getString("settings.progress-bar.color-bracket", "&e") ?: "&e"
        colorNumber = config.getString("settings.progress-bar.color-number", "&b") ?: "&b"
        colorText = config.getString("settings.progress-bar.color-text", "&7") ?: "&7"
        
        val defaultFormat = "%color_bracket%[ %completed%%remaining% %color_bracket%] %color_number%%time% วินาที %color_text%(กดย้ำทิ้งไอเทม %item% อีกครั้งเพื่อยืนยัน)"
        progressFormat = config.getString("settings.progress-bar.format", defaultFormat) ?: defaultFormat

        audioEnabled = config.getBoolean("settings.audio.enabled", true)
        warningSound = config.getString("settings.audio.warning-sound", "BLOCK_NOTE_BLOCK_BASS") ?: "BLOCK_NOTE_BLOCK_BASS"
        warningVolume = config.getDouble("settings.audio.warning-volume", 0.8).toFloat()
        warningPitch = config.getDouble("settings.audio.warning-pitch", 0.5).toFloat()

        successSound = config.getString("settings.audio.success-sound", "minecraft:entity.ender_eye.death") ?: "minecraft:entity.ender_eye.death"
        successVolume = config.getDouble("settings.audio.success-volume", 0.6).toFloat()
        successPitch = config.getDouble("settings.audio.success-pitch", 1.2).toFloat()
    }

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
        val delayMs = (confirmDelaySeconds * 1000).toLong()

        // หากผู้เล่นเพิ่งขอทิ้งไอเทมชนิดเดิมภายในเวลาจำกัด -> ยืนยันการทิ้งสำเร็จ
        if (pending != null && isSimilar(pending.itemStack, stack) && (now - pending.startTime) < delayMs) {
            pending.task.cancel()
            pendingDrops.computeIfPresent(uuid) { _, current ->
                if (current.task == pending.task) null else current
            }
            
            // เล่นเสียงยืนยันสำเร็จ
            playConfirmSound(player, true)
            return
        }

        // ยกเลิกการทิ้งครั้งแรก
        event.isCancelled = true
        player.updateInventory() // อัปเดตข้อมูลของฝั่งไคลเอนต์ทันทีเพื่อตัดอาการภาพกระตุก/หน่วง
        
        pending?.task?.cancel()

        // เล่นเสียงแจ้งเตือนครั้งแรก
        playConfirmSound(player, false)

        val totalTicks = (confirmDelaySeconds * 20).toLong()
        val intervalTicks = 5L
        var elapsedTicks = 0L

        // แสดงความคืบหน้าของเวลาเริ่มต้นทันทีบนหน้าจอ ไม่ต้องรอ 1 tick ถัดไปของ scheduler
        showConfirmProgressBar(player, totalTicks, totalTicks, stack, confirmDelaySeconds)

        var taskRef: ScheduledTask? = null
        taskRef = player.scheduler.runAtFixedRate(plugin, { task ->
            elapsedTicks += intervalTicks
            if (elapsedTicks >= totalTicks || !player.isOnline) {
                task.cancel()
                return@runAtFixedRate
            }

            val remainingTicks = totalTicks - elapsedTicks
            val remainingSeconds = remainingTicks.toDouble() / 20.0
            
            // วาดหลอดพลังแสดงเวลากดซ้ำแบบถอยหลังแอนิเมชัน
            showConfirmProgressBar(player, remainingTicks, totalTicks, stack, remainingSeconds)
        }, {
            // ใช้ computeIfPresent เพื่อให้มั่นใจว่าจะลบออกเฉพาะทาสก์ปัจจุบันที่เป็นเจ้าของเท่านั้น ป้องกัน Race Condition
            pendingDrops.computeIfPresent(uuid) { _, current ->
                if (current.task == taskRef) null else current
            }
        }, intervalTicks, intervalTicks)

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
        val material = stack.type
        val name = material.name

        if (protectEnchantedOnly && stack.enchantments.isEmpty() && material != Material.ELYTRA) {
            if (!protectMaterials.contains(name)) {
                return false
            }
        }

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
        if (!progressBarEnabled) return

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
        val finalMsg = progressFormat
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
        if (!audioEnabled) return

        val sName = if (isSuccess) successSound else warningSound
        val vol = if (isSuccess) successVolume else warningVolume
        val pitch = if (isSuccess) successPitch else warningPitch

        try {
            val sound = Sound.valueOf(sName)
            player.playSound(player.location, sound, vol, pitch)
        } catch (e: Exception) {
            // กรณีเป็นเสียงแบบ Custom Resource Key (เช่น minecraft:entity.ender_eye.death)
            try {
                player.playSound(player.location, sName, vol, pitch)
            } catch (ex: Exception) {}
        }
    }
}
