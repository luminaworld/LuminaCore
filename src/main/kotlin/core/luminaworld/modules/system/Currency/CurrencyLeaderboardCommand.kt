package core.luminaworld.modules.system.Currency

import core.luminaworld.utils.ColorParser
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class CurrencyLeaderboardCommand(private val module: CurrencyModule) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        val currencyId = args[0]
        val currency = module.service.getCurrency(currencyId) ?: run {
            send(sender, "unknown-currency")
            return true
        }

        // ตรวจสอบสิทธิ์ในการดูอันดับมหาเศรษฐี (Leaderboard Permission)
        if (currency.permissionLeaderboardRequire && !currency.permissionLeaderboard.isNullOrBlank()) {
            if (!sender.hasPermission(currency.permissionLeaderboard) && !sender.isOp) {
                send(sender, "no-permission")
                return true
            }
        }

        val leaderboard = module.service.leaderboard(currency.id)
        if (leaderboard.isEmpty()) {
            send(sender, "leaderboard-empty")
            return true
        }

        // แสดงผลลัพธ์หัวข้อการจัดอันดับ
        val header = module.message("leaderboard-header", mapOf(
            "currency" to currency.displayName,
            "limit" to leaderboard.size.toString()
        ))
        module.plugin.server.globalRegionScheduler.execute(module.plugin) {
            sender.sendMessage(ColorParser.parse(header))
        }

        // แสดงผลแต่ละอันดับ (แสดงสูงสุด 10 อันดับแรก)
        leaderboard.take(10).forEachIndexed { index, entry ->
            val row = module.message("leaderboard-format", mapOf(
                "index" to (index + 1).toString(),
                "player" to entry.playerName,
                "amount" to currency.format(entry.amount),
                "amount_short" to currency.formatShort(entry.amount),
                "amount_raw" to entry.amount.toString()
            ))
            module.plugin.server.globalRegionScheduler.execute(module.plugin) {
                sender.sendMessage(ColorParser.parse(row))
            }
        }

        return true
    }

    private fun sendUsage(sender: CommandSender) {
        val usage = ColorParser.parse("&eรูปแบบการใช้งาน: /${module.leaderboardCommandName()} <สกุลเงิน>")
        module.plugin.server.globalRegionScheduler.execute(module.plugin) {
            sender.sendMessage(usage)
        }
    }

    private fun send(sender: CommandSender, key: String) {
        module.plugin.server.globalRegionScheduler.execute(module.plugin) {
            sender.sendMessage(ColorParser.parse(module.message(key)))
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()
        val lastArg = args.last()

        val list = when (args.size) {
            1 -> {
                // เสนอรายชื่อสกุลเงินที่ผู้เล่นมีสิทธิ์เปิดดูตารางอันดับได้
                module.service.currencies()
                    .filter { !it.permissionLeaderboardRequire || it.permissionLeaderboard.isNullOrBlank() || sender.hasPermission(it.permissionLeaderboard) || sender.isOp }
                    .map { it.id }
            }
            else -> emptyList()
        }
        return list.filter { it.startsWith(lastArg, ignoreCase = true) }
    }
}
