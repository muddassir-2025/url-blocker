package com.muddassir.clearview.media.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPageParserTest {

    // A realistic playlist page: the embedded ytInitialData JSON carries the
    // title + the ordered playlistVideoListRenderer entries (one of which has
    // no id — the "private video" placeholder that must be skipped).
    private val sampleHtml = """
        <!DOCTYPE html><html><head><script>var ytInitialData = {
          "header": {
            "playlistHeaderRenderer": { "title": { "runs": [ { "text": "My Cool Playlist" } ] } }
          },
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "itemSectionRenderer": {
                              "contents": [
                                {
                                  "playlistVideoListRenderer": {
                                    "contents": [
                                      { "playlistVideoRenderer": { "videoId": "vid1", "title": {"runs": [{"text": "First Video"}]}, "index": {"simpleText": "1"}, "lengthText": {"simpleText": "12:34"}, "shortBylineText": {"runs": [{"text": "Channel One"}]}, "longBylineText": {"runs": [{"text": "Channel One", "navigationEndpoint": {"browseEndpoint": {"browseId": "UC123"}}}]}, "videoInfo": {"runs": [{"text": "1,234,567 views"}, {"text": "3 days ago"}]}, "publishedTimeText": {"simpleText": "3 days ago"} } },
                                      { "playlistVideoRenderer": { "videoId": "vid2", "title": {"simpleText": "Second Video #shorts"}, "index": {"simpleText": "2"}, "lengthText": {"simpleText": "0:45"}, "shortBylineText": {"runs": [{"text": "Channel Two"}]}, "longBylineText": {"runs": [{"text": "Channel Two", "navigationEndpoint": {"browseEndpoint": {"browseId": "UC456"}}}]}, "publishedTimeText": {"simpleText": "2 weeks ago"} } },
                                      { "playlistVideoRenderer": { "videoId": "", "title": {"runs": [{"text": "Private video"}]} } }
                                    ]
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        };</script></head><body></body></html>
    """.trimIndent()

    @Test
    fun `parses title and ordered videos from the page`() {
        val info = PlaylistPageParser.parsePage(sampleHtml)
        assertEquals("My Cool Playlist", info?.title)
        assertEquals(listOf("vid1", "vid2"), info?.videos?.map { it.videoId })
    }

    @Test
    fun `parses video metadata`() {
        val info = PlaylistPageParser.parsePage(sampleHtml)!!
        val first = info.videos[0]
        assertEquals("First Video", first.title)
        assertEquals("Channel One", first.channelName)
        assertEquals("UC123", first.channelId)
        assertEquals(754L, first.durationSeconds) // 12:34
        assertEquals(1_234_567L, first.viewCount)
        assertEquals("https://i.ytimg.com/vi/vid1/hqdefault.jpg", first.thumbnailUrl)
        // Relative publish label → a recent epoch (within the last ~4 days).
        val threeDays = 3L * 24 * 60 * 60 * 1000
        assertTrue(first.publishedAtEpochMillis > 0L)
        assertTrue(
            System.currentTimeMillis() - first.publishedAtEpochMillis in
                threeDays - 60_000L..threeDays + 60_000L
        )
    }

    @Test
    fun `classifies shorts by title hashtag`() {
        val info = PlaylistPageParser.parsePage(sampleHtml)!!
        assertTrue(info.videos[1].isShort)
    }

    @Test
    fun `skips entries without a video id`() {
        val info = PlaylistPageParser.parsePage(sampleHtml)!!
        assertEquals(2, info.videos.size)
    }

    @Test
    fun `returns null when the page has no ytInitialData`() {
        assertNull(PlaylistPageParser.parsePage("<html><body>nothing here</body></html>"))
        assertNull(PlaylistPageParser.parsePage(""))
    }

    @Test
    fun `private playlist page yields title with no videos`() {
        val privateHtml = """
            <script>var ytInitialData = {"header":{"playlistHeaderRenderer":{"title":{"simpleText":"Secret List"}}},"contents":{}};</script>
        """.trimIndent()
        val info = PlaylistPageParser.parsePage(privateHtml)
        assertEquals("Secret List", info?.title)
        assertTrue(info!!.videos.isEmpty())
    }

    // ── Modern page format (2025+): no header / no embedded video list ──

    @Test
    fun `title falls back to playlistMetadataRenderer in the modern format`() {
        // Modern playlist pages dropped playlistHeaderRenderer; the title now
        // lives in metadata.playlistMetadataRenderer.title (a plain string).
        val modernHtml = """
            <script>var ytInitialData = {"metadata":{"playlistMetadataRenderer":{"title":"Quran","description":""}},"contents":{}};</script>
        """.trimIndent()
        val info = PlaylistPageParser.parsePage(modernHtml)
        assertEquals("Quran", info?.title)
        // The modern page embeds NO videos — a public playlist is NOT private.
        assertTrue(info!!.videos.isEmpty())
    }

    @Test
    fun `title falls back to the sidebar primary info in the modern format`() {
        val modernHtml = """
            <script>var ytInitialData = {"sidebar":{"playlistSidebarRenderer":{"items":[
              {"playlistSidebarPrimaryInfoRenderer":{"title":{"runs":[{"text":"Sidebar Title"}]}}},
              {"playlistSidebarSecondaryInfoRenderer":{}}
            ]}},"contents":{}};</script>
        """.trimIndent()
        val info = PlaylistPageParser.parsePage(modernHtml)
        assertEquals("Sidebar Title", info?.title)
    }

    // ── /next playlist panel (modern page format video list) ───────────

    private val panelBody = """
        {
          "responseContext": { "mainAppWebResponseContext": { "loggedOut": true } },
          "contents": {
            "twoColumnWatchNextResults": {
              "playlist": {
                "playlist": {
                  "titleText": { "runs": [ { "text": "Quran" } ] },
                  "totalVideosText": { "runs": [ { "text": "2" }, { "text": " videos" } ] },
                  "contents": [
                    { "playlistPanelVideoRenderer": {
                        "videoId": "vid1",
                        "title": { "simpleText": "Panel One" },
                        "lengthText": { "simpleText": "12:34" },
                        "longBylineText": { "runs": [ { "text": "Channel One", "navigationEndpoint": { "browseEndpoint": { "browseId": "UC123" } } } ] }
                    } },
                    { "playlistPanelVideoRenderer": {
                        "videoId": "vid2",
                        "title": { "simpleText": "Panel Two #shorts" },
                        "lengthText": { "simpleText": "0:45" },
                        "shortBylineText": { "runs": [ { "text": "Channel Two" } ] }
                    } },
                    { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "PANEL-NEXT" } } } }
                  ]
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parsePlaylistPanel reads the ordered panel videos and next token`() {
        val panel = PlaylistPageParser.parsePlaylistPanel(panelBody)!!
        assertEquals("Quran", panel.title)
        assertEquals(listOf("vid1", "vid2"), panel.videos.map { it.videoId })
        assertEquals(754L, panel.videos[0].durationSeconds) // 12:34
        assertEquals("Channel One", panel.videos[0].channelName)
        assertEquals("UC123", panel.videos[0].channelId)
        assertTrue(panel.videos[1].isShort)
        assertEquals("PANEL-NEXT", panel.nextToken)
    }

    @Test
    fun `parsePlaylistPanel without a continuation ends the walk`() {
        val body = """
            {"contents":{"twoColumnWatchNextResults":{"playlist":{"playlist":{
              "titleText":{"simpleText":"Short List"},
              "contents":[ {"playlistPanelVideoRenderer":{"videoId":"v1","title":{"simpleText":"One"}}} ]
            }}}}}
        """.trimIndent()
        val panel = PlaylistPageParser.parsePlaylistPanel(body)!!
        assertEquals(listOf("v1"), panel.videos.map { it.videoId })
        assertEquals(null, panel.nextToken)
    }

    @Test
    fun `parsePlaylistPanel reads continuation pages (onResponseReceivedEndpoints)`() {
        // /next continuation pages carry the next batch under the action
        // container, with no twoColumnWatchNextResults wrapper and no title.
        val body = """
            {"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{
              "continuationItems": [
                { "playlistPanelVideoRenderer": { "videoId": "v30", "title": {"simpleText": "Thirty"}, "lengthText": {"simpleText": "2:00"} } },
                { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "TOKEN-PANEL-2" } } } }
              ]
            }}]}
        """.trimIndent()
        val panel = PlaylistPageParser.parsePlaylistPanel(body)!!
        assertEquals(listOf("v30"), panel.videos.map { it.videoId })
        assertEquals(120L, panel.videos[0].durationSeconds)
        assertEquals("TOKEN-PANEL-2", panel.nextToken)
        // Continuation pages carry no title — page 1's title is kept.
        assertEquals("", panel.title)
    }

    @Test
    fun `parsePlaylistPanel rejects garbage and non-panel responses`() {
        assertNull(PlaylistPageParser.parsePlaylistPanel(""))
        assertNull(PlaylistPageParser.parsePlaylistPanel("not json"))
        // No playlist wrapper AND no continuation container → not a panel.
        assertNull(PlaylistPageParser.parsePlaylistPanel("""{"foo": 1}"""))
        // An action container with no items parses to an EMPTY panel (not
        // null) — the pagination walk simply stops.
        val empty = PlaylistPageParser.parsePlaylistPanel("""{"onResponseReceivedActions":[]}""")
        assertTrue(empty != null && empty.videos.isEmpty())
    }

    @Test
    fun `parseContinuationPage accepts onResponseReceivedEndpoints (next pages)`() {
        // /next continuation pages wrap their items in
        // onResponseReceivedEndpoints instead of onResponseReceivedActions.
        val body = """
            {"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{
              "continuationItems": [
                { "playlistPanelVideoRenderer": { "videoId": "v20", "title": {"simpleText": "Twenty"}, "lengthText": {"simpleText": "3:00"} } },
                { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "TOKEN-NEXT-2" } } } }
              ]
            }}]}
        """.trimIndent()
        val page = PlaylistPageParser.parseContinuationPage(body)!!
        assertEquals(listOf("v20"), page.videos.map { it.videoId })
        assertEquals(180L, page.videos[0].durationSeconds)
        assertEquals("TOKEN-NEXT-2", page.nextToken)
    }

    @Test
    fun `parseClock handles common formats`() {
        assertEquals(754L, PlaylistPageParser.parseClock("12:34"))
        assertEquals(3723L, PlaylistPageParser.parseClock("1:02:03"))
        assertEquals(45L, PlaylistPageParser.parseClock("0:45"))
        assertEquals(0L, PlaylistPageParser.parseClock(""))
        assertEquals(0L, PlaylistPageParser.parseClock("bogus"))
        // Live entries carry no duration.
        assertEquals(0L, PlaylistPageParser.parseClock("LIVE"))
    }

    @Test
    fun `parseRelativeTimeAgo converts relative labels`() {
        val hour = 60L * 60 * 1000
        val day = 24L * hour
        val oneHourAgo = PlaylistPageParser.parseRelativeTimeAgo("1 hour ago")
        assertTrue(System.currentTimeMillis() - oneHourAgo in hour - 60_000L..hour + 60_000L)
        val threeDaysAgo = PlaylistPageParser.parseRelativeTimeAgo("3 days ago")
        assertTrue(
            System.currentTimeMillis() - threeDaysAgo in
                3 * day - 60_000L..3 * day + 60_000L
        )
        assertEquals(0L, PlaylistPageParser.parseRelativeTimeAgo(""))
        assertEquals(0L, PlaylistPageParser.parseRelativeTimeAgo("Premieres tomorrow"))
        assertEquals(0L, PlaylistPageParser.parseRelativeTimeAgo("garbage"))
    }

    // ── Continuation pagination (full-playlist fetch) ─────────────────────

    private val paginatedHtml = """
        <script>var ytInitialData = {
          "header": { "playlistHeaderRenderer": { "title": { "simpleText": "Big List" } } },
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "tabs": [ { "tabRenderer": { "content": { "sectionListRenderer": {
                "contents": [ { "itemSectionRenderer": { "contents": [
                  { "playlistVideoListRenderer": {
                    "contents": [ { "playlistVideoRenderer": { "videoId": "v1", "title": {"simpleText": "One"} } } ],
                    "continuations": [ { "nextContinuationData": { "continuation": "TOKEN-ABC" } } ]
                  } }
                ] } } ]
              } } } }
            ] }
          }
        };</script>
    """.trimIndent()

    @Test
    fun `firstContinuationToken finds the playlist pagination token`() {
        assertEquals("TOKEN-ABC", PlaylistPageParser.firstContinuationToken(paginatedHtml))
        assertEquals(null, PlaylistPageParser.firstContinuationToken("<html>no data</html>"))
    }

    @Test
    fun `continuation token prefers the video list over header continuations`() {
        // The header carries an unrelated continuationCommand; the playlist's
        // own pagination token lives in the video list and must win.
        val html = """
            <script>var ytInitialData = {
              "header": { "playlistHeaderRenderer": {
                "menu": { "menuRenderer": { "continuationItemRenderer": {
                  "continuationEndpoint": { "continuationCommand": { "token": "WRONG-HEADER" } }
                } } }
              } },
              "contents": { "twoColumnBrowseResultsRenderer": {
                "tabs": [ { "tabRenderer": { "content": { "sectionListRenderer": {
                  "contents": [ { "itemSectionRenderer": { "contents": [
                    { "playlistVideoListRenderer": {
                      "contents": [ { "playlistVideoRenderer": { "videoId": "v1", "title": {"simpleText": "One"} } } ],
                      "continuations": [ { "nextContinuationData": { "continuation": "REAL-TOKEN" } } ]
                    } }
                  ] } } ]
                } } } }
              ] } }
            };</script>
        """.trimIndent()
        assertEquals("REAL-TOKEN", PlaylistPageParser.firstContinuationToken(html))
    }

    @Test
    fun `parseContinuationPage reads videos and the next token`() {
        val body = """
            )]}'
            {"onResponseReceivedActions":[{"appendContinuationItemsAction":{
              "continuationItems": [
                { "playlistVideoRenderer": { "videoId": "v10", "title": {"simpleText": "Ten"}, "lengthText": {"simpleText": "5:00"} } },
                { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "TOKEN-NEXT" } } } }
              ]
            }}]}
        """.trimIndent()
        val page = PlaylistPageParser.parseContinuationPage(body)!!
        assertEquals(listOf("v10"), page.videos.map { it.videoId })
        assertEquals(300L, page.videos[0].durationSeconds) // 5:00
        assertEquals("TOKEN-NEXT", page.nextToken)
    }

    @Test
    fun `parseContinuationPage with no next token ends the walk`() {
        val body = """
            {"onResponseReceivedActions":[{"appendContinuationItemsAction":{
              "continuationItems": [ { "playlistVideoRenderer": { "videoId": "v11", "title": {"simpleText": "Eleven"} } } ]
            }}]}
        """.trimIndent()
        val page = PlaylistPageParser.parseContinuationPage(body)
        assertEquals(listOf("v11"), page?.videos?.map { it.videoId })
        assertEquals(null, page?.nextToken)
    }

    @Test
    fun `parseContinuationPage rejects garbage`() {
        assertNull(PlaylistPageParser.parseContinuationPage(""))
        assertNull(PlaylistPageParser.parseContinuationPage("not json"))
        assertNull(PlaylistPageParser.parseContinuationPage("""{"foo": 1}"""))
    }

    @Test
    fun `innertubeApiKey extracts the page's key`() {
        val html = """<script>window["ytcfg"] = {"INNERTUBE_API_KEY":"AIzaSyD-eJk","INNERTUBE_CONTEXT":{"client":{"hl":"en"}}};</script>"""
        assertEquals("AIzaSyD-eJk", PlaylistPageParser.innertubeApiKey(html))
        assertNull(PlaylistPageParser.innertubeApiKey("no key here"))
    }

    @Test
    fun `innertubeContext parses the balanced context object`() {
        // Nested braces + strings with braces must be balanced correctly.
        val html = """
            <script>window["ytcfg"] = {
              "INNERTUBE_API_KEY":"AIzaSyD-eJk",
              "INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.20240801.00.00","hl":"en","gl":"US","visitorData":"a{b}c"}},
              "INNERTUBE_CONTEXT_CLIENT_NAME":"3"
            };</script>
        """.trimIndent()
        val context = PlaylistPageParser.innertubeContext(html)
        assertEquals("WEB", context?.optJSONObject("client")?.optString("clientName"))
        assertEquals("2.20240801.00.00", context?.optJSONObject("client")?.optString("clientVersion"))
        // Strings inside the object must not break the brace matching.
        assertEquals("a{b}c", context?.optJSONObject("client")?.optString("visitorData"))
        assertNull(PlaylistPageParser.innertubeContext("no context here"))
    }
}
