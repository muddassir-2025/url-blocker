package com.muddassir.clearview.quran.data

import com.muddassir.clearview.quran.model.DhikrItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure helpers for the Dhikr Counter: the built-in dhikr list, JSON
 * (de)serialization and every mutation of a [DhikrItem] list. Kept free of
 * Android dependencies so the whole lifecycle is unit-testable on the JVM
 * (mirrors the MediaVideos / UserPlaylists pattern). All mutations are
 * functional: they take the current list and return the new one.
 */
object DhikrCodec {

    /**
     * The built-in dhikr with sensible defaults. Order is the selection
     * order; targets follow the classic 33 / 33 / 34 tasbih and common
     * counts for the longer phrases. Used on first launch and whenever the
     * stored list is missing/corrupt.
     */
    fun defaults(): List<DhikrItem> = listOf(
        DhikrItem(
            id = "subhanallah",
            name = "SubhanAllah",
            arabic = "سُبْحَانَ ٱللَّٰهِ",
            translation = "Glory be to Allah",
            target = 33,
            order = 0
        ),
        DhikrItem(
            id = "alhamdulillah",
            name = "Alhamdulillah",
            arabic = "ٱلْحَمْدُ لِلَّٰهِ",
            translation = "Praise be to Allah",
            target = 33,
            order = 1
        ),
        DhikrItem(
            id = "allahu-akbar",
            name = "Allahu Akbar",
            arabic = "ٱللَّٰهُ أَكْبَرُ",
            translation = "Allah is the Greatest",
            target = 34,
            order = 2
        ),
        DhikrItem(
            id = "la-ilaha-illallah",
            name = "La ilaha illallah",
            arabic = "لَا إِلَٰهَ إِلَّا ٱللَّٰهُ",
            translation = "There is no god but Allah",
            target = 100,
            order = 3
        ),
        DhikrItem(
            id = "astaghfirullah",
            name = "Astaghfirullah",
            arabic = "أَسْتَغْفِرُ ٱللَّٰهَ",
            translation = "I seek forgiveness from Allah",
            target = 100,
            order = 4
        ),
        DhikrItem(
            id = "salawat",
            name = "Salawat",
            arabic = "ٱللَّٰهُمَّ صَلِّ عَلَىٰ مُحَمَّدٍ",
            translation = "O Allah, send blessings upon Muhammad",
            target = 100,
            order = 5
        )
    )

    // ── Persistence ─────────────────────────────────────────────────

    /** JSON-encodes the list (order is preserved as stored). */
    fun encode(items: List<DhikrItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("arabic", item.arabic)
                    .put("translation", item.translation)
                    .put("target", item.target)
                    .put("visible", item.visible)
                    .put("order", item.order)
                    .put("count", item.count)
                    .put("lastCompletedCount", item.lastCompletedCount)
                    .put("lastCompletedAt", item.lastCompletedAt)
            )
        }
        return arr.toString()
    }

    /** Decodes persisted JSON; empty list on blank/corrupt input. */
    fun decode(json: String?): List<DhikrItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id", "")
                    if (id.isBlank()) {
                        null
                    } else {
                        DhikrItem(
                            id = id,
                            name = o.optString("name", ""),
                            arabic = o.optString("arabic", ""),
                            translation = o.optString("translation", ""),
                            target = o.optInt("target", 33).coerceAtLeast(0),
                            visible = o.optBoolean("visible", true),
                            order = o.optInt("order", 0),
                            count = o.optInt("count", 0).coerceAtLeast(0),
                            lastCompletedCount = o.optInt("lastCompletedCount", 0).coerceAtLeast(0),
                            lastCompletedAt = o.optLong("lastCompletedAt", 0L)
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Mutations (functional, list in → list out) ──────────────────

    /**
     * The list ordered by [DhikrItem.order] (the canonical stored order).
     * Used for DISPLAY — mutations keep their list position and renumber
     * [order] to match it (see [reindexed]), so the list is the source of
     * truth while it is being edited.
     */
    fun ordered(items: List<DhikrItem>): List<DhikrItem> =
        items.sortedBy { it.order }

    /**
     * Re-indexes [order] to match the CURRENT list positions. Deliberately
     * does NOT sort: after a manual reorder the order values no longer match
     * positions, and re-sorting here would silently undo the move.
     */
    fun reindexed(items: List<DhikrItem>): List<DhikrItem> =
        items.mapIndexed { index, item -> item.copy(order = index) }

    /** Increments the count of [id] by one. */
    fun incremented(items: List<DhikrItem>, id: String): List<DhikrItem> =
        items.map { if (it.id == id) it.copy(count = it.count + 1) else it }

    /**
     * True when [id]'s count JUST crossed its target — the count is at or
     * above the target AND was below it on the previous tap (i.e. the caller
     * passes the list AFTER the increment). Only fires on the tap that
     * reaches the target, never on continuing past it. False when there is
     * no target (0). The caller records the completed session + celebrates.
     */
    fun crossesTarget(items: List<DhikrItem>, id: String): Boolean {
        val item = items.firstOrNull { it.id == id } ?: return false
        return item.target > 0 &&
            item.count >= item.target &&
            item.count - 1 < item.target
    }

    /** Records a completed session for [id] (count reached the target). */
    fun markedCompleted(items: List<DhikrItem>, id: String, at: Long): List<DhikrItem> =
        items.map { item ->
            if (item.id == id) {
                item.copy(lastCompletedCount = item.target, lastCompletedAt = at)
            } else {
                item
            }
        }

    /** Resets the count of [id] (last-completed info is KEPT as history). */
    fun resetItem(items: List<DhikrItem>, id: String): List<DhikrItem> =
        items.map { if (it.id == id) it.copy(count = 0) else it }

    /** Sets the target count of [id]. */
    fun withTarget(items: List<DhikrItem>, id: String, target: Int): List<DhikrItem> =
        items.map { if (it.id == id) it.copy(target = target.coerceAtLeast(0)) else it }

    /** Shows/hides [id] (hidden dhikr stay in management, out of the pager). */
    fun withVisibility(items: List<DhikrItem>, id: String, visible: Boolean): List<DhikrItem> =
        items.map { if (it.id == id) it.copy(visible = visible) else it }

    /** Moves [id] by [delta] positions (-1 up / +1 down) in the ordered list. */
    fun moved(items: List<DhikrItem>, id: String, delta: Int): List<DhikrItem> {
        val ordered = reindexed(items)
        val from = ordered.indexOfFirst { it.id == id }
        if (from < 0) return items
        val to = (from + delta).coerceIn(ordered.indices)
        if (to == from) return items
        val movedList = ordered.toMutableList().apply {
            val item = removeAt(from)
            add(to, item)
        }
        return reindexed(movedList)
    }

    /** Appends a new dhikr (always visible, placed at the end of the order). */
    fun added(items: List<DhikrItem>, item: DhikrItem): List<DhikrItem> {
        val ordered = reindexed(items)
        return reindexed(ordered + item.copy(order = ordered.size, visible = true))
    }

    /** Replaces the dhikr with the same id (rename / edit keeps its state). */
    fun updated(items: List<DhikrItem>, item: DhikrItem): List<DhikrItem> =
        items.map { existing ->
            if (existing.id == item.id) {
                existing.copy(
                    name = item.name,
                    arabic = item.arabic,
                    translation = item.translation,
                    target = item.target.coerceAtLeast(0)
                )
            } else {
                existing
            }
        }

    /** Removes the dhikr with [id] entirely (its progress is gone too). */
    fun removed(items: List<DhikrItem>, id: String): List<DhikrItem> =
        reindexed(items.filterNot { it.id == id })

    /** Resets EVERY dhikr's count + completed history (items and targets stay). */
    fun resetAllProgress(items: List<DhikrItem>): List<DhikrItem> =
        items.map {
            it.copy(count = 0, lastCompletedCount = 0, lastCompletedAt = 0L)
        }
}
