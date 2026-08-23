@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.math.BigDecimal
import java.math.BigInteger

internal fun Any?.exact_aviation_object_id_or_null(): Long? = when (this) {
    is Byte -> toLong()
    is Short -> toLong()
    is Int -> toLong()
    is Long -> this
    is BigInteger -> takeIf { it >= LONG_MIN_BIG_INTEGER && it <= LONG_MAX_BIG_INTEGER }?.toLong()
    is BigDecimal -> exact_long_or_null()
    is Float -> toDouble().safe_floating_long_or_null()
    is Double -> safe_floating_long_or_null()
    else -> null
}

private fun BigDecimal.exact_long_or_null(): Long? = try {
    longValueExact()
} catch (_: ArithmeticException) {
    null
}

private fun Double.safe_floating_long_or_null(): Long? {
    if (!isFinite() || this % 1.0 != 0.0 || this !in -MAX_SAFE_FLOATING_ID..MAX_SAFE_FLOATING_ID) {
        return null
    }
    return toLong()
}

private const val MAX_SAFE_FLOATING_ID = 9_007_199_254_740_991.0
private val LONG_MIN_BIG_INTEGER: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
private val LONG_MAX_BIG_INTEGER: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
