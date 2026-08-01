package core.luminaworld.modules.system.Currency

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.UUID

data class CurrencyDefinition(
    val id: String,
    val displayName: String,
    val symbol: String,
    val decimals: Int,
    val transferable: Boolean,
    val transferCommand: String?,
    val aliases: List<String>,
    val minimumTransfer: Long,
    val maximumTransfer: Long,
    val allowSelfTransfer: Boolean
) {
    val factor: Long = (1..decimals).fold(1L) { value, _ -> value * 10L }

    fun parseAmount(input: String): Long? = parse(input, allowZero = false)

    /** Used by admin SET: a balance of exactly zero is valid. */
    fun parseBalance(input: String): Long? = parse(input, allowZero = true)

    private fun parse(input: String, allowZero: Boolean): Long? = try {
        val value = BigDecimal(input.replace(",", ""))
        if (value.signum() < 0 || (!allowZero && value.signum() == 0) || value.scale() > decimals) null
        else value.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    } catch (_: Exception) { null }

    fun format(amount: Long, includeSymbol: Boolean = true): String {
        val decimal = BigDecimal.valueOf(amount, decimals)
        val pattern = if (decimals == 0) "#,##0" else "#,##0." + "0".repeat(decimals)
        val number = DecimalFormat(pattern).format(decimal)
        return if (includeSymbol && symbol.isNotBlank()) "$number $symbol" else number
    }
}

data class CurrencyLeaderboardEntry(val playerName: String, val amount: Long)

enum class CurrencyResult {
    SUCCESS, UNKNOWN_CURRENCY, INVALID_AMOUNT, INSUFFICIENT_FUNDS, TRANSFER_DISABLED,
    SELF_TRANSFER, LIMIT_EXCEEDED, DATABASE_ERROR
}

data class CurrencyOperationResult(val status: CurrencyResult, val balance: Long = 0L)

data class CurrencyPlayer(val uuid: UUID, val name: String)
