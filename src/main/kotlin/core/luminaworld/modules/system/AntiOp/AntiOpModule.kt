package core.luminaworld.modules.system.AntiOp

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.TextDialogInput
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.BanList
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.permissions.PermissionAttachment
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AntiOpModule(plugin: LuminaCore) : LuminaModule(plugin, "AntiOp"), Listener {

    class VerificationSession(
        val originalOp: Boolean,
        val startTime: Long,
        var attempts: Int,
        val attachment: PermissionAttachment,
        var dialogTask: ScheduledTask? = null,
        var timeoutTask: ScheduledTask? = null
    )

    private val pendingVerifications = ConcurrentHashMap<UUID, VerificationSession>()
    private var pollingTask: ScheduledTask? = null

    override fun onEnable() {
        // ลงทะเบียน Event Listeners
        plugin.server.pluginManager.registerEvents(this, plugin)

        // เริ่มระบบ Polling ตรวจเช็คความปลอดภัยทุกๆ 1 วินาที (20 ticks)
        startPollingTask()
    }

    override fun onDisable() {
        // เคลียร์ Polling Task
        pollingTask?.cancel()
        pollingTask = null

        // เคลียร์และคืนสิทธิ์ผู้เล่นที่ยังค้างการยืนยัน
        val iterator = pendingVerifications.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val player = plugin.server.getPlayer(entry.key)
            val session = entry.value

            session.dialogTask?.cancel()
            session.timeoutTask?.cancel()

            if (player != null && player.isOnline) {
                if (session.originalOp) {
                    player.isOp = true
                }
                try {
                    player.removeAttachment(session.attachment)
                } catch (e: Exception) {}
            }
            iterator.remove()
        }

        // ยกเลิก Listener ของคลาสนี้
        HandlerList.unregisterAll(this as Listener)
    }

    override fun loadConfig() {
        super.loadConfig()
        // สวิตช์เปิด/ปิดโมดูลย่อยโดยตรวจสอบทั้ง settings.enabled และ antiop.enabled
        config?.let {
            if (it.contains("antiop.enabled")) {
                isEnabled = it.getBoolean("antiop.enabled", true)
            }
        }
    }

    private fun startPollingTask() {
        pollingTask = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { _ ->
            if (!isEnabled) return@runAtFixedRate

            for (player in plugin.server.onlinePlayers) {
                // รันใน Entity Scheduler ของผู้เล่นเพื่อความปลอดภัยของระบบ Folia
                player.scheduler.execute(plugin, {
                    checkPlayerOpStatus(player)
                }, null, 0L)
            }
        }, 20L, 20L)
    }

    private fun checkPlayerOpStatus(player: Player) {
        if (!isEnabled) return

        // เช็คว่ามีสิทธิ์ OP หรือมี permission * หรือไม่
        val hasOpOrWildcard = player.isOp || player.hasPermission("*")

        if (hasOpOrWildcard) {
            if (isWhitelisted(player.name)) {
                // ถ้าอยู่ใน Whitelist และยังไม่เริ่มกระบวนการยืนยันตัวตน
                if (!pendingVerifications.containsKey(player.uniqueId)) {
                    startVerification(player)
                }
            } else {
                // ถ้าไม่อยู่ใน Whitelist แต่ตรวจพบว่ามีสิทธิ์ OP หรือ * (การเสกสิทธิ์มั่ว)
                val banReason = colorize(
                    config?.getString("antiop.ban-reason-unauthorized-op")
                        ?: "&c&l[AntiOP] บัญชีถูกระงับ: ตรวจพบสิทธิ์ OP โดยไม่ได้รับอนุญาต"
                )
                banPlayer(player.name, banReason)
                player.kick(parseToComponent(banReason))

                // ส่งบรอดแคสต์
                if (config?.getBoolean("antiop.broadcast.ban-unauthorized-op.enabled", true) == true) {
                    val broadcastMsg = config?.getString("antiop.broadcast.ban-unauthorized-op.message", "<red>%player% <gray>ถูกระงับการใช้งานเนื่องจากครอบครองสิทธิ์ OP โดยไม่ได้รับอนุญาต") ?: ""
                    broadcastMessage(broadcastMsg.replace("%player%", player.name))
                }
            }
        }
    }

    private fun startVerification(player: Player) {
        val originalOp = player.isOp
        player.isOp = false

        // สร้าง Permission Attachment เชิงลบเพื่อบล็อกสิทธิ์ *
        val attachment = player.addAttachment(plugin)
        attachment.setPermission("*", false)

        val session = VerificationSession(
            originalOp = originalOp,
            startTime = System.currentTimeMillis(),
            attempts = 0,
            attachment = attachment
        )
        pendingVerifications[player.uniqueId] = session

        // สร้างและแสดง Dialog ทันที พร้อมกับรัน Task เด้งหน้าต่างกรณีผู้เล่นกดปิด
        val dialogTask = player.scheduler.runAtFixedRate(plugin, { _ ->
            if (pendingVerifications.containsKey(player.uniqueId)) {
                showVerificationDialog(player)
            }
        }, null, 1L, 100L) // แสดงทันที และแสดงใหม่ทุก 5 วินาทีหากผู้เล่นพยายามกดปิด

        session.dialogTask = dialogTask

        // เริ่มตั้งเวลาหมดเวลา (Timeout)
        val timeoutSec = config?.getLong("antiop.timeout", 1000L) ?: 1000L
        val timeoutTicks = timeoutSec * 20L
        val timeoutTask = player.scheduler.runDelayed(plugin, { _ ->
            handleTimeout(player)
        }, null, timeoutTicks)

        session.timeoutTask = timeoutTask
    }

    private fun showVerificationDialog(player: Player) {
        if (!isEnabled) return

        val title = parseToComponent(config?.getString("antiop.dialog.title") ?: "⚠ โปรดยืนยันตัวตน (AntiOP)")
        val bodyLine1 = config?.getString("antiop.dialog.body-line1") ?: "ระบบตรวจพบสิทธิ์ผู้ดูแลระบบ (OP) บนบัญชีของคุณ\nกรุณากรอกรหัสผ่าน AntiOP เพื่อยืนยันสิทธิ์การใช้งาน"
        val bodyLine2 = config?.getString("antiop.dialog.body-line2") ?: "⚠ คำเตือน: โปรดรักษาความลับของรหัสผ่านนี้ ห้ามเปิดเผยแก่ผู้อื่นโดยเด็ดขาด!"
        val bodyText = "$bodyLine1\n$bodyLine2"
        val bodyComponent = parseToComponent(bodyText)

        val inputLabel = parseToComponent(config?.getString("antiop.dialog.input-label") ?: "รหัสผ่าน AntiOP")
        val buttonSubmit = parseToComponent(config?.getString("antiop.dialog.button-submit") ?: "✔ ยืนยันตัวตน")

        // สร้าง Text Input
        val textInput = DialogInput.text("code", inputLabel)
            .width(200)
            .maxLength(32)
            .build()

        // สร้าง Action Button
        val submitAction = ActionButton.builder(buttonSubmit)
            .action(DialogAction.customClick(DialogActionCallback { response, audience ->
                val clickedPlayer = audience as? Player ?: return@DialogActionCallback
                clickedPlayer.scheduler.execute(plugin, {
                    handleDialogSubmit(clickedPlayer, response.getText("code"))
                }, null, 0L)
            }, ClickCallback.Options.builder().build()))
            .build()

        // สร้าง Dialog โครงสร้าง Notice
        val dialog = Dialog.create { builder ->
            builder.empty()
                .type(DialogType.notice(submitAction))
                .base(
                    DialogBase.builder(title)
                        .body(listOf(DialogBody.plainMessage(bodyComponent)))
                        .inputs(listOf(textInput))
                        .canCloseWithEscape(true)
                        .build()
                )
        }

        player.showDialog(dialog)
    }

    private fun handleDialogSubmit(player: Player, enteredCode: String?) {
        val session = pendingVerifications[player.uniqueId] ?: return

        val correctCode = config?.getString("antiop.code", "LLW") ?: "LLW"
        val maxAttempts = config?.getInt("antiop.max_attempts", 2) ?: 2

        if (enteredCode == correctCode) {
            // ยืนยันสำเร็จ!
            pendingVerifications.remove(player.uniqueId)

            session.dialogTask?.cancel()
            session.timeoutTask?.cancel()

            // คืนสิทธิ์ทั้งหมด
            if (session.originalOp) {
                player.isOp = true
            }
            try {
                player.removeAttachment(session.attachment)
            } catch (e: Exception) {}

            val successMsg = config?.getString("antiop.verify-success") ?: "<green><bold>ยืนยันตัวตนสำเร็จ! คืนสิทธิ์การใช้งานเรียบร้อยแล้ว"
            val successSub = config?.getString("antiop.verify-success-sub") ?: "<gray>คุณสามารถเข้าใช้งานระบบได้ตามปกติ"

            player.sendMessage(parseToComponent(successMsg))
            player.sendMessage(parseToComponent(successSub))
        } else {
            // กรอกรหัสไม่ถูกต้อง
            session.attempts++
            if (session.attempts >= maxAttempts) {
                // กรอกรหัสผิดเกินจำนวนครั้งกำหนด -> แบน
                pendingVerifications.remove(player.uniqueId)
                session.dialogTask?.cancel()
                session.timeoutTask?.cancel()

                try {
                    player.removeAttachment(session.attachment)
                } catch (e: Exception) {}

                val banReason = colorize(
                    config?.getString("antiop.ban-reason-max-attempts")
                        ?: "&c&l[AntiOP] บัญชีถูกระงับ: กรอกรหัสผ่านผิดเกิน %max% ครั้ง"
                ).replace("%max%", maxAttempts.toString())

                banPlayer(player.name, banReason)
                player.kick(parseToComponent(banReason))

                // บรอดแคสต์
                if (config?.getBoolean("antiop.broadcast.max-attempts.enabled", true) == true) {
                    val broadcastMsg = config?.getString("antiop.broadcast.max-attempts.message", "<gray>%player% <red>ถูกระงับการใช้งานเนื่องจากกรอกรหัสผ่านผิดเกินจำนวนครั้งที่กำหนด") ?: ""
                    broadcastMessage(broadcastMsg.replace("%player%", player.name))
                }
            } else {
                // รหัสผ่านผิด แจ้งและให้กรอกใหม่
                val remaining = maxAttempts - session.attempts
                val wrongMsg = config?.getString("antiop.verify-wrong")
                    ?: "<red>รหัสผ่านไม่ถูกต้อง! สามารถระบุใหม่ได้อีก <yellow>%remaining% <red>ครั้ง"
                player.sendMessage(parseToComponent(wrongMsg.replace("%remaining%", remaining.toString())))

                showVerificationDialog(player)
            }
        }
    }

    private fun handleTimeout(player: Player) {
        val session = pendingVerifications.remove(player.uniqueId) ?: return

        session.dialogTask?.cancel()
        session.timeoutTask?.cancel()

        try {
            player.removeAttachment(session.attachment)
        } catch (e: Exception) {}

        val kickMsg = config?.getString("antiop.kick-timeout") ?: "<red><bold>หมดเวลาการยืนยันตัวตน AntiOP"
        player.kick(parseToComponent(kickMsg))

        // บรอดแคสต์
        if (config?.getBoolean("antiop.broadcast.timeout.enabled", true) == true) {
            val broadcastMsg = config?.getString("antiop.broadcast.timeout.message", "<gray>%player% <red>ถูกเชิญออกจากเซิร์ฟเวอร์เนื่องจากไม่ยืนยันตัวตนภายในเวลาที่กำหนด") ?: ""
            broadcastMessage(broadcastMsg.replace("%player%", player.name))
        }
    }

    private fun isWhitelisted(playerName: String): Boolean {
        val whitelist = config?.getStringList("antiop.whitelist") ?: emptyList()
        return whitelist.any { it.equals(playerName, ignoreCase = true) }
    }

    @Suppress("DEPRECATION")
    private fun banPlayer(playerName: String, reason: String) {
        val banReason = colorize(reason)
        try {
            val banList: org.bukkit.BanList<org.bukkit.BanEntry<Any>> = plugin.server.getBanList(org.bukkit.BanList.Type.NAME)
            banList.addBan(playerName, banReason, null, "AntiOP")
        } catch (e: Exception) {
            plugin.logger.severe("[AntiOP] Failed to ban player $playerName: ${e.message}")
        }
    }

    private fun broadcastMessage(msg: String) {
        val prefix = config?.getString("antiop.prefix", "<dark_gray>[<red><bold>Lumina-2</bold><dark_gray>] <reset>") ?: ""
        val component = parseToComponent(prefix + msg)
        plugin.server.broadcast(component)
    }

    private fun parseToComponentWithPrefix(msg: String): Component {
        val prefix = config?.getString("antiop.prefix", "<dark_gray>[<red><bold>Lumina-2</bold><dark_gray>] <reset>") ?: ""
        return parseToComponent(prefix + msg)
    }

    private fun colorize(msg: String): String {
        return msg.replace('&', '§')
    }

    // ============================================================================
    // EVENT HANDLERS
    // ============================================================================

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        player.scheduler.execute(plugin, {
            checkPlayerOpStatus(player)
        }, null, 0L)
    }

    @EventHandler
    fun onPlayerQuitModule(event: PlayerQuitEvent) {
        val player = event.player
        val session = pendingVerifications.remove(player.uniqueId)
        if (session != null) {
            session.dialogTask?.cancel()
            session.timeoutTask?.cancel()
            try {
                player.removeAttachment(session.attachment)
            } catch (e: Exception) {}
        }
    }

    // บล็อกคำสั่งของผู้เล่นที่ยังค้างการยืนยัน
    @EventHandler
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        if (pendingVerifications.containsKey(player.uniqueId)) {
            event.isCancelled = true
            val msg = config?.getString("antiop.command-blocked") ?: "<red>กรุณายืนยันตัวตน AntiOP ให้เสร็จสิ้นก่อนใช้งานคำสั่ง"
            player.sendMessage(parseToComponent(msg))
        }
    }

    // บล็อกการคุยในแชทของผู้เล่นที่ยังค้างการยืนยัน
    @EventHandler
    fun onPlayerChat(event: AsyncChatEvent) {
        val player = event.player
        if (pendingVerifications.containsKey(player.uniqueId)) {
            event.isCancelled = true
            val msg = config?.getString("antiop.command-blocked") ?: "<red>กรุณายืนยันตัวตน AntiOP ให้เสร็จสิ้นก่อนใช้งานคำสั่ง"
            player.sendMessage(parseToComponent(msg))
        }
    }

    // บล็อกการเคลื่อนที่ของผู้เล่นที่ยังค้างการยืนยัน
    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        if (pendingVerifications.containsKey(player.uniqueId)) {
            val from = event.from
            val to = event.to
            if (from.x != to.x || from.z != to.z || from.y != to.y) {
                event.isCancelled = true
            }
        }
    }

    // บล็อกการทุบบล็อก
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (pendingVerifications.containsKey(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    // บล็อกการวางบล็อก
    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (pendingVerifications.containsKey(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    // บล็อกการปฏิสัมพันธ์
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (pendingVerifications.containsKey(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    // บล็อกการทิ้งไอเทม
    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (pendingVerifications.containsKey(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    // บล็อกการเก็บไอเทม
    @EventHandler
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (pendingVerifications.containsKey(player.uniqueId)) {
            event.isCancelled = true
        }
    }

    // บล็อกการรับความเสียหาย (ทำให้เป็นอมตะชั่วคราวขณะพิมพ์โค้ด)
    @EventHandler
    fun onEntityDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (pendingVerifications.containsKey(player.uniqueId)) {
            event.isCancelled = true
        }
    }

    // บล็อกการทำร้ายผู้อื่น
    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (pendingVerifications.containsKey(player.uniqueId)) {
            event.isCancelled = true
        }
    }

    // บล็อกการคลิกช่องเก็บของ
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (pendingVerifications.containsKey(player.uniqueId)) {
            event.isCancelled = true
        }
    }

    // ============================================================================
    // PRIVILEGE ESCALATION PROTECTION (การแฮก/เสก OP)
    // ============================================================================

    // ตรวจจับคำสั่งคอนโซล
    @EventHandler
    fun onServerCommand(event: ServerCommandEvent) {
        if (!isEnabled) return
        val command = event.command.trim()
        handleEscalationCheck(command, "Console", event)
    }

    // ตรวจจับคำสั่งผู้เล่น
    @EventHandler
    fun onPlayerCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        if (!isEnabled) return
        val command = event.message.trim().removePrefix("/")
        handleEscalationCheck(command, event.player.name, event)
    }

    private fun handleEscalationCheck(command: String, senderName: String, event: Any) {
        val parts = command.split("\\s+".toRegex())
        if (parts.isNotEmpty() && parts[0].equals("op", ignoreCase = true)) {
            if (parts.size > 1) {
                val targetName = parts[1]
                if (!isWhitelisted(targetName)) {
                    // ยกเลิกคำสั่งทันที
                    if (event is org.bukkit.event.Cancellable) {
                        event.isCancelled = true
                    }

                    if (senderName.equals("Console", ignoreCase = true)) {
                        // ถ้าเกิดจาก Console พยายามให้สิทธิ์
                        handleConsoleEscalation(targetName)
                    } else {
                        // ถ้าเกิดจาก Player พยายามให้สิทธิ์
                        val sender = Bukkit.getPlayerExact(senderName)
                        if (sender != null) {
                            handlePlayerEscalation(sender, targetName)
                        } else {
                            // ปลอดภัยไว้ก่อน แบน target
                            handleConsoleEscalation(targetName)
                        }
                    }
                }
            }
        }
    }

    private fun handlePlayerEscalation(sender: Player, targetName: String) {
        val senderBanReason = colorize(
            config?.getString("antiop.ban-reason-escalation-sender")
                ?: "&c&l[AntiOP] บัญชีถูกระงับ: พยายามมอบสิทธิ์ OP แก่ผู้เล่นที่ไม่อยู่ในรายชื่อ"
        )
        val targetBanReason = colorize(
            config?.getString("antiop.ban-reason-escalation-target")
                ?: "&c&l[AntiOP] บัญชีถูกระงับ: ได้รับสิทธิ์ OP จากแหล่งที่ไม่ได้รับอนุญาต"
        )

        // แบนผู้พยายามให้สิทธิ์ (Sender)
        banPlayer(sender.name, senderBanReason)
        sender.kick(parseToComponent(senderBanReason))

        // แบนผู้ได้รับสิทธิ์ (Target)
        banPlayer(targetName, targetBanReason)
        val targetPlayer = plugin.server.getPlayerExact(targetName)
        targetPlayer?.kick(parseToComponent(targetBanReason))

        // บรอดแคสต์
        val broadcastMsg = config?.getString("antiop.broadcast.escalation-player.message")
            ?: "<red>ตรวจพบ <bold>%sender%<reset><red> พยายามมอบสิทธิ์ OP แก่ <yellow>%target%<red> → บัญชีของทั้งสองฝ่ายถูกระงับการใช้งานทันที"
        broadcastMessage(
            broadcastMsg
                .replace("%sender%", sender.name)
                .replace("%target%", targetName)
        )
    }

    private fun handleConsoleEscalation(targetName: String) {
        val targetBanReason = colorize(
            config?.getString("antiop.ban-reason-console-target")
                ?: "&c&l[AntiOP] บัญชีถูกระงับ: ตรวจพบการพยายามยกระดับสิทธิ์โดยไม่ได้รับอนุญาต"
        )

        // แบนผู้ได้รับสิทธิ์ (Target)
        banPlayer(targetName, targetBanReason)
        val targetPlayer = plugin.server.getPlayerExact(targetName)
        targetPlayer?.kick(parseToComponent(targetBanReason))

        // บรอดแคสต์
        val broadcastMsg = config?.getString("antiop.broadcast.escalation-console.message")
            ?: "<red>ตรวจพบการพยายามมอบสิทธิ์ OP แก่ <yellow>%target%<red> ผ่านคอนโซล → บัญชีเป้าหมายถูกระงับการใช้งานทันทีเพื่อความปลอดภัย"
        broadcastMessage(broadcastMsg.replace("%target%", targetName))
    }
}
