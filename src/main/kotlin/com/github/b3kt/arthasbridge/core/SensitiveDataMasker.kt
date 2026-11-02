package com.github.b3kt.arthasbridge.core

/**
 * Masks card-like numbers (13-19 digits, optionally separated by spaces/dashes, passing Luhn)
 * so that watch / tt output doesn't put PANs on screen. Keeps the first 6 and last 4 digits.
 */
object SensitiveDataMasker {

    private val CANDIDATE = Regex("""(?<![\d])\d(?:[ -]?\d){12,18}(?![\d])""")

    fun mask(text: String): String = CANDIDATE.replace(text) { match ->
        val digits = match.value.filter(Char::isDigit)
        if (digits.length in 13..19 && luhn(digits)) {
            digits.take(6) + "*".repeat(digits.length - 10) + digits.takeLast(4)
        } else {
            match.value
        }
    }

    private fun luhn(digits: String): Boolean {
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }
}
