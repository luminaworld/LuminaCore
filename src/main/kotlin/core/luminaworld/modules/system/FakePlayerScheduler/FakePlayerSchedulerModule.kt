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
    private var namesFilePath = "plugins/LuminaCore/system/FakePlayerScheduler/name-list.yml"
    private var activeBotsStatePath = "plugins/LuminaCore/system/FakePlayerScheduler/active-bots.yml"

    // คลาสและตัวสั่งการคำสั่ง
    private var commandExecutor: FakePlayerSchedulerCommand? = null

    // สถานะ Runtime
    val botPool = CopyOnWriteArrayList<String>()
    val botRanks = ConcurrentHashMap<String, String>()
    val activeBots = CopyOnWriteArrayList<String>()
    val botSessions = ConcurrentHashMap<String, Long>() // เวลาเล่นเหลือ: { "name": logoutMs }
    val botJoinTimes = ConcurrentHashMap<String, String>() // เวลาเข้า: { "name": "HH:MM" }
    val botLogoutTimes = ConcurrentHashMap<String, String>() // เวลาออก: { "name": "HH:MM" }
    
    private var schedulerTask: ScheduledTask? = null
    var restartRushEnabled = true

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
            namesFilePath = it.getString("settings.names-filepath", "plugins/LuminaCore/system/FakePlayerScheduler/name-list.yml") ?: "plugins/LuminaCore/system/FakePlayerScheduler/name-list.yml"
            activeBotsStatePath = it.getString("settings.active-bots-filepath", "plugins/LuminaCore/system/FakePlayerScheduler/active-bots.yml") ?: "plugins/LuminaCore/system/FakePlayerScheduler/active-bots.yml"
            
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

    fun saveActiveBotsState(list: List<String>) {
        try {
            val file = File(activeBotsStatePath)
            if (!file.parentFile.exists()) {
                file.parentFile.mkdirs()
            }
            val yaml = YamlConfiguration()
            yaml.set("active", ArrayList(list))
            yaml.save(file)
        } catch (e: Exception) {
            plugin.logger.severe("[FP-Scheduler] Error saving active bots state: ${e.message}")
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
        val onlineList = getOnlineManagedBots()
        activeBots.clear()
        botSessions.clear()
        botJoinTimes.clear()
        botLogoutTimes.clear()
        
        val nowMs = System.currentTimeMillis()
        for (name in onlineList) {
            activeBots.add(name)
            val sessionTimeSeconds = randomInRange(10, 40) * 60
            botSessions[name] = nowMs + sessionTimeSeconds * 1000L
            botJoinTimes[name] = "ก่อนเปิดระบบ"
            val logoutTime = Date(nowMs + sessionTimeSeconds * 1000L)
            botLogoutTimes[name] = formatTime(logoutTime, false)
        }
        plugin.logger.info("§6[FP-Scheduler] §aตรวจพบและเชื่อมต่อบอทออนไลน์ในระบบแล้ว ${activeBots.size} ตัว")
        saveActiveBotsState(activeBots)
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

    private fun invokeSpawnBotReflect(name: String, loc: org.bukkit.Location, rank: String?) {
        try {
            val fppPlugin = plugin.server.pluginManager.getPlugin("FakePlayer") ?: return
            val getFppApi = fppPlugin.javaClass.getMethod("getFppApi")
            val fppApi = getFppApi.invoke(fppPlugin) ?: return

            val spawnBotMethod = fppApi.javaClass.getMethod(
                "spawnBot",
                org.bukkit.Location::class.java,
                org.bukkit.entity.Player::class.java,
                String::class.java
            )
            val botOpt = spawnBotMethod.invoke(fppApi, loc, null, name) as? java.util.Optional<*>
            if (botOpt != null && botOpt.isPresent) {
                val bot = botOpt.get()
                if (!rank.isNullOrEmpty()) {
                    plugin.server.globalRegionScheduler.runDelayed(plugin, { _ ->
                        try {
                            try {
                                val setLuckpermsGroup = bot.javaClass.getMethod("setLuckpermsGroup", String::class.java)
                                setLuckpermsGroup.invoke(bot, rank)

                                val getFakePlayerManager = fppPlugin.javaClass.getMethod("getFakePlayerManager")
                                val manager = getFakePlayerManager.invoke(fppPlugin)

                                val getHandle = bot.javaClass.getMethod("getHandle")
                                val handle = getHandle.invoke(bot)

                                if (manager != null && handle != null) {
                                    val persistMethod = manager.javaClass.getMethod("persistBotSettings", handle.javaClass)
                                    persistMethod.invoke(manager, handle)

                                    val refreshMethod = manager.javaClass.getMethod("refreshLpDisplayName", handle.javaClass)
                                    refreshMethod.invoke(manager, handle)
                                }
                            } catch (ex: Exception) {
                                // รันคำสั่งคอนโซลเป็นสำรอง (Fallback) หากใช้ Reflection API ไม่สำเร็จ
                                val rankCmd = rankCommandTemplate.replace("{name}", name).replace("{rank}", rank)
                                plugin.server.dispatchCommand(plugin.server.consoleSender, rankCmd)
                            }
                        } catch (ex: Exception) {
                            plugin.logger.severe("[FP-Scheduler] Error setting rank for $name: ${ex.message}")
                        }
                    }, 20L)
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("[FP-Scheduler] Reflection error spawning bot: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun invokeDespawnBotReflect(name: String): Boolean {
        return try {
            val fppPlugin = plugin.server.pluginManager.getPlugin("FakePlayer") ?: return false
            val getFppApi = fppPlugin.javaClass.getMethod("getFppApi")
            val fppApi = getFppApi.invoke(fppPlugin) ?: return false

            val despawnBotMethod = fppApi.javaClass.getMethod("despawnBot", String::class.java)
            despawnBotMethod.invoke(fppApi, name) as? Boolean ?: false
        } catch (e: Exception) {
            plugin.logger.severe("[FP-Scheduler] Reflection error despawning bot: ${e.message}")
            false
        }
    }

    fun spawnBot(name: String, durationSeconds: Int) {
        if (activeBots.contains(name)) return
        activeBots.add(name)
        
        val nowMs = System.currentTimeMillis()
        botSessions[name] = nowMs + durationSeconds * 1000L

        botJoinTimes[name] = formatTime(Date(), false)
        val logoutTime = Date(nowMs + durationSeconds * 1000L)
        botLogoutTimes[name] = formatTime(logoutTime, false)

        val hasPlugin = plugin.server.pluginManager.isPluginEnabled("FakePlayer")
        if (hasPlugin) {
            plugin.server.globalRegionScheduler.execute(plugin) {
                val loc = getSpawnLocation()
                if (loc != null) {
                    invokeSpawnBotReflect(name, loc, botRanks[name])
                } else {
                    plugin.logger.warning("§c[FakePlayerScheduler] ไม่สามารถระบุตำแหน่งสปอว์นสำหรับบอท $name ได้!")
                }
            }
        } else {
            plugin.server.globalRegionScheduler.execute(plugin) {
                val consoleSender = plugin.server.consoleSender
                val spawnCmd = spawnCommandTemplate.replace("{name}", name)
                plugin.server.dispatchCommand(consoleSender, spawnCmd)

                val rank = botRanks[name]
                if (!rank.isNullOrEmpty()) {
                    plugin.server.globalRegionScheduler.runDelayed(plugin, { _ ->
                        val rankCmd = rankCommandTemplate.replace("{name}", name).replace("{rank}", rank)
                        plugin.server.dispatchCommand(consoleSender, rankCmd)
                    }, 20L)
                }
            }
        }
    }

    fun despawnBot(name: String) {
        activeBots.remove(name)
        botSessions.remove(name)
        botJoinTimes.remove(name)
        botLogoutTimes.remove(name)

        val hasPlugin = plugin.server.pluginManager.isPluginEnabled("FakePlayer")
        if (hasPlugin) {
            plugin.server.globalRegionScheduler.execute(plugin) {
                invokeDespawnBotReflect(name)
            }
        } else {
            plugin.server.globalRegionScheduler.execute(plugin) {
                val despawnCmd = despawnCommandTemplate.replace("{name}", name)
                plugin.server.dispatchCommand(plugin.server.consoleSender, despawnCmd)
            }
        }
    }

    private fun getRandomAvailableBot(): String? {
        val available = botPool.filter { !activeBots.contains(it) }
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

    /**
     * ลบ/เตะบอททั้งหมดของโมดูลออกจากเซิร์ฟเวอร์
     */
    fun resetAllBots() {
        val hasPlugin = plugin.server.pluginManager.isPluginEnabled("FakePlayer")
        plugin.server.globalRegionScheduler.execute(plugin) {
            val consoleSender = plugin.server.consoleSender
            for (name in activeBots) {
                if (hasPlugin) {
                    invokeDespawnBotReflect(name)
                } else {
                    val despawnCmd = despawnCommandTemplate.replace("{name}", name)
                    plugin.server.dispatchCommand(consoleSender, despawnCmd)
                }
            }
            activeBots.clear()
            botSessions.clear()
            botJoinTimes.clear()
            botLogoutTimes.clear()
            saveActiveBotsState(emptyList())
        }
    }

    /**
     * วงรอบ Reconciliation เพื่อตรวจสอบและปรับสมดุลบอทให้ตรงตามความสมจริง
     */
    private fun runReconciliation() {
        if (!isEnabled) return

        updateDailySchedule()

        val target = getCurrentTarget()
        val onlineBots = getOnlineManagedBots()

        // บังคับเปลี่ยนสถานะ activeBots ตามผู้เล่นออนไลน์จริง เพื่อป้องกันปัญหาสถานะบอทค้าง
        activeBots.clear()
        activeBots.addAll(onlineBots)
        saveActiveBotsState(activeBots)

        val nowMs = System.currentTimeMillis()
        
        // เคลียร์บอทที่หลุดออกจาก Memory
        val sessionKeys = botSessions.keys()
        for (name in sessionKeys) {
            if (!activeBots.contains(name)) {
                botSessions.remove(name)
                botJoinTimes.remove(name)
                botLogoutTimes.remove(name)
            }
        }

        // บันทึกบอทใหม่ที่เข้ามาแยกโดยไม่ได้ผ่านวงรอบ
        for (name in activeBots) {
            if (!botSessions.containsKey(name)) {
                val duration = getRandomSessionDurationSeconds()
                botSessions[name] = nowMs + duration * 1000L
                botJoinTimes[name] = "สปอว์นแยก"
                val logoutTime = Date(nowMs + duration * 1000L)
                botLogoutTimes[name] = formatTime(logoutTime, false)
            }
        }

        // ค้นหาบอทที่หมดอายุขัยการเล่น
        val expiredBots = mutableListOf<String>()
        for (name in activeBots) {
            val logoutMs = botSessions[name]
            if (logoutMs != null) {
                if (logoutMs <= nowMs) {
                    expiredBots.add(name)
                }
            } else {
                botSessions[name] = nowMs + getRandomSessionDurationSeconds() * 1000L
            }
        }

        var secondsSinceLastLeave = (nowMs - lastLeaveTimeMs) / 1000.0
        val staggeredDelay = config?.getDouble("settings.throttling.staggered-leave-delay", 15.0) ?: 15.0

        // บอทหมดเวลาเล่น ค่อยๆ ทยอยนำออกจากเกม
        if (expiredBots.isNotEmpty()) {
            if (secondsSinceLastLeave >= staggeredDelay) {
                val nameToDespawn = expiredBots[0]
                despawnBot(nameToDespawn)
                plugin.logger.info("§6[FP-Scheduler] §eบอท $nameToDespawn หมดเวลาการเล่น ทำการเตะออก")
                lastLeaveTimeMs = System.currentTimeMillis()
                secondsSinceLastLeave = 0.0
            }
        }

        val currentCount = activeBots.size
        val secondsSinceLastJoin = (nowMs - lastJoinTimeMs) / 1000.0
        val secondsSinceLastSwap = (nowMs - lastSwapTimeMs) / 1000.0

        if (currentCount < target) {
            // ควบคุมจังหวะบอทเข้าเซิร์ฟเวอร์
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
            // ควบคุมจังหวะบอทล็อกเอาท์ออก (หากจำนวนจริงเกินเป้าหมาย)
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
            // การสลับเปลี่ยนผู้เล่น (Player Swap / Churn) กรณีบอทออนไลน์ครบเป้าหมายพอดี
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
