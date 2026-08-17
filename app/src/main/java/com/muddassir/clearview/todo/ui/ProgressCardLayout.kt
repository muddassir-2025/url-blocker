package com.muddassir.clearview.todo.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Card palette (matches the app's dark + teal identity) ──────────────
internal val CardTeal = Color(0xFF2DD4BF)
internal val CardTextDim = Color(0xFF9AA3AF)
internal val CardBgTop = Color(0xFF10161B)
internal val CardBgBottom = Color(0xFF06090D)
internal val CardDivider = Color.White.copy(alpha = 0.08f)

/**
 * Resolved, localizable strings the card renders (built by the UI layer —
 * the layout engine stays purely visual).
 */
internal data class CardTexts(
    val nameProgress: String,
    val dateRange: String,
    /** e.g. "WEEKLY SCORE" / "MONTHLY SCORE". */
    val scoreTitle: String,
    /** e.g. "62 / 100"; null → [emptyRange] is shown instead of the score. */
    val scoreLine: String?,
    /** e.g. "66% completion"; shown under the score when the range has data. */
    val percentLine: String?,
    val emptyRange: String,
    /** The five stat labels: Created, Completed, Incomplete, Streak, Active Days. */
    val statLabels: List<String>,
    /** The five stat values (the streak slot holds the badge when first-week). */
    val statValues: List<String>,
    /** True when the streak value is the "First week of tracking" badge. */
    val firstWeekBadge: Boolean,
    val madeWith: String
)

/** An absolutely-positioned region of the card, produced by [layoutProgressCard]. */
internal data class CardRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun isInside(canvasW: Float, canvasH: Float): Boolean =
        left >= 0f && top >= 0f && right <= canvasW && bottom <= canvasH
}

/**
 * One laid-out visual element. Every element carries its final [rect], so the
 * drawing pass is a pure map over blocks and the QA gate can check bounds and
 * overlap without rendering a single pixel.
 */
internal sealed class CardBlock {
    abstract val rect: CardRect

    data class Text(
        override val rect: CardRect,
        val text: String,
        val style: TextStyle
    ) : CardBlock()

    /** A thin horizontal divider. */
    data class Line(override val rect: CardRect) : CardBlock()

    data class Icon(override val rect: CardRect, val useAppIcon: Boolean) : CardBlock()
}

/**
 * Text measurement, injected so the layout engine stays pure JVM and unit
 * testable. [maxWidth] constrains wrapping — the returned Size must be the
 * size the text actually occupies when wrapped to maxWidth.
 */
internal fun interface CardMeasurer {
    fun measure(text: String, style: TextStyle, maxWidth: Float): Size
}

// ── Geometry & config for the two export sizes ─────────────────────────

/**
 * One size's full layout spec. Every value is a raw pixel on the export
 * canvas (drawn at Density(1f) so 1sp == 1px). Horizontal padding is fixed:
 * nothing below is ever allowed to exceed [left]..[right].
 */
private data class CardConfig(
    val left: Float,
    val right: Float,
    val top: Float,
    val gap: Float,
    val iconSize: Float,
    val nameFont: Float,
    val nameXGap: Float,
    val dateFont: Float,
    val gapAfterDate: Float,
    val scoreTitleFont: Float,
    val scoreFont: Float,
    val scoreMin: Float,
    val scoreGap: Float,
    val scorePercentFont: Float,
    val scorePercentGap: Float,
    val gapAfterScore: Float,
    val dividerGap: Float,
    val statIconFont: Float,
    val statFont: Float,
    val statValueFont: Float,
    val statBadgeFont: Float,
    val statGap: Float,
    val statRowGap: Float,
    val footerFont: Float,
    val footerIcon: Float
) {
    val width: Float get() = right - left
    val center: Float get() = (left + right) / 2f
}

/**
 * The card is intentionally MINIMAL — a single small premium block of content
 * (name, date range, score, completion %, five stats) with the watermark
 * footer pinned to the bottom of the canvas.
 */
private val STORY = CardConfig(
    left = 64f, right = 1080f - 64f, top = 56f, gap = 26f,
    iconSize = 64f, nameFont = 34f, nameXGap = 20f,
    dateFont = 24f, gapAfterDate = 36f,
    scoreTitleFont = 19f, scoreFont = 52f, scoreMin = 28f,
    scoreGap = 10f, scorePercentFont = 23f, scorePercentGap = 6f,
    gapAfterScore = 30f,
    dividerGap = 22f,
    statIconFont = 26f, statFont = 22f, statValueFont = 26f, statBadgeFont = 18f,
    statGap = 12f, statRowGap = 13f,
    footerFont = 24f, footerIcon = 32f
)

private val SQUARE = CardConfig(
    left = 44f, right = 1080f - 44f, top = 44f, gap = 20f,
    iconSize = 48f, nameFont = 26f, nameXGap = 16f,
    dateFont = 19f, gapAfterDate = 26f,
    scoreTitleFont = 15f, scoreFont = 40f, scoreMin = 22f,
    scoreGap = 8f, scorePercentFont = 18f, scorePercentGap = 5f,
    gapAfterScore = 22f,
    dividerGap = 16f,
    statIconFont = 21f, statFont = 17f, statValueFont = 21f, statBadgeFont = 14f,
    statGap = 10f, statRowGap = 10f,
    footerFont = 19f, footerIcon = 26f
)

/** A simple vertical flex column: y advances by measured content + gaps. */
private class Flow(
    private val left: Float,
    private val right: Float,
    private val gapSize: Float,
    private val blocks: MutableList<CardBlock>,
    private val measure: CardMeasurer
) {
    var y: Float = 0f
    val width: Float get() = right - left

    fun gap(g: Float = gapSize) {
        y += g
    }

    /**
     * Measures and places a [text] block, then advances the flow cursor below
     * it (so the next section always starts under this one's real bottom).
     */
    fun text(
        text: String,
        style: TextStyle,
        maxWidth: Float = width,
        x: Float = left,
        y: Float = this.y,
        centeredX: Float? = null
    ): CardRect {
        val size = measure.measure(text, style, maxWidth)
        val tx = centeredX?.let { it - size.width / 2f } ?: x
        val rect = CardRect(tx, y, tx + size.width, y + size.height)
        blocks += CardBlock.Text(rect, text, style)
        this.y = maxOf(this.y, y + size.height)
        return rect
    }
}

/**
 * Lays the minimal card out as a single vertical flow (flex-column semantics):
 * every section's position follows from the section above it, all text is
 * measured and width-constrained to the padded content column, and nothing is
 * placed with hardcoded pixel offsets. Returns the complete block list — the
 * drawing pass and the QA bounds/overlap check both consume exactly this.
 */
internal fun layoutProgressCard(
    texts: CardTexts,
    appIconPresent: Boolean,
    isStory: Boolean,
    measure: CardMeasurer
): List<CardBlock> {
    val cfg = if (isStory) STORY else SQUARE
    val blocks = ArrayList<CardBlock>()
    val flow = Flow(cfg.left, cfg.right, cfg.gap, blocks, measure)
    flow.y = cfg.top

    // ── 1. Header: app icon + "[Name]'s Progress" ──
    val iconY = flow.y
    blocks += CardBlock.Icon(
        CardRect(cfg.left, iconY, cfg.left + cfg.iconSize, iconY + cfg.iconSize),
        appIconPresent
    )
    val nameStyle = TextStyle(fontSize = cfg.nameFont.sp, fontWeight = FontWeight.Bold, color = Color.White)
    val nameMax = cfg.width - cfg.iconSize - cfg.nameXGap
    val nameSize = measure.measure(texts.nameProgress, nameStyle, nameMax)
    flow.text(
        texts.nameProgress, nameStyle, nameMax,
        x = cfg.left + cfg.iconSize + cfg.nameXGap,
        y = iconY + (cfg.iconSize - nameSize.height) / 2f
    )
    flow.y = iconY + cfg.iconSize
    flow.gap()

    // ── 2. Date range ──
    flow.text(
        texts.dateRange,
        TextStyle(fontSize = cfg.dateFont.sp, fontWeight = FontWeight.SemiBold, color = CardTeal)
    )
    flow.gap(cfg.gapAfterDate)

    // ── 3. Score + completion % (centered, compact) ──
    flow.text(
        texts.scoreTitle,
        TextStyle(
            fontSize = cfg.scoreTitleFont.sp,
            fontWeight = FontWeight.SemiBold,
            color = CardTextDim,
            letterSpacing = (cfg.scoreTitleFont * 0.28f).sp
        ),
        centeredX = cfg.center
    )
    flow.gap(cfg.scoreGap)
    if (texts.scoreLine != null) {
        // Auto-shrink so the score never exceeds the padded column.
        val maxScoreW = cfg.width * 0.96f
        var font = cfg.scoreFont
        var style = TextStyle(fontSize = font.sp, fontWeight = FontWeight.Bold, color = Color.White)
        while (measure.measure(texts.scoreLine, style, maxScoreW).width > maxScoreW && font > cfg.scoreMin) {
            font -= 2f
            style = TextStyle(fontSize = font.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        flow.text(texts.scoreLine, style, maxWidth = maxScoreW, centeredX = cfg.center)
        texts.percentLine?.let { percent ->
            flow.gap(cfg.scorePercentGap)
            flow.text(
                percent,
                TextStyle(
                    fontSize = cfg.scorePercentFont.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CardTeal
                ),
                centeredX = cfg.center
            )
        }
    } else {
        flow.text(
            texts.emptyRange,
            TextStyle(fontSize = cfg.scoreTitleFont.sp, color = CardTextDim),
            centeredX = cfg.center
        )
    }
    flow.gap(cfg.gapAfterScore)

    // ── 4. Hairline divider ──
    val lineY = flow.y
    blocks += CardBlock.Line(CardRect(cfg.left, lineY, cfg.right, lineY + 2f))
    flow.y = lineY + 2f
    flow.gap(cfg.dividerGap)

    // ── 5. The five stats: emoji + label left, value right ──
    listOf(
        "🎯" to 0,
        "✅" to 1,
        "⏳" to 2,
        "🔥" to 3,
        "🟢" to 4
    ).forEach { (emoji, index) ->
        statRow(flow, blocks, cfg, emoji, texts.statLabels[index], texts.statValues[index],
            badge = texts.firstWeekBadge && index == 3, measure)
    }

    // ── 6. Watermark footer (icon + "Made with …"), pinned near the bottom ──
    val footerStyle = TextStyle(fontSize = cfg.footerFont.sp, color = CardTextDim)
    val madeSize = measure.measure(texts.madeWith, footerStyle, cfg.width)
    val unitW = madeSize.width + cfg.statGap + cfg.footerIcon
    val startX = cfg.center - unitW / 2f
    // Pinned near the canvas bottom so the minimal card never leaves the
    // watermark floating mid-card — but never above where content already ends
    // (an overflowing layout stays flagged by the QA gate).
    val canvasH = if (isStory) 1920f else 1080f
    val footerY = maxOf(flow.y, canvasH - cfg.footerIcon - cfg.top)
    blocks += CardBlock.Icon(
        CardRect(startX, footerY, startX + cfg.footerIcon, footerY + cfg.footerIcon),
        appIconPresent
    )
    flow.text(
        texts.madeWith, footerStyle, cfg.width,
        x = startX + cfg.footerIcon + cfg.statGap,
        y = footerY + (cfg.footerIcon - madeSize.height) / 2f
    )
    flow.y = footerY + cfg.footerIcon

    return blocks
}

/** One stat row: emoji + label on the left, value right-aligned on the same line. */
private fun statRow(
    flow: Flow,
    blocks: MutableList<CardBlock>,
    cfg: CardConfig,
    emoji: String,
    label: String,
    value: String,
    badge: Boolean,
    measure: CardMeasurer
) {
    val emojiStyle = TextStyle(fontSize = cfg.statIconFont.sp)
    val emojiSize = measure.measure(emoji, emojiStyle, cfg.width)
    val valueStyle = TextStyle(
        fontSize = (if (badge) cfg.statBadgeFont else cfg.statValueFont).sp,
        fontWeight = FontWeight.Bold,
        color = if (badge) CardTextDim else Color.White
    )
    val valueSize = measure.measure(value, valueStyle, cfg.width)
    val labelStyle = TextStyle(fontSize = cfg.statFont.sp, color = CardTextDim)
    val labelMax = (cfg.width - emojiSize.width - cfg.statGap - valueSize.width - 20f).coerceAtLeast(1f)
    val labelSize = measure.measure(label, labelStyle, labelMax)
    val rowH = maxOf(emojiSize.height, valueSize.height, labelSize.height)
    val y = flow.y
    blocks += CardBlock.Text(
        CardRect(cfg.left, y, cfg.left + emojiSize.width, y + emojiSize.height),
        emoji, emojiStyle
    )
    blocks += CardBlock.Text(
        CardRect(
            cfg.left + emojiSize.width + cfg.statGap, y,
            cfg.left + emojiSize.width + cfg.statGap + labelSize.width, y + labelSize.height
        ),
        label, labelStyle
    )
    blocks += CardBlock.Text(
        CardRect(cfg.right - valueSize.width, y, cfg.right, y + valueSize.height),
        value, valueStyle
    )
    flow.y = y + rowH
    flow.gap(cfg.statRowGap)
}

/**
 * QA gate: returns a description for every block that sits outside the canvas
 * or overlaps another block. The unit tests assert this is empty for the
 * worst-case layouts, and the UI logs any hits at render time — so an
 * off-by-pixel regression is caught by the build instead of shipping.
 */
internal fun verifyCardLayout(blocks: List<CardBlock>, canvasW: Float, canvasH: Float): List<String> {
    val issues = ArrayList<String>()
    blocks.forEach { b ->
        if (!b.rect.isInside(canvasW, canvasH)) {
            issues += "out-of-bounds ${b.javaClass.simpleName} ${b.rect}"
        }
    }
    val eps = 1f
    for (i in blocks.indices) {
        for (j in i + 1 until blocks.size) {
            val a = blocks[i].rect
            val b = blocks[j].rect
            if (a.left < b.right - eps && b.left < a.right - eps &&
                a.top < b.bottom - eps && b.top < a.bottom - eps
            ) {
                issues += "overlap ${blocks[i].javaClass.simpleName} $a × ${blocks[j].javaClass.simpleName} $b"
            }
        }
    }
    return issues
}
