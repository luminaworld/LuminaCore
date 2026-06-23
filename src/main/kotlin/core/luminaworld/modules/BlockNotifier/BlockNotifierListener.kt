package core.luminaworld.modules.BlockNotifier

import core.luminaworld.util.DiscordWebhook
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BlockNotifierListener(private val module: BlockNotifierModule) : Listener {
    private val trackedBlocks = HashSet<Material>()

    init {
        cacheTrackedBlocks()
    }

    private fun cacheTrackedBlocks() {
        trackedBlocks.clear()
        val list = module.config?.getStringList("tracked-blocks") ?: emptyList()
        for (s in list) {
            try {
                val mat = Material.valueOf(s.uppercase())
                trackedBlocks.add(mat)
            } catch (ignored: IllegalArgumentException) {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!module.isEnabled) return

        val block = event.block
        val material = block.type

        if (!trackedBlocks.contains(material)) return

        val player = event.player
        val uuid = player.uniqueId

        // เช็คคูลดาวน์
        val now = System.currentTimeMillis()
        val lastUse = module.cooldowns.getOrDefault(uuid, 0L)
        val cooldownSec = module.config?.getInt("settings.cooldown", 5) ?: 5
        val cooldownMs = cooldownSec * 1000L

        if (now - lastUse < cooldownMs) {
            return
        }

        module.cooldowns[uuid] = now
        sendDiscordNotification(player, block, material, now)
    }

    private fun sendDiscordNotification(player: Player, block: Block, material: Material, timeMs: Long) {
        val plugin = module.plugin
        val webhookUrl = plugin.config.getString("webhook.url") ?: return
        if (webhookUrl.isBlank() || webhookUrl == "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL") {
            return
        }

        val formatType = module.config?.getString("messages", "Embed1") ?: "Embed1"
        val section = module.config?.getConfigurationSection(formatType)

        val replacer: (String) -> String = { input ->
            replacePlaceholders(input, player, block, material, timeMs)
        }

        // โหลดเนื้อหาตามรูปแบบที่กำหนด
        val webhook = if (formatType.equals("normal", ignoreCase = true)) {
            val normalContent = module.config?.getString("normal.content") ?: ""
            DiscordWebhook(webhookUrl).setContent(replacer(normalContent))
        } else {
            DiscordWebhook.fromConfig(webhookUrl, section, replacer)
        }

        // กำหนดข้อมูลโปรไฟล์บอท
        val username = plugin.config.getString("webhook.username", "LuminaCore Notifier")
        val avatar = plugin.config.getString("webhook.avatar-url", "") ?: ""
        
        webhook.setUsername(username)
        if (avatar.isNotBlank()) {
            webhook.setAvatarUrl(avatar)
        }

        // ส่ง Asynchronous
        webhook.sendAsync(plugin)
    }

    private fun replacePlaceholders(
        input: String?,
        player: Player,
        block: Block,
        material: Material,
        timeMs: Long
    ): String {
        if (input == null) return ""

        val plugin = module.plugin
        val prefix = plugin.config.getString("settings.prefix", "[LuminaCore]") ?: "[LuminaCore]"
        val tz = plugin.config.getString("settings.timezone", "Asia/Bangkok") ?: "Asia/Bangkok"
        val timePattern = plugin.config.getString("settings.time-format", "dd/MM/yyyy HH:mm:ss") ?: "dd/MM/yyyy HH:mm:ss"

        var timeStr = ""
        var dateStr = ""
        var timeOnlyStr = ""
        try {
            val formatter = DateTimeFormatter.ofPattern(timePattern).withZone(ZoneId.of(tz))
            val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of(tz))
            val timeOnlyFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of(tz))

            val instant = java.time.Instant.ofEpochMilli(timeMs)
            timeStr = formatter.format(instant)
            dateStr = dateFormatter.format(instant)
            timeOnlyStr = timeOnlyFormatter.format(instant)
        } catch (e: Exception) {
            timeStr = java.time.Instant.ofEpochMilli(timeMs).toString()
            dateStr = ""
            timeOnlyStr = ""
        }

        val blockNameFormatted = formatMaterialName(material)
        val playerAvatar = "https://cravatar.eu/avatar/${player.uniqueId}/64.png"

        return input
            .replace("%prefix%", prefix)
            .replace("%plugin_name%", "LuminaCore")
            .replace("%plugin_version%", "1.0.0")
            .replace("%player_name%", player.name)
            .replace("%player_display_name%", player.displayName)
            .replace("%player_uuid%", player.uniqueId.toString())
            .replace("%player_avatar%", playerAvatar)
            .replace("%player_x%", player.location.blockX.toString())
            .replace("%player_y%", player.location.blockY.toString())
            .replace("%player_z%", player.location.blockZ.toString())
            .replace("%world_name%", block.world.name)
            .replace("%world_type%", block.world.environment.name)
            .replace("%server_name%", Bukkit.getServer().name)
            .replace("%server_version%", Bukkit.getServer().version)
            .replace("%online_players%", Bukkit.getServer().onlinePlayers.size.toString())
            .replace("%max_players%", Bukkit.getServer().maxPlayers.toString())
            .replace("%time%", timeStr)
            .replace("%timestamp%", timeMs.toString())
            .replace("%date%", dateStr)
            .replace("%time_only%", timeOnlyStr)
            
            // ข้อมูลบล็อก
            .replace("%block_type%", blockNameFormatted)
            .replace("%block_type_raw%", material.name)
            .replace("%block_icon%", "")
            .replace("%block_x%", block.x.toString())
            .replace("%block_y%", block.y.toString())
            .replace("%block_z%", block.z.toString())
            .replace("%block_world%", block.world.name)
            .replace("%block_coordinates%", "${block.x}, ${block.y}, ${block.z}")
    }

    private fun formatMaterialName(material: Material): String {
        val name = material.name.replace('_', ' ').lowercase()
        val parts = name.split(" ")
        val builder = StringBuilder()
        for (part in parts) {
            if (part.isNotEmpty()) {
                builder.append(part.substring(0, 1).uppercase())
                    .append(part.substring(1))
                    .append(" ")
            }
        }
        return builder.toString().trim()
    }
}
