package com.contest.signalprobe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import android.content.Context

/**
 * Reads the Pleos Vehicle SDK signals the app design depends on, by polling the documented
 * get APIs. Signatures were taken from the Vehicle 2.3.2 AAR with javap, so no guessed calls.
 *
 * Public-API status (Pleos docs sitemap, 2026-11-11):
 *  - documented: seat occupancy, seat belt buckled, airbag deployed, door position/lock,
 *    telltales, wiper, low beam, fuel percent, EV SOC, turn signal, low tire pressure
 *  - NOT documented: Gear domain (read here only to compare with AAOS GEAR_SELECTION)
 *  - the official sample app blocks telltales as non-public, so a failure there is expected
 */
class VehicleSdkProbe(
    context: Context,
    private val report: (key: String, value: String) -> Unit
) {
    private val vehicle = Vehicle(context.applicationContext)
    private var ready = false

    fun initialize(): Boolean = try {
        vehicle.initialize()
        ready = true
        report("SDK 연결", "성공")
        true
    } catch (e: Exception) {
        report("SDK 연결", fail(e))
        false
    }

    fun release() {
        if (!ready) return
        try {
            vehicle.release()
        } catch (_: Exception) {
        }
        ready = false
    }

    /** One read of every signal. Call repeatedly; the caller only logs changes. */
    fun pollOnce() {
        if (!ready) return
        safely("SDK seats") { measured("SDK.readSeats") { readSeats() } }
        safely("SDK doors") { measured("SDK.readDoors") { readDoors() } }
        safely("SDK telltales") { measured("SDK.readTelltales") { readTelltales() } }
        safely("SDK misc") { measured("SDK.readMisc") { readMisc() } }
    }

    private fun readSeats() {
        val seat = vehicle.getSeat()
        val safety = vehicle.getSafety()
        for (value in SEATS) {
            val area = SeatArea(value)
            seat.getCurrentSeatOccupancy(area,
                { v -> report("SDK 좌석점유 ${value.name}", v.toString()) },
                { e -> report("SDK 좌석점유 ${value.name}", fail(e)) })
            seat.getCurrentSeatBeltBuckledState(area,
                { v -> report("SDK 안전벨트 ${value.name}", v.toString()) },
                { e -> report("SDK 안전벨트 ${value.name}", fail(e)) })
            safety.hasSeatAirbagBeenDeployed(area,
                { v -> report("SDK 에어백전개 ${value.name}", v.toString()) },
                { e -> report("SDK 에어백전개 ${value.name}", fail(e)) })
        }
    }

    private fun readDoors() {
        val door = vehicle.getDoor()
        for (value in DOORS) {
            door.getCurrentDoorPosition(DoorArea(value),
                { v -> report("SDK 문위치 ${value.name}", v.toString()) },
                { e -> report("SDK 문위치 ${value.name}", fail(e)) })
        }
        door.getDoorLockState(DoorArea(DoorValue.ROW_1_LEFT),
            { v -> report("SDK 문잠금 ROW_1_LEFT", v.toString()) },
            { e -> report("SDK 문잠금 ROW_1_LEFT", fail(e)) })
    }

    private fun readTelltales() {
        vehicle.getSafety().getTelltalesInfo(
            { info ->
                val list = info?.telltales.orEmpty()
                // DIAGNOSTIC ONLY: getTelltalesInfo needs PRIVATE_CAR_SAFETY (signature|privileged per the
                // official docs; normal only on the emulator, held by the debug build). Not a product path.
                report("DIAG 경고등 개수", list.size.toString())
                for (t in list) {
                    if (t.type in WATCHED_TELLTALES) {
                        report("DIAG 경고등 ${t.type}", t.state.toString())
                    }
                }
                val left = list.firstOrNull { it.type == TelltaleType.TURN_SIGNAL_LEFT }?.state?.toString()
                val right = list.firstOrNull { it.type == TelltaleType.TURN_SIGNAL_RIGHT }?.state?.toString()
                val bothBlink = left == "BLINK" && right == "BLINK"
                // Emulator evidence (2026-11-12): the console hazard switch does NOT populate this list, so this
                // is not a hazard-light detector. It only shows when both turn-signal telltales blink.
                report("DIAG 좌우 경고등 BLINK 쌍(비상등 판정 아님)", if (bothBlink) "둘 다 BLINK" else "아님 (좌=$left 우=$right)")
            },
            { e -> report("DIAG 경고등", fail(e)) })
    }

    private fun readMisc() {
        vehicle.getTurnSignal().getCurrentTurnSignalState(
            { v -> report("SDK 방향지시등", v.toString()) },
            { e -> report("SDK 방향지시등", fail(e)) })
        vehicle.getGear().getCurrentGearSelectionState(
            { v -> report("SDK 기어(문서 미공개 API)", v.toString()) },
            { e -> report("SDK 기어(문서 미공개 API)", fail(e)) })
        vehicle.getWiper().getCurrentWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD),
            { v -> report("SDK 와이퍼(앞)", v.toString()) },
            { e -> report("SDK 와이퍼(앞)", fail(e)) })
        vehicle.getLight().getCurrentLowBeamLightState(
            { v -> report("SDK 하향등", v.toString()) },
            { e -> report("SDK 하향등", fail(e)) })
        vehicle.getFuel().getFuelPercent(
            { v -> report("SDK 연료 %", v.toString()) },
            { e -> report("SDK 연료 %", fail(e)) })
        vehicle.getEvBattery().getEvBatteryStateOfCharge(
            { v -> report("SDK 배터리 SOC", v.toString()) },
            { e -> report("SDK 배터리 SOC", fail(e)) })
        vehicle.getTire().getLowTirePressure(WheelArea(WheelValue.LEFT_FRONT),
            { v -> report("SDK 타이어 저압 기준(앞왼쪽)", v.toString()) },
            { e -> report("SDK 타이어 저압 기준(앞왼쪽)", fail(e)) })
    }

    /** M6: can an app open the trunk? Reads the allowed range first, then requests the max. */
    fun openTrunk() {
        if (!ready) return
        val door = vehicle.getDoor()
        val rear = DoorArea(DoorValue.REAR)
        safely("SDK 트렁크 열기") {
            door.checkDoorPositionCapabilities(
                { config, bounds ->
                    val range = bounds?.get(rear)
                    report("SDK 트렁크 허용 범위", "접근=${config?.accessType} 범위=$range")
                    val target = range?.maxValue ?: 1
                    door.setDoorPosition(rear, target,
                        { _, v -> report("SDK 트렁크 열기", "요청 완료 값=$v") },
                        { e -> report("SDK 트렁크 열기", fail(e)) })
                },
                { e -> report("SDK 트렁크 허용 범위", fail(e)) })
        }
    }

    private inline fun safely(key: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            report(key, fail(e))
        }
    }

    private fun fail(e: Exception) = "실패: ${e.javaClass.simpleName}: ${e.message}"

    private companion object {
        val SEATS = listOf(
            SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT,
            SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT,
        )
        val DOORS = listOf(
            DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT,
            DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT,
            DoorValue.REAR,
        )
        val WATCHED_TELLTALES = setOf(
            TelltaleType.TURN_SIGNAL_LEFT, TelltaleType.TURN_SIGNAL_RIGHT,
            TelltaleType.TPMS, TelltaleType.SEAT_BELT, TelltaleType.DOOR_OPEN,
            TelltaleType.TRUNK_OPEN, TelltaleType.LOW_FUEL, TelltaleType.LOW_EV_BATTERY,
            TelltaleType.AIRBAG, TelltaleType.EMERGENCY_CALL, TelltaleType.ICY_ROAD,
            TelltaleType.HEADLAMP_LOW, TelltaleType.PARKING_BRAKE,
        )
    }
}
