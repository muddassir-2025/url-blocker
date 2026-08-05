package com.muddassir.clearview.media.util

/**
 * Extracts a YouTube video id from a URL (watch / youtu.be / shorts / embed
 * forms) or a bare 11-character id. Returns null when nothing matches.
 * Pure string logic — unit-testable on the JVM.
 */
fun extractYouTubeVideoId(input: String): String? {
    val t = input.trim()
    if (t.isEmpty()) return null
    // Bare 11-char id.
    if (t.length == 11 && t.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return t
    // watch?v=ID (also covers share URLs with extra params).
    Regex("[?&]v=([A-Za-z0-9_-]{11})").find(t)?.let { return it.groupValues[1] }
    // youtu.be/ID
    Regex("""youtu\.be/([A-Za-z0-9_-]{11})""").find(t)?.let { return it.groupValues[1] }
    // youtube.com/shorts/ID
    Regex("""youtube\.com/shorts/([A-Za-z0-9_-]{11})""").find(t)?.let { return it.groupValues[1] }
    // youtube.com/embed/ID
    Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})""").find(t)?.let { return it.groupValues[1] }
    return null
}
