package core.luminaworld.modules.minigames.ChatGames

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class ChatGamesConfig(private val module: ChatGamesModule) {
    val folder = File(module.plugin.dataFolder, "minigames/ChatGames")

    var config = YamlConfiguration()
    var words = YamlConfiguration()
    var rewards = YamlConfiguration()
    var messages = YamlConfiguration()
    var chatRaces = YamlConfiguration()

    fun reload() {
        if (!folder.exists()) {
            folder.mkdirs()
        }

        // คัดลอกและโหลดไฟล์คอนฟิกแต่ละตัว
        config = loadYamlFile("config.yml")
        words = loadYamlFile("words.yml")
        rewards = loadYamlFile("rewards.yml")
        messages = loadYamlFile("messages.yml")
        chatRaces = loadYamlFile("chatRaces.yml")
    }

    private fun loadYamlFile(fileName: String): YamlConfiguration {
        val file = File(folder, fileName)
        if (!file.exists()) {
            try {
                // โหลดทรัพยากรดีฟอลต์จาก JAR
                val resourcePath = "core/luminaworld/modules/minigames/ChatGames/$fileName"
                val inputStream = module.plugin.getResource(resourcePath)
                if (inputStream != null) {
                    file.parentFile.mkdirs()
                    java.nio.file.Files.copy(inputStream, file.toPath())
                } else {
                    module.plugin.logger.warning("Could not find resource path: $resourcePath")
                }
            } catch (e: Exception) {
                module.plugin.logger.severe("Failed to copy default configuration $fileName: ${e.message}")
            }
        } else {
            // อัปเดตคีย์ใหม่ๆ ที่อาจไม่มีในไฟล์เดิม
            val resourcePath = "core/luminaworld/modules/minigames/ChatGames/$fileName"
            module.plugin.updateConfig(file, resourcePath)
        }
        return YamlConfiguration.loadConfiguration(file)
    }

    // ฟังก์ชันช่วยดึงข้อความจาก messages.yml
    fun getMessage(path: String, def: String = ""): String {
        return messages.getString(path, def) ?: def
    }

    fun getMessageList(path: String): List<String> {
        return messages.getStringList(path)
    }
}
