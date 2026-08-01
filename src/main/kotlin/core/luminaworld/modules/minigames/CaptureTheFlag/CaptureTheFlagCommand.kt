package core.luminaworld.modules.minigames.CaptureTheFlag

import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class CaptureTheFlagCommand(private val module: CaptureTheFlagModule) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            // อนุญาตให้ Console รัน reload
            if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
                module.reload()
                val msg = module.messagesConfig?.getString("admin.reload-success", "%prefix% &aรีโหลดข้อมูลและตั้งค่าของระบบ CTF ทั้งหมดสำเร็จแล้ว!") ?: "%prefix% &aรีโหลดข้อมูลและตั้งค่าของระบบ CTF ทั้งหมดสำเร็จแล้ว!"
                sender.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                return true
            }
            val msg = module.messagesConfig?.getString("admin.only-player", "%prefix% &cคำสั่งนี้ใช้งานได้เฉพาะผู้เล่นในเกมเท่านั้น!") ?: "%prefix% &cคำสั่งนี้ใช้งานได้เฉพาะผู้เล่นในเกมเท่านั้น!"
            sender.sendMessage(module.parseToComponent(module.formatMessage(msg)))
            return true
        }

        val player = sender
        // ตรวจสอบสิทธิ์การใช้งานคำสั่งแอดมิน (ดึงสิทธิ์แอดมินจาก Config ย่อย)
        val adminPerm = module.config?.getString("settings.admin-permission", "luminacore.minigame.ctf.admin") ?: "luminacore.minigame.ctf.admin"
        if (!player.hasPermission(adminPerm) && !player.isOp) {
            val msg = module.messagesConfig?.getString("admin.no-permission", "%prefix% &cคุณไม่มีสิทธิ์เข้าถึงคำสั่งนี้!") ?: "%prefix% &cคุณไม่มีสิทธิ์เข้าถึงคำสั่งนี้!"
            player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
            return true
        }

        if (args.isEmpty()) {
            // เปิดเมนู GUI สำหรับจัดการโซนยึดพื้นที่
            openManagementGUI(player)
            return true
        }

        val action = args[0].lowercase()
        when (action) {
            "pos1" -> {
                val loc = player.location
                module.selectionPos1[player.uniqueId] = loc.block.location
                val msg = module.messagesConfig?.getString("admin.pos1-set", "%prefix% &aกำหนดจุดที่ 1 สำเร็จที่ &eX=%x%, Y=%y%, Z=%z%") ?: "%prefix% &aกำหนดจุดที่ 1 สำเร็จที่ &eX=%x%, Y=%y%, Z=%z%"
                val formatted = msg
                    .replace("%x%", loc.blockX.toString())
                    .replace("%y%", loc.blockY.toString())
                    .replace("%z%", loc.blockZ.toString())
                player.sendMessage(module.parseToComponent(module.formatMessage(formatted)))
            }
            "pos2" -> {
                val loc = player.location
                module.selectionPos2[player.uniqueId] = loc.block.location
                val msg = module.messagesConfig?.getString("admin.pos2-set", "%prefix% &aกำหนดจุดที่ 2 สำเร็จที่ &eX=%x%, Y=%y%, Z=%z%") ?: "%prefix% &aกำหนดจุดที่ 2 สำเร็จที่ &eX=%x%, Y=%y%, Z=%z%"
                val formatted = msg
                    .replace("%x%", loc.blockX.toString())
                    .replace("%y%", loc.blockY.toString())
                    .replace("%z%", loc.blockZ.toString())
                player.sendMessage(module.parseToComponent(module.formatMessage(formatted)))
            }
            "create" -> {
                if (args.size < 2) {
                    val msg = module.messagesConfig?.getString("admin.enter-zone-name", "%prefix% &cโปรดระบุชื่อพื้นที่ยึดครอง! (/ctf create <ชื่อ>)") ?: "%prefix% &cโปรดระบุชื่อพื้นที่ยึดครอง! (/ctf create <ชื่อ>)"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                    return true
                }
                val zoneName = args[1]
                val p1 = module.selectionPos1[player.uniqueId]
                val p2 = module.selectionPos2[player.uniqueId]

                if (p1 == null || p2 == null) {
                    val msg = module.messagesConfig?.getString("admin.select-points-first", "%prefix% &cกรุณาเลือกตำแหน่ง pos1 และ pos2 ให้ครบถ้วนก่อนสร้างโซน!") ?: "%prefix% &cกรุณาเลือกตำแหน่ง pos1 และ pos2 ให้ครบถ้วนก่อนสร้างโซน!"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                    return true
                }

                if (p1.world?.name != p2.world?.name) {
                    val msg = module.messagesConfig?.getString("admin.world-mismatch", "%prefix% &cตำแหน่งมุมทั้งสองจุดต้องอยู่ใน World เดียวกัน!") ?: "%prefix% &cตำแหน่งมุมทั้งสองจุดต้องอยู่ใน World เดียวกัน!"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                    return true
                }

                val worldName = p1.world?.name ?: player.world.name
                val minX = minOf(p1.blockX, p2.blockX)
                val maxX = maxOf(p1.blockX, p2.blockX)
                val minY = minOf(p1.blockY, p2.blockY)
                val maxY = maxOf(p1.blockY, p2.blockY)
                val minZ = minOf(p1.blockZ, p2.blockZ)
                val maxZ = maxOf(p1.blockZ, p2.blockZ)

                val captureTime = module.config?.getInt("settings.default-capture-time", 180) ?: 180
                val gracePeriod = module.config?.getInt("settings.default-grace-period", 10) ?: 10
                val cooldownTime = module.config?.getInt("settings.default-cooldown-time", 1800) ?: 1800
                val displayY = player.location.y

                val zone = CaptureZone(
                    name = zoneName,
                    worldName = worldName,
                    minX = minX,
                    maxX = maxX,
                    minY = minY,
                    maxY = maxY,
                    minZ = minZ,
                    maxZ = maxZ,
                    captureTimeSeconds = captureTime,
                    gracePeriodSeconds = gracePeriod,
                    cooldownTimeSeconds = cooldownTime,
                    displayY = displayY
                )

                module.zones[zoneName] = zone
                module.saveZoneToConfig(zone)

                val msg = module.messagesConfig?.getString("admin.create-success", "%prefix% &aสร้างพื้นที่ยึด &e%zone% &aสำเร็จและบันทึกลงไฟล์แล้ว!") ?: "%prefix% &aสร้างพื้นที่ยึด &e%zone% &aสำเร็จและบันทึกลงไฟล์แล้ว!"
                player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", zoneName))))
            }
            "createcenter" -> {
                if (args.size < 3) {
                    val msg = module.messagesConfig?.getString("admin.invalid-syntax", "%prefix% &cคำสั่งไม่ถูกต้อง! กรุณาใช้: &7/ctf pos1, pos2, create, createcenter, delete, list, reload") ?: "%prefix% &cคำสั่งไม่ถูกต้อง! กรุณาใช้: &7/ctf pos1, pos2, create, createcenter, delete, list, reload"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                    return true
                }
                val zoneName = args[1]
                val radius = args[2].toIntOrNull()
                if (radius == null || radius <= 0) {
                    val msg = module.messagesConfig?.getString("admin.enter-number", "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!") ?: "%prefix% &cกรุณากรอกตัวเลขจำนวนเต็มที่ถูกต้อง!"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                    return true
                }

                val loc = player.location
                val cx = loc.blockX
                val cy = loc.blockY
                val cz = loc.blockZ
                val worldName = loc.world?.name ?: player.world.name

                val minX = cx - radius
                val maxX = cx + radius
                val minY = cy - radius
                val maxY = cy + radius
                val minZ = cz - radius
                val maxZ = cz + radius

                val captureTime = module.config?.getInt("settings.default-capture-time", 180) ?: 180
                val gracePeriod = module.config?.getInt("settings.default-grace-period", 10) ?: 10
                val cooldownTime = module.config?.getInt("settings.default-cooldown-time", 1800) ?: 1800
                val displayY = loc.y

                val zone = CaptureZone(
                    name = zoneName,
                    worldName = worldName,
                    minX = minX,
                    maxX = maxX,
                    minY = minY,
                    maxY = maxY,
                    minZ = minZ,
                    maxZ = maxZ,
                    captureTimeSeconds = captureTime,
                    gracePeriodSeconds = gracePeriod,
                    cooldownTimeSeconds = cooldownTime,
                    displayY = displayY
                )

                module.zones[zoneName] = zone
                module.saveZoneToConfig(zone)

                val msg = module.messagesConfig?.getString("admin.create-center-success", "%prefix% &aสร้างพื้นที่จากจุดศูนย์กลาง &e%zone% &a(รัศมี %radius% บล็อก) สำเร็จแล้ว!") ?: "%prefix% &aสร้างพื้นที่จากจุดศูนย์กลาง &e%zone% &a(รัศมี %radius% บล็อก) สำเร็จแล้ว!"
                val formatted = msg.replace("%zone%", zoneName).replace("%radius%", radius.toString())
                player.sendMessage(module.parseToComponent(module.formatMessage(formatted)))
            }
            "delete" -> {
                if (args.size < 2) {
                    val msg = module.messagesConfig?.getString("admin.enter-zone-name", "%prefix% &cโปรดระบุชื่อพื้นที่ยึดครอง! (/ctf create <ชื่อ>)") ?: "%prefix% &cโปรดระบุชื่อพื้นที่ยึดครอง! (/ctf create <ชื่อ>)"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                    return true
                }
                val zoneName = args[1]
                val zone = module.getZone(zoneName)
                if (zone != null) {
                    val originalName = zone.name
                    module.zones.remove(originalName)
                    module.removeZoneFromConfig(originalName)

                    val msg = module.messagesConfig?.getString("admin.delete-success", "%prefix% &aลบพื้นที่ยึดครอง &e%zone% &aออกจากระบบและไฟล์ข้อมูลแล้ว") ?: "%prefix% &aลบพื้นที่ยึดครอง &e%zone% &aออกจากระบบและไฟล์ข้อมูลแล้ว"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", originalName))))
                } else {
                    val msg = module.messagesConfig?.getString("admin.zone-not-found", "%prefix% &cไม่พบพื้นที่ยึดครองชื่อ &e%zone%!") ?: "%prefix% &cไม่พบพื้นที่ยึดครองชื่อ &e%zone%!"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", args[1]))))
                }
            }
            "list", "gui", "menu" -> {
                openManagementGUI(player)
            }
            "reload" -> {
                module.reload()
                val msg = module.messagesConfig?.getString("admin.reload-success", "%prefix% &aรีโหลดข้อมูลและตั้งค่าของระบบ CTF ทั้งหมดสำเร็จแล้ว!") ?: "%prefix% &aรีโหลดข้อมูลและตั้งค่าของระบบ CTF ทั้งหมดสำเร็จแล้ว!"
                player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
            }
            "forcewin" -> {
                if (args.size < 2) {
                    val msg = module.messagesConfig?.getString("admin.enter-zone-name", "%prefix% &cโปรดระบุชื่อพื้นที่ยึดครอง! (/ctf create <ชื่อ>)") ?: "%prefix% &cโปรดระบุชื่อพื้นที่ยึดครอง! (/ctf create <ชื่อ>)"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                    return true
                }
                val zoneName = args[1]
                val zone = module.getZone(zoneName)
                if (zone == null) {
                    val msg = module.messagesConfig?.getString("admin.zone-not-found", "%prefix% &cไม่พบพื้นที่ยึดครองชื่อ &e%zone%!") ?: "%prefix% &cไม่พบพื้นที่ยึดครองชื่อ &e%zone%!"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", args[1]))))
                    return true
                }
                synchronized(zone) {
                    zone.remainingCaptureSeconds = 0
                    zone.status = CaptureZone.ZoneStatus.CAPTURING
                    zone.occupantUuid = player.uniqueId
                    zone.occupantName = player.name
                }
                val msg = module.messagesConfig?.getString("admin.forcewin-success", "%prefix% &aบังคับให้ผู้เล่นยึดพื้นที่ &e%zone% &aสำเร็จเรียบร้อย!") ?: "%prefix% &aบังคับให้ผู้เล่นยึดพื้นที่ &e%zone% &aสำเร็จเรียบร้อย!"
                player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", zone.name))))
            }
            "forcecooldown" -> {
                if (args.size < 2) {
                    val msg = module.messagesConfig?.getString("admin.enter-zone-name", "%prefix% &cโปรดระบุชื่อพื้นที่ยึดครอง! (/ctf create <ชื่อ>)") ?: "%prefix% &cโปรดระบุชื่อพื้นที่ยึดครอง! (/ctf create <ชื่อ>)"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
                    return true
                }
                val zoneName = args[1]
                val zone = module.getZone(zoneName)
                if (zone == null) {
                    val msg = module.messagesConfig?.getString("admin.zone-not-found", "%prefix% &cไม่พบพื้นที่ยึดครองชื่อ &e%zone%!") ?: "%prefix% &cไม่พบพื้นที่ยึดครองชื่อ &e%zone%!"
                    player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", args[1]))))
                    return true
                }
                synchronized(zone) {
                    zone.status = CaptureZone.ZoneStatus.COOLDOWN
                    zone.remainingCooldownSeconds = zone.cooldownTimeSeconds
                    zone.occupantUuid = null
                }
                val msg = module.messagesConfig?.getString("admin.forcecooldown-success", "%prefix% &aบังคับให้พื้นที่ &e%zone% &aเข้าสู่สถานะคูลดาวน์แล้ว!") ?: "%prefix% &a&aบังคับให้พื้นที่ &e%zone% &aเข้าสู่สถานะคูลดาวน์แล้ว!"
                player.sendMessage(module.parseToComponent(module.formatMessage(msg.replace("%zone%", zone.name))))
            }
            else -> {
                val msg = module.messagesConfig?.getString("admin.invalid-syntax", "%prefix% &cคำสั่งไม่ถูกต้อง! กรุณาใช้: &7/ctf pos1, pos2, create, createcenter, delete, list, reload") ?: "%prefix% &cคำสั่งไม่ถูกต้อง! กรุณาใช้: &7/ctf pos1, pos2, create, createcenter, delete, list, reload"
                player.sendMessage(module.parseToComponent(module.formatMessage(msg)))
            }
        }
        return true
    }

    private fun openManagementGUI(player: Player) {
        val gui = CaptureTheFlagGUI(module)
        gui.openMainGUI(player)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val adminPerm = module.config?.getString("settings.admin-permission", "luminacore.minigame.ctf.admin") ?: "luminacore.minigame.ctf.admin"
        if (!sender.hasPermission(adminPerm) && !sender.isOp) return emptyList()

        if (args.size == 1) {
            return listOf("pos1", "pos2", "create", "createcenter", "delete", "list", "gui", "reload", "forcewin", "forcecooldown")
                .filter { it.startsWith(args[0].lowercase()) }
        }

        if (args.size == 2 && (args[0].equals("delete", true) || args[0].equals("forcewin", true) || args[0].equals("forcecooldown", true))) {
            return module.zones.keys.filter { it.startsWith(args[1], true) }
        }

        return emptyList()
    }
}
