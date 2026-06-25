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
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
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
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class AntiOpModule(plugin: LuminaCore) : LuminaModule(plugin, "AntiOp"), Listener {

    // โมเดลข้อมูลชั่วคราว
    data class FrozenLook(val yaw: Float, val pitch: Float)
    data class PrivilegeCommandMatch(val targetName: String, val type: String, val detail: String?, val requiresVerifiedTarget: Boolean)
    data class IpAttemptRecord(val attempts: Int, val lastAttemptMs: Long)

    // สถานะและการจัดเก็บข้อมูลระบบ
    private val pendingVerification = ConcurrentHashMap.newKeySet<String>()
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    private val verifiedPlayers = ConcurrentHashMap.newKeySet<String>()
    private val sessionGrantedOp = ConcurrentHashMap.newKeySet<String>()
    private val pendingTasks = ConcurrentHashMap<String, ScheduledTask>()
    private val frozenLook = ConcurrentHashMap<String, FrozenLook>()
    private val ipAttemptLog = ConcurrentHashMap<String, IpAttemptRecord>()
    
    @Volatile
    private var hasPendingVerifications = false

    companion object {
        private val SAFE_COMMAND_TARGET = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}")
        private val IP_LOCKOUT_MS = TimeUnit.MINUTES.toMillis(15L)
        private val IP_DECAY_MS = TimeUnit.MINUTES.toMillis(30L)
        private const val IP_MAX_ATTEMPTS = 3
    }

    // ค่าการตั้งค่าต่างๆ (โหลดจาก config)
    private var antiopCode = "PASSWORD"
    private var antiopPrefix = "&8[&cAntiOP&8] &r"
    private var antiopTimeout = 1000
    private var antiopMaxAttempts = 2
    private var antiopBlockParentGroups = emptySet<String>()
    private var antiopSuspendedGameMode = GameMode.ADVENTURE
    private var antiopVerifiedGameMode = GameMode.CREATIVE
    private var antiopFreezeLook = true
    private val antiopWhitelist = mutableListOf<String>()

    override fun loadConfig() {
        super.loadConfig()
        config?.let {
            antiopCode = it.getString("antiop.code", "PASSWORD") ?: "PASSWORD"
            antiopPrefix = it.getString("antiop.prefix", "&8[&cAntiOP&8] &r") ?: "&8[&cAntiOP&8] &r"
            antiopTimeout = it.getInt("antiop.timeout", 1000)
            antiopMaxAttempts = it.getInt("antiop.max_attempts", 2)
            
            antiopWhitelist.clear()
            it.getStringList("antiop.whitelist").forEach { name ->
                if (name.isNotBlank()) {
                    antiopWhitelist.add(name.trim())
                }
            }
            
            antiopBlockParentGroups = it.getStringList("antiop.block-parent-groups")
                .map { g -> g.lowercase(Locale.ROOT) }
                .toSet()
                
            val suspendedRaw = it.getString("antiop.suspended-gamemode", "ADVENTURE")
            val verifiedRaw = it.getString("antiop.verified-gamemode", "CREATIVE")
            antiopSuspendedGameMode = parseGameMode(suspendedRaw, GameMode.ADVENTURE, "antiop.suspended-gamemode")
            antiopVerifiedGameMode = parseGameMode(verifiedRaw, GameMode.CREATIVE, "antiop.verified-gamemode")
            antiopFreezeLook = it.getBoolean("antiop.freeze-look", true)
        }
    }

    override fun onEnable() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        logInfo("enabled", "[AntiOP] Module enabled.")
    }

    override fun onDisable() {
        shutdown()
        HandlerList.unregisterAll(this as Listener)
        logInfo("disabled", "[AntiOP] Module disabled.")
    }

    private fun shutdown() {
        pendingTasks.values.forEach { it.cancel() }
        pendingTasks.clear()
        pendingVerification.clear()
        verifiedPlayers.clear()
        sessionGrantedOp.clear()
        attempts.clear()
        ipAttemptLog.clear()
        frozenLook.clear()
        hasPendingVerifications = false
    }

    private fun executeConsole(cmd: String) {
        plugin.server.globalRegionScheduler.execute(plugin) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
        }
    }

    // ============================================================================
    // CORE LOGIC FLOW
    // ============================================================================

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        player.scheduler.execute(plugin, {
            handleJoin(player)
        }, null, 0L)
    }

    private fun handleJoin(player: Player) {
        if (!isEnabled) return
        val playerName = player.name
        val ip = getPlayerIp(player)
        val hasOp = player.isOp

        if (!hasOp) {
            executeConsole("lp user $playerName permission unset *")
            verifiedPlayers.remove(playerName)
            sessionGrantedOp.remove(playerName)
            if (checkWhitelist(playerName)) {
                suspendPlayer(player)
            }
            return
        }

        if (isIpLocked(ip)) {
            logWarning("ip-locked", "[AntiOP] IP Locked for %player% (%ip%)", "player" to playerName, "ip" to ip)
            player.isOp = false
            executeConsole("lp user $playerName permission unset *")
            player.kick(prefixed(antiopPrefix, config?.getString("antiop.kick-banned-target") ?: "&c&lบัญชีถูกระงับ: ตรวจพบสิทธิ์ OP จากแหล่งที่ไม่ได้รับอนุญาต"))
            return
        }

        if (!checkWhitelist(playerName)) {
            logSevere("unauthorized-op", "[AntiOP] UNAUTHORIZED OP DETECTED FOR %player% (%ip%)", "player" to playerName, "ip" to ip)
            player.isOp = false
            executeConsole("lp user $playerName permission unset *")
            player.kick(prefixed(antiopPrefix, config?.getString("antiop.kick-banned-target") ?: "&c&lบัญชีถูกระงับ: ตรวจพบสิทธิ์ OP จากแหล่งที่ไม่ได้รับอนุญาต"))
            banPlayer(playerName, config?.getString("antiop.ban-reason-unauthorized-op") ?: "&c&l[AntiOP] ระงับบัญชีถาวร: ตรวจพบการถือครองสิทธิ์ OP โดยไม่ได้รับอนุญาต")
            maybeBroadcast("ban-unauthorized-op", "player", playerName)
            return
        }

        if (verifiedPlayers.contains(playerName)) {
            sessionGrantedOp.add(playerName)
            return
        }

        suspendPlayer(player)
    }

    private fun suspendPlayer(player: Player) {
        val playerName = player.name
        player.isOp = false
        executeConsole("lp user $playerName permission unset *")
        executeConsole("lp user $playerName permission unset luckperms.*")
        
        player.gameMode = antiopSuspendedGameMode
        
        if (antiopFreezeLook) {
            val loc = player.location
            frozenLook[playerName] = FrozenLook(loc.yaw, loc.pitch)
        } else {
            frozenLook.remove(playerName)
        }

        plugin.suspendedPlayers.add(player.uniqueId)
        attempts[playerName] = AtomicInteger(0)
        pendingVerification.add(playerName)
        hasPendingVerifications = true

        showVerificationDialog(player)

        val capturedName = playerName
        val timeoutTask = Bukkit.getAsyncScheduler().runDelayed(plugin, { _ ->
            if (!pendingVerification.contains(capturedName)) return@runDelayed
            val online = Bukkit.getPlayer(capturedName) ?: return@runDelayed
            online.scheduler.run(plugin, { _ ->
                if (!pendingVerification.contains(capturedName)) return@run
                cleanupVerification(capturedName)
                online.kick(prefixed(antiopPrefix, config?.getString("antiop.kick-timeout") ?: "&c&lหมดเวลาการยืนยันตัวตนตามมาตรการความปลอดภัย"))
                maybeBroadcast("timeout", "player", capturedName)
            }, null)
        }, antiopTimeout.toLong(), TimeUnit.SECONDS)
        
        pendingTasks[playerName] = timeoutTask
    }

    private fun showVerificationDialog(player: Player) {
        if (!isEnabled) return

        val title = parseToComponent(config?.getString("antiop.dialog.title") ?: "⚠ ระบบยืนยันตัวตนผู้ดูแลระบบ (AntiOP)")
        val bodyLine1 = config?.getString("antiop.dialog.body-line1") ?: "ตรวจพบการเข้าใช้งานด้วยสิทธิ์ผู้ดูแลระบบ (OP)\nโปรดระบุรหัสผ่านยืนยันตัวตนเพื่อเข้าใช้งานระบบความปลอดภัย"
        val bodyLine2 = config?.getString("antiop.dialog.body-line2") ?: "⚠ คำเตือน: โปรดเก็บรักษารหัสผ่านนี้เป็นความลับขั้นสูงสุด ห้ามเปิดเผยแก่ผู้อื่นโดยเด็ดขาด!"
        val bodyComponent = parseToComponent("$bodyLine1\n$bodyLine2")

        val inputLabel = parseToComponent(config?.getString("antiop.dialog.input-label") ?: "รหัสผ่านยืนยันตัวตน (AntiOP)")
        val buttonSubmit = parseToComponent(config?.getString("antiop.dialog.button-submit") ?: "✔ ยืนยันสิทธิ์")

        val textInput = DialogInput.text("code", inputLabel)
            .width(200)
            .maxLength(32)
            .build()

        val submitAction = ActionButton.builder(buttonSubmit)
            .action(DialogAction.customClick(DialogActionCallback { response, audience ->
                val clickedPlayer = audience as? Player ?: return@DialogActionCallback
                clickedPlayer.scheduler.execute(plugin, {
                    handleDialogSubmit(clickedPlayer, response.getText("code"))
                }, null, 0L)
            }, net.kyori.adventure.text.event.ClickCallback.Options.builder().build()))
            .build()

        val dialog = Dialog.create { builder ->
            builder.empty()
                .type(DialogType.notice(submitAction))
                .base(
                    DialogBase.builder(title)
                        .body(listOf(DialogBody.plainMessage(bodyComponent)))
                        .inputs(listOf(textInput))
                        .canCloseWithEscape(false)
                        .build()
                )
        }

        player.showDialog(dialog)
    }

    private fun handleDialogSubmit(player: Player, enteredCode: String?) {
        val playerName = player.name
        val ip = getPlayerIp(player)
        if (!pendingVerification.contains(playerName)) return

        player.scheduler.run(plugin, { _ ->
            if (enteredCode != null && enteredCode == antiopCode) {
                player.isOp = true
                executeConsole("lp user $playerName permission set * true")
                verifiedPlayers.add(playerName)
                sessionGrantedOp.add(playerName)
                clearIpAttempts(ip)
                cleanupVerification(playerName)
                
                player.gameMode = antiopVerifiedGameMode
                frozenLook.remove(playerName)
                
                player.sendMessage(Component.empty())
                player.sendMessage(prefixed(antiopPrefix, config?.getString("antiop.verify-success") ?: "&a&lยืนยันสิทธิ์สำเร็จ คืนสิทธิ์การเข้าถึงระบบเรียบร้อยแล้ว"))
                player.sendMessage(prefixed(antiopPrefix, config?.getString("antiop.verify-success-sub") ?: "&7บัญชีของคุณได้รับการอนุญาตให้เข้าใช้งานตามปกติ"))
                player.sendMessage(Component.empty())
            } else {
                val cur = attempts.computeIfAbsent(playerName) { AtomicInteger(0) }.incrementAndGet()
                recordFailedAttempt(ip)
                
                if (cur >= antiopMaxAttempts) {
                    lockIp(ip)
                    cleanupVerification(playerName)
                    val reason = (config?.getString("antiop.ban-reason-max-attempts") ?: "&c&l[AntiOP] ระงับบัญชีถาวร: ระบุรหัสผ่านผิดเกินกำหนดสูงสุด (%max% ครั้ง)")
                        .replace("{max}", antiopMaxAttempts.toString()).replace("%max%", antiopMaxAttempts.toString())
                    executeConsole("ban $playerName $reason")
                    maybeBroadcast("max-attempts", "player", playerName)
                } else {
                    val remaining = antiopMaxAttempts - cur
                    val wrongMsg = (config?.getString("antiop.verify-wrong") ?: "&cรหัสผ่านไม่ถูกต้อง โปรดลองอีกครั้ง (คงเหลือโอกาสทดลอง %remaining% ครั้ง)")
                        .replace("{remaining}", remaining.toString()).replace("%remaining%", remaining.toString())
                    player.sendMessage(prefixed(antiopPrefix, wrongMsg))
                    showVerificationDialog(player)
                }
            }
        }, null)
    }

    private fun cleanupVerification(playerName: String) {
        pendingVerification.remove(playerName)
        attempts.remove(playerName)
        pendingTasks.remove(playerName)?.cancel()
        frozenLook.remove(playerName)
        hasPendingVerifications = pendingVerification.isNotEmpty()
        
        val p = Bukkit.getPlayer(playerName)
        if (p != null) {
            plugin.suspendedPlayers.remove(p.uniqueId)
        }
    }

    fun removeVerified(playerName: String) {
        verifiedPlayers.remove(playerName)
        sessionGrantedOp.remove(playerName)
        frozenLook.remove(playerName)
    }

    private fun checkWhitelist(playerName: String): Boolean {
        return antiopWhitelist.any { it.equals(playerName, ignoreCase = true) }
    }

    fun addToWhitelist(name: String) {
        val trimmed = name.trim()
        if (!antiopWhitelist.contains(trimmed)) {
            antiopWhitelist.add(trimmed)
            saveWhitelist(antiopWhitelist)
        }
    }

    fun removeFromWhitelist(name: String) {
        val trimmed = name.trim()
        if (antiopWhitelist.remove(trimmed)) {
            saveWhitelist(antiopWhitelist)
        }
    }

    private fun saveWhitelist(whitelist: List<String>) {
        config?.set("antiop.whitelist", whitelist)
        try {
            config?.save(configFile)
        } catch (e: Exception) {
            logWarning("save-whitelist-failed", "[AntiOP] Failed to save whitelist: %error%", "error" to (e.message ?: ""))
        }
    }

    // ============================================================================
    // PRIVILEGE ESCALATION PROTECTION
    // ============================================================================

    @EventHandler(priority = EventPriority.LOWEST)
    fun onConsoleCommand(event: ServerCommandEvent) {
        if (!isEnabled) return
        val cmd = event.command.trim()
        val match = extractPrivilegeCommand(cmd) ?: return
        val targetName = match.targetName

        if (!isSafeCommandTarget(targetName)) {
            event.isCancelled = true
            logWarning("unsafe-console-command", "[AntiOP] Unsafe privilege target blocked from console command: %command%", "command" to cmd)
            return
        }

        val target = Bukkit.getPlayer(targetName)
        if (target != null) {
            if (!checkWhitelist(targetName)) {
                event.isCancelled = true
                val reason = config?.getString("antiop.ban-reason-console-target") ?: "&c&l[AntiOP] ระงับบัญชีถาวร: ตรวจพบความพยายามยกระดับสิทธิ์บัญชีโดยมิชอบ"
                executeConsole("ban $targetName $reason")
                maybeBroadcast("escalation-console", "target", targetName)
                logWarning("escalation-blocked", "[AntiOP] Console privilege escalation blocked for target '%target%': %command%", "target" to targetName, "command" to cmd)
                return
            }
            if (!verifiedPlayers.contains(target.name)) {
                event.isCancelled = true
                target.scheduler.run(plugin, { _ -> suspendPlayer(target) }, null)
            }
        } else {
            if (!checkWhitelist(targetName)) {
                event.isCancelled = true
                val reason = config?.getString("antiop.ban-reason-console-target") ?: "&c&l[AntiOP] ระงับบัญชีถาวร: ตรวจพบความพยายามยกระดับสิทธิ์บัญชีโดยมิชอบ"
                executeConsole("ban $targetName $reason")
                maybeBroadcast("escalation-console", "target", targetName)
                logWarning("escalation-blocked-offline", "[AntiOP] Console privilege escalation blocked for offline target '%target%': %command%", "target" to targetName, "command" to cmd)
                return
            }
            if (match.requiresVerifiedTarget) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerPrivilegeCommand(event: PlayerCommandPreprocessEvent) {
        if (!isEnabled) return
        val sender = event.player
        val senderName = sender.name

        if (pendingVerification.contains(senderName)) {
            event.isCancelled = true
            return
        }

        val cmd = event.message.substring(1).trim()
        val match = extractPrivilegeCommand(cmd) ?: return
        val targetName = match.targetName

        if (!isSafeCommandTarget(targetName)) {
            event.isCancelled = true
            sender.scheduler.run(plugin, { _ ->
                sender.isOp = false
                executeConsole("lp user $senderName permission unset *")
                val reason = config?.getString("antiop.ban-reason-escalation-sender") ?: "&c&l[AntiOP] ระงับบัญชีถาวร: พยายามมอบสิทธิ์ระดับสูงแก่บัญชีที่ไม่ได้รับอนุญาต"
                executeConsole("ban $senderName $reason")
            }, null)
            return
        }

        val target = Bukkit.getPlayer(targetName)
        if (target != null) {
            if (!checkWhitelist(targetName)) {
                event.isCancelled = true
                sender.scheduler.run(plugin, { _ ->
                    sender.isOp = false
                    executeConsole("lp user $senderName permission unset *")
                    val reason = config?.getString("antiop.ban-reason-escalation-sender") ?: "&c&l[AntiOP] ระงับบัญชีถาวร: พยายามมอบสิทธิ์ระดับสูงแก่บัญชีที่ไม่ได้รับอนุญาต"
                    executeConsole("ban $senderName $reason")
                }, null)
                
                executeConsole("lp user $targetName permission unset *")
                val reason = config?.getString("antiop.ban-reason-escalation-target") ?: "&c&l[AntiOP] ระงับบัญชีถาวร: ได้รับการยกระดับสิทธิ์จากแหล่งที่ไม่ได้รับการอนุญาต"
                executeConsole("ban $targetName $reason")
                
                target.scheduler.run(plugin, { _ ->
                    target.kick(prefixed(antiopPrefix, config?.getString("antiop.kick-banned-target") ?: "&c&lบัญชีถูกระงับ: ตรวจพบสิทธิ์ OP จากแหล่งที่ไม่ได้รับอนุญาต"))
                }, null)
                
                maybeBroadcast("escalation-player", "sender", senderName, "target", targetName)
                logWarning("banned-escalation", "[AntiOP] Banned sender '%sender%' and target '%target%' for unauthorized privilege escalation.", "sender" to senderName, "target" to targetName)
                return
            }
            if (!verifiedPlayers.contains(target.name)) {
                event.isCancelled = true
                target.scheduler.run(plugin, { _ -> suspendPlayer(target) }, null)
            }
        } else {
            if (!checkWhitelist(targetName)) {
                event.isCancelled = true
                sender.scheduler.run(plugin, { _ ->
                    sender.isOp = false
                    executeConsole("lp user $senderName permission unset *")
                    val reason = config?.getString("antiop.ban-reason-escalation-sender") ?: "&c&l[AntiOP] ระงับบัญชีถาวร: พยายามมอบสิทธิ์ระดับสูงแก่บัญชีที่ไม่ได้รับอนุญาต"
                    executeConsole("ban $senderName $reason")
                }, null)
                
                executeConsole("lp user $targetName permission unset *")
                val reason = config?.getString("antiop.ban-reason-escalation-target") ?: "&c&l[AntiOP] ระงับบัญชีถาวร: ได้รับการยกระดับสิทธิ์จากแหล่งที่ไม่ได้รับการอนุญาต"
                executeConsole("ban $targetName $reason")
                
                maybeBroadcast("escalation-player", "sender", senderName, "target", targetName)
                logWarning("banned-escalation-offline", "[AntiOP] Banned sender '%sender%' and offline target '%target%' for privilege escalation.", "sender" to senderName, "target" to targetName)
                return
            }
            if (match.requiresVerifiedTarget) {
                event.isCancelled = true
            }
        }
    }

    private fun extractPrivilegeCommand(cmd: String): PrivilegeCommandMatch? {
        if (cmd.isEmpty()) return null
        val lower = cmd.lowercase(Locale.ROOT)
        val parts = cmd.split("\\s+".toRegex())
        
        if (lower.startsWith("op ") && parts.size >= 2) {
            return PrivilegeCommandMatch(parts[1], "op", null, false)
        }
        
        if ((lower.startsWith("lp ") || lower.startsWith("luckperms ")) && parts.size >= 6) {
            if (parts[1].equals("user", ignoreCase = true) && 
                parts[3].equals("permission", ignoreCase = true) && 
                parts[4].equals("set", ignoreCase = true) && 
                isWildcardNode(parts[5])) {
                return PrivilegeCommandMatch(parts[2], "wildcard-permission", parts[5], false)
            }
            if (parts[1].equals("user", ignoreCase = true) && 
                parts[3].equals("parent", ignoreCase = true) && 
                isParentMutation(parts[4]) && 
                isBlockedParentGroup(parts[5])) {
                return PrivilegeCommandMatch(parts[2], "blocked-parent-group", parts[5], true)
            }
        }
        return null
    }

    private fun isSafeCommandTarget(targetName: String): Boolean {
        return SAFE_COMMAND_TARGET.matcher(targetName).matches()
    }

    private fun isWildcardNode(node: String): Boolean {
        return node == "*" || node.equals("luckperms.*", ignoreCase = true) || 
               node.equals("minecraft.*", ignoreCase = true) || node.equals("bukkit.*", ignoreCase = true)
    }

    private fun isParentMutation(action: String): Boolean {
        return action.equals("add", ignoreCase = true) || action.equals("set", ignoreCase = true) || 
               action.equals("addtemp", ignoreCase = true) || action.equals("settemp", ignoreCase = true)
    }

    private fun isBlockedParentGroup(group: String): Boolean {
        if (antiopBlockParentGroups.isEmpty()) return false
        return antiopBlockParentGroups.contains(group.lowercase(Locale.ROOT))
    }

    fun shouldBlock(playerName: String): Boolean {
        return hasPendingVerifications && pendingVerification.contains(playerName)
    }

    // ============================================================================
    // IP LOCKOUT & BRUTE FORCE PROTECTION
    // ============================================================================

    private fun getPlayerIp(player: Player): String {
        return try {
            val addr: InetSocketAddress? = player.address
            addr?.address?.hostAddress ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun isIpLocked(ip: String): Boolean {
        val rec = ipAttemptLog[ip] ?: return false
        val elapsed = System.currentTimeMillis() - rec.lastAttemptMs
        return rec.attempts >= IP_MAX_ATTEMPTS && elapsed < IP_LOCKOUT_MS
    }

    private fun recordFailedAttempt(ip: String) {
        ipAttemptLog.compute(ip) { _, existing ->
            if (existing == null) {
                IpAttemptRecord(1, System.currentTimeMillis())
            } else {
                if (System.currentTimeMillis() - existing.lastAttemptMs > IP_DECAY_MS) {
                    IpAttemptRecord(1, System.currentTimeMillis())
                } else {
                    IpAttemptRecord(existing.attempts + 1, System.currentTimeMillis())
                }
            }
        }
    }

    private fun lockIp(ip: String) {
        ipAttemptLog[ip] = IpAttemptRecord(IP_MAX_ATTEMPTS, System.currentTimeMillis())
    }

    private fun clearIpAttempts(ip: String) {
        ipAttemptLog.remove(ip)
    }

    // ============================================================================
    // BLOCKING EVENTS (WHILE PENDING VERIFICATION)
    // ============================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val name = event.player.name
        if (sessionGrantedOp.remove(name)) {
            event.player.isOp = false
            executeConsole("lp user $name permission unset *")
            executeConsole("lp user $name permission unset luckperms.*")
            logInfo("session-op-removed", "[AntiOP] Session OP removed for offline player %player%", "player" to name)
        }
        cleanupVerification(name)
        verifiedPlayers.remove(name)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val name = event.player.name
        if (!shouldBlock(name)) return

        val from = event.from
        val to = event.to
        val posChanged = from.x != to.x || from.y != to.y || from.z != to.z
        
        if (!posChanged && !antiopFreezeLook) return

        val lookChanged = from.yaw != to.yaw || from.pitch != to.pitch
        if (posChanged || (antiopFreezeLook && lookChanged)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (shouldBlock(event.player.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (shouldBlock(player.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (shouldBlock(event.player.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (shouldBlock(event.player.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player
        val damager = event.damager as? Player

        if (victim != null && shouldBlock(victim.name)) {
            event.isCancelled = true
        } else if (damager != null && shouldBlock(damager.name)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (shouldBlock(player.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (shouldBlock(event.player.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerChat(event: AsyncChatEvent) {
        val player = event.player
        if (shouldBlock(player.name)) {
            event.isCancelled = true
        }
    }

    // ============================================================================
    // AUXILIARY UTILS
    // ============================================================================

    private fun parseGameMode(raw: String?, fallback: GameMode, key: String): GameMode {
        if (raw.isNullOrBlank()) return fallback
        return try {
            GameMode.valueOf(raw.trim().uppercase(Locale.ROOT))
        } catch (e: IllegalArgumentException) {
            logWarning("invalid-gamemode", "[AntiOP] Invalid GameMode '%raw%' for key '%key%' - using %fallback%", "raw" to raw, "key" to key, "fallback" to fallback.name)
            fallback
        }
    }

    private fun banPlayer(playerName: String, reason: String) {
        val banReason = reason.replace('&', '§')
        try {
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            val banList = plugin.server.getBanList(org.bukkit.BanList.Type.NAME) as org.bukkit.BanList<org.bukkit.BanEntry<Any>>
            banList.addBan(playerName, banReason, null, "AntiOP")
        } catch (e: Exception) {
            logSevere("ban-failed", "[AntiOP] Failed to ban player %player%: %error%", "player" to playerName, "error" to (e.message ?: ""))
        }
    }

    private fun prefixed(prefix: String, msg: String): Component {
        return parseToComponent(prefix + msg)
    }

    private fun maybeBroadcast(key: String, vararg placeholders: String) {
        val section = "antiop.broadcast.$key"
        val enabled = config?.getBoolean("$section.enabled", true) ?: true
        if (!enabled) return

        var template = config?.getString("$section.message") ?: ""
        if (template.isEmpty()) return

        var i = 0
        while (i + 1 < placeholders.size) {
            val k = placeholders[i]
            val v = placeholders[i + 1]
            template = template.replace("{$k}", v).replace("%$k%", v)
            i += 2
        }

        val component = prefixed(antiopPrefix, template)
        plugin.server.broadcast(component)
    }

    private fun logInfo(path: String, def: String, vararg replacements: Pair<String, String>) {
        var msg = config?.getString("antiop.console.$path", def) ?: def
        replacements.forEach { (key, value) ->
            msg = msg.replace("{$key}", value).replace("%$key%", value)
        }
        plugin.logger.info(msg.replace('&', '§'))
    }

    private fun logWarning(path: String, def: String, vararg replacements: Pair<String, String>) {
        var msg = config?.getString("antiop.console.$path", def) ?: def
        replacements.forEach { (key, value) ->
            msg = msg.replace("{$key}", value).replace("%$key%", value)
        }
        plugin.logger.warning(msg.replace('&', '§'))
    }

    private fun logSevere(path: String, def: String, vararg replacements: Pair<String, String>) {
        var msg = config?.getString("antiop.console.$path", def) ?: def
        replacements.forEach { (key, value) ->
            msg = msg.replace("{$key}", value).replace("%$key%", value)
        }
        plugin.logger.severe(msg.replace('&', '§'))
    }
}
