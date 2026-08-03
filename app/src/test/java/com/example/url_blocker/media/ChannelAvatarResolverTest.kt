package com.example.url_blocker.media

import com.example.url_blocker.media.data.ChannelAvatarResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelAvatarResolverTest {

    @Test
    fun extractsAvatarFromYtInitialData() {
        // ytInitialData embeds the avatar with JSON-escaped slashes.
        val html = """
            <html><body><script>
            var ytInitialData = {"header":{"c4TabbedHeaderRenderer":{
              "avatar":{"thumbnails":[{"url":"https:\/\/yt3.ggpht.com\/ytc\/AKedOLR_avatar=s88-c-k-c0x00ffffff-no-rj","width":88,"height":88}]}
            }}};
            </script></body></html>
        """.trimIndent()
        assertEquals(
            "https://yt3.ggpht.com/ytc/AKedOLR_avatar=s88-c-k-c0x00ffffff-no-rj",
            ChannelAvatarResolver.extractAvatarUrl(html)
        )
    }

    @Test
    fun unescapesAmpersandsInQueryString() {
        val html = """
            {"avatar":{"thumbnails":[{"url":"https:\/\/yt3.ggpht.com\/abc?s=88\u0026sigh=y","width":88}]}}
        """.trimIndent()
        assertEquals(
            "https://yt3.ggpht.com/abc?s=88&sigh=y",
            ChannelAvatarResolver.extractAvatarUrl(html)
        )
    }

    @Test
    fun fallsBackToChannelThumbnailRenderer() {
        val html = """
            {"channelThumbnailSupportedRenderers":{"channelThumbnailWithAvatarFallbackRenderer":{
              "thumbnail":{"thumbnails":[{"url":"https:\/\/yt3.ggpht.com\/fallback=s176-c","width":176,"height":176}]}}}}
        """.trimIndent()
        assertEquals(
            "https://yt3.ggpht.com/fallback=s176-c",
            ChannelAvatarResolver.extractAvatarUrl(html)
        )
    }

    @Test
    fun returnsNullWhenNoAvatarPresent() {
        assertNull(ChannelAvatarResolver.extractAvatarUrl("<html>no avatar here</html>"))
        assertNull(ChannelAvatarResolver.extractAvatarUrl(""))
    }
}
