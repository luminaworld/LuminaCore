package core.luminaworld.modules.activate.TreeCapitator

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.papermc.paper.threadedregions.scheduler.ScheduledTask

class TreeCapitatorModule(plugin: LuminaCore) : LuminaModule(plugin, "TreeCapitator") {

    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val timeoutTasks = ConcurrentHashMap<UUID, ScheduledTask>()
    private var listener: TreeCapitatorListener? = null

    // Cache config values — reset เมื่อ reload()
    var maxBlocks: Int = 128
        private set
    var breakLeaves: Boolean = true
        private set
    var replantSapling: Boolean = true
        private set
    var activeDuration: Double = 10.0
        private set

    override fun onEnable() {
        refreshConfigCache()
        listener = TreeCapitatorListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
        timeoutTasks.values.forEach { it.cancel() }
        timeoutTasks.clear()
        activePlayers.clear()
    }

    /**
     * ล้างและโหลดค่า config cache ใหม่ — ถูกเรียกทั้งตอน onEnable และตอน reload()
     */
    private fun refreshConfigCache() {
        maxBlocks = config?.getInt("settings.max-blocks", 128) ?: 128
        breakLeaves = config?.getBoolean("settings.break-leaves", true) ?: true
        replantSapling = config?.getBoolean("settings.replant-sapling", true) ?: true
        activeDuration = config?.getDouble("settings.active-duration", 10.0) ?: 10.0
    }

    override fun reload() {
        super.reload()
        // super.reload() เรียก loadConfig() + onEnable() แล้ว — แต่ refreshConfigCache ถูกเรียกใน onEnable อยู่แล้ว
    }

    fun isModeEnabled(player: Player): Boolean {
        return activePlayers.contains(player.uniqueId)
    }

    fun toggleMode(player: Player) {
        val uuid = player.uniqueId
        if (activePlayers.contains(uuid)) {
            clearPlayer(player)
            val msg = config?.getString("messages.mode-disabled", "%prefix% &cTree Capitator mode disabled!") ?: ""
            sendNotification(player, msg)
        } else {
            activePlayers.add(uuid)
            val msg = config?.getString("messages.mode-enabled", "%prefix% &aTree Capitator mode enabled!") ?: ""
            sendNotification(player, msg)

            // ยกเลิก Task เก่าหากมี
            timeoutTasks.remove(uuid)?.cancel()

            // ตั้งเวลาสำหรับหมดเขต
            val ticks = (activeDuration * 20).toLong()
            val task = player.scheduler.runDelayed(plugin, { _ ->
                if (activePlayers.contains(uuid)) {
                    activePlayers.remove(uuid)
                    timeoutTasks.remove(uuid)
                    val timeoutMsg = config?.getString("messages.timeout-disabled", "%prefix% &cหมดเวลาตัดไม้! กรุณาย่อใหม่อีกครั้ง") ?: ""
                    if (timeoutMsg.isNotEmpty()) {
                        sendNotification(player, timeoutMsg)
                    }
                }
            }, null, ticks)
            if (task != null) {
                timeoutTasks[uuid] = task
            }
        }
    }

    fun clearPlayer(player: Player) {
        val uuid = player.uniqueId
        activePlayers.remove(uuid)
        timeoutTasks.remove(uuid)?.cancel()
    }
}
