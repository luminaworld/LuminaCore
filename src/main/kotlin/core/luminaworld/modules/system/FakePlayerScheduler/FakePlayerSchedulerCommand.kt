package core.luminaworld.modules.system.FakePlayerScheduler

import org.bukkit.Bukkit
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
                    sender.sendMessage("§6[FP-Scheduler] §aเปิดการทำงานระบบบอทสำเร็จหลังรีโหลด!")
                } else {
                    sender.sendMessage("§6[FP-Scheduler] §cระบบยังคงปิดใช้งานอยู่ (ตรวจสอบ settings.enabled ในไฟล์คอนฟิก)")
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
                    sender.sendMessage("§6[FP-Scheduler] §aรีโหลดฐานข้อมูลบอทและตารางใหม่สำเร็จ!")
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
                            sender.sendMessage("§6[FP-Scheduler] §cวิธีใช้: /fpscheduler rush [on/off]")
                            return true
                        }
                    } else {
                        module.restartRushEnabled = !module.restartRushEnabled
                    }
                    module.saveSettings()
                    sender.sendMessage("§6[FP-Scheduler] §fโหมดช่วงเปิดเซิร์ฟใหม่ (Rush): " + (if (module.restartRushEnabled) "§aเปิดใช้งาน" else "§cปิดใช้งาน"))
                    return true
                }
                "add" -> {
                    if (args.size < 3) {
                        sender.sendMessage("§6[FP-Scheduler] §cวิธีใช้: /fpscheduler add [ชื่อบอท] [ยศ]")
                        return true
                    }
                    val name = args[1]
                    val rank = args[2]
                    if (!module.botPool.contains(name)) {
                        module.botPool.add(name)
                    }
                    module.botRanks[name] = rank
                    module.saveDatabase()
                    sender.sendMessage("§6[FP-Scheduler] §aเพิ่ม/อัปเดตบอท §e$name §7(ยศ: $rank) เรียบร้อย!")
                    return true
                }
                "remove" -> {
                    if (args.size < 2) {
                        sender.sendMessage("§6[FP-Scheduler] §cวิธีใช้: /fpscheduler remove [ชื่อบอท]")
                        return true
                    }
                    val name = args[1]
                    if (!module.botPool.contains(name)) {
                        sender.sendMessage("§6[FP-Scheduler] §cไม่พบบอท §e$name §cในระบบ")
                        return true
                    }
                    module.botPool.remove(name)
                    module.botRanks.remove(name)
                    if (module.activeBots.contains(name)) {
                        module.despawnBot(name)
                    }
                    module.saveDatabase()
                    sender.sendMessage("§6[FP-Scheduler] §eลบบอท §f$name §eออกจากระบบแล้ว")
                    return true
                }
                "status" -> {
                    val nowMs = System.currentTimeMillis()
                    val calendar = java.util.Calendar.getInstance()
                    val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                    
                    val target = module.getCurrentTarget()
                    val currentCount = module.activeBots.size
                    val isRush = module.isRestartRushMode()
                    val baseTarget = module.getCurrentBaseTarget()
                    
                    sender.sendMessage("§6§l=== FakePlayer สถานะระบบ ===")
                    sender.sendMessage("§eเป้าหมายชั่วโมงนี้: §a$baseTarget ตัว §8| §eสุ่มจริง: §a$target ตัว §8| §eออนไลน์: §a$currentCount/$target ตัว")
                    sender.sendMessage("§eบอททั้งหมด: §b${module.botPool.size} ตัว §8| §eโหมด Rush: " + (if (isRush) "§aเปิดใช้งาน" else "§7ปิดใช้งาน"))

                    val secondsSinceLastJoin = (nowMs - module.lastJoinTimeMs) / 1000.0
                    if (currentCount < target) {
                        val rem = Math.max(0, Math.round(module.joinIntervalSeconds - secondsSinceLastJoin).toInt())
                        sender.sendMessage("§eคิวถัดไป: §aบอทเข้าในอีก $rem วินาที")
                    }

                    val secondsSinceLastLeave = (nowMs - module.lastLeaveTimeMs) / 1000.0
                    if (currentCount > target) {
                        val rem = Math.max(0, Math.round(module.leaveIntervalSeconds - secondsSinceLastLeave).toInt())
                        sender.sendMessage("§eคิวถัดไป: §cบอทออกในอีก $rem วินาที")
                    }

                    if (currentCount == 0) {
                        sender.sendMessage("§eรายชื่อบอทที่ออนไลน์:")
                        sender.sendMessage(" §8- §7ไม่มีบอทออนไลน์ในขณะนี้")
                    } else {
                        // ระบบแบ่งหน้า (Pagination) หน้าละ 5 รายชื่อ
                        val page = if (args.size > 1) args[1].toIntOrNull() ?: 1 else 1
                        val pageSize = 5
                        val activeSnapshot = ArrayList(module.activeBots)
                        val totalPages = Math.max(1, Math.ceil(activeSnapshot.size.toDouble() / pageSize.toDouble()).toInt())
                        val currentPage = Math.max(1, Math.min(page, totalPages))
                        
                        val startIndex = (currentPage - 1) * pageSize
                        val endIndex = Math.min(startIndex + pageSize, activeSnapshot.size)
                        
                        sender.sendMessage("§eรายชื่อบอทที่ออนไลน์ (หน้า $currentPage/$totalPages):")
                        val subList = activeSnapshot.subList(startIndex, endIndex)
                        for (name in subList) {
                            val rank = module.botRanks[name] ?: "ไม่มี"
                            val timeLeftSec = module.botSessions[name]?.let {
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
                            sender.sendMessage(" §7• §f$name §a[${rank.uppercase()}] §8- §eเหลือเวลา $timeStr")
                        }
                        
                        if (currentPage < totalPages) {
                            sender.sendMessage("§8» §7พิมพ์ §e/fpscheduler status ${currentPage + 1} §7เพื่อดูหน้าถัดไป")
                        }
                    }
                    return true
                }
            }
        }

        sender.sendMessage("§6§l=== FakePlayer สั่งการระบบ ===")
        sender.sendMessage("§e/fpscheduler status §7- ดูสถานะการสุ่มบอทปัจจุบัน")
        sender.sendMessage("§e/fpscheduler reload §7- รีโหลดและเริ่มตารางสุ่มใหม่")
        sender.sendMessage("§e/fpscheduler reset §7- สั่งเตะบอททั้งหมดออก")
        sender.sendMessage("§e/fpscheduler rush [on/off] §7- เปิด/ปิดเร่งบอทช่วงเปิดเซิร์ฟ")
        sender.sendMessage("§e/fpscheduler add [ชื่อ] [ยศ] §7- เพิ่มบอทลงระบบ")
        sender.sendMessage("§e/fpscheduler remove [ชื่อ] §7- ลบบอทออกจากระบบ")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.isOp && !sender.hasPermission("luminacore.admin")) {
            return emptyList()
        }
        if (args.size == 1) {
            val subcommands = listOf("status", "reload", "sync", "reset", "rush", "add", "remove")
            return subcommands.filter { it.startsWith(args[0].lowercase(Locale.ROOT)) }
        }
        if (args.size == 2) {
            val sub = args[0].lowercase(Locale.ROOT)
            if (sub == "rush") {
                val options = listOf("on", "off")
                return options.filter { it.startsWith(args[1].lowercase(Locale.ROOT)) }
            }
            if (sub == "remove") {
                val search = args[1].lowercase(Locale.ROOT)
                return module.botPool.filter { it.lowercase(Locale.ROOT).startsWith(search) }
            }
        }
        return emptyList()
    }
}
