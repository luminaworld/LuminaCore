package core.luminaworld.modules.UnknownCommand

import core.luminaworld.util.DiscordWebhook
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UnknownCommandListener(private val module: UnknownCommandModule) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        if (!module.isEnabled) return

        val player = event.player
        val message = event.message

        if (!message.startsWith("/")) return

        val parts = message.split(" ")
        val commandBase = parts[0]
        val commandLabel = commandBase.substring(1).lowercase()

        val commandArgs = if (message.length > commandBase.length) {
            message.substring(commandBase.length + 1)
        } else {
            ""
        }

        val monitoredCommands = module.config?.getStringList("monitored-commands") ?: emptyList()
        val ignoredCommands = module.config?.getStringList("ignored-commands") ?: emptyList()

        var detectorType: String? = null

        // 1. ตรวจสอบว่าเป็นคำสั่งที่ถูกเฝ้าระวัง (Monitored Command เช่น /op, /gamemode) หรือไม่
        val isMonitored = monitoredCommands.any { mc ->
            mc.equals(commandBase, ignoreCase = true) || 
            mc.equals("/$commandLabel", ignoreCase = true) || 
            mc.equals(commandLabel, ignoreCase = true)
        }

        if (isMonitored) {
            detectorType = "Monitored Command"
        } else {
            // 2. ตรวจสอบว่าเป็นคำสั่งที่ไม่รู้จัก (Unknown Command) หรือไม่
            val commandObj = Bukkit.getServer().commandMap.getCommand(commandLabel)
            if (commandObj == null) {
                val isIgnored = ignoredCommands.any { ic ->
                    ic.equals(commandBase, ignoreCase = true) || 
                    ic.equals("/$commandLabel", ignoreCase = true) || 
                    ic.equals(commandLabel, ignoreCase = true)
                }

                if (!isIgnored) {
                    detectorType = "Unknown Command"
                }
            }
        }

        if (detectorType == null) return

        // ตรวจสอบและตั้งค่าคูลดาวน์ต่อผู้เล่น
        val uuid = player.uniqueId
        val now = System.currentTimeMillis()
        val lastUse = module.cooldowns.getOrDefault(uuid, 0L)
        val cooldownSec = module.config?.getInt("settings.cooldown", 3) ?: 3
        val cooldownMs = cooldownSec * 1000L

        if (now - lastUse < cooldownMs) {
            return
        }

        module.cooldowns[uuid] = now

        sendDiscordNotification(player, message, commandBase, commandArgs, parts.size, detectorType, now)
    }

    private fun sendDiscordNotification(
        player: Player,
        fullCommand: String,
        commandBase: String,
        args: String,
        partsCount: Int,
        detectorType: String,
        timeMs: Long
    ) {
        val plugin = module.plugin
        val webhookUrl = plugin.config.getString("webhook.url") ?: return
        if (webhookUrl.isBlank() || webhookUrl == "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL") {
            return
        }

        val formatType = module.config?.getString("messages", "Embed1") ?: "Embed1"
        val section = module.config?.getConfigurationSection(formatType)

        val replacer: (String) -> String = { input ->
            replacePlaceholders(input, player, fullCommand, commandBase, args, partsCount, detectorType, timeMs)
        }

        val webhook = if (formatType.equals("normal", ignoreCase = true)) {
            val normalContent = module.config?.getString("normal.content") ?: ""
            DiscordWebhook(webhookUrl).setContent(replacer(normalContent))
        } else {
            DiscordWebhook.fromConfig(webhookUrl, section, replacer)
        }

        val username = plugin.config.getString("webhook.username", "LuminaCore Notifier")
        val avatar = plugin.config.getString("webhook.avatar-url", "") ?: ""

        webhook.setUsername(username)
        if (avatar.isNotBlank()) {
            webhook.setAvatarUrl(avatar)
        }

        webhook.sendAsync(plugin)
    }

    private fun replacePlaceholders(
        input: String?,
        player: Player,
        fullCommand: String,
        commandBase: String,
        args: String,
        partsCount: Int,
        detectorType: String,
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
            .replace("%world_name%", player.world.name)
            .replace("%world_type%", player.world.environment.name)
            .replace("%server_name%", Bukkit.getServer().name)
            .replace("%server_version%", Bukkit.getServer().version)
            .replace("%online_players%", Bukkit.getServer().onlinePlayers.size.toString())
            .replace("%max_players%", Bukkit.getServer().maxPlayers.toString())
            .replace("%time%", timeStr)
            .replace("%timestamp%", timeMs.toString())
            .replace("%date%", dateStr)
            .replace("%time_only%", timeOnlyStr)
            
            // suspicious command placeholders
            .replace("%command%", fullCommand)
            .replace("%command_base%", commandBase)
            .replace("%command_args%", args)
            .replace("%command_length%", fullCommand.length.toString())
            .replace("%command_parts%", partsCount.toString())
            .replace("%detector_type%", detectorType)
    }
}
