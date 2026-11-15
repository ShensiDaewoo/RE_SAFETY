package com.contest.signalprobe

import android.os.SystemClock
import android.util.Log

/** Measures invocation time; SDK callback arrival is logged separately. */
internal inline fun <T> measured(label: String, block: () -> T): T {
    val start = SystemClock.elapsedRealtime()
    Log.i("ProbeTiming", "BEGIN $label t=$start thread=${Thread.currentThread().name}")
    try {
        return block()
    } finally {
        Log.i("ProbeTiming", "END $label durationMs=${SystemClock.elapsedRealtime() - start} thread=${Thread.currentThread().name}")
    }
}
