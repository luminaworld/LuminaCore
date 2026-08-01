package core.luminaworld.modules.system.Currency

import core.luminaworld.utils.ColorParser
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class CurrencyBalanceCommand(private val module: CurrencyModule) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val target: org.bukkit.OfflinePlayer?
        val currencyId: String?

        if (args.size == 1) {
            if (sender !is Player) {
                send(sender, "balance-usage")
                return true
            }
            target = sender
            currencyId = args[0]
            
            val currency = currencyId.let(module.service::getCurrency) ?: run {
                send(sender, "balance-usage")
                return true
            }
            
            // 1. ตรวจสอบสิทธิ์การตรวจสอบยอดเงินของตนเอง (Balance Self)
            if (currency.permissionBalanceRequire && !currency.permissionBalance.isNullOrBlank()) {
                if (!sender.hasPermission(currency.permissionBalance) && !sender.isOp) {
                    send(sender, "no-permission")
                    return true
                }
            }
        } else if (args.size >= 2) {
            target = Bukkit.getOfflinePlayer(args[0])
            currencyId = args[1]
            
            val currency = currencyId.let(module.service::getCurrency) ?: run {
                send(sender, "balance-usage")
                return true
            }
            
            // 2. ตรวจสอบสิทธิ์การตรวจสอบยอดเงินของผู้อื่น (Balance Other)
            if (currency.permissionBalanceOtherRequire && !currency.permissionBalanceOther.isNullOrBlank()) {
                if (!sender.hasPermission(currency.permissionBalanceOther) && !sender.isOp) {
                    send(sender, "no-permission")
                    return true
                }
            }
        } else {
            send(sender, "balance-usage")
            return true
        }

        val currency = currencyId.let(module.service::getCurrency)
        if (currency == null) {
            send(sender, "balance-usage")
            return true
        }

        val targetName = target.name ?: target.uniqueId.toString()
        module.service.loadBalance(CurrencyPlayer(target.uniqueId, targetName), currency.id) { balance ->
            send(sender, "balance", mapOf(
                "player" to targetName,
                "currency" to currency.displayName,
                "amount" to currency.format(balance)
            ))
        }
        return true
    }

    private fun send(sender: CommandSender, key: String, replacements: Map<String, String> = emptyMap()) {
        module.plugin.server.globalRegionScheduler.execute(module.plugin) {
            sender.sendMessage(ColorParser.parse(module.message(key, replacements)))
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()
        val lastArg = args.last()

        val list = when (args.size) {
            1 -> {
                // กรองเฉพาะสกุลเงินที่ผู้เล่นมีสิทธิ์ดูยอดเงินตนเองได้
                val currencies = module.service.currencies()
                    .filter { !it.permissionBalanceRequire || it.permissionBalance.isNullOrBlank() || sender.hasPermission(it.permissionBalance) || sender.isOp }
                    .map { it.id }
                
                // สำหรับรายชื่อผู้เล่นออนไลน์ (จะแนะนำเฉพาะกรณีที่ผู้เล่นมีสิทธิ์ดูยอดเงินผู้อื่นของสกุลเงินใดสักตัวหนึ่ง)
                val hasAnyOtherPermission = module.service.currencies().any { 
                    !it.permissionBalanceOtherRequire || it.permissionBalanceOther.isNullOrBlank() || sender.hasPermission(it.permissionBalanceOther) || sender.isOp
                }
                val players = if (hasAnyOtherPermission) Bukkit.getOnlinePlayers().map { it.name } else emptyList()
                
                currencies + players
            }
            2 -> {
                val firstArg = args[0]
                val isCurrency = module.service.getCurrency(firstArg) != null
                if (isCurrency) {
                    emptyList()
                } else {
                    // หากพิมพ์ argument แรกเป็นชื่อผู้เล่น (เพื่อตรวจยอดเงินคนอื่น) ให้ตรวจสอบและแสดงเฉพาะสกุลเงินที่มีสิทธิ์ดูยอดเงินคนอื่น
                    module.service.currencies()
                        .filter { !it.permissionBalanceOtherRequire || it.permissionBalanceOther.isNullOrBlank() || sender.hasPermission(it.permissionBalanceOther) || sender.isOp }
                        .map { it.id }
                }
            }
            else -> emptyList()
        }
        return list.filter { it.startsWith(lastArg, ignoreCase = true) }
    }
}
