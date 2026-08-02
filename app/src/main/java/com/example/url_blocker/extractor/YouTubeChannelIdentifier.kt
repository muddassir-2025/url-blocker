package com.example.url_blocker.extractor

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.regex.Pattern

/**
 * Identifies the YouTube channel currently on screen from the accessibility
 * tree.
 *
 * The mobile Chrome window title ("Chrome: <Video Title> - YouTube") does NOT
 * carry the channel name, so channel extraction must read the on-screen tree.
 * On a YouTube watch page the channel is exposed as a visible, clickable
 * channel-link node whose text is the handle (e.g. "@CNN") or the channel
 * name, and the description often begins "ChannelName · …" or contains
 * "Subscribe to ChannelName".
 *
 * Confidence rules (deliberately conservative — a wrong channel name would
 * block/record strikes for the WRONG channel):
 *   - STRONG: a visible node whose text is exactly an "@handle" shape.
 *   - STRONG: a visible node whose text/contentDescription ends with the
 *     word "channel" (e.g. "CNN channel", "Visit CNN channel").
 *   - MEDIUM: a visible node right next to a "Subscribe" button. Only used
 *     when the strong signals are absent.
 */
object YouTubeChannelIdentifier {

    private const val TAG = "YouTubeChannelIdentifier"

    private val HANDLE_REGEX = Pattern.compile("^@[A-Za-z0-9._\\-]{2,40}$")
    private val CHANNEL_SUFFIX = "channel"
    private val SUBSCRIBE_WORDS = listOf("subscribe", "subscribed")

    // Generic non-channel stems that merely end with "channel" (e.g. "this
    // channel", "the channel"). Compared against the TRIMMED stem, so no
    // trailing space. Action verbs (view/visit/open/watch) reject feed-card
    // UI labels like "View channel" — a button on every feed card, observed
    // on-device being misread as a channel name.
    private val GENERIC_CHANNEL_STEMS = setOf(
        "this", "the", "that", "your", "my", "our", "a",
        "view", "visit", "open", "watch", "about", "subscribe", "subscribed"
    )

    /**
     * Extract a channel name from the given tree.
     * Returns the normalized channel name (without leading "@") or null.
     */
    fun extractFromTree(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null

        // Single BFS pass: collect the strong candidates; fall back to the
        // "next to Subscribe" heuristic. Every node access is guarded: this
        // runs inside the 500ms polling loop on possibly-recycled nodes, and
        // an unguarded call would crash the whole accessibility service.
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val rootCopy = try { AccessibilityNodeInfo.obtain(rootNode) } catch (e: Exception) { null }
            ?: return null
        queue.add(rootCopy)
        var depth = 0

        while (queue.isNotEmpty() && depth < 80) {
            val node = queue.removeFirst()
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            val text = try { node.text?.toString()?.trim() } catch (e: Exception) { null }
            val desc = try { node.contentDescription?.toString()?.trim() } catch (e: Exception) { null }

            if (visible && !text.isNullOrBlank()) {
                if (HANDLE_REGEX.matcher(text).matches()) {
                    Log.i(TAG, "CHANNEL_FROM_HANDLE name=${text.trimStart('@')}")
                    return cleanHandle(text)
                }
                if (isChannelSuffixName(text)) {
                    Log.i(TAG, "CHANNEL_FROM_NAME name=$text")
                    return cleanHandle(text)
                }
            }
            if (visible && !desc.isNullOrBlank() && isChannelSuffixName(desc)) {
                Log.i(TAG, "CHANNEL_FROM_DESC name=$desc")
                return cleanHandle(desc)
            }

            val parentSubscribe = try {
                node.parent?.let { p ->
                    val pt = try { p.text?.toString() } catch (e: Exception) { null }
                    try { p.recycle() } catch (e: Exception) {}
                    pt
                }
            } catch (e: Exception) {
                null
            }
            if (visible && !text.isNullOrBlank() && parentSubscribe != null &&
                parentSubscribe.lowercase(Locale.ROOT).let { s ->
                    SUBSCRIBE_WORDS.any { s.contains(it) }
                }
            ) {
                Log.i(TAG, "CHANNEL_FROM_ADJACENT name=$text")
                return cleanHandle(text)
            }

            val childCount = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) queue.add(child)
            }
            try { node.recycle() } catch (e: Exception) {}
            depth++
        }
        return null
    }

    /**
     * True when [value] is a short label ending with the word "channel" that
     * plausibly names a channel (e.g. "CNN channel", "BBC News channel").
     * Rejects generic phrases ("this channel", "the channel", "change
     * channel") that merely end in the word. Internal so unit tests can cover
     * the rejection rules.
     */
    fun isChannelSuffixName(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        if (!lower.endsWith(CHANNEL_SUFFIX)) return false
        // "channel" alone / "the channel" are too generic. Compare the TRIMMED
        // stem against generic stems (no trailing spaces).
        val stem = lower.removeSuffix(CHANNEL_SUFFIX).trim()
        if (stem.length < 2) return false
        if (stem in GENERIC_CHANNEL_STEMS) return false
        if (lower.startsWith("change channel")) return false
        // "YouTube channel" / "youtube channel" are generic.
        if (lower == "youtube channel") return false
        // Feed-card UI labels that end in "channel" but are actions, not names
        // (e.g. "View channel", "Visit channel") — the stem verb check above
        // covers the common ones; keep the whole-phrase rejections as a belt
        // and braces for any variant not caught by stem trimming.
        if (lower in setOf("view channel", "visit channel", "open channel", "watch channel", "about channel")) {
            return false
        }
        return true
    }

    /** Strip a leading "@" (and trim) from a channel name/handle. */
    fun cleanHandle(name: String): String {
        var n = name.trim()
        while (n.startsWith("@")) n = n.removePrefix("@").trim()
        return n
    }
}
