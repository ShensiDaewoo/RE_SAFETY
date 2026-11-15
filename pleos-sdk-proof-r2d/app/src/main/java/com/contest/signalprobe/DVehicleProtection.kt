package com.contest.signalprobe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch

/**
 * D plan F1: the real Pleos Vehicle SDK behind [DProtectionPort].
 *
 * Safety contract (kept by [DProtectionRun], not here):
 *  - runs only after a user tap, only while [DParkGate] reports fresh P + 0 km/h, re-checked before every step;
 *  - one actuator at a time, read before, write, read again; a write reply alone never counts as success.
 * This class adds: SDK calls on the hub's SDK thread, replies marshalled to the owner (main) thread,
 * capture of the original value on the first read, and restore mode that writes those originals back.
 * Windows, trunk, hood and anything automatic are deliberately absent.
 */
internal class DVehicleProtectionPort(
    private val hub: VehicleHub,
    private val post: (() -> Unit) -> Unit,
    /** null = protect (lights ON, doors unlocked). Non-null = restore these captured originals. */
    private val restore: Originals? = null,
) : DProtectionPort {

    class Originals {
        val lights: MutableMap<DActuator, LightSwitch> = java.util.concurrent.ConcurrentHashMap()
        val doors: MutableMap<DoorArea, Boolean> = java.util.concurrent.ConcurrentHashMap()
        fun has(action: DActuator) = if (action == DActuator.DOOR_LOCK) doors.isNotEmpty() else action in lights
    }

    /** Values seen on the first read of a protect run (what the undo button writes back). */
    val originals = Originals()
    /** Last lamp state read after the switch check, for display only ("램프 켜짐/꺼짐"). */
    val lampState: MutableMap<DActuator, String> = java.util.concurrent.ConcurrentHashMap()
    private val written: MutableSet<DActuator> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Lock areas exactly as the vehicle reports them (one area may cover all four doors). */
    @Volatile private var doorAreas: List<DoorArea> = emptyList()

    fun wrote(action: DActuator) = action in written

    private fun reply(done: (DReply<Boolean>) -> Unit, value: DReply<Boolean>) = post { done(value) }

    private fun log(m: String) = android.util.Log.i("Accident10Port", (if (restore == null) "PROTECT " else "RESTORE ") + m)

    private fun classify(e: Exception): DOutcome {
        val m = e.message.orEmpty()
        log("EXC ${e.javaClass.simpleName}: ${m.take(160)}")
        return when {
            e is SecurityException || m.contains("permission", true) -> DOutcome.DENIED
            m.contains("not support", true) || m.contains("unsupported", true) || m.contains("NOT_SUPPORTED", true) -> DOutcome.UNSUPPORTED
            else -> DOutcome.ERROR
        }
    }

    private fun onVehicle(fail: (DOutcome) -> Unit, work: (Vehicle) -> Unit) = hub.withVehicle { v ->
        if (v == null) post { fail(DOutcome.NO_VALUE) } else try { work(v) } catch (e: Exception) { post { fail(classify(e)) } }
    }

    private fun lightTarget(action: DActuator): LightSwitch =
        restore?.lights?.get(action) ?: LightSwitch.ON

    private fun doorTarget(door: DoorArea): Boolean =
        restore?.doors?.get(door) ?: false // protect: unlocked so helpers can open the doors

    // --- port ---------------------------------------------------------------------------------

    override fun supports(action: DActuator, done: (DReply<Boolean>) -> Unit) {
        val fail: (DOutcome) -> Unit = { done(DReply.Failure(it)) }
        if (restore != null && !restore.has(action)) { post { done(DReply.Value(false)) }; return }
        onVehicle(fail) { v ->
            val err: (Exception) -> Unit = { e -> post { fail(classify(e)) } }
            if (action == DActuator.DOOR_LOCK) {
                v.getDoor().checkDoorLockCapabilities({ areas: List<DoorArea>? ->
                    log("DOOR caps=${areas?.map { it.values.toList() }}")
                    // Only side-door areas; never hood or tailgate.
                    val wanted = areas.orEmpty().filter { a -> a.values.any { it in DOORS } && a.values.none { it == DoorValue.HOOD || it == DoorValue.REAR } }
                    doorAreas = if (restore != null) wanted.filter { it in restore.doors } else wanted
                    reply(done, DReply.Value(doorAreas.isNotEmpty()))
                }, err)
            } else {
                val ok: (List<LightSwitch>?) -> Unit = { list -> log("$action caps=$list"); reply(done, DReply.Value(list.orEmpty().contains(lightTarget(action)))) }
                val light = v.getLight()
                when (action) {
                    DActuator.TAIL -> light.checkTailLampLightSwitchCapabilities(ok, err)
                    DActuator.REAR_FOG -> light.checkRearFogLightSwitchCapabilities(ok, err)
                    else -> light.checkCabinLightSwitchCapabilities(ok, err)
                }
            }
        }
    }

    override fun readAtTarget(action: DActuator, done: (DReply<Boolean>) -> Unit) = readWithRetry(action, done, 0)

    /** After our own write the head unit may apply the change a moment later: re-read up to 6 times (≈2.1 s, inside the 2.5 s step timeout). */
    private fun readWithRetry(action: DActuator, done: (DReply<Boolean>) -> Unit, attempt: Int) {
        readOnce(action) { reply ->
            if (reply is DReply.Value && !reply.value && action in written && attempt < 6)
                hub.withVehicleDelayed(350L) { readWithRetry(action, done, attempt + 1) }
            else post { done(reply) }
        }
    }

    /** Callback runs on an SDK/binder thread; caller marshals. */
    private fun readOnce(action: DActuator, result: (DReply<Boolean>) -> Unit) {
        onVehicle({ result(DReply.Failure(it)) }) { v ->
            val err: (Exception) -> Unit = { e -> result(DReply.Failure(classify(e))) }
            if (action == DActuator.DOOR_LOCK) readDoors(v, 0, mutableMapOf(), result, err)
            else readLight(v.getLight(), action, result, err)
        }
    }

    private fun readLight(light: Light, action: DActuator, result: (DReply<Boolean>) -> Unit, err: (Exception) -> Unit) {
        val got: (LightSwitch?) -> Unit = { sw ->
            log("$action switch=$sw target=${lightTarget(action)}")
            if (sw == null) result(DReply.Failure(DOutcome.NO_VALUE)) else {
                if (restore == null && action !in originals.lights && action !in written) originals.lights[action] = sw
                // Lamp state is display-only evidence; the decision uses the switch the SDK reports back.
                val state: (Any?) -> Unit = { st ->
                    log("$action lamp=$st")
                    lampState[action] = when (st?.toString()) { "ON" -> "램프 켜짐"; "OFF" -> "램프 꺼짐"; null -> "램프 값 없음"; else -> "램프 $st" }
                    result(DReply.Value(sw == lightTarget(action)))
                }
                val stateErr: (Exception) -> Unit = { lampState[action] = "램프 상태 읽기 실패"; result(DReply.Value(sw == lightTarget(action))) }
                when (action) {
                    DActuator.TAIL -> light.getCurrentTailLampLightState(state, stateErr)
                    DActuator.REAR_FOG -> light.getCurrentRearFogLightState(state, stateErr)
                    else -> light.getCurrentCabinLightState(state, stateErr)
                }
            }
        }
        when (action) {
            DActuator.TAIL -> light.getTailLampLightSwitch(got, err)
            DActuator.REAR_FOG -> light.getRearFogLightSwitch(got, err)
            else -> light.getCabinLightSwitch(got, err)
        }
    }

    private fun readDoors(v: Vehicle, i: Int, seen: MutableMap<DoorArea, Boolean>,
                          result: (DReply<Boolean>) -> Unit, err: (Exception) -> Unit) {
        if (i >= doorAreas.size) {
            if (restore == null && originals.doors.isEmpty() && DActuator.DOOR_LOCK !in written) originals.doors.putAll(seen)
            val locked = seen.values.count { it }
            lampState[DActuator.DOOR_LOCK] = if (seen.size == 1) (if (locked == 1) "문 잠김" else "문 잠금 풀림") else "잠김 $locked · 풀림 ${seen.size - locked}"
            result(DReply.Value(seen.all { (door, isLocked) -> isLocked == doorTarget(door) }))
            return
        }
        val door = doorAreas[i]
        v.getDoor().getDoorLockState(door, { locked: Boolean? ->
            log("DOOR ${door.values.toList()} locked=$locked")
            if (locked == null) result(DReply.Failure(DOutcome.NO_VALUE))
            else { seen[door] = locked; hub.withVehicle { readDoors(v, i + 1, seen, result, err) } }
        }, err)
    }

    override fun requestTarget(action: DActuator, done: (DReply<Unit>) -> Unit) {
        written += action
        val fail: (DOutcome) -> Unit = { done(DReply.Failure(it)) }
        onVehicle(fail) { v ->
            val err: (Exception) -> Unit = { e -> post { fail(classify(e)) } }
            if (action == DActuator.DOOR_LOCK) writeDoors(v, 0, done, err)
            else {
                val ack: (LightSwitch?) -> Unit = { r -> log("$action set ack=$r"); post { done(DReply.Value(Unit)) } }
                val light = v.getLight()
                val target = lightTarget(action)
                when (action) {
                    DActuator.TAIL -> light.setTailLampLightSwitch(target, ack, err)
                    DActuator.REAR_FOG -> light.setRearFogLightSwitch(target, ack, err)
                    else -> light.setCabinLightSwitch(target, ack, err)
                }
            }
        }
    }

    private fun writeDoors(v: Vehicle, i: Int, done: (DReply<Unit>) -> Unit, err: (Exception) -> Unit) {
        if (i >= doorAreas.size) { post { done(DReply.Value(Unit)) }; return }
        val door = doorAreas[i]
        v.getDoor().setDoorLock(door, doorTarget(door), { _: DoorArea?, _: Boolean? ->
            hub.withVehicle { writeDoors(v, i + 1, done, err) }
        }, err)
    }

    companion object {
        val DOORS = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    }
}
