package core.luminaworld.modules.system.EconomyBridge

import core.luminaworld.LuminaCore
import core.luminaworld.modules.system.Currency.CurrencyPlayer
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.text.DecimalFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 1. Vault Economy Provider
 */
class VaultProvider : EconomyProvider {
    override val id: String = "VAULT"
    override val displayName: String = "Vault"

    private val economy: Economy? by lazy {
        val rsp = Bukkit.getServer().servicesManager.getRegistration(Economy::class.java)
        rsp?.provider
    }

    override fun getBalance(player: OfflinePlayer): Double {
        return economy?.getBalance(player) ?: 0.0
    }

    override fun deposit(player: OfflinePlayer, amount: Double): Boolean {
        val eco = economy ?: return false
        val resp = eco.depositPlayer(player, amount)
        return resp.transactionSuccess()
    }

    override fun withdraw(player: OfflinePlayer, amount: Double): Boolean {
        val eco = economy ?: return false
        val resp = eco.withdrawPlayer(player, amount)
        return resp.transactionSuccess()
    }

    override fun format(amount: Double): String {
        val eco = economy
        return if (eco != null) {
            eco.format(amount)
        } else {
            DecimalFormat("#,##0.00").format(amount)
        }
    }
}

/**
 * 2. Vanilla Experience Provider
 */
class ExpProvider : EconomyProvider {
    override val id: String = "EXP"
    override val displayName: String = "Experience"

    override fun getBalance(player: OfflinePlayer): Double {
        val onlinePlayer = player.player ?: return 0.0
        return onlinePlayer.totalExperience.toDouble()
    }

    override fun deposit(player: OfflinePlayer, amount: Double): Boolean {
        val onlinePlayer = player.player ?: return false
        setTotalExperience(onlinePlayer, onlinePlayer.totalExperience + amount.toInt())
        return true
    }

    override fun withdraw(player: OfflinePlayer, amount: Double): Boolean {
        val onlinePlayer = player.player ?: return false
        val currentExp = onlinePlayer.totalExperience
        val toWithdraw = amount.toInt()
        if (currentExp < toWithdraw) return false
        setTotalExperience(onlinePlayer, currentExp - toWithdraw)
        return true
    }

    override fun format(amount: Double): String {
        return DecimalFormat("#,##0").format(amount) + " XP"
    }

    private fun setTotalExperience(player: Player, exp: Int) {
        player.totalExperience = exp
        player.level = 0
        player.exp = 0.0f
        var experience = exp
        while (experience >= player.expToLevel) {
            experience -= player.expToLevel
            player.level++
        }
        player.exp = experience.toFloat() / player.expToLevel.toFloat()
    }
}

/**
 * 3. Lumina Currency Provider
 */
class LuminaCurrencyProvider(val currencyId: String) : EconomyProvider {
    override val id: String = "LUMINA_${currencyId.uppercase()}"
    override val displayName: String by lazy {
        LuminaCore.instance.currencyService?.getCurrency(currencyId)?.displayName ?: currencyId
    }

    override fun getBalance(player: OfflinePlayer): Double {
        val service = LuminaCore.instance.currencyService ?: return 0.0
        return service.cachedBalance(player.uniqueId, currencyId).toDouble()
    }

    override fun deposit(player: OfflinePlayer, amount: Double): Boolean {
        val service = LuminaCore.instance.currencyService ?: return false
        val cPlayer = CurrencyPlayer(player.uniqueId, player.name ?: player.uniqueId.toString())
        val future = CompletableFuture<Boolean>()
        
        service.give(cPlayer, currencyId, amount.toLong().toString()) { result ->
            future.complete(result.status == core.luminaworld.modules.system.Currency.CurrencyResult.SUCCESS)
        }
        
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
    }

    override fun withdraw(player: OfflinePlayer, amount: Double): Boolean {
        val service = LuminaCore.instance.currencyService ?: return false
        val cPlayer = CurrencyPlayer(player.uniqueId, player.name ?: player.uniqueId.toString())
        val future = CompletableFuture<Boolean>()
        
        service.take(cPlayer, currencyId, amount.toLong().toString()) { result ->
            future.complete(result.status == core.luminaworld.modules.system.Currency.CurrencyResult.SUCCESS)
        }
        
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
    }

    override fun format(amount: Double): String {
        val service = LuminaCore.instance.currencyService
        val def = service?.getCurrency(currencyId)
        return def?.format(amount.toLong()) ?: (DecimalFormat("#,##0").format(amount) + " $currencyId")
    }
}
