package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.model.DownloadSourceFilter

/**
 * True when [item] matches the Downloads view's source filter:
 * [DownloadSourceFilter.BY_URL] (downloaded from a pasted YouTube URL) or
 * [DownloadSourceFilter.DEVICE] (imported from the device — \"added by
 * system\"). Everything else (e.g. channel-feed downloads) only matches
 * [DownloadSourceFilter.ALL].
 */
fun matchesDownloadSource(
    item: DownloadItem,
    filter: DownloadSourceFilter
): Boolean = when (filter) {
    DownloadSourceFilter.ALL -> true
    DownloadSourceFilter.BY_URL -> item.source == DownloadItem.SOURCE_URL
    DownloadSourceFilter.DEVICE -> item.source == DownloadItem.SOURCE_DEVICE
}
