package tech.hegnes.thermostat.protocol

import java.time.LocalDateTime

/**
 * Text protocol used by Heatit NFC thermostats (product code "ou047").
 *
 * The thermostat exposes one NDEF Text record holding `key:value` lines. Temperatures are
 * hex in 0.5 °C units, calibration is a signed byte in 0.1 °C units, and every write ends
 * with `rc:` + CRC-16/CCITT-FALSE over everything up to and including the literal "rc:".
 */
enum class Mode(val code: Int, val label: String) {
    OFF(0, "Off"),
    COMFORT(1, "Comfort"),
    ECO(2, "Eco"),
    SCHEDULE(3, "Schedule");

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: OFF
    }
}

enum class RegulationMode(val code: Int, val label: String) {
    A(0, "A – room sensor"),
    AF(1, "AF – room sensor, floor limits"),
    F(2, "F – floor sensor"),
    A2(3, "A2 – external sensor");

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: A
    }
}

/** Wire codes for NTC sensor types. Code 6 (6.8 kΩ) is listed first, as in the original app. */
enum class SensorType(val code: Int, val label: String) {
    K6_8(6, "6.8 kΩ"),
    K10(0, "10 kΩ"),
    K12(1, "12 kΩ"),
    K15(2, "15 kΩ"),
    K22(3, "22 kΩ"),
    K33(4, "33 kΩ"),
    K47(5, "47 kΩ");

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: K6_8
    }
}

/** A heating period: [start, end) in minutes after midnight (end 1440 = midnight), temp in 0.5 °C units. */
data class Period(val start: Int, val end: Int, val temp: Int)

/** A day's schedule: base temperature from 00:00, plus up to 3 periods at another temperature. */
data class DaySchedule(val base: Int, val periods: List<Period> = emptyList()) {
    companion object {
        const val MAX_PERIODS = 3
    }
}

data class ThermostatSettings(
    val mode: Mode = Mode.COMFORT,
    val comfort: Int = 44,
    val eco: Int = 36,
    val regulation: RegulationMode = RegulationMode.A,
    val floorSensor: SensorType = SensorType.K10,
    val externalSensor: SensorType = SensorType.K10,
    /** 0.1 °C units, -40..40. */
    val calibration: Int = 0,
    /** 0..9, 10 = automatic. */
    val brightness: Int = 5,
    val aHi: Int = 56,
    val aLo: Int = 10,
    val fHi: Int = 54,
    val fLo: Int = 10,
    val childLock: Boolean = false,
    val displayOn: Boolean = true,
    val adaptiveStart: Boolean = false,
    val schedule: List<DaySchedule> = List(7) { DaySchedule(base = 44) },
) {
    /** Allowed setpoint range in 0.5 °C units for the current regulation mode. */
    val setpointRange: IntRange
        get() = when (regulation) {
            RegulationMode.A, RegulationMode.AF -> aLo..aHi
            RegulationMode.F -> fLo..fHi
            RegulationMode.A2 -> 10..80
        }

    /** Clamp setpoints and schedule temperatures into [setpointRange], as the original app does. */
    fun clamped(): ThermostatSettings {
        val r = setpointRange
        return copy(
            comfort = comfort.coerceIn(r),
            eco = eco.coerceIn(r),
            schedule = schedule.map { day ->
                day.copy(
                    base = day.base.coerceIn(r),
                    periods = day.periods.map { it.copy(temp = it.temp.coerceIn(r)) },
                )
            },
        )
    }

    companion object {
        const val A_HI_MIN = 14
        const val A_LO_MIN = 10
        const val LIMIT_MAX_HI = 80
        const val LIMIT_MAX_LO = 76
    }
}

class ProtocolException(message: String) : Exception(message)

data class DecodeResult(
    val settings: ThermostatSettings,
    val author: String,
    /** False when the op/st/ca/br/lm/cl/ds/as block was missing (preset files leave it out). */
    val hasAdvanced: Boolean,
    /** null when no rc: line was present. */
    val crcOk: Boolean?,
    /** Raw tm: value, e.g. "0e,1e,00,5". Only present in data written by the app. */
    val clock: String?,
    /** Firmware version from vn:, e.g. "1.1.0". Only written by the thermostat. */
    val firmware: String? = null,
    /** Serial number from sn:. Only written by the thermostat. */
    val serial: String? = null,
)

object ThermostatProtocol {
    const val PRODUCT = "ou047"
    const val AUTHOR = "nfc"
    private const val EMPTY_POINT = "fffff"

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun encode(
        s: ThermostatSettings,
        now: LocalDateTime = LocalDateTime.now(),
        includeAdvanced: Boolean = true,
    ): String {
        val sb = StringBuilder()
        sb.append(AUTHOR).append('\n')
        sb.append("pr:").append(PRODUCT).append('\n')
        sb.append("md:").append(s.mode.code).append('\n')
        sb.append("sp:").append(hex2(s.comfort)).append(',').append(hex2(s.eco)).append('\n')
        if (includeAdvanced) {
            sb.append("op:").append(s.regulation.code).append('\n')
            sb.append("st:").append(s.floorSensor.code).append(',').append(s.externalSensor.code).append('\n')
            sb.append("ca:").append(hex2(s.calibration and 0xFF)).append('\n')
            sb.append("br:").append(Integer.toHexString(s.brightness)).append('\n')
            sb.append("lm:").append(listOf(s.aHi, s.aLo, s.fHi, s.fLo).joinToString(",") { hex2(it) }).append('\n')
            sb.append("cl:").append(if (s.childLock) 1 else 0).append('\n')
            sb.append("ds:").append(if (s.displayOn) 1 else 0).append('\n')
            sb.append("as:").append(if (s.adaptiveStart) 1 else 0).append('\n')
        }
        s.schedule.forEachIndexed { i, day -> sb.append('d').append(i).append(':').append(encodeDay(day)).append('\n') }
        sb.append("tm:")
            .append(hex2(now.hour)).append(',')
            .append(hex2(now.minute)).append(',')
            .append(hex2(now.second)).append(',')
            .append(now.dayOfWeek.value - 1)
            .append('\n')
        sb.append("rc:")
        sb.append("%04x".format(crc16(sb.toString().toByteArray(Charsets.UTF_8))))
        return sb.toString()
    }

    fun encodeDay(day: DaySchedule): String {
        val periods = day.periods.filter { it.temp != day.base && it.end > it.start }.sortedBy { it.start }
        val points = mutableListOf<Pair<Int, Int>>()
        periods.forEachIndexed { i, p ->
            val next = periods.getOrNull(i + 1)
            val end = if (next != null && next.start < p.end) next.start else p.end
            points += p.start to p.temp
            if (next == null || next.start != end) points += end to day.base
        }
        require(points.size <= 6) { "A day holds at most 6 switch points" }
        val fields = mutableListOf(hex2(day.base))
        points.forEach { (time, temp) -> fields += "%03x%02x".format(time, temp) }
        while (fields.size < 7) fields += EMPTY_POINT
        return fields.joinToString(",")
    }

    fun decodeDay(value: String): DaySchedule {
        val fields = value.trim().split(',')
        val base = fields.first().take(2).toInt(16)
        val points = fields.drop(1)
            .filter { it.length == 5 && !it.equals(EMPTY_POINT, ignoreCase = true) }
            .map { it.substring(0, 3).toInt(16) to it.substring(3).toInt(16) }
        val periods = mutableListOf<Period>()
        points.forEachIndexed { i, (time, temp) ->
            if (temp != base && time < 1440) {
                val end = points.getOrNull(i + 1)?.first ?: 1440
                periods += Period(time, end.coerceAtMost(1440), temp)
            }
        }
        return DaySchedule(base, periods.take(DaySchedule.MAX_PERIODS))
    }

    /**
     * Parse a payload. Missing lines keep the values from [fallback], matching the original app,
     * which only overwrites fields whose line is present.
     */
    fun decode(text: String, fallback: ThermostatSettings = ThermostatSettings()): DecodeResult {
        val lines = text.split('\n').map { it.trimEnd('\r') }
        fun value(key: String): String? {
            val tag = "$key:"
            lines.firstOrNull { it.startsWith(tag) }?.let { return it.substring(3).trim() }
            return lines.firstOrNull { it.contains(tag) }?.let { it.substring(it.indexOf(tag) + 3).trim() }
        }

        val product = value("pr") ?: throw ProtocolException("No product code (pr:) found – not a Heatit thermostat?")
        if (product != PRODUCT) throw ProtocolException("Unsupported product code \"$product\" (expected $PRODUCT)")

        try {
            var s = fallback
            value("md")?.let { s = s.copy(mode = Mode.fromCode(it.toInt())) }
            value("sp")?.split(',')?.let { s = s.copy(comfort = it[0].toInt(16), eco = it[1].toInt(16)) }

            val op = value("op")
            op?.let { s = s.copy(regulation = RegulationMode.fromCode(it.toInt())) }
            value("st")?.split(',')?.let {
                s = s.copy(floorSensor = SensorType.fromCode(it[0].trim().toInt()), externalSensor = SensorType.fromCode(it[1].trim().toInt()))
            }
            value("ca")?.let { s = s.copy(calibration = it.toInt(16).toByte().toInt()) }
            value("br")?.let { s = s.copy(brightness = it.toInt(16)) }
            value("lm")?.split(',')?.map { it.toInt(16) }?.let { s = s.copy(aHi = it[0], aLo = it[1], fHi = it[2], fLo = it[3]) }
            value("cl")?.let { s = s.copy(childLock = it.toInt() != 0) }
            value("ds")?.let { s = s.copy(displayOn = it.toInt() != 0) }
            value("as")?.let { s = s.copy(adaptiveStart = it.toInt() != 0) }

            val schedule = s.schedule.toMutableList()
            for (d in 0..6) value("d$d")?.let { schedule[d] = decodeDay(it) }
            s = s.copy(schedule = schedule)

            val crcOk = text.indexOf("rc:").takeIf { it >= 0 }?.let { idx ->
                val expected = text.substring(idx + 3).take(4).toIntOrNull(16)
                expected != null && crc16(text.substring(0, idx + 3).toByteArray(Charsets.UTF_8)) == expected
            }
            val firmware = value("vn")?.split(',')?.joinToString(".") { it.trim().toIntOrNull(16)?.toString() ?: it.trim() }
            return DecodeResult(s, lines.first(), op != null, crcOk, value("tm"), firmware, value("sn"))
        } catch (e: RuntimeException) {
            throw ProtocolException("Malformed thermostat data: ${e.message}")
        }
    }

    private fun hex2(v: Int) = "%02x".format(v)
}
