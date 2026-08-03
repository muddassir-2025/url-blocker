package com.example.url_blocker.quran.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.url_blocker.R
import com.example.url_blocker.quran.model.QuranVerse

/**
 * Quran tab: the complete current verse — surah name, reference, the Arabic
 * verse (when the Arabic edition is cached) and the English translation —
 * plus subtle actions (New Verse / Copy) and the refresh-frequency picker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuranTab(
    verse: QuranVerse?,
    isLoading: Boolean,
    refreshIntervalHours: Int,
    onRefreshIntervalChange: (Int) -> Unit,
    onNewVerse: () -> Unit,
    onCopyVerse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val intervalOptions = listOf(1, 2, 3, 4, 6, 8, 12, 24)

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
                    text = "Verses are being downloaded.\nOpen the app once to download the full translation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            else -> {
                val v = verse!!
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
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${v.surahNumber}:${v.ayahNumber}",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                }

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
                        enabled = !isLoading,
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

                Spacer(Modifier.height(32.dp))

                HorizontalDivider()

                Spacer(Modifier.height(24.dp))

                // Refresh frequency picker.
                Text(
                    text = stringResource(R.string.quran_verse_refresh_frequency),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    intervalOptions.forEach { hours ->
                        FilterChip(
                            selected = hours == refreshIntervalHours,
                            onClick = {
                                if (hours != refreshIntervalHours) onRefreshIntervalChange(hours)
                            },
                            label = { Text(context.getString(R.string.quran_verse_hour_short, hours)) }
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = context.resources.getQuantityString(
                        R.plurals.quran_verse_refresh_note_hours,
                        refreshIntervalHours,
                        refreshIntervalHours
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
