package core.luminaworld.modules.system.Currency

import core.luminaworld.LuminaCore
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.command.SimpleCommandMap
import org.bukkit.command.TabCompleter
import io.papermc.paper.threadedregions.scheduler.ScheduledTask

/** Commands defined by a currency belong to this module, not CommandManager. */
class CurrencyCommandRegistry(private val plugin: LuminaCore) {
    private val commands = mutableMapOf<String, CurrencyDynamicCommand>()
    private var syncTask: ScheduledTask? = null
    private val map: CommandMap by lazy {
        val server = Bukkit.getServer()
        val getter = server.javaClass.getDeclaredMethod("getCommandMap")
        getter.isAccessible = true
        getter.invoke(server) as CommandMap
    }

    @Suppress("UNCHECKED_CAST")
    private fun knownCommands(): MutableMap<String, Command>? = try {
        val field = SimpleCommandMap::class.java.getDeclaredField("knownCommands")
        field.isAccessible = true
        field.get(map) as? MutableMap<String, Command>
    } catch (e: Exception) { plugin.logger.warning("[Currency] Cannot inspect command registry: ${e.message}"); null }

    fun register(name: String, aliases: List<String>, executor: CommandExecutor, completer: TabCompleter): Boolean {
        val normalized = name.lowercase()
        if (normalized in commands || map.getCommand(normalized) != null) {
            plugin.logger.warning("[Currency] Skipped /$name because the main command conflicts with an existing command.")
            return false
        }
        val safeAliases = aliases.filter { alias ->
            val aliasLower = alias.lowercase()
            val existing = map.getCommand(aliasLower)
            if (existing != null) {
                plugin.logger.warning("[Currency] Alias /$aliasLower for /$name was skipped because it conflicts with an existing command.")
                false
            } else {
                true
            }
        }
        val command = CurrencyDynamicCommand(normalized, safeAliases, executor, completer)
        map.register(plugin.description.name.lowercase(), command)
        commands[normalized] = command
        return true
    }

    fun unregisterAll() {
        val known = knownCommands()
        commands.forEach { (name, command) ->
            val keys = listOf(name, "${plugin.description.name.lowercase()}:$name") + command.aliases.flatMap { listOf(it.lowercase(), "${plugin.description.name.lowercase()}:${it.lowercase()}") }
            keys.forEach { key -> if (known?.get(key) === command) known.remove(key) }
            command.unregister(map)
        }
        commands.clear()
    }

    fun sync() {
        syncTask?.cancel()
        syncTask = plugin.server.globalRegionScheduler.runDelayed(plugin, { _ ->
            try {
                Bukkit.getServer().javaClass.getMethod("syncCommands").invoke(Bukkit.getServer())
            } catch (_: Exception) { }
            syncTask = null
        }, 1L)
    }
}

private class CurrencyDynamicCommand(name: String, aliases: List<String>, private val executor: CommandExecutor, private val completer: TabCompleter) : Command(name, "Transfer a LuminaCore currency", "/$name <player> <amount>", aliases) {
    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = executor.onCommand(sender, this, commandLabel, args)
    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>) = completer.onTabComplete(sender, this, alias, args) ?: emptyList()
}
