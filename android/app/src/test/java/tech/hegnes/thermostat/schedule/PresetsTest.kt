package tech.hegnes.thermostat.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tech.hegnes.thermostat.protocol.DaySchedule
import tech.hegnes.thermostat.protocol.Period
import tech.hegnes.thermostat.protocol.ThermostatProtocol

class PresetsTest {
    private val range = 10..80
    private val office = DayPreset(
        "office", "Office", TempChoice.Away,
        listOf(
            PresetBlock("Morning", 360, 480, TempChoice.Home),
            PresetBlock("Evening", 960, 1350, TempChoice.Home),
        ),
    )

    @Test
    fun resolvesHomeAndAway() {
        assertEquals(
            DaySchedule(36, listOf(Period(360, 480, 44), Period(960, 1350, 44))),
            office.toDaySchedule(home = 44, away = 36, range = range),
        )
    }

    @Test
    fun followsHomeTemperatureChanges() {
        assertEquals(46, office.toDaySchedule(46, 36, range).periods.first().temp)
    }

    @Test
    fun blocksAtBaseTemperatureAreDropped() {
        assertEquals(DaySchedule(40), office.toDaySchedule(40, 40, range))
    }

    @Test
    fun scheduleSurvivesWireRoundTripAndMatchesPreset() {
        val wire = ThermostatProtocol.encodeDay(office.toDaySchedule(44, 36, range))
        val decoded = ThermostatProtocol.decodeDay(wire)
        val (presets, plan) = Presets.matchOrImport(List(7) { decoded }, 44, 36, range, listOf(office))
        assertEquals(1, presets.size)
        assertEquals(List(7) { "office" }, plan)
    }

    @Test
    fun importsUnknownDaysOncePerDistinctSchedule() {
        val weekend = DaySchedule(36, listOf(Period(480, 1380, 44)))
        val other = DaySchedule(30, listOf(Period(600, 660, 50)))
        val schedule = List(5) { office.toDaySchedule(44, 36, range) } + weekend + other
        val (presets, plan) = Presets.matchOrImport(schedule, 44, 36, range, listOf(office))
        assertEquals(3, presets.size)
        val imported = presets.first { it.id == plan[5] }
        assertEquals("From thermostat (Sa)", imported.name)
        assertEquals(TempChoice.Away, imported.base)
        assertEquals(TempChoice.Home, imported.blocks.single().temp)
        val custom = presets.first { it.id == plan[6] }
        assertEquals(TempChoice(TempLevel.CUSTOM, 30), custom.base)
    }

    @Test
    fun validation() {
        assertNull(office.validationError())
        assertNotNull(office.copy(name = " ").validationError())
        assertNotNull(office.copy(blocks = office.blocks + PresetBlock("Late", 1300, 1400, TempChoice.Home)).validationError())
        assertNotNull(office.copy(blocks = listOf(PresetBlock("Bad", 600, 500, TempChoice.Home))).validationError())
    }

    @Test
    fun daysLabels() {
        assertEquals("Mo–Fr", daysLabel(listOf(0, 1, 2, 3, 4)))
        assertEquals("Sa, Su", daysLabel(listOf(5, 6)))
        assertEquals("Every day", daysLabel((0..6).toList()))
        assertEquals("Mo, We–Fr", daysLabel(listOf(0, 2, 3, 4)))
    }

    @Test
    fun nextChange() {
        val day = office.toDaySchedule(44, 36, range)
        assertEquals(44, day.tempAt(400))
        assertEquals(480 to 36, day.nextChange(400))
        assertEquals(960 to 44, day.nextChange(600))
        assertNull(day.nextChange(1400))
    }

    @Test
    fun defaultProfilesReuseExistingPresets() {
        val (presets, profiles) = Profiles.defaults(listOf(office.copy(name = "Office day")))
        assertEquals(listOf("Office day", "Weekend", "Home all day"), presets.map { it.name })
        assertEquals(List(5) { "office" }, profiles[0].plan!!.take(5))
        assertEquals(tech.hegnes.thermostat.protocol.Mode.ECO, profiles[2].mode)
        assertNull(profiles[2].plan)
        assertEquals("Office day Mo–Fr · Weekend Sa, Su", profiles[0].describe(presets))
        val distinct = presets.map { it.toDaySchedule(44, 36, range) }.distinct()
        assertEquals(3, distinct.size)
    }
}
