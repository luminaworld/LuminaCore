package core.luminaworld.modules.system.FakePlayerScheduler

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class FakePlayerSchedulerModule(plugin: LuminaCore) : LuminaModule(plugin, "FakePlayerScheduler"), Listener {

    // พาธของไฟล์
    private var namesFilePath = "plugins/LuminaCore/system/name-list.yml"
    private var activeBotsStatePath = "plugins/LuminaCore/system/active-bots.yml"

    // คลาสและตัวสั่งการคำสั่ง
    private var commandExecutor: FakePlayerSchedulerCommand? = null

    // สถานะ Runtime
    val botPool = CopyOnWriteArrayList<String>()
    val botRanks = ConcurrentHashMap<String, String>()
    val activeBots = CopyOnWriteArrayList<String>()
    val botSessions = ConcurrentHashMap<String, Long>() // เวลาเล่นเหลือ: { "name": logoutMs }
    val botJoinTimes = ConcurrentHashMap<String, String>() // เวลาเข้า: { "name": "HH:MM" }
    val botLogoutTimes = ConcurrentHashMap<String, String>() // เวลาออก: { "name": "HH:MM" }
    val botCooldowns = ConcurrentHashMap<String, Long>() // คูลดาวน์การล็อกอินของบอท: { "name": cooldownExpiryMs }
    
    private var schedulerTask: ScheduledTask? = null
    var restartRushEnabled = true
    var isSchedulerActive = false
        private set

    // ตัวแปรช่วงเวลาล็อกอินและล็อกเอาท์ (วินาที)
    var lastJoinTimeMs = 0L
    var lastLeaveTimeMs = 0L
    var lastSwapTimeMs = System.currentTimeMillis()
    var joinIntervalSeconds = 10L
    var leaveIntervalSeconds = 10L

    // ตารางสุ่มรายชั่วโมง
    var hourlyTargets = IntArray(24) { 0 }
    var dailySeed = ""
    private val hourlyMinTargets = IntArray(24) { 8 }
    private val hourlyMaxTargets = IntArray(24) { 12 }

    // ค่าความปั่นป่วนรายนาที (Noise)
    var currentNoise = 0
    private var lastNoiseUpdateMs = 0L

    // ตัวแปรคำสั่ง
    private var spawnCommandTemplate = "fp spawn 1 spawn --name {name} -1244 70 1668"
    private var rankCommandTemplate = "fp rank {name} {rank}"
    private var despawnCommandTemplate = "fp despawn {name}"

    // เครื่องมือจัดการ Format เวลา
    private val timeFormatSeconds = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)
    private val timeFormatMinutes = SimpleDateFormat("HH:mm", Locale.ENGLISH)

    override fun loadConfig() {
        super.loadConfig()
        config?.let {
            isSchedulerActive = it.getBoolean("settings.enabled", true)
            // บังคับให้ระบบมองว่าโมดูลถูก Enable เสมอเพื่อนำคำสั่งเข้าสู่เซิร์ฟเวอร์
            isEnabled = true

            namesFilePath = it.getString("settings.names-filepath", "plugins/LuminaCore/system/name-list.yml") ?: "plugins/LuminaCore/system/name-list.yml"
            activeBotsStatePath = it.getString("settings.active-bots-filepath", "plugins/LuminaCore/system/active-bots.yml") ?: "plugins/LuminaCore/system/active-bots.yml"
            
            spawnCommandTemplate = it.getString("settings.commands.spawn", "fp spawn 1 spawn --name {name} -1244 70 1668") ?: "fp spawn 1 spawn --name {name} -1244 70 1668"
            rankCommandTemplate = it.getString("settings.commands.rank", "fp rank {name} {rank}") ?: "fp rank {name} {rank}"
            despawnCommandTemplate = it.getString("settings.commands.despawn", "fp despawn {name}") ?: "fp despawn {name}"
            
            // โหลดชั่วโมงเป้าหมายราย Min/Max
            val hoursSection = it.getConfigurationSection("schedule.hours")
            if (hoursSection != null) {
                for (h in 0 until 24) {
                    val hourKey = h.toString()
                    if (hoursSection.contains(hourKey)) {
                        hourlyMinTargets[h] = hoursSection.getInt("$hourKey.min", 8)
                        hourlyMaxTargets[h] = hoursSection.getInt("$hourKey.max", 12)
                    }
                }
            }
        }
    }

    override fun onEnable() {
        // ลงทะเบียน Command Executor
        val cmd = FakePlayerSchedulerCommand(this@FakePlayerSchedulerModule)
        plugin.commandManager?.registerCommand(
            name = "fpscheduler",
            executor = cmd,
            tabCompleter = cmd,
            description = "คำสั่งจัดการระบบ FakePlayer Scheduler",
            usage = "/fpscheduler [status|reload|reset|rush]"
        )
        commandExecutor = cmd



        // โหลดข้อมูลบอทและกำหนดการเริ่มต้น
        loadYamlDatabase()
        updateDailySchedule()
        syncInitialBots()

        // รัน Reconciliation Loop ทุก 5 วินาที (100 Ticks)
        val intervalTicks = config?.getLong("settings.intervals.reconciliation-ticks", 100L) ?: 100L
        schedulerTask = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { _ ->
            runReconciliation()
        }, 40L, intervalTicks)

        plugin.logger.info("§6[FakePlayerScheduler] §aเปิดการทำงานของระบบสเก็ตดูลเลอร์บอทแล้ว")
    }

    override fun onDisable() {
        // ยกเลิกคำสั่ง
        plugin.commandManager?.unregisterCommand("fpscheduler")
        commandExecutor = null

        // ยกเลิกงานสเก็ตดูลเลอร์
        schedulerTask?.cancel()
        schedulerTask = null

        // ล้างสถานะ Runtime
        botPool.clear()
        botRanks.clear()
        activeBots.clear()
        botSessions.clear()
        botJoinTimes.clear()
        botLogoutTimes.clear()
        botCooldowns.clear()

        plugin.logger.info("§6[FakePlayerScheduler] §cปิดการทำงานของระบบเรียบร้อยแล้ว")
    }

    /**
     * ดึงค่า uptime เพื่อระบุโหมด Restart Rush (เซิร์ฟเวอร์เปิดใหม่ 10 นาทีแรก)
     */
    fun isRestartRushMode(): Boolean {
        if (!restartRushEnabled) return false
        return try {
            val uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().uptime
            uptimeMs < 600000L // 10 นาที
        } catch (e: Exception) {
            false
        }
    }

    fun saveSettings() {
        val namesFile = File(namesFilePath)
        if (namesFile.exists()) {
            val yaml = YamlConfiguration.loadConfiguration(namesFile)
            yaml.set("settings.restart-rush-enabled", restartRushEnabled)
            try {
                yaml.save(namesFile)
            } catch (e: Exception) {
                plugin.logger.severe("[FP-Scheduler] Cannot save settings to name-list.yml: ${e.message}")
            }
        }
    }

    /**
     * บันทึกข้อมูลบอทและกำหนดการลงใน name-list.yml
     */
    fun saveDatabase() {
        val namesFile = File(namesFilePath)
        val yaml = YamlConfiguration()
        yaml.set("settings.restart-rush-enabled", restartRushEnabled)
        val botsSection = yaml.createSection("bots")
        for (name in botPool) {
            botsSection.set(name, botRanks[name] ?: "rankf")
        }
        try {
            yaml.save(namesFile)
        } catch (e: Exception) {
            plugin.logger.severe("[FP-Scheduler] Cannot save database to name-list.yml: ${e.message}")
        }
    }

    /**
     * บันทึกสถานะบอทที่ออนไลน์และเวลาออกลงใน active-bots.yml
     */
    fun saveActiveBotsState() {
        try {
            val file = File(activeBotsStatePath)
            if (!file.parentFile.exists()) {
                file.parentFile.mkdirs()
            }
            val yaml = YamlConfiguration()
            val nowMs = System.currentTimeMillis()
            val activeSection = yaml.createSection("active")
            for (name in activeBots) {
                val botData = activeSection.createSection(name)
                botData.set("rank", botRanks[name] ?: "rankf")
                botData.set("join_time", botJoinTimes[name] ?: "ไม่ระบุ")
                botData.set("logout_time", botLogoutTimes[name] ?: "ไม่ระบุ")
                botData.set("logout_timestamp", botSessions[name] ?: 0L)
                val timeLeftSec = botSessions[name]?.let {
                    Math.max(0L, (it - nowMs) / 1000L)
                } ?: 0L
                val hours = timeLeftSec / 3600
                val minutes = (timeLeftSec % 3600) / 60
                val seconds = timeLeftSec % 60
                val timeStr = if (hours > 0) {
                    "$hours ชั่วโมง $minutes นาที $seconds วินาที"
                } else {
                    "$minutes นาที $seconds วินาที"
                }
                botData.set("time_left", timeStr)
            }
            yaml.save(file)
        } catch (e: Exception) {
            plugin.logger.severe("[FP-Scheduler] Error saving active bots state: ${e.message}")
        }
    }

    /**
     * โหลดสถานะบอทออนไลน์และเวลาสิ้นสุดเซสชันกลับคืนมาจาก active-bots.yml
     */
    fun loadActiveBotsState() {
        val file = File(activeBotsStatePath)
        if (!file.exists()) return

        try {
            val yaml = YamlConfiguration.loadConfiguration(file)
            val activeSection = yaml.getConfigurationSection("active") ?: return
            val nowMs = System.currentTimeMillis()

            activeBots.clear()
            botSessions.clear()
            botJoinTimes.clear()
            botLogoutTimes.clear()

            for (name in activeSection.getKeys(false)) {
                // ข้ามถ้าบอทนี้ไม่อยู่ในบอทพูล (เช่น โดนลบไปแล้ว)
                if (!botPool.contains(name)) continue

                val botData = activeSection.getConfigurationSection(name) ?: continue
                val logoutTimestamp = botData.getLong("logout_timestamp", 0L)

                if (logoutTimestamp > nowMs) {
                    activeBots.add(name)
                    botSessions[name] = logoutTimestamp
                    botJoinTimes[name] = botData.getString("join_time", "ไม่ระบุ") ?: "ไม่ระบุ"
                    botLogoutTimes[name] = botData.getString("logout_time", "ไม่ระบุ") ?: "ไม่ระบุ"
                }
            }
            plugin.logger.info("§6[FP-Scheduler] §aโหลดสถานะเซสชันบอทเดิมสำเร็จ: ${activeBots.size} ตัว")
        } catch (e: Exception) {
            plugin.logger.severe("[FP-Scheduler] Error loading active bots state: ${e.message}")
        }
    }

    /**
     * โหลดรายชื่อบอทจากฐานข้อมูล
     */
    fun loadYamlDatabase() {
        botPool.clear()
        botRanks.clear()

        val file = File(namesFilePath)
        // โหลดจากทรัพยากรเริ่มต้นใน JAR เสมอหากยังไม่มีไฟล์อยู่จริง
        if (!file.exists()) {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            try {
                val classPackage = javaClass.`package`.name.replace(".", "/")
                val resourcePath = "$classPackage/name-list.yml"
                val inputStream = javaClass.classLoader.getResourceAsStream(resourcePath)
                if (inputStream != null) {
                    java.nio.file.Files.copy(inputStream, file.toPath())
                    plugin.logger.info("§6[FP-Scheduler] §eสร้างไฟล์ข้อมูลบอทเริ่มต้นสำเร็จ: $namesFilePath")
                } else {
                    // Fallback หากหาไฟล์ใน JAR ไม่เจอจริงๆ
                    val yaml = YamlConfiguration()
                    yaml.set("settings.restart-rush-enabled", true)
                    yaml.set("bots.BR_Newexc", "rankf")
                    yaml.set("bots.BR_PloyKrub", "rankf")
                    yaml.set("bots.BR_PornthipZa", "rankf")
                    yaml.set("bots.DarkHawk4321", "rankf")
                    yaml.set("bots.Kratos9461", "rankf")
                    yaml.save(file)
                    plugin.logger.info("§6[FP-Scheduler] §eสร้างไฟล์ข้อมูลบอทดีฟอลต์ (Fallback) สำเร็จ: $namesFilePath")
                }
            } catch (e: Exception) {
                plugin.logger.severe("[FP-Scheduler] Could not create default name-list.yml: ${e.message}")
            }
        }

        if (file.exists()) {
            val yaml = YamlConfiguration.loadConfiguration(file)
            restartRushEnabled = yaml.getBoolean("settings.restart-rush-enabled", true)
            
            val section = yaml.getConfigurationSection("bots")
            if (section != null) {
                for (name in section.getKeys(false)) {
                    val rank = section.getString(name)
                    if (!botPool.contains(name)) {
                        botPool.add(name)
                    }
                    if (rank != null) {
                        botRanks[name] = rank
                    }
                }
            }
        }
        plugin.logger.info("§6[FP-Scheduler] §aโหลดรายชื่อบอททั้งหมดสำเร็จ: ${botPool.size} ตัว")
    }

    /**
     * สุ่มกำหนดเป้าหมายบอทรายชั่วโมงโดยใช้ Seed จากวันที่ปัจจุบัน (Deterministic Daily Seed)
     */
    fun updateDailySchedule() {
        val calendar = Calendar.getInstance()
        val seedStr = "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH) + 1}-${calendar.get(Calendar.DAY_OF_MONTH)}"
        if (seedStr == dailySeed && hourlyTargets.size == 24) {
            return
        }
        dailySeed = seedStr

        // สร้าง Hash Code แบบ Deterministic
        var hash = 0
        for (i in seedStr.indices) {
            hash = seedStr[i].code + ((hash shl 5) - hash)
        }

        hourlyTargets = IntArray(24)
        for (h in 0 until 24) {
            val minVal = hourlyMinTargets[h]
            val maxVal = hourlyMaxTargets[h]

            val rand = seededRandom(hash, h)
            var target = minVal + Math.floor(rand * (maxVal - minVal + 1)).toInt()

            if (botPool.isNotEmpty() && target > botPool.size) {
                target = botPool.size
            }
            hourlyTargets[h] = target
        }
        plugin.logger.info("§6[FP-Scheduler] §dตารางบอทรายชั่วโมงสำหรับวันนี้ ($dailySeed): ${hourlyTargets.joinToString(", ")}")
    }

    private fun seededRandom(hash: Int, index: Int): Double {
        val x = Math.sin((hash + index).toDouble()) * 10000.0
        return x - Math.floor(x)
    }

    /**
     * ดึงค่าเป้าหมายปัจจุบันแบบเฉลี่ยเชิงเส้น (Linear Interpolation) ตามนาที
     */
    fun getCurrentBaseTarget(): Int {
        val calendar = Calendar.getInstance()
        val h = calendar.get(Calendar.HOUR_OF_DAY)
        val m = calendar.get(Calendar.MINUTE)

        val nextH = (h + 1) % 24
        val progress = m / 60.0

        val currentTargetFloat = hourlyTargets[h] * (1 - progress) + hourlyTargets[nextH] * progress
        return Math.round(currentTargetFloat).toInt()
    }

    /**
     * อัปเดตความปั่นป่วนทางสถิติ (Noise) ทุกๆ ช่วงเวลาตั้งค่า
     */
    private fun updateNoise() {
        val noiseEnabled = config?.getBoolean("settings.noise.enabled", true) ?: true
        if (!noiseEnabled) {
            currentNoise = 0
            return
        }
        
        val minNoise = config?.getInt("settings.noise.min", -2) ?: -2
        val maxNoise = config?.getInt("settings.noise.max", 2) ?: 2
        val updateIntervalMin = config?.getInt("settings.noise.update-interval-minutes", 15) ?: 15

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastNoiseUpdateMs >= updateIntervalMin * 60 * 1000L || lastNoiseUpdateMs == 0L) {
            currentNoise = randomInRange(minNoise, maxNoise)
            lastNoiseUpdateMs = nowMs
        }
    }

    fun getCurrentTarget(): Int {
        val base = getCurrentBaseTarget()
        updateNoise()
        var target = base + currentNoise
        if (target < 0) target = 0
        if (botPool.isNotEmpty() && target > botPool.size) {
            target = botPool.size
        }
        return target
    }

    /**
     * ค้นหาบอทใน Pool ที่กำลังออนไลน์อยู่จริงในเซิร์ฟเวอร์
     */
    fun getOnlineManagedBots(): List<String> {
        val result = mutableListOf<String>()
        for (player in plugin.server.onlinePlayers) {
            val name = player.name
            if (botPool.contains(name)) {
                if (!result.contains(name)) {
                    result.add(name)
                }
            }
        }
        return result
    }

    /**
     * ซิงก์ข้อมูลช่วงต้นหลังโหลดโมดูล
     */
    fun syncInitialBots() {
        // โหลดข้อมูลเซสชันบอทเดิมที่เซฟไว้ก่อน
        loadActiveBotsState()

        val onlineList = getOnlineManagedBots()
        val nowMs = System.currentTimeMillis()

        // ตรวจสอบและบันทึกบอทที่ออนไลน์อยู่จริงในเซิร์ฟเวอร์
        for (name in onlineList) {
            if (activeBots.contains(name)) {
                // มีสถานะจากเซฟแล้ว และบอทออนไลน์อยู่จริง -> ข้ามไป
                continue
            }
            // บอทออนไลน์อยู่จริงแต่ไม่มีในไฟล์เซฟ -> เพิ่มเข้าไปและสุ่มเซสชันใหม่ให้
            activeBots.add(name)
            val sessionTimeSeconds = randomInRange(10, 40) * 60
            botSessions[name] = nowMs + sessionTimeSeconds * 1000L
            botJoinTimes[name] = "ตรวจพบขณะเปิดระบบ"
            val logoutTime = Date(nowMs + sessionTimeSeconds * 1000L)
            botLogoutTimes[name] = formatTime(logoutTime, false)
        }

        // สำหรับบอทที่มีในสถานะ activeBots (จากเซฟ) แต่ตอนนี้ยังไม่ได้ออนไลน์จริงในเซิร์ฟเวอร์
        // สั่งสปอว์นมันกลับเข้ามา!
        for (name in activeBots) {
            if (!onlineList.contains(name)) {
                val logoutMs = botSessions[name] ?: continue
                val durationSeconds = ((logoutMs - nowMs) / 1000).toInt()
                if (durationSeconds > 0) {
                    plugin.server.globalRegionScheduler.execute(plugin) {
                        val spawnCmd = spawnCommandTemplate.replace("{name}", name)
                        plugin.server.dispatchCommand(plugin.server.consoleSender, spawnCmd)
                        val rank = botRanks[name]
                        if (!rank.isNullOrEmpty()) {
                            plugin.server.globalRegionScheduler.runDelayed(plugin, { _ ->
                                val rankCmd = rankCommandTemplate.replace("{name}", name).replace("{rank}", rank)
                                plugin.server.dispatchCommand(plugin.server.consoleSender, rankCmd)
                            }, 20L)
                        }
                    }
                }
            }
        }

        plugin.logger.info("§6[FP-Scheduler] §aตรวจพบและเชื่อมต่อบอทออนไลน์ในระบบแล้ว ${activeBots.size} ตัว")
        saveActiveBotsState()
    }

    fun getSpawnLocation(): org.bukkit.Location? {
        val template = spawnCommandTemplate
        val tokens = template.split(" ")
        if (tokens.size >= 3) {
            val zStr = tokens[tokens.size - 1]
            val yStr = tokens[tokens.size - 2]
            val xStr = tokens[tokens.size - 3]
            val x = xStr.toDoubleOrNull()
            val y = yStr.toDoubleOrNull()
            val z = zStr.toDoubleOrNull()
            if (x != null && y != null && z != null) {
                var worldName = "world"
                if (tokens.size >= 4) {
                    val possibleWorld = tokens[tokens.size - 4]
                    if (plugin.server.getWorld(possibleWorld) != null) {
                        worldName = possibleWorld
                    }
                }
                val world = plugin.server.getWorld(worldName) ?: plugin.server.worlds.firstOrNull()
                return org.bukkit.Location(world, x, y, z)
            }
        }
        val world = plugin.server.worlds.firstOrNull() ?: return null
        return world.spawnLocation
    }

    fun spawnBot(name: String, durationSeconds: Int) {
        if (activeBots.contains(name)) return
        activeBots.add(name)
        
        val nowMs = System.currentTimeMillis()
        botSessions[name] = nowMs + durationSeconds * 1000L

        botJoinTimes[name] = formatTime(Date(), false)
        val logoutTime = Date(nowMs + durationSeconds * 1000L)
        botLogoutTimes[name] = formatTime(logoutTime, false)

        plugin.server.globalRegionScheduler.execute(plugin) {
            val spawnCmd = spawnCommandTemplate.replace("{name}", name)
            plugin.server.dispatchCommand(plugin.server.consoleSender, spawnCmd)
            val rank = botRanks[name]
            if (!rank.isNullOrEmpty()) {
                plugin.server.globalRegionScheduler.runDelayed(plugin, { _ ->
                    val rankCmd = rankCommandTemplate.replace("{name}", name).replace("{rank}", rank)
                    plugin.server.dispatchCommand(plugin.server.consoleSender, rankCmd)
                }, 20L)
            }
            saveActiveBotsState()
        }
    }

    fun despawnBot(name: String) {
        activeBots.remove(name)
        botSessions.remove(name)
        botJoinTimes.remove(name)
        botLogoutTimes.remove(name)

        // ตั้งเวลาคูลดาวน์ล็อกอินให้กับบอทตัวนี้
        val minMin = config?.getInt("settings.cooldown.min-minutes", 5) ?: 5
        val maxMin = config?.getInt("settings.cooldown.max-minutes", 15) ?: 15
        val cooldownMs = randomInRange(minMin, maxMin) * 60 * 1000L
        botCooldowns[name] = System.currentTimeMillis() + cooldownMs

        plugin.server.globalRegionScheduler.execute(plugin) {
            val cmd = despawnCommandTemplate.replace("{name}", name)
            plugin.server.dispatchCommand(plugin.server.consoleSender, cmd)
            saveActiveBotsState()
        }
    }

    private fun getRandomAvailableBot(): String? {
        val nowMs = System.currentTimeMillis()
        val available = botPool.filter { 
            !activeBots.contains(it) && (botCooldowns[it] ?: 0L) <= nowMs 
        }
        if (available.isEmpty()) return null
        return available.random()
    }

    fun getRandomSessionDurationSeconds(): Int {
        val roll = Math.random()
        val shortChance = config?.getDouble("settings.session-duration.short-chance", 0.40) ?: 0.40
        val mediumChance = config?.getDouble("settings.session-duration.medium-chance", 0.40) ?: 0.40
        
        val shortRange = config?.getIntegerList("settings.session-duration.short-range") ?: listOf(5, 15)
        val mediumRange = config?.getIntegerList("settings.session-duration.medium-range") ?: listOf(15, 45)
        val longRange = config?.getIntegerList("settings.session-duration.long-range") ?: listOf(45, 90)

        val minShort = shortRange.getOrNull(0) ?: 5
        val maxShort = shortRange.getOrNull(1) ?: 15
        val minMedium = mediumRange.getOrNull(0) ?: 15
        val maxMedium = mediumRange.getOrNull(1) ?: 45
        val minLong = longRange.getOrNull(0) ?: 45
        val maxLong = longRange.getOrNull(1) ?: 90

        val minutes = when {
            roll < shortChance -> randomInRange(minShort, maxShort)
            roll < (shortChance + mediumChance) -> randomInRange(minMedium, maxMedium)
            else -> randomInRange(minLong, maxLong)
        }

        var seconds = minutes * 60
        
        // ตัวคูณชั่วโมง Peak
        val baseTarget = getCurrentBaseTarget()
        if (baseTarget > 15) {
            val mult = config?.getDouble("settings.session-duration.peak-hour-multiplier", 1.5) ?: 1.5
            seconds = (seconds * mult).toInt()
        }

        return seconds
    }

    fun resetAllBots() {
        plugin.server.globalRegionScheduler.execute(plugin) {
            for (name in activeBots) {
                val cmd = despawnCommandTemplate.replace("{name}", name)
                plugin.server.dispatchCommand(plugin.server.consoleSender, cmd)
            }
            activeBots.clear()
            botSessions.clear()
            botJoinTimes.clear()
            botLogoutTimes.clear()
            saveActiveBotsState()
        }
    }

    /**
     * วงรอบ Reconciliation เพื่อตรวจสอบและปรับสมดุลบอทให้ตรงตามความสมจริง
     */
    private fun runReconciliation() {
        if (!isSchedulerActive) return

        updateDailySchedule()

        val target = getCurrentTarget()
        val onlineBots = getOnlineManagedBots()
        val nowMs = System.currentTimeMillis()

        // 1. ตรวจสอบและเตะบอทนอกตาราง/นอกระบบที่อาจหลุดค้างอยู่ในเซิร์ฟเวอร์
        for (player in plugin.server.onlinePlayers) {
            val name = player.name
            // ตรวจสอบว่าผู้เล่นนี้เป็นบอทที่เราจัดการหรือไม่ (มีประวัติหรือมีในระบบ)
            val isManagedBot = botPool.contains(name) || activeBots.contains(name) || botSessions.containsKey(name)
            if (isManagedBot) {
                // ถ้าไม่อยู่ใน botPool แล้ว (โดนลบออก) หรือไม่ได้อยู่ในรายการ activeBots (ไม่ควรจะออนไลน์ในขณะนี้)
                if (!botPool.contains(name) || !activeBots.contains(name)) {
                    plugin.server.globalRegionScheduler.execute(plugin) {
                        val cmd = despawnCommandTemplate.replace("{name}", name)
                        plugin.server.dispatchCommand(plugin.server.consoleSender, cmd)
                    }
                    // ทำการล้างออกจากระบบด้วยเผื่อตกค้าง
                    activeBots.remove(name)
                    botSessions.remove(name)
                    botJoinTimes.remove(name)
                    botLogoutTimes.remove(name)
                    saveActiveBotsState()
                    plugin.logger.info("§6[FP-Scheduler] §cเตะบอทนอกตารางหรือบอทที่ถูกลบ: $name")
                }
            }
        }

        // 2. ตรวจสอบบอทใน activeBots
        val activeSnapshot = ArrayList(activeBots)
        for (name in activeSnapshot) {
            // หากบอทไม่อยู่ใน botPool แล้ว (อาจโดนลบออกผ่านคำสั่ง)
            if (!botPool.contains(name)) {
                despawnBot(name)
                continue
            }

            val logoutMs = botSessions[name]
            if (logoutMs == null) {
                // ป้องกันค่าเซสชันหาย
                val duration = getRandomSessionDurationSeconds()
                botSessions[name] = nowMs + duration * 1000L
                botJoinTimes[name] = "แก้ไขเซสชัน"
                botLogoutTimes[name] = formatTime(Date(nowMs + duration * 1000L), false)
                saveActiveBotsState()
                continue
            }

            // ตรวจสอบการหมดเวลาเล่น
            if (logoutMs <= nowMs) {
                val secondsSinceLastLeave = (nowMs - lastLeaveTimeMs) / 1000.0
                val staggeredDelay = config?.getDouble("settings.throttling.staggered-leave-delay", 15.0) ?: 15.0
                if (secondsSinceLastLeave >= staggeredDelay) {
                    despawnBot(name)
                    plugin.logger.info("§6[FP-Scheduler] §eบอท $name หมดเวลาการเล่น ทำการเตะออก")
                    lastLeaveTimeMs = System.currentTimeMillis()
                }
                continue
            }

            // หากบอทควรจะออนไลน์แต่ไม่อยู่ในเซิร์ฟเวอร์จริง (หลุด หรือเปิดเซิร์ฟใหม่) -> สปอว์นกลับมา
            if (!onlineBots.contains(name)) {
                plugin.server.globalRegionScheduler.execute(plugin) {
                    val spawnCmd = spawnCommandTemplate.replace("{name}", name)
                    plugin.server.dispatchCommand(plugin.server.consoleSender, spawnCmd)
                    val rank = botRanks[name]
                    if (!rank.isNullOrEmpty()) {
                        plugin.server.globalRegionScheduler.runDelayed(plugin, { _ ->
                            val rankCmd = rankCommandTemplate.replace("{name}", name).replace("{rank}", rank)
                            plugin.server.dispatchCommand(plugin.server.consoleSender, rankCmd)
                        }, 20L)
                    }
                }
            }
        }

        // 3. ปรับสมดุลบอทตามเป้าหมาย (Target)
        val currentCount = activeBots.size
        val secondsSinceLastJoin = (nowMs - lastJoinTimeMs) / 1000.0
        val secondsSinceLastLeave = (nowMs - lastLeaveTimeMs) / 1000.0
        val secondsSinceLastSwap = (nowMs - lastSwapTimeMs) / 1000.0

        if (currentCount < target) {
            // บอทต่ำกว่าเป้าหมาย -> สุ่มเพิ่มเข้ามา
            if (secondsSinceLastJoin >= joinIntervalSeconds) {
                val name = getRandomAvailableBot()
                if (name != null) {
                    val playTime = getRandomSessionDurationSeconds()
                    spawnBot(name, playTime)
                    plugin.logger.info("§6[FP-Scheduler] §aบอท $name ล็อกอินเข้าเซิร์ฟเวอร์ (เวลาเล่น: ${playTime / 60} นาที)")

                    lastJoinTimeMs = System.currentTimeMillis()

                    val isRush = isRestartRushMode()
                    if (isRush) {
                        val rushJoinRange = config?.getIntegerList("settings.throttling.restart-rush.join-delay-range") ?: listOf(10, 30)
                        joinIntervalSeconds = randomInRange(rushJoinRange.getOrNull(0) ?: 10, rushJoinRange.getOrNull(1) ?: 30).toLong()
                    } else {
                        val normalJoinRange = config?.getIntegerList("settings.throttling.normal.join-delay-range") ?: listOf(60, 180)
                        joinIntervalSeconds = randomInRange(normalJoinRange.getOrNull(0) ?: 60, normalJoinRange.getOrNull(1) ?: 180).toLong()
                    }
                }
            }
        } else if (currentCount > target) {
            // บอทเกินกว่าเป้าหมาย -> ค่อยๆ ทยอยเอาออก
            if (secondsSinceLastLeave >= leaveIntervalSeconds) {
                if (activeBots.isNotEmpty()) {
                    val name = activeBots.random()
                    despawnBot(name)
                    plugin.logger.info("§6[FP-Scheduler] §eบอท $name ล็อกเอาท์ออก (ปรับสมดุลตามช่วงเวลา)")

                    lastLeaveTimeMs = System.currentTimeMillis()

                    val isRush = isRestartRushMode()
                    if (isRush) {
                        val rushLeaveRange = config?.getIntegerList("settings.throttling.restart-rush.leave-delay-range") ?: listOf(10, 20)
                        leaveIntervalSeconds = randomInRange(rushLeaveRange.getOrNull(0) ?: 10, rushLeaveRange.getOrNull(1) ?: 20).toLong()
                    } else {
                        val normalLeaveRange = config?.getIntegerList("settings.throttling.normal.leave-delay-range") ?: listOf(60, 120)
                        leaveIntervalSeconds = randomInRange(normalLeaveRange.getOrNull(0) ?: 60, normalLeaveRange.getOrNull(1) ?: 120).toLong()
                    }
                }
            }
        } else {
            // บอทพอดีเป้าหมาย -> สลับเปลี่ยนผู้เล่น Churn / Swap
            val churnCheckSec = config?.getLong("settings.churn.check-interval-seconds", 60L) ?: 60L
            if (secondsSinceLastSwap >= churnCheckSec) {
                lastSwapTimeMs = System.currentTimeMillis()
                val swapChance = config?.getDouble("settings.churn.swap-chance", 0.15) ?: 0.15
                if (Math.random() < swapChance && activeBots.isNotEmpty()) {
                    val nameToLeave = activeBots.random()
                    val nameToJoin = getRandomAvailableBot()
                    if (nameToLeave != null && nameToJoin != null) {
                        despawnBot(nameToLeave)
                        plugin.logger.info("§6[FP-Scheduler] §eบอท $nameToLeave ออกจากเกม (Player Swap)")

                        lastJoinTimeMs = System.currentTimeMillis()
                        val rejoinRange = config?.getIntegerList("settings.churn.rejoin-delay-range") ?: listOf(15, 40)
                        joinIntervalSeconds = randomInRange(rejoinRange.getOrNull(0) ?: 15, rejoinRange.getOrNull(1) ?: 40).toLong()
                    }
                }
            }
        }
    }

    private fun randomInRange(min: Int, max: Int): Int {
        val actualMin = Math.min(min, max)
        val actualMax = Math.max(min, max)
        return if (actualMin == actualMax) actualMin else (actualMin..actualMax).random()
    }

    fun formatTime(date: Date, includeSeconds: Boolean): String {
        return if (includeSeconds) {
            timeFormatSeconds.format(date)
        } else {
            timeFormatMinutes.format(date)
        }
    }
}
