package com.example.url_blocker.media.util

import java.util.Locale

/**
 * Compact view-count formatting for the Media tab cards, YouTube-style:
 * 999 → "999", 1234 → "1.2K", 12345 → "12K", 1500000 → "1.5M", 12_345_678 → "12M".
 *
 * Pure JVM function (no Android/Compose dependencies) so it is unit-testable.
 */
internal fun formatViews(count: Long): String {
    if (count < 0L) return "0"
    return when {
        count >= 1_000_000_000L -> compact(count, 1_000_000_000L) + "B"
        count >= 1_000_000L -> compact(count, 1_000_000L) + "M"
        count >= 1_000L -> compact(count, 1_000L) + "K"
        else -> count.toString()
    }
}

/**
 * Compact value for large counts: 1.2, 2, 12, 120 — one decimal, but a
 * trailing ".0" is stripped so exact values read cleanly ("1K", not "1.0K").
 */
private fun compact(count: Long, unit: Long): String {
    val value = count.toDouble() / unit
    if (value >= 10.0) return value.toLong().toString()
    val s = String.format(Locale.US, "%.1f", value)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}
