package com.muddassir.clearview.todo.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.todo.data.ProgressCardStats

// ── Card palette (matches the app's dark + teal identity) ──────────────
internal val CardTeal = Color(0xFF2DD4BF)
internal val CardDone = Color(0xFF43A047)
internal val CardMissed = Color(0xFFE53935)
internal val CardGrayDot = Color(0xFF333A42)
internal val CardTextDim = Color(0xFF9AA3AF)
internal val CardBgTop = Color(0xFF10161B)
internal val CardBgBottom = Color(0xFF06090D)
internal val CardTileBg = Color.White.copy(alpha = 0.05f)
internal val CardTileBorder = Color.White.copy(alpha = 0.07f)

/** Resolved, localizable strings the card renders (built by the UI layer). */
internal data class CardTexts(
    val nameProgress: String,
    val scoreLabel: String,
    val created: String,
    val completed: String,
    val incomplete: String,
    val currentStreak: String,
    val bestStreak: String,
    val activeDays: String,
    val daysUnit: String,
    val skillsTitle: String,
    val skillsEmpty: String,
    val firstWeek: String,
    val lastDays: String,
    val motivational: String,
    val madeWith: String,
    val dateRange: String,
    val percentLine: String
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

    data class Tile(
        override val rect: CardRect,
        val emoji: String,
        val emojiRect: CardRect,
        val emojiStyle: TextStyle,
        val label: String,
        val labelRect: CardRect,
        val labelStyle: TextStyle,
        val value: String,
        val valueRect: CardRect,
        val valueStyle: TextStyle
    ) : CardBlock()

    data class Pill(
        override val rect: CardRect,
        val textRect: CardRect,
        val text: String,
        val style: TextStyle
    ) : CardBlock()

    data class Dot(override val rect: CardRect, val heat: ProgressCardStats.Heat) : CardBlock()

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
    val heroLabelFont: Float,
    val heroScoreFont: Float,
    val heroScoreMin: Float,
    val heroPercentFont: Float,
    val heroInnerGap: Float,
    val gapAfterHero: Float,
    val tilePadX: Float,
    val tilePadY: Float,
    val tileVGap: Float,
    val tileGap: Float,
    val tileRowGap: Float,
    val tileLabelFont: Float,
    val tileValueFont: Float,
    val tileBadgeFont: Float,
    val tileEmojiFont: Float,
    val gapAfterTiles: Float,
    val skillsTitleFont: Float,
    val gapAfterSkillsTitle: Float,
    val pillH: Float,
    val pillGap: Float,
    val pillPadX: Float,
    val pillFont: Float,
    val pillCount: Int,
    val pillCharCap: Int,
    val gapAfterPills: Float,
    val heatTitleFont: Float,
    val heatDot7: Float,
    val heatGap7: Float,
    val heatDot30: Float,
    val heatGap30: Float,
    val gapAfterHeat: Float,
    val motivFont: Float,
    val gapAfterMotiv: Float,
    val footerFont: Float,
    val footerIcon: Float
) {
    val width: Float get() = right - left
    val center: Float get() = (left + right) / 2f
}

private val STORY = CardConfig(
    left = 64f, right = 1080f - 64f, top = 72f, gap = 30f,
    iconSize = 92f, nameFont = 46f, nameXGap = 28f,
    dateFont = 34f, gapAfterDate = 44f,
    heroLabelFont = 26f, heroScoreFont = 186f, heroScoreMin = 96f, heroPercentFont = 32f,
    heroInnerGap = 12f, gapAfterHero = 36f,
    tilePadX = 26f, tilePadY = 18f, tileVGap = 10f, tileGap = 16f, tileRowGap = 14f,
    tileLabelFont = 22f, tileValueFont = 44f, tileBadgeFont = 26f, tileEmojiFont = 40f,
    gapAfterTiles = 40f,
    skillsTitleFont = 26f, gapAfterSkillsTitle = 16f,
    pillH = 56f, pillGap = 12f, pillPadX = 28f, pillFont = 29f, pillCount = 6, pillCharCap = 28,
    gapAfterPills = 34f,
    heatTitleFont = 24f, heatDot7 = 54f, heatGap7 = 17f, heatDot30 = 20f, heatGap30 = 8f,
    gapAfterHeat = 34f,
    motivFont = 42f, gapAfterMotiv = 26f,
    footerFont = 28f, footerIcon = 40f
)

private val SQUARE = CardConfig(
    left = 44f, right = 1080f - 44f, top = 44f, gap = 20f,
    iconSize = 56f, nameFont = 34f, nameXGap = 20f,
    dateFont = 27f, gapAfterDate = 28f,
    heroLabelFont = 20f, heroScoreFont = 108f, heroScoreMin = 64f, heroPercentFont = 25f,
    heroInnerGap = 8f, gapAfterHero = 24f,
    tilePadX = 20f, tilePadY = 10f, tileVGap = 6f, tileGap = 12f, tileRowGap = 10f,
    tileLabelFont = 17f, tileValueFont = 34f, tileBadgeFont = 21f, tileEmojiFont = 30f,
    gapAfterTiles = 24f,
    skillsTitleFont = 22f, gapAfterSkillsTitle = 12f,
    pillH = 44f, pillGap = 10f, pillPadX = 20f, pillFont = 24f, pillCount = 4, pillCharCap = 16,
    gapAfterPills = 20f,
    heatTitleFont = 20f, heatDot7 = 40f, heatGap7 = 13f, heatDot30 = 15f, heatGap30 = 5f,
    gapAfterHeat = 20f,
    motivFont = 32f, gapAfterMotiv = 14f,
    footerFont = 22f, footerIcon = 30f
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

private data class TileSpec(
    val emoji: String,
    val label: String,
    val value: String,
    val valueColor: Color,
    val badge: Boolean
)

/**
 * Lays the card out as a single vertical flow (flex-column semantics): every
 * section's position follows from the section above it, all text is measured
 * and width-constrained to the padded content column, and nothing is placed
 * with hardcoded pixel offsets. Returns the complete block list — the drawing
 * pass and the QA bounds/overlap check both consume exactly this.
 */
internal fun layoutProgressCard(
    stats: ProgressCardStats.CardStats,
    texts: CardTexts,
    heroScore: Int?,
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

    // ── 3. Hero stat (score auto-shrinks to ≤80% of the content width) ──
    flow.text(
        texts.scoreLabel,
        TextStyle(
            fontSize = cfg.heroLabelFont.sp,
            fontWeight = FontWeight.SemiBold,
            color = CardTextDim,
            letterSpacing = (cfg.heroLabelFont * 0.30f).sp
        ),
        centeredX = cfg.center
    )
    flow.gap(cfg.heroInnerGap)

    val scoreText = if (heroScore != null) "$heroScore/100" else "—"
    val maxScoreW = cfg.width * 0.8f
    var scoreFont = cfg.heroScoreFont
    var scoreStyle = TextStyle(fontSize = scoreFont.sp, fontWeight = FontWeight.Bold, color = Color.White)
    while (measure.measure(scoreText, scoreStyle, maxScoreW).width > maxScoreW &&
        scoreFont > cfg.heroScoreMin
    ) {
        scoreFont -= 4f
        scoreStyle = TextStyle(fontSize = scoreFont.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
    flow.text(scoreText, scoreStyle, maxWidth = maxScoreW, centeredX = cfg.center)
    flow.gap(cfg.heroInnerGap)

    flow.text(
        texts.percentLine,
        TextStyle(fontSize = cfg.heroPercentFont.sp, fontWeight = FontWeight.Medium, color = CardTeal),
        centeredX = cfg.center
    )
    flow.gap(cfg.gapAfterHero)

    // ── 4. Stats grid (2×3): icon + label inline on one line, value below ──
    val percentSuffix = if (stats.due > 0) " (${stats.percent}%)" else ""
    val tiles = listOf(
        TileSpec("🎯", texts.created, stats.created.toString(), Color.White, badge = false),
        TileSpec("✅", texts.completed, stats.completed.toString() + percentSuffix, CardDone, badge = false),
        TileSpec("⏳", texts.incomplete, stats.missed.toString() + percentSuffix, CardMissed, badge = false),
        TileSpec(
            "⚡", texts.currentStreak,
            if (stats.firstWeek) texts.firstWeek else stats.currentStreak.toString() + texts.daysUnit,
            if (stats.firstWeek) CardTextDim else Color.White,
            badge = stats.firstWeek
        ),
        TileSpec("📈", texts.bestStreak, stats.bestStreak.toString() + texts.daysUnit, Color.White, badge = false),
        TileSpec("📅", texts.activeDays, stats.activeDays.toString(), Color.White, badge = false)
    )
    val tileW = (cfg.width - cfg.tileGap) / 2f
    for (row in 0 until 3) {
        val rowY = flow.y
        // Equal heights within a row: both tiles' backgrounds stretch to the
        // taller tile so the 2-column grid always reads as a clean, even pair
        // (the inner text stays top-aligned inside each tile).
        val leftTile = buildTile(tiles[row * 2], cfg.left, tileW, rowY, cfg, measure)
        val rightTile = buildTile(tiles[row * 2 + 1], cfg.left + tileW + cfg.tileGap, tileW, rowY, cfg, measure)
        val rowH = maxOf(leftTile.rect.height, rightTile.rect.height)
        blocks += leftTile.copy(rect = leftTile.rect.copy(bottom = leftTile.rect.top + rowH))
        blocks += rightTile.copy(rect = rightTile.rect.copy(bottom = rightTile.rect.top + rowH))
        flow.y = rowY + rowH
        if (row < 2) flow.gap(cfg.tileRowGap)
    }
    flow.gap(cfg.gapAfterTiles)

    // ── 5. Skills & habits pills (wrapped, width-constrained) ──
    flow.text(
        texts.skillsTitle,
        TextStyle(
            fontSize = cfg.skillsTitleFont.sp,
            fontWeight = FontWeight.SemiBold,
            color = CardTextDim,
            letterSpacing = (cfg.skillsTitleFont * 0.24f).sp
        )
    )
    flow.gap(cfg.gapAfterSkillsTitle)

    val pillStyle = TextStyle(fontSize = cfg.pillFont.sp, fontWeight = FontWeight.SemiBold, color = CardTeal)
    val skills = stats.skills.take(cfg.pillCount).map { it.take(cfg.pillCharCap) }
    if (skills.isEmpty()) {
        flow.text(texts.skillsEmpty, TextStyle(fontSize = cfg.pillFont.sp, color = CardTextDim))
    } else {
        var px = cfg.left
        var py = flow.y
        skills.forEach { skill ->
            val size = measure.measure(skill, pillStyle, cfg.width)
            val pillW = size.width + cfg.pillPadX * 2f
            if (px + pillW > cfg.right && px > cfg.left) {
                px = cfg.left
                py += cfg.pillH + cfg.pillGap
            }
            val textTop = py + (cfg.pillH - size.height) / 2f
            blocks += CardBlock.Pill(
                rect = CardRect(px, py, px + pillW, py + cfg.pillH),
                textRect = CardRect(px + cfg.pillPadX, textTop, px + cfg.pillPadX + size.width, textTop + size.height),
                text = skill,
                style = pillStyle
            )
            px += pillW + cfg.pillGap
        }
        flow.y = py + cfg.pillH
    }
    flow.gap(cfg.gapAfterPills)

    // ── 6. Heatmap strip: own row, cleared from the section above ──
    flow.text(
        texts.lastDays,
        TextStyle(
            fontSize = cfg.heatTitleFont.sp,
            fontWeight = FontWeight.SemiBold,
            color = CardTextDim,
            letterSpacing = (cfg.heatTitleFont * 0.20f).sp
        )
    )
    flow.gap(cfg.gapAfterSkillsTitle)

    val dots = stats.heatmap
    if (dots.isNotEmpty()) {
        var dotSize = if (dots.size <= 7) cfg.heatDot7 else cfg.heatDot30
        val dotGap = if (dots.size <= 7) cfg.heatGap7 else cfg.heatGap30
        var totalW = dots.size * dotSize + (dots.size - 1) * dotGap
        while (totalW > cfg.width && dotSize > 10f) {
            dotSize -= 2f
            totalW = dots.size * dotSize + (dots.size - 1) * dotGap
        }
        var dx = cfg.center - totalW / 2f
        val dotY = flow.y
        dots.forEach { day ->
            blocks += CardBlock.Dot(CardRect(dx, dotY, dx + dotSize, dotY + dotSize), day.heat)
            dx += dotSize + dotGap
        }
        flow.y = dotY + dotSize
    }
    flow.gap(cfg.gapAfterHeat)

    // ── 7. Motivational line ──
    flow.text(
        texts.motivational,
        TextStyle(fontSize = cfg.motivFont.sp, fontWeight = FontWeight.Bold, color = CardTeal),
        centeredX = cfg.center
    )
    flow.gap(cfg.gapAfterMotiv)

    // ── 8. Watermark footer (icon + "Made with …", centered as one unit) ──
    val footerStyle = TextStyle(fontSize = cfg.footerFont.sp, color = CardTextDim)
    val madeSize = measure.measure(texts.madeWith, footerStyle, cfg.width)
    val unitW = madeSize.width + 16f + cfg.footerIcon
    val startX = cfg.center - unitW / 2f
    val footerY = flow.y
    blocks += CardBlock.Icon(
        CardRect(startX, footerY, startX + cfg.footerIcon, footerY + cfg.footerIcon),
        appIconPresent
    )
    flow.text(
        texts.madeWith, footerStyle, cfg.width,
        x = startX + cfg.footerIcon + 16f,
        y = footerY + (cfg.footerIcon - madeSize.height) / 2f
    )
    flow.y = footerY + cfg.footerIcon

    return blocks
}

/** One 2×3-grid tile: emoji + label share the top line, value sits below. */
private fun buildTile(
    spec: TileSpec,
    x: Float,
    w: Float,
    y: Float,
    cfg: CardConfig,
    measure: CardMeasurer
): CardBlock.Tile {
    val emojiStyle = TextStyle(fontSize = cfg.tileEmojiFont.sp)
    val emojiSize = measure.measure(spec.emoji, emojiStyle, w)
    val labelStyle = TextStyle(fontSize = cfg.tileLabelFont.sp, color = CardTextDim)
    val labelMax = w - cfg.tilePadX * 2f - emojiSize.width - 10f
    val labelSize = measure.measure(spec.label, labelStyle, labelMax)
    val labelRowH = maxOf(emojiSize.height, labelSize.height)
    val valueStyle = TextStyle(
        fontSize = (if (spec.badge) cfg.tileBadgeFont else cfg.tileValueFont).sp,
        fontWeight = FontWeight.Bold,
        color = spec.valueColor
    )
    val valueSize = measure.measure(spec.value, valueStyle, w - cfg.tilePadX * 2f)
    val h = cfg.tilePadY + labelRowH + cfg.tileVGap + valueSize.height + cfg.tilePadY

    val innerLeft = x + cfg.tilePadX
    val labelTop = y + cfg.tilePadY
    return CardBlock.Tile(
        rect = CardRect(x, y, x + w, y + h),
        emoji = spec.emoji,
        emojiRect = CardRect(innerLeft, labelTop, innerLeft + emojiSize.width, labelTop + emojiSize.height),
        emojiStyle = emojiStyle,
        label = spec.label,
        labelRect = CardRect(
            innerLeft + emojiSize.width + 10f, labelTop,
            innerLeft + emojiSize.width + 10f + labelSize.width, labelTop + labelSize.height
        ),
        labelStyle = labelStyle,
        value = spec.value,
        valueRect = CardRect(
            innerLeft, y + cfg.tilePadY + labelRowH + cfg.tileVGap,
            innerLeft + valueSize.width, y + cfg.tilePadY + labelRowH + cfg.tileVGap + valueSize.height
        ),
        valueStyle = valueStyle
    )
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
