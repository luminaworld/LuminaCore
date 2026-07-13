package core.luminaworld.modules.minigames.ChatGames

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import org.bukkit.event.HandlerList
import org.bukkit.configuration.file.YamlConfiguration

class ChatGamesModule(plugin: LuminaCore) : LuminaModule(plugin, "ChatGames") {

    val chatConfig = ChatGamesConfig(this)
    val database = ChatGamesDatabase(this)
    val gameManager = GameManager(this)
    
    private val chatListener = ChatListener(this)
    private val raceListener = RaceListener(this)
    val commandExecutor = ChatGamesCommand(this)

    override fun loadConfig() {
        chatConfig.reload()
        
        // กำหนดสถานะเปิดใช้งานตาม settings ใน config
        val enabledInModule = chatConfig.config.getBoolean("settings.enabled", true)
        isEnabled = enabledInModule
        commandExecutor.allGamesDisabled = !enabledInModule

        // โอนย้ายออบเจ็กต์คอนฟิกย่อยให้เป็นคอนฟิกหลักของ LuminaModule
        // เพื่อรองรับฟังก์ชัน checkPermission() และค่าดีฟอลต์อื่นๆ ในเบสคลาส
        try {
            val superConfigField = LuminaModule::class.java.getDeclaredField("config")
            superConfigField.isAccessible = true
            superConfigField.set(this, chatConfig.config)
        } catch (e: Exception) {
            plugin.logger.warning("[ChatGames] ไม่สามารถสะท้อนตั้งค่าคอนฟิกหลักย่อยได้: ${e.message}")
        }
    }

    override fun onEnable() {
        // สร้างตารางฐานข้อมูลย่อย
        database.createTable()

        // ลงทะเบียน Event Listeners
        plugin.server.pluginManager.registerEvents(chatListener, plugin)
        plugin.server.pluginManager.registerEvents(raceListener, plugin)

        // ลงทะเบียนคำสั่งไดนามิก /chatgames และตัวย่อ /cg
        plugin.commandManager?.registerCommand(
            name = "chatgames",
            executor = commandExecutor,
            tabCompleter = commandExecutor,
            description = "กิจกรรมมินิเกมแชทและการแข่งขันทำภารกิจในเกม",
            usage = "/chatgames",
            aliases = listOf("cg")
        )

        // เริ่มวงรอบจับเวลาการสุ่มเปิดกิจกรรม
        gameManager.startScheduler()

        // ลงทะเบียนตั้งค่าผู้เล่นแบบเจาะจง 4 รายการ
        val manager = core.luminaworld.settings.PlayerSettingsManager
        manager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = "chatgames_sound",
                displayName = "🔊 เสียงแชทเกม",
                material = org.bukkit.Material.JUKEBOX,
                description = "เปิด/ปิดการเล่นเสียงเมื่อเกิดกิจกรรมแชทเกม"
            )
        )
        manager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = "chatgames_message",
                displayName = "💬 ข้อความแชทเกม",
                material = org.bukkit.Material.WRITABLE_BOOK,
                description = "เปิด/ปิดการแจ้งคำถามและผู้ชนะทางช่องแชท"
            )
        )
        manager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = "chatgames_title",
                displayName = "📺 Title กลางจอ",
                material = org.bukkit.Material.PAINTING,
                description = "เปิด/ปิดการประกาศกลางหน้าจอเมื่อมีเกมแชท"
            )
        )
        manager.registerSetting(
            core.luminaworld.settings.PlayerSettingOption(
                moduleName = name,
                key = "chatgames_actionbar",
                displayName = "📢 Actionbar แชทเกม",
                material = org.bukkit.Material.BELL,
                description = "เปิด/ปิดการประกาศข้อความด่วนเหนือเกจเลือด"
            )
        )

        plugin.logger.info("§6[ChatGames] §aเปิดใช้งานระบบมินิเกมแชทเรียบร้อยแล้ว")
    }

    override fun onDisable() {
        // หยุดการทำงานวงรอบจับเวลาของมินิเกม
        gameManager.stopScheduler()

        // ยกเลิกคำสั่งไดนามิก
        plugin.commandManager?.unregisterCommand("chatgames")

        // ยกเลิก Event Listeners ของมินิเกม
        HandlerList.unregisterAll(chatListener)
        HandlerList.unregisterAll(raceListener)

        // ยกเลิกการลงทะเบียนตั้งค่าผู้เล่นทั้งหมดของโมดูลนี้
        core.luminaworld.settings.PlayerSettingsManager.unregisterSettingsOfModule(name)

        plugin.logger.info("§6[ChatGames] §cปิดระบบมินิเกมแชทเรียบร้อยแล้ว")
    }
}
