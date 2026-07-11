package core.luminaworld.modules.system.UltimateAutoRestart

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import core.luminaworld.util.DiscordWebhook
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class UltimateAutoRestartModule(plugin: LuminaCore) : LuminaModule(plugin, "UltimateAutoRestart") {

    lateinit var uarConfig: YamlConfiguration
        private set
    lateinit var uarMessages: YamlConfiguration
        private set
    lateinit var uarSounds: YamlConfiguration
        private set
    lateinit var uarWebhook: YamlConfiguration
        private set

    private val moduleDir = File(plugin.dataFolder, "UltimateAutoRestart")
    
    // Runtime States
    var restartTimeMs: Long? = null
    var isStopped = false
    var isDelayed = false
    var currentReason = ""
    private var lastProcessedSeconds: Long? = null
    
    private var schedulerTask: ScheduledTask? = null
    private val parsedRestartCommands = mutableListOf<ScheduledCommand>()

    // Getters for configuration
    val defaultReason: String 
        get() = uarMessages.getString("defaultReason", "Scheduled restart") ?: "Scheduled restart"
        
    val restarts: List<String> 
        get() = uarConfig.getStringList("settings.restarts")
        
    val messageAtIntervals: List<Long> 
        get() = uarConfig.getStringList("settings.messageAtIntervals").mapNotNull { it.toLongOrNull() }
        
    val soundAtIntervals: List<Long> 
        get() = uarConfig.getStringList("settings.soundAtIntervals").mapNotNull { it.toLongOrNull() }
        
    val delayRestartEnabled: Boolean 
        get() = uarConfig.getBoolean("delayRestart.enabled", false)
        
    val delayCheckSeconds: Int 
        get() = uarConfig.getInt("delayRestart.checkSecondsBeforeRestart", 30)
        
    val delayMinPlayers: Int 
        get() = uarConfig.getInt("delayRestart.minimumPlayers", 1)
        
    val delayBySeconds: Int 
        get() = uarConfig.getInt("delayRestart.delayBySeconds", 600)

    override fun loadConfig() {
        if (!moduleDir.exists()) {
            moduleDir.mkdirs()
        }

        val files = listOf("config.yml", "messages.yml", "sounds.yml", "webhook.yml")
        val classPackage = javaClass.`package`.name.replace(".", "/")
        
        for (fileName in files) {
            val file = File(moduleDir, fileName)
            val resourcePath = "$classPackage/$fileName"
            if (!file.exists()) {
                val inputStream = javaClass.classLoader.getResourceAsStream(resourcePath)
                if (inputStream != null) {
                    Files.copy(inputStream, file.toPath())
                } else {
                    plugin.logger.warning("Could not find default configuration resource for UltimateAutoRestart: $resourcePath")
                }
            } else {
                plugin.updateConfig(file, resourcePath)
            }
        }

        uarConfig = YamlConfiguration.loadConfiguration(File(moduleDir, "config.yml"))
        uarMessages = YamlConfiguration.loadConfiguration(File(moduleDir, "messages.yml"))
        uarSounds = YamlConfiguration.loadConfiguration(File(moduleDir, "sounds.yml"))
        uarWebhook = YamlConfiguration.loadConfiguration(File(moduleDir, "webhook.yml"))

        // settings.enabled ของโมดูล UAR (ใช้แยกต่างหากได้)
        val enabled = if (uarConfig.contains("settings.enabled")) uarConfig.getBoolean("settings.enabled") else true
        isEnabled = enabled

        // แปลงคำสั่งที่ต้องทำงานล่วงหน้า/บนรีสตาร์ท
        parsedRestartCommands.clear()
        val commandsList = uarConfig.getStringList("settings.restartCommands")
        for (line in commandsList) {
            val parsed = parseScheduledCommand(line)
            if (parsed != null) {
                parsedRestartCommands.add(parsed)
            }
        }
    }

    override fun onEnable() {
        // ลงทะเบียนคำสั่งและผูกมัด
        val cmd = UltimateAutoRestartCommand(this)
        plugin.commandManager?.registerCommand(
            name = "uar",
            executor = cmd,
            tabCompleter = cmd,
            description = "UltimateAutoRestart Control Command",
            usage = "/uar [status|force|now|delay|stop|reload|debug]",
            aliases = listOf("ultimateautorestart")
        )

        // ลงทะเบียน plugin channel สำหรับ Proxy commands
        if (!plugin.server.messenger.isOutgoingChannelRegistered(plugin, "BungeeCord")) {
            plugin.server.messenger.registerOutgoingPluginChannel(plugin, "BungeeCord")
        }

        // คำนวณและตั้งการรีสตาร์ทถัดไป
        calculateAndScheduleNextRestart()

        // ทำงาน commandsAfterReboot หลังเริ่มต้นระบบ
        executeStartupCommands()

        // Webhook: แจ้งเตือนเซิร์ฟเวอร์เปิดใช้งานสำเร็จ
        sendDiscordWebhook("SERVER_BACK_UP", mapOf(
            "{TIMESTAMP}" to getWebhookTime(),
            "{SECONDS}" to "0",
            "{FORMATTED}" to "",
            "{REASON}" to ""
        ))

        // เริ่ม Ticker Task ทุก 1 วินาที (20 Ticks)
        schedulerTask = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { _ ->
            tick()
        }, 20L, 20L)
        
        plugin.logger.info("§6[UltimateAutoRestart] §aระบบรีสตาร์ทเซิร์ฟเวอร์เริ่มทำงานแล้ว")
    }

    override fun onDisable() {
        // ยกเลิกคำสั่ง
        plugin.commandManager?.unregisterCommand("uar")
        
        // เคลียร์ Ticker Task
        schedulerTask?.cancel()
        schedulerTask = null
        
        // ล้างสถานะ Runtime
        restartTimeMs = null
        parsedRestartCommands.clear()
        
        plugin.logger.info("§6[UltimateAutoRestart] §cปิดการทำงานของระบบเรียบร้อยแล้ว")
    }

    /**
     * ตัวทำสเก็ตดูลตรวจสอบในแต่ละวินาที
     */
    private fun tick() {
        if (!isEnabled || isStopped) return
        
        val timeMs = restartTimeMs ?: return
        val now = System.currentTimeMillis()
        val remainingSecs = Math.max(0L, (timeMs - now) / 1000L)
        
        if (remainingSecs == lastProcessedSeconds) return
        lastProcessedSeconds = remainingSecs
        
        // การเช็คเลื่อนเวลา (Delay Restart) ก่อนหมดเวลารีสตาร์ท
        if (delayRestartEnabled && remainingSecs == delayCheckSeconds.toLong() && !isDelayed) {
            val onlinePlayers = plugin.server.onlinePlayers.size
            if (onlinePlayers >= delayMinPlayers) {
                // เลื่อนเวลารีสตาร์ทออกไป
                restartTimeMs = timeMs + (delayBySeconds * 1000L)
                isDelayed = true
                
                val delayFormatted = formatTimeRemaining(delayBySeconds.toLong())
                val placeholders = mapOf("{0}" to delayFormatted)
                
                sendFormattedMessage("EVENT_RESTART_DELAY_GLOBAL", placeholders)
                playSound("EVENT_RESTART_DELAY_GLOBAL")
                
                // แจ้งเตือนสตาฟ
                val staffSection = uarMessages.getConfigurationSection("messages.EVENT_RESTART_DELAY_STAFF")
                if (staffSection != null && staffSection.getBoolean("enabled", false)) {
                    val prefix = uarMessages.getString("messages.prefix", "") ?: ""
                    val content = staffSection.getStringList("chatMessage.content")
                    for (line in content) {
                        val formatted = line.replace("{PREFIX}", prefix).replace("{0}", delayFormatted)
                        val component = parseToComponent(formatted)
                        for (player in plugin.server.onlinePlayers) {
                            if (player.hasPermission("uar.notifydelay") || player.isOp) {
                                player.sendMessage(component)
                            }
                        }
                        plugin.server.consoleSender.sendMessage(component)
                    }
                }
                playSound("EVENT_RESTART_DELAY_STAFF")
                return
            }
        }
        
        val today = java.time.LocalDate.now().dayOfWeek.name
        
        // 1. ส่งข้อความแจ้งเตือนตามระยะเวลาที่กำหนด
        if (messageAtIntervals.contains(remainingSecs)) {
            val formatted = formatTimeRemaining(remainingSecs)
            sendFormattedMessage("EVENT_INTERVAL_GLOBAL", mapOf(
                "{0}" to formatted,
                "{1}" to currentReason
            ))
        }
        
        // 2. เล่นเสียงแจ้งเตือนตามระยะเวลาที่กำหนด
        if (soundAtIntervals.contains(remainingSecs)) {
            playSound("EVENT_INTERVAL_GLOBAL")
        }
        
        // 3. รันคำสั่งที่ระบุเวลาไว้ล่วงหน้าก่อนรีสตาร์ท
        for (cmd in parsedRestartCommands) {
            if (cmd.timeBeforeRestart == remainingSecs.toInt() && !cmd.isProxyDelay) {
                if (cmd.days.isEmpty() || cmd.days.contains(today)) {
                    executeCommand(cmd)
                }
            }
        }
        
        // 4. รีสตาร์ทจริงเมื่อนับถอยหลังเป็น 0
        if (remainingSecs == 0L) {
            executeRestart()
        }
    }

    /**
     * ดำเนินการรีสตาร์ทเซิร์ฟเวอร์
     */
    private fun executeRestart() {
        isStopped = true
        
        // ส่ง Webhook แจ้งเตือนรีสตาร์ท
        sendDiscordWebhook("SERVER_RESTARTING", mapOf(
            "{TIMESTAMP}" to getWebhookTime(),
            "{SECONDS}" to "0",
            "{FORMATTED}" to formatTimeRemaining(0),
            "{REASON}" to currentReason
        ))
        
        // ส่งข้อความและเล่นเสียง
        sendFormattedMessage("EVENT_RESTART_GLOBAL", emptyMap())
        playSound("EVENT_RESTART_GLOBAL")
        
        val today = java.time.LocalDate.now().dayOfWeek.name
        
        // รันคำสั่งทั้งหมดที่จะต้องทำตอนรีสตาร์ท (0 วินาที)
        for (cmd in parsedRestartCommands) {
            if (cmd.timeBeforeRestart == 0 && !cmd.isProxyDelay) {
                if (cmd.days.isEmpty() || cmd.days.contains(today)) {
                    executeCommand(cmd)
                }
            }
        }
        
        // รันคำสั่ง proxy delay
        for (cmd in parsedRestartCommands) {
            if (cmd.isProxyDelay) {
                if (cmd.days.isEmpty() || cmd.days.contains(today)) {
                    plugin.server.globalRegionScheduler.runDelayed(plugin, { _ ->
                        sendProxyCommand(cmd.command)
                    }, cmd.proxyDelaySec * 20L)
                }
            }
        }
    }

    /**
     * คำนวณและตั้งเวลาการรีสตาร์ทถัดไปตาม config
     */
    fun calculateAndScheduleNextRestart() {
        isStopped = false
        isDelayed = false
        currentReason = defaultReason
        lastProcessedSeconds = null
        val nextTime = getNextRestartTimeMs(restarts)
        if (nextTime != Long.MAX_VALUE) {
            restartTimeMs = nextTime
            plugin.logger.info("§6[UltimateAutoRestart] §aScheduled next restart at: ${Date(nextTime)}")
        } else {
            restartTimeMs = null
            plugin.logger.info("§6[UltimateAutoRestart] §eNo restarts scheduled in config.yml.")
        }
    }

    /**
     * รันคำสั่งหลังเริ่มต้นระบบ (commandsAfterReboot)
     */
    private fun executeStartupCommands() {
        val today = java.time.LocalDate.now().dayOfWeek.name
        val startupCommands = uarConfig.getStringList("settings.commandsAfterReboot")
        for (line in startupCommands) {
            val startupCmd = parseStartupCommand(line) ?: continue
            
            // เช็ควันหากกำหนดไว้เฉพาะ
            if (startupCmd.days.isNotEmpty() && !startupCmd.days.contains(today)) {
                continue
            }
            
            plugin.server.globalRegionScheduler.runDelayed(plugin, { _ ->
                if (startupCmd.isProxy) {
                    sendProxyCommand(startupCmd.command)
                } else {
                    plugin.server.dispatchCommand(plugin.server.consoleSender, startupCmd.command)
                }
            }, startupCmd.delaySec * 20L)
        }
    }

    /**
     * จัดการยิงคำสั่งรีสตาร์ท
     */
    private fun executeCommand(cmd: ScheduledCommand) {
        plugin.server.globalRegionScheduler.execute(plugin) {
            if (cmd.isProxy) {
                sendProxyCommand(cmd.command)
            } else {
                plugin.server.dispatchCommand(plugin.server.consoleSender, cmd.command)
            }
        }
    }

    /**
     * ส่งคำสั่งยิงไปที่ BungeeCord proxy
     */
    fun sendProxyCommand(command: String) {
        val player = plugin.server.onlinePlayers.firstOrNull()
        if (player != null) {
            try {
                val out = com.google.common.io.ByteStreams.newDataOutput()
                out.writeUTF("ExecuteCommand")
                out.writeUTF(command)
                player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray())
            } catch (e: Exception) {
                plugin.logger.warning("Failed to send proxy command via plugin message: ${e.message}")
            }
        } else {
            // หากไม่มีผู้เล่นออนไลน์ ให้ลงบันทึกเตือนไว้
            plugin.logger.warning("No online players to relay proxy command: $command")
        }
    }

    /**
     * ส่งฟอร์แมตข้อความแบบ UAR
     */
    fun sendFormattedMessage(sectionKey: String, placeholders: Map<String, String>) {
        val section = uarMessages.getConfigurationSection("messages.$sectionKey") ?: return
        val prefix = uarMessages.getString("messages.prefix", "") ?: ""
        
        val replacer: (String) -> String = { text ->
            var result = text.replace("{PREFIX}", prefix)
            placeholders.forEach { (k, v) ->
                result = result.replace(k, v)
            }
            result
        }

        // 1. Chat Message
        val chatSection = section.getConfigurationSection("chatMessage")
        if (chatSection != null && chatSection.getBoolean("enabled", false)) {
            val content = chatSection.getStringList("content")
            for (line in content) {
                val formatted = replacer(line)
                val component = parseToComponent(formatted)
                for (player in plugin.server.onlinePlayers) {
                    player.sendMessage(component)
                }
                plugin.server.consoleSender.sendMessage(component)
            }
        }

        // 2. Action Bar
        val actionbarSection = section.getConfigurationSection("actionbar")
        if (actionbarSection != null && actionbarSection.getBoolean("enabled", false)) {
            val content = actionbarSection.getString("content") ?: ""
            val formatted = replacer(content)
            val component = parseToComponent(formatted)
            for (player in plugin.server.onlinePlayers) {
                player.sendActionBar(component)
            }
        }

        // 3. Titles
        val titlesSection = section.getConfigurationSection("titles")
        if (titlesSection != null && titlesSection.getBoolean("enabled", false)) {
            val titleContent = titlesSection.getString("titleContent") ?: ""
            val subtitleContent = titlesSection.getString("subtitleContent") ?: ""
            val titleComp = parseToComponent(replacer(titleContent))
            val subComp = parseToComponent(replacer(subtitleContent))
            
            val title = net.kyori.adventure.title.Title.title(
                titleComp,
                subComp,
                net.kyori.adventure.title.Title.Times.times(
                    java.time.Duration.ofMillis(500),
                    java.time.Duration.ofMillis(2000),
                    java.time.Duration.ofMillis(500)
                )
            )
            for (player in plugin.server.onlinePlayers) {
                player.showTitle(title)
            }
        }
    }

    /**
     * ส่งฟอร์แมตข้อความแบบเจาะจงผู้ส่ง
     */
    fun sendFormattedMessageToSender(sender: org.bukkit.command.CommandSender, sectionKey: String, placeholders: Map<String, String>) {
        val section = uarMessages.getConfigurationSection("messages.$sectionKey") ?: return
        val prefix = uarMessages.getString("messages.prefix", "") ?: ""
        
        val replacer: (String) -> String = { text ->
            var result = text.replace("{PREFIX}", prefix)
            placeholders.forEach { (k, v) ->
                result = result.replace(k, v)
            }
            result
        }

        val chatSection = section.getConfigurationSection("chatMessage")
        if (chatSection != null && chatSection.getBoolean("enabled", false)) {
            val content = chatSection.getStringList("content")
            for (line in content) {
                val formatted = replacer(line)
                sender.sendMessage(parseToComponent(formatted))
            }
        }

        if (sender is org.bukkit.entity.Player) {
            val actionbarSection = section.getConfigurationSection("actionbar")
            if (actionbarSection != null && actionbarSection.getBoolean("enabled", false)) {
                val content = actionbarSection.getString("content") ?: ""
                val formatted = replacer(content)
                sender.sendActionBar(parseToComponent(formatted))
            }

            val titlesSection = section.getConfigurationSection("titles")
            if (titlesSection != null && titlesSection.getBoolean("enabled", false)) {
                val titleContent = titlesSection.getString("titleContent") ?: ""
                val subtitleContent = titlesSection.getString("subtitleContent") ?: ""
                val title = net.kyori.adventure.title.Title.title(
                    parseToComponent(replacer(titleContent)),
                    parseToComponent(replacer(subtitleContent))
                )
                sender.showTitle(title)
            }
        }
    }

    /**
     * เล่นเสียงโดยดึงข้อมูลจาก sounds.yml
     */
    fun playSound(sectionKey: String, targetPlayer: org.bukkit.entity.Player? = null) {
        val section = uarSounds.getConfigurationSection("sounds.$sectionKey") ?: return
        if (!section.getBoolean("enabled", false)) return
        
        val soundStr = section.getString("sound") ?: return
        val volume = section.getDouble("volume", 1.0).toFloat()
        val pitch = section.getDouble("pitch", 1.0).toFloat()
        
        try {
            val sound = Sound.valueOf(soundStr.uppercase())
            if (targetPlayer != null) {
                targetPlayer.playSound(targetPlayer.location, sound, volume, pitch)
            } else {
                for (player in plugin.server.onlinePlayers) {
                    player.playSound(player.location, sound, volume, pitch)
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Could not play sound $soundStr: ${e.message}")
        }
    }

    /**
     * ส่ง Webhook ไปที่ Discord
     */
    fun sendDiscordWebhook(key: String, placeholders: Map<String, String>) {
        val enabled = uarWebhook.getBoolean("webhook.enabled", false)
        val url = uarWebhook.getString("webhook.url", "") ?: ""
        if (!enabled || url.isBlank() || url == "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL") return

        val section = uarWebhook.getConfigurationSection("messages.$key") ?: return
        if (!section.getBoolean("enabled", false)) return

        val contentPattern = section.getString("content") ?: ""
        var formattedContent = contentPattern
        placeholders.forEach { (k, v) ->
            formattedContent = formattedContent.replace(k, v)
        }

        val webhook = DiscordWebhook(url)
        webhook.setContent(formattedContent)
        
        val username = plugin.config.getString("webhook.username", "LuminaCore Notifier")
        val avatar = plugin.config.getString("webhook.avatar-url", "") ?: ""
        webhook.setUsername(username)
        if (avatar.isNotBlank()) {
            webhook.setAvatarUrl(avatar)
        }
        
        webhook.sendAsync(plugin)
    }

    fun getWebhookTime(): String {
        val pattern = uarWebhook.getString("format.timestamp", "dd.MM.yyyy - HH:mm:ss") ?: "dd.MM.yyyy - HH:mm:ss"
        return try {
            val sdf = SimpleDateFormat(pattern, Locale.ENGLISH)
            sdf.format(Date())
        } catch (e: Exception) {
            Date().toString()
        }
    }

    /**
     * แปลงระยะเวลาเป็นข้อความฟอร์แมตภาษาไทยหรือตาม config
     */
    fun formatTimeRemaining(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        val daysStr = uarConfig.getString("format.days", "D ") ?: "D "
        val dayStr = uarConfig.getString("format.day", "D ") ?: "D "
        val hoursStr = uarConfig.getString("format.hours", "h ") ?: "h "
        val hourStr = uarConfig.getString("format.hour", "h ") ?: "h "
        val minutesStr = uarConfig.getString("format.minutes", "m ") ?: "m "
        val minuteStr = uarConfig.getString("format.minute", "m ") ?: "m "
        val secondsStr = uarConfig.getString("format.seconds", "s") ?: "s"
        val secondStr = uarConfig.getString("format.second", "s") ?: "s"
        val splitter = uarConfig.getString("format.splitter", "and ") ?: "and "

        val parts = mutableListOf<String>()
        if (days > 0) {
            parts.add("$days${if (days == 1L) dayStr else daysStr}".trim())
        }
        if (hours > 0) {
            parts.add("$hours${if (hours == 1L) hourStr else hoursStr}".trim())
        }
        if (minutes > 0) {
            parts.add("$minutes${if (minutes == 1L) minuteStr else minutesStr}".trim())
        }
        if (secs > 0 || parts.isEmpty()) {
            parts.add("$secs${if (secs == 1L) secondStr else secondsStr}".trim())
        }

        return if (parts.size > 1) {
            val last = parts.removeAt(parts.size - 1)
            parts.joinToString(", ") + " " + splitter.trim() + " " + last
        } else {
            parts.firstOrNull() ?: ""
        }
    }

    /**
     * หาเวลาการรีสตาร์ทถัดไป
     */
    private fun getNextRestartTimeMs(restarts: List<String>): Long {
        val safetyNow = Calendar.getInstance().apply { add(Calendar.SECOND, 30) }
        var minRestartTime: Long = Long.MAX_VALUE
        
        for (entry in restarts) {
            val parts = entry.split(";")
            if (parts.size < 3) continue
            val dayStr = parts[0]
            val hour = parts[1].toIntOrNull() ?: continue
            val minute = parts[2].toIntOrNull() ?: continue
            
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            
            if (dayStr.equals("Daily", ignoreCase = true)) {
                if (cal.before(safetyNow)) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                if (cal.timeInMillis < minRestartTime) {
                    minRestartTime = cal.timeInMillis
                }
            } else {
                val dayOfWeek = when (dayStr.uppercase()) {
                    "SUNDAY" -> Calendar.SUNDAY
                    "MONDAY" -> Calendar.MONDAY
                    "TUESDAY" -> Calendar.TUESDAY
                    "WEDNESDAY" -> Calendar.WEDNESDAY
                    "THURSDAY" -> Calendar.THURSDAY
                    "FRIDAY" -> Calendar.FRIDAY
                    "SATURDAY" -> Calendar.SATURDAY
                    else -> continue
                }
                cal.set(Calendar.DAY_OF_WEEK, dayOfWeek)
                if (cal.before(safetyNow)) {
                    cal.add(Calendar.WEEK_OF_YEAR, 1)
                }
                if (cal.timeInMillis < minRestartTime) {
                    minRestartTime = cal.timeInMillis
                }
            }
        }
        return minRestartTime
    }

    /**
     * ดึงคำสั่งรีสตาร์ทที่ผ่านการวิเคราะห์มาแล้ว
     */
    private fun parseScheduledCommand(original: String): ScheduledCommand? {
        var cmd = original.trim()
        
        // Parse time:X
        var timeBefore = 0
        val timeRegex = Regex("\\[time:(\\d+)\\]", RegexOption.IGNORE_CASE)
        val timeMatch = timeRegex.find(cmd)
        if (timeMatch != null) {
            timeBefore = timeMatch.groupValues[1].toIntOrNull() ?: 0
            cmd = cmd.replace(timeMatch.value, "").trim()
        }
        
        // Parse proxydelay:X
        var isProxyDelay = false
        var proxyDelaySec = 0
        val proxyDelayRegex = Regex("\\[proxydelay:(\\d+)\\]", RegexOption.IGNORE_CASE)
        val proxyDelayMatch = proxyDelayRegex.find(cmd)
        if (proxyDelayMatch != null) {
            isProxyDelay = true
            proxyDelaySec = proxyDelayMatch.groupValues[1].toIntOrNull() ?: 0
            cmd = cmd.replace(proxyDelayMatch.value, "").trim()
        }

        // Parse proxy
        var isProxy = false
        if (cmd.startsWith("[proxy]", ignoreCase = true)) {
            isProxy = true
            cmd = cmd.substring("[proxy]".length).trim()
        } else if (cmd.startsWith("[proxy:", ignoreCase = true)) {
            val proxyTimeRegex = Regex("\\[proxy:(\\d+)\\]", RegexOption.IGNORE_CASE)
            val proxyTimeMatch = proxyTimeRegex.find(cmd)
            if (proxyTimeMatch != null) {
                isProxy = true
                timeBefore = proxyTimeMatch.groupValues[1].toIntOrNull() ?: 0
                cmd = cmd.replace(proxyTimeMatch.value, "").trim()
            }
        }
        
        // Parse days
        val daysList = mutableListOf<String>()
        val daysOfWeek = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
        for (day in daysOfWeek) {
            val dayTag = "[$day]"
            if (cmd.contains(dayTag, ignoreCase = true)) {
                daysList.add(day)
                cmd = cmd.replace(Regex("\\[$day\\]", RegexOption.IGNORE_CASE), "").trim()
            }
        }
        
        return ScheduledCommand(
            original = original,
            timeBeforeRestart = timeBefore,
            days = daysList,
            isProxy = isProxy,
            isProxyDelay = isProxyDelay,
            proxyDelaySec = proxyDelaySec,
            command = cmd
        )
    }

    /**
     * ดึงคำสั่งเริ่มระบบใหม่ที่ผ่านการวิเคราะห์มาแล้ว
     */
    private fun parseStartupCommand(original: String): StartupCommand? {
        var cmd = original.trim()
        
        // Parse time:X หรือ proxy:X
        var delaySec = 0
        val timeRegex = Regex("\\[time:(\\d+)\\]", RegexOption.IGNORE_CASE)
        val timeMatch = timeRegex.find(cmd)
        if (timeMatch != null) {
            delaySec = timeMatch.groupValues[1].toIntOrNull() ?: 0
            cmd = cmd.replace(timeMatch.value, "").trim()
        }
        
        var isProxy = false
        if (cmd.startsWith("[proxy]", ignoreCase = true)) {
            isProxy = true
            cmd = cmd.substring("[proxy]".length).trim()
        } else {
            val proxyTimeRegex = Regex("\\[proxy:(\\d+)\\]", RegexOption.IGNORE_CASE)
            val proxyTimeMatch = proxyTimeRegex.find(cmd)
            if (proxyTimeMatch != null) {
                isProxy = true
                delaySec = proxyTimeMatch.groupValues[1].toIntOrNull() ?: 0
                cmd = cmd.replace(proxyTimeMatch.value, "").trim()
            }
        }
        
        // Parse days
        val daysList = mutableListOf<String>()
        val daysOfWeek = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
        for (day in daysOfWeek) {
            val dayTag = "[$day]"
            if (cmd.contains(dayTag, ignoreCase = true)) {
                daysList.add(day)
                cmd = cmd.replace(Regex("\\[$day\\]", RegexOption.IGNORE_CASE), "").trim()
            }
        }
        
        return StartupCommand(
            delaySec = delaySec,
            days = daysList,
            isProxy = isProxy,
            command = cmd
        )
    }

    data class ScheduledCommand(
        val original: String,
        val timeBeforeRestart: Int,
        val days: List<String>,
        val isProxy: Boolean,
        val isProxyDelay: Boolean,
        val proxyDelaySec: Int,
        val command: String
    )

    data class StartupCommand(
        val delaySec: Int,
        val days: List<String>,
        val isProxy: Boolean,
        val command: String
    )
}
