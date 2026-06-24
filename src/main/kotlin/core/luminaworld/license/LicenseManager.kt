package core.luminaworld.license

import core.luminaworld.LuminaCore
import org.bukkit.Bukkit
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.nio.charset.StandardCharsets

object LicenseManager {

    /**
     * เปลี่ยนค่า BYPASS เป็น true ก่อนการ Build ปลั๊กอิน
     * หากเป็น true ระบบการตรวจสอบ License Key จะถูกปิดใช้งานโดยสมบูรณ์
     */
    private const val BYPASS = true

    private const val DEFAULT_VERIFY_URL = "http://localhost:3000/api/licenses/verify"

    /**
     * ดึง IP Address ของเซิร์ฟเวอร์จาก Network Interface ของเครื่องโดยตรง (ไม่ใช่ Loopback และเป็น IPv4)
     */
    fun getServerIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netInterface = interfaces.nextElement()
                // ข้าม Loopback หรืออินเตอร์เฟซที่ปิดอยู่
                if (netInterface.isLoopback || !netInterface.isUp) continue
                
                val addresses = netInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    // กรองเฉพาะ IPv4 ที่ไม่ใช่ loopback
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        // กรองไอพีจำพวก link-local หรืออื่นๆ (หากมี) เพื่อหาไอพีหลัก
                        if (ip.startsWith("127.") || ip.startsWith("169.254")) continue
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            LuminaCore.instance.logger.warning("[License] Failed to fetch server IP from network interfaces: ${e.message}")
        }
        
        // Fallback: ลองใช้ InetAddress.getLocalHost()
        try {
            val localhost = java.net.InetAddress.getLocalHost()
            if (localhost is Inet4Address && !localhost.isLoopbackAddress) {
                return localhost.hostAddress
            }
        } catch (e: Exception) {
            // Ignore
        }
        
        return "127.0.0.1"
    }

    /**
     * ตรวจสอบ License Key แบบ Asynchronous
     */
    fun verifyLicense(plugin: LuminaCore, onComplete: (Boolean) -> Unit) {
        // ตรวจสอบสถานะการ Bypass ก่อน
        if (BYPASS) {
            plugin.server.consoleSender.sendMessage("§a[LuminaCore] ✔ License Key Bypass is ENABLED. Skipping license verification.")
            onComplete(true)
            return
        }

        val config = plugin.config
        val licenseKey = config.getString("settings.license-key", "")?.trim() ?: ""

        if (licenseKey.isEmpty()) {
            plugin.server.consoleSender.sendMessage("§c[LuminaCore] License key not found in config.yml!")
            plugin.server.consoleSender.sendMessage("§cPlease configure 'settings.license-key' in config.yml.")
            shutdownPlugin(plugin)
            onComplete(false)
            return
        }

        val serverIp = getServerIp()
        val fullUrl = DEFAULT_VERIFY_URL

        plugin.server.consoleSender.sendMessage("§e[LuminaCore] Verifying license key: $licenseKey ...")
        plugin.server.consoleSender.sendMessage("§e[LuminaCore] Verifying IP address: $serverIp")

        // รันแบบ Synchronous บน Main Thread เพื่อบล็อกขั้นตอนการ Enable จนกว่าจะตรวจสอบเสร็จสิ้น
        var isValid = false
        try {
            val url = URL(fullUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000 // 5 วินาที
            conn.readTimeout = 5000

            // สร้าง JSON payload แบบแมนวลเพื่อไม่ต้องการ library ภายนอก
            val jsonPayload = "{\"licenseKey\":\"$licenseKey\",\"serverIp\":\"$serverIp\"}"

            conn.outputStream.use { os ->
                val input = jsonPayload.toByteArray(StandardCharsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = conn.responseCode
            val response = StringBuilder()
            
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            if (stream != null) {
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line?.trim())
                    }
                }
            }

            val responseString = response.toString()

            // ตรวจสอบคำตอบ
            if (responseCode == 200 && responseString.contains("\"valid\":true")) {
                isValid = true
                plugin.server.consoleSender.sendMessage("§a[LuminaCore] License verification successful. Thank you for using our services.")
                onComplete(true)
            } else {
                val errorMessage = extractMessage(responseString)
                plugin.server.consoleSender.sendMessage("§c[LuminaCore] License verification failed!")
                plugin.server.consoleSender.sendMessage("§cReason: §e$errorMessage")
                plugin.server.consoleSender.sendMessage("§cThe plugin will be disabled immediately.")
                shutdownPlugin(plugin)
                onComplete(false)
            }

        } catch (e: Exception) {
            plugin.server.consoleSender.sendMessage("§c[LuminaCore] Unable to establish connection to the license verification server.")
            plugin.server.consoleSender.sendMessage("§cError: §e${e.message}")
            plugin.server.consoleSender.sendMessage("§cThe plugin will be disabled immediately.")
            shutdownPlugin(plugin)
            onComplete(false)
        }
    }

    /**
     * แยกข้อความ error message จาก JSON response แบบง่าย
     */
    private fun extractMessage(json: String): String {
        try {
            if (json.contains("\"message\":\"")) {
                val start = json.indexOf("\"message\":\"") + 11
                val end = json.indexOf("\"", start)
                if (start in 0 until end) {
                    return json.substring(start, end)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return "Invalid license key or unauthorized IP address."
    }

    /**
     * สั่งปิดการทำงานของปลั๊กอินอย่างปลอดภัยบน Main Thread
     */
    private fun shutdownPlugin(plugin: LuminaCore) {
        if (Bukkit.isPrimaryThread()) {
            plugin.server.pluginManager.disablePlugin(plugin)
        } else {
            plugin.server.globalRegionScheduler.run(plugin) { _ ->
                plugin.server.pluginManager.disablePlugin(plugin)
            }
        }
    }
}
