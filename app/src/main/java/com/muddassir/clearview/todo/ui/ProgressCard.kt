package com.muddassir.clearview.todo.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.data.ProgressCardStats
import com.muddassir.clearview.todo.data.TodoStore
import com.muddassir.clearview.todo.model.TodoItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A rendered card: the composable preview surface + the raw bitmap for export.
// The palette, resolved strings ([CardTexts]) and the layout engine itself live
// in ProgressCardLayout.kt (pure, unit-tested).
private data class RenderedCard(val image: ImageBitmap, val bitmap: Bitmap)

/**
 * The shareable Progress Card generator — a full-screen dialog in two steps:
 *
 *  1. Name + time range → Generate Card.
 *  2. Live preview (Story 1080×1920 / Square 1080×1080) + Save to gallery,
 *     Share sheet and Regenerate.
 *
 * The card itself is drawn VECTOR-STRAIGHT INTO A BITMAP at full export
 * resolution ([CanvasDrawScope] over an off-screen [android.graphics.Bitmap]),
 * so the exported image is razor sharp at any size — no screenshot scaling.
 */
@Composable
fun ProgressCardDialog(
    items: List<TodoItem>,
    today: LocalDate,
    nowMillis: Long,
    weekScore: Int?,
    store: TodoStore,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ProgressCardFlow(items, today, nowMillis, weekScore, store, onDismiss)
        }
    }
}

@Composable
private fun ProgressCardFlow(
    items: List<TodoItem>,
    today: LocalDate,
    nowMillis: Long,
    weekScore: Int?,
    store: TodoStore,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf(0) } // 0 = input · 1 = card
    var name by remember { mutableStateOf(store.getProgressCardName()) }
    var range by remember { mutableStateOf(ProgressCardStats.RangeKind.WEEK) }
    var customFrom by remember { mutableStateOf<LocalDate?>(null) }
    var customTo by remember { mutableStateOf<LocalDate?>(null) }
    var isStory by remember { mutableStateOf(true) }
    var card by remember { mutableStateOf<ProgressCardStats.CardStats?>(null) }
    var generating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (step == 1) {
                    step = 0
                    card = null
                } else {
                    onDismiss()
                }
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.todo_back)
                )
            }
            Text(
                text = stringResource(R.string.progress_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        when (step) {
            0 -> NameInputStep(
                name = name,
                onNameChange = { name = it },
                range = range,
                onRangeChange = { range = it },
                customFrom = customFrom,
                customTo = customTo,
                onCustomFromChange = { customFrom = it },
                onCustomToChange = { customTo = it },
                generating = generating,
                onGenerate = {
                    // Range math (especially All Time, which walks every day
                    // since the earliest todo) must not run on the UI thread.
                    generating = true
                    scope.launch {
                        val computed = withContext(Dispatchers.Default) {
                            ProgressCardStats.compute(
                                items, range, today, nowMillis, customFrom, customTo
                            )
                        }
                        card = computed
                        if (name.isNotBlank()) store.setProgressCardName(name.trim())
                        generating = false
                        step = 1
                    }
                }
            )
            else -> card?.let { stats ->
                CardPreviewStep(
                    stats = stats,
                    name = name,
                    weekScore = weekScore,
                    isStory = isStory,
                    onStoryChange = { isStory = it },
                    onRegenerate = {
                        step = 0
                        card = null
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameInputStep(
    name: String,
    onNameChange: (String) -> Unit,
    range: ProgressCardStats.RangeKind,
    onRangeChange: (ProgressCardStats.RangeKind) -> Unit,
    customFrom: LocalDate?,
    customTo: LocalDate?,
    onCustomFromChange: (LocalDate?) -> Unit,
    onCustomToChange: (LocalDate?) -> Unit,
    generating: Boolean,
    onGenerate: () -> Unit
) {
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.progress_card_name_label)) },
            placeholder = { Text(stringResource(R.string.progress_card_name_placeholder)) },
            singleLine = true
        )
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.progress_card_range_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                ProgressCardStats.RangeKind.WEEK to R.string.progress_card_range_week,
                ProgressCardStats.RangeKind.MONTH to R.string.progress_card_range_month,
                ProgressCardStats.RangeKind.ALL to R.string.progress_card_range_all,
                ProgressCardStats.RangeKind.CUSTOM to R.string.progress_card_range_custom
            ).forEach { (option, label) ->
                FilterChip(
                    selected = range == option,
                    onClick = { onRangeChange(option) },
                    label = { Text(stringResource(label)) }
                )
            }
        }

        if (range == ProgressCardStats.RangeKind.CUSTOM) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { showFromPicker = true }) {
                    Text(
                        text = stringResource(
                            R.string.progress_card_range_from,
                            customFrom?.format(dateFmt) ?: "—"
                        )
                    )
                }
                OutlinedButton(onClick = { showToPicker = true }) {
                    Text(
                        text = stringResource(
                            R.string.progress_card_range_to,
                            customTo?.format(dateFmt) ?: "—"
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        val customValid = range != ProgressCardStats.RangeKind.CUSTOM ||
            (customFrom != null && customTo != null && !customTo!!.isBefore(customFrom))
        Button(
            onClick = onGenerate,
            enabled = name.isNotBlank() && customValid && !generating,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                if (generating) stringResource(R.string.progress_card_generating)
                else stringResource(R.string.progress_card_generate)
            )
        }
    }

    if (showFromPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = customFrom?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onCustomFromChange(
                            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                        )
                    }
                    showFromPicker = false
                }) { Text(stringResource(R.string.todo_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showFromPicker = false }) {
                    Text(stringResource(R.string.todo_cancel))
                }
            }
        ) {
            DatePicker(state = state)
        }
    }
    if (showToPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = customTo?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onCustomToChange(
                            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                        )
                    }
                    showToPicker = false
                }) { Text(stringResource(R.string.todo_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showToPicker = false }) {
                    Text(stringResource(R.string.todo_cancel))
                }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun CardPreviewStep(
    stats: ProgressCardStats.CardStats,
    name: String,
    weekScore: Int?,
    isStory: Boolean,
    onStoryChange: (Boolean) -> Unit,
    onRegenerate: () -> Unit
) {
    val context = LocalContext.current
    // The hero stat: the on-screen Weekly Score for the WEEK range (exact
    // match), the generalized range score otherwise.
    val heroScore = if (stats.rangeKind == ProgressCardStats.RangeKind.WEEK) {
        weekScore ?: stats.score
    } else {
        stats.score
    }
    val rendered = rememberProgressCardImage(stats, name, heroScore, context, isStory)

    // Pre-Android-10 gallery save goes through the system save dialog (SAF);
    // Android 10+ writes straight into MediaStore Pictures/ClearView.
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri != null) {
            val ok = writePngToUri(context, uri, rendered.bitmap)
            Toast.makeText(
                context,
                context.getString(
                    if (ok) R.string.progress_card_saved else R.string.progress_card_save_failed
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = isStory,
                onClick = { onStoryChange(true) },
                label = { Text(stringResource(R.string.progress_card_story)) }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = !isStory,
                onClick = { onStoryChange(false) },
                label = { Text(stringResource(R.string.progress_card_square)) }
            )
        }
        Spacer(Modifier.height(16.dp))
        Image(
            bitmap = rendered.image,
            contentDescription = stringResource(R.string.progress_card_title),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (isStory) 1080f / 1920f else 1f)
                .clip(RoundedCornerShape(24.dp))
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val ok = saveCardToMediaStore(context, rendered.bitmap)
                        Toast.makeText(
                            context,
                            context.getString(
                                if (ok) R.string.progress_card_saved
                                else R.string.progress_card_save_failed
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        createDoc.launch("ProgressCard.png")
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.progress_card_save))
            }
            OutlinedButton(
                onClick = { shareCard(context, rendered.bitmap) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.progress_card_share))
            }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onRegenerate,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.progress_card_regenerate))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Off-screen rendering ────────────────────────────────────────────────

/**
 * Renders [stats] into an off-screen [Bitmap] at full export resolution
 * (1080×1920 story / 1080×1080 square) via [CanvasDrawScope], returning both
 * the composable preview surface and the raw bitmap for save/share. Re-renders
 * whenever the stats, name, hero score or size change.
 */
@Composable
private fun rememberProgressCardImage(
    stats: ProgressCardStats.CardStats,
    name: String,
    heroScore: Int?,
    context: Context,
    isStory: Boolean
): RenderedCard {
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val density = Density(1f) // draw in raw pixels: 1 sp/dp == 1 px
    val appIcon = remember { loadAppIcon(context) }
    val grain = remember { createGrainBitmap() }
    val width = 1080
    val height = if (isStory) 1920 else 1080
    // Draw SYNCHRONOUSLY while creating the bitmap so the preview never shows
    // a blank frame: CanvasDrawScope.draw is synchronous, and this only re-runs
    // when the stats/name/score/size actually change (a button tap), not on
    // every recomposition.
    return remember(stats, name, heroScore, isStory) {
        val texts = buildTexts(stats, name, heroScore, context)
        // 1. Lay out every element as a measured block (flex-column flow).
        val blocks = layoutProgressCard(
            stats, texts, heroScore, appIcon != null, isStory
        ) { text, style, maxWidth ->
            // ceil: never constrain below the layout width, or a borderline
            // single-line text would re-wrap during the draw pass.
            val result = textMeasurer.measure(
                AnnotatedString(text), style,
                constraints = Constraints(maxWidth = ceil(maxWidth).toInt().coerceAtLeast(1))
            )
            Size(result.size.width.toFloat(), result.size.height.toFloat())
        }
        // 2. QA gate: nothing may sit outside the canvas or overlap. The unit
        //    tests assert this is empty for worst-case inputs; this log catches
        //    any regression the tests didn't predict.
        verifyCardLayout(blocks, width.toFloat(), height.toFloat())
            .takeIf { it.isNotEmpty() }
            ?.let { Log.w("ProgressCard", "Layout QA failed: ${it.joinToString(" | ")}") }
        // 3. Draw the blocks onto the export bitmap.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val image = bitmap.asImageBitmap()
        CanvasDrawScope().draw(
            density,
            LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(image),
            Size(width.toFloat(), height.toFloat())
        ) {
            drawProgressCardBackground(grain)
            drawCardBlocks(blocks, appIcon, textMeasurer)
        }
        RenderedCard(image, bitmap)
    }
}

private fun buildTexts(
    stats: ProgressCardStats.CardStats,
    name: String,
    heroScore: Int?,
    context: Context
): CardTexts {
    val appName = context.getString(R.string.app_name)
    return CardTexts(
        nameProgress = context.getString(R.string.progress_card_your_progress, name.trim().take(24)),
        scoreLabel = context.getString(
            if (stats.rangeKind == ProgressCardStats.RangeKind.WEEK) {
                R.string.progress_card_score_week
            } else {
                R.string.progress_card_score_range
            }
        ),
        created = context.getString(R.string.progress_card_created),
        completed = context.getString(R.string.progress_card_completed),
        incomplete = context.getString(R.string.progress_card_incomplete),
        currentStreak = context.getString(R.string.progress_card_current_streak),
        bestStreak = context.getString(R.string.progress_card_best_streak),
        activeDays = context.getString(R.string.progress_card_active_days),
        daysUnit = " " + context.getString(R.string.progress_card_days),
        skillsTitle = context.getString(R.string.progress_card_skills),
        skillsEmpty = context.getString(R.string.progress_card_skills_empty),
        firstWeek = context.getString(R.string.progress_card_first_week),
        lastDays = if (stats.rangeKind == ProgressCardStats.RangeKind.WEEK) {
            context.getString(R.string.progress_card_this_week)
        } else {
            context.getString(R.string.progress_card_last_days, stats.heatmap.size)
        },
        motivational = when {
            stats.percent < 20 -> context.getString(R.string.progress_card_motiv_low)
            stats.percent <= 70 -> context.getString(R.string.progress_card_motiv_mid)
            else -> context.getString(R.string.progress_card_motiv_high)
        },
        madeWith = context.getString(R.string.progress_card_made_with, appName),
        dateRange = formatDateRange(stats.from, stats.to),
        percentLine = context.getString(R.string.progress_card_percent, stats.percent)
    )
}

/** "Aug 3 – Aug 10, 2026" (year repeated only when the range crosses years). */
private fun formatDateRange(from: LocalDate, to: LocalDate): String {
    val short = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    val full = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    return when {
        from == to -> full.format(from)
        from.year == to.year -> "${short.format(from)} – ${full.format(to)}"
        else -> "${full.format(from)} – ${full.format(to)}"
    }
}

/** The launcher icon rasterized for the card header (null → teal ✓ mark). */
private fun loadAppIcon(context: Context): ImageBitmap? = runCatching {
    val drawable = context.getDrawable(R.mipmap.ic_launcher) ?: return null
    val size = 192
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    bmp.asImageBitmap()
}.getOrNull()

/** A small tiled noise bitmap giving the flat black a subtle grain texture. */
private fun createGrainBitmap(size: Int = 128): ImageBitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val rand = Random(0xC0FFEE)
    for (x in 0 until size) {
        for (y in 0 until size) {
            val v = (rand.nextInt(64) - 32 + 128).coerceIn(0, 255)
            bmp.setPixel(x, y, android.graphics.Color.argb(255, v, v, v))
        }
    }
    return bmp.asImageBitmap()
}

// ── The poster drawing (DrawScope over the off-screen bitmap) ──────────

private fun DrawScope.drawProgressCardBackground(grain: ImageBitmap?) {
    val w = size.width
    val h = size.height

    // Flat black + a teal-tinted vertical gradient and a radial glow behind
    // the hero — the "designed, not a screenshot" backdrop.
    drawRect(
        brush = Brush.verticalGradient(listOf(CardBgTop, CardBgBottom))
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(CardTeal.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(w / 2f, h * 0.30f),
            radius = w * 0.60f
        ),
        radius = w * 0.60f,
        center = Offset(w / 2f, h * 0.30f)
    )
    // Subtle grain over the whole card.
    grain?.let { g ->
        val gs = g.width
        var gx = 0
        while (gx < w) {
            var gy = 0
            while (gy < h) {
                drawImage(
                    image = g,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(gs, gs),
                    dstOffset = IntOffset(gx, gy),
                    dstSize = IntSize(gs, gs),
                    alpha = 0.05f
                )
                gy += gs
            }
            gx += gs
        }
    }
}

/**
 * Draws the laid-out [blocks]. Placement comes entirely from the rects
 * produced by [layoutProgressCard] — this pass never re-derives positions, so
 * the QA-verified layout is exactly what gets exported.
 */
private fun DrawScope.drawCardBlocks(blocks: List<CardBlock>, appIcon: ImageBitmap?, tm: TextMeasurer) {
    blocks.forEach { block ->
        when (block) {
            is CardBlock.Text ->
                drawTextBlock(block.text, block.style, block.rect, tm)

            is CardBlock.Tile -> {
                drawRoundRect(
                    color = CardTileBg,
                    topLeft = Offset(block.rect.left, block.rect.top),
                    size = Size(block.rect.width, block.rect.height),
                    cornerRadius = CornerRadius(26f)
                )
                drawRoundRect(
                    color = CardTileBorder,
                    topLeft = Offset(block.rect.left, block.rect.top),
                    size = Size(block.rect.width, block.rect.height),
                    cornerRadius = CornerRadius(26f),
                    style = Stroke(width = 2f)
                )
                drawTextBlock(block.emoji, block.emojiStyle, block.emojiRect, tm)
                drawTextBlock(block.label, block.labelStyle, block.labelRect, tm)
                drawTextBlock(block.value, block.valueStyle, block.valueRect, tm)
            }

            is CardBlock.Pill -> {
                drawRoundRect(
                    color = CardTeal.copy(alpha = 0.12f),
                    topLeft = Offset(block.rect.left, block.rect.top),
                    size = Size(block.rect.width, block.rect.height),
                    cornerRadius = CornerRadius(block.rect.height / 2f)
                )
                drawTextBlock(block.text, block.style, block.textRect, tm)
            }

            is CardBlock.Dot -> {
                val color = when (block.heat) {
                    ProgressCardStats.Heat.DONE -> CardDone
                    ProgressCardStats.Heat.MISSED -> CardMissed
                    ProgressCardStats.Heat.SCHEDULED -> CardGrayDot
                }
                drawCircle(
                    color = color,
                    radius = block.rect.width / 2f,
                    center = Offset(
                        block.rect.left + block.rect.width / 2f,
                        block.rect.top + block.rect.height / 2f
                    )
                )
            }

            is CardBlock.Icon -> drawIconBlock(block, appIcon, tm)
        }
    }
}

/** Draws a text block at exactly the laid-out rect (wrapped to its width). */
private fun DrawScope.drawTextBlock(text: String, style: TextStyle, rect: CardRect, tm: TextMeasurer) {
    // ceil: keep the draw constraint ≥ the layout width so the text can never
    // re-wrap differently than the QA-verified layout.
    val layout = tm.measure(
        AnnotatedString(text), style,
        constraints = Constraints(maxWidth = ceil(rect.width).toInt().coerceAtLeast(1))
    )
    drawText(layout, topLeft = Offset(rect.left, rect.top))
}

/** The app logo raster (or a teal ✓ tile as fallback) inside its block. */
private fun DrawScope.drawIconBlock(block: CardBlock.Icon, appIcon: ImageBitmap?, tm: TextMeasurer) {
    val corner = CornerRadius(block.rect.width * 0.24f)
    if (block.useAppIcon && appIcon != null) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.10f),
            topLeft = Offset(block.rect.left, block.rect.top),
            size = Size(block.rect.width, block.rect.height),
            cornerRadius = corner
        )
        drawImage(
            image = appIcon,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(appIcon.width, appIcon.height),
            dstOffset = IntOffset(block.rect.left.toInt(), block.rect.top.toInt()),
            dstSize = IntSize(block.rect.width.toInt(), block.rect.height.toInt())
        )
    } else {
        drawRoundRect(
            color = CardTeal,
            topLeft = Offset(block.rect.left, block.rect.top),
            size = Size(block.rect.width, block.rect.height),
            cornerRadius = corner
        )
        val mark = TextStyle(
            fontSize = (block.rect.width * 0.55f).sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF06241F)
        )
        val layout = tm.measure(AnnotatedString("✓"), mark)
        drawText(
            layout,
            topLeft = Offset(
                block.rect.left + (block.rect.width - layout.size.width) / 2f,
                block.rect.top + (block.rect.height - layout.size.height) / 2f
            )
        )
    }
}

// ── Save / share ────────────────────────────────────────────────────────

/** Android 10+: writes the PNG straight into Pictures/ClearView (no permission needed). */
private fun saveCardToMediaStore(context: Context, bitmap: Bitmap): Boolean {
    val bytes = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "progress_card.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/ClearView"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        } catch (e: Exception) {
            // Don't leave an IS_PENDING=1 ghost row behind on failure.
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        false
    }
}

/** Pre-Android-10: writes the PNG to a SAF-picked location. */
private fun writePngToUri(context: Context, uri: Uri, bitmap: Bitmap): Boolean = try {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    } != null
} catch (e: Exception) {
    false
}

/** Native share sheet (Instagram / WhatsApp / …) via a cache file + FileProvider. */
private fun shareCard(context: Context, bitmap: Bitmap) {
    try {
        val file = File(context.cacheDir, "progress_card.png")
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.progress_card_share_via))
        )
    } catch (e: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.progress_card_share_failed),
            Toast.LENGTH_SHORT
        ).show()
    }
}
