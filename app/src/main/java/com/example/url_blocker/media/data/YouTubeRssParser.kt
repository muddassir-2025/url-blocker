package com.example.url_blocker.media.data

import com.example.url_blocker.media.model.MediaVideo
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses YouTube's per-channel RSS feed
 * (`https://www.youtube.com/feeds/videos.xml?channel_id=UC…`) into a flat list
 * of [MediaVideo].
 *
 * Pure function (no Android dependencies beyond the JVM DOM/date APIs, which
 * both Android and the JVM unit tests provide) so it is unit-testable.
 *
 * Parsing is defensive: any malformed entry is skipped rather than failing the
 * whole feed, and a totally unparseable body returns an empty list.
 */
object YouTubeRssParser {

    /** Thumbnail fallback if the feed omits a media:thumbnail. */
    fun fallbackThumbnail(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    /**
     * Parses [rssXml] into videos, most-recent first (the feed is already
     * newest-first, but a stable sort guards against feed reordering).
     * Returns an empty list on any parse failure (never throws).
     */
    fun parse(rssXml: String): List<MediaVideo> {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            // Namespace awareness is required for localName-based lookups
            // (yt:videoId, media:thumbnail) on both the JVM and Android.
            factory.isNamespaceAware = true
            // Disable external entity resolution for safety on this raw input.
            try {
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            } catch (e: Exception) {
                // Some DOM implementations don't expose this feature — harmless.
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(rssXml.toByteArray(StandardCharsets.UTF_8)))
            doc.documentElement.normalize()

            val entryNodes = doc.getElementsByTagNameNS("*", "entry")
            val videos = ArrayList<MediaVideo>(entryNodes.length)
            for (i in 0 until entryNodes.length) {
                val entry = entryNodes.item(i) as? Element ?: continue
                val videoId = firstText(entry, "videoId")?.trim().orEmpty()
                if (videoId.isBlank()) continue
                val title = firstText(entry, "title")?.trim().orEmpty()
                val channelId = firstText(entry, "channelId")?.trim().orEmpty()
                val channelName = firstText(entry, "name")?.trim().orEmpty()
                val publishedRaw = firstText(entry, "published")?.trim().orEmpty()
                val publishedAt = parsePublished(publishedRaw) ?: 0L
                val thumbnail = firstThumbnailUrl(entry) ?: fallbackThumbnail(videoId)
                videos.add(
                    MediaVideo(
                        videoId = videoId,
                        title = title,
                        channelId = channelId,
                        channelName = channelName,
                        publishedAtEpochMillis = publishedAt,
                        thumbnailUrl = thumbnail
                    )
                )
            }
            videos
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** First descendant element with the given local tag name, or null. */
    private fun firstText(parent: Element, localName: String): String? {
        val all = parent.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i)
            if (node is Element && node.localName == localName) {
                return node.textContent
            }
        }
        return null
    }

    /** First media:thumbnail element's url attribute, or null. */
    private fun firstThumbnailUrl(parent: Element): String? {
        val all = parent.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i)
            if (node is Element && node.localName == "thumbnail") {
                val url = node.getAttribute("url")
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    /** Parses YouTube's ISO-8601 published stamp (e.g. 2024-01-01T12:00:00+00:00). */
    private fun parsePublished(raw: String): Long? {
        return try {
            // "X" handles +00:00 style offsets; parser must be non-lenient to
            // reject garbage. SimpleDateFormat is not thread-safe — created per
            // call on purpose.
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            fmt.parse(raw)?.time
        } catch (e: Exception) {
            null
        }
    }
}
