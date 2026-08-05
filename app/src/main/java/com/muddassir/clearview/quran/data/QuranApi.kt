package com.muddassir.clearview.quran.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal network client for the Quran editions.
 *
 * Sources (both from the fawazahmed0/quran-api mirror, served via the jsDelivr
 * CDN — no API key required, stable public URLs):
 *  - English: "The Clear Quran" translation by Dr. Mustafa Khattab.
 *  - Arabic:  the full Quran in IndoPak script.
 *
 * Each full edition (~1.5–2 MB) is downloaded exactly once and cached locally;
 * the app never re-fetches it during normal use, so this class is only
 * exercised by the initial download worker.
 */
object QuranApi {

    /** Full Quran, "The Clear Quran" English translation (Mustafa Khattab). */
    private const val QURAN_URL =
        "https://cdn.jsdelivr.net/gh/fawazahmed0/quran-api@1/editions/eng-mustafakhattaba.json"

    /** Full Quran, IndoPak Arabic script (for the Arabic verse display). */
    private const val QURAN_AR_URL =
        "https://cdn.jsdelivr.net/gh/fawazahmed0/quran-api@1/editions/ara-quranindopak.json"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /**
     * Downloads the raw JSON body of the full English translation.
     * Returns null on any network/HTTP failure so callers can retry via WorkManager.
     */
    suspend fun download(): String? = fetch(QURAN_URL)

    /**
     * Downloads the raw JSON body of the full Arabic (IndoPak) edition.
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
