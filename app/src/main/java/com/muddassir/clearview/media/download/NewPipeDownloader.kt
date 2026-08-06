package com.muddassir.clearview.media.download

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Bridges NewPipeExtractor's network calls onto the app's existing
 * [HttpURLConnection] stack — no OkHttp dependency needed.
 *
 * NewPipeExtractor only defines the abstract [execute] method; everything else
 * (GET/POST/HEAD helpers) funnels through it. Request headers from the
 * extractor (e.g. the innertube client's User-Agent / Content-Type) are applied
 * verbatim, and gzip bodies (sent when the extractor asks for them) are
 * transparently decompressed. Responses carry the raw body so the extractor can
 * interpret YouTube's error payloads itself.
 */
internal class NewPipeDownloader : Downloader() {

    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.httpMethod()
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true

            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                setHeader(conn, name, values)
            }
            // The innertube clients set their own UA; fall back to a neutral
            // mobile one for any request that didn't specify one.
            if (!hasUserAgent) {
                conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            }

            request.dataToSend()?.let { body ->
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }

            val code = conn.responseCode
            val body = readBody(conn, code)
            return Response(
                code,
                conn.responseMessage ?: "",
                conn.headerFields,
                body,
                conn.url.toString()
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun setHeader(conn: HttpURLConnection, name: String, values: List<String>) {
        if (values.isEmpty()) return
        // Multiple values joined like OkHttp would send them.
        conn.setRequestProperty(name, values.joinToString(", "))
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val raw = if (code in 200..299) conn.inputStream else conn.errorStream
            ?: return ""
        return try {
            val decoded = if ("gzip".equals(conn.getHeaderField("Content-Encoding"), true)) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            decoded.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            ""
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 25_000
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
