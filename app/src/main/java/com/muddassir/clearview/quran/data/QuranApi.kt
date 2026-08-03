package com.muddassir.clearview.quran.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal network client for the AlQuran.Cloud API.
 *
 * The full Sahih International translation (~6 MB) is downloaded exactly once
 * and cached locally; the app never re-fetches it during normal use, so this
 * class is only exercised by the initial download worker.
 */
object QuranApi {

    /** Full Quran, Sahih International English translation. */
    private const val QURAN_URL = "https://api.alquran.cloud/v1/quran/en.sahih"

    /** Full Quran, Uthmani Arabic script (for the Arabic verse display). */
    private const val QURAN_AR_URL = "https://api.alquran.cloud/v1/quran/quran-uthmani"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /**
     * Downloads the raw JSON body of the full English translation.
     * Returns null on any network/HTTP failure so callers can retry via WorkManager.
     */
    suspend fun download(): String? = fetch(QURAN_URL)

    /**
     * Downloads the raw JSON body of the full Arabic (Uthmani) edition.
     * Returns null on any network/HTTP failure.
     */
    suspend fun downloadArabic(): String? = fetch(QURAN_AR_URL)

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
