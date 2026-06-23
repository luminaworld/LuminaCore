package core.luminaworld.modules.features.ColorSign

import org.bukkit.Sound
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent
import core.luminaworld.LuminaCore
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class ColorSignListener(private val plugin: LuminaCore, private val module: ColorSignModule) : Listener {

    @EventHandler
    fun onSignChange(event: SignChangeEvent) {
        val player = event.player
        if (!module.isEnabled) return
        
        // ตรวจสอบ permission เฉพาะของระบบนี้
        if (!module.checkPermission(player)) return

        val config = module.config ?: return
        val allowHex = config.getBoolean("settings.allow-hex", true)
        val allowMiniMessage = config.getBoolean("settings.allow-minimessage", true)
        val allowLegacy = config.getBoolean("settings.allow-legacy", true)

        var formattedAny = false

        for (i in 0..3) {
            val component = event.line(i) ?: continue
            val plainText = PlainTextComponentSerializer.plainText().serialize(component)
            if (plainText.isEmpty()) continue

            // จัดรูปแบบข้อความ
            var textToFormat = plainText
            
            if (allowHex) {
                val hexRegex = Regex("&#([A-Fa-f0-9]{6})")
                textToFormat = hexRegex.replace(textToFormat) { matchResult ->
                    "<#${matchResult.groupValues[1]}>"
                }
            }

            if (allowLegacy) {
                val legacyMap = mapOf(
                    "&0" to "<black>", "&1" to "<dark_blue>", "&2" to "<dark_green>", "&3" to "<dark_aqua>",
                    "&4" to "<dark_red>", "&5" to "<dark_purple>", "&6" to "<gold>", "&7" to "<gray>",
                    "&8" to "<dark_gray>", "&9" to "<blue>", "&a" to "<green>", "&b" to "<aqua>",
                    "&c" to "<red>", "&d" to "<light_purple>", "&e" to "<yellow>", "&f" to "<white>",
                    "&k" to "<obfuscated>", "&l" to "<bold>", "&m" to "<strikethrough>",
                    "&n" to "<underlined>", "&o" to "<italic>", "&r" to "<reset>"
                )
                for ((legacy, tag) in legacyMap) {
                    textToFormat = textToFormat.replace(legacy, tag)
                }
            }

            val newComponent = if (allowMiniMessage) {
                try {
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(textToFormat)
                } catch (e: Exception) {
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(plainText)
                }
            } else {
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(plainText)
            }

            if (newComponent != component) {
                event.line(i, newComponent)
                formattedAny = true
            }
        }

        if (formattedAny) {
            val audioEnabled = config.getBoolean("settings.audio.enabled", true)
            if (audioEnabled) {
                val soundName = config.getString("settings.audio.sound", "ENTITY_ITEM_FRAME_ADD_ITEM") ?: "ENTITY_ITEM_FRAME_ADD_ITEM"
                val volume = config.getDouble("settings.audio.volume", 1.0).toFloat()
                val pitch = config.getDouble("settings.audio.pitch", 1.2).toFloat()
                try {
                    val sound = Sound.valueOf(soundName)
                    player.playSound(player.location, sound, volume, pitch)
                } catch (e: Exception) {
                    // Ignore
                }
            }

            val msg = config.getString("messages.formatted", "%prefix% &aจัดรูปแบบป้ายข้อความสำเร็จ!") ?: ""
            if (msg.isNotEmpty()) {
                module.sendNotification(player, msg)
            }
        }
    }
}
