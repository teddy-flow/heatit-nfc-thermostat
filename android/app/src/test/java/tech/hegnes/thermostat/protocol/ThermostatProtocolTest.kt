package tech.hegnes.thermostat.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ThermostatProtocolTest {

    private val weekday = DaySchedule(36, listOf(Period(7 * 60, 8 * 60, 45), Period(16 * 60, 23 * 60, 45)))
    private val weekend = DaySchedule(45)
    private val settings = ThermostatSettings(
        mode = Mode.SCHEDULE, comfort = 45, eco = 36,
        regulation = RegulationMode.A, floorSensor = SensorType.K10, externalSensor = SensorType.K10,
        calibration = 0, brightness = 5, aHi = 80, aLo = 10, fHi = 54, fLo = 10,
        childLock = false, displayOn = true, adaptiveStart = false,
        schedule = List(5) { weekday } + List(2) { weekend },
    )

    /** Same message as the reference example in the documentation, produced by the original app's algorithm. */
    private val reference = """
        nfc
        pr:ou047
        md:3
        sp:2d,24
        op:0
        st:0,0
        ca:00
        br:5
        lm:50,0a,36,0a
        cl:0
        ds:1
        as:0
        d0:24,1a42d,1e024,3c02d,56424,fffff,fffff
        d1:24,1a42d,1e024,3c02d,56424,fffff,fffff
        d2:24,1a42d,1e024,3c02d,56424,fffff,fffff
        d3:24,1a42d,1e024,3c02d,56424,fffff,fffff
        d4:24,1a42d,1e024,3c02d,56424,fffff,fffff
        d5:2d,fffff,fffff,fffff,fffff,fffff,fffff
        d6:2d,fffff,fffff,fffff,fffff,fffff,fffff
        tm:0e,1e,00,5
        rc:61c7
    """.trimIndent()

    @Test
    fun crcMatchesCcittFalseCheckValue() {
        assertEquals(0x29B1, ThermostatProtocol.crc16("123456789".toByteArray()))
    }

    @Test
    fun encodeMatchesReference() {
        val saturday = LocalDateTime.of(2026, 10, 10, 14, 30, 0)
        assertEquals(reference, ThermostatProtocol.encode(settings, saturday))
    }

    @Test
    fun decodeRoundTrips() {
        val r = ThermostatProtocol.decode(reference)
        assertEquals(settings, r.settings)
        assertEquals("nfc", r.author)
        assertTrue(r.hasAdvanced)
        assertEquals(true, r.crcOk)
        assertEquals("0e,1e,00,5", r.clock)
    }

    @Test
    fun detectsBadCrc() {
        assertEquals(false, ThermostatProtocol.decode(reference.replace("rc:61c7", "rc:0000")).crcOk)
    }

    @Test
    fun negativeCalibrationAndAutoBrightness() {
        val s = settings.copy(calibration = -15, brightness = 10, floorSensor = SensorType.K6_8)
        val text = ThermostatProtocol.encode(s)
        assertTrue(text.contains("ca:f1\n"))
        assertTrue(text.contains("br:a\n"))
        assertTrue(text.contains("st:6,0\n"))
        assertEquals(s, ThermostatProtocol.decode(text).settings)
    }

    @Test
    fun adjacentPeriodsShareSwitchPoint() {
        val day = DaySchedule(36, listOf(Period(360, 480, 44), Period(480, 600, 46)))
        assertEquals("24,1682c,1e02e,25824,fffff,fffff,fffff", ThermostatProtocol.encodeDay(day))
        assertEquals(day, ThermostatProtocol.decodeDay("24,1682c,1e02e,25824,fffff,fffff,fffff"))
    }

    @Test
    fun periodRunningToMidnight() {
        val day = DaySchedule(36, listOf(Period(1200, 1440, 44)))
        val wire = ThermostatProtocol.encodeDay(day)
        assertEquals("24,4b02c,5a024,fffff,fffff,fffff,fffff", wire)
        assertEquals(day, ThermostatProtocol.decodeDay(wire))
    }

    @Test
    fun presetWithoutAdvancedBlockKeepsFallback() {
        val preset = ThermostatProtocol.encode(settings.copy(mode = Mode.ECO), includeAdvanced = false)
        val fallback = settings.copy(childLock = true)
        val r = ThermostatProtocol.decode(preset, fallback)
        assertFalse(r.hasAdvanced)
        assertEquals(Mode.ECO, r.settings.mode)
        assertTrue(r.settings.childLock)
    }

    @Test(expected = ProtocolException::class)
    fun rejectsOtherProducts() {
        ThermostatProtocol.decode(reference.replace("pr:ou047", "pr:xx001"))
    }

    @Test
    fun clampsToLimits() {
        val s = settings.copy(regulation = RegulationMode.F, fHi = 50, fLo = 20, comfort = 60, eco = 10).clamped()
        assertEquals(50, s.comfort)
        assertEquals(20, s.eco)
    }

    /** Captured from a real Heatit thermostat (author "i2c"); serial replaced with a placeholder and CRC recomputed. */
    @Test
    fun decodesRealDevicePayload() {
        val raw = "i2c\npr:ou047\nvn:01,01,00\nsn:1234567890\nmd:2\nsp:32,32\nop:2\nst:0,0\nca:00\nbr:3\n" +
            "lm:50,0a,50,0a\ncl:0\nds:1\nas:0\n" +
            (0..6).joinToString("") { "d$it:2a,fffff,fffff,fffff,fffff,fffff,fffff\n" } + "rc:c657"
        val r = ThermostatProtocol.decode(raw)
        assertEquals("i2c", r.author)
        assertEquals(true, r.crcOk)
        assertEquals("1.1.0", r.firmware)
        assertEquals("1234567890", r.serial)
        assertEquals(Mode.ECO, r.settings.mode)
        assertEquals(RegulationMode.F, r.settings.regulation)
        assertEquals(50, r.settings.comfort)
        assertEquals(DaySchedule(42), r.settings.schedule[3])
    }
}
