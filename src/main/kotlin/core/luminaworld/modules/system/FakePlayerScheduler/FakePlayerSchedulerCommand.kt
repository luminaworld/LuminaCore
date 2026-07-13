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
            sendMessage(sender, "no_permission")
            return true
        }

        // ตรวจสอบสถานะการทำงานของโมดูลย่อย
        if (!module.isSchedulerActive) {
            if (args.isNotEmpty() && (args[0].equals("reload", ignoreCase = true) || args[0].equals("sync", ignoreCase = true))) {
                module.reload()
                if (module.isSchedulerActive) {
                    sendMessage(sender, "system_enabled_reload_success")
                } else {
                    sendMessage(sender, "system_disabled_reload")
                }
                return true
            }
            sendMessage(sender, "system_disabled")
            sendMessage(sender, "system_enable_hint")
            return true
        }

        if (args.isNotEmpty()) {
            val sub = args[0].lowercase(Locale.ROOT)
            when (sub) {
                "reload", "sync" -> {
                    module.loadYamlDatabase()
                    module.updateDailySchedule()
                    module.syncInitialBots()
                    sendMessage(sender, "reload_success")
                    return true
                }
                "reset" -> {
                    module.resetAllBots()
                    sendMessage(sender, "reset_success")
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
                            sendMessage(sender, "rush_usage")
                            return true
                        }
                    } else {
                        module.restartRushEnabled = !module.restartRushEnabled
                    }
                    module.saveSettings()
                    val statusText = if (module.restartRushEnabled) "§aเปิดใช้งาน" else "§cปิดใช้งาน"
                    sendMessage(sender, "rush_status", mapOf("%status%" to statusText))
                    return true
                }
                "add" -> {
                    if (args.size < 3) {
                        sendMessage(sender, "add_usage")
                        return true
                    }
                    val name = args[1]
                    val rank = args[2]
                    if (!module.botPool.contains(name)) {
                        module.botPool.add(name)
                    }
                    module.botRanks[name] = rank
                    module.saveDatabase()
                    sendMessage(sender, "add_success", mapOf("%name%" to name, "%rank%" to rank))
                    return true
                }
                "remove" -> {
                    if (args.size < 2) {
                        sendMessage(sender, "remove_usage")
                        return true
                    }
                    val name = args[1]
                    if (!module.botPool.contains(name)) {
                        sendMessage(sender, "bot_not_found", mapOf("%name%" to name))
                        return true
                    }
                    module.botPool.remove(name)
                    module.botRanks.remove(name)
                    if (module.activeBots.contains(name)) {
                        module.despawnBot(name)
                    }
                    module.saveDatabase()
                    sendMessage(sender, "remove_success", mapOf("%name%" to name))
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
                    
                    sendMessage(sender, "status_title")
                    sendMessage(sender, "status_stats", mapOf(
                        "%base_target%" to baseTarget.toString(),
                        "%target%" to target.toString(),
                        "%current%" to currentCount.toString()
                    ))
                    val rushText = if (isRush) "§aเปิดใช้งาน" else "§7ปิดใช้งาน"
                    sendMessage(sender, "status_info", mapOf(
                        "%total%" to module.botPool.size.toString(),
                        "%rush%" to rushText
                    ))

                    val secondsSinceLastJoin = (nowMs - module.lastJoinTimeMs) / 1000.0
                    if (currentCount < target) {
                        val rem = Math.max(0, Math.round(module.joinIntervalSeconds - secondsSinceLastJoin).toInt())
                        sendMessage(sender, "status_next_join", mapOf("%time%" to rem.toString()))
                    }

                    val secondsSinceLastLeave = (nowMs - module.lastLeaveTimeMs) / 1000.0
                    if (currentCount > target) {
                        val rem = Math.max(0, Math.round(module.leaveIntervalSeconds - secondsSinceLastLeave).toInt())
                        sendMessage(sender, "status_next_leave", mapOf("%time%" to rem.toString()))
                    }

                    if (currentCount == 0) {
                        sendMessage(sender, "status_list_title")
                        sendMessage(sender, "status_list_empty")
                    } else {
                        // ระบบแบ่งหน้า (Pagination) หน้าละ 5 รายชื่อ
                        val page = if (args.size > 1) args[1].toIntOrNull() ?: 1 else 1
                        val pageSize = 5
                        val activeSnapshot = ArrayList(module.activeBots)
                        val totalPages = Math.max(1, Math.ceil(activeSnapshot.size.toDouble() / pageSize.toDouble()).toInt())
                        val currentPage = Math.max(1, Math.min(page, totalPages))
                        
                        val startIndex = (currentPage - 1) * pageSize
                        val endIndex = Math.min(startIndex + pageSize, activeSnapshot.size)
                        
                        sendMessage(sender, "status_list_title_page", mapOf(
                            "%page%" to currentPage.toString(),
                            "%total_pages%" to totalPages.toString()
                        ))
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
                            sendMessage(sender, "status_list_item", mapOf(
                                "%name%" to name,
                                "%rank%" to rank.uppercase(),
                                "%time%" to timeStr
                            ))
                        }
                        
                        if (currentPage < totalPages) {
                            sendMessage(sender, "status_next_page", mapOf(
                                "%next_page%" to (currentPage + 1).toString()
                            ))
                        }
                    }
                    return true
                }
            }
        }

        sendMessage(sender, "help_title")
        sendMessage(sender, "help_status")
        sendMessage(sender, "help_reload")
        sendMessage(sender, "help_reset")
        sendMessage(sender, "help_rush")
        sendMessage(sender, "help_add")
        sendMessage(sender, "help_remove")
        return true
    }

    private fun getMsg(key: String, placeholders: Map<String, String> = emptyMap()): String {
        val prefix = module.config?.getString("messages.prefix", "&6[FP-Scheduler]") ?: "&6[FP-Scheduler]"
        var raw = module.config?.getString("messages.$key") ?: ""
        if (raw.isEmpty()) {
            raw = when (key) {
                "no_permission" -> "&cYou do not have permission to execute this command."
                "system_disabled_reload" -> "&cระบบยังคงปิดใช้งานอยู่ (ตรวจสอบ settings.enabled ในไฟล์คอนฟิก)"
                "system_enabled_reload_success" -> "&aเปิดการทำงานระบบบอทสำเร็จหลังรีโหลด!"
                "system_disabled" -> "&cระบบสเก็ตดูลเลอร์บอทถูกปิดใช้งานอยู่ในขณะนี้ (settings.enabled: false)"
                "system_enable_hint" -> "&7หากต้องการเปิดใช้งาน กรุณาแก้ไขไฟล์คอนฟิกแล้วพิมพ์ &e/fpscheduler reload"
                "reload_success" -> "&aรีโหลดฐานข้อมูลบอทและตารางใหม่สำเร็จ!"
                "reset_success" -> "&eสั่งเตะบอททั้งหมดเรียบร้อยแล้ว"
                "rush_usage" -> "&cวิธีใช้: /fpscheduler rush [on/off]"
                "rush_status" -> "&fโหมดช่วงเปิดเซิร์ฟใหม่ (Rush): %status%"
                "add_usage" -> "&cวิธีใช้: /fpscheduler add [ชื่อบอท] [ยศ]"
                "add_success" -> "&aเพิ่ม/อัปเดตบอท &e%name% &7(ยศ: %rank%) เรียบร้อย!"
                "remove_usage" -> "&cวิธีใช้: /fpscheduler remove [ชื่อบอท]"
                "bot_not_found" -> "&cไม่พบบอท &e%name% &cในระบบ"
                "remove_success" -> "&eลบบอท &f%name% &eออกจากระบบแล้ว"
                "status_title" -> "=== FakePlayer สถานะระบบ ==="
                "status_stats" -> "&eเป้าหมายชั่วโมงนี้: &a%base_target% ตัว &8| &eสุ่มจริง: &a%target% ตัว &8| &eออนไลน์: &a%current%/%target% ตัว"
                "status_info" -> "&eบอททั้งหมด: &b%total% ตัว &8| &eโหมด Rush: %rush%"
                "status_next_join" -> "&eคิวถัดไป: &aบอทเข้าในอีก %time% วินาที"
                "status_next_leave" -> "&eคิวถัดไป: &cบอทออกในอีก %time% วินาที"
                "status_list_title" -> "&eรายชื่อบอทที่ออนไลน์:"
                "status_list_title_page" -> "&eรายชื่อบอทที่ออนไลน์ (หน้า %page%/%total_pages%):"
                "status_list_empty" -> " &8- &7ไม่มีบอทออนไลน์ในขณะนี้"
                "status_list_item" -> " &7• &f%name% &a[%rank%] &8- &eเหลือเวลา %time%"
                "status_next_page" -> "&8» &7พิมพ์ &e/fpscheduler status %next_page% &7เพื่อดูหน้าถัดไป"
                "help_title" -> "=== FakePlayer สั่งการระบบ ==="
                "help_status" -> "&e/fpscheduler status &7- ดูสถานะการสุ่มบอทปัจจุบัน"
                "help_reload" -> "&e/fpscheduler reload &7- รีโหลดและเริ่มตารางสุ่มใหม่"
                "help_reset" -> "&e/fpscheduler reset &7- สั่งเตะบอททั้งหมดออก"
                "help_rush" -> "&e/fpscheduler rush [on/off] &7- เปิด/ปิดเร่งบอทช่วงเปิดเซิร์ฟ"
                "help_add" -> "&e/fpscheduler add [ชื่อ] [ยศ] &7- เพิ่มบอทลงระบบ"
                "help_remove" -> "&e/fpscheduler remove [ชื่อ] &7- ลบบอทออกจากระบบ"
                else -> ""
            }
        }
        var msg = raw.replace("%prefix%", prefix)
        placeholders.forEach { (k, v) -> msg = msg.replace(k, v) }
        return msg.replace("&", "§")
    }

    private fun sendMessage(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        val msg = getMsg(key, placeholders)
        if (msg.isNotEmpty()) {
            sender.sendMessage(msg)
        }
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
