package core.luminaworld.modules.minigames.ChatGames

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

class ChatGamesCommand(private val module: ChatGamesModule) : CommandExecutor, TabCompleter {

    // เก็บสถานะการปิดแชท/ปิดเสียงของผู้เล่น
    val disabledPlayers = HashSet<UUID>()
    val mutedPlayers = HashSet<UUID>()
    var allGamesDisabled = false

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val prefix = module.chatConfig.config.getString("prefix", "&a[ChatGames]") ?: "&a[ChatGames]"

        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        val sub = args[0].lowercase()

        when (sub) {
            "reload" -> {
                if (!sender.hasPermission("chatgames.reload") && !sender.isOp) {
                    sendMessage(sender, "not_enough_permissions")
                    return true
                }
                module.reload()
                sendMessage(sender, "config_reloaded")
                return true
            }
            "top" -> {
                module.database.getTopPoints { list ->
                    Bukkit.getGlobalRegionScheduler().execute(module.plugin) {
                        val header = module.chatConfig.getMessage("top.header")
                        val footer = module.chatConfig.getMessage("top.footer")
                        val format = module.chatConfig.getMessage("top.format")

                        if (header.isNotBlank()) sender.sendMessage(module.parseToComponent(header.replace("%prefix%", prefix)))
                        if (list.isEmpty()) {
                            sendMessage(sender, "top.no_data_message")
                        } else {
                            for ((spot, name, points) in list) {
                                val line = format.replace("%spot%", spot.toString())
                                    .replace("%player%", name)
                                    .replace("%points%", points.toString())
                                sender.sendMessage(module.parseToComponent(line))
                            }
                        }
                        if (footer.isNotBlank()) sender.sendMessage(module.parseToComponent(footer.replace("%prefix%", prefix)))
                    }
                }
                return true
            }
            "fastest" -> {
                val enabledTop = module.chatConfig.config.getBoolean("fastest-typers-top", true)
                if (!enabledTop) {
                    sendMessage(sender, "TypersTop.top_disabled_message")
                    return true
                }
                module.database.getTopFastest { list ->
                    Bukkit.getGlobalRegionScheduler().execute(module.plugin) {
                        val header = module.chatConfig.getMessage("TypersTop.header")
                        val footer = module.chatConfig.getMessage("TypersTop.footer")
                        val format = module.chatConfig.getMessage("TypersTop.format")

                        if (header.isNotBlank()) sender.sendMessage(module.parseToComponent(header.replace("%prefix%", prefix)))
                        if (list.isEmpty()) {
                            sendMessage(sender, "TypersTop.no_data_message")
                        } else {
                            for ((spot, name, time) in list) {
                                val line = format.replace("%spot%", spot.toString())
                                    .replace("%player%", name)
                                    .replace("%time%", String.format("%.3f", time))
                                sender.sendMessage(module.parseToComponent(line))
                            }
                        }
                        if (footer.isNotBlank()) sender.sendMessage(module.parseToComponent(footer.replace("%prefix%", prefix)))
                    }
                }
                return true
            }
            "points" -> {
                if (sender !is Player) {
                    sendMessage(sender, "player_only_command")
                    return true
                }
                module.database.getPoints(sender.uniqueId) { pts ->
                    val msg = module.chatConfig.getMessage("player_points")
                        .replace("%amount%", pts.toString())
                        .replace("%prefix%", prefix)
                    sender.sendMessage(module.parseToComponent(msg))
                }
                return true
            }
            "+points" -> {
                if (!sender.hasPermission("chatgames.admin") && !sender.isOp) {
                    sendMessage(sender, "not_enough_permissions")
                    return true
                }
                val targetName = args.getOrNull(1) ?: return false
                val amt = args.getOrNull(2)?.toIntOrNull() ?: return false
                val targetPlayer = Bukkit.getPlayer(targetName)
                val uuid = targetPlayer?.uniqueId ?: UUID.nameUUIDFromBytes(targetName.toByteArray())

                module.database.updatePoints(uuid, targetName, amt, "ADD") { newPts ->
                    val msg = module.chatConfig.getMessage("points_added")
                        .replace("%player%", targetName)
                        .replace("%amount%", amt.toString())
                        .replace("%prefix%", prefix)
                    sender.sendMessage(module.parseToComponent(msg))
                }
                return true
            }
            "-points" -> {
                if (!sender.hasPermission("chatgames.admin") && !sender.isOp) {
                    sendMessage(sender, "not_enough_permissions")
                    return true
                }
                val targetName = args.getOrNull(1) ?: return false
                val amt = args.getOrNull(2)?.toIntOrNull() ?: return false
                val targetPlayer = Bukkit.getPlayer(targetName)
                val uuid = targetPlayer?.uniqueId ?: UUID.nameUUIDFromBytes(targetName.toByteArray())

                module.database.updatePoints(uuid, targetName, amt, "REMOVE") { newPts ->
                    val msg = module.chatConfig.getMessage("points_removed")
                        .replace("%player%", targetName)
                        .replace("%amount%", amt.toString())
                        .replace("%prefix%", prefix)
                    sender.sendMessage(module.parseToComponent(msg))
                }
                return true
            }
            "setpoints" -> {
                if (!sender.hasPermission("chatgames.admin") && !sender.isOp) {
                    sendMessage(sender, "not_enough_permissions")
                    return true
                }
                val targetName = args.getOrNull(1) ?: return false
                val amt = args.getOrNull(2)?.toIntOrNull() ?: return false
                val targetPlayer = Bukkit.getPlayer(targetName)
                val uuid = targetPlayer?.uniqueId ?: UUID.nameUUIDFromBytes(targetName.toByteArray())

                module.database.updatePoints(uuid, targetName, amt, "SET") { newPts ->
                    val msg = module.chatConfig.getMessage("points_set")
                        .replace("%player%", targetName)
                        .replace("%amount%", amt.toString())
                        .replace("%prefix%", prefix)
                    sender.sendMessage(module.parseToComponent(msg))
                }
                return true
            }
            "toggle" -> {
                if (sender !is Player) {
                    sendMessage(sender, "player_only_command")
                    return true
                }
                val uuid = sender.uniqueId
                val isOff = disabledPlayers.contains(uuid)
                if (isOff) {
                    disabledPlayers.remove(uuid)
                } else {
                    disabledPlayers.add(uuid)
                }
                val stateMsg = if (isOff) module.chatConfig.getMessage("state-ON", "เปิด") else module.chatConfig.getMessage("state-OFF", "ปิด")
                val msg = module.chatConfig.getMessage("toggle_game")
                    .replace("%state%", stateMsg)
                    .replace("%prefix%", prefix)
                sender.sendMessage(module.parseToComponent(msg))
                return true
            }
            "togglesound" -> {
                if (sender !is Player) {
                    sendMessage(sender, "player_only_command")
                    return true
                }
                val uuid = sender.uniqueId
                val isMuted = mutedPlayers.contains(uuid)
                if (isMuted) {
                    mutedPlayers.remove(uuid)
                } else {
                    mutedPlayers.add(uuid)
                }
                val stateMsg = if (isMuted) module.chatConfig.getMessage("state-ON", "เปิด") else module.chatConfig.getMessage("state-OFF", "ปิด")
                val msg = module.chatConfig.getMessage("toggle_sound")
                    .replace("%state%", stateMsg)
                    .replace("%prefix%", prefix)
                sender.sendMessage(module.parseToComponent(msg))
                return true
            }
            "toggleall" -> {
                if (!sender.hasPermission("chatgames.admin") && !sender.isOp) {
                    sendMessage(sender, "not_enough_permissions")
                    return true
                }
                allGamesDisabled = !allGamesDisabled
                val stateMsg = if (!allGamesDisabled) module.chatConfig.getMessage("state-ON", "เปิด") else module.chatConfig.getMessage("state-OFF", "ปิด")
                val msg = module.chatConfig.getMessage("toggleAllGames")
                    .replace("%state%", stateMsg)
                    .replace("%prefix%", prefix)
                sender.sendMessage(module.parseToComponent(msg))
                return true
            }
            "click" -> {
                if (sender !is Player) return true
                val clickIdStr = args.getOrNull(1) ?: return true
                val game = module.gameManager.currentGame ?: return true
                if (game.type == "clickable" && game.answer == clickIdStr) {
                    val timeTaken = (System.currentTimeMillis() - game.startTime) / 1000.0
                    module.gameManager.handleWinner(sender, timeTaken)
                }
                return true
            }
            else -> {
                // สมมติว่า sub เป็นชื่อเกม/Race ที่ต้องการบังคับเริ่มงานแบบแมนนวล
                if (!sender.hasPermission("chatgames.start") && !sender.isOp) {
                    sendMessage(sender, "not_enough_permissions")
                    return true
                }

                val listChatGames = listOf(
                    "unscramble", "unreverse", "reaction", "guess_the_number",
                    "shoppinglist", "clickable", "fillout", "hoverable",
                    "random", "math", "variable", "trivia"
                )
                val listRaces = listOf("hunt", "mine", "place", "fish", "eat", "craft", "furnace")

                val worldName = if (sender is Player) sender.world.name else ""
                val disabledWorlds = module.chatConfig.config.getStringList("disabled-worlds")
                val allowFromDisabled = module.chatConfig.config.getBoolean("allow_starting_from_disabled", true)

                if (sender is Player && disabledWorlds.contains(worldName) && !allowFromDisabled) {
                    sendMessage(sender, "cannot_start_from_disabled")
                    return true
                }

                if (listChatGames.contains(sub)) {
                    module.gameManager.startChatGame(sub)
                    val msg = module.chatConfig.getMessage("game_started")
                        .replace("%game%", sub)
                        .replace("%prefix%", prefix)
                    sender.sendMessage(module.parseToComponent(msg))
                } else if (listRaces.contains(sub)) {
                    module.gameManager.startChatRace(sub)
                    val msg = module.chatConfig.getMessage("game_started")
                        .replace("%game%", sub)
                        .replace("%prefix%", prefix)
                    sender.sendMessage(module.parseToComponent(msg))
                } else {
                    sendMessage(sender, "unknown_command")
                }
                return true
            }
        }
    }

    private fun sendMessage(sender: CommandSender, path: String) {
        val prefix = module.chatConfig.config.getString("prefix", "&a[ChatGames]") ?: "&a[ChatGames]"
        val msg = module.chatConfig.getMessage(path)
        if (msg.isNotBlank()) {
            sender.sendMessage(module.parseToComponent(msg.replace("%prefix%", prefix)))
        }
    }

    private fun sendHelp(sender: CommandSender) {
        val prefix = module.chatConfig.config.getString("prefix", "&a[ChatGames]") ?: "&a[ChatGames]"
        val list = module.chatConfig.getMessageList("help_command")
        for (line in list) {
            sender.sendMessage(module.parseToComponent(line.replace("%prefix%", prefix)))
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val list = ArrayList<String>()
        if (args.size == 1) {
            val subs = listOf("reload", "top", "fastest", "points", "+points", "-points", "setpoints", "toggle", "togglesound", "toggleall")
            val games = listOf(
                "unscramble", "unreverse", "reaction", "guess_the_number",
                "shoppinglist", "clickable", "fillout", "hoverable",
                "random", "math", "variable", "trivia",
                "hunt", "mine", "place", "fish", "eat", "craft", "furnace"
            )
            val search = args[0].lowercase()
            for (s in subs) {
                if (s.startsWith(search)) list.add(s)
            }
            if (sender.hasPermission("chatgames.start") || sender.isOp) {
                for (g in games) {
                    if (g.startsWith(search)) list.add(g)
                }
            }
        } else if (args.size == 2) {
            val sub = args[0].lowercase()
            if (sub == "+points" || sub == "-points" || sub == "setpoints") {
                val search = args[1].lowercase()
                for (p in Bukkit.getOnlinePlayers()) {
                    if (p.name.lowercase().startsWith(search)) {
                        list.add(p.name)
                    }
                }
            }
        }
        return list
    }
}
