package core.luminaworld.modules.minigames.CaptureTheFlag

import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.UUID

class CaptureZone(
    val name: String,
    val worldName: String,
    val minX: Int,
    val maxX: Int,
    var minY: Int,
    var maxY: Int,
    val minZ: Int,
    val maxZ: Int,
    var captureTimeSeconds: Int,
    var gracePeriodSeconds: Int,
    var cooldownTimeSeconds: Int,
    var rewardCommands: List<String> = emptyList(),
    var displayY: Double = minY.toDouble()
) {
    // สเตทขณะทำงาน (Runtime State)
    var status: ZoneStatus = ZoneStatus.IDLE
    var occupantUuid: UUID? = null
    var occupantName: String = "None"

    var remainingCaptureSeconds: Int = captureTimeSeconds
    var remainingGraceSeconds: Int = 0
    var remainingCooldownSeconds: Int = 0

    // พารามิเตอร์พาทิเคิลเริ่มต้น
    var particleEnabled: Boolean = true
    var particleType: String = "DUST"
    var particleDensity: Double = 0.5
    var particleSize: Float = 1.0f
    var particleColorHex: String = "#00FF80"
    var particleHeightOffset: Double = 0.1
    var particleSpawnInterval: Double = 5.0
    var particleDuration: Double = 2.0

    var hideDuringCooldown: Boolean = false
    var cooldownParticleType: String = "DUST"
    var cooldownParticleSize: Float = 0.8f
    var cooldownParticleColorHex: String = "#FF0000"

    // แคชพิกัดตำแหน่งพาทิเคิลชั่วคราวเพื่อประสิทธิภาพ
    var cachedOutlinePoints: List<org.bukkit.util.Vector>? = null

    /**
     * แปลงรหัสสี Hex String ไปเป็น org.bukkit.Color สำหรับสร้างพาทิเคิล
     */
    fun parseHexColor(hex: String): org.bukkit.Color {
        val cleanHex = hex.replace("#", "").trim()
        return try {
            if (cleanHex.length == 6) {
                val r = cleanHex.substring(0, 2).toInt(16)
                val g = cleanHex.substring(2, 4).toInt(16)
                val b = cleanHex.substring(4, 6).toInt(16)
                org.bukkit.Color.fromRGB(r, g, b)
            } else {
                org.bukkit.Color.fromRGB(0, 255, 128)
            }
        } catch (e: Exception) {
            org.bukkit.Color.fromRGB(0, 255, 128)
        }
    }

    fun getParticleColor(): org.bukkit.Color = parseHexColor(particleColorHex)
    fun getCooldownParticleColor(): org.bukkit.Color = parseHexColor(cooldownParticleColorHex)

    enum class ZoneStatus {
        IDLE,
        CAPTURING,
        GRACE_PERIOD,
        COOLDOWN
    }

    /**
     * ตรวจสอบว่าพิกัดที่กำหนดอยู่ในพื้นที่โซนนี้หรือไม่
     */
    fun contains(loc: Location): Boolean {
        if (loc.world == null) return false
        if (loc.world.name != worldName) return false
        return loc.blockX >= minX && loc.blockX <= maxX &&
               loc.blockY >= minY && loc.blockY <= maxY &&
               loc.blockZ >= minZ && loc.blockZ <= maxZ
    }

    /**
     * อัปเดตสเตทพิกัดขั้นต่ำ/สูงสุดกรณีมีการย้อนข้อมูล
     */
    fun getCenter(): Location {
        val world = Bukkit.getWorld(worldName) ?: Bukkit.getWorlds().first()
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        val cz = (minZ + maxZ) / 2.0
        return Location(world, cx, cy, cz)
    }
}
