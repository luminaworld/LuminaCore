package core.luminaworld.settings

import core.luminaworld.LuminaCore
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class PlayerSettingsCommand(
    private val plugin: LuminaCore,
    private val gui: PlayerSettingsGUI
) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("§cคำสั่งนี้ใช้ได้เฉพาะผู้เล่นภายในเกมเท่านั้น!")
            return true
        }

        gui.openGUI(player, 0)
        return true
    }
}
