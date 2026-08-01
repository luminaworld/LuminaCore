package core.luminaworld.modules.system.Currency

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import core.luminaworld.placeholder.LuminaPlaceholderManager
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.entity.Player
import java.io.File
import java.nio.file.Files

class CurrencyModule(plugin: LuminaCore) : LuminaModule(plugin, "Currency") {
    private val repository = CurrencyRepository(plugin)
    val service = CurrencyService(plugin, repository)
    private val commandRegistry = CurrencyCommandRegistry(plugin)
    private var payCommand: CurrencyPayCommand? = null
    private var balanceCommand: CurrencyBalanceCommand? = null
    private var adminCommand: CurrencyAdminCommand? = null
    private var leaderboardCommand: CurrencyLeaderboardCommand? = null
    private var leaderboardTask: ScheduledTask? = null

    /** Preserve configurations created while Currency was briefly located under features/. */
    override fun loadConfig() {
        val legacyConfig = File(plugin.dataFolder, "features/Currency.yml")
        if (!configFile.exists() && legacyConfig.isFile) {
            configFile.parentFile.mkdirs()
            Files.copy(legacyConfig.toPath(), configFile.toPath())
            plugin.logger.info("[Currency] Copied legacy features/Currency.yml to system/Currency.yml.")
        }
        super.loadConfig()
    }

    override fun onEnable() {
        val currencies = loadDefinitions()
        service.replaceDefinitions(currencies)
        repository.createTables()
        plugin.currencyService = service

        // สร้าง command instances
        val payCmd = CurrencyPayCommand(this)
        val balCmd = CurrencyBalanceCommand(this)
        val admCmd = CurrencyAdminCommand(this)
        val leadCmd = CurrencyLeaderboardCommand(this)
        
        payCommand = payCmd
        balanceCommand = balCmd
        adminCommand = admCmd
        leaderboardCommand = leadCmd

        // โหลดและลงทะเบียนคำสั่งไดนามิก
        val payCmdName = payCommandName()
        val payCmdAliases = config?.getStringList("settings.pay-command-aliases")?.map { it.trim().lowercase() }?.filter { it.isNotBlank() } ?: emptyList()

        val balCmdName = balanceCommandName()
        val balCmdAliases = config?.getStringList("settings.balance-command-aliases")?.map { it.trim().lowercase() }?.filter { it.isNotBlank() } ?: emptyList()

        val admCmdName = adminCommandName()
        val admCmdAliases = config?.getStringList("settings.admin-command-aliases")?.map { it.trim().lowercase() }?.filter { it.isNotBlank() } ?: emptyList()

        val leadCmdName = leaderboardCommandName()
        val leadCmdAliases = config?.getStringList("settings.leaderboard-command-aliases")?.map { it.trim().lowercase() }?.filter { it.isNotBlank() } ?: emptyList()

        commandRegistry.register(payCmdName, payCmdAliases, payCmd, payCmd)
        commandRegistry.register(balCmdName, balCmdAliases, balCmd, balCmd)
        commandRegistry.register(admCmdName, admCmdAliases, admCmd, admCmd)
        commandRegistry.register(leadCmdName, leadCmdAliases, leadCmd, leadCmd)

        registerCurrencyCommands(currencies, payCmd)
        commandRegistry.sync()
        LuminaPlaceholderManager.register("currency") { player, params -> placeholder(player, params) }

        Bukkit.getOnlinePlayers().forEach(service::preload)
        service.refreshLeaderboards(leaderboardSize())
        val interval = config?.getLong("settings.leaderboard-refresh-seconds", 60L)?.coerceAtLeast(10L) ?: 60L
        leaderboardTask = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { service.refreshLeaderboards(leaderboardSize()) }, 20L, interval * 20L)
        plugin.logger.info("§6[Currency] §aLoaded ${currencies.size} currency definition(s).")
    }

    override fun onDisable() {
        leaderboardTask?.cancel(); leaderboardTask = null
        commandRegistry.unregisterAll()
        commandRegistry.sync()
        LuminaPlaceholderManager.unregister("currency")
        service.clearCache()
        if (plugin.currencyService === service) plugin.currencyService = null
    }

    @EventHandler
    fun loadPlayerCache(event: PlayerJoinEvent) = service.preload(event.player)

    private fun loadDefinitions(): List<CurrencyDefinition> {
        val currencies = mutableListOf<CurrencyDefinition>()
        val cfg = config ?: return currencies
        val section = cfg.getConfigurationSection("currencies") ?: return currencies
        for (rawId in section.getKeys(false)) {
            val id = rawId.lowercase()
            val path = "currencies.$rawId"
            if (!id.matches(Regex("[a-z0-9_-]{1,32}"))) { plugin.logger.warning("[Currency] Invalid currency id '$rawId'; skipped."); continue }
            val decimals = cfg.getInt("$path.decimals", 0).coerceIn(0, 8)
            val commandName = cfg.getString("$path.transfer-command", "")?.trim()?.lowercase().orEmpty().ifBlank { null }
            val aliases = cfg.getStringList("$path.command-aliases")?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
            
            val permTransfer = cfg.getString("$path.permission-transfer", "")?.trim().orEmpty().ifBlank { null }
            val permTransferReq = cfg.getBoolean("$path.permission-transfer-require", false)
            val permReceive = cfg.getString("$path.permission-receive", "")?.trim().orEmpty().ifBlank { null }
            val permReceiveReq = cfg.getBoolean("$path.permission-receive-require", false)
            val permBalance = cfg.getString("$path.permission-balance", "")?.trim().orEmpty().ifBlank { null }
            val permBalanceReq = cfg.getBoolean("$path.permission-balance-require", false)
            val permBalanceOther = cfg.getString("$path.permission-balance-other", "")?.trim().orEmpty().ifBlank { null }
            val permBalanceOtherReq = cfg.getBoolean("$path.permission-balance-other-require", false)

            val permLeaderboard = cfg.getString("$path.permission-leaderboard", "")?.trim().orEmpty().ifBlank { null }
            val permLeaderboardReq = cfg.getBoolean("$path.permission-leaderboard-require", false)

            val definition = CurrencyDefinition(
                id, cfg.getString("$path.display-name", id.uppercase()) ?: id.uppercase(),
                cfg.getString("$path.symbol", id.uppercase()) ?: id.uppercase(), decimals,
                cfg.getBoolean("$path.transferable", true), commandName, aliases,
                parseConfiguredAmount("$path.minimum-transfer", decimals, 1L),
                parseConfiguredAmount("$path.maximum-transfer", decimals, 0L),
                cfg.getBoolean("$path.allow-self-transfer", false),
                cfg.getString("$path.placing", "[money] [symbol]") ?: "[money] [symbol]",
                parseConfiguredAmount("$path.minimum-balance", decimals, 0L),
                parseConfiguredAmount("$path.maximum-balance", decimals, 0L),
                permTransfer, permTransferReq,
                permReceive, permReceiveReq,
                permBalance, permBalanceReq,
                permBalanceOther, permBalanceOtherReq,
                permLeaderboard, permLeaderboardReq
            )
            if (currencies.any { it.id == definition.id }) plugin.logger.warning("[Currency] Duplicate id '$id'; skipped.") else currencies += definition
        }
        return currencies
    }

    private fun parseConfiguredAmount(path: String, decimals: Int, default: Long): Long {
        val raw = config?.getString(path) ?: return default
        return CurrencyDefinition("_", "", "", decimals, true, null, emptyList(), 0, 0, false, "[money] [symbol]", 0L, 0L).parseAmount(raw) ?: default
    }

    private fun registerCurrencyCommands(currencies: List<CurrencyDefinition>, payCmd: CurrencyPayCommand) {
        currencies.forEach { currency ->
            val name = currency.transferCommand ?: return@forEach
            if (!name.matches(Regex("[a-z0-9_-]{1,32}"))) { plugin.logger.warning("[Currency] Invalid transfer command '$name' for ${currency.id}."); return@forEach }
            val fixedPayCmd = CurrencyPayCommand(this, currency.id)
            commandRegistry.register(name, currency.aliases, fixedPayCmd, fixedPayCmd)
        }
    }

    fun payCommandName(): String = config?.getString("settings.pay-command", "lcpay")?.trim()?.lowercase().orEmpty().ifBlank { "lcpay" }
    fun balanceCommandName(): String = config?.getString("settings.balance-command", "lcbal")?.trim()?.lowercase().orEmpty().ifBlank { "lcbal" }
    fun adminCommandName(): String = config?.getString("settings.admin-command", "currencyadmin")?.trim()?.lowercase().orEmpty().ifBlank { "currencyadmin" }
    fun leaderboardCommandName(): String = config?.getString("settings.leaderboard-command", "lcbaltop")?.trim()?.lowercase().orEmpty().ifBlank { "lcbaltop" }

    private fun leaderboardSize() = config?.getInt("settings.leaderboard-cache-size", 10)?.coerceIn(1, 100) ?: 10
    fun requireRecipientOnline() = config?.getBoolean("settings.require-recipient-online", true) ?: true

    fun message(key: String, replacements: Map<String, String> = emptyMap()): String {
        var result = config?.getString("messages.$key", "&cMissing message: $key") ?: "&cMissing message: $key"
        val allReplacements = replacements.toMutableMap()
        allReplacements["pay_cmd"] = payCommandName()
        allReplacements["balance_cmd"] = balanceCommandName()
        allReplacements["admin_cmd"] = adminCommandName()
        allReplacements["leaderboard_cmd"] = leaderboardCommandName()
        allReplacements.forEach { (find, value) -> result = result.replace("%$find%", value) }
        return result
    }

    fun adminPermissionName(): String = config?.getString("settings.admin-permission", "luminacore.currency.admin")?.trim().orEmpty().ifBlank { "luminacore.currency.admin" }

    private fun placeholder(player: org.bukkit.OfflinePlayer?, params: String): String {
        if (params == "count") return service.currencies().size.toString()
        val parts = params.split("_")
        val currency = service.getCurrency(parts.firstOrNull() ?: return "") ?: return ""
        val action = parts.drop(1).joinToString("_")
        val amount = player?.let { service.cachedBalance(it.uniqueId, currency.id) } ?: 0L
        return when {
            action == "balance" -> currency.format(amount, false)
            action == "formatted" -> currency.format(amount)
            action == "short" -> currency.formatShort(amount)
            action == "name" -> currency.displayName
            action == "symbol" -> currency.symbol
            action == "transferable" -> currency.transferable.toString()
            action.startsWith("top_name_") -> service.leaderboard(currency.id).getOrNull(action.removePrefix("top_name_").toIntOrNull()?.minus(1) ?: -1)?.playerName ?: ""
            action.startsWith("top_balance_") -> service.leaderboard(currency.id).getOrNull(action.removePrefix("top_balance_").toIntOrNull()?.minus(1) ?: -1)?.let { currency.format(it.amount, false) } ?: "0"
            action.startsWith("top_formatted_") -> service.leaderboard(currency.id).getOrNull(action.removePrefix("top_formatted_").toIntOrNull()?.minus(1) ?: -1)?.let { currency.format(it.amount) } ?: ""
            action.startsWith("top_short_") -> service.leaderboard(currency.id).getOrNull(action.removePrefix("top_short_").toIntOrNull()?.minus(1) ?: -1)?.let { currency.formatShort(it.amount) } ?: ""
            else -> ""
        }
    }

    fun getNotificationType(path: String, default: NotificationType): NotificationType {
        val raw = config?.getString(path)?.uppercase() ?: return default
        return try {
            NotificationType.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            default
        }
    }

    fun sendNotification(player: Player, message: String, type: NotificationType) {
        if (type == NotificationType.NONE || message.isBlank()) return
        val component = core.luminaworld.utils.ColorParser.parse(message)
        plugin.server.globalRegionScheduler.execute(plugin) {
            when (type) {
                NotificationType.CHAT -> player.sendMessage(component)
                NotificationType.ACTION_BAR -> player.sendActionBar(component)
                NotificationType.NONE -> {}
            }
        }
    }
}

enum class NotificationType {
    CHAT,
    ACTION_BAR,
    NONE
}

