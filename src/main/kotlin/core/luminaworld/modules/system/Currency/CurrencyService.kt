package core.luminaworld.modules.system.Currency

import core.luminaworld.LuminaCore
import org.bukkit.OfflinePlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Public, Vault-independent API exposed by [LuminaCore.currencyService]. */
class CurrencyService(private val plugin: LuminaCore, private val repository: CurrencyRepository) {
    private val definitions = ConcurrentHashMap<String, CurrencyDefinition>()
    private val balances = ConcurrentHashMap<String, Long>()
    private val leaderboards = ConcurrentHashMap<String, List<CurrencyLeaderboardEntry>>()

    fun replaceDefinitions(values: Collection<CurrencyDefinition>) {
        definitions.clear()
        values.forEach { definitions[it.id.lowercase()] = it }
        leaderboards.keys.retainAll(definitions.keys)
    }

    fun getCurrency(id: String): CurrencyDefinition? = definitions[id.lowercase()]
    fun currencies(): List<CurrencyDefinition> = definitions.values.sortedBy { it.id }
    fun cachedBalance(player: UUID, currency: String): Long = balances[cacheKey(player, currency)] ?: 0L
    fun leaderboard(currency: String): List<CurrencyLeaderboardEntry> = leaderboards[currency.lowercase()] ?: emptyList()

    fun loadBalance(player: CurrencyPlayer, currency: String, callback: (Long) -> Unit = {}) {
        if (getCurrency(currency) == null) { callback(0); return }
        repository.getBalance(player.uuid, currency.lowercase()) { amount ->
            balances[cacheKey(player.uuid, currency)] = amount
            callback(amount)
        }
    }

    fun preload(player: OfflinePlayer) {
        val name = player.name ?: player.uniqueId.toString()
        currencies().forEach { loadBalance(CurrencyPlayer(player.uniqueId, name), it.id) }
    }

    fun give(player: CurrencyPlayer, currencyId: String, input: String, actor: UUID? = null, reason: String? = null, callback: (CurrencyOperationResult) -> Unit) {
        change(player, currencyId, input, "GIVE", actor, reason, callback)
    }
    fun take(player: CurrencyPlayer, currencyId: String, input: String, actor: UUID? = null, reason: String? = null, callback: (CurrencyOperationResult) -> Unit) {
        change(player, currencyId, input, "TAKE", actor, reason, callback)
    }
    fun set(player: CurrencyPlayer, currencyId: String, input: String, actor: UUID? = null, reason: String? = null, callback: (CurrencyOperationResult) -> Unit) {
        change(player, currencyId, input, "SET", actor, reason, callback)
    }

    private fun change(player: CurrencyPlayer, currencyId: String, input: String, operation: String, actor: UUID?, reason: String?, callback: (CurrencyOperationResult) -> Unit) {
        val currency = getCurrency(currencyId) ?: run { callback(CurrencyOperationResult(CurrencyResult.UNKNOWN_CURRENCY)); return }
        val amount = (if (operation == "SET") currency.parseBalance(input) else currency.parseAmount(input))
            ?: run { callback(CurrencyOperationResult(CurrencyResult.INVALID_AMOUNT)); return }
        repository.change(player, currency.id, amount, operation, actor, reason, currency.minimumBalance, currency.maximumBalance) { result ->
            if (result.status == CurrencyResult.SUCCESS) {
                balances[cacheKey(player.uuid, currency.id)] = result.balance
                refreshLeaderboard(currency.id)
            }
            callback(result)
        }
    }

    fun transfer(from: CurrencyPlayer, to: CurrencyPlayer, currencyId: String, input: String, callback: (CurrencyOperationResult) -> Unit) {
        val currency = getCurrency(currencyId) ?: run { callback(CurrencyOperationResult(CurrencyResult.UNKNOWN_CURRENCY)); return }
        val amount = currency.parseAmount(input) ?: run { callback(CurrencyOperationResult(CurrencyResult.INVALID_AMOUNT)); return }
        when {
            !currency.transferable -> { callback(CurrencyOperationResult(CurrencyResult.TRANSFER_DISABLED)); return }
            from.uuid == to.uuid && !currency.allowSelfTransfer -> { callback(CurrencyOperationResult(CurrencyResult.SELF_TRANSFER)); return }
            amount < currency.minimumTransfer || (currency.maximumTransfer > 0 && amount > currency.maximumTransfer) -> { callback(CurrencyOperationResult(CurrencyResult.LIMIT_EXCEEDED)); return }
        }
        repository.transfer(from, to, currency.id, amount, currency.minimumBalance, currency.maximumBalance) { result, targetBalance ->
            if (result.status == CurrencyResult.SUCCESS) {
                balances[cacheKey(from.uuid, currency.id)] = result.balance
                balances[cacheKey(to.uuid, currency.id)] = targetBalance
                refreshLeaderboard(currency.id)
            }
            callback(result)
        }
    }

    fun refreshLeaderboards(limit: Int) = currencies().forEach { refreshLeaderboard(it.id, limit) }
    fun refreshLeaderboard(currency: String, limit: Int = 10) {
        repository.top(currency.lowercase(), limit) { leaderboards[currency.lowercase()] = it }
    }
    fun clearCache() { balances.clear(); leaderboards.clear() }
    private fun cacheKey(player: UUID, currency: String) = "${currency.lowercase()}:$player"
}
