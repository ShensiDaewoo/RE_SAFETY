package com.contest.signalprobe

import android.content.Intent
import android.widget.LinearLayout

/**
 * SUBMISSION variant of the lab hook object. Every hook is a no-op, so none of the lab-only code
 * (simulated accident event, relay client, motion override) exists in this APK.
 * The lab variant lives in src/lab/java and has the same member signatures.
 */
internal object LabBridge {
    fun adjustMotion(mob: MobilityState): MobilityState = mob
    fun onIntent(a: ProductActivity, intent: Intent?) = Unit
    fun signature(): String = ""
    fun mastRight(): String? = null
    /** true = the lab accident home was drawn instead of the normal home. */
    fun homeAccident(a: ProductActivity, col: LinearLayout, mob: MobilityState): Boolean = false
    fun hideRailEmergency(s: Screen): Boolean = false
    fun sosTapped(a: ProductActivity) = Unit
    fun homeLabelExtra(a: ProductActivity): android.view.View? = null
    /** true = the lab nearby-SOS status screen was drawn instead of the offline SOS screen. */
    fun sosScreen(a: ProductActivity, col: LinearLayout, mob: MobilityState): Boolean = false
}
