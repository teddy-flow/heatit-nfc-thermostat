package tech.hegnes.thermostat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.hegnes.thermostat.protocol.DaySchedule
import tech.hegnes.thermostat.schedule.TempChoice
import tech.hegnes.thermostat.schedule.TempLevel
import java.util.Locale

fun formatTemp(half: Int): String = String.format(Locale.getDefault(), "%.1f °C", half / 2.0)
fun formatTime(minutes: Int): String = if (minutes >= 1440) "24:00" else "%02d:%02d".format(minutes / 60, minutes % 60)

private val Cool = Color(0xFF3D7BF0)
private val Warm = Color(0xFFF0623A)

/** Blue for cool through orange for warm, across roughly 17–22 °C. */
fun tempColor(half: Int): Color = lerp(Cool, Warm, ((half - 34) / 10f).coerceIn(0f, 1f))

@Composable
fun Section(title: String, modifier: Modifier = Modifier, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Hint(it) }
            }
            content()
        }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Card(
        modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, Modifier.padding(end = 12.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

/** Large −/+ stepper in 0.5 °C steps. */
@Composable
fun TempStepper(label: String, value: Int, range: IntRange, modifier: Modifier = Modifier, hint: String? = null, onChange: (Int) -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Hint(it) }
        }
        FilledTonalIconButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            formatTemp(value),
            Modifier.width(84.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = tempColor(value),
            textAlign = TextAlign.Center,
        )
        FilledTonalIconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** A 24-hour bar coloured by target temperature, with optional hour labels and a "now" marker. */
@Composable
fun DayTimeline(
    day: DaySchedule,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    showHours: Boolean = false,
    nowMinute: Int? = null,
) {
    val marker = MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2)),
        ) {
            val w = size.width
            drawRect(tempColor(day.base))
            day.periods.forEach { p ->
                val x0 = w * p.start / 1440f
                val x1 = w * p.end / 1440f
                drawRect(tempColor(p.temp), topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height))
            }
            nowMinute?.let {
                val x = w * it / 1440f
                drawRoundRect(marker, Offset(x - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
            }
        }
        if (showHours) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
                listOf(0, 6, 12, 18, 24).forEach { h ->
                    val x = maxWidth * (h / 24f)
                    Text(
                        "%02d".format(h),
                        Modifier.offset(x = (x - 8.dp).coerceIn(0.dp, maxWidth - 16.dp)).width(16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Home / Away / Custom picker; Home and Away follow the setpoints on the Overview tab. */
@Composable
fun LevelSelector(choice: TempChoice, home: Int, away: Int, range: IntRange, onChange: (TempChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(TempLevel.HOME to "Home", TempLevel.AWAY to "Away", TempLevel.CUSTOM to "Custom")
            options.forEachIndexed { i, (level, label) ->
                SegmentedButton(
                    selected = choice.level == level,
                    onClick = {
                        val start = if (choice.level == TempLevel.CUSTOM) choice.custom else choice.resolve(home, away)
                        onChange(TempChoice(level, start))
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) {
                    val sub = when (level) {
                        TempLevel.HOME -> formatTemp(home)
                        TempLevel.AWAY -> formatTemp(away)
                        TempLevel.CUSTOM -> null
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, maxLines = 1)
                        sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    }
                }
            }
        }
        if (choice.level == TempLevel.CUSTOM) {
            TempStepper("Custom temperature", choice.custom.coerceIn(range), range) { onChange(choice.copy(custom = it)) }
        }
    }
}

/** Button showing a time; opens a 24-hour picker. For end times, 00:00 means midnight (24:00). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(minutes: Int, isEnd: Boolean, modifier: Modifier = Modifier, onPicked: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        Text(formatTime(minutes), style = MaterialTheme.typography.titleMedium)
    }
    if (open) {
        val m = if (minutes >= 1440) 0 else minutes
        val state = rememberTimePickerState(initialHour = m / 60, initialMinute = m % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(if (isEnd) "Ends at" else "Starts at") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = state)
                    if (isEnd) Hint("Pick 00:00 to run until midnight.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    val picked = state.hour * 60 + state.minute
                    onPicked(if (isEnd && picked == 0) 1440 else picked)
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun <T> DropdownField(label: String, options: List<T>, selected: T, labelOf: (T) -> String, hint: String? = null, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Hint(it) }
        }
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(labelOf(selected)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(labelOf(option)) }, onClick = { expanded = false; onSelect(option) })
                }
            }
        }
    }
}

@Composable
fun SliderRow(label: String, valueText: String, value: Int, range: IntRange, hint: String? = null, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        hint?.let { Hint(it) }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(Math.round(it)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            // Tick marks only for short ranges; with dozens of steps they turn into a dotted line.
            steps = if (range.last - range.first <= 12) range.last - range.first - 1 else 0,
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Hint(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
