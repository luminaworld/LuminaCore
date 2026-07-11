package core.luminaworld.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class DynamicCommand(
    name: String,
    description: String,
    usageMessage: String,
    aliases: List<String>,
    private val executor: CommandExecutor,
    private val completer: TabCompleter?
) : Command(name, description, usageMessage, aliases) {

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        return executor.onCommand(sender, this, commandLabel, args)
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
        return completer?.onTabComplete(sender, this, alias, args) ?: super.tabComplete(sender, alias, args)
    }
}
