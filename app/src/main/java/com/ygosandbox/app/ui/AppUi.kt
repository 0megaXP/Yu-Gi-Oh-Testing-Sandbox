package com.ygosandbox.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.BuildConfig
import com.ygosandbox.app.DbStatus
import com.ygosandbox.app.Screen
import com.ygosandbox.app.UiState

@Composable
fun SandboxApp(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        val m = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.dismissMessage()
    }
    BackHandler(enabled = ui.stack.size > 1) { vm.back() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ui.update?.let { info ->
                UpdateBanner(info.version, ui.updateProgress, onUpdate = { vm.installUpdate() })
            }
            if (ui.dbStatus == DbStatus.DOWNLOADING) DbBanner(ui.dbProgress)
            Box(Modifier.weight(1f)) {
                when (val s = ui.screen) {
                    Screen.Menu -> MainMenu(ui, vm)
                    Screen.Decks -> DecksScreen(ui, vm)
                    Screen.NewSearch -> NewSearchScreen(ui, vm)
                    is Screen.DeckDetail -> DeckDetailScreen(ui, vm, s.id)
                    Screen.Cards -> CardsScreen(ui, vm)
                    Screen.MyDecks -> MyDecksScreen(ui, vm)
                    is Screen.DeckEditor -> DeckEditorScreen(ui, vm, s.id)
                    Screen.Analysis -> AnalysisScreen(ui, vm)
                    is Screen.AnalysisDeck -> AnalysisDeckScreen(ui, vm, s.id)
                    Screen.Play -> PlayScreen(ui, vm)
                    is Screen.SidePlan -> SidePlanScreen(ui, vm, s.deckId)
                    Screen.Settings -> SettingsScreen(ui, vm)
                }
            }
        }
    }
}

/** Title row with a back arrow, shared by every screen below the main menu. */
@Composable
fun Header(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(
            title, style = MaterialTheme.typography.titleLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun MainMenu(ui: UiState, vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "YGO Sandbox", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.open(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
        }
        Text(
            "Test and learn decks from real tournament results.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DbStatusLine(ui, vm)
        Spacer(Modifier.height(4.dp))
        MenuTile(
            Icons.Default.Layers, "My decks",
            if (ui.myDecks.isEmpty()) "Build, edit and save your own decks"
            else "${ui.myDecks.size} deck${if (ui.myDecks.size == 1) "" else "s"}",
        ) { vm.open(Screen.MyDecks) }
        MenuTile(
            Icons.Default.ViewCarousel, "Archetype search",
            if (ui.decks.isEmpty()) "See what tournament decks play with any archetype"
            else "${ui.decks.size} saved · search a new archetype",
        ) { vm.open(Screen.Decks) }
        MenuTile(
            Icons.Default.Insights, "Analysis",
            "Card roles and opening-hand odds for your decks",
            badge = { TestBadge() },
        ) { vm.open(Screen.Analysis) }
        MenuTile(
            Icons.Default.Psychology, "Play engine",
            "Best plays and end boards for a hand, against a real deck",
            badge = { TestBadge() },
        ) { vm.open(Screen.Play) }
        MenuTile(
            Icons.Default.Style, "Card database",
            if (ui.cardCount > 0) "Browse ${ui.cardCount} cards and archetypes" else "Available once downloaded",
        ) { vm.open(Screen.Cards) }
        Spacer(Modifier.height(8.dp))
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DbStatusLine(ui: UiState, vm: AppViewModel) {
    when (ui.dbStatus) {
        DbStatus.LOADING -> Text("Opening card database…", style = MaterialTheme.typography.bodySmall)
        DbStatus.ERROR, DbStatus.MISSING -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Couldn't download the card database",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    ui.dbError ?: "Check your internet connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Button(onClick = { vm.retryCardDatabase() }) { Text("Try again") }
            }
        }
        else -> {}
    }
}

@Composable
private fun MenuTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (badge != null) { Spacer(Modifier.width(8.dp)); badge() }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DbBanner(progress: Float) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                if (progress < 0.85f) "Downloading the card database… ${(progress / 0.85f * 100).toInt()}%"
                else "Saving the card database…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UpdateBanner(version: String, progress: Float?, onUpdate: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (progress == null) "YGO Sandbox $version is available" else "Downloading $version…",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (progress == null) TextButton(onClick = onUpdate) { Text("Update") }
            }
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}
