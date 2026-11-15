package com.contest.resafety.fullhmi

import kotlin.math.abs

/*
 * aurora13: protect mode on the real Pleos Vehicle SDK.
 * This file is the R2D submission app's DProtection.kt (verified on the Pleos Connect emulator, run_20261114_230144)
 * carried over unchanged in logic; only the package and the reading type differ.
 */

enum class SignalStatus { OK, UNAVAILABLE, PERMISSION_DENIED, ERROR }

/** One vehicle observation. `at` is elapsedRealtime of the read (freshness). */
data class Reading(val key: String, val raw: String?, val status: SignalStatus, val at: Long)

internal object DParkGate {
    /** Uses local monotonic receipt times, not rounded UI speed or parking-brake substitution. */
    fun blocked(speed: Reading?, gear: Reading?, nowMs: Long): String? {
        fun fresh(r: Reading?) = r != null && r.status == SignalStatus.OK && r.at >= 0L && nowMs - r.at in 0..2_500L
        if (!fresh(speed) || !fresh(gear)) return "최근 속도와 기어를 확인할 수 없어요."
        if (abs(checkNotNull(speed).at - checkNotNull(gear).at) > 1_500L) return "속도와 기어를 다시 확인하고 있어요."
        val metresPerSecond = speed.raw?.toDoubleOrNull()
        if (metresPerSecond == null || !metresPerSecond.isFinite() || abs(metresPerSecond * 3.6) > 0.1)
            return "차가 완전히 멈춘 뒤 실행하세요."
        if (gear.raw?.toIntOrNull() != 4) return "기어를 P에 둔 뒤 실행하세요."
        return null
    }
}

internal enum class DActuator(val key: String, val label: String, val target: String) {
    TAIL("tail", "미등", "켜짐"),
    REAR_FOG("fog", "후방 안개등", "켜짐"),
    CABIN_AMBIENT("cabin", "실내등", "켜짐"),
    DOOR_LOCK("unlock", "앞뒤 문 4개 잠금", "해제")
}

internal enum class DPhase { CHECK, BEFORE, WRITE, AFTER }
internal enum class DOutcome { WAITING, IN_PROGRESS, ALREADY, VERIFIED, MISMATCH, UNSUPPORTED, DENIED, NO_VALUE, ERROR, TIMEOUT, STOPPED, UNCERTAIN }

internal sealed interface DReply<out T> {
    data class Value<T>(val value: T) : DReply<T>
    data class Failure(val outcome: DOutcome) : DReply<Nothing>
}

/** All callbacks must be marshalled to the controller's owner thread. A write reply is acknowledgement only. */
internal interface DProtectionPort {
    fun supports(action: DActuator, done: (DReply<Boolean>) -> Unit)
    fun readAtTarget(action: DActuator, done: (DReply<Boolean>) -> Unit)
    fun requestTarget(action: DActuator, done: (DReply<Unit>) -> Unit)
}

internal data class DResult(val outcome: DOutcome, val phase: DPhase? = null, val observedAtTarget: Boolean? = null)

/** Gate re-checked before every step and on every reply; one actuator at a time; read before, write, read again. */
internal class DProtectionRun(
    private val port: DProtectionPort,
    private val blockReason: () -> String?,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val entries: List<DActuator> = DActuator.entries,
    /** Restore runs: write the original value even if the readback already shows it (an earlier request may still be pending). */
    private val alwaysWrite: Boolean = false,
    private val changed: () -> Unit
) {
    private val state = entries.associateWith { DResult(DOutcome.WAITING) }.toMutableMap()
    private var token = 0L
    private var active: DActuator? = null
    private var writeIssued = false
    var running = false
        private set
    var stoppedBecause: String? = null
        private set
    fun result(action: DActuator) = state[action] ?: throw NoSuchElementException("not part of this run: $action")
    fun has(action: DActuator) = action in state
    val actions: List<DActuator> get() = entries

    fun start() {
        if (running) return
        entries.forEach { state[it] = DResult(DOutcome.WAITING) }
        stoppedBecause = null
        running = true
        next(0)
    }

    fun recheck() { if (running) blockReason()?.let(::cancel) }

    fun cancel(reason: String) {
        if (!running) return
        token++
        for (action in entries) {
            val result = state.getValue(action)
            if (result.outcome == DOutcome.IN_PROGRESS || result.outcome == DOutcome.WAITING)
                state[action] = DResult(if (action == active && writeIssued) DOutcome.UNCERTAIN else DOutcome.STOPPED, result.phase)
        }
        stoppedBecause = reason
        active = null
        running = false
        changed()
    }

    private fun stillParked(): Boolean {
        if (!running) return false
        val reason = blockReason() ?: return true
        cancel(reason)
        return false
    }

    private fun next(i: Int) {
        if (!stillParked()) return
        if (i >= entries.size) { running = false; active = null; changed(); return }
        val action = entries[i]
        active = action
        writeIssued = false
        fun finish(outcome: DOutcome, phase: DPhase, observed: Boolean? = null) {
            state[action] = DResult(outcome, phase, observed)
            val unknownAfterWrite = when (phase) {
                DPhase.WRITE -> outcome != DOutcome.DENIED && outcome != DOutcome.UNSUPPORTED
                DPhase.AFTER -> outcome != DOutcome.VERIFIED && outcome != DOutcome.MISMATCH
                else -> false
            }
            if (writeIssued && unknownAfterWrite) { cancel("요청 뒤 상태를 확인하지 못해 나머지 요청을 중단했어요."); return }
            next(i + 1)
        }
        request<Boolean>(action, DPhase.CHECK, { port.supports(action, it) }) { cap ->
            when (cap) {
                is DReply.Failure -> finish(cap.outcome, DPhase.CHECK)
                is DReply.Value -> if (!cap.value) finish(DOutcome.UNSUPPORTED, DPhase.CHECK) else {
                    request<Boolean>(action, DPhase.BEFORE, { port.readAtTarget(action, it) }) { before ->
                        when (before) {
                            is DReply.Failure -> finish(before.outcome, DPhase.BEFORE)
                            is DReply.Value -> if (before.value && !alwaysWrite) finish(DOutcome.ALREADY, DPhase.BEFORE, true) else {
                                request<Unit>(action, DPhase.WRITE, { callback -> writeIssued = true; port.requestTarget(action, callback) }) { ack ->
                                    when (ack) {
                                        is DReply.Failure -> finish(ack.outcome, DPhase.WRITE)
                                        is DReply.Value -> request<Boolean>(action, DPhase.AFTER, { port.readAtTarget(action, it) }) { after ->
                                            when (after) {
                                                is DReply.Failure -> finish(after.outcome, DPhase.AFTER)
                                                is DReply.Value -> finish(if (after.value) DOutcome.VERIFIED else DOutcome.MISMATCH, DPhase.AFTER, after.value)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun <T> request(action: DActuator, phase: DPhase, send: ((DReply<T>) -> Unit) -> Unit, consume: (DReply<T>) -> Unit) {
        if (!stillParked()) return
        val expected = ++token
        state[action] = DResult(DOutcome.IN_PROGRESS, phase)
        changed()
        fun resolve(reply: DReply<T>) {
            if (!running || token != expected || !stillParked()) return
            token++
            consume(reply)
        }
        schedule(2_500L) { resolve(DReply.Failure(DOutcome.TIMEOUT)) }
        if (!stillParked() || token != expected) return
        try { send(::resolve) } catch (_: SecurityException) { resolve(DReply.Failure(DOutcome.DENIED)) } catch (_: Exception) { resolve(DReply.Failure(DOutcome.ERROR)) }
    }
}
