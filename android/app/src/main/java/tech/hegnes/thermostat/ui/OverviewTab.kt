package tech.hegnes.thermostat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.hegnes.thermostat.protocol.Mode
import tech.hegnes.thermostat.protocol.RegulationMode
import tech.hegnes.thermostat.schedule.DAY_LONG
import tech.hegnes.thermostat.schedule.nextChange
import tech.hegnes.thermostat.schedule.tempAt
import java.time.LocalDateTime

private val modeInfo = mapOf(
    Mode.OFF to "Heating is switched off.",
    Mode.COMFORT to "Keeps your Home temperature all the time.",
    Mode.ECO to "Keeps your Away temperature all the time to save energy.",
    Mode.SCHEDULE to "Switches between Home and Away by itself, following your week plan.",
)

@Composable
fun OverviewTab(state: UiState, vm: ThermostatViewModel, onGoToSchedule: () -> Unit) {
    val s = state.settings
    NowCard(state, onGoToSchedule)
    SameTempWarning(s) { vm.edit { it.copy(eco = (it.comfort - 8).coerceAtLeast(it.setpointRange.first)) } }

    Section("Quick profiles", subtitle = "Load a whole week in one tap, then press Send. Make your own on the Schedule tab.") {
        state.profiles.forEach { profile ->
            ProfileCard(
                name = profile.name,
                description = profile.describe(state.presets),
                active = profile.isActive(s.mode, state.weekPlan),
            ) { vm.applyProfile(profile) }
        }
        if (state.profiles.isEmpty()) Hint("No profiles yet – save one from the Schedule tab.")
    }

    Section("Mode", subtitle = "What the thermostat should do.") {
        Mode.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { mode ->
                    ModeCard(mode, selected = s.mode == mode, Modifier.weight(1f)) { vm.edit { it.copy(mode = mode) } }
                }
            }
        }
    }

    Section(
        "Your temperatures",
        subtitle = "Home and Away are used by Comfort and Eco mode, and by every preset in your week plan. " +
            "Change them here and all presets follow.",
    ) {
        TempStepper("Home", s.comfort, s.setpointRange, hint = "When you're home and awake (Comfort)") { v ->
            vm.edit { it.copy(comfort = v) }
        }
        HorizontalDivider()
        TempStepper("Away", s.eco, s.setpointRange, hint = "At night and while you're out (Eco)") { v ->
            vm.edit { it.copy(eco = v) }
        }
        Hint(
            "Allowed range: ${formatTemp(s.setpointRange.first)} – ${formatTemp(s.setpointRange.last)}. " +
                when (s.regulation) {
                    RegulationMode.F -> "Set by the floor limits in Settings."
                    RegulationMode.A2 -> "Fixed for external sensor mode."
                    else -> "Set by the room limits in Settings."
                },
        )
    }
}

@Composable
private fun ProfileCard(name: String, description: String, active: Boolean, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            if (active) Text("Active", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            else Text("Load", style = MaterialTheme.typography.labelLarge)
        }
    }
    if (active) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { content() }
    } else {
        OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/** Home == Away makes every preset a flat line, which is easy to miss. */
@Composable
fun SameTempWarning(s: tech.hegnes.thermostat.protocol.ThermostatSettings, onFix: () -> Unit) {
    if (s.comfort != s.eco) return
    InfoBanner("Home and Away are both ${formatTemp(s.comfort)}, so presets and Eco mode make no difference.") {
        TextButton(onClick = onFix) { Text("Away −4 °C") }
    }
}

@Composable
private fun ModeCard(mode: Mode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(12.dp).heightIn(min = 72.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(mode.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(modeInfo.getValue(mode), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (selected) {
        Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { content() }
    } else {
        OutlinedCard(onClick = onClick, modifier = modifier) { content() }
    }
}

@Composable
private fun NowCard(state: UiState, onGoToSchedule: () -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = LocalDateTime.now()
        }
    }
    val s = state.settings
    val dayIndex = now.dayOfWeek.value - 1
    val minute = now.hour * 60 + now.minute
    val today = s.schedule[dayIndex]

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.hasUnsentChanges) "Planned right now (not sent yet)" else "Right now", style = MaterialTheme.typography.labelLarge)
            when (s.mode) {
                Mode.OFF -> Big("Off", null)
                Mode.COMFORT -> Big(formatTemp(s.comfort), "Holding Home temperature")
                Mode.ECO -> Big(formatTemp(s.eco), "Holding Away temperature")
                Mode.SCHEDULE -> {
                    val temp = today.tempAt(minute)
                    val next = today.nextChange(minute)
                    val presetName = state.preset(state.weekPlan.getOrNull(dayIndex) ?: "")?.name
                    Big(
                        formatTemp(temp),
                        if (next != null) "until ${formatTime(next.first)}, then ${formatTemp(next.second)}" else "for the rest of the day",
                        tempHalf = temp,
                    )
                    DayTimeline(today, height = 18.dp, showHours = true, nowMinute = minute)
                    Row {
                        Hint("${DAY_LONG[dayIndex]} · ${presetName ?: "Custom"}", Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = onGoToSchedule) { Text("Edit week") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Big(value: String, caption: String?, tempHalf: Int? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            color = tempHalf?.let { tempColor(it) } ?: MaterialTheme.colorScheme.onSurface,
        )
        caption?.let { Text(it, Modifier.weight(1f).padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium) }
    }
}
