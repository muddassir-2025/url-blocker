package com.muddassir.clearview.quran.ui

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.muddassir.clearview.R
import com.muddassir.clearview.quran.data.DhikrCodec
import com.muddassir.clearview.quran.data.DhikrStore
import com.muddassir.clearview.quran.model.DhikrItem
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A peaceful, distraction-free Dhikr (tasbih) counter: a swipeable pager of
 * dhikr cards, each with a large circular progress ring that fills toward its
 * own target, a big tappable area that counts instantly (with a subtle tick
 * vibration), long-press to reset, a quick-jump search, and a settings screen
 * for vibration, dhikr management (add / edit / remove / hide / search /
 * reorder) and counter controls. Everything is persisted per dhikr via
 * [DhikrStore], so counts survive restarts and switching between dhikr never
 * loses progress.
 *
 * Opened from the Quran settings sheet (Dhikr Counter card); rendered as a
 * full-screen dialog like the other hub screens.
 */
@Composable
fun DhikrCounterScreen(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        // Full width + edge-to-edge, same as the Quran search / bookmarks screens.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            DhikrCounterContent(onDismiss = onDismiss)
        }
    }
}

@Composable
private fun DhikrCounterContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DhikrStore(context.applicationContext) }
    var items by remember { mutableStateOf(store.getItems()) }
    var selectedId by remember { mutableStateOf(store.getSelectedId()) }
    var vibrationEnabled by remember { mutableStateOf(store.getVibrationEnabled()) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    // A dhikr awaiting "reset count" confirmation (long-press).
    var pendingReset by remember { mutableStateOf<DhikrItem?>(null) }

    fun save(newItems: List<DhikrItem>) {
        items = newItems
        store.saveItems(newItems)
    }

    // Only visible dhikr appear in the pager; hidden ones stay in management.
    val visibleItems = remember(items) { items.filter { it.visible } }
    // Keep the selection on a visible dhikr (e.g. the selected one got hidden).
    val effectiveSelectedId = remember(visibleItems, selectedId) {
        if (visibleItems.any { it.id == selectedId }) selectedId
        else visibleItems.firstOrNull()?.id
    }

    fun select(id: String) {
        selectedId = id
        store.setSelectedId(id)
    }

    // Counting must feel instant: the count state changes on the same frame as
    // the tap; only the ring fill and scale animate afterwards. When the count
    // CROSSES the target on this tap, the completed session is recorded in the
    // SAME write (one save, one recomposition) and the haptic is a stronger
    // triple pulse instead of the single tick.
    fun increment(id: String) {
        val updated = DhikrCodec.incremented(items, id)
        val crossed = DhikrCodec.crossesTarget(updated, id)
        save(
            if (crossed) {
                DhikrCodec.markedCompleted(updated, id, System.currentTimeMillis())
            } else {
                updated
            }
        )
        if (crossed) {
            DhikrVibrator.celebrate(context, vibrationEnabled)
        } else {
            DhikrVibrator.tick(context, vibrationEnabled)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── Top bar: back · title · search · settings ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.dhikr_counter_back)
                )
            }
            Text(
                text = stringResource(R.string.dhikr_counter_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            if (visibleItems.size > 1) {
                IconButton(onClick = { showSearch = true }) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.dhikr_counter_search)
                    )
                }
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.dhikr_counter_settings)
                )
            }
        }

        if (visibleItems.isEmpty()) {
            // All dhikr hidden — recover them from settings.
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.dhikr_counter_all_hidden),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { showSettings = true }) {
                    Text(stringResource(R.string.dhikr_counter_settings))
                }
            }
        } else {
            DhikrPager(
                items = visibleItems,
                selectedId = effectiveSelectedId.orEmpty(),
                onSelect = ::select,
                onIncrement = ::increment,
                onRequestReset = { pendingReset = it },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }

    // ── Reset confirmation (long-press) ───────────────────────────
    pendingReset?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text(stringResource(R.string.dhikr_counter_reset_title)) },
            text = { Text(stringResource(R.string.dhikr_counter_reset_text, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    save(DhikrCodec.resetItem(items, item.id))
                    pendingReset = null
                }) { Text(stringResource(R.string.dhikr_counter_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReset = null }) {
                    Text(stringResource(R.string.dhikr_cancel))
                }
            }
        )
    }

    if (showSettings) {
        DhikrSettingsScreen(
            items = items,
            vibrationEnabled = vibrationEnabled,
            selectedId = effectiveSelectedId,
            onVibrationChange = {
                vibrationEnabled = it
                store.setVibrationEnabled(it)
            },
            onSave = ::save,
            onSelect = ::select,
            onDismiss = { showSettings = false }
        )
    }

    if (showSearch) {
        DhikrSearchSheet(
            items = visibleItems,
            selectedId = effectiveSelectedId,
            onSelect = {
                select(it)
                showSearch = false
            },
            onDismiss = { showSearch = false }
        )
    }
}

/**
 * The swipeable dhikr carousel. Each page is one dhikr's counter; swiping
 * left/right moves to the next/previous dhikr and persists the selection. The
 * pager is recreated only when the VISIBLE set changes (add/remove/hide/
 * reorder) — count updates recompose pages in place, so the scroll position
 * and the running animation never reset while counting.
 */
@Composable
private fun DhikrPager(
    items: List<DhikrItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onIncrement: (String) -> Unit,
    onRequestReset: (DhikrItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // Stable identity of the visible set — NOT the counts, so counting never
    // recreates the pager.
    val setKey = items.joinToString(",") { it.id }
    val initialPage = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

    // key() resets the pager STATE only when the visible set changes — the
    // weight modifier is applied by the caller's ColumnScope (key() does not
    // carry it), and the inner Column re-applies it to the pager itself.
    key(setKey) {
        val pagerState = rememberPagerState(
            initialPage = initialPage,
            pageCount = { items.size }
        )
        // Follow external selection changes (search jump / management) by
        // animating the pager to the selected dhikr.
        LaunchedEffect(selectedId) {
            val target = items.indexOfFirst { it.id == selectedId }
            if (target >= 0 && target != pagerState.currentPage) {
                pagerState.animateScrollToPage(target)
            }
        }
        // Keep the persisted selection in sync with the page actually shown
        // (swipe / jump / external selection all funnel through this).
        LaunchedEffect(pagerState.currentPage) {
            items.getOrNull(pagerState.currentPage)?.let { onSelect(it.id) }
        }
        Column(modifier = modifier) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                val item = items.getOrNull(page) ?: return@HorizontalPager
                DhikrPage(
                    item = item,
                    onCount = { onIncrement(item.id) },
                    onRequestReset = { onRequestReset(item) }
                )
            }

            // ── Position dots (only when there is more than one dhikr) ──
            if (items.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    items.forEachIndexed { index, _ ->
                        val selected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (selected) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * One dhikr's counting page: name, Arabic + translation, then the large
 * circular progress ring with the count in its center — the whole ring is the
 * tap target (instant increment with a springy scale) and long-pressing it
 * requests a reset.
 */
@Composable
private fun DhikrPage(
    item: DhikrItem,
    onCount: () -> Unit,
    onRequestReset: () -> Unit
) {
    val completed = item.target > 0 && item.count >= item.target
    val fraction = if (item.target > 0) item.count.toFloat() / item.target.toFloat() else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label = "dhikr-ring"
    )
    // Springy press feedback: counts instantly, scales after.
    val scale = remember(item.id) { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.arabic.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = item.arabic,
                fontSize = 34.sp,
                lineHeight = 44.sp,
                fontFamily = FontFamily.Serif,
                textAlign = TextAlign.Center
            )
        }
        if (item.translation.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.translation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.weight(0.9f))

        // ── Ring + count (the tap area) ──
        val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
        val progressColor = if (completed) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .size(296.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .pointerInput(item.id) {
                    detectTapGestures(
                        onTap = {
                            scope.launch {
                                scale.snapTo(0.93f)
                                scale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                            onCount()
                        },
                        onLongPress = { onRequestReset() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            ProgressRing(
                progress = animatedFraction,
                strokeWidth = 14.dp,
                trackColor = trackColor,
                progressColor = progressColor,
                modifier = Modifier.fillMaxSize()
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = item.count.toString(),
                    fontSize = 68.sp,
                    fontWeight = FontWeight.Light,
                    color = if (completed) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (item.target > 0) "${item.count} / ${item.target}"
                    else stringResource(R.string.dhikr_counter_no_target),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (completed) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.dhikr_counter_completed),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(0.25f))

        // ── Remaining / completed note ──
        Text(
            text = if (completed) {
                stringResource(R.string.dhikr_counter_completed_note)
            } else if (item.target > 0) {
                stringResource(
                    R.string.dhikr_counter_remaining,
                    (item.target - item.count).coerceAtLeast(0)
                )
            } else {
                ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        // Previous / saved progress indicator.
        if (item.lastCompletedCount > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dhikr_counter_last, item.lastCompletedCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.count == 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.dhikr_counter_tap_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        Spacer(Modifier.weight(0.5f))
    }
}

/** A calm circular progress ring (rounded caps) around the count. */
@Composable
private fun ProgressRing(
    progress: Float,
    strokeWidth: Dp,
    trackColor: Color,
    progressColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        if (progress > 0f) {
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * Full-screen Dhikr settings: the vibration toggle, dhikr management (search,
 * add / edit / remove / hide / reorder) and counter controls (target count,
 * reset current, reset all). Rendered as its own dialog so the management
 * list gets a single, bounded scroller.
 */
@Composable
private fun DhikrSettingsScreen(
    items: List<DhikrItem>,
    vibrationEnabled: Boolean,
    selectedId: String?,
    onVibrationChange: (Boolean) -> Unit,
    onSave: (List<DhikrItem>) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<DhikrItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showTargetDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DhikrItem?>(null) }
    var pendingResetCurrent by remember { mutableStateOf(false) }
    var pendingResetAll by remember { mutableStateOf(false) }

    val ordered = remember(items) { DhikrCodec.ordered(items) }
    val currentItem = remember(ordered, selectedId) {
        ordered.firstOrNull { it.id == selectedId } ?: ordered.firstOrNull()
    }
    val q = query.trim().lowercase()
    val filtered = remember(ordered, q) {
        if (q.isEmpty()) ordered
        else ordered.filter {
            it.name.lowercase().contains(q) ||
                it.arabic.lowercase().contains(q) ||
                it.translation.lowercase().contains(q)
        }
    }

    // ── Drag-to-reorder (replaces the old up/down arrows) ──
    // Long-press a row and drag it to a new position. Reordering only applies
    // to the FULL ordered list — a search filter disables dragging (filtered
    // indices would mismatch the real positions), while edit/hide/delete stay
    // available.
    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<String?>(null) }
    val dragOffset = remember { Animatable(0f) }
    val itemHeights = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()
    // The drag gesture lambdas outlive recompositions, so they must always
    // read the CURRENT list and save callback, never a stale composition copy.
    val currentFiltered by rememberUpdatedState(filtered)
    val currentOnSave by rememberUpdatedState(onSave)
    val dragEnabled = q.isEmpty()
    // Slot stride = item height + the 6dp gap between rows (Arrangement.spacedBy
    // below) — the drag swap math must use the stride or it drifts off the finger.
    val rowSpacingPx = with(LocalDensity.current) { 6.dp.toPx() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                // ── Top bar ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dhikr_counter_back)
                        )
                    }
                    Text(
                        text = stringResource(R.string.dhikr_counter_settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // ── Vibration ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Vibration,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dhikr_counter_vibration),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.dhikr_counter_vibration_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = vibrationEnabled, onCheckedChange = onVibrationChange)
                    }

                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    // ── Manage Dhikr ──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.dhikr_counter_manage_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { adding = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.dhikr_counter_add))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.dhikr_counter_manage_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotBlank()) {
                            {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = null)
                                }
                            }
                        } else null,
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    if (filtered.isEmpty()) {
                        Text(
                            text = stringResource(
                                if (ordered.isEmpty()) R.string.dhikr_counter_manage_empty
                                else R.string.dhikr_counter_manage_no_match
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        // Drag-to-reorder (long-press a row, then drag) replaces
                        // the old up/down arrows. Bounded so the list scrolls
                        // inside the settings column; dragging is disabled while
                        // a search filter is active.
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filtered, key = { it.id }) { item ->
                                val isDragging = draggingId == item.id
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onGloballyPositioned { itemHeights[item.id] = it.size.height }
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .graphicsLayer {
                                            translationY = if (isDragging) dragOffset.value else 0f
                                        }
                                        .shadow(if (isDragging) 10.dp else 0.dp, RoundedCornerShape(12.dp))
                                        .pointerInput(item.id, dragEnabled) {
                                            if (!dragEnabled) return@pointerInput
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingId = item.id
                                                    scope.launch { dragOffset.snapTo(0f) }
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    scope.launch {
                                                        dragOffset.snapTo(dragOffset.value + dragAmount.y)
                                                        val stride =
                                                            (itemHeights[item.id] ?: return@launch) + rowSpacingPx
                                                        val current =
                                                            currentFiltered.indexOfFirst { it.id == item.id }
                                                        if (current < 0) return@launch
                                                        val shift = (dragOffset.value / stride).roundToInt()
                                                        val target = (current + shift)
                                                            .coerceIn(0, currentFiltered.lastIndex)
                                                        if (target != current) {
                                                            val reordered =
                                                                currentFiltered.toMutableList().apply {
                                                                    add(target, removeAt(current))
                                                                }
                                                            currentOnSave(DhikrCodec.reindexed(reordered))
                                                            dragOffset.snapTo(
                                                                dragOffset.value - (target - current) * stride
                                                            )
                                                        }
                                                    }
                                                },
                                                onDragEnd = {
                                                    draggingId = null
                                                    scope.launch { dragOffset.snapTo(0f) }
                                                },
                                                onDragCancel = {
                                                    draggingId = null
                                                    scope.launch { dragOffset.snapTo(0f) }
                                                }
                                            )
                                        }
                                ) {
                                    ManageRow(
                                        item = item,
                                        dragging = isDragging,
                                        dragEnabled = dragEnabled,
                                        onToggleVisible = {
                                            onSave(DhikrCodec.withVisibility(items, item.id, !item.visible))
                                        },
                                        onEdit = { editing = item },
                                        onDelete = { pendingDelete = item }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    // ── Counter controls ──
                    Text(
                        text = stringResource(R.string.dhikr_counter_counter_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    if (currentItem != null) {
                        Button(
                            onClick = { showTargetDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    R.string.dhikr_counter_set_target_for,
                                    currentItem.name,
                                    if (currentItem.target > 0) currentItem.target.toString()
                                    else stringResource(R.string.dhikr_counter_no_target)
                                )
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(
                        onClick = { pendingResetCurrent = true },
                        enabled = currentItem != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dhikr_counter_reset_current))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { pendingResetAll = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dhikr_counter_reset_all))
                    }
                }
            }
        }
    }

    // ── Add / edit ──
    val addedMsg = stringResource(R.string.dhikr_counter_added)
    if (adding) {
        DhikrEditDialog(
            title = stringResource(R.string.dhikr_counter_add_title),
            onSubmit = { name, arabic, translation, target ->
                onSave(
                    DhikrCodec.added(
                        items,
                        DhikrItem(
                            id = "custom-${UUID.randomUUID()}",
                            name = name,
                            arabic = arabic,
                            translation = translation,
                            target = target
                        )
                    )
                )
                Toast.makeText(context, addedMsg, Toast.LENGTH_SHORT).show()
                adding = false
            },
            onDismiss = { adding = false }
        )
    }
    editing?.let { item ->
        DhikrEditDialog(
            title = stringResource(R.string.dhikr_counter_edit_title),
            initial = item,
            onSubmit = { name, arabic, translation, target ->
                onSave(
                    DhikrCodec.updated(
                        items,
                        item.copy(name = name, arabic = arabic, translation = translation, target = target)
                    )
                )
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    // ── Target count ──
    if (showTargetDialog) {
        val targetItem = currentItem ?: return
        TargetCountDialog(
            initial = targetItem.target,
            onConfirm = { newTarget ->
                onSave(DhikrCodec.withTarget(items, targetItem.id, newTarget))
                showTargetDialog = false
            },
            onDismiss = { showTargetDialog = false }
        )
    }

    // ── Delete confirmation ──
    val removedMsg = stringResource(R.string.dhikr_counter_removed)
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dhikr_counter_delete_title)) },
            text = { Text(stringResource(R.string.dhikr_counter_delete_text, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val remaining = DhikrCodec.removed(items, item.id)
                    onSave(remaining)
                    // If the deleted dhikr was selected, fall back to the first
                    // VISIBLE remaining one (never a hidden dhikr).
                    if (selectedId == item.id) {
                        DhikrCodec.ordered(remaining).firstOrNull { it.visible }
                            ?.let { onSelect(it.id) }
                    }
                    pendingDelete = null
                    Toast.makeText(
                        context,
                        removedMsg,
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text(stringResource(R.string.dhikr_counter_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.dhikr_cancel))
                }
            }
        )
    }

    // ── Reset current dhikr ──
    if (pendingResetCurrent) {
        AlertDialog(
            onDismissRequest = { pendingResetCurrent = false },
            title = { Text(stringResource(R.string.dhikr_counter_reset_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dhikr_counter_reset_text,
                        currentItem?.name ?: ""
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    currentItem?.let { onSave(DhikrCodec.resetItem(items, it.id)) }
                    pendingResetCurrent = false
                }) { Text(stringResource(R.string.dhikr_counter_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingResetCurrent = false }) {
                    Text(stringResource(R.string.dhikr_cancel))
                }
            }
        )
    }

    // ── Reset all progress ──
    val resetAllDoneMsg = stringResource(R.string.dhikr_counter_reset_all_done)
    if (pendingResetAll) {
        AlertDialog(
            onDismissRequest = { pendingResetAll = false },
            title = { Text(stringResource(R.string.dhikr_counter_reset_all_title)) },
            text = { Text(stringResource(R.string.dhikr_counter_reset_all_text)) },
            confirmButton = {
                TextButton(onClick = {
                    onSave(DhikrCodec.resetAllProgress(items))
                    pendingResetAll = false
                    Toast.makeText(
                        context,
                        resetAllDoneMsg,
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text(stringResource(R.string.dhikr_counter_reset_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingResetAll = false }) {
                    Text(stringResource(R.string.dhikr_cancel))
                }
            }
        )
    }
}

/**
 * One dhikr card in the management list: info + a drag handle (long-press the
 * row to reorder — the parent wrapper owns the gesture) + a ⋮ menu with
 * show/hide, edit and delete. [dragging] lifts the row visually while it is
 * being dragged; [dragEnabled] dims the handle during a search filter.
 */
@Composable
private fun ManageRow(
    item: DhikrItem,
    dragging: Boolean,
    dragEnabled: Boolean,
    onToggleVisible: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember(item.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (dragging) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!item.visible) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = stringResource(R.string.dhikr_counter_hidden),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                if (item.arabic.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.arabic,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Serif,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                val targetLabel = if (item.target > 0) {
                    stringResource(R.string.dhikr_counter_target_value, item.target)
                } else {
                    stringResource(R.string.dhikr_counter_no_target)
                }
                Text(
                    text = buildString {
                        if (item.translation.isNotBlank()) {
                            append(item.translation).append(" · ")
                        }
                        append(targetLabel)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // ── Drag handle (long-press the row to reorder) ──
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.dhikr_counter_drag_reorder),
                tint = if (dragEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(2.dp))
            // ── Row menu: show/hide · edit · delete ──
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.dhikr_counter_menu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (item.visible) R.string.dhikr_counter_hide
                                    else R.string.dhikr_counter_show
                                )
                            )
                        },
                        onClick = { menuOpen = false; onToggleVisible() },
                        leadingIcon = {
                            Icon(
                                imageVector = if (item.visible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.dhikr_counter_edit)) },
                        onClick = { menuOpen = false; onEdit() },
                        leadingIcon = {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.dhikr_counter_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { menuOpen = false; onDelete() },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * Add / edit dialog: name (required), Arabic text, translation and target
 * count (preset chips + custom field).
 */
@Composable
private fun DhikrEditDialog(
    title: String,
    onSubmit: (name: String, arabic: String, translation: String, target: Int) -> Unit,
    onDismiss: () -> Unit,
    initial: DhikrItem? = null
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var arabic by remember { mutableStateOf(initial?.arabic ?: "") }
    var translation by remember { mutableStateOf(initial?.translation ?: "") }
    // Keep a 0 target ("no target") as 0 — editing a dhikr must never
    // silently turn its "no target" into the default 33.
    var targetText by remember {
        mutableStateOf((initial?.target ?: 33).toString())
    }
    var nameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dhikr_counter_name)) },
                    singleLine = true,
                    isError = nameError
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = arabic,
                    onValueChange = { arabic = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dhikr_counter_arabic)) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dhikr_counter_translation)) },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                TargetField(
                    targetText = targetText,
                    onTargetTextChange = { targetText = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) {
                        nameError = true
                    } else {
                        onSubmit(
                            trimmed,
                            arabic.trim(),
                            translation.trim(),
                            targetText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                        )
                    }
                }
            ) { Text(stringResource(R.string.dhikr_counter_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dhikr_cancel)) }
        }
    )
}

/** Preset target chips (33 / 99 / 100 / 1000) + a custom numeric field. */
@Composable
private fun TargetField(
    targetText: String,
    onTargetTextChange: (String) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.dhikr_counter_target_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(33, 99, 100, 1000).forEach { preset ->
                FilterChip(
                    selected = targetText == preset.toString(),
                    onClick = { onTargetTextChange(preset.toString()) },
                    label = { Text(preset.toString()) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = targetText,
            onValueChange = { input ->
                // Digits only, keep the field short.
                onTargetTextChange(input.filter { it.isDigit() }.take(5))
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.dhikr_counter_target_custom)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

/** Sets the target count of the current dhikr (presets + custom). */
@Composable
private fun TargetCountDialog(
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var targetText by remember {
        mutableStateOf((if (initial > 0) initial else 33).toString())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dhikr_counter_set_target)) },
        text = {
            TargetField(
                targetText = targetText,
                onTargetTextChange = { targetText = it }
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(targetText.toIntOrNull()?.coerceAtLeast(0) ?: 0)
                }
            ) { Text(stringResource(R.string.dhikr_counter_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dhikr_cancel)) }
        }
    )
}

/**
 * Quick-jump search over the visible dhikr (the pager's search icon): type to
 * filter, tap a result to jump the pager to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DhikrSearchSheet(
    items: List<DhikrItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val filtered = remember(items, q) {
        if (q.isEmpty()) items
        else items.filter {
            it.name.lowercase().contains(q) ||
                it.arabic.lowercase().contains(q) ||
                it.translation.lowercase().contains(q)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.dhikr_counter_search_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.dhikr_counter_manage_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotBlank()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                } else null,
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.dhikr_counter_search_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                filtered.forEach { item ->
                    Surface(
                        onClick = { onSelect(item.id) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (item.id == selectedId) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.arabic.isNotBlank()) {
                                Text(
                                    text = item.arabic,
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Serif,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (item.id == selectedId) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
