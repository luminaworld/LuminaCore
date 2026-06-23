package core.luminaworld.modules.system.RconMonitor

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import core.luminaworld.util.DiscordWebhook
import org.bukkit.event.HandlerList
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RconMonitorModule(plugin: LuminaCore) : LuminaModule(plugin, "RconMonitor") {
    private var listener: RconListener? = null
    private var logFilter: RconLogFilter? = null

    override fun onEnable() {
        // ลงทะเบียน Event Listener
        listener = RconListener(this)
        plugin.server.pluginManager.registerEvents(listener!!, plugin)

        // ลงทะเบียน Log4j Filter เพื่อดัก RCON login
        registerLogFilter()
    }

    override fun onDisable() {
        // ถอนการลงทะเบียน Listener
        listener?.let {
            HandlerList.unregisterAll(it)
            listener = null
        }

        // ถอนการลงทะเบียน Log4j Filter
        unregisterLogFilter()
    }

    private fun registerLogFilter() {
        try {
            val ctx = org.apache.logging.log4j.LogManager.getContext(false) as org.apache.logging.log4j.core.LoggerContext
            val config = ctx.configuration
            val rootLoggerConfig = config.rootLogger

            logFilter = RconLogFilter(this)
            rootLoggerConfig.addFilter(logFilter)
            ctx.updateLoggers()
        } catch (e: Exception) {
            plugin.logger.severe("[LuminaCore] Failed to register Log4j Filter for RconMonitor: ${e.message}")
        }
    }

    private fun unregisterLogFilter() {
        logFilter?.let {
            try {
                val ctx = org.apache.logging.log4j.LogManager.getContext(false) as org.apache.logging.log4j.core.LoggerContext
                val config = ctx.configuration
                val rootLoggerConfig = config.rootLogger

                rootLoggerConfig.removeFilter(it)
                ctx.updateLoggers()
                logFilter = null
            } catch (e: Exception) {
                plugin.logger.severe("[LuminaCore] Failed to unregister Log4j Filter for RconMonitor: ${e.message}")
            }
        }
    }

    /**
     * ดักจับและประมวลผลการ Login RCON
     */
    fun handleRconLogin(ip: String) {
        if (!isEnabled) return
        if (config?.getBoolean("settings.notify-login", true) == false) return

        // ข้อความคำเตือนใน Console แบบแก้ไขคำได้และไม่มีอีโมจิ
        val consoleWarning = config?.getString("settings.console-warning", "[LuminaCore] RCON Login detected from IP: %ip%") 
            ?: "[LuminaCore] RCON Login detected from IP: %ip%"
        plugin.server.consoleSender.sendMessage(consoleWarning.replace("%ip%", ip))

        sendNotification("LoginEmbed") { it.replace("%ip%", ip) }
    }

    /**
     * ดักจับและประมวลผลคำสั่ง RCON
     */
    fun handleRconCommand(command: String) {
        if (!isEnabled) return
        sendNotification("CommandEmbed") { it.replace("%command%", command) }
    }

    private fun sendNotification(embedKey: String, customReplacer: (String) -> String) {
        var webhookUrl = config?.getString("settings.discord-webhook-url", "") ?: ""
        if (webhookUrl.isBlank()) {
            webhookUrl = plugin.config.getString("webhook.url") ?: ""
        }

        if (webhookUrl.isBlank() || webhookUrl == "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL") {
            return
        }

        val section = config?.getConfigurationSection(embedKey) ?: return
        val timeMs = System.currentTimeMillis()

        val baseReplacer: (String) -> String = { input ->
            val prefix = plugin.config.getString("settings.prefix", "[LuminaCore]") ?: "[LuminaCore]"
            val tz = plugin.config.getString("settings.timezone", "Asia/Bangkok") ?: "Asia/Bangkok"
            val timePattern = plugin.config.getString("settings.time-format", "dd/MM/yyyy HH:mm:ss") ?: "dd/MM/yyyy HH:mm:ss"

            val timeStr = try {
                val formatter = DateTimeFormatter.ofPattern(timePattern).withZone(ZoneId.of(tz))
                formatter.format(java.time.Instant.ofEpochMilli(timeMs))
            } catch (e: Exception) {
                java.time.Instant.ofEpochMilli(timeMs).toString()
            }

            input.replace("%prefix%", prefix)
                .replace("%time%", timeStr)
                .replace("%timestamp%", timeMs.toString())
                .replace("%plugin_name%", "LuminaCore")
                .replace("%plugin_version%", "1.0.0")
        }

        val combinedReplacer: (String) -> String = { input ->
            customReplacer(baseReplacer(input))
        }

        val webhook = DiscordWebhook.fromConfig(webhookUrl, section, combinedReplacer)
        val username = plugin.config.getString("webhook.username", "LuminaCore Notifier")
        val avatar = plugin.config.getString("webhook.avatar-url", "") ?: ""

        webhook.setUsername(username)
        if (avatar.isNotBlank()) {
            webhook.setAvatarUrl(avatar)
        }

        webhook.sendAsync(plugin)
    }
}
