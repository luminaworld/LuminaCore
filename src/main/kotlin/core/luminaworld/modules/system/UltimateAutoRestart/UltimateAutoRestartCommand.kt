package core.luminaworld.modules.system.UltimateAutoRestart

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.Locale

class UltimateAutoRestartCommand(private val module: UltimateAutoRestartModule) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // คำสั่งหลักที่ทุกคนพิมพ์ได้ (ดูสถานะการรีสตาร์ท)
        if (args.isEmpty() || args[0].equals("status", ignoreCase = true)) {
            val timeMs = module.restartTimeMs
            val now = System.currentTimeMillis()
            
            val formattedInterval = if (timeMs != null && !module.isStopped) {
                val remainingSecs = Math.max(0L, (timeMs - now) / 1000L)
                module.formatTimeRemaining(remainingSecs)
            } else {
                // ดึงค่าสำหรับกรณีไม่ได้เปิดใช้งานนับถอยหลัง
                module.uarConfig.getString("settings.unscheduledIntervalValue", "&f&lN/A") ?: "&f&lN/A"
            }

            val uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().uptime
            val formattedUptime = module.formatTimeRemaining(uptimeMs / 1000L)
            
            val reason = if (module.currentReason.isNotBlank()) module.currentReason else module.defaultReason

            val placeholders = mapOf(
                "{0}" to formattedInterval,
                "{1}" to formattedUptime,
                "{2}" to reason
            )

            module.sendFormattedMessageToSender(sender, "COMMAND_USER_VIEWINTERVAL", placeholders)
            module.playSound("COMMAND_USER_VIEWINTERVAL", sender as? org.bukkit.entity.Player)
            return true
        }

        // เช็คสิทธิ์แอดมินสำหรับคำสั่งอื่นๆ
        if (!sender.isOp && !sender.hasPermission("uar.admin") && !sender.hasPermission("luminacore.admin")) {
            module.sendFormattedMessageToSender(sender, "COMMAND_NOPERMISSION", emptyMap())
            module.playSound("COMMAND_NOPERMISSION", sender as? org.bukkit.entity.Player)
            return true
        }

        val sub = args[0].lowercase(Locale.ROOT)
        when (sub) {
            "force", "now" -> {
                val seconds: Int
                val reason: String
                
                if (sub == "now") {
                    seconds = 0
                    reason = if (args.size > 1) args.drop(1).joinToString(" ") else module.defaultReason
                } else {
                    if (args.size > 1) {
                        val secArg = args[1]
                        val parsedSec = secArg.toIntOrNull()
                        if (parsedSec != null) {
                            seconds = parsedSec
                            reason = if (args.size > 2) args.drop(2).joinToString(" ") else module.defaultReason
                        } else if (secArg.equals("now", ignoreCase = true)) {
                            seconds = 0
                            reason = if (args.size > 2) args.drop(2).joinToString(" ") else module.defaultReason
                        } else {
                            // กรณีอาร์กิวเมนต์แรกเป็นข้อความเหตุผลเลย ให้ถือว่ารีสตาร์ททันที (0 วินาที)
                            seconds = 0
                            reason = args.drop(1).joinToString(" ")
                        }
                    } else {
                        seconds = 0
                        reason = module.defaultReason
                    }
                }

                if (seconds < 0) {
                    val placeholders = mapOf("{0}" to (args.getOrNull(1) ?: ""))
                    module.sendFormattedMessageToSender(sender, "COMMAND_FORCE_RESTART_INVALID", placeholders)
                    module.playSound("COMMAND_FORCE_RESTART_INVALID", sender as? org.bukkit.entity.Player)
                    return true
                }

                // สั่งบังคับรีสตาร์ท
                module.restartTimeMs = System.currentTimeMillis() + (seconds * 1000L)
                module.currentReason = reason
                module.isStopped = false
                module.isDelayed = false

                val formattedTime = module.formatTimeRemaining(seconds.toLong())
                val placeholders = mapOf(
                    "{0}" to formattedTime,
                    "{1}" to sender.name,
                    "{2}" to reason
                )

                // ส่งการแจ้งเตือน
                if (reason == module.defaultReason) {
                    module.sendFormattedMessageToSender(sender, "COMMAND_FORCE_RESTART", placeholders)
                    module.sendFormattedMessage("COMMAND_FORCE_RESTART_GLOBAL", placeholders)
                } else {
                    module.sendFormattedMessageToSender(sender, "COMMAND_FORCE_RESTART_WITH_REASON", placeholders)
                    module.sendFormattedMessage("COMMAND_FORCE_RESTART_GLOBAL_WITH_REASON", placeholders)
                }

                module.playSound("COMMAND_FORCE_RESTART")
                
                // ยิง Discord Webhook
                module.sendDiscordWebhook("SERVER_FORCED_RESTART", mapOf(
                    "{TIMESTAMP}" to module.getWebhookTime(),
                    "{SECONDS}" to seconds.toString(),
                    "{FORMATTED}" to formattedTime,
                    "{REASON}" to reason
                ))
                return true
            }
            "delay" -> {
                val timeMs = module.restartTimeMs
                if (timeMs == null || module.isStopped) {
                    module.sendFormattedMessageToSender(sender, "COMMAND_DELAY_RESTART_NOT_POSSIBLE", emptyMap())
                    module.playSound("COMMAND_DELAY_RESTART_NOT_POSSIBLE", sender as? org.bukkit.entity.Player)
                    return true
                }

                if (args.size < 2) {
                    val placeholders = mapOf("{0}" to "")
                    module.sendFormattedMessageToSender(sender, "COMMAND_DELAY_RESTART_INVALID", placeholders)
                    module.playSound("COMMAND_DELAY_RESTART_INVALID", sender as? org.bukkit.entity.Player)
                    return true
                }

                val seconds = args[1].toLongOrNull()
                if (seconds == null || seconds <= 0) {
                    val placeholders = mapOf("{0}" to args[1])
                    module.sendFormattedMessageToSender(sender, "COMMAND_DELAY_RESTART_INVALID", placeholders)
                    module.playSound("COMMAND_DELAY_RESTART_INVALID", sender as? org.bukkit.entity.Player)
                    return true
                }

                // เพิ่มเวลาการรีสตาร์ท
                module.restartTimeMs = timeMs + (seconds * 1000L)
                module.isDelayed = true // มาร์คไว้ว่าถูกเลื่อนแล้วเพื่อกันเช็คลูปสแปม

                val now = System.currentTimeMillis()
                val totalRemaining = Math.max(0L, (module.restartTimeMs!! - now) / 1000L)
                val formattedTotal = module.formatTimeRemaining(totalRemaining)
                
                val placeholders = mapOf("{0}" to formattedTotal)
                
                module.sendFormattedMessageToSender(sender, "COMMAND_DELAY_RESTART", placeholders)
                module.playSound("COMMAND_DELAY_RESTART", sender as? org.bukkit.entity.Player)
                
                module.sendFormattedMessage("COMMAND_DELAY_RESTART_GLOBAL", placeholders)
                module.playSound("COMMAND_DELAY_RESTART_GLOBAL")
                return true
            }
            "stop" -> {
                val timeMs = module.restartTimeMs
                if (timeMs == null || module.isStopped) {
                    module.sendFormattedMessageToSender(sender, "COMMAND_STOP_RESTART_FAIL", emptyMap())
                    module.playSound("COMMAND_STOP_RESTART_FAIL", sender as? org.bukkit.entity.Player)
                    return true
                }

                // หยุดการรีสตาร์ท
                module.restartTimeMs = null
                module.isStopped = true

                module.sendFormattedMessageToSender(sender, "COMMAND_STOP_RESTART", emptyMap())
                module.playSound("COMMAND_STOP_RESTART", sender as? org.bukkit.entity.Player)
                
                module.sendFormattedMessage("COMMAND_STOP_RESTART_GLOBAL", emptyMap())
                module.playSound("COMMAND_STOP_RESTART_GLOBAL")
                return true
            }
            "reload" -> {
                val startTime = System.currentTimeMillis()
                module.reload()
                val timeTaken = System.currentTimeMillis() - startTime

                val placeholders = mapOf("{0}" to timeTaken.toString())
                module.sendFormattedMessageToSender(sender, "COMMAND_RELOAD", placeholders)
                module.playSound("COMMAND_RELOAD", sender as? org.bukkit.entity.Player)
                return true
            }
            "debug" -> {
                if (args.size > 1 && args[1].equals("webhook", ignoreCase = true)) {
                    val placeholders = mapOf(
                        "{TIMESTAMP}" to module.getWebhookTime(),
                        "{SECONDS}" to "60",
                        "{FORMATTED}" to module.formatTimeRemaining(60),
                        "{REASON}" to "Debug test webhook"
                    )
                    module.sendDiscordWebhook("SERVER_FORCED_RESTART", placeholders)
                    
                    val placeholdersSuccess = mapOf("{0}" to "Webhook")
                    module.sendFormattedMessageToSender(sender, "COMMAND_DEBUG_SUCCESS", placeholdersSuccess)
                    module.playSound("COMMAND_DEBUG_SUCCESS", sender as? org.bukkit.entity.Player)
                } else {
                    val placeholdersInvalid = mapOf("{0}" to (args.getOrNull(1) ?: ""))
                    module.sendFormattedMessageToSender(sender, "COMMAND_DEBUG_INVALID", placeholdersInvalid)
                    module.playSound("COMMAND_DEBUG_INVALID", sender as? org.bukkit.entity.Player)
                }
                return true
            }
        }

        sender.sendMessage("§6§l=== UltimateAutoRestart Admin Menu ===")
        sender.sendMessage("§e/uar status §7- ดูรายละเอียดสถานะและเวลารีสตาร์ท")
        sender.sendMessage("§e/uar force [วินาที] [เหตุผล] §7- บังคับนับถอยหลังรีสตาร์ท (เว้นไว้เพื่อรีสตาร์ททันที)")
        sender.sendMessage("§e/uar now [เหตุผล] §7- บังคับรีสตาร์ทเซิร์ฟเวอร์ทันที")
        sender.sendMessage("§e/uar delay [วินาที] §7- เลื่อนเวลาการรีสตาร์ทออกไป")
        sender.sendMessage("§e/uar stop §7- ยกเลิกการรีสตาร์ทที่ตั้งตารางเวลาไว้")
        sender.sendMessage("§e/uar reload §7- รีโหลดค่าตั้งค่าทั้งหมด")
        sender.sendMessage("§e/uar debug webhook §7- ทดสอบส่ง Webhook ไปยัง Discord")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val isAdmin = sender.isOp || sender.hasPermission("uar.admin") || sender.hasPermission("luminacore.admin")
        
        if (args.size == 1) {
            val subs = if (isAdmin) {
                listOf("status", "force", "now", "delay", "stop", "reload", "debug")
            } else {
                listOf("status")
            }
            return subs.filter { it.startsWith(args[0].lowercase(Locale.ROOT)) }
        }
        
        if (isAdmin && args.size == 2) {
            when (args[0].lowercase(Locale.ROOT)) {
                "debug" -> return listOf("webhook").filter { it.startsWith(args[1].lowercase(Locale.ROOT)) }
                "force" -> return listOf("now", "10", "30", "60", "300").filter { it.startsWith(args[1].lowercase(Locale.ROOT)) }
                "delay" -> return listOf("60", "300", "600", "1200").filter { it.startsWith(args[1].lowercase(Locale.ROOT)) }
            }
        }
        
        return emptyList()
    }
}
