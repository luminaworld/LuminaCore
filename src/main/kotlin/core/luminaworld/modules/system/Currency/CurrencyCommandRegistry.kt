package core.luminaworld.modules.system.Currency

import core.luminaworld.LuminaCore
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.command.SimpleCommandMap
import org.bukkit.command.TabCompleter

/** Commands defined by a currency belong to this module, not CommandManager. */
class CurrencyCommandRegistry(private val plugin: LuminaCore) {
    private val commands = mutableMapOf<String, CurrencyDynamicCommand>()
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

    private val brigadierRootNode: Any? by lazy {
        try {
            val server = Bukkit.getServer()
            val getServerMethod = server.javaClass.getDeclaredMethod("getServer")
            getServerMethod.isAccessible = true
            val minecraftServer = getServerMethod.invoke(server)
            val getCommandsMethod = minecraftServer.javaClass.getMethod("getCommands")
            val commands = getCommandsMethod.invoke(minecraftServer)
            val getDispatcherMethod = commands.javaClass.getMethod("getDispatcher")
            val dispatcher = getDispatcherMethod.invoke(commands)
            val getRootMethod = dispatcher.javaClass.getMethod("getRoot")
            getRootMethod.invoke(dispatcher)
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <T> withLock(block: () -> T): T {
        val lock1 = knownCommands() ?: map
        val lock2 = brigadierRootNode
        return if (lock2 != null) {
            synchronized(lock2) {
                synchronized(lock1) {
                    block()
                }
            }
        } else {
            synchronized(lock1) {
                block()
            }
        }
    }

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
        withLock {
            map.register(plugin.description.name.lowercase(), command)
            commands[normalized] = command
        }
        return true
    }

    fun unregisterAll() {
        withLock {
            val known = knownCommands()
            if (known != null) {
                commands.forEach { (name, command) ->
                    val keys = listOf(name, "${plugin.description.name.lowercase()}:$name") + command.aliases.flatMap { listOf(it.lowercase(), "${plugin.description.name.lowercase()}:${it.lowercase()}") }
                    keys.forEach { key -> if (known[key] === command) known.remove(key) }
                    command.unregister(map)
                }
            } else {
                commands.forEach { (_, command) -> command.unregister(map) }
            }
            commands.clear()
        }
    }
}

private class CurrencyDynamicCommand(name: String, aliases: List<String>, private val executor: CommandExecutor, private val completer: TabCompleter) : Command(name, "Transfer a LuminaCore currency", "/$name <player> <amount>", aliases) {
    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = executor.onCommand(sender, this, commandLabel, args)
    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>) = completer.onTabComplete(sender, this, alias, args) ?: emptyList()
}
