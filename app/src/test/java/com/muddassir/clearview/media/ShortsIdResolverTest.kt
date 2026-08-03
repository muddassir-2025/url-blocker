package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.ShortsIdResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsIdResolverTest {

    @Test
    fun extractsAllVideoIdsFromShortsTab() {
        val html = """
            <html><body><script>
            var ytInitialData = {
              "richGridRenderer": {"contents": [
                {"richItemRenderer": {"content": {
                  "shortsLockupViewModel": {"videoId": "aaaaaa11111"}}}},
                {"richItemRenderer": {"content": {
                  "shortsLockupViewModel": {"videoId": "bbbbbb22222"}}}},
                {"richItemRenderer": {"content": {
                  "shortsLockupViewModel": {"videoId": "cccccc33333"}}}}
              ]}
            };
            </script></body></html>
        """.trimIndent()
        assertEquals(
            setOf("aaaaaa11111", "bbbbbb22222", "cccccc33333"),
            ShortsIdResolver.extractShortsIds(html)
        )
    }

    @Test
    fun returnsEmptySetWhenNoVideoIds() {
        assertTrue(ShortsIdResolver.extractShortsIds("<html>no shorts here</html>").isEmpty())
        assertTrue(ShortsIdResolver.extractShortsIds("").isEmpty())
    }
}
