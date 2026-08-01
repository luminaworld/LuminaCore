package core.luminaworld.modules.system.Currency

import core.luminaworld.utils.ColorParser
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class CurrencyPayCommand(private val module: CurrencyModule, private val fixedCurrency: String? = null) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (fixedCurrency != null) {
            if (args.size < 2) {
                send(sender, "help")
                return true
            }
            return pay(sender, fixedCurrency, args[0], args[1])
        }
        if (args.isEmpty() || args[0].equalsIgnoreCase("help")) {
            return help(sender)
        }
        if (args.size < 3) {
            return help(sender)
        }
        return pay(sender, args[1], args[0], args[2])
    }

    private fun pay(sender: CommandSender, currencyId: String?, recipientName: String?, amount: String?): Boolean {
        if (sender !is Player) { send(sender, "players-only"); return true }
        
        val currency = currencyId?.let(module.service::getCurrency) ?: run { send(sender, "unknown-currency"); return true }
        
        // 1. ตรวจสอบสิทธิ์โอนเงิน (Transfer Permission) ของผู้โอน
        if (currency.permissionTransferRequire && !currency.permissionTransfer.isNullOrBlank()) {
            if (!sender.hasPermission(currency.permissionTransfer) && !sender.isOp) {
                send(sender, "no-permission")
                return true
            }
        }
        
        val resolvedRecipient = recipientName ?: run { send(sender, "recipient-offline"); return true }
        val onlineTarget = Bukkit.getPlayerExact(resolvedRecipient)
        if (onlineTarget == null && module.requireRecipientOnline()) { send(sender, "recipient-offline"); return true }
        val target = onlineTarget ?: Bukkit.getOfflinePlayer(resolvedRecipient)
        val targetName = target.name ?: resolvedRecipient
        
        // 2. ตรวจสอบสิทธิ์รับเงิน (Receive Permission) ของผู้รับ (ถ้าผู้รับออนไลน์อยู่)
        if (currency.permissionReceiveRequire && !currency.permissionReceive.isNullOrBlank()) {
            if (onlineTarget != null && !onlineTarget.hasPermission(currency.permissionReceive) && !onlineTarget.isOp) {
                send(sender, "recipient-no-permission", mapOf("player" to targetName))
                return true
            }
        }
        
        module.service.transfer(CurrencyPlayer(sender.uniqueId, sender.name), CurrencyPlayer(target.uniqueId, targetName), currency.id, amount ?: "") { result ->
            when (result.status) {
                CurrencyResult.SUCCESS -> {
                    val amountVal = currency.parseAmount(amount ?: "") ?: 0L
                    val formattedAmount = currency.format(amountVal)
                    
                    val senderType = module.getNotificationType("settings.notifications.player-transfer.sender", NotificationType.CHAT)
                    val senderMsg = module.message("transfer-sent", mapOf(
                        "player" to targetName,
                        "currency" to currency.displayName,
                        "amount" to formattedAmount
                    ))
                    module.sendNotification(sender, senderMsg, senderType)
                    
                    if (onlineTarget != null) {
                        val receiverType = module.getNotificationType("settings.notifications.player-transfer.receiver", NotificationType.CHAT)
                        val receiverMsg = module.message("transfer-received", mapOf(
                            "player" to sender.name,
                            "currency" to currency.displayName,
                            "amount" to formattedAmount
                        ))
                        module.sendNotification(onlineTarget, receiverMsg, receiverType)
                    }
                }
                CurrencyResult.INSUFFICIENT_FUNDS -> send(sender, "insufficient-funds")
                CurrencyResult.TRANSFER_DISABLED -> send(sender, "transfer-disabled")
                CurrencyResult.SELF_TRANSFER -> send(sender, "self-transfer")
                CurrencyResult.LIMIT_EXCEEDED -> {
                    val amountVal = currency.parseAmount(amount ?: "") ?: 0L
                    if (currency.maximumTransfer > 0L && amountVal > currency.maximumTransfer) {
                        send(sender, "transfer-limit")
                    } else {
                        send(sender, "balance-limit-exceeded", mapOf("limit" to currency.format(currency.maximumBalance)))
                    }
                }
                CurrencyResult.INVALID_AMOUNT -> send(sender, "invalid-amount")
                else -> send(sender, "transaction-failed")
            }
        }
        return true
    }

    private fun help(sender: CommandSender): Boolean { send(sender, "help"); return true }
    
    private fun send(sender: CommandSender, key: String, replacements: Map<String, String> = emptyMap()) {
        module.plugin.server.globalRegionScheduler.execute(module.plugin) { sender.sendMessage(ColorParser.parse(module.message(key, replacements))) }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()
        val lastArg = args.last()

        val list = if (fixedCurrency != null) {
            // หากระบบตั้งค่า require permission โอนสกุลเงินนี้ และผู้เล่นไม่มีสิทธิ์
            if (fixedCurrency.let(module.service::getCurrency)?.let { it.permissionTransferRequire && !it.permissionTransfer.isNullOrBlank() && !sender.hasPermission(it.permissionTransfer) && !sender.isOp } == true) {
                return emptyList()
            }
            when (args.size) {
                1 -> Bukkit.getOnlinePlayers().map { it.name }
                else -> emptyList()
            }
        } else {
            when (args.size) {
                1 -> Bukkit.getOnlinePlayers().map { it.name }
                2 -> module.service.currencies()
                    .filter { !it.permissionTransferRequire || it.permissionTransfer.isNullOrBlank() || sender.hasPermission(it.permissionTransfer) || sender.isOp }
                    .map { it.id }
                else -> emptyList()
            }
        }
        return list.filter { it.startsWith(lastArg, ignoreCase = true) }
    }
}

private fun String.equalsIgnoreCase(other: String): Boolean = this.equals(other, ignoreCase = true)
