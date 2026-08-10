package com.muddassir.clearview.quran.data

import com.muddassir.clearview.quran.model.DhikrItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DhikrCodecTest {

    private fun item(id: String, target: Int = 33) = DhikrItem(
        id = id,
        name = "Dhikr $id",
        arabic = "عربي",
        translation = "Translation $id",
        target = target,
        visible = true,
        order = 0,
        count = 0,
        lastCompletedCount = 0,
        lastCompletedAt = 0L
    )

    @Test
    fun `defaults ship six built-in dhikr in order with sensible targets`() {
        val defaults = DhikrCodec.defaults()
        assertEquals(6, defaults.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), defaults.map { it.order })
        assertTrue(defaults.all { it.visible })
        assertTrue(defaults.all { it.count == 0 })
        // Classic 33/33/34 tasbih then longer phrases.
        assertEquals(33, defaults[0].target)
        assertEquals(33, defaults[1].target)
        assertEquals(34, defaults[2].target)
        assertEquals(100, defaults[3].target)
    }

    @Test
    fun `encode decode round-trips every field`() {
        val list = listOf(
            item("a", 99).copy(count = 37, lastCompletedCount = 99, lastCompletedAt = 1234L, visible = false, order = 1),
            item("b", 0).copy(count = 1000)
        )
        assertEquals(list, DhikrCodec.decode(DhikrCodec.encode(list)))
    }

    @Test
    fun `decode handles blank and corrupt input`() {
        assertTrue(DhikrCodec.decode(null).isEmpty())
        assertTrue(DhikrCodec.decode("").isEmpty())
        assertTrue(DhikrCodec.decode("not json").isEmpty())
        assertTrue(DhikrCodec.decode("[{\"name\":\"no id\"}]").isEmpty())
    }

    @Test
    fun `legacy JSON without new fields decodes to safe defaults`() {
        val json = """[{"id":"x","name":"X","target":100,"count":5}]"""
        val decoded = DhikrCodec.decode(json).single()
        assertEquals("x", decoded.id)
        assertEquals(100, decoded.target)
        assertEquals(5, decoded.count)
        assertTrue(decoded.visible)
        assertEquals(0, decoded.lastCompletedCount)
    }

    @Test
    fun `increment raises only the matching count`() {
        val list = listOf(item("a"), item("b"))
        val updated = DhikrCodec.incremented(list, "a")
        assertEquals(1, updated[0].count)
        assertEquals(0, updated[1].count)
    }

    @Test
    fun `crossesTarget only on the tap that reaches the target`() {
        val at32 = listOf(item("a").copy(count = 32))
        assertTrue(DhikrCodec.crossesTarget(DhikrCodec.incremented(at32, "a"), "a"))
        val at33 = listOf(item("a").copy(count = 33))
        assertFalse(DhikrCodec.crossesTarget(DhikrCodec.incremented(at33, "a"), "a"))
        // Continuing past the target never re-crosses.
        val at50 = listOf(item("a").copy(count = 50))
        assertFalse(DhikrCodec.crossesTarget(DhikrCodec.incremented(at50, "a"), "a"))
        // No target (0) → never crosses.
        val noTarget = listOf(item("a", target = 0).copy(count = 100))
        assertFalse(DhikrCodec.crossesTarget(DhikrCodec.incremented(noTarget, "a"), "a"))
    }

    @Test
    fun `markedCompleted records the target as the completed session`() {
        val list = listOf(item("a").copy(count = 33, target = 33))
        val updated = DhikrCodec.markedCompleted(list, "a", 9_999L)
        assertEquals(33, updated[0].lastCompletedCount)
        assertEquals(9_999L, updated[0].lastCompletedAt)
        assertEquals(33, updated[0].count)
    }

    @Test
    fun `reset clears the count but keeps the completed history`() {
        val list = listOf(item("a").copy(count = 33, lastCompletedCount = 33, lastCompletedAt = 5L))
        val updated = DhikrCodec.resetItem(list, "a")
        assertEquals(0, updated[0].count)
        assertEquals(33, updated[0].lastCompletedCount)
        assertEquals(5L, updated[0].lastCompletedAt)
    }

    @Test
    fun `withTarget sets and clamps the target`() {
        val list = listOf(item("a", target = 33))
        assertEquals(100, DhikrCodec.withTarget(list, "a", 100)[0].target)
        assertEquals(0, DhikrCodec.withTarget(list, "a", -5)[0].target)
    }

    @Test
    fun `visibility toggle leaves the item in the list`() {
        val list = listOf(item("a"))
        val hidden = DhikrCodec.withVisibility(list, "a", false)
        assertFalse(hidden[0].visible)
        assertTrue(DhikrCodec.withVisibility(hidden, "a", true)[0].visible)
    }

    @Test
    fun `move reorders and reindexes`() {
        val list = listOf(item("a"), item("b"), item("c"))
        val moved = DhikrCodec.moved(list, "a", +1) // a,b,c → b,a,c
        assertEquals(listOf("b", "a", "c"), moved.map { it.id })
        assertEquals(listOf(0, 1, 2), moved.map { it.order })
        // Moving the last item down is a no-op.
        assertEquals(moved, DhikrCodec.moved(moved, "c", +1))
    }

    @Test
    fun `add appends as visible and reindexes`() {
        val list = listOf(item("a"), item("b"))
        val updated = DhikrCodec.added(list, item("z", target = 1000))
        assertEquals(3, updated.size)
        assertEquals("z", updated.last().id)
        assertEquals(2, updated.last().order)
        assertTrue(updated.last().visible)
    }

    @Test
    fun `update edits metadata but keeps id order and progress`() {
        val list = listOf(item("a").copy(count = 7, order = 1), item("b"))
        val updated = DhikrCodec.updated(
            list,
            item("a").copy(name = "Renamed", arabic = "", translation = "", target = 999)
        )
        val a = updated.first { it.id == "a" }
        assertEquals("Renamed", a.name)
        assertEquals(999, a.target)
        assertEquals(7, a.count)
        assertEquals(1, a.order)
        assertEquals("", a.arabic)
        assertEquals("Translation b", updated.first { it.id == "b" }.translation)
    }

    @Test
    fun `remove drops the item and reindexes the rest`() {
        val list = listOf(item("a"), item("b"), item("c"))
        val updated = DhikrCodec.removed(list, "b")
        assertEquals(listOf("a", "c"), updated.map { it.id })
        assertEquals(listOf(0, 1), updated.map { it.order })
    }

    @Test
    fun `resetAllProgress clears counts and history but keeps items and targets`() {
        val list = listOf(
            item("a").copy(count = 40, lastCompletedCount = 33, lastCompletedAt = 9L, target = 33),
            item("b", target = 0).copy(count = 12)
        )
        val updated = DhikrCodec.resetAllProgress(list)
        assertTrue(updated.all { it.count == 0 && it.lastCompletedCount == 0 && it.lastCompletedAt == 0L })
        assertEquals(listOf(33, 0), updated.map { it.target })
        assertEquals(listOf("a", "b"), updated.map { it.id })
    }

    @Test
    fun `ordered sorts by order field`() {
        val list = listOf(item("a").copy(order = 2), item("b").copy(order = 0), item("c").copy(order = 1))
        assertEquals(listOf("b", "c", "a"), DhikrCodec.ordered(list).map { it.id })
    }
}
