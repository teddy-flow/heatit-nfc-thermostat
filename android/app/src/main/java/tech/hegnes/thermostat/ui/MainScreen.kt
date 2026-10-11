package tech.hegnes.thermostat.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class NfcAvailability { AVAILABLE, DISABLED, MISSING }

private enum class Tab(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Filled.Home),
    SCHEDULE("Schedule", Icons.Filled.DateRange),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ThermostatViewModel, nfc: NfcAvailability, onOpenNfcSettings: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.OVERVIEW) }
    var confirmBlindWrite by remember { mutableStateOf(false) }

    LaunchedEffect(state.message?.id) {
        state.message?.let { snackbar.showSnackbar(it.text) }
    }

    val onWrite = { if (state.loadedFromDevice) viewModel.armWrite() else confirmBlindWrite = true }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Heatit Thermostat") })
                SyncBar(state, nfc, onOpenNfcSettings, onRead = viewModel::armRead, onWrite = onWrite, onDiscard = viewModel::discardChanges)
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tab) {
                Tab.OVERVIEW -> OverviewTab(state, viewModel, onGoToSchedule = { tab = Tab.SCHEDULE })
                Tab.SCHEDULE -> ScheduleTab(state, viewModel)
                Tab.SETTINGS -> SettingsTab(state, viewModel)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (state.pending != PendingOp.NONE) {
        ModalBottomSheet(onDismissRequest = viewModel::cancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            NfcPrompt(writing = state.pending == PendingOp.WRITE, onCancel = viewModel::cancel)
        }
    }

    if (confirmBlindWrite) {
        AlertDialog(
            onDismissRequest = { confirmBlindWrite = false },
            title = { Text("Read the thermostat first?") },
            text = {
                Text(
                    "Sending replaces every setting on the thermostat, including sensor type and limits. " +
                        "Reading first makes sure only the things you changed are different.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmBlindWrite = false; viewModel.armRead() }) { Text("Read first") } },
            dismissButton = { TextButton(onClick = { confirmBlindWrite = false; viewModel.armWrite() }) { Text("Send anyway") } },
        )
    }
}

@Composable
private fun SyncBar(
    state: UiState,
    nfc: NfcAvailability,
    onOpenNfcSettings: () -> Unit,
    onRead: () -> Unit,
    onWrite: () -> Unit,
    onDiscard: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, icon, title, detail) = when {
        nfc == NfcAvailability.MISSING -> Quad(scheme.errorContainer, Icons.Filled.Warning, "No NFC on this phone", "This app needs a phone with NFC.")
        nfc == NfcAvailability.DISABLED -> Quad(scheme.errorContainer, Icons.Filled.Warning, "NFC is turned off", "Turn on NFC to talk to the thermostat.")
        !state.loadedFromDevice -> Quad(scheme.secondaryContainer, Icons.Filled.Phone, "Not read yet", "Read the thermostat to start from its current settings.")
        state.hasUnsentChanges -> Quad(scheme.tertiaryContainer, Icons.Filled.Warning, "Changes not sent", "Tap Send and hold the phone to the thermostat.")
        else -> Quad(scheme.surfaceContainerHigh, Icons.Filled.CheckCircle, "In sync", "Matches the thermostat · ${readTime(state.lastReadAt)}")
    }
    Surface(color = container) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            when {
                nfc == NfcAvailability.DISABLED -> Button(onClick = onOpenNfcSettings) { Text("Turn on") }
                nfc == NfcAvailability.MISSING -> Unit
                state.hasUnsentChanges -> Row {
                    TextButton(onClick = onDiscard, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Undo") }
                    Button(onClick = onWrite) { Text("Send") }
                }
                !state.loadedFromDevice -> Button(onClick = onRead) { Text("Read") }
                else -> Row {
                    TextButton(onClick = onRead, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Read") }
                    OutlinedButton(onClick = onWrite) { Text("Send") }
                }
            }
        }
    }
}

private data class Quad(val a: androidx.compose.ui.graphics.Color, val b: ImageVector, val c: String, val d: String)

private fun readTime(millis: Long): String {
    if (millis == 0L) return "never"
    val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val time = dt.format(DateTimeFormatter.ofPattern("HH:mm"))
    return if (dt.toLocalDate() == LocalDate.now()) "read today $time" else "read ${dt.format(DateTimeFormatter.ofPattern("d MMM"))} $time"
}

@Composable
private fun NfcPrompt(writing: Boolean, onCancel: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(1f, 1.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp).scale(scale).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape))
            Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Phone, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
            }
        }
        Text(if (writing) "Send to thermostat" else "Read from thermostat", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Hold the back of your phone flat against the front of the thermostat and keep it still until the phone confirms. " +
                "The NFC spot is usually near the middle of the phone's back.",
            textAlign = TextAlign.Center,
        )
        if (writing) Hint("All settings, the week plan and the clock are sent together.", Modifier.padding(horizontal = 8.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
