package com.muddassir.clearview.quran.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.quran.model.QuranVerse

/**
 * Quran tab (the app's home page): the complete current verse — surah name,
 * reference, the Arabic verse (when the Arabic edition is cached) and the
 * English translation — plus Previous/Next verse navigation (wrapping across
 * surah boundaries) and subtle actions (New Verse / Copy).
 *
 * The refresh-frequency picker, the Media/Quran notification toggles and the
 * updates feed live in the top-bar Settings / Notifications sheets instead,
 * so the home page stays a clean verse reader.
 *
 * LAYOUT NOTE: every child is a plain element of the vertically scrollable
 * Column — no Box overlays, no stacked content. The Previous/Next row is part
 * of that normal flow (below the Arabic verse, above the translation), so it
 * pushes the content below it down naturally and nothing ever overlaps.
 */
@Composable
fun QuranTab(
    verse: QuranVerse?,
    isLoading: Boolean,
    onNewVerse: () -> Unit,
    onCopyVerse: () -> Unit,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            isLoading -> {
                Spacer(Modifier.height(48.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Loading verse…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            verse == null -> {
                Spacer(Modifier.height(48.dp))
                Text("🕋", fontSize = 56.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    // Shown only when the first-run download couldn't complete
                    // (e.g. offline). Normally the download runs in-process and
                    // the verse appears automatically — see ContentHubState.start.
                    text = "Quran verses are downloading.\nPlease close and reopen ClearView once the download is complete.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            else -> {
                val v = verse!!
                // Previous/Next navigation is part of the normal vertical
                // layout (no overlay/stack): the row sits under the reference
                // chip and above the translation, pushing content down.
                VerseDisplay(
                    v = v,
                    canGoPrevious = canGoPrevious,
                    canGoNext = canGoNext,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onNewVerse = onNewVerse,
                    onCopyVerse = onCopyVerse
                )
            }
        }
    }
}

/**
 * The full verse reading view: surah header, verse reference, Arabic verse,
 * Previous/Next navigation, English translation and the action buttons — all
 * plain Column children in order, so they stack vertically with normal
 * spacing and never overlap.
 */
@Composable
private fun VerseDisplay(
    v: QuranVerse,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onNewVerse: () -> Unit,
    onCopyVerse: () -> Unit
) {
    Spacer(Modifier.height(8.dp))

    // Surah name + translation.
    Text(
        text = v.surahName,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    if (v.surahTranslation.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = v.surahTranslation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Surah ${v.surahNumber} · Ayah ${v.ayahNumber}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(Modifier.height(24.dp))

    // Arabic verse (renders RTL automatically from the script).
    if (v.arabicText.isNotBlank()) {
        Text(
            text = v.arabicText,
            fontSize = 30.sp,
            lineHeight = 50.sp,
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
    }

    // Previous / Next navigation — centered, directly under the Arabic verse
    // and above the English translation. A plain centered Row in the normal
    // vertical flow (pushes the translation down; never overlays it).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onPrevious,
            enabled = canGoPrevious,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.quran_verse_previous),
                style = MaterialTheme.typography.titleSmall
            )
        }
        Spacer(Modifier.width(16.dp))
        OutlinedButton(
            onClick = onNext,
            enabled = canGoNext,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.quran_verse_next),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    Spacer(Modifier.height(28.dp))

    // English translation — never truncated.
    Text(
        text = "“${v.text}”",
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Serif,
        lineHeight = 32.sp,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(32.dp))

    // Subtle actions.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilledTonalButton(
            onClick = onNewVerse,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.quran_verse_new))
        }
        OutlinedButton(
            onClick = onCopyVerse,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.quran_verse_copy))
        }
    }
}
