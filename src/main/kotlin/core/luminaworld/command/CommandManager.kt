package core.luminaworld.command

import core.luminaworld.LuminaCore
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandMap
import org.bukkit.command.SimpleCommandMap
import org.bukkit.command.TabCompleter

class CommandManager(private val plugin: LuminaCore) {
    private val registeredCommands = mutableMapOf<String, DynamicCommand>()

    val commandMap: CommandMap by lazy {
        val server = Bukkit.getServer()
        val getCommandMapMethod = server.javaClass.getDeclaredMethod("getCommandMap")
        getCommandMapMethod.isAccessible = true
        getCommandMapMethod.invoke(server) as CommandMap
    }

    @Suppress("UNCHECKED_CAST")
    val knownCommands: MutableMap<String, Command>? by lazy {
        try {
            val simpleCommandMapClass = SimpleCommandMap::class.java
            val field = simpleCommandMapClass.getDeclaredField("knownCommands")
            field.isAccessible = true
            field.get(commandMap) as? MutableMap<String, Command>
        } catch (e: Exception) {
            plugin.logger.severe("[CommandManager] Failed to get knownCommands map: ${e.message}")
            null
        }
    }

    val brigadierRootNode: Any? by lazy {
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

    private inline fun <T> withCommandLock(block: () -> T): T {
        val lock1 = knownCommands ?: commandMap
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

    /**
     * ลงทะเบียนคำสั่งแบบ Dynamic ไปยัง Bukkit CommandMap
     */
    fun registerCommand(
        name: String,
        executor: CommandExecutor,
        tabCompleter: TabCompleter? = null,
        description: String = "",
        usage: String = "/$name",
        aliases: List<String> = emptyList()
    ) {
        val fallbackPrefix = plugin.description.name.lowercase()
        val dynamicCommand = DynamicCommand(name, description, usage, aliases, executor, tabCompleter)
        
        withCommandLock {
            commandMap.register(fallbackPrefix, dynamicCommand)
            registeredCommands[name.lowercase()] = dynamicCommand
        }
        plugin.logger.info("§6[CommandManager] §aRegistered dynamic command: /$name" + (if (aliases.isNotEmpty()) " (aliases: ${aliases.joinToString(", ")})" else ""))
    }

    /**
     * ยกเลิกการลงทะเบียนคำสั่งแบบ Dynamic จาก Bukkit CommandMap
     */
    fun unregisterCommand(name: String) {
        val key = name.lowercase()
        val command = registeredCommands.remove(key) ?: return
        val fallbackPrefix = plugin.description.name.lowercase()

        withCommandLock {
            command.unregister(commandMap)
            val map = knownCommands
            if (map != null) {
                map.remove(key)
                map.remove("$fallbackPrefix:$key")
                for (alias in command.aliases) {
                    val aliasKey = alias.lowercase()
                    map.remove(aliasKey)
                    map.remove("$fallbackPrefix:$aliasKey")
                }
            }
        }
        plugin.logger.info("§6[CommandManager] §cUnregistered dynamic command: /$name")
    }

    /**
     * ยกเลิกการลงทะเบียนคำสั่งไดนามิกทั้งหมด
     */
    fun unregisterAll() {
        withCommandLock {
            val keys = registeredCommands.keys.toList()
            for (key in keys) {
                val command = registeredCommands.remove(key) ?: continue
                val fallbackPrefix = plugin.description.name.lowercase()
                command.unregister(commandMap)
                val map = knownCommands
                if (map != null) {
                    map.remove(key)
                    map.remove("$fallbackPrefix:$key")
                    for (alias in command.aliases) {
                        val aliasKey = alias.lowercase()
                        map.remove(aliasKey)
                        map.remove("$fallbackPrefix:$aliasKey")
                    }
                }
                plugin.logger.info("§6[CommandManager] §cUnregistered dynamic command: /$key")
            }
            registeredCommands.clear()
        }
    }

    /**
     * ซิงก์ระบบคำสั่งไปยังผู้เล่นและ Vanilla command dispatcher
     */
    fun syncServerCommands() {
        try {
            withCommandLock {
                Bukkit.getServer().javaClass.getMethod("syncCommands").invoke(Bukkit.getServer())
            }
        } catch (_: Exception) { }
    }
}
