package core.luminaworld.modules.system.CreativeMonitor

import core.luminaworld.util.DiscordWebhook
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.ItemStack
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CreativeMonitorListener(private val module: CreativeMonitorModule) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCreativeSpawn(event: InventoryCreativeEvent) {
        if (!module.isEnabled) return
        if (module.config?.getBoolean("settings.track-item-obtain", true) == false) return

        val player = event.whoClicked as? Player ?: return
        if (player.gameMode != GameMode.CREATIVE) return

        val item = event.cursor
        if (item.type == Material.AIR) return

        val uuid = player.uniqueId
        val now = System.currentTimeMillis()

        // ตรวจสอบคูลดาวน์เพื่อหลีกเลี่ยงการสแปม Webhook
        val lastUse = module.cooldowns.getOrDefault(uuid, 0L)
        val cooldownSec = module.config?.getInt("settings.cooldown", 3) ?: 3
        val cooldownMs = cooldownSec * 1000L

        if (now - lastUse < cooldownMs) {
            return
        }
        module.cooldowns[uuid] = now

        sendDiscordNotification(player, item, null, "ObtainEmbed", now)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemDrop(event: PlayerDropItemEvent) {
        if (!module.isEnabled) return
        if (module.config?.getBoolean("settings.track-item-drop", true) == false) return

        val player = event.player
        if (player.gameMode != GameMode.CREATIVE) return

        val itemEntity = event.itemDrop
        val item = itemEntity.itemStack
        val dropLoc = itemEntity.location

        val uuid = player.uniqueId
        val now = System.currentTimeMillis()

        // ตรวจสอบคูลดาวน์
        val lastUse = module.cooldowns.getOrDefault(uuid, 0L)
        val cooldownSec = module.config?.getInt("settings.cooldown", 3) ?: 3
        val cooldownMs = cooldownSec * 1000L

        if (now - lastUse < cooldownMs) {
            return
        }
        module.cooldowns[uuid] = now

        sendDiscordNotification(player, item, dropLoc, "DropEmbed", now)
    }

    private fun sendDiscordNotification(
        player: Player,
        item: ItemStack,
        location: Location?,
        embedKey: String,
        timeMs: Long
    ) {
        val plugin = module.plugin
        var webhookUrl = module.config?.getString("settings.discord-webhook-url", "") ?: ""
        if (webhookUrl.isBlank()) {
            webhookUrl = plugin.config.getString("webhook.url") ?: ""
        }

        if (webhookUrl.isBlank() || webhookUrl == "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL") {
            return
        }

        val formatType = module.config?.getString("messages", "Embed1") ?: "Embed1"
        val section = module.config?.getConfigurationSection(embedKey)

        val replacer: (String) -> String = { input ->
            replacePlaceholders(input, player, item, location, timeMs)
        }

        val webhook = if (formatType.equals("normal", ignoreCase = true)) {
            val webhookObj = DiscordWebhook(webhookUrl)
            val path = if (embedKey.contains("Obtain")) "normal.obtain" else "normal.drop"
            val normalContent = module.config?.getString(path) ?: ""
            webhookObj.setContent(replacer(normalContent))
        } else {
            DiscordWebhook.fromConfig(webhookUrl, section, replacer)
        }

        // กำหนดรายละเอียด Bot
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
        item: ItemStack,
        location: Location?,
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
        val itemNameFormatted = formatMaterialName(item.type)
        val targetLoc = location ?: player.location

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
            .replace("%world_name%", targetLoc.world?.name ?: player.world.name)
            .replace("%world_type%", (targetLoc.world?.environment ?: player.world.environment).name)
            .replace("%server_name%", Bukkit.getServer().name)
            .replace("%server_version%", Bukkit.getServer().version)
            .replace("%online_players%", Bukkit.getServer().onlinePlayers.size.toString())
            .replace("%max_players%", Bukkit.getServer().maxPlayers.toString())
            .replace("%time%", timeStr)
            .replace("%timestamp%", timeMs.toString())
            .replace("%date%", dateStr)
            .replace("%time_only%", timeOnlyStr)
            
            // ข้อมูลไอเทม
            .replace("%item_type%", itemNameFormatted)
            .replace("%item_type_raw%", item.type.name)
            .replace("%item_amount%", item.amount.toString())
            
            // ข้อมูลพิกัด
            .replace("%block_x%", targetLoc.blockX.toString())
            .replace("%block_y%", targetLoc.blockY.toString())
            .replace("%block_z%", targetLoc.blockZ.toString())
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
