package com.muddassir.clearview.media.data

import android.util.Log
import com.muddassir.clearview.media.model.InstagramMediaType
import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses Instagram RSS 2.0 and Atom feeds (from providers like RSS.app, RSS-Bridge,
 * or custom public RSS generators) into normalized [MediaVideo] items.
 *
 * Guarantees:
 * - Extracts multiple items (never stops at 1)
 * - Classifies content types (IMAGE, REEL, VIDEO, CAROUSEL)
 * - Extracts direct media URL when available, leaving it null otherwise
 * - Extracts original Instagram URL for explicit user opening
 * - Defensive parsing: malformed entries skipped without failing the feed
 */
object InstagramRssParser {

    private const val TAG = "InstagramRssParser"

    data class ParsedInstagramFeed(
        val username: String,
        val title: String,
        val avatarUrl: String?,
        val items: List<MediaVideo>
    )

    fun parse(xml: String, fallbackUsername: String): ParsedInstagramFeed? {
        if (xml.isBlank()) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                try {
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                } catch (_: Exception) {}
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
            doc.documentElement.normalize()

            val rootTag = doc.documentElement.localName ?: doc.documentElement.tagName
            if (rootTag.equals("feed", ignoreCase = true)) {
                parseAtomFeed(doc.documentElement, fallbackUsername)
            } else {
                parseRssFeed(doc.documentElement, fallbackUsername)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Instagram RSS: ${e.message}")
            null
        }
    }

    private fun parseRssFeed(root: Element, fallbackUsername: String): ParsedInstagramFeed? {
        val channel = root.getElementsByTagName("channel").item(0) as? Element ?: return null
        val feedTitle = firstText(channel, "title")?.trim().orEmpty()
        val username = extractUsernameFromTitle(feedTitle, fallbackUsername)
        val channelId = "ig_${username.lowercase()}"

        val avatarUrl = firstImageLogo(channel)

        val itemNodes = channel.getElementsByTagName("item")
        val items = ArrayList<MediaVideo>(itemNodes.length)

        for (i in 0 until itemNodes.length) {
            val item = itemNodes.item(i) as? Element ?: continue
            val link = firstText(item, "link")?.trim().orEmpty()
            val guid = firstText(item, "guid")?.trim().orEmpty()
            val rawTitle = firstText(item, "title")?.trim().orEmpty()
            val description = firstText(item, "description")?.trim().orEmpty()
            val pubDateRaw = firstText(item, "pubDate")?.trim().orEmpty()
            val publishedAt = parseDate(pubDateRaw) ?: (System.currentTimeMillis() - (i * 3600_000L))

            val originalUrl = when {
                link.contains("instagram.com/") -> link
                guid.contains("instagram.com/") -> guid
                else -> link.ifBlank { guid }
            }

            val shortcode = extractShortcode(originalUrl).ifBlank {
                guid.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "$i"
            }
            val videoId = "ig_$shortcode"

            // Media extraction
            val enclosureUrl = firstEnclosureUrl(item)
            val enclosureType = firstEnclosureType(item)
            val mediaContentUrl = firstMediaContentUrl(item)
            val mediaContentMedium = firstMediaContentMedium(item)
            val mediaThumbnail = firstMediaThumbnail(item)
            val descImageUrls = extractImagesFromHtml(description)

            val isVideoContent = enclosureType.startsWith("video") ||
                mediaContentMedium.equals("video", ignoreCase = true) ||
                originalUrl.contains("/reel/", ignoreCase = true) ||
                enclosureUrl.endsWith(".mp4", ignoreCase = true) ||
                mediaContentUrl.endsWith(".mp4", ignoreCase = true)

            val directMediaUrl = when {
                enclosureUrl.endsWith(".mp4", ignoreCase = true) || enclosureType.startsWith("video") -> enclosureUrl
                mediaContentUrl.endsWith(".mp4", ignoreCase = true) || mediaContentMedium.equals("video", ignoreCase = true) -> mediaContentUrl
                else -> null
            }

            val thumbnailUrl = when {
                !mediaThumbnail.isNullOrBlank() -> mediaThumbnail
                !mediaContentUrl.isNullOrBlank() && !isVideoContent -> mediaContentUrl
                !enclosureUrl.isNullOrBlank() && !isVideoContent -> enclosureUrl
                descImageUrls.isNotEmpty() -> descImageUrls.first()
                else -> ""
            }

            val igType = when {
                originalUrl.contains("/reel/", ignoreCase = true) -> InstagramMediaType.REEL
                isVideoContent -> InstagramMediaType.VIDEO
                descImageUrls.size > 1 -> InstagramMediaType.CAROUSEL
                else -> InstagramMediaType.IMAGE
            }

            val caption = cleanHtmlCaption(description).ifBlank { rawTitle }
            val itemTitle = caption.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
                ?: "$username ${if (isVideoContent) "Reel" else "Post"}"

            items.add(
                MediaVideo(
                    videoId = videoId,
                    title = itemTitle,
                    channelId = channelId,
                    channelName = feedTitle.ifBlank { username },
                    publishedAtEpochMillis = publishedAt,
                    thumbnailUrl = thumbnailUrl,
                    viewCount = 0L,
                    isShort = isVideoContent,
                    isLive = false,
                    durationSeconds = 0L,
                    platform = MediaPlatform.INSTAGRAM,
                    instagramType = igType,
                    mediaUrl = directMediaUrl,
                    instagramUrl = originalUrl.takeIf { it.isNotBlank() }
                )
            )
        }

        return ParsedInstagramFeed(
            username = username,
            title = feedTitle.ifBlank { username },
            avatarUrl = avatarUrl,
            items = items
        )
    }

    private fun parseAtomFeed(root: Element, fallbackUsername: String): ParsedInstagramFeed? {
        val feedTitle = firstText(root, "title")?.trim().orEmpty()
        val username = extractUsernameFromTitle(feedTitle, fallbackUsername)
        val channelId = "ig_${username.lowercase()}"
        val avatarUrl = firstText(root, "logo") ?: firstText(root, "icon")

        val entryNodes = root.getElementsByTagName("entry")
        val items = ArrayList<MediaVideo>(entryNodes.length)

        for (i in 0 until entryNodes.length) {
            val entry = entryNodes.item(i) as? Element ?: continue
            val link = firstAlternateLink(entry)
            val id = firstText(entry, "id")?.trim().orEmpty()
            val rawTitle = firstText(entry, "title")?.trim().orEmpty()
            val content = firstText(entry, "content") ?: firstText(entry, "summary") ?: ""
            val publishedRaw = firstText(entry, "published") ?: firstText(entry, "updated") ?: ""
            val publishedAt = parseDate(publishedRaw) ?: (System.currentTimeMillis() - (i * 3600_000L))

            val originalUrl = when {
                link.contains("instagram.com/") -> link
                id.contains("instagram.com/") -> id
                else -> link.ifBlank { id }
            }

            val shortcode = extractShortcode(originalUrl).ifBlank {
                id.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "$i"
            }
            val videoId = "ig_$shortcode"

            val descImageUrls = extractImagesFromHtml(content)
            val isVideoContent = originalUrl.contains("/reel/", ignoreCase = true) ||
                content.contains("<video", ignoreCase = true)

            val directMediaUrl = extractVideoFromHtml(content)

            val thumbnailUrl = descImageUrls.firstOrNull() ?: ""
            val igType = when {
                originalUrl.contains("/reel/", ignoreCase = true) -> InstagramMediaType.REEL
                isVideoContent -> InstagramMediaType.VIDEO
                descImageUrls.size > 1 -> InstagramMediaType.CAROUSEL
                else -> InstagramMediaType.IMAGE
            }

            val caption = cleanHtmlCaption(content).ifBlank { rawTitle }
            val itemTitle = caption.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
                ?: "$username ${if (isVideoContent) "Reel" else "Post"}"

            items.add(
                MediaVideo(
                    videoId = videoId,
                    title = itemTitle,
                    channelId = channelId,
                    channelName = feedTitle.ifBlank { username },
                    publishedAtEpochMillis = publishedAt,
                    thumbnailUrl = thumbnailUrl,
                    viewCount = 0L,
                    isShort = isVideoContent,
                    isLive = false,
                    durationSeconds = 0L,
                    platform = MediaPlatform.INSTAGRAM,
                    instagramType = igType,
                    mediaUrl = directMediaUrl,
                    instagramUrl = originalUrl.takeIf { it.isNotBlank() }
                )
            )
        }

        return ParsedInstagramFeed(
            username = username,
            title = feedTitle.ifBlank { username },
            avatarUrl = avatarUrl,
            items = items
        )
    }

    private fun extractShortcode(url: String): String {
        val patterns = listOf(
            Regex("""instagram\.com/(?:p|reel|tv)/([^/?#&]+)"""),
            Regex("""instagr\.am/(?:p|reel|tv)/([^/?#&]+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return ""
    }

    private fun extractUsernameFromTitle(title: String, fallback: String): String {
        val clean = title.removePrefix("Instagram -").removePrefix("Instagram:").trim()
        val match = Regex("""@?([a-zA-Z0-9._]{1,30})""").find(clean)
        return match?.groupValues?.get(1) ?: fallback
    }

    private fun extractImagesFromHtml(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val regex = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return regex.findAll(html).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
    }

    private fun extractVideoFromHtml(html: String): String? {
        if (html.isBlank()) return null
        val regex = Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun cleanHtmlCaption(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace(Regex("""<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<[^>]*>"""), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private fun firstText(parent: Element, localName: String): String? {
        val all = parent.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i)
            if (node is Element && (node.localName == localName || node.tagName == localName)) {
                return node.textContent
            }
        }
        return null
    }

    private fun firstAlternateLink(parent: Element): String {
        val all = parent.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i)
            if (node is Element && (node.localName == "link" || node.tagName == "link")) {
                val rel = node.getAttribute("rel")
                if (rel.isEmpty() || rel.contains("alternate", ignoreCase = true)) {
                    val href = node.getAttribute("href").trim()
                    if (href.isNotBlank()) return href
                }
            }
        }
        return ""
    }

    private fun firstImageLogo(channel: Element): String? {
        val images = channel.getElementsByTagName("image")
        if (images.length > 0) {
            val img = images.item(0) as? Element
            if (img != null) {
                val url = firstText(img, "url")
                if (!url.isNullOrBlank()) return url
            }
        }
        return null
    }

    private fun firstEnclosureUrl(item: Element): String {
        val nodes = item.getElementsByTagName("enclosure")
        if (nodes.length > 0) {
            val el = nodes.item(0) as? Element
            return el?.getAttribute("url").orEmpty()
        }
        return ""
    }

    private fun firstEnclosureType(item: Element): String {
        val nodes = item.getElementsByTagName("enclosure")
        if (nodes.length > 0) {
            val el = nodes.item(0) as? Element
            return el?.getAttribute("type").orEmpty()
        }
        return ""
    }

    private fun firstMediaContentUrl(item: Element): String {
        val all = item.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i)
            if (node is Element && (node.localName == "content" || node.tagName.endsWith(":content"))) {
                val url = node.getAttribute("url")
                if (url.isNotBlank()) return url
            }
        }
        return ""
    }

    private fun firstMediaContentMedium(item: Element): String {
        val all = item.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i)
            if (node is Element && (node.localName == "content" || node.tagName.endsWith(":content"))) {
                val med = node.getAttribute("medium")
                if (med.isNotBlank()) return med
            }
        }
        return ""
    }

    private fun firstMediaThumbnail(item: Element): String? {
        val all = item.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i)
            if (node is Element && (node.localName == "thumbnail" || node.tagName.endsWith(":thumbnail"))) {
                val url = node.getAttribute("url")
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    private fun parseDate(raw: String): Long? {
        if (raw.isBlank()) return null
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (f in formats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = sdf.parse(raw)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return null
    }
}
