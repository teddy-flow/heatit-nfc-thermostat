package tech.hegnes.thermostat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import tech.hegnes.thermostat.protocol.RegulationMode
import tech.hegnes.thermostat.protocol.SensorType
import tech.hegnes.thermostat.protocol.ThermostatSettings
import java.util.Locale
import kotlin.math.abs

private val regulationInfo = mapOf(
    RegulationMode.A to ("Room sensor" to "Uses the sensor built into the thermostat. The usual choice for a room heated by the floor or a panel."),
    RegulationMode.AF to ("Room sensor + floor limits" to "Controls on the room temperature, and uses the floor sensor to keep the floor between FLo and FHi. Protects wooden and vinyl floors."),
    RegulationMode.F to ("Floor sensor" to "Controls on the floor temperature only. Common in bathrooms where you want a warm floor."),
    RegulationMode.A2 to ("External sensor" to "Controls on a separate room sensor wired to the thermostat, for when the thermostat sits in a bad spot."),
)

@Composable
fun SettingsTab(state: UiState, vm: ThermostatViewModel) {
    val s = state.settings
    val edit = vm::edit

    InfoBanner("These are installer settings. Wrong values can make the thermostat heat too much or too little. If unsure, leave them as read from the thermostat.")

    Section("How it measures", subtitle = "Which sensor the thermostat follows.") {
        RegulationMode.entries.forEach { mode ->
            val (title, text) = regulationInfo.getValue(mode)
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = s.regulation == mode, role = Role.RadioButton) { edit { it.copy(regulation = mode) } }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = s.regulation == mode, onClick = null, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
                Column {
                    Text("${mode.name} – $title", style = MaterialTheme.typography.bodyLarge)
                    Hint(text)
                }
            }
        }
    }

    Section(
        "Sensor types",
        subtitle = "Must match the sensor that's installed (its resistance at 25 °C, printed on the sensor or in its manual). A wrong value gives wrong temperatures.",
    ) {
        DropdownField(
            "Floor sensor", SensorType.entries, s.floorSensor, { it.label },
            hint = if (s.regulation == RegulationMode.AF || s.regulation == RegulationMode.F) "In use in ${s.regulation.name} mode" else "Not used in ${s.regulation.name} mode",
        ) { v -> edit { it.copy(floorSensor = v) } }
        DropdownField(
            "External sensor", SensorType.entries, s.externalSensor, { it.label },
            hint = if (s.regulation == RegulationMode.A2) "In use in A2 mode" else "Only used in A2 mode",
        ) { v -> edit { it.copy(externalSensor = v) } }
    }

    Section("Calibration", subtitle = "If the thermostat shows a different temperature than a thermometer you trust, add the difference here.") {
        val sign = if (s.calibration < 0) "−" else "+"
        SliderRow(
            "Room sensor offset",
            sign + String.format(Locale.getDefault(), "%.1f °C", abs(s.calibration) / 10.0),
            s.calibration, -40..40,
            hint = "Example: the thermostat says 22 °C but the room is 21 °C → set −1.0 °C.",
        ) { v -> edit { it.copy(calibration = v) } }
        if (s.calibration != 0) TextButton(onClick = { edit { it.copy(calibration = 0) } }) { Text("Reset to 0") }
    }

    Section("Temperature limits", subtitle = limitsSubtitle(s.regulation)) {
        Text("Room", style = MaterialTheme.typography.titleSmall)
        SliderRow("Highest room setting (AHi)", formatTemp(s.aHi), s.aHi, ThermostatSettings.A_HI_MIN..ThermostatSettings.LIMIT_MAX_HI) { v ->
            edit { it.copy(aHi = v, aLo = minOf(it.aLo, v)) }
        }
        SliderRow("Lowest room setting (ALo)", formatTemp(s.aLo), s.aLo, ThermostatSettings.A_LO_MIN..ThermostatSettings.LIMIT_MAX_LO) { v ->
            edit { it.copy(aLo = v, aHi = maxOf(it.aHi, v)) }
        }
        HorizontalDivider()
        Text("Floor", style = MaterialTheme.typography.titleSmall)
        SliderRow("Maximum floor temperature (FHi)", formatTemp(s.fHi), s.fHi, ThermostatSettings.A_HI_MIN..ThermostatSettings.LIMIT_MAX_HI,
            hint = "Check the floor maker's limit – wood is often 27 °C.") { v ->
            edit { it.copy(fHi = v, fLo = minOf(it.fLo, v)) }
        }
        SliderRow("Minimum floor temperature (FLo)", formatTemp(s.fLo), s.fLo, ThermostatSettings.A_LO_MIN..ThermostatSettings.LIMIT_MAX_LO) { v ->
            edit { it.copy(fLo = v, fHi = maxOf(it.fHi, v)) }
        }
        Hint("Home, Away and preset temperatures outside the limits are moved inside them automatically.")
    }

    Section("Display and buttons") {
        SliderRow(
            "Brightness", if (s.brightness >= 10) "Auto" else "${s.brightness}", s.brightness, 0..10,
            hint = "Slide all the way right for Auto, which adjusts to the light in the room.",
        ) { v -> edit { it.copy(brightness = v) } }
        SwitchRow("Display on", s.displayOn, hint = "Keeps the display lit. (The original app doesn't explain this setting; this is our best understanding.)") { v ->
            edit { it.copy(displayOn = v) }
        }
        SwitchRow("Child lock", s.childLock, hint = "Locks the buttons on the thermostat. You can still change everything with this app.") { v ->
            edit { it.copy(childLock = v) }
        }
    }

    RawDataSection(state.lastRaw, vm.previewPayload())

    Section("About") {
        state.lastRead?.let { r ->
            r.serial?.let { Text("Thermostat serial: $it", style = MaterialTheme.typography.bodyMedium) }
            r.firmware?.let { Text("Firmware: $it", style = MaterialTheme.typography.bodyMedium) }
        }
        Hint(
            "Works with Heatit NFC thermostats (product code ou047). The app reads and writes the thermostat's NFC tag " +
                "directly; nothing is sent to the internet. Presets are stored on this phone – the thermostat only stores the resulting week.",
        )
    }
}

private fun limitsSubtitle(mode: RegulationMode) = when (mode) {
    RegulationMode.A, RegulationMode.A2 -> "Room limits set the range you can choose for Home, Away and presets."
    RegulationMode.AF -> "Room limits set the range you can choose; floor limits protect the floor."
    RegulationMode.F -> "Floor limits set the range you can choose in floor sensor mode."
}

@Composable
private fun RawDataSection(lastRaw: String?, preview: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Section("Raw NFC data", subtitle = "For troubleshooting: the exact text stored on the thermostat.") {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Last read from thermostat", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(lastRaw ?: "—", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text("Will be sent", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(preview, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
