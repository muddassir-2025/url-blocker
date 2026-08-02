package com.example.url_blocker

import com.example.url_blocker.extractor.ContentExtractor
import com.example.url_blocker.extractor.YouTubeChannelIdentifier
import com.example.url_blocker.repository.ChannelBlocklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelBlockingTest {

    // ── ChannelBlocklist.normalize ─────────────────────────────────

    @Test
    fun normalizeLowercasesAndTrims() {
        assertEquals("cnn", ChannelBlocklist.normalize("  CNN "))
        assertEquals("cnn", ChannelBlocklist.normalize("Cnn"))
        assertEquals("bbc news", ChannelBlocklist.normalize("BBC News"))
    }

    @Test
    fun normalizeStripsLeadingAtSign() {
        assertEquals("cnn", ChannelBlocklist.normalize("@CNN"))
        assertEquals("cnn", ChannelBlocklist.normalize("@@CNN"))
        assertEquals("cnn", ChannelBlocklist.normalize(" @CNN "))
    }

    @Test
    fun normalizeRejectsBlank() {
        assertNull(ChannelBlocklist.normalize(null))
        assertNull(ChannelBlocklist.normalize(""))
        assertNull(ChannelBlocklist.normalize("   "))
        assertNull(ChannelBlocklist.normalize("@"))
    }

    @Test
    fun blockedMatchingIsHandleInsensitive() {
        // A channel stored without "@" must match a handle read with "@" and
        // vice-versa (normalize is applied on both sides in the blocklist).
        assertEquals(ChannelBlocklist.normalize("@CNN"), ChannelBlocklist.normalize("cnn"))
        assertEquals(ChannelBlocklist.normalize("@BBC News"), ChannelBlocklist.normalize("bbc news"))
        assertEquals(ChannelBlocklist.normalize(" @@CNN "), ChannelBlocklist.normalize("cnn"))
    }

    // ── ChannelBlocklist strike policy (distinct videos only) ───────

    @Test
    fun reblockingSameVideoIsNotANewStrike() {
        // A channel with 1 strike re-blocking the SAME video stays at 1 strike.
        assertEquals(1, ChannelBlocklist.nextStrikeCount(1, "videoA", listOf("videoA")))
        // A NEW video from the same channel increments toward the threshold.
        assertEquals(2, ChannelBlocklist.nextStrikeCount(1, "videoB", listOf("videoA")))
        // New video on a fresh channel starts at 1.
        assertEquals(1, ChannelBlocklist.nextStrikeCount(0, "videoA", emptyList()))
    }

    @Test
    fun nullVideoIdCountsAsNewStrike() {
        // Null id (unavailable) is treated as a new strike — conservative.
        assertEquals(2, ChannelBlocklist.nextStrikeCount(1, null, listOf("videoA")))
        assertEquals(1, ChannelBlocklist.nextStrikeCount(0, null, emptyList()))
    }

    // ── YouTubeChannelIdentifier.cleanHandle ───────────────────────

    @Test
    fun cleanHandleStripsAtSignAndTrims() {
        assertEquals("CNN", YouTubeChannelIdentifier.cleanHandle("@CNN"))
        assertEquals("BBC News", YouTubeChannelIdentifier.cleanHandle("  @BBC News "))
        assertEquals("plain", YouTubeChannelIdentifier.cleanHandle("plain"))
        assertEquals("multiple", YouTubeChannelIdentifier.cleanHandle("@@multiple"))
    }

    @Test
    fun cleanHandleNeverReturnsBlankForValidInput() {
        assertEquals("a", YouTubeChannelIdentifier.cleanHandle("@a"))
        assertFalse(YouTubeChannelIdentifier.cleanHandle("x").isEmpty())
    }

    // ── YouTubeChannelIdentifier handle/name shape detection ───────

    @Test
    fun handleShapedTextIsDetected() {
        val handle = YouTubeChannelIdentifier.cleanHandle("@channel_123.abc")
        assertEquals("channel_123.abc", handle)
        assertTrue(handle.isNotEmpty())
    }

    @Test
    fun channelSuffixNamesAreAccepted() {
        assertTrue(YouTubeChannelIdentifier.isChannelSuffixName("CNN channel"))
        assertTrue(YouTubeChannelIdentifier.isChannelSuffixName("BBC News channel"))
        assertTrue(YouTubeChannelIdentifier.isChannelSuffixName("MrBeast channel"))
        assertTrue(YouTubeChannelIdentifier.isChannelSuffixName("@CNN channel"))
    }

    @Test
    fun genericChannelPhrasesAreRejected() {
        // Generic phrases that merely end with "channel" must never be read as
        // a channel name (would block/record strikes for the WRONG channel).
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("this channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("the channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("that channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("your channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("my channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("change channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("YouTube channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("x channel"))
    }

    @Test
    fun feedCardActionLabelsAreRejected() {
        // YouTube feed cards expose a "View channel" button label (observed
        // on-device as CHANNEL_FROM_DESC name=View channel). It is an action,
        // not a channel name — must never be read as a channel.
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("View channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("Visit channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("Open channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("Watch channel"))
        assertFalse(YouTubeChannelIdentifier.isChannelSuffixName("About channel"))
    }

    // ── ContentExtractor.isLikelyVideoTitle (pre-emptive feed filter) ─────

    @Test
    fun likelyVideoTitleAcceptsRealTitles() {
        assertTrue(ContentExtractor.isLikelyVideoTitle("I Asked 100 Girls to Rank Their Own Attractiveness"))
        assertTrue(ContentExtractor.isLikelyVideoTitle("Understanding the Importance of Women's Education"))
        assertTrue(ContentExtractor.isLikelyVideoTitle("Top 10 Workout Routines for Beginners"))
    }

    @Test
    fun likelyVideoTitleSplitsCombinedCardDescriptions() {
        // WebView cards often expose one combined accessibility string; the
        // title is the segment before the first metadata separator. Both
        // spaced and unspaced bullets must be handled.
        assertTrue(ContentExtractor.isLikelyVideoTitle("How to Study Effectively • 1.2M views • 3 days ago"))
        assertTrue(ContentExtractor.isLikelyVideoTitle("Beginner Yoga Routines•500K views•2 weeks ago"))
        // Contains a metadata marker after the separator — only passes because
        // the split isolates the title segment before "| 1.2M views".
        assertTrue(ContentExtractor.isLikelyVideoTitle("Cooking Basics for Everyone | 1.2M views"))
    }

    @Test
    fun likelyVideoTitleRejectsUrlsAndHints() {
        assertFalse(ContentExtractor.isLikelyVideoTitle("https://m.youtube.com"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("m.youtube.com"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("Search Google or type URL"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("search"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("ask anything"))
    }

    @Test
    fun likelyVideoTitleRejectsMetadataAndUi() {
        assertFalse(ContentExtractor.isLikelyVideoTitle("1.2M views"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("3 days ago"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("Subscribe"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("Shorts"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("Home"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("#shorts"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("12345"))
    }

    @Test
    fun likelyVideoTitleRejectsTooShortOrBlank() {
        assertFalse(ContentExtractor.isLikelyVideoTitle(null))
        assertFalse(ContentExtractor.isLikelyVideoTitle(""))
        assertFalse(ContentExtractor.isLikelyVideoTitle("   "))
        assertFalse(ContentExtractor.isLikelyVideoTitle("Hi"))
    }

    @Test
    fun likelyVideoTitleRejectsChromeNtpTiles() {
        // Chrome NTP tiles observed on-device as fake feed cards (490x110):
        // they must never pass the title profile even before the bounds gate.
        assertFalse(ContentExtractor.isLikelyVideoTitle("Ask AI Mode"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("New Incognito tab"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("Incognito"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("New tab"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("Recent tabs"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("Add to home screen"))
        assertFalse(ContentExtractor.isLikelyVideoTitle("Customize Chrome"))
    }

    // ── ContentExtractor.isYouTubeCardText (card-metadata gate) ────────────

    @Test
    fun youtubeCardTextAcceptsCombinedMetadataCards() {
        assertTrue(ContentExtractor.isYouTubeCardText("How to Study Effectively • 1.2M views • 3 days ago"))
        assertTrue(ContentExtractor.isYouTubeCardText("Beginner Yoga Routines•500K views•2 weeks ago"))
        assertTrue(ContentExtractor.isYouTubeCardText("Cooking Basics for Everyone | 1.2M views"))
        assertTrue(ContentExtractor.isYouTubeCardText("My Travel Vlog by HotTravelGirls • 500K views • 1 day ago"))
    }

    @Test
    fun youtubeCardTextAcceptsYouTubeSuffixTitles() {
        // "Video Title - YouTube" is the on-page title shape of a video
        // page/card carrying the YouTube brand suffix.
        assertTrue(ContentExtractor.isYouTubeCardText("Coffee and cleavage - YouTube"))
        assertTrue(ContentExtractor.isYouTubeCardText("Attachment Styles Explained - YouTube"))
    }

    @Test
    fun youtubeCardTextRejectsChromeUiTiles() {
        // NTP tiles have no separator metadata and no YouTube suffix.
        assertFalse(ContentExtractor.isYouTubeCardText("Ask AI Mode"))
        assertFalse(ContentExtractor.isYouTubeCardText("New Incognito tab"))
        assertFalse(ContentExtractor.isYouTubeCardText("Search with your voice"))
        assertFalse(ContentExtractor.isYouTubeCardText("Settings"))
        assertFalse(ContentExtractor.isYouTubeCardText(null))
        assertFalse(ContentExtractor.isYouTubeCardText(""))
        assertFalse(ContentExtractor.isYouTubeCardText("   "))
    }

    @Test
    fun youtubeCardTextRejectsYoutubeUiScreensWithSuffix() {
        // "Home - YouTube" / "Shorts - YouTube" are UI screen titles (from the
        // tab strip / window title), not video cards — the " - youtube" branch
        // must reject them exactly like youtubeTitleFromChromeWindowTitle does.
        assertFalse(ContentExtractor.isYouTubeCardText("Home - YouTube"))
        assertFalse(ContentExtractor.isYouTubeCardText("Shorts - YouTube"))
        assertFalse(ContentExtractor.isYouTubeCardText("Subscriptions - YouTube"))
        assertFalse(ContentExtractor.isYouTubeCardText("Settings - YouTube"))
    }

    // ── ContentExtractor.feedTitleSegment (combined card strings) ──────────

    @Test
    fun feedTitleSegmentIsolatesTitleFromCombinedCard() {
        assertEquals("How to Study Effectively", ContentExtractor.feedTitleSegment("How to Study Effectively • 1.2M views • 3 days ago"))
        assertEquals("Beginner Yoga Routines", ContentExtractor.feedTitleSegment("Beginner Yoga Routines•500K views•2 weeks ago"))
        assertEquals("Cooking Basics for Everyone", ContentExtractor.feedTitleSegment("Cooking Basics for Everyone | 1.2M views"))
    }

    @Test
    fun feedTitleSegmentKeepsPlainTitleUnchanged() {
        assertEquals("Plain Video Title", ContentExtractor.feedTitleSegment("Plain Video Title"))
        assertEquals("Understanding the Importance of Women's Education", ContentExtractor.feedTitleSegment("Understanding the Importance of Women's Education"))
    }

    @Test
    fun feedTitleSegmentHandlesBlankInput() {
        assertEquals("", ContentExtractor.feedTitleSegment(null))
        assertEquals("", ContentExtractor.feedTitleSegment(""))
        assertEquals("", ContentExtractor.feedTitleSegment("   "))
    }

    // ── ContentExtractor.channelFromCardText (channel-by signal) ───────────

    @Test
    fun channelFromCardTextExtractsChannelAfterBy() {
        assertEquals(
            "HotTravelGirls",
            ContentExtractor.channelFromCardText("My Travel Vlog by HotTravelGirls • 1.2M views • 3 days ago")
        )
        assertEquals(
            "FitLife",
            ContentExtractor.channelFromCardText("10 Minute Workout by FitLife • 500K views • 2 weeks ago")
        )
        assertEquals(
            "MrBeast",
            ContentExtractor.channelFromCardText("I Gave Away $100000 by MrBeast • 20M views • 1 day ago")
        )
    }

    @Test
    fun channelFromCardTextUsesLastByForTitlesContainingBy() {
        // A title that itself contains " by " must still yield the trailing
        // channel (last " by " in the title segment wins).
        assertEquals(
            "Adele",
            ContentExtractor.channelFromCardText("Songs by Adele • 2M views • 1 year ago")
        )
    }

    @Test
    fun channelFromCardTextHandlesUnspacedBullets() {
        assertEquals(
            "Nightcore",
            ContentExtractor.channelFromCardText("Lofi Beats by Nightcore•1M views•2 days ago")
        )
    }

    @Test
    fun channelFromCardTextRejectsMissingMetadata() {
        // No metadata bullets — a sentence-y title that merely contains
        // " by " must NOT produce a junk channel (e.g. "Songs by Adele to Cry
        // To" would otherwise yield "Adele to Cry To").
        assertNull(ContentExtractor.channelFromCardText("Songs by Adele to Cry To"))
        assertNull(ContentExtractor.channelFromCardText("A plain title"))
    }

    @Test
    fun channelFromCardTextRejectsBlankAndJunk() {
        assertNull(ContentExtractor.channelFromCardText(null))
        assertNull(ContentExtractor.channelFromCardText(""))
        assertNull(ContentExtractor.channelFromCardText("   "))
    }

    @Test
    fun channelFromCardTextStripsLeadingPunctuation() {
        // Leading decoration (@, dashes, bullets) must not prevent a match
        // against the blocklist's normalized names.
        assertEquals(
            "HotGirls TV",
            ContentExtractor.channelFromCardText("Beach Vlog by — HotGirls TV • 500K views • 1 day ago")
        )
        assertEquals(
            "MrBeast",
            ContentExtractor.channelFromCardText("Giveaway by @MrBeast • 20M views • 1 day ago")
        )
    }

    // ── ContentExtractor.thumbnailBoundsForCard (thumbnail region geometry) ──
    // The on-device evidence these tests pin: a "card" node is sometimes the
    // FULL card (thumbnail + title) and sometimes ONLY the title text row. The
    // NSFW pipeline must crop the 16:9 IMAGE band — never the title text (an
    // 800x79 title strip was previously analyzed as a "thumbnail").

    @Test
    fun thumbnailBoundsForCardCropsFullCardNodeAtItsTop() {
        // Full-card node [0,230,1080,2280] on a 1080x2400 screen: its top edge
        // IS the thumbnail's top; crop the top 16:9 band (1080x607).
        val r = ContentExtractor.thumbnailBoundsForCard(0, 230, 1080, 2280, 1080, 2400)
        assertNotNull(r)
        assertEquals(0, r!!.left)
        assertEquals(230, r.top)
        assertEquals(1080, r.right)
        assertEquals(230 + (1080 * 9 / 16), r.bottom)
    }

    @Test
    fun thumbnailBoundsForCardLiftsThumbnailAboveShortTitleRow() {
        // Text-only node [160,1790,960,1922]: the thumbnail is the 16:9 band
        // directly ABOVE the text row, ending exactly at its top.
        val r = ContentExtractor.thumbnailBoundsForCard(160, 1790, 960, 1922, 1080, 2400)
        assertNotNull(r)
        assertEquals(160, r!!.left)
        assertEquals(1790 - (800 * 9 / 16), r.top)
        assertEquals(960, r.right)
        assertEquals(1790, r.bottom)
    }

    @Test
    fun thumbnailBoundsForCardRejectsTinyRegions() {
        // 140x30 is a text strip, not a thumbnail.
        assertNull(ContentExtractor.thumbnailBoundsForCard(10, 100, 150, 130, 1080, 2400))
        // Too narrow even when tall.
        assertNull(ContentExtractor.thumbnailBoundsForCard(500, 100, 640, 1200, 1080, 2400))
        // Full width but only 60px tall — a title row, not a thumbnail.
        assertNull(ContentExtractor.thumbnailBoundsForCard(0, 100, 1080, 160, 1080, 2400))
    }

    @Test
    fun thumbnailBoundsForCardLiftsOnlyWhenBandFitsOnScreen() {
        // Title row at y=700 has room for the 16:9 band above (700-607=93).
        val r = ContentExtractor.thumbnailBoundsForCard(0, 700, 1080, 850, 1080, 2400)
        assertNotNull(r)
        assertEquals(93, r!!.top)
        assertEquals(700, r.bottom)
        assertEquals(1080, r.width)
        assertEquals(607, r.height)
        // A row too near the top has NO thumbnail above it on screen → null.
        assertNull(ContentExtractor.thumbnailBoundsForCard(0, 50, 1080, 200, 1080, 2400))
    }

    @Test
    fun thumbnailBoundsForCardClampsRightEdgeToScreen() {
        // Card wider than the screen: the band clamps to the screen width.
        val r = ContentExtractor.thumbnailBoundsForCard(0, 800, 1200, 900, 1080, 2400)
        assertNotNull(r)
        assertEquals(1080, r!!.right)
        assertEquals(1080, r.width)
    }
}
