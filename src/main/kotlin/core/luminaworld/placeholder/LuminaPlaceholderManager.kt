package core.luminaworld.placeholder

import core.luminaworld.LuminaCore
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.util.concurrent.ConcurrentHashMap

object LuminaPlaceholderManager {
    private val handlers = ConcurrentHashMap<String, (OfflinePlayer?, String) -> String?>()
    private var expansion: LuminaExpansion? = null
    private var isHooked = false

    /**
     * ลงทะเบียนหน่วยประมวลผลตัวแปรของแต่ละโมดูลย่อย
     * @param prefix ชื่อระบุหัวเรื่อง เช่น "ctf"
     * @param handler แลมบ์ดาฟังก์ชันประมวลผลรับผู้เล่นและอาร์กิวเมนต์ย่อย
     */
    fun register(prefix: String, handler: (OfflinePlayer?, String) -> String?) {
        handlers[prefix.lowercase()] = handler
    }

    /**
     * ยกเลิกการลงทะเบียนของโมดูลย่อย
     * @param prefix ชื่อระบุหัวเรื่องที่เคยลงทะเบียนไว้
     */
    fun unregister(prefix: String) {
        handlers.remove(prefix.lowercase())
    }

    /**
     * เริ่มการทำงานระบบตัวแปรส่วนกลางของปลั๊กอิน (เรียกใช้เมื่อ OnEnable ของ Core)
     */
    fun initialize(plugin: LuminaCore) {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                val exp = LuminaExpansion(plugin)
                exp.register()
                expansion = exp
                isHooked = true
                plugin.logger.info("§6[LuminaPlaceholderManager] §aเปิดใช้งานระบบเชื่อมต่อ PlaceholderAPI ส่วนกลางเรียบร้อยแล้ว")
            } catch (e: Exception) {
                plugin.logger.warning("[LuminaPlaceholderManager] ไม่สามารถเปิดใช้งานระบบ PAPI ได้: ${e.message}")
            }
        }
    }

    /**
     * หยุดการทำงานเมื่อปิดการทำงานปลั๊กอิน (เรียกใช้เมื่อ OnDisable ของ Core)
     */
    fun shutdown() {
        if (isHooked && expansion != null) {
            try {
                expansion!!.unregister()
            } catch (e: Exception) {
                // ละเว้นข้อผิดพลาดในขั้นตอน unregister
            }
            expansion = null
            isHooked = false
        }
        handlers.clear()
    }

    private class LuminaExpansion(private val plugin: LuminaCore) : PlaceholderExpansion() {
        override fun getIdentifier(): String = "luminacore"
        override fun getAuthor(): String = "Loma0531"
        override fun getVersion(): String = plugin.description.version
        override fun persist(): Boolean = true

        override fun onRequest(player: OfflinePlayer?, params: String): String? {
            // ค้นหาขอบเขต prefix ก่อนอันดับแรก เช่น ctf_status_zone -> ctf และ status_zone
            val index = params.indexOf('_')
            if (index == -1) return null

            val prefix = params.substring(0, index).lowercase()
            val args = params.substring(index + 1)

            val handler = handlers[prefix] ?: return ""
            return handler(player, args)
        }
    }
}
