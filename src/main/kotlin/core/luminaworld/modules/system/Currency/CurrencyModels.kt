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
    val allowSelfTransfer: Boolean,
    val placing: String = "[money] [symbol]",
    val minimumBalance: Long = 0L,
    val maximumBalance: Long = 0L,
    val permissionTransfer: String? = null,
    val permissionTransferRequire: Boolean = false,
    val permissionReceive: String? = null,
    val permissionReceiveRequire: Boolean = false,
    val permissionBalance: String? = null,
    val permissionBalanceRequire: Boolean = false,
    val permissionBalanceOther: String? = null,
    val permissionBalanceOtherRequire: Boolean = false,
    val permissionLeaderboard: String? = null,
    val permissionLeaderboardRequire: Boolean = false
) {
    val factor: Long = (1..decimals).fold(1L) { value, _ -> value * 10L }

    fun parseAmount(input: String): Long? = parse(input, allowZero = false)

    /** Used by admin SET: a balance of exactly zero is valid. */
    fun parseBalance(input: String): Long? = parse(input, allowZero = true)

    private fun parse(input: String, allowZero: Boolean): Long? = try {
        val trimmed = input.trim().replace(",", "")
        var multiplier = BigDecimal.ONE
        var numericPart = trimmed
        
        if (trimmed.endsWith("k", ignoreCase = true)) {
            multiplier = BigDecimal("1000")
            numericPart = trimmed.substring(0, trimmed.length - 1)
        } else if (trimmed.endsWith("m", ignoreCase = true)) {
            multiplier = BigDecimal("1000000")
            numericPart = trimmed.substring(0, trimmed.length - 1)
        } else if (trimmed.endsWith("b", ignoreCase = true)) {
            multiplier = BigDecimal("1000000000")
            numericPart = trimmed.substring(0, trimmed.length - 1)
        } else if (trimmed.endsWith("t", ignoreCase = true)) {
            multiplier = BigDecimal("1000000000000")
            numericPart = trimmed.substring(0, trimmed.length - 1)
        }
        
        val value = BigDecimal(numericPart).multiply(multiplier)
        if (value.signum() < 0 || (!allowZero && value.signum() == 0)) null
        else value.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    } catch (_: Exception) { null }

    fun format(amount: Long, includeSymbol: Boolean = true): String {
        val decimal = BigDecimal.valueOf(amount, decimals)
        
        // คำนวณค่า [money] แบบเต็ม
        val pattern = if (decimals == 0) "#,##0" else "#,##0." + "0".repeat(decimals)
        val moneyStr = DecimalFormat(pattern).format(decimal)
        
        // คำนวณค่า [money_abbreviate]
        val moneyAbbreviateStr = if (decimal.abs() >= BigDecimal("1000")) {
            val (abbreviatedValue, suffix) = when {
                decimal.abs() >= BigDecimal("1000000000000") -> {
                    decimal.divide(BigDecimal("1000000000000"), decimals.coerceAtLeast(2), RoundingMode.HALF_UP) to "t"
                }
                decimal.abs() >= BigDecimal("1000000000") -> {
                    decimal.divide(BigDecimal("1000000000"), decimals.coerceAtLeast(2), RoundingMode.HALF_UP) to "b"
                }
                decimal.abs() >= BigDecimal("1000000") -> {
                    decimal.divide(BigDecimal("1000000"), decimals.coerceAtLeast(2), RoundingMode.HALF_UP) to "m"
                }
                else -> {
                    decimal.divide(BigDecimal("1000"), decimals.coerceAtLeast(2), RoundingMode.HALF_UP) to "k"
                }
            }
            
            val abbreviatePattern = StringBuilder("#,##0")
            val maxDecimals = decimals.coerceAtLeast(2)
            if (maxDecimals > 0) {
                abbreviatePattern.append(".")
                abbreviatePattern.append("#".repeat(maxDecimals))
            }
            val number = DecimalFormat(abbreviatePattern.toString()).format(abbreviatedValue)
            "$number$suffix"
        } else {
            moneyStr
        }
        
        // จัดการโครงสร้างและแสดงผลตาม Placing pattern
        var result = placing
        result = result.replace("[money]", moneyStr, ignoreCase = true)
        result = result.replace("[money_abbreviate]", moneyAbbreviateStr, ignoreCase = true)
        
        if (includeSymbol && symbol.isNotBlank()) {
            result = result.replace("[symbol]", symbol, ignoreCase = true)
        } else {
            result = result.replace("[symbol]", "", ignoreCase = true).trim()
            result = result.replace("\\s+".toRegex(), " ")
        }
        
        return result
    }

    fun formatShort(amount: Long): String {
        val decimal = BigDecimal.valueOf(amount, decimals)
        val moneyAbbreviateStr = if (decimal.abs() >= BigDecimal("1000")) {
            val (abbreviatedValue, suffix) = when {
                decimal.abs() >= BigDecimal("1000000000000") -> {
                    decimal.divide(BigDecimal("1000000000000"), decimals.coerceAtLeast(2), RoundingMode.HALF_UP) to "t"
                }
                decimal.abs() >= BigDecimal("1000000000") -> {
                    decimal.divide(BigDecimal("1000000000"), decimals.coerceAtLeast(2), RoundingMode.HALF_UP) to "b"
                }
                decimal.abs() >= BigDecimal("1000000") -> {
                    decimal.divide(BigDecimal("1000000"), decimals.coerceAtLeast(2), RoundingMode.HALF_UP) to "m"
                }
                else -> {
                    decimal.divide(BigDecimal("1000"), decimals.coerceAtLeast(2), RoundingMode.HALF_UP) to "k"
                }
            }
            val abbreviatePattern = StringBuilder("#,##0")
            val maxDecimals = decimals.coerceAtLeast(2)
            if (maxDecimals > 0) {
                abbreviatePattern.append(".")
                abbreviatePattern.append("#".repeat(maxDecimals))
            }
            val number = DecimalFormat(abbreviatePattern.toString()).format(abbreviatedValue)
            "$number$suffix"
        } else {
            val pattern = if (decimals == 0) "#,##0" else "#,##0." + "0".repeat(decimals)
            DecimalFormat(pattern).format(decimal)
        }
        return moneyAbbreviateStr
    }
}

data class CurrencyLeaderboardEntry(val playerName: String, val amount: Long)

enum class CurrencyResult {
    SUCCESS, UNKNOWN_CURRENCY, INVALID_AMOUNT, INSUFFICIENT_FUNDS, TRANSFER_DISABLED,
    SELF_TRANSFER, LIMIT_EXCEEDED, DATABASE_ERROR
}

data class CurrencyOperationResult(val status: CurrencyResult, val balance: Long = 0L)

data class CurrencyPlayer(val uuid: UUID, val name: String)
