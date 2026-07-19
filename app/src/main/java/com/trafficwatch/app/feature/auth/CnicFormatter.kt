package com.trafficwatch.app.feature.auth

/**
 * Formats raw CNIC digits as 12345-1234567-1 (5-7-1 grouping), inserting a dash
 * after the 5th and 12th digit. Works on partial input for live formatting as
 * the user types.
 */
fun formatCnicWithDashes(rawDigits: String): String {
    val builder = StringBuilder()
    for (i in rawDigits.indices) {
        if (i == 5 || i == 12) builder.append('-')
        builder.append(rawDigits[i])
    }
    return builder.toString()
}
