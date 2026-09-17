package core.luminaworld.modules.system.EconomyBridge

import core.luminaworld.LuminaCore
import org.bukkit.OfflinePlayer
import java.util.concurrent.ConcurrentHashMap

class EconomyBridgeService(private val plugin: LuminaCore) {
    companion object {
        lateinit var instance: EconomyBridgeService
            private set
    }

    private val providers = ConcurrentHashMap<String, EconomyProvider>()

    init {
        instance = this
        // ลงทะเบียนผู้ให้บริการมาตรฐาน
        registerProvider(VaultProvider())
        registerProvider(ExpProvider())
    }

    fun registerProvider(provider: EconomyProvider) {
        providers[provider.id.uppercase()] = provider
    }

    /**
     * วิเคราะห์ Config String เพื่อดึงตัวจัดการเงินที่ถูกต้อง
     * ตัวอย่าง: "VAULT", "EXP", "LUMINA:gold", "LUMINA:gems"
     */
    fun parseEconomy(ecoString: String?): EconomyProvider {
        if (ecoString.isNullOrBlank()) return getProvider("VAULT")
        
        val trimmed = ecoString.trim()
        val upper = trimmed.uppercase()
        if (upper == "VAULT") return getProvider("VAULT")
        if (upper == "EXP" || upper == "EXPERIENCE") return getProvider("EXP")
        
        if (upper.startsWith("LUMINA:")) {
            val currencyId = trimmed.substring("LUMINA:".length).trim()
            val providerKey = "LUMINA_${currencyId.uppercase()}"
            return providers.getOrPut(providerKey) {
                LuminaCurrencyProvider(currencyId)
            }
        }
        
        // ค่าดีฟอลต์เมื่อวิเคราะห์ไม่พบ
        return getProvider("VAULT")
    }

    fun getProvider(id: String): EconomyProvider {
        return providers[id.uppercase()] ?: providers.getOrPut("VAULT") { VaultProvider() }
    }

    fun getBalance(player: OfflinePlayer, ecoType: String): Double {
        return parseEconomy(ecoType).getBalance(player)
    }

    fun withdraw(player: OfflinePlayer, ecoType: String, amount: Double): Boolean {
        return parseEconomy(ecoType).withdraw(player, amount)
    }

    fun deposit(player: OfflinePlayer, ecoType: String, amount: Double): Boolean {
        return parseEconomy(ecoType).deposit(player, amount)
    }

    fun format(amount: Double, ecoType: String): String {
        return parseEconomy(ecoType).format(amount)
    }
}
