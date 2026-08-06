package com.muddassir.clearview.media.model

/**
 * Source filter for the Downloads (offline audio) view — where each audio
 * came from: [BY_URL] downloaded from a pasted YouTube URL, and [DEVICE]
 * imported from the device's storage.
 */
enum class DownloadSourceFilter(val label: String) {
    ALL("All"),
    BY_URL("By URL"),
    DEVICE("From device")
}
