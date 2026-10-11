package tech.hegnes.thermostat.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tech.hegnes.thermostat.protocol.Mode
import tech.hegnes.thermostat.schedule.DayPreset
import tech.hegnes.thermostat.schedule.PresetBlock
import tech.hegnes.thermostat.schedule.TempChoice
import tech.hegnes.thermostat.schedule.TempLevel
import tech.hegnes.thermostat.schedule.WeekProfile

/** Persists presets, the week plan, the last payload read from the thermostat and unsent edits. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("heatit", Context.MODE_PRIVATE)

    var lastRaw: String?
        get() = prefs.getString(KEY_LAST_RAW, null)
        set(v) = prefs.edit().putString(KEY_LAST_RAW, v).apply()

    var lastReadAt: Long
        get() = prefs.getLong(KEY_LAST_READ_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_READ_AT, v).apply()

    /** The edited settings, encoded in the thermostat's own text format. */
    var draft: String?
        get() = prefs.getString(KEY_DRAFT, null)
        set(v) = prefs.edit().putString(KEY_DRAFT, v).apply()

    fun loadPresets(): List<DayPreset>? = runCatching {
        val arr = JSONArray(prefs.getString(KEY_PRESETS, null) ?: return null)
        (0 until arr.length()).map { presetFromJson(arr.getJSONObject(it)) }
    }.getOrNull()

    fun savePresets(presets: List<DayPreset>) {
        prefs.edit().putString(KEY_PRESETS, JSONArray(presets.map(::presetToJson)).toString()).apply()
    }

    fun loadPlan(): List<String>? = runCatching {
        val arr = JSONArray(prefs.getString(KEY_PLAN, null) ?: return null)
        List(arr.length()) { arr.getString(it) }.takeIf { it.size == 7 }
    }.getOrNull()

    fun savePlan(plan: List<String>) {
        prefs.edit().putString(KEY_PLAN, JSONArray(plan).toString()).apply()
    }

    fun loadProfiles(): List<WeekProfile>? = runCatching {
        val arr = JSONArray(prefs.getString(KEY_PROFILES, null) ?: return null)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val plan = o.optJSONArray("plan")?.let { p -> List(p.length()) { i -> p.getString(i) } }
            WeekProfile(o.getString("id"), o.getString("name"), Mode.valueOf(o.getString("mode")), plan)
        }
    }.getOrNull()

    fun saveProfiles(profiles: List<WeekProfile>) {
        val arr = JSONArray(profiles.map { p ->
            JSONObject().put("id", p.id).put("name", p.name).put("mode", p.mode.name)
                .apply { p.plan?.let { put("plan", JSONArray(it)) } }
        })
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    private fun choiceToJson(c: TempChoice) = JSONObject().put("level", c.level.name).put("custom", c.custom)
    private fun choiceFromJson(o: JSONObject) = TempChoice(TempLevel.valueOf(o.getString("level")), o.getInt("custom"))

    private fun presetToJson(p: DayPreset) = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("base", choiceToJson(p.base))
        .put("blocks", JSONArray(p.blocks.map {
            JSONObject().put("label", it.label).put("start", it.start).put("end", it.end).put("temp", choiceToJson(it.temp))
        }))

    private fun presetFromJson(o: JSONObject): DayPreset {
        val blocks = o.getJSONArray("blocks")
        return DayPreset(
            id = o.getString("id"),
            name = o.getString("name"),
            base = choiceFromJson(o.getJSONObject("base")),
            blocks = (0 until blocks.length()).map {
                val b = blocks.getJSONObject(it)
                PresetBlock(b.getString("label"), b.getInt("start"), b.getInt("end"), choiceFromJson(b.getJSONObject("temp")))
            },
        )
    }

    private companion object {
        const val KEY_LAST_RAW = "last_raw"
        const val KEY_LAST_READ_AT = "last_read_at"
        const val KEY_DRAFT = "draft"
        const val KEY_PRESETS = "presets"
        const val KEY_PLAN = "plan"
        const val KEY_PROFILES = "profiles"
    }
}
