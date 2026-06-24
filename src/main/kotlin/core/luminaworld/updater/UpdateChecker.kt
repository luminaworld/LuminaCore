package core.luminaworld.updater

import core.luminaworld.LuminaCore
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object UpdateChecker {
    var hasUpdate: Boolean = false
        private set
    var latestVersion: String = ""
        private set
    val downloadUrl = "https://github.com/luminaworld/LuminaCore/releases"

    /**
     * ตรวจสอบการอัปเดตปลั๊กอินแบบ Asynchronous
     */
    fun checkForUpdates(plugin: LuminaCore) {
        val currentVersion = plugin.description.version
        
        plugin.server.asyncScheduler.runNow(plugin) { _ ->
            try {
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()

                val request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/luminaworld/LuminaCore/releases/latest"))
                    .header("User-Agent", "LuminaCore-Updater")
                    .header("Accept", "application/vnd.github.v3+json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()

                // ใช้ send() แบบ blocking ภายใน async thread แทน sendAsync() ซ้อนกัน
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    try {
                        val jsonObject = JsonParser.parseString(response.body()).asJsonObject
                        val tagName = jsonObject.get("tag_name").asString

                        val cleanLatest = tagName.replace(Regex("[^0-9.]"), "")
                        val cleanCurrent = currentVersion.replace(Regex("[^0-9.]"), "")

                        if (isNewerVersion(cleanCurrent, cleanLatest)) {
                            hasUpdate = true
                            latestVersion = tagName

                            plugin.logger.warning("===================================================")
                            plugin.logger.warning(" [LuminaCore] A new version is available for update.")
                            plugin.logger.warning(" - Current version: v$currentVersion")
                            plugin.logger.warning(" - New version: v$cleanLatest")
                            plugin.logger.warning(" - Download at: $downloadUrl")
                            plugin.logger.warning("===================================================")
                        } else {
                            plugin.logger.info("[LuminaCore] The plugin is up to date (v$currentVersion)")
                        }
                    } catch (e: Exception) {
                        plugin.logger.warning("[LuminaCore] Failed to parse update data: ${e.message}")
                    }
                } else {
                    plugin.logger.warning("[LuminaCore] Failed to check for updates. Status code: ${response.statusCode()}")
                }
            } catch (e: Exception) {
                plugin.logger.warning("[LuminaCore] Failed to check for updates: ${e.message}")
            }
        }
    }


    /**
     * เปรียบเทียบเวอร์ชันเพื่อดูว่าเวอร์ชันล่าสุดใหม่กว่าเวอร์ชันปัจจุบันหรือไม่
     */
    private fun isNewerVersion(current: String, latest: String): Boolean {
        if (current == latest) return false
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        
        val length = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until length) {
            val currentVal = if (i < currentParts.size) currentParts[i] else 0
            val latestVal = if (i < latestParts.size) latestParts[i] else 0
            
            if (latestVal > currentVal) return true
            if (currentVal > latestVal) return false
        }
        return false
    }
}
