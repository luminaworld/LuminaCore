package core.luminaworld.modules.minigames.CaptureTheFlag

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CaptureTheFlagModule(plugin: LuminaCore) : LuminaModule(plugin, "CaptureTheFlag") {

    val zones = ConcurrentHashMap<String, CaptureZone>()
    val playerCurrentZone = ConcurrentHashMap<UUID, String>()

    // แคชการเลือกขอบเขตตำแหน่งของผู้เล่นสำหรับการสร้างโซน
    val selectionPos1 = ConcurrentHashMap<UUID, Location>()
    val selectionPos2 = ConcurrentHashMap<UUID, Location>()

    fun getZone(name: String): CaptureZone? {
        return zones[name] ?: zones.values.find { it.name.equals(name, ignoreCase = true) }
    }

    private var tickTask: ScheduledTask? = null
    private var playerLocationTask: ScheduledTask? = null
    private var particleTask: ScheduledTask? = null

    val mainConfigFile: File
        get() = File(File(plugin.dataFolder, "minigames/CaptureTheFlag"), "CaptureTheFlag.yml")
    val messagesFile: File
        get() = File(File(plugin.dataFolder, "minigames/CaptureTheFlag"), "messages.yml")
    var messagesConfig: YamlConfiguration? = null

    override fun loadConfig() {
        try {
            val moduleFolder = File(plugin.dataFolder, "minigames/CaptureTheFlag")
            if (!moduleFolder.exists()) {
                moduleFolder.mkdirs()
            }

            // โหลด CaptureTheFlag.yml
            val mainConfigFile = File(moduleFolder, "CaptureTheFlag.yml")
            val resourcePath = "core/luminaworld/modules/minigames/CaptureTheFlag/CaptureTheFlag.yml"

            if (!mainConfigFile.exists()) {
                val inputStream = javaClass.classLoader.getResourceAsStream(resourcePath)
                if (inputStream != null) {
                    val parentFile = mainConfigFile.parentFile
                    if (!parentFile.exists()) {
                        parentFile.mkdirs()
                    }
                    Files.copy(inputStream, mainConfigFile.toPath())
                } else {
                    plugin.logger.warning("Could not find default configuration resource for module: $name")
                }
            } else {
                plugin.updateConfig(mainConfigFile, resourcePath)
            }

            if (mainConfigFile.exists()) {
                config = YamlConfiguration.loadConfiguration(mainConfigFile)
                isEnabled = config?.getBoolean("settings.enabled", true) ?: true
            } else {
                isEnabled = false
            }

            // โหลด messages.yml
            val msgFile = File(moduleFolder, "messages.yml")
            val msgResourcePath = "core/luminaworld/modules/minigames/CaptureTheFlag/messages.yml"
            if (!msgFile.exists()) {
                val inputStream = javaClass.classLoader.getResourceAsStream(msgResourcePath)
                if (inputStream != null) {
                    val parentFile = msgFile.parentFile
                    if (!parentFile.exists()) {
                        parentFile.mkdirs()
                    }
                    Files.copy(inputStream, msgFile.toPath())
                } else {
                    plugin.logger.warning("Could not find default messages resource for CTF")
                }
            } else {
                plugin.updateConfig(msgFile, msgResourcePath)
            }

            if (msgFile.exists()) {
                messagesConfig = YamlConfiguration.loadConfiguration(msgFile)
            }
        } catch (e: Exception) {
            plugin.logger.severe("Error loading config for module $name: ${e.message}")
            isEnabled = false
        }
    }

    override fun onEnable() {
        refreshConfigCache()

        // ลงทะเบียนคำสั่ง
        val commandExecutor = CaptureTheFlagCommand(this)
        plugin.commandManager?.registerCommand(
            name = "ctf",
            executor = commandExecutor,
            tabCompleter = commandExecutor,
            description = "ระบบจัดการยึดพื้นที่ (Capture The Flag)",
            usage = "/ctf",
            aliases = listOf("capturetheflag", "zonecapture")
        )

        // เริ่มตัวตรวจสอบตำแหน่งผู้เล่นถี่พิเศษ เพื่อการดักจับเดินเข้าออกเรียลไทม์
        startPlayerLocationScheduler()

        // เริ่มตัวนับเวลา (Ticking Engine) ทุกๆ 1 วินาที
        startTickScheduler()

        // เริ่มระบบแสดงพาทิเคิลขอบเขตโซน
        startParticleScheduler()

        // ลงทะเบียนแท็กตัวแปรกับระบบ PlaceholderAPI ส่วนกลาง
        core.luminaworld.placeholder.LuminaPlaceholderManager.register("ctf") { _, params ->
            val parts = params.split("_")
            if (parts.size < 2) null
            else {
                val type = parts[0].lowercase()
                val zoneName = parts.drop(1).joinToString("_")
                val zone = getZone(zoneName)
                if (zone == null) ""
                else {
                    when (type) {
                        "status" -> {
                            when (zone.status) {
                                CaptureZone.ZoneStatus.IDLE -> {
                                    config?.getString("placeholder.status.idle", "Idle") ?: "Idle"
                                }
                                CaptureZone.ZoneStatus.CAPTURING -> {
                                    val template = config?.getString("placeholder.status.capturing", "Capturing") ?: "Capturing"
                                    val remaining = zone.remainingCaptureSeconds
                                    val elapsed = zone.captureTimeSeconds - remaining
                                    template
                                        .replace("%remaining%", formatPlaceholderTime(remaining, "capturing"))
                                        .replace("%elapsed%", formatPlaceholderTime(elapsed, "capturing"))
                                        .replace("%total%", formatPlaceholderTime(zone.captureTimeSeconds, "capturing"))
                                        .replace("%time%", remaining.toString())
                                }
                                CaptureZone.ZoneStatus.GRACE_PERIOD -> {
                                    val template = config?.getString("placeholder.status.grace_period", "Grace Period") ?: "Grace Period"
                                    val remaining = zone.remainingGraceSeconds
                                    template
                                        .replace("%remaining%", formatPlaceholderTime(remaining, "grace_period"))
                                        .replace("%total%", formatPlaceholderTime(zone.gracePeriodSeconds, "grace_period"))
                                        .replace("%time%", remaining.toString())
                                }
                                CaptureZone.ZoneStatus.COOLDOWN -> {
                                    val template = config?.getString("placeholder.status.cooldown", "Cooldown") ?: "Cooldown"
                                    val remaining = zone.remainingCooldownSeconds
                                    template
                                        .replace("%cooldown%", formatPlaceholderTime(remaining, "cooldown"))
                                        .replace("%total%", formatPlaceholderTime(zone.cooldownTimeSeconds, "cooldown"))
                                        .replace("%time%", formatPlaceholderTime(remaining, "cooldown"))
                                }
                            }
                        }
                        "occupant" -> {
                            if (zone.occupantUuid == null || zone.occupantName == "None" || zone.occupantName.isEmpty()) {
                                config?.getString("placeholder.occupant.none", "None") ?: "None"
                            } else {
                                zone.occupantName
                            }
                        }
                        "time" -> {
                            if (zone.status == CaptureZone.ZoneStatus.CAPTURING) {
                                zone.remainingCaptureSeconds.toString()
                            } else if (zone.status == CaptureZone.ZoneStatus.GRACE_PERIOD) {
                                zone.remainingGraceSeconds.toString()
                            } else if (zone.status == CaptureZone.ZoneStatus.COOLDOWN) {
                                zone.remainingCooldownSeconds.toString()
                            } else {
                                "0"
                            }
                        }
                        "cooldown" -> {
                            if (zone.status == CaptureZone.ZoneStatus.COOLDOWN) {
                                formatCooldownTime(zone.remainingCooldownSeconds)
                            } else {
                                "0"
                            }
                        }
                        else -> null
                    }
                }
            }
        }

        // ลงทะเบียนเมนูจัดการ GUI
        plugin.server.pluginManager.registerEvents(CaptureTheFlagGUI(this), plugin)

        plugin.logger.info("§6[CaptureTheFlag] §aเปิดใช้งานระบบยึดพื้นที่สำเร็จแล้ว")
    }

    override fun onDisable() {
        // เซฟสถานะปัจจุบันของโซนทั้งหมดลงดิสก์แบบ Synchronous ก่อนปิด/รีโหลด
        val zonesFolder = File(File(plugin.dataFolder, "minigames/CaptureTheFlag"), "zones")
        if (!zonesFolder.exists()) {
            zonesFolder.mkdirs()
        }
        for (zone in zones.values) {
            val zoneFile = File(zonesFolder, "${zone.name}.yml")
            val zoneConfig = YamlConfiguration()
            saveZoneToConfigInMemory(zone, zoneConfig)
            try {
                zoneConfig.save(zoneFile)
            } catch (e: Exception) {
                plugin.logger.severe("[CaptureTheFlag] ไม่สามารถบันทึกไฟล์สนาม ${zone.name} ในจังหวะปิดปลั๊กอินได้: ${e.message}")
            }
        }

        tickTask?.cancel()
        tickTask = null

        playerLocationTask?.cancel()
        playerLocationTask = null

        particleTask?.cancel()
        particleTask = null

        // ยกเลิกการลงทะเบียนตัวแปรระบบกลาง
        core.luminaworld.placeholder.LuminaPlaceholderManager.unregister("ctf")

        plugin.commandManager?.unregisterCommand("ctf")
        zones.clear()
        playerCurrentZone.clear()
        selectionPos1.clear()
        selectionPos2.clear()
    }

    /**
     * โหลดข้อมูลโซนทั้งหมดจากโฟลเดอร์ zones เข้ามายังแคชขณะทำงาน
     */
    fun refreshConfigCache() {
        zones.clear()

        val zonesFolder = File(File(plugin.dataFolder, "minigames/CaptureTheFlag"), "zones")
        if (!zonesFolder.exists()) {
            zonesFolder.mkdirs()
            return
        }

        val defaultCap = config?.getInt("settings.default-capture-time", 180) ?: 180
        val defaultGrace = config?.getInt("settings.default-grace-period", 10) ?: 10
        val defaultCooldown = config?.getInt("settings.default-cooldown-time", 1800) ?: 1800

        // โหลดข้อมูลพาทิเคิลสากล
        val globPartEnable = config?.getBoolean("settings.particles.enabled", true) ?: true
        val globPartType = config?.getString("settings.particles.type", "DUST") ?: "DUST"
        val globPartDensity = config?.getDouble("settings.particles.density", 0.5) ?: 0.5
        val globPartSize = config?.getDouble("settings.particles.size", 1.0) ?: 1.0
        val globPartColorHex = config?.getString("settings.particles.color", "#00FF00") ?: "#00FF00"
        val globPartHeightOffset = config?.getDouble("settings.particles.height-offset", 0.1) ?: 0.1
        val globPartSpawnInterval = config?.getDouble("settings.particles.spawn-interval", 0.0) ?: 0.0
        val globPartDuration = config?.getDouble("settings.particles.duration", 0.0) ?: 0.0
        val globHideCool = config?.getBoolean("settings.particles.hide-during-cooldown", false) ?: false
        val globCoolType = config?.getString("settings.particles.cooldown-particle.type", "DUST") ?: "DUST"
        val globCoolSize = config?.getDouble("settings.particles.cooldown-particle.size", 0.8) ?: 0.8
        val globCoolColorHex = config?.getString("settings.particles.cooldown-particle.color", "#FF0000") ?: "#FF0000"

        val files = zonesFolder.listFiles { _, name -> name.lowercase().endsWith(".yml") } ?: return
        for (file in files) {
            val zoneName = file.nameWithoutExtension
            val zoneConfig = YamlConfiguration.loadConfiguration(file)

            val world = zoneConfig.getString("world") ?: continue
            val p1X = zoneConfig.getInt("pos1.x")
            val p1Y = zoneConfig.getInt("pos1.y")
            val p1Z = zoneConfig.getInt("pos1.z")
            val p2X = zoneConfig.getInt("pos2.x")
            val p2Y = zoneConfig.getInt("pos2.y")
            val p2Z = zoneConfig.getInt("pos2.z")

            val captureTime = zoneConfig.getInt("capture-time", defaultCap)
            val gracePeriod = zoneConfig.getInt("grace-period", defaultGrace)
            val cooldownTime = zoneConfig.getInt("cooldown-time", defaultCooldown)
            val rewards = zoneConfig.getStringList("rewards.commands")

            // หาค่าขอบเขตพิกัด
            val minX = minOf(p1X, p2X)
            val maxX = maxOf(p1X, p2X)
            val minY = minOf(p1Y, p2Y)
            val maxY = maxOf(p1Y, p2Y)
            val minZ = minOf(p1Z, p2Z)
            val maxZ = maxOf(p1Z, p2Z)
            val displayY = zoneConfig.getDouble("display-y", minY.toDouble())

            val zone = CaptureZone(
                name = zoneName,
                worldName = world,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                minZ = minZ,
                maxZ = maxZ,
                captureTimeSeconds = captureTime,
                gracePeriodSeconds = gracePeriod,
                cooldownTimeSeconds = cooldownTime,
                rewardCommands = rewards,
                displayY = displayY
            )

            // โหลดสถานะการทำงานปัจจุบัน (Runtime State)
            val statusStr = zoneConfig.getString("state.status", "IDLE") ?: "IDLE"
            zone.status = try {
                CaptureZone.ZoneStatus.valueOf(statusStr.uppercase())
            } catch (e: Exception) {
                CaptureZone.ZoneStatus.IDLE
            }
            zone.remainingCooldownSeconds = zoneConfig.getInt("state.remaining-cooldown", 0)
            zone.remainingCaptureSeconds = zoneConfig.getInt("state.remaining-capture", captureTime)
            zone.remainingGraceSeconds = zoneConfig.getInt("state.remaining-grace", 0)

            val uuidStr = zoneConfig.getString("state.occupant-uuid")
            zone.occupantUuid = if (!uuidStr.isNullOrEmpty()) try { UUID.fromString(uuidStr) } catch(e: Exception) { null } else null
            zone.occupantName = zoneConfig.getString("state.occupant-name", "None") ?: "None"

            zone.particleEnabled = zoneConfig.getBoolean("particles.enabled", globPartEnable)
            zone.particleType = zoneConfig.getString("particles.type", globPartType) ?: globPartType
            zone.particleDensity = zoneConfig.getDouble("particles.density", globPartDensity)
            zone.particleSize = zoneConfig.getDouble("particles.size", globPartSize).toFloat()
            zone.particleColorHex = zoneConfig.getString("particles.color", globPartColorHex) ?: globPartColorHex
            zone.particleHeightOffset = zoneConfig.getDouble("particles.height-offset", globPartHeightOffset)
            zone.particleSpawnInterval = zoneConfig.getDouble("particles.spawn-interval", globPartSpawnInterval)
            zone.particleDuration = zoneConfig.getDouble("particles.duration", globPartDuration)

            zone.hideDuringCooldown = zoneConfig.getBoolean("particles.hide-during-cooldown", globHideCool)
            zone.cooldownParticleType = zoneConfig.getString("particles.cooldown-particle.type", globCoolType) ?: globCoolType
            zone.cooldownParticleSize = zoneConfig.getDouble("particles.cooldown-particle.size", globCoolSize).toFloat()
            zone.cooldownParticleColorHex = zoneConfig.getString("particles.cooldown-particle.color", globCoolColorHex) ?: globCoolColorHex

            zones[zoneName] = zone
        }
    }

    /**
     * เขียนข้อมูลและสถานะของพื้นที่ยึดลงใน config ในแรม
     */
    fun saveZoneToConfigInMemory(zone: CaptureZone, zoneConfig: YamlConfiguration) {
        zoneConfig.set("world", zone.worldName)
        zoneConfig.set("pos1.x", zone.minX)
        zoneConfig.set("pos1.y", zone.minY)
        zoneConfig.set("pos1.z", zone.minZ)
        zoneConfig.set("pos2.x", zone.maxX)
        zoneConfig.set("pos2.y", zone.maxY)
        zoneConfig.set("pos2.z", zone.maxZ)
        zoneConfig.set("display-y", zone.displayY)
        zoneConfig.set("capture-time", zone.captureTimeSeconds)
        zoneConfig.set("grace-period", zone.gracePeriodSeconds)
        zoneConfig.set("cooldown-time", zone.cooldownTimeSeconds)
        zoneConfig.set("rewards.commands", zone.rewardCommands)

        // ข้อมูลพาทิเคิล
        zoneConfig.set("particles.enabled", zone.particleEnabled)
        zoneConfig.set("particles.type", zone.particleType)
        zoneConfig.set("particles.density", zone.particleDensity)
        zoneConfig.set("particles.size", zone.particleSize)

        // ล้างโครงสร้างสี RGB เดิมก่อน เพื่อความปลอดภัย
        zoneConfig.set("particles.color", null)
        zoneConfig.set("particles.cooldown-particle.color", null)

        zoneConfig.set("particles.color", zone.particleColorHex)
        zoneConfig.set("particles.height-offset", zone.particleHeightOffset)
        zoneConfig.set("particles.spawn-interval", zone.particleSpawnInterval)
        zoneConfig.set("particles.duration", zone.particleDuration)
        zoneConfig.set("particles.hide-during-cooldown", zone.hideDuringCooldown)
        zoneConfig.set("particles.cooldown-particle.type", zone.cooldownParticleType)
        zoneConfig.set("particles.cooldown-particle.size", zone.cooldownParticleSize)
        zoneConfig.set("particles.cooldown-particle.color", zone.cooldownParticleColorHex)

        // บันทึกสถานะการทำงานปัจจุบัน (Runtime State)
        zoneConfig.set("state.status", zone.status.name)
        zoneConfig.set("state.remaining-cooldown", zone.remainingCooldownSeconds)
        zoneConfig.set("state.remaining-capture", zone.remainingCaptureSeconds)
        zoneConfig.set("state.remaining-grace", zone.remainingGraceSeconds)
        zoneConfig.set("state.occupant-uuid", zone.occupantUuid?.toString())
        zoneConfig.set("state.occupant-name", zone.occupantName)
    }

    /**
     * บันทึกพื้นที่ยึดลงในไฟล์ตั้งค่าแยกแบบ Asynchronous
     */
    fun saveZoneToConfig(zone: CaptureZone) {
        val zonesFolder = File(File(plugin.dataFolder, "minigames/CaptureTheFlag"), "zones")
        if (!zonesFolder.exists()) {
            zonesFolder.mkdirs()
        }
        val zoneFile = File(zonesFolder, "${zone.name}.yml")
        val zoneConfig = YamlConfiguration()
        saveZoneToConfigInMemory(zone, zoneConfig)

        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                zoneConfig.save(zoneFile)
            } catch (e: Exception) {
                plugin.logger.severe("[CaptureTheFlag] ไม่สามารถบันทึกไฟล์สนาม ${zone.name} ได้: ${e.message}")
            }
        }
    }

    /**
     * ลบไฟล์ข้อมูลพื้นที่ยึดออกจากดิสก์
     */
    fun removeZoneFromConfig(zoneName: String) {
        val zonesFolder = File(File(plugin.dataFolder, "minigames/CaptureTheFlag"), "zones")
        val zoneFile = File(zonesFolder, "$zoneName.yml")
        if (zoneFile.exists()) {
            zoneFile.delete()
        }
    }

    private fun saveConfigAsync() {
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                // คัดลอกคอมเมนต์ทั้งหมดจากดีฟอลต์คอนฟิกใน JAR มารวมเพื่อรักษาคอมเมนต์ไว้ก่อนเซฟ
                try {
                    val classPackage = javaClass.`package`.name.replace(".", "/")
                    val resourcePath = "$classPackage/CaptureTheFlag.yml"
                    val inputStream = javaClass.classLoader.getResourceAsStream(resourcePath)
                    if (inputStream != null) {
                        java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8).use { reader ->
                            val defaultConfig = YamlConfiguration.loadConfiguration(reader)
                            config?.setComments("", defaultConfig.getComments(""))
                            defaultConfig.getKeys(true).toList().forEach { key ->
                                config?.setComments(key, defaultConfig.getComments(key))
                                config?.setInlineComments(key, defaultConfig.getInlineComments(key))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // ปลอดภัยเผื่อเกิดข้อผิดพลาดบนเซิร์ฟเวอร์รุ่นเก่า
                }

                config?.save(mainConfigFile)
            } catch (e: Exception) {
                plugin.logger.severe("[CaptureTheFlag] ไม่สามารถบันทึกไฟล์คอนฟิกได้: ${e.message}")
            }
        }
    }

    /**
     * ตรวจสอบตำแหน่งพิกัดของผู้เล่นทุกคนว่าอยู่ในโซนยึดครองใดหรือไม่ ถี่พิเศษเพื่อตอบสนองทันที
     */
    private fun startPlayerLocationScheduler() {
        playerLocationTask = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { _ ->
            if (!isEnabled) return@runAtFixedRate

            for (player in plugin.server.onlinePlayers) {
                player.scheduler.execute(plugin, {
                    if (!player.isOnline) return@execute
                    val loc = player.location
                    var activeZoneName: String? = null
                    for (zone in zones.values) {
                        if (loc.world?.name == zone.worldName && zone.contains(loc)) {
                            activeZoneName = zone.name
                            break
                        }
                    }

                    val previousZoneName = playerCurrentZone[player.uniqueId]
                    if (activeZoneName != previousZoneName) {
                        if (activeZoneName != null) {
                            playerCurrentZone[player.uniqueId] = activeZoneName
                        } else {
                            playerCurrentZone.remove(player.uniqueId)
                        }
                    }
                }, null, 0L)
            }
        }, 2L, 2L) // เช็กตำแหน่งพิกัดเดินเข้าออกทุกๆ 2 Ticks (0.1 วินาที) ตอบสนองเรียลไทม์ทันที!
    }

    /**
     * สตรีมการรันเวลาของตัวนับเวลา (Ticking Engine) ทุกๆ 1 วินาที
     */
    private fun startTickScheduler() {
        tickTask = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { _ ->
            if (!isEnabled) return@runAtFixedRate

            // ประมวลผลสถานะของแต่ละสนามยึดครอง (Zone Ticking logic) ทุกๆ 1 วินาทีสม่ำเสมอ
            for (zone in zones.values) {
                tickZoneState(zone)
            }
        }, 20L, 20L)
    }

    private fun tickZoneState(zone: CaptureZone) {
        val world = Bukkit.getWorld(zone.worldName) ?: return

        // กรองหาผู้เล่นทั้งหมดที่อยู่ในเขตพื้นที่ยึดครองและไม่ได้แสร้งตาย/หายตัว
        val playersInZone = world.players.filter { player ->
            val cachedZone = playerCurrentZone[player.uniqueId]
            cachedZone != null && cachedZone.equals(zone.name, ignoreCase = true)
        }

        // กรองการส่งข้อความ ActionBar และพาทิเคิล
        sendActionBarToPlayers(zone, playersInZone)

        // ควบคุมสเตจการคำนวณเวลาของสนามยึดครอง
        when (zone.status) {
            CaptureZone.ZoneStatus.COOLDOWN -> {
                zone.remainingCooldownSeconds--
                if (zone.remainingCooldownSeconds <= 0) {
                    zone.status = CaptureZone.ZoneStatus.IDLE
                    saveZoneToConfig(zone) // บันทึกสถานะทันทีที่คูลดาวน์สิ้นสุด

                    val msgEnabled = config?.getBoolean("chat-notifications.cooldown-end.enabled", true) ?: true
                    val msg = config?.getString("chat-notifications.cooldown-end.message", "") ?: ""
                    if (msgEnabled && msg.isNotEmpty()) {
                        broadcastMessage(msg.replace("%zone%", zone.name))
                    }
                }
            }
            CaptureZone.ZoneStatus.IDLE -> {
                if (playersInZone.isNotEmpty()) {
                    val firstPlayer = playersInZone.first()
                    zone.occupantUuid = firstPlayer.uniqueId
                    zone.occupantName = firstPlayer.name
                    zone.status = CaptureZone.ZoneStatus.CAPTURING
                    zone.remainingCaptureSeconds = zone.captureTimeSeconds
                    saveZoneToConfig(zone) // บันทึกสถานะเมื่อผู้เล่นเริ่มยึดครอง

                    val msgEnabled = config?.getBoolean("chat-notifications.started.enabled", true) ?: true
                    val msg = config?.getString("chat-notifications.started.message", "") ?: ""
                    if (msgEnabled && msg.isNotEmpty()) {
                        broadcastMessage(msg.replace("%zone%", zone.name).replace("%player%", firstPlayer.name))
                    }
                }
            }
            CaptureZone.ZoneStatus.CAPTURING -> {
                if (playersInZone.isEmpty()) {
                    zone.status = CaptureZone.ZoneStatus.GRACE_PERIOD
                    zone.remainingGraceSeconds = zone.gracePeriodSeconds
                    saveZoneToConfig(zone) // บันทึกสถานะเมื่อเข้าสู่ช่วงเวลาผ่อนผัน
                } else {
                    val isOccupantStillInZone = playersInZone.any { it.uniqueId == zone.occupantUuid }
                    if (isOccupantStillInZone) {
                        zone.remainingCaptureSeconds--

                        // ประกาศลงแชทตามช่วงหลักกิโลเมตรวินาที (Milestones)
                        val milestoneEnabled = config?.getBoolean("chat-notifications.milestones.enabled", true) ?: true
                        val milestones = config?.getConfigurationSection("chat-notifications.milestones.list")
                        if (milestoneEnabled && milestones != null) {
                            val key = zone.remainingCaptureSeconds.toString()
                            if (milestones.contains(key)) {
                                val template = milestones.getString(key, "") ?: ""
                                if (template.isNotEmpty()) {
                                    broadcastMessage(template.replace("%zone%", zone.name).replace("%player%", zone.occupantName))
                                }
                            }
                        }

                        if (zone.remainingCaptureSeconds <= 0) {
                            // ผู้เล่นชนะ!
                            val winnerName = zone.occupantName
                            val winnerUuid = zone.occupantUuid
                            zone.status = CaptureZone.ZoneStatus.COOLDOWN
                            zone.remainingCooldownSeconds = zone.cooldownTimeSeconds
                            zone.occupantUuid = null
                            saveZoneToConfig(zone) // บันทึกสถานะเมื่อผู้เล่นยึดสำเร็จและติดคูลดาวน์

                            val msgEnabled = config?.getBoolean("chat-notifications.captured.enabled", true) ?: true
                            val msg = config?.getString("chat-notifications.captured.message", "") ?: ""
                            if (msgEnabled && msg.isNotEmpty()) {
                                broadcastMessage(msg.replace("%zone%", zone.name).replace("%player%", winnerName))
                            }

                            // มอบของรางวัล
                            for (commandTemplate in zone.rewardCommands) {
                                val execCommand = commandTemplate
                                    .replace("%player%", winnerName)
                                    .replace("%zone%", zone.name)
                                Bukkit.getGlobalRegionScheduler().execute(plugin) {
                                    plugin.server.dispatchCommand(plugin.server.consoleSender, execCommand)
                                }
                            }
                        }
                    } else {
                        // โดนแย่งพื้นที่โดยผู้ยึดครองไม่อยู่ในเขต แต่มีคนอื่นอยู่ (เปลี่ยนคนครองทันที)
                        val newOccupant = playersInZone.first()
                        zone.occupantUuid = newOccupant.uniqueId
                        zone.occupantName = newOccupant.name
                        saveZoneToConfig(zone) // บันทึกการเปลี่ยนแปลงสิทธิ์ผู้ยึดครองในพื้นที่
                    }
                }
            }
            CaptureZone.ZoneStatus.GRACE_PERIOD -> {
                if (playersInZone.isNotEmpty()) {
                    val newOccupant = playersInZone.first()
                    zone.occupantUuid = newOccupant.uniqueId
                    zone.occupantName = newOccupant.name
                    zone.status = CaptureZone.ZoneStatus.CAPTURING
                    saveZoneToConfig(zone) // บันทึกสถานะเมื่อมีผู้เข้ามายึดต่อในช่วงผ่อนผัน
                } else {
                    zone.remainingGraceSeconds--
                    if (zone.remainingGraceSeconds <= 0) {
                        zone.status = CaptureZone.ZoneStatus.IDLE
                        zone.occupantUuid = null
                        zone.occupantName = "None"
                        zone.remainingCaptureSeconds = zone.captureTimeSeconds
                        saveZoneToConfig(zone) // บันทึกสถานะเมื่อหมดเวลาผ่อนผันและโซนรีเซ็ต
                    }
                }
            }
        }
    }

    private fun sendActionBarToPlayers(zone: CaptureZone, playersInZone: List<Player>) {
        val template = when (zone.status) {
            CaptureZone.ZoneStatus.CAPTURING -> config?.getString("actionbar-notifications.capturing", "")
            CaptureZone.ZoneStatus.GRACE_PERIOD -> ""
            CaptureZone.ZoneStatus.COOLDOWN -> config?.getString("actionbar-notifications.cooldown", "")
            CaptureZone.ZoneStatus.IDLE -> config?.getString("actionbar-notifications.idle", "")
        } ?: ""

        if (template.isEmpty()) return

        val timePlaceholder = when (zone.status) {
            CaptureZone.ZoneStatus.CAPTURING -> formatPlaceholderTime(zone.remainingCaptureSeconds, "capturing")
            CaptureZone.ZoneStatus.GRACE_PERIOD -> formatPlaceholderTime(zone.remainingGraceSeconds, "grace_period")
            CaptureZone.ZoneStatus.COOLDOWN -> formatPlaceholderTime(zone.remainingCooldownSeconds, "cooldown")
            CaptureZone.ZoneStatus.IDLE -> ""
        }

        val formattedText = formatMessage(template)
            .replace("%zone%", zone.name)
            .replace("%player%", zone.occupantName)
            .replace("%time%", timePlaceholder)

        val component = parseToComponent(formattedText)
        for (player in playersInZone) {
            player.sendActionBar(component)
        }
    }

    /**
     * ดำเนินระบบการแสดงผลขอบเขตโซนโดยส่งพาทิเคิลแบบ Asynchronous เฉพาะผู้เล่นที่อยู่ใกล้เคียง
     */
    private fun startParticleScheduler() {
        val ticks = config?.getLong("settings.particles.render-interval-ticks", 10L) ?: 10L
        particleTask = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { _ ->
            if (!isEnabled) return@runAtFixedRate

            val now = System.currentTimeMillis()

            for (zone in zones.values) {
                if (!zone.particleEnabled) continue
                if (zone.status == CaptureZone.ZoneStatus.COOLDOWN && zone.hideDuringCooldown) continue

                // ตรวจสอบความถี่และระยะเวลาแสดงผลในหนึ่งรอบ (Spawn interval & duration)
                val intervalMs = (zone.particleSpawnInterval * 1000).toLong()
                val durationMs = (zone.particleDuration * 1000).toLong()
                if (intervalMs > 0 && durationMs > 0) {
                    val timeInCycle = now % intervalMs
                    if (timeInCycle >= durationMs) {
                        continue
                    }
                }

                val world = Bukkit.getWorld(zone.worldName) ?: continue
                val center = zone.getCenter()

                // หาผู้เล่นในโลกเดียวกันและอยู่ในรัศมี 40 บล็อก
                val nearbyPlayers = world.players.filter { player ->
                    player.location.distanceSquared(center) <= 1600.0
                }

                if (nearbyPlayers.isEmpty()) continue

                val density = zone.particleDensity
                val type = if (zone.status == CaptureZone.ZoneStatus.COOLDOWN) zone.cooldownParticleType else zone.particleType
                val size = if (zone.status == CaptureZone.ZoneStatus.COOLDOWN) zone.cooldownParticleSize else zone.particleSize
                val dustColor = if (zone.status == CaptureZone.ZoneStatus.COOLDOWN) zone.getCooldownParticleColor() else zone.getParticleColor()

                // ใช้ valueOf ผ่านชื่อเพื่อความปลอดภัยข้ามเวอร์ชัน (ป้องกัน NoSuchFieldError เมื่อเซิร์ฟเวอร์รุ่นเก่าไม่มี DUST)
                val particleTypeEnum = try {
                    Particle.valueOf(type.uppercase())
                } catch (e: Exception) {
                    try {
                        Particle.valueOf("REDSTONE")
                    } catch (ex: Exception) {
                        try {
                            Particle.valueOf("DUST")
                        } catch (ex2: Exception) {
                            null
                        }
                    }
                }

                // ดึงตำแหน่งจากแคช หรือคำนวณใหม่หากยังไม่มีแคช
                val points = zone.cachedOutlinePoints ?: calculateOutlinePoints(zone, density).also {
                    zone.cachedOutlinePoints = it
                }

                for (player in nearbyPlayers) {
                    player.scheduler.execute(plugin, {
                        if (!player.isOnline) return@execute
                        if (particleTypeEnum == null) return@execute
                        for (p in points) {
                            val loc = Location(world, p.x, p.y, p.z)
                            try {
                                // ตรวจสอบจาก Class ของ dataType ของ Particle ว่าต้องการ DustOptions หรือไม่ (รองรับทั้ง DUST และ REDSTONE)
                                val dataClass = try { particleTypeEnum.dataType } catch (e: NoSuchMethodError) { null }
                                if (dataClass == Particle.DustOptions::class.java) {
                                    val options = Particle.DustOptions(dustColor, size)
                                    player.spawnParticle(particleTypeEnum, loc, 1, 0.0, 0.0, 0.0, 0.0, options)
                                } else {
                                    player.spawnParticle(particleTypeEnum, loc, 1, 0.0, 0.0, 0.0, 0.0)
                                }
                            } catch (e: Exception) {
                                // ละเว้นข้อผิดพลาดกรณีเซิร์ฟเวอร์รุ่นเก่า/ไม่มีพาทิเคิลชนิดนั้น
                            }
                        }
                    }, null, 0L)
                }
            }
        }, 10L, ticks)
    }

    private fun calculateOutlinePoints(zone: CaptureZone, density: Double): List<Vector> {
        val list = ArrayList<Vector>()
        val minX = zone.minX.toDouble()
        val maxX = zone.maxX.toDouble() + 1.0
        val targetY = zone.displayY + zone.particleHeightOffset
        val minZ = zone.minZ.toDouble()
        val maxZ = zone.maxZ.toDouble() + 1.0

        val lines = listOf(
            // วาดเฉพาะ 4 เส้นด้านล่างตามขอบระนาบ X-Z
            Pair(Vector(minX, targetY, minZ), Vector(maxX, targetY, minZ)),
            Pair(Vector(maxX, targetY, minZ), Vector(maxX, targetY, maxZ)),
            Pair(Vector(maxX, targetY, maxZ), Vector(minX, targetY, maxZ)),
            Pair(Vector(minX, targetY, maxZ), Vector(minX, targetY, minZ))
        )

        for (line in lines) {
            val start = line.first
            val end = line.second
            val dist = start.distance(end)
            val steps = maxOf(1, Math.ceil(dist / density).toInt())
            for (i in 0..steps) {
                val fraction = i.toDouble() / steps
                val point = start.clone().add(end.clone().subtract(start).multiply(fraction))
                list.add(point)
            }
        }
        return list
    }

    fun formatMessage(msg: String): String {
        val prefix = config?.getString("settings.prefix", "<gradient:#00FF80:#00FFD8>CTF</gradient>") ?: "<gradient:#00FF80:#00FFD8>CTF</gradient>"
        return msg.replace("%prefix%", prefix)
    }

    fun broadcastMessage(msg: String) {
        val formattedMsg = formatMessage(msg)
        val component = parseToComponent(formattedMsg)
        plugin.server.broadcast(component)
    }

    fun formatCooldownTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format("%02d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    fun formatPlaceholderTime(seconds: Int, type: String): String {
        val section = config?.getConfigurationSection("settings.placeholder-time-format")
        val format = if (section != null) {
            section.getString(type, "FORMATTED") ?: "FORMATTED"
        } else {
            config?.getString("settings.placeholder-time-format", "FORMATTED") ?: "FORMATTED"
        }
        return if (format.equals("RAW", ignoreCase = true)) {
            seconds.toString()
        } else {
            formatCooldownTime(seconds)
        }
    }
}
