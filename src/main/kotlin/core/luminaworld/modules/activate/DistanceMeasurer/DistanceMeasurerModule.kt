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

            // คำนวณระยะทาง
            val dx = abs(point1.blockX - clickedLoc.blockX)
            val dy = abs(point1.blockY - clickedLoc.blockY)
            val dz = abs(point1.blockZ - clickedLoc.blockZ)
            
            val diffX = clickedLoc.blockX - point1.blockX
            val diffY = clickedLoc.blockY - point1.blockY
            val diffZ = clickedLoc.blockZ - point1.blockZ

            val maxDelta = maxOf(dx, dy, dz)
            val mainDistance = maxDelta + 1

            // โหลดค่ากำหนดข้อความเบี่ยงจากคอนฟิก (หรือใช้ดีฟอลต์หากไม่มีระบุ)
            val cAligned = config?.getString("messages.offsets.aligned", " (ตรงแนวพอดี)") ?: " (ตรงแนวพอดี)"
            val cPrefix = config?.getString("messages.offsets.prefix", " | ") ?: " | "
            val cSeparator = config?.getString("messages.offsets.separator", ", ") ?: ", "
            val cLeft = config?.getString("messages.offsets.left", "ซ้าย") ?: "ซ้าย"
            val cRight = config?.getString("messages.offsets.right", "ขวา") ?: "ขวา"
            val cAbove = config?.getString("messages.offsets.above", "สูงกว่า") ?: "สูงกว่า"
            val cBelow = config?.getString("messages.offsets.below", "ต่ำกว่า") ?: "ต่ำกว่า"
            val cEast = config?.getString("messages.offsets.east", "ตะวันออก") ?: "ตะวันออก"
            val cWest = config?.getString("messages.offsets.west", "ตะวันตก") ?: "ตะวันตก"
            val cNorth = config?.getString("messages.offsets.north", "เหนือ") ?: "เหนือ"
            val cSouth = config?.getString("messages.offsets.south", "ใต้") ?: "ใต้"
            val fSide = config?.getString("messages.offsets.format-side", "ห่างออกไปทาง%dir% %amount% บล็อก") ?: "ห่างออกไปทาง%dir% %amount% บล็อก"
            val fHeight = config?.getString("messages.offsets.format-height", "%dir% %amount% บล็อก") ?: "%dir% %amount% บล็อก"
            val fAxisX = config?.getString("messages.offsets.format-axis-x", "ห่างแกน X %amount% บล็อก (%dir%)") ?: "ห่างแกน X %amount% บล็อก (%dir%)"
            val fAxisZ = config?.getString("messages.offsets.format-axis-z", "ห่างแกน Z %amount% บล็อก (%dir%)") ?: "ห่างแกน Z %amount% บล็อก (%dir%)"

            // คำนวณหาทิศการเบี่ยง
            val offsetParts = mutableListOf<String>()
            val playerDir = player.location.direction

            if (maxDelta == dx) {
                // แกนหลักคือ X
                if (dz > 0) {
                    val sideCross = playerDir.x * diffZ
                    val sideDir = if (sideCross >= 0) cRight else cLeft
                    offsetParts.add(fSide.replace("%dir%", sideDir).replace("%amount%", dz.toString()))
                }
                if (dy > 0) {
                    val heightDir = if (diffY > 0) cAbove else cBelow
                    offsetParts.add(fHeight.replace("%dir%", heightDir).replace("%amount%", abs(diffY).toString()))
                }
            } else if (maxDelta == dz) {
                // แกนหลักคือ Z
                if (dx > 0) {
                    val sideCross = -playerDir.z * diffX
                    val sideDir = if (sideCross >= 0) cRight else cLeft
                    offsetParts.add(fSide.replace("%dir%", sideDir).replace("%amount%", dx.toString()))
                }
                if (dy > 0) {
                    val heightDir = if (diffY > 0) cAbove else cBelow
                    offsetParts.add(fHeight.replace("%dir%", heightDir).replace("%amount%", abs(diffY).toString()))
                }
            } else {
                // แกนหลักคือ Y
                if (dx > 0) {
                    val dirX = if (diffX > 0) cEast else cWest
                    offsetParts.add(fAxisX.replace("%dir%", dirX).replace("%amount%", dx.toString()))
                }
                if (dz > 0) {
                    val dirZ = if (diffZ > 0) cSouth else cNorth
                    offsetParts.add(fAxisZ.replace("%dir%", dirZ).replace("%amount%", dz.toString()))
                }
            }

            val offsetString = if (offsetParts.isEmpty()) {
                cAligned
            } else {
                cPrefix + offsetParts.joinToString(cSeparator)
            }


            val distance = point1.distance(clickedLoc)
            val resultTemplate = config?.getString("messages.result", "%prefix% &bระยะทางหลัก: &e%main_dist% บล็อก&7%offset% &7(&fทแยง: &e%dist% บล็อก&7)") ?: ""
            val resultMsg = resultTemplate
                .replace("%dist%", String.format("%.2f", distance))
                .replace("%main_dist%", mainDistance.toString())
                .replace("%offset%", offsetString)

            
            // ใช้ Scheduler หน่วงเวลาแสดงข้อความผลลัพธ์เล็กน้อยเพื่อให้เรียงลำดับการมองเห็นได้ดี
            player.scheduler.runDelayed(plugin, { _ ->
                sendNotification(player, resultMsg)
            }, null, 10L)

            playerPoints.remove(uuid)
        }
    }
}
