package com.example.url_blocker.quran.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.example.url_blocker.quran.data.QuranRepository
import com.example.url_blocker.quran.model.QuranVerse
import com.example.url_blocker.ui.theme.UrlblockerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen verse details, opened by tapping the Quran Reminder widget.
 * Shows the complete English verse with its surah name and numbers.
 * A "New Verse" action picks another random verse from the local cache.
 */
class QuranVerseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UrlblockerTheme {
                VerseDetailsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerseDetailsScreen() {
    val context = LocalContext.current
    val repository = remember { QuranRepository(context.applicationContext) }

    var verse by remember { mutableStateOf(repository.getCurrentVerse()) }
    var isLoading by remember { mutableStateOf(verse == null) }
    val scope = rememberCoroutineScope()

    // Initial load: show whatever is cached; trigger a download if nothing yet.
    LaunchedEffect(Unit) {
        if (verse == null) {
            isLoading = true
            val loaded = withContext(Dispatchers.IO) {
                if (repository.isCached()) repository.pickRandomVerse() else null
            }
            verse = loaded
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quran_verse_header)) },
                navigationIcon = {
                    IconButton(onClick = { (context as? android.app.Activity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isLoading = true
                            scope.launch {
                                val next = withContext(Dispatchers.IO) { repository.pickRandomVerse() }
                                if (next != null) verse = next
                                isLoading = false
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "New verse")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    Text(
                        text = "🕋",
                        fontSize = 56.sp
                    )
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
                    Spacer(Modifier.height(16.dp))

                    // Reference: Quran S:V (Surah Name)
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${v.surahNumber}:${v.ayahNumber}",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(Modifier.height(32.dp))

                    // Full verse text — never truncated.
                    Text(
                        text = "“${v.text}”",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif,
                        lineHeight = 32.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(32.dp))

                    // Surah name + translation.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = v.surahName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (v.surahTranslation.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = v.surahTranslation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Surah ${v.surahNumber} · Ayah ${v.ayahNumber}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(40.dp))

                    OutlinedButton(
                        onClick = {
                            isLoading = true
                            scope.launch {
                                val next = withContext(Dispatchers.IO) { repository.pickRandomVerse() }
                                if (next != null) verse = next
                                isLoading = false
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New Verse")
                    }

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = "Refreshes automatically every 6 hours · works offline",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}


