package com.muddassir.clearview.todo.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.min

/**
 * QA gate for the minimal premium card layout: with a deterministic stand-in
 * for the platform TextMeasurer, every worst-case composition (long name,
 * long first-week badge, wide score line) must fit inside the canvas with no
 * overlaps on BOTH export sizes — the check [verifyCardLayout] runs on every
 * layout, so a regression here fails the build instead of shipping a clipped
 * or overlapping card.
 */
class ProgressCardLayoutTest {

    private fun texts(
        name: String = "Abdul Rahman",
        scoreLine: String? = "62 / 100",
        percentLine: String? = "66% completion",
        firstWeekBadge: Boolean = false,
        statValues: List<String> = listOf("12", "7", "5", "3 days", "5")
    ) = CardTexts(
        nameProgress = "$name's Progress",
        dateRange = "Aug 4 – Aug 10, 2026",
        scoreTitle = "WEEKLY SCORE",
        scoreLine = scoreLine,
        percentLine = percentLine,
        emptyRange = "No todos due in this period",
        statLabels = listOf("Created", "Completed", "Incomplete", "Streak", "Active Days"),
        // With the badge, the streak value really carries the long badge text —
        // the QA worst case must exercise the actual production string.
        statValues = if (firstWeekBadge) {
            statValues.toMutableList().also { it[3] = "First week of tracking" }
        } else statValues,
        firstWeekBadge = firstWeekBadge,
        madeWith = "Made with ClearView"
    )

    /**
     * Deterministic stand-in for the real TextMeasurer. Latin glyphs measure
     * ~0.58em wide / 1.18em tall; color emoji measure much wider and taller
     * (~1.15em / 1.35em) — modeled so the QA gate matches the tightest real
     * case (emoji sit inline in the stat rows).
     */
    private fun fakeMeasure(text: String, style: TextStyle, maxWidth: Float): Size {
        // TextStyle() defaults are TextUnit.Unspecified, whose .value is NaN —
        // treat unspecified as 0 so widths stay finite.
        val fs = style.fontSize.value.takeUnless { it.isNaN() || it.isInfinite() } ?: 0f
        val ls = style.letterSpacing.value.takeUnless { it.isNaN() || it.isInfinite() } ?: 0f
        val wide = text.any { it.code in 0x1F000..0x1FAFF } // color emoji range
        val wFactor = if (wide) 1.15f else 0.58f
        val hFactor = if (wide) 1.35f else 1.18f
        val singleW = text.length * (fs * wFactor + ls)
        val max = maxWidth.coerceAtLeast(1f)
        val lines = ceil(singleW / max).toInt().coerceAtLeast(1)
        return Size(min(singleW, max), lines * fs * hFactor)
    }

    /** The most content-dense compositions: long name + first-week badge. */
    private fun worstCase(isStory: Boolean): List<CardBlock> = layoutProgressCard(
        texts(name = "Abdul RahmanAbdul Rahman".take(24), firstWeekBadge = true),
        appIconPresent = true,
        isStory = isStory,
        measure = ::fakeMeasure
    )

    @Test
    fun `story worst case stays inside canvas and never overlaps`() {
        val issues = verifyCardLayout(worstCase(isStory = true), 1080f, 1920f)
        assertTrue("story layout issues: $issues", issues.isEmpty())
    }

    @Test
    fun `square worst case stays inside canvas and never overlaps`() {
        val issues = verifyCardLayout(worstCase(isStory = false), 1080f, 1080f)
        assertTrue("square layout issues: $issues", issues.isEmpty())
    }

    @Test
    fun `score line never exceeds the padded content column`() {
        val blocks = layoutProgressCard(texts(), appIconPresent = true, isStory = true, measure = ::fakeMeasure)
        val score = blocks.filterIsInstance<CardBlock.Text>().first { it.text.startsWith("62 / 100") }
        val contentWidth = 1080f - 64f * 2f
        assertTrue(
            "score too wide: ${score.rect.width} > $contentWidth",
            score.rect.width <= contentWidth + 1f
        )
        assertTrue("score off-canvas", score.rect.isInside(1080f, 1920f))
    }

    @Test
    fun `empty range shows the empty message instead of a score`() {
        val blocks = layoutProgressCard(
            texts(scoreLine = null, percentLine = null),
            appIconPresent = true, isStory = true, measure = ::fakeMeasure
        )
        val empty = blocks.filterIsInstance<CardBlock.Text>().first { it.text == "No todos due in this period" }
        assertTrue(empty.rect.isInside(1080f, 1920f))
    }

    @Test
    fun `stat labels and values render on single lines without overlap`() {
        val blocks = layoutProgressCard(texts(), appIconPresent = true, isStory = true, measure = ::fakeMeasure)
        val textBlocks = blocks.filterIsInstance<CardBlock.Text>()
        val labels = listOf("Created", "Completed", "Incomplete", "Streak", "Active Days")
        val values = listOf("12", "7", "5", "3 days", "5")
        labels.forEach { label ->
            val t = textBlocks.first { it.text == label }
            assertTrue("'$label' wrapped to two lines", t.rect.height <= 22f * 1.5f)
            assertTrue("'$label' escapes the canvas", t.rect.isInside(1080f, 1920f))
        }
        values.forEach { value ->
            assertTrue("missing stat value '$value'", textBlocks.any { it.text == value })
        }
        // The QA gate covers label↔value overlaps inside each row.
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue("stat rows overlap: $issues", issues.isEmpty())
    }

    @Test
    fun `first week badge fits the streak row`() {
        val blocks = layoutProgressCard(
            texts(firstWeekBadge = true),
            appIconPresent = true, isStory = true, measure = ::fakeMeasure
        )
        val badge = blocks.filterIsInstance<CardBlock.Text>().first { it.text == "First week of tracking" }
        assertTrue(badge.rect.isInside(1080f, 1920f))
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue("badge layout issues: $issues", issues.isEmpty())
    }

    @Test
    fun `footer is pinned near the canvas bottom on both sizes`() {
        val story = layoutProgressCard(texts(), appIconPresent = true, isStory = true, measure = ::fakeMeasure)
        val storyFooter = story.filterIsInstance<CardBlock.Text>().first { it.text == "Made with ClearView" }
        // The minimal card leaves most of the tall story canvas empty; the
        // watermark must anchor near the bottom, not float mid-card.
        assertTrue(
            "story footer floats at ${storyFooter.rect.top}",
            storyFooter.rect.top > 1920f * 0.75f
        )
        assertTrue(storyFooter.rect.isInside(1080f, 1920f))

        val square = layoutProgressCard(texts(), appIconPresent = true, isStory = false, measure = ::fakeMeasure)
        val squareFooter = square.filterIsInstance<CardBlock.Text>().first { it.text == "Made with ClearView" }
        assertTrue(squareFooter.rect.isInside(1080f, 1080f))
    }

    @Test
    fun `verify flags blocks that exceed the canvas`() {
        val blocks = listOf(
            CardBlock.Text(CardRect(100f, 100f, 200f, 140f), "ok", TextStyle()),
            CardBlock.Text(CardRect(1050f, 1900f, 1200f, 1950f), "overflow", TextStyle())
        )
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue("expected out-of-bounds flagged, got $issues", issues.any { it.startsWith("out-of-bounds") })
    }

    @Test
    fun `verify flags overlapping blocks`() {
        val blocks = listOf(
            CardBlock.Text(CardRect(100f, 100f, 300f, 140f), "a", TextStyle()),
            CardBlock.Text(CardRect(250f, 100f, 400f, 140f), "b", TextStyle())
        )
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue("expected overlap flagged, got $issues", issues.any { it.startsWith("overlap") })
    }
}
