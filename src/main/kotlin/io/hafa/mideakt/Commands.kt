package io.hafa.mideakt

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/**
 * The air conditioner's operating mode.
 *
 * The named values cover the modes Midea units report; a unit may report another value,
 * which is kept rather than dropped.
 */
@JvmInline
value class OperationalMode(
    /** The 3-bit mode value on the wire; only the low 3 bits are sent. */
    val raw: Int,
) {
    /** The mode's name, such as `COOL`, or `mode(7)` for an unnamed value. */
    override fun toString(): String = NAMES[raw] ?: "mode($raw)"

    /** The named modes. */
    companion object {
        /** The unit picks heating or cooling itself. */
        val AUTO = OperationalMode(1)

        /** Cooling. */
        val COOL = OperationalMode(2)

        /** Dehumidifying. */
        val DRY = OperationalMode(3)

        /** Heating. */
        val HEAT = OperationalMode(4)

        /** Fan only, no heating or cooling. */
        val FAN_ONLY = OperationalMode(5)

        /** Dehumidifying toward [SetState.targetHumidity]. */
        val SMART_DRY = OperationalMode(6)

        /** The named modes. */
        val entries: List<OperationalMode> = listOf(AUTO, COOL, DRY, HEAT, FAN_ONLY, SMART_DRY)

        private val NAMES = mapOf(
            1 to "AUTO", 2 to "COOL", 3 to "DRY", 4 to "HEAT", 5 to "FAN_ONLY", 6 to "SMART_DRY",
        )
    }
}

/**
 * A fan-speed setting.
 *
 * [AUTO] lets the unit choose and [MAX] is full power. Units also accept and report other
 * speeds from 0 to 100, which are kept as they are.
 */
@JvmInline
value class FanSpeed(
    /** The 7-bit speed value on the wire; only the low 7 bits are sent. */
    val raw: Int,
) {
    /** The speed's name, such as `AUTO`, or `speed(55)` for an unnamed value. */
    override fun toString(): String = NAMES[raw] ?: "speed($raw)"

    /** The named speeds. */
    companion object {
        /** Quietest speed. */
        val SILENT = FanSpeed(20)

        /** Low speed. */
        val LOW = FanSpeed(40)

        /** Medium speed. */
        val MEDIUM = FanSpeed(60)

        /** High speed. */
        val HIGH = FanSpeed(80)

        /** Full power. */
        val MAX = FanSpeed(100)

        /** The unit picks the speed. */
        val AUTO = FanSpeed(102)

        /** The named speeds. */
        val entries: List<FanSpeed> = listOf(SILENT, LOW, MEDIUM, HIGH, MAX, AUTO)

        private val NAMES = mapOf(
            20 to "SILENT", 40 to "LOW", 60 to "MEDIUM", 80 to "HIGH", 100 to "MAX", 102 to "AUTO",
        )
    }
}

/** Which louvers swing; combine with `+` and test with `in`. */
@JvmInline
value class SwingMode(
    /** The 4-bit swing mask on the wire. */
    val raw: Int,
) {
    /** Both settings' louvers swing. */
    operator fun plus(other: SwingMode): SwingMode = SwingMode(raw or other.raw)

    /** Whether every louver that swings in [other] swings in this one too. */
    operator fun contains(other: SwingMode): Boolean = raw and other.raw == other.raw

    /** The setting's name, such as `BOTH`, or `swing(5)` for an unnamed mask. */
    override fun toString(): String = NAMES[raw] ?: "swing($raw)"

    /** The named settings. */
    companion object {
        /** No swing. */
        val OFF = SwingMode(0)

        /** Side-to-side swing. */
        val HORIZONTAL = SwingMode(0x3)

        /** Up-and-down swing. */
        val VERTICAL = SwingMode(0xC)

        /** Both directions. */
        val BOTH = SwingMode(0xF)

        private val NAMES = mapOf(0 to "OFF", 0x3 to "HORIZONTAL", 0xC to "VERTICAL", 0xF to "BOTH")
    }
}

private val messageId = AtomicInteger(0)

private fun buildCommand(type: Frame.Type, body: ByteArray): ByteArray {
    val withId = body + byteArrayOf((messageId.incrementAndGet() and 0xFF).toByte())
    val withCrc = withId + byteArrayOf(CRC8.calculate(withId).toByte())
    return Frame.build(type, withCrc)
}

internal object Command {
    fun getState(): ByteArray = buildCommand(
        Frame.Type.QUERY,
        intArrayOf(
            0x41, 0x81, 0x00, 0xFF, 0x03, 0xFF, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x03,
        ).toByteArray(),
    )

    fun toggleDisplay(beep: Boolean = true): ByteArray = buildCommand(
        Frame.Type.QUERY,
        intArrayOf(
            0x41, 0x02 or (if (beep) 0x40 else 0), 0x00, 0xFF, 0x02,
            0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        ).toByteArray(),
    )
}

private fun IntArray.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }

/** The full settable state; start from an [ACState] and override fields. */
@ConsistentCopyVisibility
data class SetState internal constructor(
    /** Whether the unit chimes to acknowledge the command. */
    var beep: Boolean = true,
    /** Whether the unit is powered on. */
    var powerOn: Boolean,
    /** Target temperature in °C, in 0.5° steps; clamped to 17–30 when sent. */
    var targetTemperature: Double,
    /** Operating mode. */
    var mode: OperationalMode,
    /** Fan speed. */
    var fanSpeed: FanSpeed,
    /** Energy-saving eco mode. */
    var eco: Boolean,
    /** Which louvers swing. */
    var swingMode: SwingMode,
    /** Turbo/boost mode for maximum output. */
    var turbo: Boolean,
    /** Sleep mode (a gentler overnight temperature curve). */
    var sleep: Boolean,
    /** Show °F on the unit's panel; [targetTemperature] and the readings stay in °C regardless. */
    var fahrenheit: Boolean,
    /** Ionizer/air purifier (a.k.a. anion). */
    var purifier: Boolean,
    /** Target relative humidity in percent (0–100), used by the dry/smart-dry modes. */
    var targetHumidity: Int,
    /** 8 °C freeze-protection heating; only effective on units that support it. */
    var freezeProtection: Boolean,
) {
    internal constructor(state: ACState) : this(
        powerOn = state.powerOn,
        targetTemperature = state.targetTemperature,
        mode = state.mode,
        fanSpeed = state.fanSpeed,
        eco = state.eco,
        swingMode = state.swingMode,
        turbo = state.turbo,
        sleep = state.sleep,
        fahrenheit = state.fahrenheit,
        purifier = state.purifier,
        targetHumidity = state.targetHumidity ?: 40,
        freezeProtection = state.freezeProtection,
    )

    internal fun encode(): ByteArray {
        val beepByte = if (beep) 0x40 else 0
        val powerByte = if (powerOn) 0x01 else 0

        require(!targetTemperature.isNaN()) { "targetTemperature must not be NaN" }
        val clamped = targetTemperature.coerceIn(17.0, 30.0)
        val integral = floor(clamped).toInt()
        val hasHalf = clamped - integral > 0
        var temperature: Int
        val temperatureAlt: Int
        if (integral in 17..30) {
            temperature = (integral - 16) and 0xF
            temperatureAlt = 0
        } else {
            temperature = 0
            temperatureAlt = (integral - 12) and 0x1F
        }
        if (hasHalf) temperature = temperature or 0x10

        val modeByte = (mode.raw and 0x7) shl 5
        val swingByte = 0x30 or (swingMode.raw and 0x3F)
        val ecoByte = if (eco) 0x80 else 0
        val purifierByte = if (purifier) 0x20 else 0
        val sleepByte = if (sleep) 0x01 else 0
        val turboByte = if (turbo) 0x02 else 0
        val fahrenheitByte = if (fahrenheit) 0x04 else 0
        val turboAlt = if (turbo) 0x20 else 0
        val humidityByte = targetHumidity and 0x7F
        val freezeByte = if (freezeProtection) 0x80 else 0

        return buildCommand(
            Frame.Type.CONTROL,
            intArrayOf(
                0x40,
                0x02 or beepByte or powerByte,
                temperature or modeByte,
                fanSpeed.raw and 0x7F,
                0x7F, 0x7F, 0x00,
                swingByte,
                turboAlt,
                ecoByte or purifierByte,
                sleepByte or turboByte or fahrenheitByte,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00,
                temperatureAlt,
                humidityByte,
                0x00,
                freezeByte,
                0x00,
                0x00,
            ).toByteArray(),
        )
    }
}

/** Parsed device state from a 0xC0 state response. */
data class ACState(
    /** Whether the unit is powered on. */
    val powerOn: Boolean,
    /** Target temperature in °C, in 0.5° steps; a device may report 13–43, wider than the 17–30 you can set. */
    val targetTemperature: Double,
    /** Operating mode. */
    val mode: OperationalMode,
    /** Fan speed. */
    val fanSpeed: FanSpeed,
    /** Measured indoor temperature in °C, or null if the unit doesn't report it. */
    val indoorTemperature: Double?,
    /** Measured outdoor temperature in °C, or null if the unit doesn't report it. */
    val outdoorTemperature: Double?,
    /** Which louvers swing. */
    val swingMode: SwingMode,
    /** Whether energy-saving eco mode is on. */
    val eco: Boolean,
    /** Whether turbo/boost mode is on. */
    val turbo: Boolean,
    /** Whether sleep mode is on. */
    val sleep: Boolean,
    /** Whether the panel shows °F; the temperatures here are always °C regardless. */
    val fahrenheit: Boolean,
    /** Whether the ionizer/air purifier is on. */
    val purifier: Boolean,
    /** Whether the LED panel is lit; toggle it with [MideaClient.toggleDisplay]. */
    val displayOn: Boolean,
    /** Whether the unit is signalling that its filter needs cleaning. */
    val filterAlert: Boolean,
    /** Whether 8 °C freeze-protection heating is active. */
    val freezeProtection: Boolean,
    /** Target relative humidity in percent, or null if the unit doesn't support it. */
    val targetHumidity: Int?,
) {
    companion object {
        /** Parse a 0xAA frame into state; null if it's not a 0xC0 state response. */
        @Suppress("ReturnCount", "ThrowsCount")
        internal fun parse(frame: ByteArray): ACState? {
            if (frame.size < Frame.HEADER_LENGTH + 2) throw ProtocolException("frame too short")
            if (frame[0].toInt() and 0xFF != 0xAA || frame[2].toInt() and 0xFF != Frame.DEVICE_TYPE_AC) {
                throw ProtocolException("bad frame")
            }
            val expected = Frame.checksum(frame, 1, frame.size - 1)
            if (expected != frame[frame.size - 1].toInt() and 0xFF) throw ProtocolException("checksum mismatch")

            if (frame[10].toInt() and 0xFF != 0xC0) return null
            val p = IntArray(frame.size - 12) { frame[10 + it].toInt() and 0xFF }
            if (p.size < 16) return null  // fixed-offset reads go up to index 15

            val fahrenheit = (p[10] and 0x4) != 0
            var target = (p[2] and 0xF) + 16.0
            if (p[2] and 0x10 != 0) target += 0.5
            val altTarget = p[13] and 0x1F
            if (altTarget != 0) {
                target = altTarget + 12.0
                if (p[2] and 0x10 != 0) target += 0.5
            }

            return ACState(
                powerOn = (p[1] and 0x1) != 0,
                targetTemperature = target,
                mode = OperationalMode((p[2] shr 5) and 0x7),
                fanSpeed = FanSpeed(p[3] and 0x7F),
                indoorTemperature = parseTemp(p[11], (p[15] and 0xF) / 10.0, fahrenheit),
                outdoorTemperature = parseTemp(p[12], (p[15] shr 4) / 10.0, fahrenheit),
                swingMode = SwingMode(p[7] and 0xF),
                eco = (p[9] and 0x10) != 0,
                turbo = (p[8] and 0x20) != 0 || (p[10] and 0x2) != 0,
                sleep = (p[10] and 0x1) != 0,
                fahrenheit = fahrenheit,
                purifier = (p[9] and 0x20) != 0,
                displayOn = p[14] != 0x70,
                filterAlert = (p[13] and 0x20) != 0,
                freezeProtection = p.size >= 22 && (p[21] and 0x80) != 0,
                targetHumidity = if (p.size >= 20) p[19] and 0x7F else null,
            )
        }

        @Suppress("ReturnCount")
        private fun parseTemp(raw: Int, decimals: Double, fahrenheit: Boolean): Double? {
            if (raw == 0xFF) return null
            val temperature = (raw - 50) / 2.0
            if (!fahrenheit && decimals != 0.0) {
                return temperature.toInt() + if (temperature >= 0) decimals else -decimals
            }
            if (decimals >= 0.5) {
                return temperature.toInt() + if (temperature >= 0) 0.5 else -0.5
            }
            return temperature
        }
    }
}
