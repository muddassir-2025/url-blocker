package com.muddassir.clearview.todo.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import com.muddassir.clearview.todo.data.ProgressCardStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.min

/**
 * QA gate for the progress-card layout engine: with a deterministic stand-in
 * for the platform TextMeasurer, every worst-case composition (long name,
 * first-week badge, 6 long skills, 30-day heatmap) must fit inside the canvas
 * with no overlaps — the check [verifyCardLayout] runs on every layout, so a
 * regression here fails the build instead of shipping a clipped card.
 */
class ProgressCardLayoutTest {

    private val today = LocalDate.of(2026, 8, 10)
    private val from = today.minusDays(6)

    private fun stats(
        heatmapDays: Int = 7,
        skills: List<String> = listOf("Leetcode DSA", "Read Qur'an", "Fajr Salah"),
        firstWeek: Boolean = false,
        due: Int = 12,
        completed: Int = 8
    ) = ProgressCardStats.CardStats(
        from = from,
        to = today,
        rangeKind = ProgressCardStats.RangeKind.WEEK,
        created = 12,
        completed = completed,
        missed = due - completed,
        activeDays = 6,
        currentStreak = 3,
        bestStreak = 5,
        score = 62,
        skills = skills,
        heatmap = (0 until heatmapDays).map { i ->
            ProgressCardStats.HeatDay(
                today.minusDays((heatmapDays - 1 - i).toLong()),
                when (i % 3) {
                    0 -> ProgressCardStats.Heat.DONE
                    1 -> ProgressCardStats.Heat.MISSED
                    else -> ProgressCardStats.Heat.SCHEDULED
                }
            )
        },
        firstWeek = firstWeek
    )

    private fun texts(name: String = "Abdul Rahman") = CardTexts(
        nameProgress = "$name's Progress",
        scoreLabel = "WEEKLY SCORE",
        created = "Todos Created",
        completed = "Completed",
        incomplete = "Incomplete",
        currentStreak = "Current Streak",
        bestStreak = "Best Streak",
        activeDays = "Active Days",
        daysUnit = " days",
        skillsTitle = "SKILLS & HABITS",
        skillsEmpty = "Complete todos to grow your skills & habits",
        firstWeek = "First week of tracking",
        lastDays = "THIS WEEK",
        motivational = "Building momentum 🔥",
        madeWith = "Made with ClearView",
        dateRange = "Aug 3 – Aug 10, 2026",
        percentLine = "66% COMPLETION"
    )

    /**
     * Deterministic stand-in for the real TextMeasurer. Latin glyphs measure
     * ~0.58em wide / 1.18em tall; color emoji measure much wider and taller
     * (~1.15em / 1.35em) — modeled so the QA gate matches the tightest real
     * case (emoji share the label line inside stat tiles).
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

    private fun worstCase(isStory: Boolean): List<CardBlock> = layoutProgressCard(
        stats(
            heatmapDays = 30,
            skills = List(6) { "Long habit title number $it" }.take(if (isStory) 6 else 4),
            firstWeek = true
        ),
        texts(name = "Abdul RahmanAbdul Rahman".take(24)),
        heroScore = 100,
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
    fun `hero score shrinks to at most 80 pct of the content width`() {
        val blocks = layoutProgressCard(stats(), texts(), heroScore = 100, appIconPresent = true, isStory = true, measure = ::fakeMeasure)
        val score = blocks.filterIsInstance<CardBlock.Text>().first { "/100" in it.text }
        val contentWidth = 1080f - 64f * 2f
        assertTrue("score too wide: ${score.rect.width} > ${contentWidth * 0.8f}", score.rect.width <= contentWidth * 0.8f + 1f)
        // And it never bleeds out of the safe padding either.
        assertTrue("score off-canvas", score.rect.isInside(1080f, 1920f))
    }

    @Test
    fun `empty skills message wraps within the content column and flows`() {
        val blocks = layoutProgressCard(
            stats(heatmapDays = 30, skills = emptyList(), firstWeek = true),
            texts(), heroScore = 62, appIconPresent = true, isStory = true, measure = ::fakeMeasure
        )
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue("empty-skills layout issues: $issues", issues.isEmpty())
    }

    @Test
    fun `real stat labels never split and stay inside their tiles`() {
        // The reported "Complete d" / "Incomplet e" class of bug: labels are
        // measured and width-constrained, so each label sits fully inside its
        // tile, stays on one line (it fits), and never touches the value row.
        val blocks = layoutProgressCard(stats(), texts(), 62, true, true, ::fakeMeasure)
        val tiles = blocks.filterIsInstance<CardBlock.Tile>()
        assertEquals(6, tiles.size)
        val labels = tiles.map { it.label }
        assertTrue(
            labels.containsAll(
                listOf("Completed", "Incomplete", "Todos Created", "Current Streak", "Best Streak", "Active Days")
            )
        )
        tiles.forEach { tile ->
            assertTrue(
                "label '${tile.label}' escapes its tile: ${tile.labelRect}",
                tile.labelRect.left >= tile.rect.left && tile.labelRect.right <= tile.rect.right &&
                    tile.labelRect.top >= tile.rect.top && tile.labelRect.bottom <= tile.rect.bottom
            )
            assertTrue(
                "label '${tile.label}' overlaps its value",
                tile.labelRect.bottom <= tile.valueRect.top
            )
            assertTrue(
                "label '${tile.label}' wrapped when it should fit on one line",
                tile.labelRect.height <= 22f * 1.5f
            )
        }
    }

    @Test
    fun `30-day dot strip stays inside the content column`() {
        val blocks = layoutProgressCard(stats(heatmapDays = 30), texts(), 62, true, true, ::fakeMeasure)
        val dots = blocks.filterIsInstance<CardBlock.Dot>()
        assertTrue("expected 30 dots, got ${dots.size}", dots.size == 30)
        val leftMost = dots.minOf { it.rect.left }
        val rightMost = dots.maxOf { it.rect.right }
        assertTrue("dots span $leftMost..$rightMost", leftMost >= 64f && rightMost <= 1080f - 64f)
    }

    @Test
    fun `7-day strip uses the bigger dots`() {
        val blocks = layoutProgressCard(stats(heatmapDays = 7), texts(), 62, true, true, ::fakeMeasure)
        val dots = blocks.filterIsInstance<CardBlock.Dot>()
        assertTrue(dots.size == 7)
        assertTrue(dots.all { it.rect.width > 30f })
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
