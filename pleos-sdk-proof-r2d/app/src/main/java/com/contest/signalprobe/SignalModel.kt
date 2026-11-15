package com.contest.signalprobe

/** Evidence category for every signal shown or reported by the prototype. */
enum class Category(val label: String) {
    VERIFIED("VERIFIED"),
    OBSERVED_INCOMPLETE("OBSERVED BUT INCOMPLETE"),
    INFERRED("INFERRED"),
    SIMULATED("SIMULATED"),
    UNAVAILABLE("UNAVAILABLE"),
    NOT_TESTED("NOT TESTED"),
}

/** Conservative reading of one seat. Never a passenger count or a rescue judgement. */
enum class SeatPresence(val label: String) {
    REPORTED_EMPTY("빈 좌석으로 보고됨"),
    POSSIBLY_OCCUPIED("착석 가능성 있음 / 상태 불확실"),
    UNKNOWN("확인 불가"),
}

/**
 * The single place that defines what the Pleos SDK seat Boolean means.
 *
 * Evidence (Vehicle SDK 2.3.2, emulator image Pleos Connect v2.1.0, see PHASE2_TECHNICAL_ANALYSIS.md):
 *  - VHAL SEAT_OCCUPANCY raw 1 (AOSP VACANT) -> SDK true; raw 2 (OCCUPIED) -> SDK false.
 *  - The SDK maps `raw == 1` to true and every other value (OCCUPIED, UNKNOWN, parse failure) to false.
 *  - The official Pleos docs do not define the Boolean, so this is NOT a version-independent public contract.
 * Re-run the four-seat test whenever the SDK or the emulator image changes.
 */
object SdkSeatAdapter {
    const val VALIDATED_SDK = "ai.pleos.playground:Vehicle:2.3.2"

    fun interpret(sdkValue: Boolean?): SeatPresence = when (sdkValue) {
        true -> SeatPresence.REPORTED_EMPTY
        false -> SeatPresence.POSSIBLY_OCCUPIED
        null -> SeatPresence.UNKNOWN
    }

    /** Parses the text the probe reports ("true" / "false" / failure text). */
    fun interpretReported(text: String?): SeatPresence = when (text) {
        "true" -> SeatPresence.REPORTED_EMPTY
        "false" -> SeatPresence.POSSIBLY_OCCUPIED
        else -> SeatPresence.UNKNOWN
    }
}

data class Eligibility(val eligible: Boolean, val summary: String)

/**
 * Stage B. Gear P and speed 0 only say the car is stationary. They do NOT say an accident happened.
 * AAOS VehicleGear: GEAR_PARK = 4. Speed is in m/s.
 */
object StationaryGate {
    private const val GEAR_PARK = 4
    private const val SPEED_EPSILON_MPS = 0.03f // ~0.1 km/h

    fun evaluate(gearRaw: Int?, speedMps: Float?, speedFresh: Boolean): Eligibility {
        val gearText = when (gearRaw) {
            null -> "기어 확인 불가"
            GEAR_PARK -> "기어 P"
            else -> "기어 P 아님(raw=$gearRaw)"
        }
        val speedText = when {
            speedMps == null || !speedFresh -> "속도 확인 불가"
            speedMps <= SPEED_EPSILON_MPS -> "속도 0 km/h"
            else -> "속도 %.1f km/h".format(speedMps * 3.6f)
        }
        val ok = gearRaw == GEAR_PARK && speedMps != null && speedFresh && speedMps <= SPEED_EPSILON_MPS
        return Eligibility(ok, "$gearText · $speedText")
    }
}
