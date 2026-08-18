package com.muddassir.clearview.media.util

import java.text.SimpleDateFormat
import java.util.Date
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

/**
 * Human-readable byte size for downloads / storage cards:
 * 500 → "500 B", 14336 → "14 KB", 286_720_000 → "286.7 MB", 1_500_000_000 → "1.5 GB".
 * Whole values drop the ".0" ("14 MB", not "14.0 MB").
 *
 * Pure JVM function (no Android/Compose dependencies) so it is unit-testable.
 */
internal fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    return when {
        b >= 1L shl 30 -> compactBytes(b, 1L shl 30) + " GB"
        b >= 1L shl 20 -> compactBytes(b, 1L shl 20) + " MB"
        b >= 1L shl 10 -> compactBytes(b, 1L shl 10) + " KB"
        else -> "$b B"
    }
}

/** Compact value for byte sizes: one decimal, trailing ".0" stripped. */
private fun compactBytes(value: Long, unit: Long): String {
    val s = String.format(Locale.US, "%.1f", value.toDouble() / unit)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/**
 * "12 Jan 2026" from an epoch-millis timestamp (download dates, the Manage
 * Storage sheet), or "—" when the time is unknown. Pure JVM so it is
 * unit-testable.
 */
internal fun formatDownloadDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    return SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMillis))
}

/**
 * "~2m 30s left" for a download ETA (seconds), or "" while the estimate is
 * unavailable (too early in the download, the size is unknown, or the finish
 * line is right there — "~0s left" is noise). Shared by the feed thumbnails,
 * the video player and the Downloads list. Pure JVM so it is unit-testable.
 */
internal fun formatEtaRemaining(seconds: Long): String {
    if (seconds < 2L) return ""
    val s = seconds.coerceAtLeast(0L)
    return when {
        s < 60 -> "~${s}s left"
        s < 3600 -> "~${s / 60}m ${s % 60}s left"
        else -> "~${s / 3600}h ${(s % 3600) / 60}m left"
    }
}
