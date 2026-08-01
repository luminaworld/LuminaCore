package core.luminaworld.modules.system.Currency

import core.luminaworld.utils.ColorParser
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class CurrencyAdminCommand(private val module: CurrencyModule) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // ตรวจสอบสิทธิ์ผู้ดูแลระบบกลางจาก settings
        val adminPerm = module.adminPermissionName()
        if (!sender.hasPermission(adminPerm) && !sender.isOp) {
            send(sender, "no-permission")
            return true
        }
        if (args.size < 4 || args[0].lowercase() !in setOf("give", "take", "set")) {
            send(sender, "admin-usage")
            return true
        }

        val operation = args[0].lowercase()
        val targetArg = args[1]
        val currency = module.service.getCurrency(args[2]) ?: run {
            send(sender, "unknown-currency")
            return true
        }

        val targets = resolveAdminTargets(targetArg)
        if (targets.isEmpty()) {
            send(sender, "no-targets")
            return true
        }

        if (targets.size == 1) {
            val target = targets.first()
            val callback: (CurrencyOperationResult) -> Unit = { result ->
                when (result.status) {
                    CurrencyResult.SUCCESS -> {
                        val amountVal = currency.parseAmount(args[3]) ?: 0L
                        send(sender, "admin-success", mapOf(
                            "operation" to operation,
                            "player" to target.name,
                            "currency" to currency.displayName,
                            "amount" to currency.format(amountVal)
                        ))
                        notifyTarget(sender, target, currency, amountVal, operation)
                    }
                    CurrencyResult.LIMIT_EXCEEDED -> send(sender, "balance-limit-exceeded", mapOf("limit" to currency.format(currency.maximumBalance)))
                    CurrencyResult.INSUFFICIENT_FUNDS -> send(sender, "insufficient-funds")
                    else -> send(sender, "transaction-failed")
                }
            }
            val amountStr = args[3]
            when (operation) {
                "give" -> module.service.give(target, currency.id, amountStr, (sender as? Player)?.uniqueId, "admin-command", callback)
                "take" -> module.service.take(target, currency.id, amountStr, (sender as? Player)?.uniqueId, "admin-command", callback)
                else -> module.service.set(target, currency.id, amountStr, (sender as? Player)?.uniqueId, "admin-command", callback)
            }
        } else {
            val amountVal = currency.parseAmount(args[3]) ?: 0L
            val amountStr = args[3]
            val processed = java.util.concurrent.atomic.AtomicInteger(0)
            val successCount = java.util.concurrent.atomic.AtomicInteger(0)
            val failedCount = java.util.concurrent.atomic.AtomicInteger(0)

            targets.forEach { target ->
                val callback: (CurrencyOperationResult) -> Unit = { result ->
                    if (result.status == CurrencyResult.SUCCESS) {
                        successCount.incrementAndGet()
                        notifyTarget(sender, target, currency, amountVal, operation)
                    } else {
                        failedCount.incrementAndGet()
                    }
                    
                    if (processed.incrementAndGet() == targets.size) {
                        val succ = successCount.get()
                        val fail = failedCount.get()
                        if (succ > 0) {
                            send(sender, "admin-success-bulk", mapOf(
                                "operation" to operation,
                                "count" to succ.toString(),
                                "currency" to currency.displayName,
                                "amount" to currency.format(amountVal)
                            ))
                        }
                        if (fail > 0) {
                            send(sender, "transaction-failed-bulk", mapOf(
                                "count" to fail.toString()
                            ))
                        }
                    }
                }
                when (operation) {
                    "give" -> module.service.give(target, currency.id, amountStr, (sender as? Player)?.uniqueId, "admin-command", callback)
                    "take" -> module.service.take(target, currency.id, amountStr, (sender as? Player)?.uniqueId, "admin-command", callback)
                    else -> module.service.set(target, currency.id, amountStr, (sender as? Player)?.uniqueId, "admin-command", callback)
                }
            }
        }
        return true
    }

    private fun resolveAdminTargets(target: String): List<CurrencyPlayer> {
        val online = Bukkit.getOnlinePlayers().map { CurrencyPlayer(it.uniqueId, it.name) }
        val offline = Bukkit.getOfflinePlayers()
            .filter { !it.isOnline }
            .map { CurrencyPlayer(it.uniqueId, it.name ?: it.uniqueId.toString()) }
        return when (target.lowercase()) {
            "-allonline" -> online
            "-allofline", "-alloffline" -> offline
            "-all" -> (offline + online).distinctBy { it.uuid }
            else -> listOf(Bukkit.getOfflinePlayer(target)).map { CurrencyPlayer(it.uniqueId, it.name ?: target) }
        }
    }

    private fun send(sender: CommandSender, key: String, replacements: Map<String, String> = emptyMap()) {
        module.plugin.server.globalRegionScheduler.execute(module.plugin) {
            sender.sendMessage(ColorParser.parse(module.message(key, replacements)))
        }
    }

    private fun notifyTarget(sender: CommandSender, target: CurrencyPlayer, currency: CurrencyDefinition, amountVal: Long, operation: String) {
        val onlineTarget = Bukkit.getPlayer(target.uuid) ?: return
        
        val isConsole = sender !is Player
        val configPath = if (isConsole) "settings.notifications.console-action.target" else "settings.notifications.admin-action.target"
        val notificationType = module.getNotificationType(configPath, if (isConsole) NotificationType.ACTION_BAR else NotificationType.CHAT)
        
        val adminName = if (isConsole) "Console" else sender.name
        val msgKey = when (operation) {
            "give" -> "admin-give-received"
            "take" -> "admin-take-received"
            else -> "admin-set-received"
        }
        
        val targetMsg = module.message(msgKey, mapOf(
            "admin" to adminName,
            "currency" to currency.displayName,
            "amount" to currency.format(amountVal)
        ))
        
        module.sendNotification(onlineTarget, targetMsg, notificationType)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()
        val lastArg = args.last()
        
        val adminPerm = module.adminPermissionName()
        if (!sender.hasPermission(adminPerm) && !sender.isOp) return emptyList()

        val list = when (args.size) {
            1 -> listOf("give", "take", "set")
            2 -> Bukkit.getOnlinePlayers().map { it.name } + listOf("-all", "-allonline", "-alloffline", "-allofline")
            3 -> module.service.currencies().map { it.id }
            else -> emptyList()
        }
        return list.filter { it.startsWith(lastArg, ignoreCase = true) }
    }
}
