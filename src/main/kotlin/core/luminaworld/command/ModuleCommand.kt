package core.luminaworld.command

import core.luminaworld.LuminaCore
import core.luminaworld.gui.ModuleGUI
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ModuleCommand(private val plugin: LuminaCore) : CommandExecutor, TabCompleter {
    private val gui = ModuleGUI(plugin)

    init {
        // ผูกมัด GUI Events กับระบบของปลั๊กอิน
        plugin.server.pluginManager.registerEvents(gui, plugin)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // เช็คสิทธิ์
        if (!sender.hasPermission("luminacore.admin")) {
            sender.sendMessage(plugin.getMsg("no-permission", "%prefix% You do not have permission to execute this command."))
            return true
        }

        if (args.isNotEmpty()) {
            if (args[0].equals("reload", ignoreCase = true)) {
                sender.sendMessage("§7[LuminaCore] Reloading configuration and modules...")
                
                // รีโหลด config.yml และรีโหลดโมดูลย่อยทั้งหมด
                plugin.reloadConfig()
                plugin.moduleManager?.reloadModules()
                
                sender.sendMessage(plugin.getMsg("plugin-reloaded", "%prefix% Plugin configuration and modules have been reloaded."))
                return true
            }
            
            sender.sendMessage("§7[LuminaCore] Usage: /$label [reload]")
            return true
        }

        // เปิด GUI หรือหากเป็น Console ให้ส่งข้อความแจ้งเตือนคำสั่งสำหรับ reload
        if (sender is Player) {
            gui.openGUI(sender)
        } else {
            sender.sendMessage(plugin.getMsg("only-players", "%prefix% Only players can execute this command."))
            sender.sendMessage("§7[LuminaCore] Console can use: /$label reload")
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("luminacore.admin")) {
            return emptyList()
        }

        if (args.size == 1) {
            return if ("reload".startsWith(args[0].lowercase())) {
                listOf("reload")
            } else {
                emptyList()
            }
        }

        return emptyList()
    }
}
