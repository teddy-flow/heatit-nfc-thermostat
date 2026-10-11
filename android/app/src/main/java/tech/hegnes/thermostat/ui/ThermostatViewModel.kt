package tech.hegnes.thermostat.ui

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tech.hegnes.thermostat.data.SettingsStore
import tech.hegnes.thermostat.nfc.TagException
import tech.hegnes.thermostat.nfc.ThermostatTag
import tech.hegnes.thermostat.protocol.DecodeResult
import tech.hegnes.thermostat.protocol.ProtocolException
import tech.hegnes.thermostat.protocol.ThermostatProtocol
import tech.hegnes.thermostat.protocol.ThermostatSettings
import tech.hegnes.thermostat.schedule.DayPreset
import tech.hegnes.thermostat.schedule.Presets
import tech.hegnes.thermostat.schedule.Profiles
import tech.hegnes.thermostat.schedule.WeekProfile

enum class PendingOp { NONE, READ, WRITE }

data class Message(val id: Long, val text: String, val isError: Boolean)

data class UiState(
    val settings: ThermostatSettings = ThermostatSettings(),
    val presets: List<DayPreset> = emptyList(),
    /** Preset id per day, Monday first. */
    val weekPlan: List<String> = emptyList(),
    val profiles: List<WeekProfile> = emptyList(),
    val pending: PendingOp = PendingOp.NONE,
    val lastRead: DecodeResult? = null,
    val lastRaw: String? = null,
    val lastReadAt: Long = 0L,
    val message: Message? = null,
) {
    val loadedFromDevice: Boolean get() = lastRead != null

    /** True when the app holds settings the thermostat doesn't have yet. */
    val hasUnsentChanges: Boolean get() = lastRead != null && lastRead.settings != settings

    fun preset(id: String): DayPreset? = presets.firstOrNull { it.id == id }
}

class ThermostatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun loadInitial(): UiState {
        val (defaultPresets, defaultPlan) = Presets.defaults()
        val stored = store.loadPresets()?.takeIf { it.isNotEmpty() } ?: defaultPresets
        val plan = store.loadPlan()?.takeIf { p -> p.all { id -> stored.any { it.id == id } } }
            ?: List(7) { stored.first().id }.let { if (stored === defaultPresets) defaultPlan else it }
        val storedProfiles = store.loadProfiles()
        val (presets, profiles) = if (storedProfiles != null) stored to storedProfiles else Profiles.defaults(stored)
        val lastRaw = store.lastRaw
        val lastRead = lastRaw?.let { runCatching { ThermostatProtocol.decode(it) }.getOrNull() }
        val draft = store.draft?.let { runCatching { ThermostatProtocol.decode(it, lastRead?.settings ?: ThermostatSettings()).settings }.getOrNull() }
        val settings = draft ?: lastRead?.settings ?: ThermostatSettings()
        return UiState(
            settings = withSchedule(settings, presets, plan),
            presets = presets,
            weekPlan = plan,
            profiles = profiles,
            lastRead = lastRead,
            lastRaw = lastRaw,
            lastReadAt = if (lastRead != null) store.lastReadAt else 0L,
        )
    }

    /** Rebuild the 7-day schedule from presets so it always mirrors the week plan. */
    private fun withSchedule(s: ThermostatSettings, presets: List<DayPreset>, plan: List<String>): ThermostatSettings {
        val range = s.setpointRange
        val schedule = s.schedule.mapIndexed { day, current ->
            presets.firstOrNull { it.id == plan.getOrNull(day) }?.toDaySchedule(s.comfort, s.eco, range) ?: current
        }
        return s.copy(schedule = schedule).clamped()
    }

    private fun commit(transform: (UiState) -> UiState) {
        _state.update { old ->
            val next = transform(old)
            next.copy(settings = withSchedule(next.settings, next.presets, next.weekPlan))
        }
        val s = _state.value
        store.savePresets(s.presets)
        store.savePlan(s.weekPlan)
        store.saveProfiles(s.profiles)
        store.draft = ThermostatProtocol.encode(s.settings)
    }

    fun edit(transform: (ThermostatSettings) -> ThermostatSettings) = commit { it.copy(settings = transform(it.settings).clamped()) }

    fun savePreset(preset: DayPreset) = commit { st ->
        val exists = st.presets.any { it.id == preset.id }
        st.copy(presets = if (exists) st.presets.map { if (it.id == preset.id) preset else it } else st.presets + preset)
    }

    fun deletePreset(id: String) = commit { st ->
        val remaining = st.presets.filter { it.id != id }
        if (remaining.isEmpty()) return@commit st
        val fallback = remaining.first().id
        st.copy(
            presets = remaining,
            weekPlan = st.weekPlan.map { if (it == id) fallback else it },
            profiles = st.profiles.map { p -> p.copy(plan = p.plan?.map { if (it == id) fallback else it }) },
        )
    }

    fun assignPreset(day: Int, id: String) = commit { st ->
        st.copy(weekPlan = st.weekPlan.toMutableList().also { it[day] = id })
    }

    fun assignPresetToDays(id: String, days: Set<Int>) = commit { st ->
        st.copy(weekPlan = st.weekPlan.mapIndexed { day, current -> if (day in days) id else current })
    }

    /** Load a whole-week profile into the app; it reaches the thermostat on the next Send. */
    fun applyProfile(profile: WeekProfile) {
        commit { st ->
            val plan = profile.plan?.takeIf { p -> p.all { id -> st.presets.any { it.id == id } } } ?: st.weekPlan
            st.copy(weekPlan = plan, settings = st.settings.copy(mode = profile.mode))
        }
        post(if (_state.value.hasUnsentChanges) "\"${profile.name}\" loaded – tap Send to apply it" else "\"${profile.name}\" is already on the thermostat")
    }

    fun saveCurrentAsProfile(name: String) = commit { st ->
        st.copy(profiles = st.profiles + WeekProfile(DayPreset.newId(), name.trim(), st.settings.mode, st.weekPlan))
    }

    fun deleteProfile(id: String) = commit { st -> st.copy(profiles = st.profiles.filter { it.id != id }) }

    /** Throw away edits and go back to what was last read from the thermostat. */
    fun discardChanges() {
        val read = _state.value.lastRead ?: return
        commit { st ->
            val (presets, plan) = Presets.matchOrImport(read.settings.schedule, read.settings.comfort, read.settings.eco, read.settings.setpointRange, st.presets)
            st.copy(settings = read.settings, presets = presets, weekPlan = plan)
        }
        post("Changes discarded")
    }

    fun armRead() = _state.update { it.copy(pending = PendingOp.READ) }
    fun armWrite() = _state.update { it.copy(pending = PendingOp.WRITE) }
    fun cancel() = _state.update { it.copy(pending = PendingOp.NONE) }

    fun previewPayload(): String = ThermostatProtocol.encode(_state.value.settings)

    /** Called on the NFC reader thread. A tag seen while nothing is armed is treated as a read. */
    fun onTag(tag: Tag) {
        val op = _state.value.pending
        try {
            val raw: String
            val result: DecodeResult
            if (op == PendingOp.WRITE) {
                raw = ThermostatTag.write(tag, ThermostatProtocol.encode(_state.value.settings))
                result = ThermostatProtocol.decode(raw)
            } else {
                raw = ThermostatTag.read(tag)
                result = ThermostatProtocol.decode(raw, _state.value.settings)
            }
            val now = System.currentTimeMillis()
            store.lastRaw = raw
            store.lastReadAt = now
            commit { st ->
                val s = result.settings
                val (presets, plan) = Presets.matchOrImport(s.schedule, s.comfort, s.eco, s.setpointRange, st.presets)
                st.copy(
                    settings = s, presets = presets, weekPlan = plan,
                    pending = PendingOp.NONE, lastRead = result, lastRaw = raw, lastReadAt = now,
                )
            }
            post(
                when {
                    op == PendingOp.WRITE -> "Sent to thermostat and verified"
                    result.crcOk == false -> "Read, but the checksum didn't match – read again to be sure"
                    else -> "Settings loaded from thermostat"
                },
            )
        } catch (e: TagException) {
            post(e.message ?: "NFC error", error = true)
        } catch (e: ProtocolException) {
            post(e.message ?: "Unrecognised data", error = true)
        }
    }

    private fun post(text: String, error: Boolean = false) {
        _state.update { it.copy(message = Message(System.nanoTime(), text, error)) }
    }
}
