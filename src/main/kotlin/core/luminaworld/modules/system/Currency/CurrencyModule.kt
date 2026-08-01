package core.luminaworld.modules.system.Currency

import core.luminaworld.LuminaCore
import core.luminaworld.module.LuminaModule
import core.luminaworld.placeholder.LuminaPlaceholderManager
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File
import java.nio.file.Files

class CurrencyModule(plugin: LuminaCore) : LuminaModule(plugin, "Currency") {
    private val repository = CurrencyRepository(plugin)
    val service = CurrencyService(plugin, repository)
    private val command = CurrencyCommand(this)
    private val commandRegistry = CurrencyCommandRegistry(plugin)
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

        // /lcpay is declared in plugin.yml; the module owns its executor and delegates to CurrencyService.
        plugin.getCommand("lcpay")?.apply { setExecutor(command); tabCompleter = command }
        registerCurrencyCommands(currencies)
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
        plugin.getCommand("lcpay")?.apply { setExecutor(null); tabCompleter = null }
        LuminaPlaceholderManager.unregister("currency")
        service.clearCache()
        if (plugin.currencyService === service) plugin.currencyService = null
    }

    @EventHandler
    fun loadPlayerCache(event: PlayerJoinEvent) = service.preload(event.player)

    private fun loadDefinitions(): List<CurrencyDefinition> {
        val currencies = mutableListOf<CurrencyDefinition>()
        val section = config?.getConfigurationSection("currencies") ?: return currencies
        for (rawId in section.getKeys(false)) {
            val id = rawId.lowercase()
            val path = "currencies.$rawId"
            if (!id.matches(Regex("[a-z0-9_-]{1,32}"))) { plugin.logger.warning("[Currency] Invalid currency id '$rawId'; skipped."); continue }
            val decimals = config?.getInt("$path.decimals", 0)?.coerceIn(0, 8) ?: 0
            val commandName = config?.getString("$path.transfer-command", "")?.trim()?.lowercase().orEmpty().ifBlank { null }
            val aliases = config?.getStringList("$path.command-aliases")?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
            val definition = CurrencyDefinition(
                id, config?.getString("$path.display-name", id.uppercase()) ?: id.uppercase(),
                config?.getString("$path.symbol", id.uppercase()) ?: id.uppercase(), decimals,
                config?.getBoolean("$path.transferable", true) ?: true, commandName, aliases,
                parseConfiguredAmount("$path.minimum-transfer", decimals, 1L),
                parseConfiguredAmount("$path.maximum-transfer", decimals, 0L),
                config?.getBoolean("$path.allow-self-transfer", false) ?: false
            )
            if (currencies.any { it.id == definition.id }) plugin.logger.warning("[Currency] Duplicate id '$id'; skipped.") else currencies += definition
        }
        return currencies
    }

    private fun parseConfiguredAmount(path: String, decimals: Int, default: Long): Long {
        val raw = config?.getString(path) ?: return default
        return CurrencyDefinition("_", "", "", decimals, true, null, emptyList(), 0, 0, false).parseAmount(raw) ?: default
    }

    private fun registerCurrencyCommands(currencies: List<CurrencyDefinition>) {
        currencies.forEach { currency ->
            val name = currency.transferCommand ?: return@forEach
            if (!name.matches(Regex("[a-z0-9_-]{1,32}"))) { plugin.logger.warning("[Currency] Invalid transfer command '$name' for ${currency.id}."); return@forEach }
            commandRegistry.register(name, currency.aliases, command.forCurrency(currency.id), command.forCurrency(currency.id))
        }
    }

    private fun leaderboardSize() = config?.getInt("settings.leaderboard-cache-size", 10)?.coerceIn(1, 100) ?: 10
    fun requireRecipientOnline() = config?.getBoolean("settings.require-recipient-online", true) ?: true

    fun message(key: String, replacements: Map<String, String> = emptyMap()): String {
        var result = config?.getString("messages.$key", "&cMissing message: $key") ?: "&cMissing message: $key"
        replacements.forEach { (find, value) -> result = result.replace("%$find%", value) }
        return result
    }

    private fun placeholder(player: org.bukkit.OfflinePlayer?, params: String): String {
        if (params == "count") return service.currencies().size.toString()
        val parts = params.split("_")
        val currency = service.getCurrency(parts.firstOrNull() ?: return "") ?: return ""
        val action = parts.drop(1).joinToString("_")
        val amount = player?.let { service.cachedBalance(it.uniqueId, currency.id) } ?: 0L
        return when {
            action == "balance" -> currency.format(amount, false)
            action == "formatted" -> currency.format(amount)
            action == "name" -> currency.displayName
            action == "symbol" -> currency.symbol
            action == "transferable" -> currency.transferable.toString()
            action.startsWith("top_name_") -> service.leaderboard(currency.id).getOrNull(action.removePrefix("top_name_").toIntOrNull()?.minus(1) ?: -1)?.playerName ?: ""
            action.startsWith("top_balance_") -> service.leaderboard(currency.id).getOrNull(action.removePrefix("top_balance_").toIntOrNull()?.minus(1) ?: -1)?.let { currency.format(it.amount, false) } ?: "0"
            action.startsWith("top_formatted_") -> service.leaderboard(currency.id).getOrNull(action.removePrefix("top_formatted_").toIntOrNull()?.minus(1) ?: -1)?.let { currency.format(it.amount) } ?: ""
            else -> ""
        }
    }
}
