package core.luminaworld.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

object ColorParser {
    private val NORSKA_GRADIENT_REGEX = Regex("<GRADIENT:([A-Fa-f0-9]{6})>(.*?)</GRADIENT:([A-Fa-f0-9]{6})>", RegexOption.IGNORE_CASE)
    private val HEX_REGEX = Regex("&#([A-Fa-f0-9]{6})")
    
    // ดักจับฟอร์แมต &x&7&4&F&F&8&3 และ §x§7§4§F§F§8§3 (BungeeCord Hex Color Format)
    private val AMPERSAND_HEX_REGEX = Regex("&x&([A-Fa-f0-9])&([A-Fa-f0-9])&([A-Fa-f0-9])&([A-Fa-f0-9])&([A-Fa-f0-9])&([A-Fa-f0-9])", RegexOption.IGNORE_CASE)
    private val SECTION_HEX_REGEX = Regex("§x§([A-Fa-f0-9])§([A-Fa-f0-9])§([A-Fa-f0-9])§([A-Fa-f0-9])§([A-Fa-f0-9])§([A-Fa-f0-9])", RegexOption.IGNORE_CASE)

    private val LEGACY_COLOR_MAP = mapOf(
        "&0" to "<black>", "&1" to "<dark_blue>", "&2" to "<dark_green>", "&3" to "<dark_aqua>",
        "&4" to "<dark_red>", "&5" to "<dark_purple>", "&6" to "<gold>", "&7" to "<gray>",
        "&8" to "<dark_gray>", "&9" to "<blue>", "&a" to "<green>", "&b" to "<aqua>",
        "&c" to "<red>", "&d" to "<light_purple>", "&e" to "<yellow>", "&f" to "<white>",
        "&k" to "<obfuscated>", "&l" to "<bold>", "&m" to "<strikethrough>",
        "&n" to "<underlined>", "&o" to "<italic>", "&r" to "<reset>",
        "§0" to "<black>", "§1" to "<dark_blue>", "§2" to "<dark_green>", "§3" to "<dark_aqua>",
        "§4" to "<dark_red>", "§5" to "<dark_purple>", "§6" to "<gold>", "§7" to "<gray>",
        "§8" to "<dark_gray>", "§9" to "<blue>", "§a" to "<green>", "§b" to "<aqua>",
        "§c" to "<red>", "§d" to "<light_purple>", "§e" to "<yellow>", "§f" to "<white>",
        "§k" to "<obfuscated>", "§l" to "<bold>", "§m" to "<strikethrough>",
        "§n" to "<underlined>", "§o" to "<italic>", "§r" to "<reset>"
    )

    /**
     * แปลง String รหัสสีทุกรูปแบบเป็น Component ของ Adventure
     */
    fun parse(input: String): Component {
        var formatted = input

        // 1. แปลงฟอร์แมต Bungee Hex แบบ &x&7&4&F&F&8&3 -> <#74FF83>
        formatted = AMPERSAND_HEX_REGEX.replace(formatted) { matchResult ->
            val hex = matchResult.groupValues.drop(1).joinToString("")
            "<#$hex>"
        }
        
        // 2. แปลงฟอร์แมต Bungee Hex แบบ §x§7§4§F§F§8§3 -> <#74FF83>
        formatted = SECTION_HEX_REGEX.replace(formatted) { matchResult ->
            val hex = matchResult.groupValues.drop(1).joinToString("")
            "<#$hex>"
        }

        // 3. แปลง Norska's gradient <GRADIENT:XXXXXX>text</GRADIENT:YYYYYY> -> <gradient:#XXXXXX:#YYYYYY>text</gradient>
        formatted = NORSKA_GRADIENT_REGEX.replace(formatted) { matchResult ->
            val startColor = matchResult.groupValues[1]
            val content = matchResult.groupValues[2]
            val endColor = matchResult.groupValues[3]
            "<gradient:#$startColor:#$endColor>$content</gradient>"
        }

        // 4. แปลง HEX สีแบบ &#xxxxxx -> <#xxxxxx>
        formatted = HEX_REGEX.replace(formatted) { matchResult ->
            "<#${matchResult.groupValues[1]}>"
        }

        // 5. แปลงสีดั้งเดิม & และ § เป็น MiniMessage tags
        for ((legacy, tag) in LEGACY_COLOR_MAP) {
            formatted = formatted.replace(legacy, tag)
        }

        return try {
            MiniMessage.miniMessage().deserialize(formatted)
        } catch (e: Exception) {
            // Fallback กรณีพาร์ส MiniMessage ผิดพลาด
            LegacyComponentSerializer.legacyAmpersand().deserialize(input)
        }
    }
}
