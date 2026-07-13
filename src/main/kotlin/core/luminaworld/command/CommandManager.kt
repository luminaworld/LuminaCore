package core.luminaworld.command

import core.luminaworld.LuminaCore
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandMap
import org.bukkit.command.SimpleCommandMap
import org.bukkit.command.TabCompleter
import java.lang.reflect.Field

class CommandManager(private val plugin: LuminaCore) {
    private val registeredCommands = mutableMapOf<String, DynamicCommand>()

    private val commandMap: CommandMap by lazy {
        val server = Bukkit.getServer()
        val getCommandMapMethod = server.javaClass.getDeclaredMethod("getCommandMap")
        getCommandMapMethod.isAccessible = true
        getCommandMapMethod.invoke(server) as CommandMap
    }

    @Suppress("UNCHECKED_CAST")
    private val knownCommands: MutableMap<String, Command>? by lazy {
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
        
        commandMap.register(fallbackPrefix, dynamicCommand)
        registeredCommands[name.lowercase()] = dynamicCommand
        plugin.logger.info("§6[CommandManager] §aRegistered dynamic command: /$name" + (if (aliases.isNotEmpty()) " (aliases: ${aliases.joinToString(", ")})" else ""))
        syncServerCommands()
    }

    /**
     * ยกเลิกการลงทะเบียนคำสั่งแบบ Dynamic จาก Bukkit CommandMap
     */
    fun unregisterCommand(name: String, sync: Boolean = true) {
        val key = name.lowercase()
        val command = registeredCommands.remove(key) ?: return
        val fallbackPrefix = plugin.description.name.lowercase()

        val map = knownCommands
        if (map != null) {
            // ลบคำสั่งหลักและคำสั่งแบบมี prefix
            map.remove(key)
            map.remove("$fallbackPrefix:$key")
            
            // ลบ aliases ทั้งหมด
            for (alias in command.aliases) {
                val aliasKey = alias.lowercase()
                map.remove(aliasKey)
                map.remove("$fallbackPrefix:$aliasKey")
            }
            plugin.logger.info("§6[CommandManager] §cUnregistered dynamic command: /$name")
            if (sync) {
                syncServerCommands()
            }
        } else {
            plugin.logger.warning("[CommandManager] Cannot unregister command /$name because knownCommands map is unavailable.")
        }
    }

    /**
     * ยกเลิกการลงทะเบียนคำสั่งไดนามิกทั้งหมด
     */
    fun unregisterAll() {
        val keys = registeredCommands.keys.toList()
        for (key in keys) {
            unregisterCommand(key, sync = false)
        }
        registeredCommands.clear()
        syncServerCommands()
    }

    /**
     * ซิงก์ระบบคำสั่งไปยังผู้เล่นและ Vanilla command dispatcher
     */
    private fun syncServerCommands() {
        try {
            val server = Bukkit.getServer()
            val syncCommandsMethod = server.javaClass.getMethod("syncCommands")
            syncCommandsMethod.invoke(server)
        } catch (e: NoSuchMethodException) {
            // ข้ามสำหรับเวอร์ชันเก่าที่ไม่มี API นี้
        } catch (e: Exception) {
            plugin.logger.severe("[CommandManager] Failed to sync commands via reflection: ${e.message}")
        }
    }
}
