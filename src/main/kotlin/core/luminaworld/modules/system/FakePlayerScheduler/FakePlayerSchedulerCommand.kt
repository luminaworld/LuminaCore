package core.luminaworld.modules.system.FakePlayerScheduler

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.Date
import java.util.Locale

class FakePlayerSchedulerCommand(private val module: FakePlayerSchedulerModule) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.isOp && !sender.hasPermission("luminacore.admin")) {
            sender.sendMessage("§cYou do not have permission to execute this command.")
            return true
        }

        // ตรวจสอบสถานะการทำงานของโมดูลย่อย
        if (!module.isSchedulerActive) {
            if (args.isNotEmpty() && (args[0].equals("reload", ignoreCase = true) || args[0].equals("sync", ignoreCase = true))) {
                module.reload()
                if (module.isSchedulerActive) {
                    sender.sendMessage("§6[FP-Scheduler] §aเปิดการทำงานระบบสเก็ตดูลเลอร์บอทสำเร็จหลังรีโหลด!")
                } else {
                    sender.sendMessage("§6[FP-Scheduler] §cระบบยังคงปิดใช้งานอยู่ (ตรวจสอบ settings.enabled ในไฟล์คอนฟิก)!")
                }
                return true
            }
            sender.sendMessage("§6[FP-Scheduler] §cระบบสเก็ตดูลเลอร์บอทถูกปิดใช้งานอยู่ในขณะนี้ (settings.enabled: false)")
            sender.sendMessage("§7หากต้องการเปิดใช้งาน กรุณาแก้ไขไฟล์คอนฟิกแล้วพิมพ์ §e/fpscheduler reload")
            return true
        }

        if (args.isNotEmpty()) {
            val sub = args[0].lowercase(Locale.ROOT)
            when (sub) {
                "reload", "sync" -> {
                    module.loadYamlDatabase()
                    module.updateDailySchedule()
                    module.syncInitialBots()
                    sender.sendMessage("§6[FP-Scheduler] §aรีโหลดฐานข้อมูลบอทและสุ่มตารางงานวันใหม่สำเร็จ!")
                    return true
                }
                "reset" -> {
                    module.resetAllBots()
                    sender.sendMessage("§6[FP-Scheduler] §eสั่งเตะบอททั้งหมดเรียบร้อยแล้ว")
                    return true
                }
                "rush" -> {
                    if (args.size > 1) {
                        val value = args[1].lowercase(Locale.ROOT)
                        if (value == "on" || value == "true" || value == "enable") {
                            module.restartRushEnabled = true
                        } else if (value == "off" || value == "false" || value == "disable") {
                            module.restartRushEnabled = false
                        } else {
                            sender.sendMessage("§6[FP-Scheduler] §cวิธีใช้: /fpscheduler rush [on/off] หรือ /fpscheduler rush เพื่อสลับเปิด/ปิด")
                            return true
                        }
                    } else {
                        module.restartRushEnabled = !module.restartRushEnabled
                    }
                    module.saveSettings()
                    sender.sendMessage("§6[FP-Scheduler] §fโหมดเปิดเซิร์ฟใหม่ (Restart Rush): " + (if (module.restartRushEnabled) "§aเปิดใช้งาน" else "§cปิดใช้งาน"))
                    return true
                }
                "status" -> {
                    val now = Date()
                    val nowMs = System.currentTimeMillis()
                    val calendar = java.util.Calendar.getInstance()
                    val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                    
                    val target = module.getCurrentTarget()
                    val currentCount = module.activeBots.size
                    val isRush = module.isRestartRushMode()
                    val baseTarget = module.getCurrentBaseTarget()
                    val currentNoise = module.currentNoise

                    sender.sendMessage("§6§l=== FakePlayer Scheduler Status ===")
                    sender.sendMessage("§eวันที่สุ่ม (Seed): §f${module.dailySeed}")
                    sender.sendMessage("§eเป้าหมายชั่วโมงนี้: §a${module.hourlyTargets[currentHour]} ตัว §7(ชั่วโมงถัดไป: ${module.hourlyTargets[(currentHour + 1) % 24]} ตัว)")
                    
                    val noiseStr = if (currentNoise >= 0) "+$currentNoise" else "$currentNoise"
                    sender.sendMessage("§eเป้าหมายปัจจุบัน (เฉลี่ยรายนาที): §a$baseTarget §eตัว | รวม Noise ($noiseStr): §a$target §eตัว §7(ปัจจุบันออนไลน์: $currentCount ตัว)")
                    sender.sendMessage("§eจำนวนบอทในระบบทั้งหมด (Pool): §b${module.botPool.size} ตัว")
                    
                    val rushStatus = if (module.restartRushEnabled) {
                        if (isRush) "§aกำลังทำงาน (บอทเข้าเร็วขึ้นตัวละ 10-30 วิ)"
                        else "§eเปิดการใช้งานไว้ (แต่ไม่ได้ทำงาน: พ้นช่วง 10 นาทีแรกแล้ว)"
                    } else {
                        "§cปิดการใช้งานหลัก"
                    }
                    sender.sendMessage("§eโหมดเปิดเซิร์ฟใหม่ (Restart Rush): $rushStatus")

                    // เวลาที่บอทถัดไปจะเข้า
                    val secondsSinceLastJoin = (nowMs - module.lastJoinTimeMs) / 1000.0
                    if (currentCount < target) {
                        val rem = Math.max(0, Math.round(module.joinIntervalSeconds - secondsSinceLastJoin).toInt())
                        val nextJoinClock = Date(nowMs + rem * 1000L)
                        val timeStr = if (rem == 0) "§aกำลังเข้าล็อกอิน..." else "§b${module.formatTime(nextJoinClock, true)} §7(ในอีก $rem วินาที)"
                        sender.sendMessage("§eเวลาที่บอทถัดไปจะเข้า: $timeStr")
                    } else {
                        sender.sendMessage("§eเวลาที่บอทถัดไปจะเข้า: §7เป้าหมายเต็มแล้ว (รอเปลี่ยนช่วงเวลาหรือสุ่ม Swap)")
                    }

                    // เวลาที่บอทถัดไปจะออก (เพื่อปรับสมดุล)
                    val secondsSinceLastLeave = (nowMs - module.lastLeaveTimeMs) / 1000.0
                    if (currentCount > target) {
                        val rem = Math.max(0, Math.round(module.leaveIntervalSeconds - secondsSinceLastLeave).toInt())
                        val nextLeaveClock = Date(nowMs + rem * 1000L)
                        val timeStr = if (rem == 0) "§cกำลังล็อกเอาท์..." else "§b${module.formatTime(nextLeaveClock, true)} §7(ในอีก $rem วินาที)"
                        sender.sendMessage("§eเวลาที่บอทถัดไปจะออก (เพื่อปรับสมดุล): $timeStr")
                    } else {
                        sender.sendMessage("§eเวลาที่บอทถัดไปจะออก (เพื่อปรับสมดุล): §7จำนวนปกติ (รอหมดอายุขัย)")
                    }

                    sender.sendMessage("§eบอทที่ออนไลน์อยู่ขณะนี้ (§a$currentCount ตัว§e):")
                    if (currentCount == 0) {
                        sender.sendMessage(" §7- ไม่มีบอทออนไลน์ในระบบ")
                    } else {
                        for (name in module.activeBots) {
                            val rank = module.botRanks[name] ?: "ไม่มี"
                            val joinT = module.botJoinTimes[name] ?: "ไม่ระบุ"
                            val logoutT = module.botLogoutTimes[name] ?: "ไม่ระบุ"
                            val timeLeftSec = module.botSessions[name]?.let {
                                Math.max(0L, (it - nowMs) / 1000L)
                            } ?: 0L
                            val minLeft = timeLeftSec / 60
                            val secLeft = timeLeftSec % 60
                            val timeStr = "$minLeft นาที $secLeft วินาที"
                            sender.sendMessage(" §7• §a$name §7(ยศ: §e$rank§7) | เข้า: §b$joinT §7| ออก: §c$logoutT §7(เหลือ: §e$timeStr§7)")
                        }
                    }
                    return true
                }
            }
        }

        sender.sendMessage("§6§l=== FakePlayer Scheduler Menu ===")
        sender.sendMessage("§e/fpscheduler status §7- ดูสถานะการสุ่มบอทปัจจุบัน")
        sender.sendMessage("§e/fpscheduler reload §7- รีโหลดบอทและเริ่มตารางสุ่มใหม่")
        sender.sendMessage("§e/fpscheduler reset §7- สั่งเตะบอททั้งหมดของสคริปต์ออก")
        sender.sendMessage("§e/fpscheduler rush [on/off] §7- เปิด/ปิดโหมดเร่งจำนวนบอทหลังเซิร์ฟเปิด")
        sender.sendMessage("§7บอทออนไลน์: §a${module.activeBots.size} ตัว §7| เป้าหมายปัจจุบัน: §a${module.getCurrentTarget()} ตัว")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.isOp && !sender.hasPermission("luminacore.admin")) {
            return emptyList()
        }
        if (args.size == 1) {
            val subcommands = listOf("status", "reload", "sync", "reset", "rush")
            return subcommands.filter { it.startsWith(args[0].lowercase(Locale.ROOT)) }
        }
        if (args.size == 2 && args[0].equals("rush", ignoreCase = true)) {
            val options = listOf("on", "off")
            return options.filter { it.startsWith(args[1].lowercase(Locale.ROOT)) }
        }
        return emptyList()
    }
}
