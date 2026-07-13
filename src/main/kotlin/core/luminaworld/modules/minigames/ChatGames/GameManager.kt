package core.luminaworld.modules.minigames.ChatGames

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class GameManager(private val module: ChatGamesModule) {
    var currentGame: ActiveGame? = null
        private set

    private var nextGameTask: ScheduledTask? = null
    private var gameTimeoutTask: ScheduledTask? = null
    private var shoppingListMemorizeTask: ScheduledTask? = null

    // รายชื่อเสียงเกมแชท
    private val volume get() = module.chatConfig.config.getDouble("GameSounds.volume", 1.0).toFloat()
    private val soundStart get() = getSound(module.chatConfig.config.getString("GameSounds.game-start", "LEVEL_UP"))
    private val soundWin get() = getSound(module.chatConfig.config.getString("GameSounds.win", "ORB_PICKUP"))
    private val soundExpired get() = getSound(module.chatConfig.config.getString("GameSounds.time-expired", "VILLAGER_NO"))

    fun startScheduler() {
        stopScheduler()

        val delayTicks = getNextGameDelayTicks()
        if (delayTicks > 0) {
            scheduleNextGame(delayTicks)
        }
    }

    fun stopScheduler() {
        nextGameTask?.cancel()
        nextGameTask = null
        
        shoppingListMemorizeTask?.cancel()
        shoppingListMemorizeTask = null
        
        stopTimeoutTask()
    }

    private fun stopTimeoutTask() {
        gameTimeoutTask?.cancel()
        gameTimeoutTask = null
    }

    private fun getNextGameDelayTicks(): Long {
        val randomDelayEnabled = module.chatConfig.config.getBoolean("randomDelay.enable", false)
        if (randomDelayEnabled) {
            val timeUnit = module.chatConfig.config.getString("randomDelay.timeUnit", "minutes") ?: "minutes"
            val min = module.chatConfig.config.getInt("randomDelay.minimum", 1)
            val limit = module.chatConfig.config.getInt("randomDelay.limit", 25)
            val value = if (min >= limit) min else Random.nextInt(min, limit + 1)
            val seconds = if (timeUnit.equals("seconds", ignoreCase = true)) value else value * 60
            return (seconds * 20).toLong()
        } else {
            val timeMinutes = module.chatConfig.config.getDouble("time_minutes", 3.0)
            if (timeMinutes <= -1.0) return -1L
            return (timeMinutes * 60 * 20).toLong()
        }
    }

    private fun scheduleNextGame(delayTicks: Long) {
        nextGameTask?.cancel()
        nextGameTask = Bukkit.getGlobalRegionScheduler().runDelayed(module.plugin, { _ ->
            if (currentGame == null) {
                val minPlayers = module.chatConfig.config.getInt("min_players_online", 1)
                val onlinePlayers = Bukkit.getOnlinePlayers().size
                if (onlinePlayers >= minPlayers) {
                    startRandomGame()
                } else {
                    scheduleNextGame(1200L) // หน่วงเวลาอีก 1 นาที (1200 ticks)
                }
            }
        }, delayTicks)
    }

    /**
     * ดึงค่าเสียงจากชื่อ String
     */
    private fun getSound(name: String?): Sound? {
        if (name.isNullOrBlank()) return null
        return try {
            Sound.valueOf(name.uppercase())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * เล่นเสียงให้ผู้เล่นทุกคนในโลกที่อนุญาต
     */
    fun playSoundToAll(sound: Sound?) {
        if (sound == null) return
        val disabledWorlds = module.chatConfig.config.getStringList("disabled-worlds")
        for (player in Bukkit.getOnlinePlayers()) {
            if (disabledWorlds.contains(player.world.name)) continue
            // ตรวจสอบความพึงพอใจการเปิดเสียงของผู้เล่น (ถ้ามี)
            player.playSound(player.location, sound, volume, 1.0f)
        }
    }

    /**
     * สุ่มกิจกรรมเปิดเกมแชทหรือการแข่งขัน
     */
    fun startRandomGame() {
        val enableRaces = module.chatConfig.chatRaces.getBoolean("enable-races", true)
        val isRaceRandom = enableRaces && Random.nextBoolean()

        if (isRaceRandom) {
            startRandomRace()
        } else {
            val gamesList = listOf(
                "unscramble", "unreverse", "reaction", "guess_the_number",
                "shoppinglist", "clickable", "fillout", "hoverable",
                "random", "math", "variable", "trivia"
            )
            // คัดกรองเฉพาะเกมที่เปิดใช้งานในคอนฟิก
            val activeGames = gamesList.filter { module.chatConfig.config.getBoolean("$it.enable", true) }
            if (activeGames.isEmpty()) {
                startRandomRace() // Fallback ไปที่ Race หากปิดเกมแชททั้งหมด
                return
            }
            startChatGame(activeGames.random())
        }
    }

    /**
     * สุ่มเลือกภารกิจแข่งขันในเกม (Chat Race)
     */
    fun startRandomRace() {
        val raceTypes = listOf("hunt", "mine", "place", "fish", "eat", "craft", "furnace")
        val activeRaces = raceTypes.filter { module.chatConfig.chatRaces.getBoolean("$it.enable", true) }
        if (activeRaces.isEmpty()) {
            return
        }
        startChatRace(activeRaces.random())
    }

    /**
     * เริ่มกิจกรรม Chat Game
     */
    fun startChatGame(gameType: String) {
        val isEnabled = module.chatConfig.config.getBoolean("$gameType.enable", true)
        if (!isEnabled) return

        val timeToGuess = module.chatConfig.config.getInt("$gameType.timeToGuess_seconds", 20)
        val caseSensitive = module.chatConfig.config.getBoolean("$gameType.case-sensitive", true)

        var question = ""
        var answer = ""
        val answers = ArrayList<String>()
        var selection = ""
        var shoppingListOriginal = emptyList<String>()
        val clickUuid = UUID.randomUUID()

        when (gameType) {
            "unscramble" -> {
                val wordsList = module.chatConfig.words.getStringList("unscramble.Words")
                if (wordsList.isEmpty()) return
                val word = wordsList.random()
                answer = word
                // สลับอักษร
                var shuffled = word.toCharArray().apply { shuffle() }.joinToString("")
                while (shuffled.equals(word, ignoreCase = true) && word.length > 1) {
                    shuffled = word.toCharArray().apply { shuffle() }.joinToString("")
                }
                selection = shuffled
                question = shuffled
            }
            "unreverse" -> {
                val wordsList = module.chatConfig.words.getStringList("unreverse.Words")
                if (wordsList.isEmpty()) return
                val word = wordsList.random()
                answer = word
                selection = word.reversed()
                question = selection
            }
            "reaction" -> {
                val wordsList = module.chatConfig.words.getStringList("reaction.Words")
                if (wordsList.isEmpty()) return
                val word = wordsList.random()
                answer = word
                selection = word
                question = word
            }
            "guess_the_number" -> {
                val min = module.chatConfig.config.getInt("guess_the_number.lower-limit", 1)
                val max = module.chatConfig.config.getInt("guess_the_number.upper-limit", 20)
                val num = if (min >= max) min else Random.nextInt(min, max + 1)
                answer = num.toString()
                selection = "$min-$max"
                question = selection
            }
            "shoppinglist" -> {
                val items = module.chatConfig.words.getStringList("shoppinglist.items")
                if (items.isEmpty()) return
                val length = module.chatConfig.config.getInt("shoppinglist.listLength", 3)
                shoppingListOriginal = items.shuffled().take(length)
                answer = shoppingListOriginal.joinToString(", ")
                selection = shoppingListOriginal.joinToString(", ")
                question = selection
            }
            "clickable" -> {
                question = clickUuid.toString()
                answer = clickUuid.toString()
            }
            "fillout" -> {
                val wordsList = module.chatConfig.words.getStringList("fillout.Words")
                if (wordsList.isEmpty()) return
                val word = wordsList.random()
                answer = word
                val difficulty = module.chatConfig.config.getInt("fillout.difficulty", 30)
                val countToHide = Math.max(1, (word.length * (difficulty / 100.0)).toInt())
                val indicesToHide = (word.indices).shuffled().take(countToHide)
                val builder = StringBuilder()
                for (i in word.indices) {
                    if (indicesToHide.contains(i)) {
                        builder.append("_")
                    } else {
                        builder.append(word[i])
                    }
                }
                selection = builder.toString()
                question = selection
            }
            "hoverable" -> {
                val wordsList = module.chatConfig.words.getStringList("hoverable.Words")
                if (wordsList.isEmpty()) return
                val word = wordsList.random()
                answer = word
                selection = word
                question = word
            }
            "random" -> {
                val rangeStr = module.chatConfig.config.getString("random.lengthRange", "6-8") ?: "6-8"
                val tokens = rangeStr.split("-")
                val minLen = tokens.getOrNull(0)?.toIntOrNull() ?: 6
                val maxLen = tokens.getOrNull(1)?.toIntOrNull() ?: 8
                val len = if (minLen >= maxLen) minLen else Random.nextInt(minLen, maxLen + 1)

                val charType = module.chatConfig.config.getString("random.character_type", "ALPHANUMERIC") ?: "ALPHANUMERIC"
                val charPool = when (charType.uppercase()) {
                    "ALPHA" -> "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    "NUMERIC" -> "0123456789"
                    "SYMBOLIC" -> "!@#$%^&*()_+{}|:<>?`-=[]\\;',./"
                    "LOWERCASE_ALPHA" -> "abcdefghijklmnopqrstuvwxyz"
                    "UPPERCASE_ALPHA" -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    "HEXADECIMAL" -> "0123456789ABCDEF"
                    "CUSTOM" -> module.chatConfig.config.getString("random.custom_characters", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") ?: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
                    else -> "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
                }

                val sb = StringBuilder()
                for (i in 0 until len) {
                    sb.append(charPool[Random.nextInt(charPool.length)])
                }
                answer = sb.toString()
                selection = answer
                question = answer
            }
            "math" -> {
                val difficulty = module.chatConfig.config.getInt("math.difficulty", 2)
                val operators = module.chatConfig.config.getStringList("math.operators").ifEmpty { listOf("+", "-") }
                val op = operators.random()

                val limit = when (difficulty) {
                    1 -> 9
                    2 -> 99
                    3 -> 999
                    4 -> 9999
                    else -> 99
                }
                val n1 = Random.nextInt(1, limit + 1)
                val n2 = Random.nextInt(1, limit + 1)

                val calculated = when (op) {
                    "+" -> n1 + n2
                    "-" -> n1 - n2
                    "x", "*" -> n1 * n2
                    "/" -> {
                        // ป้องกันการหารไม่ลงตัวหรือหารศูนย์
                        val resultVal = Random.nextInt(1, limit + 1)
                        val num1 = resultVal * n2
                        answer = resultVal.toString()
                        selection = "$num1 / $n2"
                        question = selection
                        null
                    }
                    else -> n1 + n2
                }

                if (calculated != null) {
                    answer = calculated.toString()
                    selection = "$n1 $op $n2"
                    question = selection
                }
            }
            "variable" -> {
                val minResult = module.chatConfig.config.getInt("variable.result-min", 20)
                val maxResult = module.chatConfig.config.getInt("variable.result-max", 100)
                val resultVal = if (minResult >= maxResult) minResult else Random.nextInt(minResult, maxResult + 1)

                val dataList = module.chatConfig.config.getStringList("variable.data")
                if (dataList.isEmpty()) return
                val symbolData = dataList.random().split(";")
                val symbol = symbolData.getOrNull(0) ?: "✯"
                val symbolVal = symbolData.getOrNull(1)?.toIntOrNull() ?: 10

                val toGet = module.chatConfig.config.getString("variable.toGet", "✗") ?: "✗"

                // สุ่มสร้างสมการ เช่น symbol + toGet = resultVal
                val isSymbolFirst = Random.nextBoolean()
                if (isSymbolFirst) {
                    selection = "$symbol + $toGet = $resultVal"
                } else {
                    selection = "$toGet + $symbol = $resultVal"
                }
                answer = (resultVal - symbolVal).toString()
                question = selection
            }
            "trivia" -> {
                val dataSection = module.chatConfig.words.getConfigurationSection("trivia.data")
                if (dataSection == null) return
                val keys = dataSection.getKeys(false).toList()
                if (keys.isEmpty()) return
                val key = keys.random()
                val questionText = dataSection.getString("$key.question") ?: ""
                val ansList = dataSection.getStringList("$key.answers")
                if (questionText.isBlank() || ansList.isEmpty()) return

                question = questionText
                selection = questionText
                answer = ansList.firstOrNull() ?: ""
                answers.addAll(ansList)
            }
        }

        val game = ActiveGame(
            type = gameType,
            question = question,
            answer = answer,
            answers = answers,
            isCaseSensitive = caseSensitive,
            startTime = System.currentTimeMillis(),
            timeToGuessSeconds = timeToGuess,
            isRace = false,
            shoppingListOriginal = shoppingListOriginal,
            clickUuid = clickUuid
        )

        currentGame = game

        // การประกาศข้อความเริ่มกิจกรรม
        if (gameType == "shoppinglist") {
            // เฟสความจำของเกม shoppinglist
            val timeToMemorize = module.chatConfig.config.getInt("shoppinglist.timeToMemorize", 7)
            game.isShoppingListMemorizePhase = true
            announceStart(gameType, selection, timeToMemorize)

            // ย้ายไปยังเฟสทายหลังจากหมดเวลาความจำ
            shoppingListMemorizeTask = Bukkit.getGlobalRegionScheduler().runDelayed(module.plugin, { _ ->
                if (currentGame == game) {
                    game.isShoppingListMemorizePhase = false
                    game.startTime = System.currentTimeMillis() // รีเซ็ตเวลาเริ่มต้นสำหรับการทาย
                    announceShoppingListGuessPhase(timeToGuess)
                    setupTimeoutTask(game, timeToGuess)
                }
            }, (timeToMemorize * 20).toLong())
        } else {
            announceStart(gameType, selection, timeToGuess)
            setupTimeoutTask(game, timeToGuess)
        }

        playSoundToAll(soundStart)
        logGameStart(gameType, answer.ifBlank { answers.joinToString("|") })
    }

    /**
     * เริ่มกิจกรรมการแข่งขัน (Chat Race)
     */
    fun startChatRace(raceType: String) {
        val isEnabled = module.chatConfig.chatRaces.getBoolean("$raceType.enable", true)
        if (!isEnabled) return

        val timeToComplete = module.chatConfig.chatRaces.getInt("$raceType.timeToComplete_seconds", 60)

        var targetAmount = 1
        var targetValue = ""

        if (raceType == "fish") {
            targetValue = "FISH"
            targetAmount = 1
        } else {
            val dataSection = module.chatConfig.chatRaces.getConfigurationSection("$raceType.data")
            if (dataSection == null) return
            val keys = dataSection.getKeys(false).toList()
            if (keys.isEmpty()) return
            targetValue = keys.random()
            val amountKeys = dataSection.getConfigurationSection(targetValue)?.getKeys(false)?.toList() ?: emptyList()
            val amountStr = amountKeys.randomOrNull() ?: "1"
            targetAmount = amountStr.toIntOrNull() ?: 1
        }

        val game = ActiveGame(
            type = raceType,
            question = "$targetAmount x $targetValue",
            answer = "",
            isCaseSensitive = false,
            startTime = System.currentTimeMillis(),
            timeToGuessSeconds = timeToComplete,
            isRace = true,
            raceTargetAmount = targetAmount,
            raceTargetValue = targetValue
        )

        currentGame = game
        announceRaceStart(raceType, targetAmount, targetValue, timeToComplete)
        setupTimeoutTask(game, timeToComplete)

        playSoundToAll(soundStart)
        logRaceStart(raceType, targetValue, targetAmount)
    }

    private fun setupTimeoutTask(game: ActiveGame, delaySec: Int) {
        stopTimeoutTask()
        gameTimeoutTask = Bukkit.getGlobalRegionScheduler().runDelayed(module.plugin, { _ ->
            if (currentGame == game) {
                endGameTimeout()
            }
        }, (delaySec * 20).toLong())
    }

    /**
     * จบเกมเมื่อหมดเวลาทายผล
     */
    fun endGameTimeout() {
        val game = currentGame ?: return
        currentGame = null
        stopTimeoutTask()

        announceTimeout(game)
        playSoundToAll(soundExpired)

        // สุ่มเริ่มเกมนัดถัดไป
        val nextDelay = getNextGameDelayTicks()
        if (nextDelay > 0) {
            scheduleNextGame(nextDelay)
        }
    }

    /**
     * จบเกมเมื่อผู้เล่นชนะการแข่งขัน
     */
    fun handleWinner(player: Player, timeTaken: Double) {
        val game = currentGame ?: return
        currentGame = null
        stopTimeoutTask()

        // บันทึก Log
        logGameEnd(game.type, true, player.name, game.answer)

        // มอบแต้มและบันทึกสถิติลง Database
        val pointsToAdd = 1 // ปกติชนะได้ 1 แต้ม
        module.database.recordWin(player.uniqueId, player.name, pointsToAdd, timeTaken)

        // ประกาศข่าวสารผู้ชนะ
        announceWinner(game, player, timeTaken)
        playSoundToAll(soundWin)

        // มอบรางวัลตามคอนฟิก
        giveRewards(game, player)

        // สุ่มเริ่มเกมนัดถัดไป
        val nextDelay = getNextGameDelayTicks()
        if (nextDelay > 0) {
            scheduleNextGame(nextDelay)
        }
    }

    /**
     * มอบรางวัลแก่ผู้เล่นตามที่ตั้งไว้ใน rewards.yml
     */
    private fun giveRewards(game: ActiveGame, player: Player) {
        val section = if (game.isRace) {
            // โหลดรางวัลสำหรับ Race
            val rewardsList = module.chatConfig.chatRaces.getStringList("${game.type}.data.${game.raceTargetValue}.${game.raceTargetAmount}.rewards")
            val configKey = rewardsList.firstOrNull() ?: "reward_1"
            module.chatConfig.rewards.getConfigurationSection("races.rewards.$configKey")
        } else {
            // โหลดรางวัลสำหรับ Game ปกติ (จาก rewards.yml ในลำดับที่ 1)
            val rewardsSec = module.chatConfig.rewards.getConfigurationSection("${game.type}.rewards")
            val spotSec = rewardsSec?.getConfigurationSection("1") ?: rewardsSec?.get("1") as? org.bukkit.configuration.ConfigurationSection
            spotSec?.getConfigurationSection("reward_1")
        }

        if (section == null) {
            module.plugin.logger.warning("[ChatGames] ไม่พบข้อมูลของรางวัลสำหรับ ${game.type} (isRace=${game.isRace})")
            return
        }

        val commands = section.getStringList("data")
        val chance = section.getDouble("chance", 100.0)

        // ตรวจสอบโอกาสได้รับรางวัล
        if (chance < 100.0 && Random.nextDouble() * 100.0 > chance) {
            return
        }

        for (cmdLine in commands) {
            var finalCmd = cmdLine

            // ตรวจสอบระบบโอกาสแบบ inline เช่น '10.0%~ [consolecmd] ...'
            if (finalCmd.contains("%~")) {
                val parts = finalCmd.split("%~ ")
                if (parts.size >= 2) {
                    val inlineChance = parts[0].toDoubleOrNull() ?: 100.0
                    if (Random.nextDouble() * 100.0 > inlineChance) {
                        continue
                    }
                    finalCmd = parts[1]
                }
            }

            // ตรวจสอบสิทธิ์ท้ายคำสั่ง เช่น 'has:some.perm'
            if (finalCmd.contains(" has:")) {
                val parts = finalCmd.split(" has:")
                val perm = parts.getOrNull(1)
                if (!perm.isNullOrBlank() && !player.hasPermission(perm)) {
                    continue
                }
                finalCmd = parts[0]
            }

            // แทนที่ placeholder ทั่วไป
            finalCmd = finalCmd.replace("%player%", player.name)

            // ดำเนินการตามแอ็กชันรางวัล
            when {
                finalCmd.startsWith("[consolecmd] ") -> {
                    val cmd = finalCmd.substring(13)
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                }
                finalCmd.startsWith("[playercmd] ") -> {
                    val cmd = finalCmd.substring(12)
                    player.performCommand(cmd)
                }
                finalCmd.startsWith("[player_as_op] ") -> {
                    val cmd = finalCmd.substring(15)
                    val wasOp = player.isOp
                    try {
                        player.isOp = true
                        player.performCommand(cmd)
                    } finally {
                        player.isOp = wasOp
                    }
                }
                finalCmd.startsWith("[playermsg] ") -> {
                    val msg = finalCmd.substring(12)
                    player.sendMessage(module.parseToComponent(msg))
                }
                finalCmd.startsWith("[broadcast] ") -> {
                    val msg = finalCmd.substring(12)
                    Bukkit.broadcast(module.parseToComponent(msg))
                }
            }
        }
    }

    // ==========================================
    // การประกาศข่าวสารทางช่องแชท (Announcements)
    // ==========================================

    private fun announceStart(gameType: String, selection: String, timeToGuess: Int) {
        val list = module.chatConfig.getMessageList("$gameType.gameStartAnnouncement")
        val prefix = module.chatConfig.config.getString("prefix", "&a[ChatGames]") ?: "&a[ChatGames]"
        
        for (line in list) {
            val formatted = line.replace("%prefix%", prefix)
                .replace("%selection%", selection)
                .replace("%timeToGuess%", timeToGuess.toString())
            broadcastMessage(formatted)
        }
    }

    private fun announceShoppingListGuessPhase(timeToGuess: Int) {
        val list = module.chatConfig.getMessageList("shoppinglist.messageAfterMemorized")
        val prefix = module.chatConfig.config.getString("prefix", "&a[ChatGames]") ?: "&a[ChatGames]"

        for (line in list) {
            val formatted = line.replace("%prefix%", prefix)
                .replace("%timeToGuess%", timeToGuess.toString())
            broadcastMessage(formatted)
        }
    }

    private fun announceRaceStart(raceType: String, amount: Int, value: String, timeToComplete: Int) {
        val keyName = if (raceType == "fish") "fish" else raceType
        val list = module.chatConfig.getMessageList("$keyName.gameStartAnnouncement")
        val prefix = module.chatConfig.config.getString("prefix", "&a[ChatGames]") ?: "&a[ChatGames]"

        // แปลชื่อวัสดุ/เอนทิตีเป็นชื่อที่แสดงในเซิร์ฟเวอร์ (เพื่อความสวยงามใน Race)
        val displayName = translateItemName(value)

        for (line in list) {
            val formatted = line.replace("%prefix%", prefix)
                .replace("%amount%", amount.toString())
                .replace("%value%", displayName)
                .replace("%timeToComplete%", timeToComplete.toString())
            broadcastMessage(formatted)
        }
    }

    private fun announceWinner(game: ActiveGame, player: Player, timeTaken: Double) {
        val list = module.chatConfig.getMessageList("${game.type}.correct_message")
        val prefix = module.chatConfig.config.getString("prefix", "&a[ChatGames]") ?: "&a[ChatGames]"
        val timeStr = String.format("%.3f", timeTaken)

        val displayName = if (game.isRace) translateItemName(game.raceTargetValue) else ""

        for (line in list) {
            val formatted = line.replace("%prefix%", prefix)
                .replace("%winner_1%", player.name)
                .replace("%winner%", player.name)
                .replace("%time_1%", timeStr)
                .replace("%time%", timeStr)
                .replace("%correct_answer%", game.answer)
                .replace("%amount%", game.raceTargetAmount.toString())
                .replace("%value%", displayName)
            broadcastMessage(formatted)
        }
    }

    private fun announceTimeout(game: ActiveGame) {
        val list = module.chatConfig.getMessageList("${game.type}.time_expired_message")
        val prefix = module.chatConfig.config.getString("prefix", "&a[ChatGames]") ?: "&a[ChatGames]"
        val timeToGuess = game.timeToGuessSeconds.toString()

        val displayName = if (game.isRace) translateItemName(game.raceTargetValue) else ""

        for (line in list) {
            val formatted = line.replace("%prefix%", prefix)
                .replace("%timeToGuess%", timeToGuess)
                .replace("%timeToComplete%", timeToGuess)
                .replace("%correct_answer%", game.answer)
                .replace("%amount%", game.raceTargetAmount.toString())
                .replace("%value%", displayName)
            broadcastMessage(formatted)
        }
    }

    private fun broadcastMessage(msg: String) {
        val disabledWorlds = module.chatConfig.config.getStringList("disabled-worlds")
        val component = module.parseToComponent(msg)
        
        if (msg.contains("<center>")) {
            // ฟอร์แมตกึ่งกลาง (ถ้าต้องการ) - ปัจจุบัน MiniMessage parsed content
            val textOnly = msg.replace("<center>", "")
            val finalComp = module.parseToComponent(textOnly)
            for (player in Bukkit.getOnlinePlayers()) {
                if (disabledWorlds.contains(player.world.name)) continue
                player.sendMessage(finalComp)
            }
        } else {
            for (player in Bukkit.getOnlinePlayers()) {
                if (disabledWorlds.contains(player.world.name)) continue
                player.sendMessage(component)
            }
        }
    }

    /**
     * ดึงข้อความสำหรับการแปลภาษาวัสดุ/เอนทิตี
     */
    private fun translateItemName(input: String): String {
        // ในระบบแปลภาษา ถ้ามี JSON แปลภาษารองรับ สามารถดึงมาใช้ได้ 
        // หรือส่งกลับมาเป็นชื่อที่จัดรูปแบบสวยงามแทน (เช่น DIAMOND_ORE -> Diamond Ore)
        return input.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
    }

    // ==========================================
    // บันทึกกิจกรรมระบบ Log (Logging System)
    // ==========================================

    private fun logGameStart(game: String, answer: String) {
        val fileLog = module.chatConfig.config.getBoolean("log-local", false)
        val consoleLog = module.chatConfig.config.getBoolean("log-console", true)
        val msg = "เริ่มกิจกรรมแชท: $game | คำตอบ: $answer"

        if (consoleLog) {
            module.plugin.logger.info("[ChatGames] $msg")
        }
        if (fileLog) {
            writeLogToFile(msg)
        }
    }

    private fun logRaceStart(race: String, value: String, amount: Int) {
        val fileLog = module.chatConfig.config.getBoolean("log-local", false)
        val consoleLog = module.chatConfig.config.getBoolean("log-console", true)
        val msg = "เริ่มภารกิจการแข่งขัน: $race | เป้าหมาย: $value ($amount)"

        if (consoleLog) {
            module.plugin.logger.info("[ChatGames] $msg")
        }
        if (fileLog) {
            writeLogToFile(msg)
        }
    }

    private fun logGameEnd(game: String, hasWinner: Boolean, winner: String, answer: String) {
        val fileLog = module.chatConfig.config.getBoolean("log-local", false)
        val consoleLog = module.chatConfig.config.getBoolean("log-console", true)
        val msg = "จบกิจกรรม: $game | มีผู้ชนะ: $hasWinner | ผู้ชนะ: $winner | คำตอบ: $answer"

        if (consoleLog) {
            module.plugin.logger.info("[ChatGames] $msg")
        }
        if (fileLog) {
            writeLogToFile(msg)
        }
    }

    private fun writeLogToFile(text: String) {
        try {
            val logFileName = module.chatConfig.config.getString("log-file", "game_log.txt") ?: "game_log.txt"
            val logFile = File(module.chatConfig.folder, logFileName)
            if (!logFile.exists()) {
                logFile.createNewFile()
            }
            val timeStr = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(java.util.Date())
            logFile.appendText("[$timeStr] $text\n")
        } catch (e: Exception) {
            module.plugin.logger.severe("[ChatGames] Failed to write log file: ${e.message}")
        }
    }
}

class ActiveGame(
    val type: String,
    val question: String,
    val answer: String,
    val answers: List<String> = emptyList(),
    val isCaseSensitive: Boolean = true,
    var startTime: Long,
    val timeToGuessSeconds: Int,
    val isRace: Boolean = false,
    val raceTargetAmount: Int = 1,
    val raceTargetValue: String = "",
    val shoppingListOriginal: List<String> = emptyList(),
    val clickUuid: UUID = UUID.randomUUID()
) {
    val raceProgress = ConcurrentHashMap<UUID, Int>()
    var isShoppingListMemorizePhase: Boolean = false
}
