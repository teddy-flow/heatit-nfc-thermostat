package tech.hegnes.thermostat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tech.hegnes.thermostat.protocol.DaySchedule
import tech.hegnes.thermostat.protocol.Mode
import tech.hegnes.thermostat.protocol.ThermostatSettings
import tech.hegnes.thermostat.schedule.DAY_SHORT
import tech.hegnes.thermostat.schedule.DayPreset
import tech.hegnes.thermostat.schedule.PresetBlock
import tech.hegnes.thermostat.schedule.Presets
import tech.hegnes.thermostat.schedule.TempChoice
import tech.hegnes.thermostat.schedule.TempLevel
import tech.hegnes.thermostat.schedule.daysLabel
import java.time.LocalDate

@Composable
fun ScheduleTab(state: UiState, vm: ThermostatViewModel) {
    val s = state.settings
    var editing by remember { mutableStateOf<DayPreset?>(null) }
    var choosingTemplate by remember { mutableStateOf(false) }
    var assigning by remember { mutableStateOf<DayPreset?>(null) }
    var deleting by remember { mutableStateOf<DayPreset?>(null) }
    var savingProfile by remember { mutableStateOf(false) }

    if (s.mode != Mode.SCHEDULE) {
        InfoBanner("The thermostat is in ${s.mode.label} mode, so the week plan isn't used.") {
            TextButton(onClick = { vm.edit { it.copy(mode = Mode.SCHEDULE) } }) { Text("Use it") }
        }
    }

    SameTempWarning(s) { vm.edit { it.copy(eco = (it.comfort - 8).coerceAtLeast(it.setpointRange.first)) } }

    Section(
        "Your week",
        subtitle = "Pick a preset for each day. Presets say when you want it warm (Home) and when it can cool down (Away).",
    ) {
        val today = LocalDate.now().dayOfWeek.value - 1
        (0..6).forEach { day ->
            DayPlanRow(
                day = day,
                isToday = day == today,
                schedule = s.schedule[day],
                selected = state.preset(state.weekPlan[day]),
                presets = state.presets,
                onSelect = { vm.assignPreset(day, it.id) },
            )
        }
        Legend(s)
    }

    Section(
        "Presets",
        subtitle = "A preset is one day's rhythm: up to 3 warm blocks, and a temperature for the rest of the day.",
    ) {
        state.presets.forEach { preset ->
            val days = state.weekPlan.indices.filter { state.weekPlan[it] == preset.id }
            PresetCard(
                preset = preset,
                settings = s,
                usedOn = days,
                canDelete = state.presets.size > 1,
                onEdit = { editing = preset },
                onAssign = { assigning = preset },
                onDelete = { deleting = preset },
            )
        }
        Button(onClick = { choosingTemplate = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null, Modifier.padding(end = 8.dp))
            Text("New preset")
        }
    }

    Section(
        "Week profiles",
        subtitle = "Save this week (presets per day + mode) as a profile you can load from the Overview tab, e.g. \"Office week\" and \"Home week\".",
    ) {
        state.profiles.forEach { profile ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                    Hint(profile.describe(state.presets))
                }
                IconButton(onClick = { vm.deleteProfile(profile.id) }) { Icon(Icons.Filled.Delete, "Delete profile") }
            }
        }
        OutlinedButton(onClick = { savingProfile = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null, Modifier.padding(end = 8.dp))
            Text("Save this week as a profile")
        }
    }

    Section("Adaptive start") {
        SwitchRow(
            "Start heating early",
            s.adaptiveStart,
            hint = "The thermostat learns how long the room takes to warm up and starts in advance, " +
                "so it is already warm when a Home block begins.",
        ) { v -> vm.edit { it.copy(adaptiveStart = v) } }
    }

    if (savingProfile) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { savingProfile = false },
            title = { Text("Save week as profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, placeholder = { Text("e.g. Home office week") }, singleLine = true)
                    Hint("Saves which preset each day uses, and the ${s.mode.label} mode.")
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveCurrentAsProfile(name); savingProfile = false }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { savingProfile = false }) { Text("Cancel") } },
        )
    }
    if (choosingTemplate) {
        TemplatePicker(onDismiss = { choosingTemplate = false }) { template ->
            choosingTemplate = false
            editing = template
        }
    }
    editing?.let { preset ->
        PresetEditor(
            initial = preset,
            settings = s,
            isNew = state.presets.none { it.id == preset.id },
            onDismiss = { editing = null },
            onSave = { saved ->
                vm.savePreset(saved)
                editing = null
                if (state.presets.none { it.id == saved.id }) assigning = saved
            },
        )
    }
    assigning?.let { preset ->
        AssignDaysDialog(
            preset = preset,
            current = state.weekPlan.indices.filter { state.weekPlan[it] == preset.id }.toSet(),
            onDismiss = { assigning = null },
            onConfirm = { days -> vm.assignPresetToDays(preset.id, days); assigning = null },
        )
    }
    deleting?.let { preset ->
        val used = state.weekPlan.indices.filter { state.weekPlan[it] == preset.id }
        val fallback = state.presets.firstOrNull { it.id != preset.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${preset.name}\"?") },
            text = {
                Text(
                    if (used.isEmpty()) "It isn't used on any day."
                    else "It's used on ${daysLabel(used)}. Those days will use \"${fallback?.name}\" instead.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.deletePreset(preset.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Legend(s: ThermostatSettings) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem("Away ${formatTemp(s.eco)}", tempColor(s.eco))
        LegendItem("Home ${formatTemp(s.comfort)}", tempColor(s.comfort))
    }
}

@Composable
private fun LegendItem(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 6.dp).width(16.dp).height(8.dp).background(color, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun DayPlanRow(
    day: Int,
    isToday: Boolean,
    schedule: DaySchedule,
    selected: DayPreset?,
    presets: List<DayPreset>,
    onSelect: (DayPreset) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            DAY_SHORT[day],
            Modifier.width(36.dp),
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Column(Modifier.weight(1f)) {
            Box {
                TextButton(onClick = { open = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                    Text(selected?.name ?: "Custom", maxLines = 1)
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    presets.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; onSelect(p) })
                    }
                }
            }
            DayTimeline(schedule, height = 10.dp)
        }
    }
}

@Composable
private fun PresetCard(
    preset: DayPreset,
    settings: ThermostatSettings,
    usedOn: List<Int>,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onAssign: () -> Unit,
    onDelete: () -> Unit,
) {
    val day = preset.toDaySchedule(settings.comfort, settings.eco, settings.setpointRange)
    OutlinedCard(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium)
                    Hint(if (usedOn.isEmpty()) "Not used yet" else "Used on ${daysLabel(usedOn)}")
                }
                if (canDelete) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete preset") }
            }
            DayTimeline(day, height = 14.dp, showHours = true)
            preset.blocks.sortedBy { it.start }.forEach { b ->
                Text(
                    "${formatTime(b.start)}–${formatTime(b.end)}  ${b.label.ifBlank { "Block" }}: ${choiceText(b.temp, settings)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("Rest of the day: ${choiceText(preset.base, settings)}", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                OutlinedButton(onClick = onAssign) { Text("Use on days…") }
            }
        }
    }
}

private fun choiceText(c: TempChoice, s: ThermostatSettings): String = when (c.level) {
    TempLevel.HOME -> "Home (${formatTemp(s.comfort)})"
    TempLevel.AWAY -> "Away (${formatTemp(s.eco)})"
    TempLevel.CUSTOM -> formatTemp(c.custom)
}

@Composable
private fun TemplatePicker(onDismiss: () -> Unit, onPick: (DayPreset) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start from…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Presets.templates.forEach { t ->
                    OutlinedCard(onClick = { onPick(t.preset) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Hint(t.description)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssignDaysDialog(preset: DayPreset, current: Set<Int>, onDismiss: () -> Unit, onConfirm: (Set<Int>) -> Unit) {
    var days by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use \"${preset.name}\" on") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..6).forEach { d ->
                        FilterChip(
                            selected = d in days,
                            onClick = { days = if (d in days) days - d else days + d },
                            label = { Text(DAY_SHORT[d]) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { days = (0..4).toSet() }) { Text("Weekdays") }
                    TextButton(onClick = { days = setOf(5, 6) }) { Text("Weekend") }
                    TextButton(onClick = { days = (0..6).toSet() }) { Text("Every day") }
                }
                Hint("Days you untick keep the preset they have now.")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(days) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditor(
    initial: DayPreset,
    settings: ThermostatSettings,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (DayPreset) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    val home = settings.comfort
    val away = settings.eco
    val range = settings.setpointRange
    val error = draft.validationError()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (isNew) "New preset" else "Edit preset") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel") } },
                    actions = {
                        TextButton(onClick = { onSave(draft.copy(name = draft.name.trim(), blocks = draft.blocks.sortedBy { it.start })) }, enabled = error == null) {
                            Text("Save")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Office day") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Preview", style = MaterialTheme.typography.labelLarge)
                        DayTimeline(draft.toDaySchedule(home, away, range), height = 22.dp, showHours = true)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }

                Section("Warm blocks", subtitle = "Times when you want a different temperature, usually Home. The thermostat stores up to 3 per day.") {
                    draft.blocks.forEachIndexed { i, block ->
                        if (i > 0) HorizontalDivider()
                        BlockEditor(
                            block = block,
                            home = home, away = away, range = range,
                            onChange = { nb -> draft = draft.copy(blocks = draft.blocks.toMutableList().also { it[i] = nb }) },
                            onRemove = { draft = draft.copy(blocks = draft.blocks.filterIndexed { j, _ -> j != i }) },
                        )
                    }
                    if (draft.blocks.size < DaySchedule.MAX_PERIODS) {
                        OutlinedButton(onClick = {
                            val start = (draft.blocks.maxOfOrNull { it.end } ?: 360).coerceAtMost(1380)
                            val label = listOf("Morning", "Afternoon", "Evening").getOrElse(draft.blocks.size) { "Block" }
                            draft = draft.copy(blocks = draft.blocks + PresetBlock(label, start, (start + 120).coerceAtMost(1440), TempChoice.Home))
                        }) {
                            Icon(Icons.Filled.Add, null, Modifier.padding(end = 8.dp))
                            Text("Add block")
                        }
                    } else {
                        Hint("All 3 blocks are in use. Remove one to add another.")
                    }
                }

                Section("Rest of the day", subtitle = "Used at night and between blocks, e.g. while you're at the office.") {
                    LevelSelector(draft.base, home, away, range) { draft = draft.copy(base = it) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun BlockEditor(
    block: PresetBlock,
    home: Int,
    away: Int,
    range: IntRange,
    onChange: (PresetBlock) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = block.label,
                onValueChange = { onChange(block.copy(label = it)) },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "Remove block") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField(block.start, isEnd = false, modifier = Modifier.weight(1f)) { onChange(block.copy(start = it)) }
            Text("to")
            TimeField(block.end, isEnd = true, modifier = Modifier.weight(1f)) { onChange(block.copy(end = it)) }
        }
        LevelSelector(block.temp, home, away, range) { onChange(block.copy(temp = it)) }
    }
}

