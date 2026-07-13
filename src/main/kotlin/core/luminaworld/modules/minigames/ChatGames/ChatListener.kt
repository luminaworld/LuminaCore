package core.luminaworld.modules.minigames.ChatGames

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent

class ChatListener(private val module: ChatGamesModule) : Listener {

    /**
     * ดักจับสำหรับเซิร์ฟเวอร์ Paper รุ่นใหม่ (AsyncChatEvent)
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPaperChat(event: AsyncChatEvent) {
        if (!module.isEnabled) return
        val game = module.gameManager.currentGame ?: return
        if (game.isRace) return // แข่งขันไม่เกี่ยวข้องกับแชททายผล
        if (game.isShoppingListMemorizePhase) return // อยู่ในเฟสจดจำรายการ

        val player = event.player
        val disabledWorlds = module.chatConfig.config.getStringList("disabled-worlds")
        if (disabledWorlds.contains(player.world.name)) return

        // แปลงข้อความ Component เป็น String ดิบ
        val message = PlainTextComponentSerializer.plainText().serialize(event.originalMessage()).trim()

        val matched = checkAnswer(player, message, game)
        if (matched) {
            val cancelMessage = module.chatConfig.config.getBoolean("cancel-message-when-winning", true)
            if (cancelMessage) {
                event.isCancelled = true
            }
        }
    }

    /**
     * ดักจับสำหรับเซิร์ฟเวอร์ Spigot / Paper รุ่นดั้งเดิม (AsyncPlayerChatEvent)
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onLegacyChat(event: AsyncPlayerChatEvent) {
        if (!module.isEnabled) return
        val game = module.gameManager.currentGame ?: return
        if (game.isRace) return
        if (game.isShoppingListMemorizePhase) return

        val player = event.player
        val disabledWorlds = module.chatConfig.config.getStringList("disabled-worlds")
        if (disabledWorlds.contains(player.world.name)) return

        val message = event.message.trim()

        val matched = checkAnswer(player, message, game)
        if (matched) {
            val cancelMessage = module.chatConfig.config.getBoolean("cancel-message-when-winning", true)
            if (cancelMessage) {
                event.isCancelled = true
            }
        }
    }

    /**
     * ตรวจคำตอบของผู้เล่น
     */
    private fun checkAnswer(player: Player, message: String, game: ActiveGame): Boolean {
        val answersToCheck = if (game.type == "trivia") {
            game.answers
        } else {
            listOf(game.answer)
        }

        var isCorrect = false

        for (ans in answersToCheck) {
            if (game.isCaseSensitive) {
                if (message == ans) {
                    isCorrect = true
                    break
                }
            } else {
                if (message.equals(ans, ignoreCase = true)) {
                    isCorrect = true
                    break
                }
            }
        }

        // หากผู้เล่นพิมพ์ถูกแบบไม่คำนึงถึงเคส แต่ระบบต้องการความตรงเคส (Case-sensitive)
        if (!isCorrect && game.isCaseSensitive) {
            for (ans in answersToCheck) {
                if (message.equals(ans, ignoreCase = true)) {
                    // แจ้งเตือนเรื่องการพิมพ์ผิดเคสตัวเล็ก/ใหญ่
                    val wrongCaseMsg = module.chatConfig.getMessage("wrong_case")
                    if (wrongCaseMsg.isNotBlank()) {
                        val prefix = module.chatConfig.config.getString("prefix", "&a[ChatGames]") ?: "&a[ChatGames]"
                        val formatted = wrongCaseMsg.replace("%prefix%", prefix)
                        player.sendMessage(module.parseToComponent(formatted))
                    }
                    break
                }
            }
        }

        if (isCorrect) {
            val timeTaken = (System.currentTimeMillis() - game.startTime) / 1000.0
            
            // ประมวลผลชัยชนะบน Main Thread (ปลอดภัยสำหรับการจ่ายรางวัล สั่งรันคำสั่ง และจัดการผู้เล่น)
            Bukkit.getGlobalRegionScheduler().execute(module.plugin) {
                // เช็คอีกครั้งว่าเกมนี้ยังไม่ถูกผู้เล่นอื่นตอบไปก่อนหน้านี้
                if (module.gameManager.currentGame == game) {
                    module.gameManager.handleWinner(player, timeTaken)
                }
            }
            return true
        }

        return false
    }
}
