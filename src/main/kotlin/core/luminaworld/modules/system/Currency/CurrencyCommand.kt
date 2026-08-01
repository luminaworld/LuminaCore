package core.luminaworld.modules.system.Currency

import core.luminaworld.utils.ColorParser
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class CurrencyCommand(private val module: CurrencyModule, private val fixedCurrency: String? = null) : CommandExecutor, TabCompleter {
    fun forCurrency(currency: String) = CurrencyCommand(module, currency)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (fixedCurrency != null) return pay(sender, fixedCurrency, args.getOrNull(0), args.getOrNull(1))
        if (args.isEmpty()) return help(sender)
        return when (args[0].lowercase()) {
            "help" -> help(sender)
            "balance", "bal" -> balance(sender, args.drop(1))
            "admin" -> admin(sender, args.drop(1))
            else -> pay(sender, args.getOrNull(1), args[0], args.getOrNull(2))
        }
    }

    private fun pay(sender: CommandSender, currencyId: String?, recipientName: String?, amount: String?): Boolean {
        if (sender !is Player) { send(sender, "players-only"); return true }
        val currency = currencyId?.let(module.service::getCurrency) ?: run { send(sender, "unknown-currency"); return true }
        if (!sender.hasPermission("luminacore.currency.transfer.${currency.id}") && !sender.hasPermission("luminacore.currency.transfer.*") && !sender.isOp) { send(sender, "no-permission"); return true }
        val resolvedRecipient = recipientName ?: run { send(sender, "recipient-offline"); return true }
        val onlineTarget = Bukkit.getPlayerExact(resolvedRecipient)
        if (onlineTarget == null && module.requireRecipientOnline()) { send(sender, "recipient-offline"); return true }
        val target = onlineTarget ?: Bukkit.getOfflinePlayer(resolvedRecipient)
        val targetName = target.name ?: resolvedRecipient
        module.service.transfer(CurrencyPlayer(sender.uniqueId, sender.name), CurrencyPlayer(target.uniqueId, targetName), currency.id, amount ?: "") { result ->
            when (result.status) {
                CurrencyResult.SUCCESS -> {
                    send(sender, "transfer-sent", mapOf("player" to targetName, "currency" to currency.displayName, "amount" to currency.format(currency.parseAmount(amount ?: "") ?: 0)))
                    if (onlineTarget != null) send(onlineTarget, "transfer-received", mapOf("player" to sender.name, "currency" to currency.displayName, "amount" to currency.format(currency.parseAmount(amount ?: "") ?: 0)))
                }
                CurrencyResult.INSUFFICIENT_FUNDS -> send(sender, "insufficient-funds")
                CurrencyResult.TRANSFER_DISABLED -> send(sender, "transfer-disabled")
                CurrencyResult.SELF_TRANSFER -> send(sender, "self-transfer")
                CurrencyResult.LIMIT_EXCEEDED -> send(sender, "transfer-limit")
                CurrencyResult.INVALID_AMOUNT -> send(sender, "invalid-amount")
                else -> send(sender, "transaction-failed")
            }
        }
        return true
    }

    private fun balance(sender: CommandSender, args: List<String>): Boolean {
        if (!sender.hasPermission("luminacore.currency.balance") && !sender.isOp) { send(sender, "no-permission"); return true }
        val target = if (args.isEmpty() && sender is Player) sender else args.firstOrNull()?.let(Bukkit::getOfflinePlayer)
        val currency = args.getOrNull(if (sender is Player && args.size == 1) 0 else 1)?.let(module.service::getCurrency)
        if (target == null || currency == null) { send(sender, "balance-usage"); return true }
        module.service.loadBalance(CurrencyPlayer(target.uniqueId, target.name ?: target.uniqueId.toString()), currency.id) { balance ->
            send(sender, "balance", mapOf("player" to (target.name ?: target.uniqueId.toString()), "currency" to currency.displayName, "amount" to currency.format(balance)))
        }
        return true
    }

    private fun admin(sender: CommandSender, args: List<String>): Boolean {
        if (!sender.hasPermission("luminacore.currency.admin") && !sender.isOp) { send(sender, "no-permission"); return true }
        if (args.size < 4 || args[0].lowercase() !in setOf("give", "take", "set")) { send(sender, "admin-usage"); return true }
        val operation = args[0].lowercase(); val targetArg = args[1]; val currency = module.service.getCurrency(args[2]) ?: run { send(sender, "unknown-currency"); return true }
        val targets = resolveAdminTargets(targetArg)
        if (targets.isEmpty()) { send(sender, "no-targets"); return true }
        targets.forEach { target ->
            val callback: (CurrencyOperationResult) -> Unit = { result -> if (result.status == CurrencyResult.SUCCESS) send(sender, "admin-success", mapOf("operation" to operation, "player" to target.name, "currency" to currency.displayName, "amount" to currency.format(currency.parseAmount(args[3]) ?: 0))) else send(sender, "transaction-failed") }
            when (operation) { "give" -> module.service.give(target, currency.id, args[3], (sender as? Player)?.uniqueId, "admin-command", callback); "take" -> module.service.take(target, currency.id, args[3], (sender as? Player)?.uniqueId, "admin-command", callback); else -> module.service.set(target, currency.id, args[3], (sender as? Player)?.uniqueId, "admin-command", callback) }
        }
        return true
    }

    /** -all uses everyone known to Bukkit, while -allofline deliberately excludes online players. */
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

    private fun help(sender: CommandSender): Boolean { send(sender, "help"); return true }
    private fun send(sender: CommandSender, key: String, replacements: Map<String, String> = emptyMap()) {
        module.plugin.server.globalRegionScheduler.execute(module.plugin) { sender.sendMessage(ColorParser.parse(module.message(key, replacements))) }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (fixedCurrency != null) return when (args.size) { 1 -> Bukkit.getOnlinePlayers().map { it.name }; else -> emptyList() }
        return when (args.size) {
            1 -> listOf("balance", "help", "admin") + Bukkit.getOnlinePlayers().map { it.name }
            2 -> if (args[0].equals("admin", true)) listOf("give", "take", "set") else module.service.currencies().map { it.id }
            3 -> if (args[0].equals("admin", true)) Bukkit.getOnlinePlayers().map { it.name } + listOf("-all", "-allonline", "-allofline") else emptyList()
            4 -> if (args[0].equals("admin", true)) module.service.currencies().map { it.id } else emptyList()
            else -> emptyList()
        }
    }
}
