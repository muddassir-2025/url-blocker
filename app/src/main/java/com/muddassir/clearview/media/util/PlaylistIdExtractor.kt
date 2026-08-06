package com.muddassir.clearview.media.util

/**
 * Extracts a YouTube playlist id (the `list=` parameter) from a URL or a bare
 * id. Handles the playlist URL forms (`/playlist?list=`, watch URLs with a
 * `list` param, `music.youtube.com`, `youtu.be`) plus a bare playlist id.
 * Returns null when nothing matches. Pure string logic — unit-testable on the
 * JVM.
 *
 * Playlist ids come in a few shapes (PL + 32 chars, UC/FL/WL uploads lists,
 * OLAK5uy_ album lists, …), so the bare-id rule accepts a 13–43 char
 * id-ish token and deliberately never matches an 11-char video id.
 */
fun extractYouTubePlaylistId(input: String): String? {
    val t = input.trim()
    if (t.isEmpty()) return null
    // list=ID anywhere in the URL (covers /playlist?list=, watch?v=&list=,
    // youtu.be, music.youtube.com, …). Stops at the next & or #.
    Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(t)?.let { return it.groupValues[1] }
    // /playlist/ID path form (rare, but supported).
    Regex("""youtube\.com/playlist/([A-Za-z0-9_-]+)""").find(t)?.let { return it.groupValues[1] }
    // YouTube's short SYSTEM list ids (Watch Later "WL", Liked "LL",
    // Favorites "FL", Watch on Demand "WD") — the only 2-letter bare ids.
    if (t in SHORT_SYSTEM_LIST_IDS) return t
    // A bare playlist id pasted on its own (no URL shape at all).
    if (t.length in 13..43 && t.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        return t
    }
    return null
}

/** YouTube's special 2-letter list ids (no long PL- prefix). */
private val SHORT_SYSTEM_LIST_IDS = setOf("WL", "LL", "FL", "WD")
