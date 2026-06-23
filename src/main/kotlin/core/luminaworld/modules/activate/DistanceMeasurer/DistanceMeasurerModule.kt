package core.luminaworld.modules.activate.DistanceMeasurer

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class DistanceMeasurerModule(plugin: LuminaCore) : LuminaModule(plugin, "DistanceMeasurer") {

    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val playerPoints = ConcurrentHashMap<UUID, Location>()
    private var listener: DistanceMeasurerListener? = null

    override fun onEnable() {
        listener = DistanceMeasurerListener(plugin, this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)
    }

    override fun onDisable() {
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
        activePlayers.clear()
        playerPoints.clear()
    }


    fun isMeasureModeEnabled(player: Player): Boolean {
        return activePlayers.contains(player.uniqueId)
    }

    fun toggleMeasureMode(player: Player) {
        val uuid = player.uniqueId
        if (activePlayers.contains(uuid)) {
            activePlayers.remove(uuid)
            playerPoints.remove(uuid)
            val msg = config?.getString("messages.mode-disabled", "%prefix% &cMeasure Mode Disabled!") ?: ""
            sendNotification(player, msg)
        } else {
            activePlayers.add(uuid)
            val msg = config?.getString("messages.mode-enabled", "%prefix% &aMeasure Mode Enabled! Right-click blocks to set points.") ?: ""
            sendNotification(player, msg)
        }
    }

    fun handleBlockClick(player: Player, clickedLoc: Location) {
        val uuid = player.uniqueId
        val point1 = playerPoints[uuid]

        if (point1 == null) {
            // บันทึกพิกัดของจุดที่ 1
            playerPoints[uuid] = clickedLoc
            val msgTemplate = config?.getString("messages.point-1", "%prefix% &aPoint 1 set at: &eX=%x%, Y=%y%, Z=%z%") ?: ""
            val finalMsg = msgTemplate
                .replace("%x%", clickedLoc.blockX.toString())
                .replace("%y%", clickedLoc.blockY.toString())
                .replace("%z%", clickedLoc.blockZ.toString())
            sendNotification(player, finalMsg)
        } else {
            // บันทึกพิกัดของจุดที่ 2
            val msgTemplate2 = config?.getString("messages.point-2", "%prefix% &aPoint 2 set at: &eX=%x%, Y=%y%, Z=%z%") ?: ""
            val finalMsg2 = msgTemplate2
                .replace("%x%", clickedLoc.blockX.toString())
                .replace("%y%", clickedLoc.blockY.toString())
                .replace("%z%", clickedLoc.blockZ.toString())
            sendNotification(player, finalMsg2)

            if (point1.world != clickedLoc.world) {
                playerPoints.remove(uuid)
                return
            }

            // คำนวณ Euclidean และ Manhattan distance
            val distance = point1.distance(clickedLoc)
            val manhattan = abs(point1.blockX - clickedLoc.blockX) + 
                            abs(point1.blockY - clickedLoc.blockY) + 
                            abs(point1.blockZ - clickedLoc.blockZ)

            val resultTemplate = config?.getString("messages.result", "%prefix% &bDistance: &e%dist% blocks &7| &bManhattan: &e%manhattan% blocks") ?: ""
            val resultMsg = resultTemplate
                .replace("%dist%", String.format("%.2f", distance))
                .replace("%manhattan%", manhattan.toString())
            
            // ใช้ Scheduler หน่วงเวลาแสดงข้อความผลลัพธ์เล็กน้อยเพื่อให้เรียงลำดับการมองเห็นได้ดี
            player.scheduler.runDelayed(plugin, { _ ->
                sendNotification(player, resultMsg)
            }, null, 10L)

            playerPoints.remove(uuid)
        }
    }
}
