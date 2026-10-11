package tech.hegnes.thermostat.schedule

import tech.hegnes.thermostat.protocol.DaySchedule
import tech.hegnes.thermostat.protocol.Mode
import tech.hegnes.thermostat.protocol.Period
import java.util.UUID

/**
 * Day presets live only in the app. Each one is turned into a [DaySchedule] (base temperature +
 * up to 3 periods) when the week is written to the thermostat.
 *
 * Temperatures can follow the Home (comfort) or Away (eco) setpoint, so changing Home from
 * 21 °C to 22 °C updates every preset that uses it.
 */
enum class TempLevel { HOME, AWAY, CUSTOM }

data class TempChoice(val level: TempLevel, val custom: Int = 40) {
    fun resolve(home: Int, away: Int): Int = when (level) {
        TempLevel.HOME -> home
        TempLevel.AWAY -> away
        TempLevel.CUSTOM -> custom
    }

    companion object {
        val Home = TempChoice(TempLevel.HOME)
        val Away = TempChoice(TempLevel.AWAY)

        fun infer(temp: Int, home: Int, away: Int): TempChoice = when (temp) {
            home -> Home
            away -> Away
            else -> TempChoice(TempLevel.CUSTOM, temp)
        }
    }
}

/** A block of time with its own temperature, [start, end) in minutes; end 1440 = midnight. */
data class PresetBlock(val label: String, val start: Int, val end: Int, val temp: TempChoice)

data class DayPreset(
    val id: String,
    val name: String,
    /** Temperature outside the blocks (night and while away). */
    val base: TempChoice,
    val blocks: List<PresetBlock>,
) {
    fun toDaySchedule(home: Int, away: Int, range: IntRange): DaySchedule {
        val baseTemp = base.resolve(home, away).coerceIn(range)
        val periods = blocks.sortedBy { it.start }
            .map { Period(it.start, it.end, it.temp.resolve(home, away).coerceIn(range)) }
            // A block at the base temperature is invisible on the wire, so drop it here too.
            .filter { it.temp != baseTemp }
        return DaySchedule(baseTemp, periods)
    }

    /** Returns a human-readable problem, or null when the preset can be saved. */
    fun validationError(): String? {
        if (name.isBlank()) return "Give the preset a name"
        if (blocks.size > DaySchedule.MAX_PERIODS) return "The thermostat stores at most ${DaySchedule.MAX_PERIODS} blocks per day"
        blocks.forEach { if (it.end <= it.start) return "\"${it.label.ifBlank { "A block" }}\" ends before it starts" }
        blocks.sortedBy { it.start }.zipWithNext().forEach { (a, b) ->
            if (b.start < a.end) return "\"${a.label.ifBlank { "A block" }}\" overlaps \"${b.label.ifBlank { "the next block" }}\""
        }
        return null
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

data class PresetTemplate(val title: String, val description: String, val preset: DayPreset)

object Presets {
    private fun t(h: Int, m: Int = 0) = h * 60 + m

    val templates: List<PresetTemplate>
        get() = listOf(
            PresetTemplate(
                "Office day",
                "Warm while you get ready, cooler while you're at work, warm again in the evening.",
                DayPreset(
                    DayPreset.newId(), "Office day", TempChoice.Away,
                    listOf(
                        PresetBlock("Morning", t(6), t(8), TempChoice.Home),
                        PresetBlock("Evening", t(16), t(22, 30), TempChoice.Home),
                    ),
                ),
            ),
            PresetTemplate(
                "Home all day",
                "Warm from morning to bedtime, cooler at night. Good for weekends or working from home.",
                DayPreset(
                    DayPreset.newId(), "Home all day", TempChoice.Away,
                    listOf(PresetBlock("Day", t(7), t(23), TempChoice.Home)),
                ),
            ),
            PresetTemplate(
                "Lazy weekend",
                "Later start and a late evening.",
                DayPreset(
                    DayPreset.newId(), "Weekend", TempChoice.Away,
                    listOf(PresetBlock("Day", t(8, 30), t(23, 30), TempChoice.Home)),
                ),
            ),
            PresetTemplate(
                "Home for lunch",
                "Morning, lunch break and evening warm – uses all three blocks.",
                DayPreset(
                    DayPreset.newId(), "Home for lunch", TempChoice.Away,
                    listOf(
                        PresetBlock("Morning", t(6), t(8), TempChoice.Home),
                        PresetBlock("Lunch", t(11, 30), t(13), TempChoice.Home),
                        PresetBlock("Evening", t(16), t(22, 30), TempChoice.Home),
                    ),
                ),
            ),
            PresetTemplate(
                "Always warm",
                "Home temperature all day and night.",
                DayPreset(DayPreset.newId(), "Always warm", TempChoice.Home, emptyList()),
            ),
            PresetTemplate(
                "Blank",
                "Start from scratch.",
                DayPreset(DayPreset.newId(), "", TempChoice.Away, emptyList()),
            ),
        )

    /** First-run presets: office days Monday–Friday, home days at the weekend. */
    fun defaults(): Pair<List<DayPreset>, List<String>> {
        val work = templates[0].preset
        val weekend = templates[2].preset.copy(name = "Weekend")
        return listOf(work, weekend) to List(7) { if (it < 5) work.id else weekend.id }
    }

    /**
     * Maps each day of a schedule read from the thermostat to a preset. Days that match no existing
     * preset become new presets, with temperatures linked to Home/Away where they match.
     */
    fun matchOrImport(
        schedule: List<DaySchedule>,
        home: Int,
        away: Int,
        range: IntRange,
        presets: List<DayPreset>,
    ): Pair<List<DayPreset>, List<String>> {
        val result = presets.toMutableList()
        val plan = MutableList(7) { "" }
        for (day in 0..6) {
            val existing = result.firstOrNull { it.toDaySchedule(home, away, range) == schedule[day] }
            if (existing != null) {
                plan[day] = existing.id
                continue
            }
            val sameDays = (0..6).filter { schedule[it] == schedule[day] }
            val s = schedule[day]
            val imported = DayPreset(
                id = DayPreset.newId(),
                name = if (s.periods.isEmpty()) "Thermostat: ${s.base / 2.0} °C all day" else "From thermostat (${daysLabel(sameDays)})",
                base = TempChoice.infer(s.base, home, away),
                blocks = s.periods.mapIndexed { i, p ->
                    PresetBlock("Block ${i + 1}", p.start, p.end, TempChoice.infer(p.temp, home, away))
                },
            )
            result += imported
            plan[day] = imported.id
        }
        return result to plan
    }
}

/**
 * A whole-week setup you can load in one tap, e.g. "Normal week" or "Away on holiday".
 * [plan] is a preset id per day; null keeps the current week (useful for Eco/Off profiles).
 */
data class WeekProfile(
    val id: String,
    val name: String,
    val mode: Mode,
    val plan: List<String>?,
) {
    fun isActive(mode: Mode, weekPlan: List<String>): Boolean = this.mode == mode && (plan == null || plan == weekPlan)

    fun describe(presets: List<DayPreset>): String = when {
        mode == Mode.OFF -> "Heating off"
        mode == Mode.ECO -> "Holds the Away temperature every day"
        mode == Mode.COMFORT -> "Holds the Home temperature every day"
        plan == null -> "Follows the current week plan"
        else -> plan.indices.groupBy { plan[it] }.entries.joinToString(" · ") { (id, days) ->
            "${presets.firstOrNull { it.id == id }?.name ?: "?"} ${daysLabel(days)}"
        }
    }
}

object Profiles {
    /**
     * Built-in profiles, reusing presets with the same name when they exist and adding the
     * ones that are missing.
     */
    fun defaults(presets: List<DayPreset>): Pair<List<DayPreset>, List<WeekProfile>> {
        val all = presets.toMutableList()
        fun ensure(name: String, template: Int): DayPreset =
            all.firstOrNull { it.name == name } ?: Presets.templates[template].preset.copy(name = name).also { all += it }

        val office = ensure("Office day", 0)
        val weekend = ensure("Weekend", 2)
        val home = all.firstOrNull { it.name == "Home all day" }
            ?: Presets.templates[1].preset.copy(blocks = listOf(PresetBlock("Day", 6 * 60 + 30, 23 * 60, TempChoice.Home))).also { all += it }
        val profiles = listOf(
            WeekProfile(DayPreset.newId(), "Normal week", Mode.SCHEDULE, List(7) { if (it < 5) office.id else weekend.id }),
            WeekProfile(DayPreset.newId(), "Home all week", Mode.SCHEDULE, List(7) { home.id }),
            WeekProfile(DayPreset.newId(), "Away / holiday", Mode.ECO, null),
        )
        return all to profiles
    }
}

val DAY_SHORT = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
val DAY_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** "Mo–Fr", "Sa, Su", "Every day", … */
fun daysLabel(days: Collection<Int>): String {
    val sorted = days.distinct().sorted()
    if (sorted.isEmpty()) return "No days"
    if (sorted.size == 7) return "Every day"
    val runs = mutableListOf<MutableList<Int>>()
    sorted.forEach { d -> if (runs.isNotEmpty() && runs.last().last() == d - 1) runs.last() += d else runs += mutableListOf(d) }
    return runs.joinToString(", ") { r ->
        if (r.size >= 3) "${DAY_SHORT[r.first()]}–${DAY_SHORT[r.last()]}" else r.joinToString(", ") { DAY_SHORT[it] }
    }
}

fun DaySchedule.tempAt(minute: Int): Int = periods.firstOrNull { minute >= it.start && minute < it.end }?.temp ?: base

/** Next time (minute) the target temperature changes after [minute], with the new temperature. */
fun DaySchedule.nextChange(minute: Int): Pair<Int, Int>? {
    val now = tempAt(minute)
    return periods.flatMap { listOf(it.start, it.end) }
        .filter { it in (minute + 1) until 1440 }
        .distinct().sorted()
        .map { it to tempAt(it) }
        .firstOrNull { it.second != now }
}
