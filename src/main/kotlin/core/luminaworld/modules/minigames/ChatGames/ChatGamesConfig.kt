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

    var languageMap: Map<String, String> = emptyMap()

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

        // โหลดและคัดลอกไฟล์แปลภาษา
        val lang = config.getString("lang", "en_us") ?: "en_us"
        loadTranslationFile("$lang.json")
        loadLanguageMap("$lang.json")
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

    private fun loadTranslationFile(fileName: String) {
        val transFolder = File(folder, "translations")
        if (!transFolder.exists()) {
            transFolder.mkdirs()
        }
        val file = File(transFolder, fileName)
        if (!file.exists()) {
            try {
                val resourcePath = "core/luminaworld/modules/minigames/ChatGames/translations/$fileName"
                val inputStream = module.plugin.getResource(resourcePath)
                if (inputStream != null) {
                    java.nio.file.Files.copy(inputStream, file.toPath())
                } else {
                    module.plugin.logger.warning("Could not find translation resource path: $resourcePath")
                }
            } catch (e: Exception) {
                module.plugin.logger.severe("Failed to copy default translation $fileName: ${e.message}")
            }
        }
    }

    private fun loadLanguageMap(fileName: String) {
        val file = File(File(folder, "translations"), fileName)
        if (!file.exists()) {
            languageMap = emptyMap()
            return
        }
        try {
            val reader = java.io.FileReader(file)
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            languageMap = com.google.gson.Gson().fromJson(reader, type) ?: emptyMap()
            reader.close()
        } catch (e: Exception) {
            module.plugin.logger.severe("Failed to load translation map from $fileName: ${e.message}")
            languageMap = emptyMap()
        }
    }

    // ฟังก์ชันช่วยดึงข้อความจาก messages.yml
    fun getMessage(path: String, def: String = ""): String {
        return messages.getString(path, def) ?: def
    }

    fun getMessageList(path: String): List<String> {
        return messages.getStringList(path)
    }
}
