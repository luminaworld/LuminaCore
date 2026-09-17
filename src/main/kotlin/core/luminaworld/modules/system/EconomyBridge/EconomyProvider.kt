package core.luminaworld.modules.system.EconomyBridge

import org.bukkit.OfflinePlayer

interface EconomyProvider {
    val id: String
    val displayName: String
    fun getBalance(player: OfflinePlayer): Double
    fun deposit(player: OfflinePlayer, amount: Double): Boolean
    fun withdraw(player: OfflinePlayer, amount: Double): Boolean
    fun format(amount: Double): String
}
