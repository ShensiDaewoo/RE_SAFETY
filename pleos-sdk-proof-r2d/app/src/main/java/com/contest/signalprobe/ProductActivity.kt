package com.contest.signalprobe

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.EditText
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen(val step: Int, val title: String) {
    HOME(0, "RE:SAFETY"),
    GUIDE(1, "행동 확인"),
    INJURY(2, "부상 여부"),
    CONTEXT(3, "차량 상태"),
    SUMMARY(4, "공유할 내용"),
    QR(5, "QR로 공유"),
    DONE(6, "완료"),
    NOTES(0, "출발 전 확인"),
    PASSPORT(0, "사고 기록"),
    HANDOFF(0, "공유 목적 선택"),
    ROADSIDE(0, "보험·견인"),
    SOS(0, "SOS"),
    SOS_LOC(0, "현재 위치 확인"),
    D_LAB(0, "보호 모드"),
    D_BELTS(0, "좌석·안전벨트"),
}

/** Stage 1: insurer emergency line entered by the user before driving. Local only, never transmitted. */
private class PrepStore(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("prep", android.content.Context.MODE_PRIVATE)
    val insurerName: String? get() = prefs.getString("insurer_name", null)?.takeIf { it.isNotBlank() }
    val insurerPhone: String? get() = prefs.getString("insurer_phone", null)?.takeIf { HandoffCards.validPhone(it) }
    val generalPhone: String? get() = prefs.getString("insurer_general_phone", null)?.takeIf { HandoffCards.validPhone(it) }
    /** D plan F6: last pre-departure vehicle snapshot (one line) and when it was taken. Local only. */
    val handoverText: String? get() = prefs.getString("handover_text", null)?.takeIf { it.isNotBlank() }
    val handoverWall: Long? get() = prefs.getLong("handover_wall", 0L).takeIf { it > 0L }
    val handoverQr: String? get() = prefs.getString("handover_qr", null)?.takeIf { it.isNotBlank() }
    fun saveHandover(text: String, wall: Long, qr: String) { prefs.edit().putString("handover_text", text).putLong("handover_wall", wall).putString("handover_qr", qr).commit() }
    fun save(name: String, emergency: String, general: String) = prefs.edit()
        .putString("insurer_name", name.trim().take(20))
        .putString("insurer_phone", emergency)
        .putString("insurer_general_phone", general)
        .apply()
}

/**
 * RC4 (S2 lite): optional seat notes the user sets before driving. Stored only in this app's private
 * preferences on the head unit; never transmitted. Fixed choices only (no free text, names or ages).
 */
private class SeatNotes(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("seat_notes", android.content.Context.MODE_PRIVATE)
    fun get(area: String): String? = prefs.getString(area, null)?.takeIf { it in OPTIONS.map { o -> o.first } }
    // commit(): tiny file; a synchronous write survives an immediate process kill (seen with apply() in RC4 testing).
    fun set(area: String, value: String?) { prefs.edit().apply { if (value == null) remove(area) else putString(area, value) }.commit() }
    fun clear() { prefs.edit().clear().commit() }
    fun count(areas: List<String>) = areas.count { get(it) != null }
    companion object {
        /** label shown on screen to short form written into the QR summary */
        val OPTIONS = listOf("영유아(카시트)" to "영유아", "어린이" to "어린이", "거동 불편" to "거동불편", "반려동물" to "반려동물")
        fun short(label: String) = OPTIONS.firstOrNull { it.first == label }?.second ?: label
    }
}

/** Session state lives in a process-wide object so activity recreation does not lose the flow. */
private object Session {
    var screen = Screen.HOME
    val guide = BooleanArray(4)
    var category: String? = null
    val injuriesBySeat = mutableMapOf<String, String>()
    var includeState = true
    var includeNotes = false
    var active = false
    var stationaryConfirmed = false
    var notice: String? = null
    /** Where the QR screen's "이전" returns to (a card or the summary). */
    var qrReturn: Screen? = null

    /** Keep incident details already entered in the insurance flow. */
    fun start() {
        screen = Screen.GUIDE; active = true
        guide.fill(false); stationaryConfirmed = false; includeState = true; includeNotes = false; notice = null; qrReturn = null
    }
    fun reset() {
        screen = Screen.HOME
        active = false
        notice = null
        category = null
        injuriesBySeat.clear()
        qrReturn = null
    }
}

/**
 * RE:SAFETY (formerly 사고 후 10분) — consumer flow (provisional UI, functional, not designer-approved).
 * Home -> guide -> injury -> vehicle context -> summary -> QR -> done. Paused while moving.
 * The app never infers a crash. D plan: protect mode changes 3 lights + 4 door locks only on a user tap while parked (P, 0 km/h),
 * reads each back, and can undo. Vehicle position comes from Pleos FusedLocation (no INTERNET). No windows, trunk or automatic action.
 */
class ProductActivity : Activity() {
    internal val tag = "Accident10"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var hub: VehicleHub
    private lateinit var notes: SeatNotes
    private lateinit var prep: PrepStore
    private lateinit var body: ScrollView
    private lateinit var rail: LinearLayout   // reference 01: app-owned left navigation rail (secondary panel)
    private lateinit var incidentLog: IncidentLog
    private var passportHistory = false
    private var passportSeats = false
    private var passportVehicle = false
    private var lastD5Display: String? = null
    private var handoffPayload: PassportHandoff.Payload? = null
    private var handoffProfile: HandoffProfile? = null
    private var handoffBitmap: Bitmap? = null
    private var handoffDisplayed = false
    private var handoffError: Pair<String, String>? = null   // visible QR failure (title, hint); cleared on success or reset
    private var lastRendered: Screen? = null
    private var roadsideStep = 1
    private var towLocation: String? = null
    private var towRehearsal: TowRehearsal? = null
    private var roadsideEditor: AlertDialog? = null
    private var dProtection: DProtectionRun? = null
    private var dRestore: DProtectionRun? = null
    private var dPort: DVehicleProtectionPort? = null
    private var dRestorePort: DVehicleProtectionPort? = null
    private var dRanWall = 0L
    private var dResumed = false
    private var dBeltGeneration = 0L
    private val dBelts = mutableMapOf<String, Pair<DBeltReport, Long>>()
    private var destroyed = false
    private var visible = false

    private var lastSig = ""
    private var lastMotion: Motion? = null
    /** uptime of the last resume / window-focus gain; clicks right after it are not from a deliberate tap. */
    private var activatedAt = 0L
    private var qrCache: Triple<String, Bitmap, Boolean>? = null

    private val tick = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (visible) refresh()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(tag, "START pid=${android.os.Process.myPid()} restoredScreen=${Session.screen}")
        hub = VehicleHub(this) { main.post { if (!destroyed) refresh() } }
        LabBridge.onIntent(this, intent)
        notes = SeatNotes(this)
        prep = PrepStore(this)
        incidentLog = IncidentLog(File(noBackupFilesDir, "incident-passport.json"))
        restorePassportInputs()
        body = ScrollView(this).apply { setBackgroundColor(C_BG); isFillViewport = true }
        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(C_CARD)
        }
        // Figma frame: blossom-pink outer border around the light app surface. Left: fixed navigation rail
        // (reference 01 display), right: the scrolling screen.
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(C_FRAME)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(LinearLayout(this@ProductActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(rail, LinearLayout.LayoutParams(dp(128), MATCH_PARENT))
                addView(View(this@ProductActivity).apply { setBackgroundColor(C_LINE) }, LinearLayout.LayoutParams(dp(2), MATCH_PARENT))
                addView(body, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        // CAR_SPEED (motion gate) and FUSED_LOCATION (vehicle position) are runtime permissions; ask once together.
        val missing = listOf(PERM_SPEED, VehicleHub.PERM_FUSED).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        main.postDelayed(tick, 1000)
        tts = android.speech.tts.TextToSpeech(this, { status ->
            val engine = tts
            val lang = engine?.setLanguage(Locale.KOREAN)
            ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS &&
                lang != android.speech.tts.TextToSpeech.LANG_MISSING_DATA && lang != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
            Log.i(tag, "TTS_INIT status=$status lang=$lang engine=${engine?.defaultEngine} ready=$ttsReady engines=${engine?.engines?.map { it.name }}")
        }, TTS_ENGINE)
        refresh(force = true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p -> Log.i(tag, "PERMISSION $p granted=${grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED}") }
    }

    override fun onStart() { super.onStart(); visible = true; hub.start(); refresh(force = true) }
    override fun onResume() { super.onResume(); dResumed = true; activatedAt = SystemClock.uptimeMillis() }
    override fun onPause() {
        dResumed = false
        dProtection?.cancel("화면을 벗어나 남은 요청을 보내지 않았어요.")
        dRestore?.cancel("화면을 벗어나 남은 요청을 보내지 않았어요.")
        if (Session.screen == Screen.D_BELTS) clearDBelts()
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) activatedAt = SystemClock.uptimeMillis()
        Log.i(tag, "WINDOW_FOCUS $hasFocus")
    }
    /** Diagnostic: record key events (rotary/hardware/IME) so unexplained clicks can be traced. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.i(tag, "KEY ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} focus=${(currentFocus as? Button)?.text}")
        return super.dispatchKeyEvent(event)
    }
    override fun onStop() {
        visible = false
        dProtection?.cancel("화면을 벗어나 남은 요청을 보내지 않았어요.")
        dRestore?.cancel("화면을 벗어나 남은 요청을 보내지 않았어요.")
        clearDBelts()
        towRehearsal = null
        roadsideEditor?.dismiss()
        hub.stop()
        super.onStop()
    }
    override fun onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null); hub.release()
        try { tts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); activatedAt = SystemClock.uptimeMillis(); Log.i(tag, "onNewIntent reuse")
        LabBridge.onIntent(this, intent)
    }

    // --- state ------------------------------------------------------------------------------------

    private fun seatCells() = listOf(
        Triple("ROW_1_LEFT", "운전석", "SEAT_ROW_1_LEFT"),
        Triple("ROW_1_RIGHT", "조수석", "SEAT_ROW_1_RIGHT"),
        Triple("ROW_2_LEFT", "운전석 뒷좌석", "SEAT_ROW_2_LEFT"),
        Triple("ROW_2_RIGHT", "조수석 뒷좌석", "SEAT_ROW_2_RIGHT"),
    )

    @JvmName("refresh") internal fun refresh(force: Boolean = false) {
        if (destroyed) return
        val mob = motionNow()
        if (Session.screen == Screen.D_LAB) { dProtection?.recheck(); dRestore?.recheck() }
        if (Session.screen == Screen.ROADSIDE && !roadsideParked(mob)) {
            towLocation = null
            towRehearsal = null
            roadsideEditor?.dismiss()
        }
        if (Session.screen == Screen.NOTES && !roadsideParked(mob)) roadsideEditor?.dismiss()
        if (visible && incidentLog.active) observePassport()
        incidentLog.flush()
        if (mob.motion != lastMotion) {
            Log.i(tag, "MOTION=${mob.motion} (${mob.note}) speed=${mob.speedKmh} gear=${mob.gear} brake=${mob.parkingBrake}")
            lastMotion = mob.motion
        }
        val sig = signature(mob)
        if (!force && sig == lastSig) return
        lastSig = sig
        // RC4: a different screen always starts at the top. Only same-screen refreshes keep the scroll position.
        // Stage 1 fix: no immediate scrollTo(keep) for the same screen — before layout the new child has height 0, so
        // ScrollView clamps to 0 and one frame is drawn at the top (content jumps; a tap in that frame hits another button).
        val sameScreen = lastRendered == Session.screen
        val keep = if (sameScreen) body.scrollY else 0
        lastRendered = Session.screen
        body.removeCallbacks(restoreScroll)
        body.removeAllViews()
        body.addView(buildScreen(mob), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        buildRail(mob)
        if (!sameScreen) body.scrollTo(0, 0)
        pendingScroll = keep
        body.post(restoreScroll)
    }

    private var pendingScroll = 0
    private val restoreScroll = Runnable { body.scrollTo(0, pendingScroll) }

    /** Fresh vehicle fix for the lab relay panel (lab variant only calls it). */
    internal fun labFix(): VehicleFix? = hub.fix?.takeIf { it.fresh() }

    /** Single place for the motion decision. The lab variant can force a state for visual checks; submission returns it unchanged. */
    internal fun motionNow(): MobilityState = LabBridge.adjustMotion(MobilityEvaluator.evaluate(hub))

    private fun signature(mob: MobilityState): String = buildString {
        append(LabBridge.signature()).append('|')
        append(Session.screen).append('|').append(mob.motion).append('|').append(mob.note).append('|')
        append(mob.speedKmh?.toInt()).append('|').append(mob.gear).append('|').append(Session.notice).append('|')
        append(Session.guide.joinToString("")).append(Session.category).append(Session.includeState).append(Session.includeNotes)
        append(Session.injuriesBySeat).append(prep.handoverText).append(handoverBusy).append(prep.insurerName).append(prep.insurerPhone).append(prep.generalPhone).append(Session.qrReturn)
        if (Session.screen == Screen.ROADSIDE || Session.screen == Screen.NOTES) append('|').append(roadsideParked(mob))
        if (Session.screen == Screen.D_LAB) append('|').append(dParkBlock()).append(hub.fix?.text).append(hub.fix?.ageSec()).append(hub.locationStatus)
        if (Session.screen == Screen.D_BELTS) {
            for ((_, _, key) in seatCells()) {
                val reading = hub.reading(key)
                append('|').append(reading?.raw).append(reading?.status).append(hub.isFresh(reading)).append(dBeltLabel(key))
            }
        }
        if (Session.screen == Screen.PASSPORT) append(incidentLog.revision).append(incidentLog.error).append(hub.fix?.ageSec()?.div(5))
        append('|').append(hub.fix?.text).append(hub.locationStatus).append(hub.freshNaviAddress())
        for ((area, _, _) in seatCells()) append(notes.get(area))
        for (k in listOf("DOOR_ROW_1_LEFT", "DOOR_ROW_1_RIGHT",
            "DOOR_ROW_2_LEFT", "DOOR_ROW_2_RIGHT", "DOOR_REAR", "WIPER_FRONT", "LOWBEAM", "EV_SOC", "FUEL_PCT")) {
            val r = hub.reading(k); append(k).append(r?.raw).append(r?.status).append(hub.isFresh(r))
            if (k.startsWith("DOOR")) append(hub.doorOpenedSeen(k))
        }
    }

    private fun scheduleNoticeClear() {
        main.postDelayed({ if (Session.notice != null) { Session.notice = null; refresh() } }, 8000)
        refresh(force = true)
    }

    internal fun go(s: Screen) {
        if (s != Screen.D_LAB) {
            dProtection?.cancel("화면을 벗어나 남은 요청을 보내지 않았어요.")
            dRestore?.cancel("화면을 벗어나 남은 요청을 보내지 않았어요.")
            // Keep the finished run: the screen shows what was changed and offers undo on return.
        }
        if (s != Screen.D_BELTS) clearDBelts()
        if (s != Screen.ROADSIDE) {
            towLocation = null
            towRehearsal = null
        }
        if (s != Screen.QR) lastD5Display = null
        if (s == Screen.PASSPORT) restorePassportInputs()
        Session.screen = s; Session.notice = null
        body.scrollTo(0, 0)
        Log.i(tag, "SCREEN=$s")
        refresh(force = true)
    }

    @JvmName("startSituation") internal fun startSituation() {
        val mobility = motionNow()
        if (mobility.motion == Motion.MOVING) {
            refusal("이동 중에는 시작할 수 없습니다.", mobility)
            return
        }
        editSummary = false
        if (!beginPassport()) return
        Session.start()
        restorePassportInputs()
        hub.resetDoorHistory()
        go(Screen.GUIDE)
    }

    private fun confirmStoppedForGuide() {
        val mobility = motionNow()
        if (mobility.motion == Motion.MOVING) {
            refusal("이동 중에는 시작할 수 없습니다.", mobility)
            return
        }
        Session.stationaryConfirmed = true
        refresh(force = true)
    }

    private fun refusal(msg: String, mob: MobilityState) {
        Log.i(tag, "ASSIST_START_REFUSED motion=${mob.motion} reason=$msg")
        Session.notice = msg; scheduleNoticeClear()
    }

    // --- views ------------------------------------------------------------------------------------

    private fun buildScreen(mob: MobilityState): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(20), dp(32), dp(24)) }
        val s = Session.screen
        if (s != Screen.HOME) {
            val inFlow = s.step > 0 && !(s == Screen.QR && Session.qrReturn != null)
            // Reference 02/06 masthead on every screen: brand with pink rule | section name.
            col.addView(masthead(if (inFlow) "사고 상황 정리" else s.title))
            if (inFlow) col.addView(flowHeading(s))
        }
        Session.notice?.let { col.addView(card(C_WARN_BG, text("⚠ $it", 22f, true, C_WARN))) }
        if (s != Screen.HOME && s != Screen.ROADSIDE && s != Screen.SOS && s != Screen.SOS_LOC && s != Screen.D_LAB && s != Screen.D_BELTS &&
            mob.motion == Motion.MOVING) {
            col.addView(card(C_CARD, text("차가 움직이고 있습니다", 32f, true),
                text("정차한 뒤에 이용해 주세요. 진행한 내용은 보존됩니다.", 24f),
                text(mob.speedKmh?.let { "속도 ${it.toInt()} km/h" } ?: "속도 확인 불가", 20f, color = C_DIM)))
            return col
        }
        when (s) {
            Screen.HOME -> home(col, mob)
            Screen.GUIDE -> guide(col, mob)
            Screen.INJURY -> injury(col)
            Screen.CONTEXT -> context(col, mob)
            Screen.SUMMARY -> summary(col, mob)
            Screen.QR -> qr(col, mob)
            Screen.DONE -> done(col)
            Screen.NOTES -> notesScreen(col)
            Screen.PASSPORT -> passport(col)
            Screen.HANDOFF -> passportHandoff(col)
            Screen.ROADSIDE -> roadside(col, mob)
            Screen.SOS -> sosScreen(col, mob)
            Screen.SOS_LOC -> locationScreen(col)
            Screen.D_LAB -> dLab(col)
            Screen.D_BELTS -> dBeltScreen(col)
        }
        return col
    }

    /**
     * Reference 01 rail: four destinations with line icons, the current one on a blush tile, and the 119 shortcut
     * pinned at the bottom (it used to be repeated in every footer). Home has the 119 banner, so the bottom slot holds the round 112 quick button there (R2D).
     */
    private fun buildRail(mob: MobilityState) {
        rail.removeAllViews()
        rail.setPadding(0, dp(28), 0, dp(24))
        val s = Session.screen
        val moving = mob.motion == Motion.MOVING
        fun item(icon: Int, label: String, active: Boolean, enabled: Boolean, onTap: () -> Unit) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                if (active) background = GradientDrawable().apply { setColor(C_FRAME); cornerRadius = dp(20).toFloat() }
                addView(ImageView(this@ProductActivity).apply {
                    // Locked while moving: the lock glyph replaces the icon (PATH D+ drive_home rail), no colour-only cue.
                    setImageDrawable(getDrawable(if (enabled) icon else R.drawable.ic_lock)?.mutate()?.apply { setTint(if (enabled) C_TEXT else C_RAIL_OFF) })
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(text(label, 15f, active, if (enabled) C_TEXT else C_RAIL_OFF).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
                contentDescription = label
                isEnabled = enabled
                setOnClickListener {
                    if (SystemClock.uptimeMillis() - activatedAt < ACTIVATION_GUARD_MS) return@setOnClickListener
                    if (enabled) { Log.i(this@ProductActivity.tag, "CLICK rail '$label'"); onTap() }
                }
            }
            rail.addView(v, LinearLayout.LayoutParams(dp(104), dp(104)).apply { bottomMargin = dp(14) })
        }
        item(R.drawable.ic_home, "홈", s == Screen.HOME, true) { go(Screen.HOME) }
        item(R.drawable.ic_seat, "좌석", s == Screen.D_BELTS, !moving) { go(Screen.D_BELTS) }
        item(R.drawable.ic_protection, "보호 모드", s == Screen.D_LAB, !moving) { go(Screen.D_LAB) }
        item(R.drawable.ic_record, "사고 기록", s == Screen.PASSPORT || s == Screen.HANDOFF, true) { Log.i(tag, "PASSPORT_OPEN rail"); go(Screen.PASSPORT) }
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        // Bottom slot: 119 on every non-home screen (R2C behaviour); on Home the 119 banner exists, so the slot holds the 112 quick button.
        if (s != Screen.HOME && !LabBridge.hideRailEmergency(s)) rail.addView(button("119", true, color = C_EMERG) { openDial(HandoffCards.NUM_119, "rail") }.apply {
            contentDescription = "긴급 연락 119"
            setSingleLine(true)
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_EMERG) }
        }, LinearLayout.LayoutParams(dp(88), dp(88)))
        else if (s == Screen.HOME) rail.addView(railPoliceQuick(), LinearLayout.LayoutParams(dp(88), dp(88)))
    }

    /**
     * R2D 112 quick button (final layout 2026-11-14): round, blue, bottom of the rail on Home. One tap opens the dialer with 112
     * entered (ACTION_DIAL only); the call is never placed by the app. Only the number, big and centred with a small margin
     * (user decision: no handset icon). Main blue #2F6FD6 with a thin #8CB3F3 ring.
     */
    private fun railPoliceQuick(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_QUICK); setStroke(dp(3), C_QUICK_LINE) }
        addView(TextView(this@ProductActivity).apply {
            text = "112"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 50f)   // measured widths in the 127 px circle: 66sp = 96 px (too big, user), 60sp = 86 px, 50sp = ~73 px
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setSingleLine(true)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        contentDescription = "긴급 연락 112 경찰, 전화 화면 열기"
        isClickable = true
        setOnClickListener {
            if (SystemClock.uptimeMillis() - activatedAt < ACTIVATION_GUARD_MS) return@setOnClickListener
            Log.i(this@ProductActivity.tag, "CLICK rail '112'")
            openDial(HandoffCards.NUM_112, "rail")
        }
    }

    /** Reference 02/06 header: bold brand with a short pink underline, a hairline divider, then the section name. */
    internal fun masthead(section: String, right: String? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@ProductActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("RE:SAFETY", 30f, true).apply { setSingleLine(true); setPadding(0, 0, 0, dp(2)) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(View(this@ProductActivity).apply { setBackgroundColor(C_ACCENT) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(3)))
        })
        addView(View(this@ProductActivity).apply { setBackgroundColor(C_TEXT) }, LinearLayout.LayoutParams(dp(2), dp(44)).apply { marginStart = dp(30); marginEnd = dp(30) })
        addView(text(section, 28f, color = C_DIM), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        right?.let {
            // Lab simulated-event chip (red pill) vs. plain dim caption.
            if (it.startsWith("SIMULATED")) addView(text(it, 17f, true, Color.WHITE).apply {
                background = GradientDrawable().apply { setColor(C_EMERG); cornerRadius = dp(20).toFloat() }
                setPadding(dp(14), dp(5), dp(14), dp(5))
            })
            else addView(text(it, 19f, color = C_DIM))
        }
        setPadding(0, 0, 0, dp(14))
    }

    /** Editorial top rule used instead of a filled card (reference 02/06 sections have rules, not boxes). */
    private inner class RuleDrawable(private val color: Int, private val thickness: Float) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint().apply { this.color = this@RuleDrawable.color }
        override fun draw(canvas: android.graphics.Canvas) { canvas.drawRect(0f, 0f, bounds.width().toFloat(), thickness, p) }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Display serif for editorial numerals (device Noto Serif Bold; falls back to the platform serif). */
    private val serifBold: Typeface by lazy {
        runCatching { Typeface.createFromFile("/system/fonts/NotoSerif-Bold.ttf") }.getOrNull() ?: Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private fun footer(col: LinearLayout, actions: List<Button>, show119: Boolean = false, compactBack: Boolean = false) {
        // Anchor the action bar to the bottom of the panel. The tiny weight takes all spare space on short screens
        // but almost none when other content already expands (weights are shared proportionally).
        col.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.001f))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.forEachIndexed { index, action ->
            val small = compactBack && index == 0
            row.addView(action, LinearLayout.LayoutParams(if (small) dp(128) else 0, dp(76), if (small) 0f else 1f).apply {
                if (index < actions.lastIndex) marginEnd = dp(12)
            })
        }
        // The 119 shortcut now lives once in the navigation rail (same action, every non-home screen).
        if (false && show119) {
            val emergencyAction = button("119", true, color = C_EMERG) { openDial(HandoffCards.NUM_119, "shortcut") }
            emergencyAction.contentDescription = "긴급 연락 119"
            emergencyAction.setSingleLine(true)
            emergencyAction.setPadding(0, 0, 0, 0)
            emergencyAction.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_EMERG)
            }
            row.addView(emergencyAction, LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(28) })
        }
        col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
    }

    private fun nav(col: LinearLayout, back: Screen?, next: Screen?, nextLabel: String = "다음") {
        val actions = mutableListOf(button("정리 종료", false) {
            Log.i(tag, "ASSIST_ENDED by user at $s")
            Session.reset()
            refresh(force = true)
        })
        if (back != null) actions += button(if (back == Screen.HOME) "홈으로" else "이전", false) { go(back) }
        if (next != null) actions += button(nextLabel, true) { go(next) }
        footer(col, actions, show119 = true)
    }
    private val s get() = Session.screen

    /**
     * Home, R2 hierarchy (decided 2026-11-13): emergency (119 / 112) -> after the accident (protection, situation log,
     * seats, insurance/towing) -> records and preparation. All eight entries stay. One short caption per card; no speed or
     * gear here (the system already shows them). Heights are weights, so it adapts to the real window instead of fixed px.
     */
    private fun home(col: LinearLayout, mob: MobilityState) {
        if (LabBridge.homeAccident(this, col, mob)) return   // lab only: simulated accident event home
        val canStart = mob.motion != Motion.MOVING
        if (!canStart) { homeMoving(col); return }
        col.addView(masthead("사고 대응"))
        // Proximity: a section label sits closer to its own cards than to the previous group.
        fun label(t: String, first: Boolean = false) = text(t, 18f, true, C_DIM).apply { setPadding(dp(4), dp(if (first) 6 else 24), 0, 0) }
        // 1. emergency: keeps semantic colours and the largest touch area
        val emergencyLabel = label("긴급", first = true)
        val labLink = LabBridge.homeLabelExtra(this)   // lab: "SOS 받기 설정" entry that opens the SOS screen WITHOUT sending; submission: null
        if (labLink == null) col.addView(emergencyLabel) else col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(emergencyLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(labLink)
        })
        homeEmergencyBanner(col)
        // 2. after the accident: protection (green) and the guided log (pink tint) lead; seats and help follow
        col.addView(label("사고 후"))
        grid(col, listOf(
            homeBanner("보호 모드", "조명·문 잠금을 한 번에 요청", canStart, R.drawable.ic_protection, protect = true) { go(Screen.D_LAB) },
            homeBanner("사고 상황 정리", "행동 확인부터 QR까지 6단계", canStart, R.drawable.ic_situation, workflow = true) { startSituation() },
            homeBanner("좌석·안전벨트", "차량 보고와 직접 확인", canStart, R.drawable.ic_seats) { go(Screen.D_BELTS) },
            homeBanner("보험·견인", "연락과 현장 공유", true, R.drawable.ic_tow) {
                roadsideStep = 1
                Log.i(tag, "HELP_CARD_OPEN ROADSIDE")
                go(Screen.ROADSIDE)
            }
        ), expand = true, equalRows = true, rowWeights = listOf(1f, 1f))
        // 3. records and preparation: quiet secondary row
        col.addView(label("기록·준비"))
        footer(col, listOf(
            button("출발 전 확인", false, enabled = canStart) { go(Screen.NOTES) }.apply { homeIcon(R.drawable.ic_preparation) },
            button("사고 기록", false) { Log.i(tag, "PASSPORT_OPEN home"); go(Screen.PASSPORT) }.apply { homeIcon(R.drawable.ic_record) }
        ))
        if (Session.active) addBlock(col, button("진행 중인 상황 정리 이어하기", false) {
            go(Session.screen.takeIf { it != Screen.HOME && it != Screen.NOTES } ?: Screen.GUIDE)
        })
    }

    // --- R2D emergency banner (design fixed 2026-11-14) ----------------------------------------------------
    // 119 and SOS split the row 50:50 and share ONE geometry (height, width, padding, touch area, stroke width).
    // 119 keeps the red family, SOS is orange (main #C65A08 / fill #FFF3E6 / line #F0CCA4). 112 is NOT a banner: it is the round blue quick button at the bottom of the rail (main #2F6FD6 / halo #EAF2FF / line #8CB3F3).
    // White-based, flat: no gradients, no glow.

    /** One banner cell. Both emergency cells call this with different colours only. */
    internal fun bannerButton(title: String, sub: String, titleColor: Int, tint: Int, line: Int, description: String, minHeightDp: Int = 76,
                              iconRes: Int? = null, action: () -> Unit): Button =
        largeAction(title, true, true, C_STANDARD, action).apply {
            skin(tint, line, 3)
            minHeight = dp(minHeightDp)
            // si.png button composition (icon left, title + caption, chevron right), in the app's flat white style.
            if (iconRes != null) {
                val chevron = getDrawable(R.drawable.ic_chevron)?.mutate()?.apply { setTint(titleColor); setBounds(0, 0, dp(30), dp(30)) }
                setCompoundDrawables(tileIcon(iconRes, titleColor, Color.WHITE), null, chevron, null)
                compoundDrawablePadding = dp(18)
            }
            text = SpannableString("$title\n$sub").apply {
                setSpan(RelativeSizeSpan(1.25f), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(titleColor), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.8f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.NORMAL), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(C_DIM), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            contentDescription = description
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(if (iconRes != null) 24 else 30), paddingTop, dp(if (iconRes != null) 24 else 16), paddingBottom)
        }

    internal fun homeEmergencyBanner(col: LinearLayout) {
        val nearby = BuildConfig.NEARBY_SOS
        val b119 = bannerButton("119 전화 연결", "구급·화재 · 통화는 직접", C_EMERG, C_EMERG_BG, C_EMERG, "119 전화 연결, 전화 화면 열기", 76, R.drawable.ic_call) {
            openDial(HandoffCards.NUM_119, "home")
        }
        val bSos = bannerButton(if (nearby) "주변 SOS" else "SOS", if (nearby) "근처 요청 · 위치 확인" else "신고 안내 · 위치 · 기록",
            C_SOS, C_SOS_BG, C_SOS_LINE, if (nearby) "주변 SOS, SOS 화면 열기" else "SOS 안내, SOS 화면 열기", 76, R.drawable.ic_alert) {
            Log.i(tag, "SOS_OPEN home")
            go(Screen.SOS)
            LabBridge.sosTapped(this)   // lab: one tap starts the nearby-SOS request; submission: no-op
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        row.addView(b119, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(12) })
        row.addView(bSos, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.1f).apply { topMargin = dp(12) })
        // 112 is not a banner here: it is the round quick button at the bottom of the rail (buildRail / railPoliceQuick).
    }

    /** Flat text-only back link (PATH D+ 'sos' / 'location' screens: "← 홈", "← 돌아가기"). */
    internal fun backLink(label: String, target: Screen = Screen.HOME): View =
        button("←  $label", false) { go(target) }.apply {
            background = null
            setTextColor(C_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(24), 0)
            minHeight = dp(56)
            contentDescription = "$label 화면으로 돌아가기"
        }

    /**
     * SOS screen (PATH D+ 'sos', ported 2026-11-14): back link, 119 / 112 dialer cards, "현재 위치 확인" and "사고 기록 확인" cards,
     * the lab-only nearby-SOS panel, and the no-automation note. Open while moving, like R2C's rail record. Nothing is sent from here.
     */
    private fun sosScreen(col: LinearLayout, mob: MobilityState) {
        if (LabBridge.sosScreen(this, col, mob)) return   // lab: dedicated nearby-SOS status screen (no duplicate 119/112)
        col.addView(backLink("홈"), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        grid(col, listOf(
            bannerButton("119 전화 연결", "구급·화재 · 통화는 직접", C_EMERG, C_EMERG_BG, C_EMERG, "119 전화 연결, 전화 화면 열기", 104, R.drawable.ic_call) { openDial(HandoffCards.NUM_119, "sos") },
            bannerButton("112 전화 연결", "경찰 · 통화는 직접", C_POLICE, C_POLICE_BG, C_POLICE, "112 전화 연결, 전화 화면 열기", 104, R.drawable.ic_call) { openDial(HandoffCards.NUM_112, "sos") }
        ))
        grid(col, listOf(
            homeBanner("현재 위치 확인", "전화로 직접 말할 위치", true, R.drawable.ic_pin) { Log.i(tag, "LOCATION_OPEN sos"); go(Screen.SOS_LOC) },
            homeBanner("사고 기록 확인", "기록 보기", true, R.drawable.ic_record) { Log.i(tag, "PASSPORT_OPEN sos"); go(Screen.PASSPORT) }
        ))
        addBlock(col, text("자동 신고·자동 위치 전송은 없습니다. 119·112는 직접 눌러 전화 화면을 열고, 통화는 직접 겁니다.", 20f, color = C_DIM))
    }

    /** Location screen (PATH D+ 'location'): source / place / coordinates as rows, then the dialer. Never sends the position anywhere. */
    private fun locationScreen(col: LinearLayout) {
        col.addView(backLink("돌아가기", Screen.SOS), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val fix = hub.fix?.takeIf { it.fresh() }
        val rows: List<Pair<String, String>> = if (fix != null) listOf(
            "출처" to "차량 좌표 · ${fix.ageSec()}초 전",
            "장소" to (hub.freshNaviAddress() ?: "내비 주소 없음"),
            "좌표" to fix.text
        ) else listOf(
            "출처" to "사용할 수 없어요",
            "이유" to hub.locationStatus
        )
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(C_CARD); setStroke(dp(2), C_LINE); cornerRadius = dp(18).toFloat() }
            setPadding(dp(24), dp(18), dp(24), dp(20))
        }
        panel.addView(text("현재 위치 확인", 28f, true))
        rows.forEach { (k, v) ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(text(k, 22f, false, C_DIM), LinearLayout.LayoutParams(dp(110), WRAP_CONTENT))
            r.addView(text(v, 24f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            panel.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        panel.addView(text(
            if (fix != null) "좌표와 함께 도로 이름·방향도 말하세요. 이 앱은 위치를 자동으로 전송하지 않습니다. 전화로 직접 말하세요."
            else "위치 신호가 없으면 사고 기록에서 직접 입력하거나, 이정표·출구·건물 이름을 전화로 말하세요.", 20f, false, C_DIM).apply { setPadding(0, dp(10), 0, dp(12)) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("119 전화 연결", true, color = C_TEXT) { openDial(HandoffCards.NUM_119, "location") },
            LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginEnd = dp(12) })
        actions.addView(button("사고 기록 열기", false) { Log.i(tag, "PASSPORT_OPEN location"); go(Screen.PASSPORT) },
            LinearLayout.LayoutParams(0, dp(76), 1f))
        panel.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addBlock(col, panel)
    }

    /**
     * Home while moving (PATH D+ 'drive_home', ported): emergency banners stay open, the after-stop features are grouped as locked cards
     * with lock tiles and the same "정차 후 이용" caption R2C uses, and the records / insurance cards stay open.
     * On the Pleos emulator the system blocks the whole app window when the gear is not P, so this layout is reachable there only through the lab motion override.
     */
    private fun homeMoving(col: LinearLayout) {
        col.addView(masthead("사고 대응", LabBridge.mastRight()))
        fun label(t: String, first: Boolean = false) = text(t, 18f, true, C_DIM).apply { setPadding(dp(4), dp(if (first) 6 else 24), 0, 0) }
        col.addView(label("긴급", first = true))
        homeEmergencyBanner(col)
        col.addView(label("정차 후 이용"))
        grid(col, listOf(
            homeBanner("보호 모드", "조명·문 잠금", false, R.drawable.ic_lock, protect = true) { go(Screen.D_LAB) },
            homeBanner("사고 상황 정리", "6단계", false, R.drawable.ic_lock, workflow = true) { startSituation() },
            homeBanner("좌석·안전벨트", "차량 보고", false, R.drawable.ic_lock) { go(Screen.D_BELTS) },
            homeBanner("출발 전 확인", "운행 전 기록", false, R.drawable.ic_lock) { go(Screen.NOTES) }
        ), expand = true, equalRows = true, rowWeights = listOf(1f, 1f))
        col.addView(label("주행 중에도 이용"))
        grid(col, listOf(
            homeBanner("보험·견인", "연락과 현장 공유", true, R.drawable.ic_tow) {
                roadsideStep = 1
                Log.i(tag, "HELP_CARD_OPEN ROADSIDE")
                go(Screen.ROADSIDE)
            },
            homeBanner("사고 기록 확인", "기록 보기", true, R.drawable.ic_record) { Log.i(tag, "PASSPORT_OPEN home"); go(Screen.PASSPORT) }
        ), expand = true, equalRows = true, rowWeights = listOf(1f))
        if (Session.active) addBlock(col, button("진행 중인 상황 정리 이어하기", false, enabled = false) { })
    }

    private fun Button.homeIcon(res: Int) {
        val icon = getDrawable(res)?.mutate() ?: return
        icon.setTint(if (isEnabled) C_TEXT else C_DIM)
        icon.setBounds(0, 0, dp(38), dp(38))
        setCompoundDrawables(icon, null, null, null)
        compoundDrawablePadding = dp(14)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(24), paddingTop, dp(16), paddingBottom)
    }

    /** Rounded icon tile (Figma home): coloured square behind a white or plum line icon. */
    private fun tileIcon(res: Int, tile: Int, glyph: Int): android.graphics.drawable.Drawable {
        val size = dp(54)
        val bg = GradientDrawable().apply { setColor(tile); cornerRadius = dp(14).toFloat() }
        val icon = getDrawable(res)!!.mutate().apply { setTint(glyph) }
        return LayerDrawable(arrayOf(bg, icon)).apply {
            setLayerInset(1, dp(11), dp(11), dp(11), dp(11))
            setBounds(0, 0, size, size)
        }
    }

    internal fun Button.skin(bg: Int, stroke: Int, strokeDp: Int = 2) {
        background = GradientDrawable().apply { setColor(bg); setStroke(dp(strokeDp), stroke); cornerRadius = dp(18).toFloat() }
    }

    /** 119 / 112: tinted card with a coloured border and a large number. Opens the dialer only, as before. */
    private fun homeEmergency(number: String, label: String, accent: Int, tint: Int, action: () -> Unit): Button =
        largeAction("$number $label", true, true, C_STANDARD, action).apply {
            skin(tint, accent, 3)
            minHeight = dp(76)
            text = SpannableString("$number   $label").apply {
                setSpan(RelativeSizeSpan(1.25f), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(accent), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.68f), number.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.NORMAL), number.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(C_DIM), number.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            contentDescription = "$number $label, 전화 화면 열기"
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(40), paddingTop, dp(16), paddingBottom)
        }

    internal fun homeBanner(title: String, subtitle: String, enabled: Boolean, iconRes: Int, protect: Boolean = false,
                           workflow: Boolean = false, action: () -> Unit): Button =
        largeAction(title, true, enabled, C_STANDARD, action).apply {
            val detail = if (enabled) subtitle else "정차 후 이용"
            text = SpannableString("$title\n$detail").apply {
                setSpan(RelativeSizeSpan(0.68f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.NORMAL), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(C_DIM), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (protect && enabled) setSpan(android.text.style.ForegroundColorSpan(C_OK), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (protect && enabled) skin(C_OK_BG, C_OK, 3)
            else if (workflow && enabled) skin(C_SOFT, C_ACCENT, 3)   // guided accident workflow: the one pink-accent entry
            else skin(if (enabled) C_CARD else C_DISABLED, C_LINE)
            val tile = if (protect && enabled) C_OK else if (workflow && enabled) C_ACCENT else C_TILE
            val glyph = if (protect && enabled) Color.WHITE else C_TILE_ICON
            setCompoundDrawables(tileIcon(iconRes, tile, glyph), null, null, null)
            compoundDrawablePadding = dp(18)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(24), paddingTop, dp(16), paddingBottom)
        }

    private fun dParkBlock(): String? =
        DParkGate.blocked(hub.reading("SPEED"), hub.reading("GEAR"), SystemClock.elapsedRealtime())

    // --- D plan F1: protect mode (real Pleos Vehicle SDK) ------------------------------------------
    // User tap only. DParkGate (fresh P + 0 km/h) is re-checked before every step by DProtectionRun.
    // Undo writes back only what this app changed, using the values read before the change.

    private val dDispatch: (Long, () -> Unit) -> Unit = { delay, work -> main.postDelayed({ work() }, delay) }
    private var dLogged: DProtectionRun? = null
    private fun dRedraw() = main.post {
        // Once per finished run: keep what the vehicle reported back in the incident record (if one is open).
        for ((run, restoring) in listOf(dProtection to false, dRestore to true)) {
            if (run == null || run.running || dLogged === run || run.actions.all { run.result(it).outcome == DOutcome.WAITING }) continue
            dLogged = run
            val items = run.actions.map { "${it.label} ${dOutcomeText(it, run.result(it).outcome, restoring)}".replace(" · ", " ") }
            Log.i(tag, "PROTECT_RESULT restoring=$restoring ${items.joinToString(" / ")}")
            if (incidentLog.active) {
                // Header first, then one item per entry: the record screen puts each " / " part on its own line.
                incidentLog.add(LogEvent(System.currentTimeMillis(), EventKind.HANDOFF,
                    (listOf(if (restoring) "되돌리기 결과" else "보호 모드 결과") + items).joinToString(" / "), value = LocalAction.PROTECT_RESULT.name))
                incidentLog.flush()
            }
            speak(dSpoken(run, restoring))
        }
        if (!destroyed && visible && Session.screen == Screen.D_LAB) refresh(force = true)
    }
    private fun dGate() = if (dResumed && visible && Session.screen == Screen.D_LAB) dParkBlock() else "보호 모드 화면을 벗어났어요."

    private fun startDProtection() {
        if (!dResumed || !visible || Session.screen != Screen.D_LAB || dParkBlock() != null || dBusy()) return
        val port = DVehicleProtectionPort(hub, { work -> main.post(work) })
        dPort = port
        dRestore = null
        dRanWall = System.currentTimeMillis()
        dProtection = DProtectionRun(port, ::dGate, dDispatch, changed = ::dRedraw).also { it.start() }
        Log.i(tag, "PROTECT_START user tap")
        passportAction(LocalAction.PROTECT_REQUESTED)
    }

    /** Actions whose state this app wrote (or may have written) and whose original value is known. */
    private fun dUndoable(): List<DActuator> {
        val run = dProtection ?: return emptyList()
        val port = dPort ?: return emptyList()
        if (run.running) return emptyList()
        return DActuator.entries.filter { port.wrote(it) && port.originals.has(it) &&
            dRestore?.takeIf { r -> r.has(it) }?.result(it)?.outcome.let { o -> o != DOutcome.VERIFIED && o != DOutcome.ALREADY } }
    }

    private fun startDRestore() {
        val port = dPort ?: return
        val undo = dUndoable()
        if (undo.isEmpty() || !dResumed || !visible || dParkBlock() != null || dBusy()) return
        val restorePort = DVehicleProtectionPort(hub, { work -> main.post(work) }, restore = port.originals)
        dRestorePort = restorePort
        dRestore = DProtectionRun(restorePort, ::dGate, dDispatch, undo, alwaysWrite = true, changed = ::dRedraw).also { it.start() }
        Log.i(tag, "PROTECT_UNDO user tap ${undo.joinToString()}")
        passportAction(LocalAction.PROTECT_UNDONE)
    }

    /** Short sentence for the driver's ears: only what the vehicle reported back, then the hazard reminder. */
    private fun dSpoken(run: DProtectionRun, restoring: Boolean): String {
        val parts = run.actions.mapNotNull { a ->
            when (run.result(a).outcome) {
                DOutcome.VERIFIED -> if (restoring) "${a.label} 원래대로" else "${a.label} ${a.target}"
                DOutcome.ALREADY -> if (restoring) null else "${a.label} 이미 ${a.target}"
                DOutcome.UNSUPPORTED -> null
                else -> "${a.label} 확인 안 됨"
            }
        }
        val head = if (restoring) "되돌렸어요." else "보호 모드 결과."
        return listOf(head, parts.joinToString(", ") + ".", if (restoring) "" else "비상등은 직접 켜세요.").joinToString(" ").trim()
    }

    // --- D plan F4: spoken result (platform TextToSpeech; engine is the head unit's, no extra permission) ---
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private fun speak(textToSay: String) {
        val engine = tts
        if (engine == null || !ttsReady) { Log.i(tag, "TTS_SKIPPED ready=$ttsReady"); return }
        val r = engine.speak(textToSay, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "protect-${SystemClock.uptimeMillis()}")
        Log.i(tag, "TTS_SPEAK result=$r text=$textToSay")
    }

    private fun dBusy() = dProtection?.running == true || dRestore?.running == true

    /** Short result words, one glance: what the vehicle confirmed, not what we asked. */
    private fun dOutcomeText(action: DActuator, outcome: DOutcome, restoring: Boolean): String = when (outcome) {
        DOutcome.VERIFIED -> if (restoring) "원래대로 확인" else "${action.target} 확인"
        DOutcome.ALREADY -> if (restoring) "이미 원래대로" else "이미 ${action.target}"
        DOutcome.MISMATCH -> "반영 대기"
        DOutcome.UNSUPPORTED -> "미지원 · 건너뜀"
        DOutcome.DENIED -> "권한 없음"
        DOutcome.WAITING -> "대기"
        DOutcome.UNCERTAIN -> "확인 필요"
        DOutcome.STOPPED -> "중단"
        else -> outcome.label
    }

    private fun dOutcomeColor(outcome: DOutcome): Int = when (outcome) {
        DOutcome.VERIFIED, DOutcome.ALREADY -> C_OK
        DOutcome.MISMATCH, DOutcome.UNCERTAIN, DOutcome.ERROR, DOutcome.TIMEOUT, DOutcome.DENIED, DOutcome.NO_VALUE -> C_WARN
        DOutcome.UNSUPPORTED, DOutcome.WAITING, DOutcome.STOPPED -> C_DIM
        else -> C_TEXT
    }

    private fun dLab(col: LinearLayout) {
        val reason = dParkBlock()
        val run = dProtection
        val restore = dRestore
        // Line 1: can it run right now. Line 2: the one thing this feature cannot do.
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@ProductActivity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (reason == null) C_OK else C_WARN) } },
                LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(12) })
            addView(text(reason ?: "정차 확인됨", 24f, true, if (reason == null) C_OK else C_WARN), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(text("누를 때만 실행 · 비상등은 직접 켜세요", 20f, color = C_DIM))
        })
        (restore?.stoppedBecause ?: run?.stoppedBecause)?.let { col.addView(text(it, 21f, true, C_WARN)) }
        val shown = DActuator.entries.map { action ->
            val restoring = restore?.has(action) == true
            val result = if (restoring) restore?.result(action) else run?.result(action)
            Triple(action, restoring, result)
        }
        // One summary line once something has run: counts only, no repeated detail.
        val ran = shown.filter { it.third?.phase != null || it.third?.outcome == DOutcome.STOPPED }
        if (ran.isNotEmpty() && run?.running != true && restore?.running != true) {
            val ok = ran.count { it.third?.outcome == DOutcome.VERIFIED || it.third?.outcome == DOutcome.ALREADY }
            val skip = ran.count { it.third?.outcome == DOutcome.UNSUPPORTED }
            val check = ran.size - ok - skip
            col.addView(text(listOfNotNull("확인 ${ok}", "건너뜀 ${skip}".takeIf { skip > 0 }, "확인 필요 ${check}".takeIf { check > 0 })
                .joinToString(" · "), 25f, true, if (check > 0) C_WARN else C_OK))
        }
        // Screen-specific composition: white SUV map (where each actuator is) | numbered result rows (what the car read back).
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(C_CARD); cornerRadius = dp(20).toFloat(); setStroke(dp(1), C_HAIR) }
            setPadding(dp(28), dp(8), dp(28), dp(8))
        }
        shown.forEachIndexed { index, (action, restoring, result) ->
            val outcome = result?.outcome ?: DOutcome.WAITING
            val evidence = (if (restoring) dRestorePort else dPort)?.lampState?.get(action)
            val detail = when {
                result?.phase == null -> action.why
                outcome == DOutcome.UNSUPPORTED -> "나머지는 계속 실행"
                outcome == DOutcome.MISMATCH || outcome == DOutcome.UNCERTAIN -> "늦게 반영될 수 있어요 · 되돌리기 가능"
                evidence != null -> "차량 응답 · $evidence"
                else -> "실행 ${passportTime(dRanWall).takeLast(8)}"
            }
            if (index > 0) list.addView(View(this).apply { setBackgroundColor(C_HAIR) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))
            list.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text("%02d".format(index + 1), 30f, true, C_PLUM).apply { typeface = serifBold }, LinearLayout.LayoutParams(dp(72), WRAP_CONTENT))
                addView(LinearLayout(this@ProductActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(action.label, 24f, true))
                    addView(text(detail, 18f, color = C_DIM))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(text(dOutcomeText(action, outcome, restoring), 22f, true, dOutcomeColor(outcome)).apply { gravity = Gravity.END; minWidth = dp(190) })   // fixed result column: all rows end on one edge
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        val stage = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        stage.addView(protectMap(shown.map { (a, _, r) -> a to (r?.outcome ?: DOutcome.WAITING) }), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        stage.addView(list, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.45f).apply { marginStart = dp(20) })
        col.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        addBlock(col, text(vehicleLocationLine(), 22f, true, if (hub.fix?.fresh() == true) C_TEXT else C_DIM))
        val actions = mutableListOf<Button>()   // home is in the rail
        val active = if (restore?.running == true) restore else run?.takeIf { it.running }
        if (active != null) actions += button("중단", false) { active.cancel("사용자가 중단했어요. 남은 요청은 보내지 않았어요.") }
        else {
            val undo = dUndoable()
            if (undo.isNotEmpty()) actions += button("되돌리기 (${undo.size})", false, enabled = reason == null, onClick = ::startDRestore)
            actions += button(if (run == null) "보호 모드 켜기" else "다시 실행", true, enabled = reason == null, onClick = ::startDProtection)
        }
        footer(col, actions, show119 = true)
    }
    // --- D plan F2: vehicle location line ------------------------------------------------------------
    /** One readable line: coordinates + age, or why there is none. Never a guess. */
    private fun vehicleLocationLine(): String {
        val fix = hub.fix
        val place = hub.freshNaviAddress()?.let { "$it · " }.orEmpty()
        return when {
            fix != null && fix.fresh() -> if (place.isNotEmpty()) "차량 위치 · ${place.removeSuffix(" · ")}" else "차량 위치 · ${fix.text}"
            fix != null -> "차량 위치가 오래됐어요 · 공유하지 않아요"
            else -> "차량 위치 없음 · 직접 말하거나 입력하세요"
        }.let { line -> if (hub.naviDisagrees() && hub.fix?.fresh() == true) "$line · 주소는 내비와 달라 뺐어요" else line }
    }

    /** Fresh vehicle coordinates for sharing, or null (stale / none). */
    private fun shareableFix(): String? = hub.fix?.takeIf { it.fresh() }?.let { fix ->
        listOfNotNull(hub.freshNaviAddress(), fix.text).joinToString(" · ") + " (${passportTime(System.currentTimeMillis())} 기준)"
    }

    private fun clearDBelts() {
        dBeltGeneration++
        dBelts.clear()
    }

    private fun dBeltLabel(key: String): String {
        val (report, time) = dBelts[key] ?: return DBeltReport.WAITING.label
        return if ((report == DBeltReport.BUCKLED || report == DBeltReport.UNBUCKLED) &&
            SystemClock.elapsedRealtime() - time > 12_000L) DBeltReport.STALE.label else report.label
    }

    private fun requestDBelts() {
        if (!dResumed || !visible || Session.screen != Screen.D_BELTS || dParkBlock() != null) return
        clearDBelts()
        val request = dBeltGeneration
        for ((_, _, key) in seatCells()) dBelts[key] = DBeltReport.READING to SystemClock.elapsedRealtime()
        refresh(force = true)
        fun active() = !destroyed && dResumed && visible && request == dBeltGeneration &&
            Session.screen == Screen.D_BELTS && dParkBlock() == null
        hub.requestBeltReports { key, report ->
            main.post {
                if (active() && dBelts[key]?.first == DBeltReport.READING) {
                    dBelts[key] = report to SystemClock.elapsedRealtime()
                    refresh(force = true)
                }
            }
        }
        main.postDelayed({
            if (active()) {
                dBelts.replaceAll { _, entry ->
                    if (entry.first == DBeltReport.READING) DBeltReport.TIMEOUT to SystemClock.elapsedRealtime() else entry
                }
                refresh(force = true)
            }
        }, 3_500L)
    }

    private fun dBeltScreen(col: LinearLayout) {
        val reason = dParkBlock()
        if (reason != null) {
            clearDBelts()
            addBlock(col, statusCard("주차한 뒤 조회", reason))
            footer(col, listOf(button("홈으로", false) { go(Screen.HOME) }))
            return
        }
        // Reference 06, measured: left column 0.025-0.23, car 0.29-0.62, rule at 0.65, right column 0.68-0.975 of the
        // width; full-width rule at 0.725 of the height above the vehicle-status strip. 01 and 02 are never merged.
        val seats = seatRows()
        fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun rule() = View(this).apply { setBackgroundColor(C_LINE) }
        fun swatch(p: SeatPresence) = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                when (p) {
                    // three states differ by fill, outline AND word (never colour alone): solid pink + dark outline / white + thin grey / white + dashed dark
                    SeatPresence.POSSIBLY_OCCUPIED -> { setColor(C_ACCENT); setStroke(dp(3), C_TEXT) }
                    SeatPresence.REPORTED_EMPTY -> { setColor(C_EMPTY_SEAT); setStroke(dp(2), C_SEAT_LINE) }
                    else -> { setColor(Color.WHITE); setStroke(dp(3), C_TEXT, dp(6).toFloat(), dp(4).toFloat()) }
                }
            }
        }
        val left = column().apply {
            addView(editorialHeading("01", "차량 보고", "차량이 기록한 좌석 정보예요.\n사람이 확인한 내용이 아니에요.", C_TEXT))
            // The vehicle's per-seat report lives ONCE, on the map. This column is only the legend for it.
            for ((presence, word) in listOf(
                SeatPresence.POSSIBLY_OCCUPIED to "착석 가능성 있음\n상태는 불확실해요",
                SeatPresence.REPORTED_EMPTY to "빈 좌석으로 보고",
                SeatPresence.UNKNOWN to "상태 확인 불가")) {
                addView(rule(), LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))
                addView(LinearLayout(this@ProductActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(swatch(presence), LinearLayout.LayoutParams(dp(40), dp(30)).apply { marginEnd = dp(18) })
                    addView(text(word, 22f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
            addView(rule(), LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))
        }
        val right = column().apply {
            addView(editorialHeading("02", "직접 확인", "현장에서 좌석을 직접 확인하고\n결과를 기록해요.", C_PLUM))
            seats.forEach { s ->
                addView(rule(), LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))
                addView(LinearLayout(this@ProductActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(8), 0)
                    if (s.human) setBackgroundColor(C_SOFT)
                    // seat name once, with its belt reading (shown here only); the human check is the box on the right
                    addView(column().apply {
                        addView(text(s.label, 22f, true))
                        addView(text("벨트 · ${s.belt}", 18f, color = C_DIM).apply { setSingleLine(true) })   // short + one line: never breaks inside a word
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(text(if (s.human) "직접 확인함" else "확인 필요", 22f, s.human).apply {
                        setSingleLine(true)   // never break inside the status word
                        setCompoundDrawables(checkBoxDrawable(s.human), null, null, null)
                        compoundDrawablePadding = dp(14)
                        gravity = Gravity.CENTER_VERTICAL
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
            addView(rule(), LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))
        }
        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        main.addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.95f))
        main.addView(seatMap(seats), LinearLayout.LayoutParams(0, MATCH_PARENT, 1.25f).apply { marginStart = dp(12); marginEnd = dp(12) })
        main.addView(View(this).apply { setBackgroundColor(C_LINE) }, LinearLayout.LayoutParams(dp(2), MATCH_PARENT).apply { marginEnd = dp(28) })
        main.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.3f))
        col.addView(main, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(text("직접 확인은 사고 기록의 ‘좌석 직접 확인’에서 남겨요. 버클 신호로 탑승·부상·대피를 판단하지 않아요.", 18f, color = C_DIM).apply { setPadding(0, dp(8), 0, dp(8)) })
        // gear / speed / wiper strip removed: gear and speed are already shown by the Pleos system area
        footer(col, listOf(
            button("안전벨트 조회", true, enabled = dBelts.values.none { it.first == DBeltReport.READING }, onClick = ::requestDBelts)))
    }

    /**
     * Reference 02/06 bottom strip: full-width rule, "차량 상태" title, then gear / speed / wiper as large values with
     * captions between hairline dividers, and the screen's action at the right end. Values are live readings or "신호 없음".
     */
    private fun vehicleStrip(actions: List<Button>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val mob = motionNow()
        addView(View(this@ProductActivity).apply { setBackgroundColor(C_TEXT) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))
        addView(LinearLayout(this@ProductActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(4))
            fun divider() = View(this@ProductActivity).apply { setBackgroundColor(C_LINE) }
            addView(text("차량 상태", 28f, true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(28) })
            for ((value, caption) in listOf(
                gearLabel(mob.gear).let { if (it.startsWith("raw=")) "?" else it } to "기어",
                (mob.speedKmh?.let { "%.0f".format(it) } ?: "—") to "속도 km/h",
                wiperShort() to "와이퍼")) {
                addView(divider(), LinearLayout.LayoutParams(dp(2), dp(64)))
                addView(LinearLayout(this@ProductActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(text(value, 30f, true).apply { gravity = Gravity.CENTER; includeFontPadding = false })
                    addView(text(caption, 16f, color = C_DIM).apply { gravity = Gravity.CENTER })
                }, LinearLayout.LayoutParams(dp(150), WRAP_CONTENT))
            }
            addView(divider(), LinearLayout.LayoutParams(dp(2), dp(64)).apply { marginEnd = dp(24) })
            actions.forEachIndexed { i, b -> addView(b, LinearLayout.LayoutParams(0, dp(76), 1f).apply { if (i > 0) marginStart = dp(12) }) }
        })
    }

    // ===== Vehicle graphics (references 01 / 02 / 06 / 07) ==========================================
    // One renderer for every vehicle view in the app. Vector, drawn at runtime so seat and lamp states stay data-driven.
    // It is an illustration of the cabin, not a photograph, and it never invents a sensor value.

    private fun shade(color: Int, f: Float): Int {
        val r = (Color.red(color) * f).toInt().coerceIn(0, 255); val g = (Color.green(color) * f).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * f).toInt().coerceIn(0, 255); return Color.rgb(r, g, b)
    }
    private fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** ambient: 0 = blush trim (material only), 1 = lit by the vehicle (confirmed), 2 = off. */
    private fun ambientLine(c: android.graphics.Canvas, p: android.graphics.Paint, x0: Float, y0: Float, x1: Float, y1: Float, mode: Int) {
        p.shader = null; p.pathEffect = null; p.style = android.graphics.Paint.Style.STROKE; p.strokeCap = android.graphics.Paint.Cap.ROUND
        if (mode == 1) for (k in 4 downTo 1) { p.color = alpha(C_ACCENT, 34); p.strokeWidth = dp(3 + k * 5).toFloat(); c.drawLine(x0, y0, x1, y1, p) }
        p.color = when (mode) { 1 -> C_ACCENT; 2 -> C_SEAT_LINE; else -> C_FRAME }
        p.strokeWidth = dp(if (mode == 1) 4 else 3).toFloat(); c.drawLine(x0, y0, x1, y1, p)
    }

    /**
     * White SUV cabin cutaway, front up: ground shadow, tyres, pearl body with side shading, tinted windshield with a
     * reflection, ivory dashboard with the black centre display and an ambient line, spoked steering wheel, console with
     * two lit cupholders, door ambient strips, rear glass and the tail-lamp line. Returns the four seat boxes
     * (driver, passenger, rear-left, rear-right); seats are drawn by [drawSeat] so callers can colour them by live state.
     */
    private fun drawCabin(c: android.graphics.Canvas, p: android.graphics.Paint, l: Float, t: Float, bw: Float, bh: Float,
                          ambient: Int = 0): List<android.graphics.RectF> {
        val cx = l + bw / 2
        val ink = 0xFF2B2628.toInt(); val line = maxOf(dp(2).toFloat(), bw * 0.006f)
        fun R(x0: Float, y0: Float, x1: Float, y1: Float) = android.graphics.RectF(l + bw * x0, t + bh * y0, l + bw * x1, t + bh * y1)
        p.shader = null; p.pathEffect = null; p.strokeCap = android.graphics.Paint.Cap.ROUND
        // ground shadow
        p.style = android.graphics.Paint.Style.FILL
        for (i in 4 downTo 1) { p.color = Color.argb(13, 60, 20, 35); val g = bw * 0.018f * i
            c.drawRoundRect(l - g * 0.6f, t + g * 0.5f, l + bw + g * 0.6f, t + bh + g, bw * 0.42f, bw * 0.42f, p) }
        // tyres, half hidden under the body
        for (yy in listOf(0.135f, 0.715f)) for (left in listOf(true, false)) {
            val tw = bw * 0.1f; val th = bh * 0.125f; val x = if (left) l - tw * 0.4f else l + bw - tw * 0.6f
            val tyre = android.graphics.RectF(x, t + bh * yy, x + tw, t + bh * yy + th)
            p.style = android.graphics.Paint.Style.FILL; p.color = 0xFF2A2729.toInt(); c.drawRoundRect(tyre, tw * 0.38f, tw * 0.38f, p)
            p.color = 0xFF4A4548.toInt(); p.strokeWidth = dp(2).toFloat(); p.style = android.graphics.Paint.Style.STROKE
            var ty = tyre.top + th * 0.18f; while (ty < tyre.bottom - th * 0.1f) { c.drawLine(tyre.left + tw * 0.2f, ty, tyre.right - tw * 0.2f, ty, p); ty += th * 0.16f }
        }
        // body: rounder nose than tail; pearl white with darker flanks so it reads as a rounded shell
        val body = android.graphics.Path().apply {
            addRoundRect(android.graphics.RectF(l, t, l + bw, t + bh),
                floatArrayOf(bw * 0.4f, bh * 0.14f, bw * 0.4f, bh * 0.14f, bw * 0.26f, bh * 0.075f, bw * 0.26f, bh * 0.075f),
                android.graphics.Path.Direction.CW)
        }
        p.style = android.graphics.Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(l, 0f, l + bw, 0f, intArrayOf(0xFFE6DFE2.toInt(), Color.WHITE, Color.WHITE, 0xFFE6DFE2.toInt()),
            floatArrayOf(0f, 0.2f, 0.8f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawPath(body, p); p.shader = null
        p.style = android.graphics.Paint.Style.STROKE; p.color = ink; p.strokeWidth = line; c.drawPath(body, p)
        // hood creases and headlamps
        p.color = 0xFFD6CCD1.toInt(); p.strokeWidth = dp(2).toFloat()
        c.drawPath(android.graphics.Path().apply { moveTo(l + bw * 0.27f, t + bh * 0.03f); quadTo(l + bw * 0.25f, t + bh * 0.1f, l + bw * 0.3f, t + bh * 0.16f) }, p)
        c.drawPath(android.graphics.Path().apply { moveTo(l + bw * 0.73f, t + bh * 0.03f); quadTo(l + bw * 0.75f, t + bh * 0.1f, l + bw * 0.7f, t + bh * 0.16f) }, p)
        p.color = 0xFF3A3538.toInt(); p.strokeWidth = bh * 0.009f
        c.drawLine(l + bw * 0.13f, t + bh * 0.045f, l + bw * 0.33f, t + bh * 0.022f, p)
        c.drawLine(l + bw * 0.87f, t + bh * 0.045f, l + bw * 0.67f, t + bh * 0.022f, p)
        // side mirrors
        for (left in listOf(true, false)) {
            val mx = if (left) l - bw * 0.085f else l + bw - bw * 0.01f
            val m = android.graphics.RectF(mx, t + bh * 0.2f, mx + bw * 0.095f, t + bh * 0.232f)
            p.style = android.graphics.Paint.Style.FILL; p.color = 0xFFF7F3F5.toInt(); c.drawRoundRect(m, bw * 0.03f, bw * 0.03f, p)
            p.style = android.graphics.Paint.Style.STROKE; p.color = ink; p.strokeWidth = line; c.drawRoundRect(m, bw * 0.03f, bw * 0.03f, p)
        }
        // windshield: tinted glass with one soft reflection
        val glass = android.graphics.Path().apply {
            moveTo(l + bw * 0.17f, t + bh * 0.165f); quadTo(cx, t + bh * 0.14f, l + bw * 0.83f, t + bh * 0.165f)
            lineTo(l + bw * 0.91f, t + bh * 0.235f); lineTo(l + bw * 0.09f, t + bh * 0.235f); close()
        }
        p.style = android.graphics.Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(0f, t + bh * 0.14f, 0f, t + bh * 0.235f, 0xFF2F2C33.toInt(), 0xFF57525D.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawPath(glass, p); p.shader = null
        p.color = Color.argb(46, 255, 255, 255)
        c.drawPath(android.graphics.Path().apply { moveTo(l + bw * 0.55f, t + bh * 0.16f); lineTo(l + bw * 0.66f, t + bh * 0.16f)
            lineTo(l + bw * 0.5f, t + bh * 0.234f); lineTo(l + bw * 0.39f, t + bh * 0.234f); close() }, p)
        // cabin floor (cut-away roof), dashboard with centre display
        val floor = R(0.085f, 0.235f, 0.915f, 0.875f)
        p.color = 0xFFFBF7F6.toInt(); c.drawRoundRect(floor, bw * 0.06f, bw * 0.06f, p)
        p.style = android.graphics.Paint.Style.STROKE; p.color = 0xFFE5DBDF.toInt(); p.strokeWidth = dp(1).toFloat(); c.drawRoundRect(floor, bw * 0.06f, bw * 0.06f, p)
        val dash = R(0.085f, 0.235f, 0.915f, 0.29f)
        p.style = android.graphics.Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(0f, dash.top, 0f, dash.bottom, 0xFFF7F2F4.toInt(), 0xFFEAE2E5.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(dash, p); p.shader = null
        p.color = 0xFF1D1B1E.toInt(); c.drawRoundRect(R(0.38f, 0.245f, 0.62f, 0.268f), bw * 0.01f, bw * 0.01f, p)
        ambientLine(c, p, l + bw * 0.14f, t + bh * 0.287f, l + bw * 0.86f, t + bh * 0.287f, ambient)
        // door ambient strips
        ambientLine(c, p, l + bw * 0.1f, t + bh * 0.32f, l + bw * 0.1f, t + bh * 0.83f, ambient)
        ambientLine(c, p, l + bw * 0.9f, t + bh * 0.32f, l + bw * 0.9f, t + bh * 0.83f, ambient)
        // rear glass and tail-lamp line
        val rear = android.graphics.Path().apply {
            moveTo(l + bw * 0.12f, t + bh * 0.882f); lineTo(l + bw * 0.88f, t + bh * 0.882f)
            lineTo(l + bw * 0.82f, t + bh * 0.925f); quadTo(cx, t + bh * 0.94f, l + bw * 0.18f, t + bh * 0.925f); close()
        }
        p.style = android.graphics.Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(0f, t + bh * 0.88f, 0f, t + bh * 0.94f, 0xFF4A4650.toInt(), 0xFF2F2C33.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawPath(rear, p); p.shader = null
        p.style = android.graphics.Paint.Style.STROKE; p.color = 0xFF8E8589.toInt(); p.strokeWidth = bh * 0.006f
        c.drawLine(l + bw * 0.16f, t + bh * 0.968f, l + bw * 0.84f, t + bh * 0.968f, p)
        // seats geometry
        // Reference 06 proportion: each seat is ~0.38 of the body width, seats fill most of the cabin length.
        val sw = bw * 0.37f; val gap = bw * 0.036f
        val xs = floatArrayOf(cx - gap - sw, cx + gap)
        val seats = listOf(
            android.graphics.RectF(xs[0], t + bh * 0.345f, xs[0] + sw, t + bh * 0.575f), android.graphics.RectF(xs[1], t + bh * 0.345f, xs[1] + sw, t + bh * 0.575f),
            android.graphics.RectF(xs[0], t + bh * 0.61f, xs[0] + sw, t + bh * 0.83f), android.graphics.RectF(xs[1], t + bh * 0.61f, xs[1] + sw, t + bh * 0.83f))
        // centre console with two lit cupholders
        val console = android.graphics.RectF(cx - bw * 0.028f, t + bh * 0.3f, cx + bw * 0.028f, t + bh * 0.59f)
        p.style = android.graphics.Paint.Style.FILL; p.color = 0xFFF1EAED.toInt(); c.drawRoundRect(console, bw * 0.02f, bw * 0.02f, p)
        p.style = android.graphics.Paint.Style.STROKE; p.color = 0xFFD6CCD1.toInt(); p.strokeWidth = dp(1).toFloat() * 1.5f; c.drawRoundRect(console, bw * 0.02f, bw * 0.02f, p)
        for (cy in listOf(0.395f, 0.445f)) {
            val r = bw * 0.019f
            if (ambient != 2) { p.style = android.graphics.Paint.Style.FILL; p.color = alpha(C_ACCENT, if (ambient == 1) 90 else 40); c.drawCircle(cx, t + bh * cy, r * 1.7f, p) }
            p.style = android.graphics.Paint.Style.FILL; p.color = 0xFFE7DFE3.toInt(); c.drawCircle(cx, t + bh * cy, r, p)
            p.style = android.graphics.Paint.Style.STROKE; p.color = if (ambient == 2) C_SEAT_LINE else C_ACCENT; p.strokeWidth = dp(2).toFloat(); c.drawCircle(cx, t + bh * cy, r, p)
        }
        // steering wheel in front of the driver seat
        val wx = seats[0].centerX(); val wy = t + bh * 0.3f; val wr = bw * 0.095f
        p.style = android.graphics.Paint.Style.STROKE; p.color = 0xFF2E2A2C.toInt(); p.strokeWidth = bw * 0.022f; c.drawCircle(wx, wy, wr, p)
        p.color = 0xFF5A5457.toInt(); p.strokeWidth = bw * 0.006f; c.drawCircle(wx, wy, wr - bw * 0.004f, p)
        p.color = 0xFF2E2A2C.toInt(); p.strokeWidth = bw * 0.014f
        c.drawLine(wx - wr, wy, wx - wr * 0.3f, wy, p); c.drawLine(wx + wr * 0.3f, wy, wx + wr, wy, p); c.drawLine(wx, wy + wr * 0.25f, wx, wy + wr, p)
        val hub = android.graphics.RectF(wx - wr * 0.34f, wy - wr * 0.26f, wx + wr * 0.34f, wy + wr * 0.3f)
        p.style = android.graphics.Paint.Style.FILL; p.color = 0xFFF4EFF1.toInt(); c.drawRoundRect(hub, wr * 0.12f, wr * 0.12f, p)
        p.style = android.graphics.Paint.Style.STROKE; p.color = 0xFF2E2A2C.toInt(); p.strokeWidth = dp(2).toFloat(); c.drawRoundRect(hub, wr * 0.12f, wr * 0.12f, p)
        return seats
    }

    /**
     * Ivory seat seen from above (front up): cushion with quilting, wider backrest at the rear, headrest pill.
     * fill/stroke carry the vehicle report; [hatch] draws the "state unknown" pattern; [dashed] the unknown outline.
     */
    private fun drawSeat(c: android.graphics.Canvas, p: android.graphics.Paint, s: android.graphics.RectF, fill: Int, stroke: Int,
                         hatch: Boolean = false, dashed: Boolean = false) {
        val w = s.width(); val h = s.height()
        val back = android.graphics.RectF(s.left, s.top + h * 0.56f, s.right, s.top + h * 0.86f)
        val cushion = android.graphics.RectF(s.left + w * 0.07f, s.top, s.right - w * 0.07f, s.top + h * 0.64f)
        val head = android.graphics.RectF(s.left + w * 0.27f, s.top + h * 0.83f, s.right - w * 0.27f, s.bottom)
        p.pathEffect = null; p.shader = null
        for ((r, rad) in listOf(head to h * 0.06f, back to w * 0.2f, cushion to w * 0.24f)) {
            p.style = android.graphics.Paint.Style.FILL
            p.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom, Color.WHITE, fill, android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p); p.shader = null
            if (hatch) {
                c.save(); val clip = android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) }; c.clipPath(clip)
                p.style = android.graphics.Paint.Style.STROKE; p.color = alpha(C_DIM, 170); p.strokeWidth = dp(2).toFloat()
                var k = -r.height(); while (k < r.width()) { c.drawLine(r.left + k, r.bottom, r.left + k + r.height(), r.top, p); k += dp(10) }
                c.restore()
            }
            p.style = android.graphics.Paint.Style.STROKE; p.color = stroke; p.strokeWidth = dp(2).toFloat()
            p.pathEffect = if (dashed) android.graphics.DashPathEffect(floatArrayOf(dp(7).toFloat(), dp(5).toFloat()), 0f) else null
            c.drawRoundRect(r, rad, rad, p); p.pathEffect = null
        }
        // quilting on the cushion
        p.style = android.graphics.Paint.Style.STROKE; p.color = alpha(stroke, 110); p.strokeWidth = dp(1).toFloat() * 1.5f
        for (fx in listOf(0.36f, 0.64f)) c.drawLine(s.left + w * fx, cushion.top + h * 0.1f, s.left + w * fx, cushion.bottom - h * 0.08f, p)
    }

    /**
     * Protection map: the cabin with the four actuators where they sit on the car, tagged with the list numerals.
     * Lit (pink glow) only when the vehicle read back the target state; grey = not run; amber = check; dashed = unsupported.
     */
    private fun protectMap(states: List<Pair<DActuator, DOutcome>>): View = object : View(this) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private fun style(o: DOutcome?): Int = when (o) {
            DOutcome.VERIFIED, DOutcome.ALREADY -> 1
            DOutcome.UNSUPPORTED, DOutcome.DENIED -> 3
            null, DOutcome.WAITING, DOutcome.IN_PROGRESS -> 0
            else -> 2
        }
        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val bw = minOf(w * 0.56f, h * 0.44f); val bh = minOf(h * 0.92f, bw * 2.1f)
            val l = (w - bw) / 2; val t = (h - bh) / 2
            val byAction = states.toMap()
            val cab = style(byAction[DActuator.CABIN_AMBIENT])
            val seats = drawCabin(c, p, l, t, bw, bh, ambient = if (cab == 1) 1 else 2)
            seats.forEach { drawSeat(c, p, it, 0xFFF3EDEF.toInt(), C_SEAT_LINE) }
            fun zone(r: android.graphics.RectF, o: DOutcome?) {
                val rad = minOf(r.width(), r.height()) / 2
                p.shader = null; p.pathEffect = null
                when (style(o)) {
                    1 -> { p.style = android.graphics.Paint.Style.FILL
                        for (k in 3 downTo 1) { p.color = alpha(C_ACCENT, 40); val g = dp(k * 5).toFloat()
                            c.drawRoundRect(r.left - g, r.top - g, r.right + g, r.bottom + g, rad + g, rad + g, p) }
                        p.color = C_ACCENT; c.drawRoundRect(r, rad, rad, p) }
                    2 -> { p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dp(3).toFloat(); p.color = C_WARN; c.drawRoundRect(r, rad, rad, p) }
                    3 -> { p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = C_SEAT_LINE
                        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5).toFloat(), dp(4).toFloat()), 0f); c.drawRoundRect(r, rad, rad, p); p.pathEffect = null }
                    else -> { p.style = android.graphics.Paint.Style.FILL; p.color = 0xFFE9E2E5.toInt(); c.drawRoundRect(r, rad, rad, p)
                        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = C_SEAT_LINE; c.drawRoundRect(r, rad, rad, p) }
                }
            }
            fun tag(n: String, x: Float, y: Float) {
                val r = dp(19).toFloat()
                p.shader = null; p.style = android.graphics.Paint.Style.FILL; p.color = C_TEXT; c.drawCircle(x, y, r, p)
                p.color = Color.WHITE; p.textAlign = android.graphics.Paint.Align.CENTER; p.textSize = dp(19).toFloat(); p.typeface = serifBold
                c.drawText(n, x, y + p.textSize * 0.36f, p); p.typeface = Typeface.DEFAULT
            }
            fun R(x0: Float, y0: Float, x1: Float, y1: Float) = android.graphics.RectF(l + bw * x0, t + bh * y0, l + bw * x1, t + bh * y1)
            // 01 tail lamps (rear corners), 02 rear fog (centre), 04 door handles, 03 cabin ambient (amber outline if it needs checking)
            zone(R(0.1f, 0.955f, 0.36f, 0.978f), byAction[DActuator.TAIL]); zone(R(0.64f, 0.955f, 0.9f, 0.978f), byAction[DActuator.TAIL])
            zone(R(0.44f, 0.955f, 0.56f, 0.978f), byAction[DActuator.REAR_FOG])
            for (y in listOf(0.43f, 0.67f)) { zone(R(-0.03f, y, 0.03f, y + 0.06f), byAction[DActuator.DOOR_LOCK]); zone(R(0.97f, y, 1.03f, y + 0.06f), byAction[DActuator.DOOR_LOCK]) }
            if (cab == 2) { p.style = android.graphics.Paint.Style.STROKE; p.color = C_WARN; p.strokeWidth = dp(3).toFloat()
                c.drawLine(l + bw * 0.1f, t + bh * 0.32f, l + bw * 0.1f, t + bh * 0.83f, p); c.drawLine(l + bw * 0.9f, t + bh * 0.32f, l + bw * 0.9f, t + bh * 0.83f, p) }
            tag("01", l + bw * 0.23f, t + bh * 1.0f + dp(6)); tag("02", l + bw * 0.5f, t + bh * 1.0f + dp(6))
            tag("03", l + bw * 0.9f, t + bh * 0.3f); tag("04", l - bw * 0.03f - dp(26), t + bh * 0.46f)
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    /**
     * Seat map (reference 06): the cabin with every seat coloured by what the vehicle reported (01: blush = possibly
     * occupied, grey = reported empty, hatched = state unknown) and marked by what a person confirmed (02: pink disc + check,
     * or an outlined "!"). Labels inside the seats follow the reference wording. [compact] drops the labels (home).
     */
    private fun seatMap(seats: List<SeatRow>, compact: Boolean = false): View = object : View(this) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val bw = minOf(w * 0.8f, h * 0.465f); val bh = minOf(h * 0.97f, bw * 2.1f)
            val l = (w - bw) / 2; val t = (h - bh) / 2
            val boxes = drawCabin(c, p, l, t, bw, bh)
            seats.take(4).forEachIndexed { i, s ->
                val box = boxes[i]
                // The map shows the VEHICLE's report only (the human check lives in the right column).
                // State = fill + outline + word: solid pink + dark outline / white + thin grey / hatched + dashed dark.
                when (s.presence) {
                    SeatPresence.POSSIBLY_OCCUPIED -> drawSeat(c, p, box, C_ACCENT, C_TEXT)
                    SeatPresence.REPORTED_EMPTY -> drawSeat(c, p, box, C_EMPTY_SEAT, C_SEAT_LINE)
                    else -> drawSeat(c, p, box, Color.WHITE, C_TEXT, hatch = true, dashed = true)
                }
                val short = when (s.presence) { SeatPresence.POSSIBLY_OCCUPIED -> "착석 가능성"; SeatPresence.REPORTED_EMPTY -> "빈 좌석"; else -> "확인 불가" }
                // seat name, split at the space so rear seats read "운전석 / 뒷좌석"
                val name = s.label.split(" ")
                val lines = name + short
                p.shader = null; p.pathEffect = null; p.style = android.graphics.Paint.Style.FILL; p.textAlign = android.graphics.Paint.Align.CENTER
                val ts = minOf(box.width() / 5.6f, dp(17).toFloat())
                val top = box.centerY() - (lines.size - 1) * ts * 1.15f / 2f - ts * 0.25f
                lines.forEachIndexed { k, line ->
                    val state = k == lines.lastIndex
                    // light pill behind the text so it reads on pink, hatching and white alike
                    p.textSize = ts; p.isFakeBoldText = state
                    val tw = p.measureText(line); val cy = top + k * ts * 1.15f + ts * 0.9f
                    p.color = alpha(Color.WHITE, 215); c.drawRoundRect(box.centerX() - tw / 2 - ts * 0.3f, cy - ts * 0.95f, box.centerX() + tw / 2 + ts * 0.3f, cy + ts * 0.3f, ts * 0.3f, ts * 0.3f, p)
                    p.color = C_TEXT; c.drawText(line, box.centerX(), cy, p)
                }
                p.isFakeBoldText = false
            }
        }
    }.apply {
        contentDescription = "좌석 배치도: 차량 보고와 직접 확인 표시"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Live seat rows shared by the seat screen and the home map. */
    private fun seatRows(): List<SeatRow> = seatCells().map { (area, label, key) ->
        val r = hub.reading(key)
        SeatRow(label, SdkSeatAdapter.interpretReported(r?.raw?.takeIf { r.status == SignalStatus.OK && hub.isFresh(r) }),
            dBeltLabel(key), incidentLog.fact("seat-check:$area")?.value == "true")
    }

    // ===== Shared components (Premium Blush) =====================================================

    /** Step header for the 1..6 situation flow: segmented progress bar, "n / 6", then the title. */
    private fun flowHeading(s: Screen): View = flowHeading(s.step, 6, s.title)

    /**
     * Editorial step header (reference 02 grammar applied to every workflow): large pink step numeral, thin rule,
     * title, quiet "단계 n / N" on the right, then a hairline progress track with the done part in accent.
     */
    private fun flowHeading(step: Int, total: Int, title: String, titleSize: Float = 34f): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@ProductActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("%02d".format(step), 44f, true, C_PLUM).apply { typeface = serifBold; includeFontPadding = false; setPadding(0, 0, dp(14), 0) })
            addView(View(this@ProductActivity).apply { setBackgroundColor(C_TEXT) }, LinearLayout.LayoutParams(dp(28), dp(3)).apply { marginEnd = dp(16) })
            addView(text(title, titleSize, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(text("단계 $step / $total", 19f, true, C_DIM))
        })
        val bar = LinearLayout(this@ProductActivity).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 1..total) bar.addView(View(this@ProductActivity).apply {
            background = GradientDrawable().apply { setColor(if (i <= step) C_ACCENT else C_LINE); cornerRadius = dp(2).toFloat() }
        }, LinearLayout.LayoutParams(0, dp(if (i == step) 6 else 4), 1f).apply { if (i < total) marginEnd = dp(6) })
        addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2); bottomMargin = dp(6) })
    }

    /** Page title with the short accent rule under it (reference 02 / 06 masthead). */
    private fun pageHeading(title: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 34f, true))
        addView(View(this@ProductActivity).apply { setBackgroundColor(C_ACCENT) }, LinearLayout.LayoutParams(dp(56), dp(5)).apply { bottomMargin = dp(6) })
    }

    /** Neutral notice: thin accent edge + message + optional detail. Used for unavailable / unknown states. */
    private fun noticePanel(title: String, detail: String?, color: Int = C_WARN): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply { setColor(C_CARD); cornerRadius = dp(16).toFloat(); setStroke(dp(1), C_HAIR) }
        addView(View(this@ProductActivity).apply { background = GradientDrawable().apply { setColor(color); cornerRadius = dp(3).toFloat() } },
            LinearLayout.LayoutParams(dp(6), MATCH_PARENT).apply { marginEnd = dp(20) })
        addView(LinearLayout(this@ProductActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 25f, true, if (color == C_WARN) C_WARN else C_TEXT))
            detail?.let { addView(text(it, 20f, color = C_DIM)) }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        setPadding(dp(18), dp(18), dp(20), dp(18))
    }

    /** Empty state: icon tile, one bold line, one helper line. */
    private fun emptyState(iconRes: Int, title: String, detail: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(View(this@ProductActivity).apply { background = tileIcon(iconRes, C_TILE, C_TILE_ICON) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        addView(text(title, 30f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(4)) })
        addView(text(detail, 20f, color = C_DIM).apply { gravity = Gravity.CENTER })
    }

    /** Source badge: 차량 보고 / 직접 확인 / 사용자 입력 / 앱 동작 / 미확인. Colour never carries the meaning alone. */
    private fun sourceBadge(kind: String, size: Float = 20f): TextView = text(kind, size, true, C_TEXT).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(5), dp(14), dp(5))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            when (kind) {
                "차량 보고", "차량 관측" -> { setColor(C_SOFT); setStroke(dp(2), C_LINE) }
                "직접 확인" -> { setColor(C_ACCENT); setStroke(dp(2), C_ACCENT) }
                "사용자 입력" -> { setColor(C_CARD); setStroke(dp(2), C_ACCENT) }
                "앱 동작" -> { setColor(C_GRAY); setStroke(dp(2), C_LINE) }
                else -> { setColor(C_CARD); setStroke(dp(2), C_DIM, dp(6).toFloat(), dp(4).toFloat()) } // 미확인: dashed
            }
        }
    }

    /** Card whose header is the source badge, so the origin of every value is visible. */
    private fun sourceCard(source: String, value: String, detail: String? = null): View {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(sourceBadge(source)) }
        val views = mutableListOf<View>(head, text(value, 30f, true))
        detail?.let { views += text(it, 21f, color = C_DIM) }
        return card(C_CARD, *views.toTypedArray()).apply { gravity = Gravity.CENTER_VERTICAL }
    }

    private fun sectionTitle(title: String, sub: String? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 25f, true))
        sub?.let { addView(text(it, 19f, color = C_DIM)) }
        setPadding(0, dp(8), 0, 0)
    }

    /** Check box drawn as a rounded square; selected = accent fill with a check mark. */
    private fun checkBoxDrawable(selected: Boolean): android.graphics.drawable.Drawable {
        val size = dp(40)
        val box = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (selected) C_ACCENT else C_CARD)
            setStroke(dp(2), if (selected) C_ACCENT else C_DIM)
        }
        val layers = if (selected) arrayOf<android.graphics.drawable.Drawable>(box, getDrawable(R.drawable.ic_check)!!.mutate().apply { setTint(C_TEXT) }) else arrayOf<android.graphics.drawable.Drawable>(box)
        return LayerDrawable(layers).apply {
            if (selected) setLayerInset(1, dp(6), dp(6), dp(6), dp(6))
            setBounds(0, 0, size, size)
        }
    }

    /**
     * One selection pattern for the whole app: pale-pink card with accent border when chosen, white with a quiet border
     * when not. `box` adds the check box (multi-select / toggles); without it the card is a single-choice option.
     */
    private fun choiceButton(title: String, detail: String?, selected: Boolean, box: Boolean = true, big: Boolean = false,
                             onClick: () -> Unit): Button =
        button(title, selected) { onClick() }.apply {
            isSingleLine = false
            maxLines = 5
            ellipsize = null
            val caption = if (detail.isNullOrEmpty()) title else "$title\n$detail"
            text = SpannableString(caption).apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(if (big) 1.3f else 1.05f), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (!detail.isNullOrEmpty()) {
                    setSpan(RelativeSizeSpan(0.82f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.ForegroundColorSpan(C_DIM), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            setTextColor(C_TEXT)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (selected) C_SOFT else C_CARD)
                setStroke(dp(if (selected) 3 else 2), if (selected) C_ACCENT else C_LINE)
            }
            isSelected = selected
            if (box) {
                setCompoundDrawables(checkBoxDrawable(selected), null, null, null)
                compoundDrawablePadding = dp(16)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(10), dp(16), dp(10))
            }
        }

    private data class SeatRow(val label: String, val presence: SeatPresence, val belt: String, val human: Boolean)

    /**
     * Editorial column heading (reference 02 / 06): large serif-weight numeral with a dash, title, one-line caption.
     * Kept to ~3 lines so the seat screen footer stays on screen.
     */
    private fun editorialHeading(num: String, title: String, sub: String, numColor: Int): View = LinearLayout(this).apply {
        // Reference 06 left/right column head: large serif numeral + dash, big title on its own line, two-line caption.
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@ProductActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(num, 58f, true, numColor).apply { typeface = serifBold; includeFontPadding = false; setPadding(0, 0, dp(18), 0) })
            addView(View(this@ProductActivity).apply { setBackgroundColor(C_TEXT) }, LinearLayout.LayoutParams(dp(44), dp(3)))
        })
        addView(text(title, 34f, true).apply { setPadding(0, dp(2), 0, dp(4)) })
        addView(text(sub, 18f, color = C_DIM))
        setPadding(0, 0, 0, dp(14))
    }

    /** Small status chip: filled soft pink when confirmed by a person, outlined when still needed. */
    private fun chip(label: String, filled: Boolean): TextView = text(label, 21f, true, C_TEXT).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            setColor(if (filled) C_ACCENT else C_CARD)
            setStroke(dp(2), if (filled) C_ACCENT else C_LINE)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun notesScreen(col: LinearLayout) {
        // Editorial table (reference 02 right column): count as a serif numeral, one ruled row per seat, options as a
        // single segmented control per row (blush segment = chosen) instead of five separate boxes.
        val set = seatCells().count { notes.get(it.first) != null }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("$set", 46f, true, C_PLUM).apply { typeface = serifBold; includeFontPadding = false })
            addView(text(" / ${seatCells().size}석 설정", 24f, true).apply { setPadding(0, 0, dp(24), 0) })
            addView(text("좌석마다 미리 알려 둘 내용을 고르세요. 사고 기록과 QR에 들어가요.", 20f, color = C_DIM))
            setPadding(0, 0, 0, dp(6))
        })
        for ((area, label, _) in seatCells()) {
            val cur = notes.get(area)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text(label, 24f, true), LinearLayout.LayoutParams(dp(220), WRAP_CONTENT))
            val seg = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(C_CARD); cornerRadius = dp(18).toFloat(); setStroke(dp(1), C_LINE) }
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }
            val options = listOf<String?>(null) + SeatNotes.OPTIONS.map { it.first }
            options.forEach { option ->
                val labelText = if (option == "영유아(카시트)") "영유아\n카시트" else option ?: "없음"   // break at the word boundary
                val chosen = cur == option
                val choice = choiceButton(labelText, null, chosen, box = false) {
                    notes.set(area, option)
                    Log.i(tag, "SEAT_NOTE $area=${option ?: "none"}")
                    refresh(force = true)
                }.apply {
                    size(if (option == "영유아(카시트)") 18f else 21f)
                    minHeight = 0
                    setPadding(dp(4), 0, dp(4), 0)
                    setTypeface(typeface, if (chosen) Typeface.BOLD else Typeface.NORMAL)
                    background = if (chosen) GradientDrawable().apply { setColor(C_FRAME); cornerRadius = dp(14).toFloat() } else null
                }
                seg.addView(choice, LinearLayout.LayoutParams(0, dp(64), 1f))
            }
            row.addView(seg, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addBlock(col, card(C_CARD, row).apply { setPadding(dp(2), dp(14), dp(2), dp(4)) })
        }
        val numbers = insurerNumbers()
        grid(col, listOf(
            statusCard("보험사 연락처", prep.insurerName ?: if (numbers.isEmpty()) "번호 미등록" else "저장된 번호",
                numbers.joinToString("\n") { (label, number) -> "$label · $number" }.ifEmpty { null }),
            button(if (numbers.isEmpty()) "보험사 번호 등록" else "보험사 번호 수정", false, enabled = canEditInsurer()) { showInsurerDialog() }
        ), expand = true)
        // D plan F6: same card + button pattern as the insurer row.
        grid(col, listOf(
            statusCard("출발 전 차량 기록",
                if (handoverBusy) "읽는 중…" else prep.handoverWall?.let { "기록함 · ${passportTime(it)}" } ?: "아직 기록 안 함",
                prep.handoverText?.let { "$it\n사고 기록·보험 QR에 들어가요" } ?: "렌터카 인수·출발 때 한 번 누르세요"),
            button(if (handoverBusy) "읽는 중" else "지금 기록", false, enabled = canEditInsurer() && !handoverBusy) { recordHandover() }
        ), expand = true)
        footer(col, listOf(
            button("좌석 메모 모두 지우기", false) {
                notes.clear()
                Log.i(tag, "SEAT_NOTE cleared")
                refresh(force = true)
            },
            button("저장하고 홈으로", true) { go(Screen.HOME) }
        ))
    }

    // --- D plan F6: pre-departure handover record -----------------------------------------------------
    private var handoverBusy = false

    private fun recordHandover() {
        if (!canEditInsurer() || handoverBusy) return
        handoverBusy = true
        refresh(force = true)
        hub.requestHandover { h -> main.post {
            handoverBusy = false
            if (destroyed) return@post
            val text = handoverLine(h)
            prep.saveHandover(text, System.currentTimeMillis(), handoverQr(h))
            Log.i(tag, "HANDOVER_SAVED vinLen=${h.vin?.length} vinValid=${h.vin?.let(::isVin)} odo=${h.odometer} range=${h.range} text=$text")
            refresh(force = true)
        } }
    }

    /** QR form: only what the insurance QR does not already carry (battery and trunk have their own lines). */
    private fun handoverQr(h: VehicleHub.Handover): String = listOf(
        SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date()),
        h.vin?.takeIf(::isVin)?.let { "차대 …${it.takeLast(6)}" } ?: "차대 미확인",
        h.odometer?.let { String.format(Locale.KOREA, "누적 %,dkm", it.toInt()) } ?: "누적 미확인",
        h.range?.let { String.format(Locale.KOREA, "가능 %,dkm", (it / 1000f).toInt()) } ?: "가능 미확인"
    ).joinToString(" · ")

    /** Only what the vehicle reported now; anything missing is written as 미확인. */
    private fun handoverLine(h: VehicleHub.Handover): String {
        val soc = hub.reading("EV_SOC")?.takeIf { it.status == SignalStatus.OK && hub.isFresh(it) }?.raw?.let(::percentReading)
        val doors = listOf("DOOR_ROW_1_LEFT", "DOOR_ROW_1_RIGHT", "DOOR_ROW_2_LEFT", "DOOR_ROW_2_RIGHT", "DOOR_REAR")
            .map { k -> hub.reading(k)?.takeIf { it.status == SignalStatus.OK && hub.isFresh(it) }?.raw?.toIntOrNull() }
        val doorText = when {
            doors.any { it == null } -> "문 미확인"
            doors.all { it == 0 } -> "문·트렁크 닫힘"
            else -> "열린 문 있음"
        }
        return listOf(
            when {
                h.vin == null -> "차대 미확인"
                isVin(h.vin) -> "차대 …${h.vin.takeLast(6)}"
                else -> "차대 미확인(값 이상)"
            },
            h.odometer?.let { String.format(Locale.KOREA, "누적 %,d km", it.toInt()) } ?: "누적 미확인",
            // RANGE_REMAINING is metres (AAOS; console unit "m").
            h.range?.let { String.format(Locale.KOREA, "주행가능 %,d km", (it / 1000f).toInt()) } ?: "주행가능 미확인",
            soc?.let { "배터리 $it" } ?: "배터리 미확인",
            doorText
        ).joinToString(" · ")
    }

    private fun noteLabel(note: String) = when (note) {
        "영유아(카시트)" -> "영유아·카시트"
        else -> note
    }

    private fun insurerNumbers() = listOfNotNull(
        prep.insurerPhone?.let { "긴급출동" to it },
        prep.generalPhone?.let { "일반 문의" to it }
    )

    private fun callInsurer(numbers: List<Pair<String, String>>) {
        if (numbers.size == 1) {
            openDial(numbers.single().second, "insurer")
            return
        }
        AlertDialog.Builder(this).setTitle("보험사에 전화")
            .setItems(numbers.map { (label, number) -> "$label · $number" }.toTypedArray()) { _, index ->
                openDial(numbers[index].second, "insurer")
            }
            .setNegativeButton("닫기", null).show()
    }

    private fun canEditInsurer(): Boolean =
        (Session.screen == Screen.NOTES || Session.screen == Screen.ROADSIDE) &&
            roadsideParked(motionNow())

    private fun showInsurerDialog() {
        if (!canEditInsurer()) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun field(label: String, current: String?, type: Int, last: Boolean = false) = EditText(this).apply {
            hint = label
            setText(current.orEmpty())
            textSize = 22f
            inputType = type
            isSingleLine = true
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
                (if (last) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT)
            box.addView(this)
        }
        val name = field("보험사 이름", prep.insurerName, InputType.TYPE_CLASS_TEXT)
        val emergency = field("긴급출동 번호", prep.insurerPhone, InputType.TYPE_CLASS_PHONE)
        val general = field("일반 문의 번호", prep.generalPhone, InputType.TYPE_CLASS_PHONE, last = true)
        val status = text("닫으면 자동 저장돼요.", 18f, color = C_DIM)
        box.addView(status)
        fun valid(field: EditText) = field.text.toString().trim().let { it.isEmpty() || HandoffCards.validPhone(it) }
        val dialog = AlertDialog.Builder(this).setTitle("보험사 번호 관리").setView(box)
            .setPositiveButton("닫기", null).create()
        fun finish() {
            if (valid(emergency) && valid(general)) dialog.dismiss()
            else status.text = "번호는 숫자와 하이픈만, 3자리 이상 입력해 주세요."
        }
        dialog.setOnDismissListener {
            if (roadsideEditor === dialog) roadsideEditor = null
            if (!visible || !canEditInsurer()) return@setOnDismissListener
            if (valid(emergency) && valid(general)) {
                prep.save(name.text.toString(), emergency.text.toString().trim(), general.text.toString().trim())
                refresh(force = true)
            } else {
                Toast.makeText(this, "번호 형식이 올바르지 않아 기존 번호를 유지했어요.", Toast.LENGTH_LONG).show()
            }
        }
        roadsideEditor = dialog
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { finish() }
        general.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) false else { finish(); true }
        }
    }

    // --- Stage 1: official contact + handoff cards --------------------------------------------------

    private fun openDial(number: String, reason: String) {
        try {
            startActivity(HandoffCards.dialIntent(number))
            passportAction(LocalAction.DIAL_REQUESTED)
            // The app cannot know whether a call was placed; it only records that the dialer was opened.
            Log.i(tag, "DIAL_SCREEN_OPENED number=${HandoffCards.digits(number)} reason=$reason (call not placed by app)")
        } catch (e: ActivityNotFoundException) {
            Log.i(tag, "DIAL_SCREEN_UNAVAILABLE number=$number")
            Session.notice = "이 차에서 전화 화면을 열 수 없습니다. 휴대폰으로 $number 에 전화하세요."; scheduleNoticeClear()
        }
    }

    private fun injuryCounts(): String {
        val minor = Session.injuriesBySeat.values.count { it == HandoffCards.MINOR_INJURY }
        val severe = Session.injuriesBySeat.values.count { it == HandoffCards.SEVERE_INJURY }
        return "경미한 부상 ${minor}명 · 중상 ${severe}명"
    }

    private fun injuryDetails(): List<String> = seatCells().mapNotNull { (area, label, _) ->
        Session.injuriesBySeat[area]?.let { "$label · $it" }
    }

    private fun beginPassport(): Boolean {
        if (incidentLog.begin(System.currentTimeMillis())) {
            observePassport()
            return true
        }
        go(Screen.PASSPORT)
        return false
    }

    private fun setPassportCategory(value: String?) {
        if (!beginPassport()) return
        Session.category = value
        recordInput("category", "사고 유형 · ${categoryLabel(value)}", value)
    }

    private fun recordInput(key: String, label: String, value: String?, kind: EventKind = EventKind.INPUT) {
        incidentLog.add(LogEvent(System.currentTimeMillis(), kind, label, key = key, value = value))
        incidentLog.flush()
    }

    /** Shares the editor; the towing copy is only for this on-screen call preparation. */
    private fun showLocationDialog(forTow: Boolean = false) {
        if (forTow && !canEditRoadside()) return
        val saved = incidentLog.fact("location")?.value
        val current = if (forTow) towLocation ?: saved else saved
        val input = EditText(this).apply {
            hint = if (forTow) "도로 이름 · 진행 방향 · 출구나 표지판" else "예: 경부고속도로 서울 방향 ○○km 갓길"
            setText(current.orEmpty()); textSize = 22f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(LOCATION_MAX))
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_DONE
            if (forTow) setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * UI_SCALE)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        if (forTow) box.addView(text("위치 직접 입력", 28f, true))
        box.addView(input)
        box.addView(text(if (forTow) {
            if (towLocation == null && saved != null) "사고 기록의 위치예요. 지금 위치와 맞는지 확인하세요."
            else "지금 통화에서 읽을 내용이에요. 사고 기록이나 QR에는 추가되지 않아요."
        } else "직접 적은 내용으로만 공유됩니다. 기기 위치(GPS)는 읽지 않습니다.", 18f, color = C_DIM))
        if (forTow) {
            box.background = GradientDrawable().apply { setColor(C_CARD); cornerRadius = dp(14).toFloat() }
            box.setPadding(dp(24), dp(18), dp(24), dp(18))
            val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert).setView(box).create()
            fun useLocation() {
                if (visible && canEditRoadside()) towLocation = input.text.toString().trim().takeIf { it.isNotEmpty() }
                dialog.dismiss()
                refresh(force = true)
            }
            footer(box, listOf(button("취소", false) { dialog.dismiss() }, button("이 위치 사용", true) { useLocation() }))
            input.setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_DONE) false else { useLocation(); true }
            }
            roadsideEditor = dialog
            dialog.setOnDismissListener { if (roadsideEditor === dialog) roadsideEditor = null }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(dp(760), WRAP_CONTENT)
            return
        }
        AlertDialog.Builder(this).setTitle("위치 직접 입력").setView(box)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val value = input.text.toString().trim().takeIf { it.isNotEmpty() }
                if (value != current) {
                    if (!incidentLog.active) incidentLog.begin(System.currentTimeMillis())
                    recordInput("location", "위치 · ${value ?: "입력 취소"}", value)
                    Log.i(tag, "LOCATION_INPUT chars=${value?.length ?: 0}")
                    if (handoffProfile != null) resetHandoff()
                }
                refresh(force = true)
            }.show()
    }
    private fun restorePassportInputs() {
        if (!incidentLog.active) return
        Session.category = incidentLog.fact("category")?.value
        Session.injuriesBySeat.clear()
        seatCells().forEach { (area, _, _) ->
            incidentLog.fact("injury:$area")?.value?.let { Session.injuriesBySeat[area] = it }
        }
        Session.guide.indices.forEach { Session.guide[it] = incidentLog.fact("guide:$it")?.value == "true" }
    }

    private fun passportAction(action: LocalAction, label: String? = null) {
        val now = System.currentTimeMillis()
        incidentLog.add(LogEvent(now, EventKind.HANDOFF, listOfNotNull(label, action.label).joinToString(" · "), value = action.name))
        incidentLog.flush()
    }

    private fun observePassport() {
        val now = System.currentTimeMillis()
        seatCells().forEach { (area, label, sensor) ->
            observeFact(sensor, "$label 좌석", now) { raw ->
                when (SdkSeatAdapter.interpretReported(raw)) {
                    SeatPresence.REPORTED_EMPTY -> "빈 좌석 보고"
                    SeatPresence.POSSIBLY_OCCUPIED -> "착석 가능성"
                    SeatPresence.UNKNOWN -> null
                }
            }
            observeFact("DOOR_$area", "$label 문", now, ::doorReading)
        }
        observeFact("DOOR_REAR", "트렁크", now, ::doorReading)
        observeFact("GEAR", "기어", now) { raw ->
            raw.toIntOrNull()?.let(::gearLabel)?.takeUnless { it.startsWith("raw=") }
        }
        observeFact("SPEED", "속도", now) { raw ->
            raw.toFloatOrNull()?.takeIf { it.isFinite() }?.let { "${(it * 3.6f).toInt()} km/h" }
        }
        observeFact("WIPER_FRONT", "앞유리 와이퍼", now) { raw -> signalSwitch(raw, "작동 중") }
        observeFact("LOWBEAM", "하향등", now) { raw -> signalSwitch(raw, "켜짐") }
        observeFact("EV_SOC", "배터리", now, ::percentReading)
        observeFact("FUEL_PCT", "연료", now, ::percentReading)
    }

    private fun doorReading(raw: String) = raw.toIntOrNull()?.let { if (it > 0) "열림" else "닫힘" }
    private fun percentReading(raw: String) = raw.toFloatOrNull()?.takeIf { it.isFinite() }?.let { "${it.toInt()}%" }
    private fun signalSwitch(raw: String, on: String) = when (raw.uppercase(Locale.ROOT)) {
        "ON" -> on
        "OFF" -> "꺼짐"
        else -> null
    }

    private fun observeFact(key: String, label: String, now: Long, format: (String) -> String?) {
        val reading = hub.reading(key)
        val previous = incidentLog.fact(key)
        val value = reading?.takeIf { it.status == SignalStatus.OK && hub.isFresh(it) }?.raw?.let(format)
        val quality = when {
            reading == null && previous?.receivedWall != null -> "이전 수신 · 현재 미확인"
            reading == null -> "아직 수신하지 않음"
            !hub.isFresh(reading) -> "오래된 신호"
            reading.status == SignalStatus.PERMISSION_DENIED -> "조회 권한 없음"
            reading.status == SignalStatus.ERROR -> "읽기 실패"
            reading.status == SignalStatus.UNAVAILABLE || value == null -> "조회할 수 없음"
            reading.category == Category.VERIFIED -> "확인된 신호"
            else -> "일부 확인"
        }
        val received = if (reading == null) previous?.receivedWall else reading.receivedWall
        val eventWall = if (reading != null && hub.isFresh(reading)) reading.receivedWall ?: now else now
        val kind = if (value == null) EventKind.UNKNOWN else EventKind.VEHICLE
        incidentLog.add(LogEvent(eventWall, kind, label, key = key, value = value, quality = quality,
            source = reading?.source ?: previous?.source, receivedWall = received, insertedWall = now))
    }

    private fun passportTime(wall: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date(wall))

    private fun passport(col: LinearLayout) {
        incidentLog.error?.let { addBlock(col, text(it, 22f, true, C_WARN)) }
        val started = incidentLog.startedWall
        if (started == null) {
            col.addView(emptyState(R.drawable.ic_record, "저장된 사고 기록 없음", "사고 상황 정리를 시작하면 위치·좌석·입력이 출처와 함께 기록돼요."),
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            if (incidentLog.error != null) addBlock(col, button("기록 지우기", false) { clearPassport() })
            footer(col, listOf(button("홈으로", true) { go(Screen.HOME) }))
            return
        }
        addBlock(col, text("기록 시작 ${passportTime(started).takeLast(8).take(5)}", 21f, color = C_DIM))
        addBlock(col, passportLocationCard())
        grid(col, listOf(
            button("공유 목적 선택", true) { resetHandoff(); go(Screen.HANDOFF) },
            button(if (passportSeats) "좌석 확인 접기" else "좌석 직접 확인", false) {
                passportSeats = !passportSeats
                refresh(force = true)
            }
        ))
        if (passportSeats) passportSeatConfirmations(col)
        val facts = incidentLog.facts()
        val vehicle = facts.filter { it.kind == EventKind.VEHICLE }
        val unknown = facts.filter { it.kind == EventKind.UNKNOWN }
        val confirmations = facts.filter { it.kind == EventKind.HUMAN && it.value == "true" }
        val currentHighlights = vehicle.filter { it.key in listOf("GEAR", "SPEED", "EV_SOC", "WIPER_FRONT") }
        grid(col, listOf(
            passportSection("차량 보고", if (passportVehicle) listOfNotNull(prep.handoverText?.let { "출발 전 · $it" }) + vehicle.map { "${it.text} · ${it.value} · ${it.quality}" } else vehicleSummary(vehicle, currentHighlights)),
            passportSection("직접 확인", confirmations.map { "${it.text} · ${passportTime(it.wall).takeLast(8)}" }.ifEmpty { listOf("아직 없음 · 좌석을 직접 확인하세요") }),
            passportSection("사용자 입력", listOf("사고 유형 · ${categoryLabel(Session.category)}", injuryCounts()) + injuryDetails()),
            passportSection("미확인", listOf("사고 발생 시각 · 수신 여부") +
                (if (passportVehicle) unknown.map { "${it.text} · ${it.quality}" } else listOf("차량 항목 ${unknown.size}개")))
        ))
        addBlock(col, appActionsCard(incidentLog.all().filter { it.kind == EventKind.HANDOFF }.takeLast(3)))
        grid(col, listOf(
            button(if (passportVehicle) "차량 세부 정보 접기" else "차량 세부 정보 보기", false) {
                passportVehicle = !passportVehicle
                refresh(force = true)
            },
            button(if (passportHistory) "변경 이력 접기" else "변경 이력 보기", false) {
                passportHistory = !passportHistory
                refresh(force = true)
            }
        ))
        if (passportHistory) {
            addBlock(col, passportSection("변경 이력", incidentLog.all().takeLast(25).map { event ->
                val label = when {
                    event.kind == EventKind.VEHICLE && event.wall < started -> "기록 전 수신"
                    event.kind == EventKind.VEHICLE -> "수신"
                    event.kind == EventKind.UNKNOWN -> "상태 기록"
                    else -> event.kind.tag
                }
                val detail = listOfNotNull(event.value.takeIf { event.kind == EventKind.VEHICLE }, event.quality.takeIf {
                    event.kind == EventKind.VEHICLE || event.kind == EventKind.UNKNOWN
                }).joinToString(" · ")
                val timestamps = if (event.kind == EventKind.VEHICLE || event.kind == EventKind.UNKNOWN) {
                    listOfNotNull(event.receivedWall?.let { "수신 ${passportTime(it)}" }, "추가 ${passportTime(event.insertedWall)}").joinToString(" · ")
                } else ""
                listOf("${passportTime(event.wall)} · $label · ${event.text}", detail, timestamps)
                    .filter { it.isNotEmpty() }.joinToString("\n")
            }))
        }
        footer(col, listOf(
            button("상황 정리 이어하기", false) { startSituation() },
            button("기록 지우기", false) { clearPassport() }
        ), show119 = true)
    }

    private fun resetHandoff() {
        handoffPayload = null
        handoffProfile = null
        handoffBitmap = null
        handoffDisplayed = false
        handoffError = null
    }

    /** Share-purpose option: bold title, quiet one-line description, left aligned (not two equal centred lines). */
    private fun purposeCard(title: String, desc: String, index: Int = 0, action: () -> Unit): Button =
        largeAction("$title\n$desc", false, action = action).apply {
            // Editorial row: pink numeral, bold title, quiet description, chevron at the far edge.
            val num = if (index > 0) "%02d   ".format(index) else ""
            text = SpannableString("$num$title\n$desc").apply {
                if (num.isNotEmpty()) setSpan(android.text.style.ForegroundColorSpan(C_PLUM), 0, num.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val d = num.length + title.length + 1
                setSpan(RelativeSizeSpan(0.62f), d, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.NORMAL), d, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(C_DIM), d, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            background = GradientDrawable().apply { setColor(C_CARD); cornerRadius = dp(20).toFloat(); setStroke(dp(1), C_HAIR) }
            val chevron = getDrawable(R.drawable.ic_chevron)?.mutate()?.apply { setTint(C_DIM); setBounds(0, 0, dp(36), dp(36)) }
            setCompoundDrawables(null, null, chevron, null)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(32), paddingTop, dp(28), paddingBottom)
        }

    private fun passportHandoff(col: LinearLayout) {
        val profile = handoffProfile
        val payload = handoffPayload
        if (profile == null || payload == null) {
            grid(col, HandoffProfile.entries.mapIndexed { i, option -> option to i + 1 }.map { (option, n) ->
                purposeCard(option.label, option.description, n) {
                    restorePassportInputs()
                    observePassport()
                    handoffProfile = option
                    handoffError = null
                    Log.i(tag, "QR_STAGE purpose profile=${option.name}")
                    handoffPayload = PassportHandoff.prepare(option,
                        incidentLog.startedWall?.let(::passportTime) ?: "미확인", categoryLabel(Session.category),
                        injuryCounts().takeIf { Session.injuriesBySeat.isNotEmpty() }, incidentLog.facts(),
                        incidentLog.all(), ::passportTime, incidentLog.fact("location")?.value, shareableFix(),
                        prep.handoverQr)
                    refresh(force = true)
                }
            }, columns = 1, expand = true, equalRows = true)
            footer(col, listOf(button("위치 직접 입력", false) { showLocationDialog() }, button("사고 기록으로", false) { go(Screen.PASSPORT) }), show119 = true)
            return
        }
        addBlock(col, text(profile.label, 29f, true))
        val preview = card(C_CARD, text(payload.text, 22f))
        val bitmap = handoffBitmap
        if (bitmap == null) addBlock(col, preview) else {
            val qrView = ImageView(this).apply {
                contentDescription = "공유 QR"
                setImageBitmap(bitmap)
                setBackgroundColor(Color.WHITE)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            row.addView(qrView, LinearLayout.LayoutParams(dp(410), dp(410)).apply { marginEnd = dp(16) })
            row.addView(preview, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addBlock(col, row)
            qrView.post {
                val shown = !destroyed && visible && qrView.isShown && Session.screen == Screen.HANDOFF &&
                    handoffPayload === payload && handoffBitmap === bitmap
                Log.i(tag, "QR_STAGE render shown=$shown size=${qrView.width}x${qrView.height} first=${!handoffDisplayed}")
                if (shown && !handoffDisplayed) {
                    handoffDisplayed = true
                    passportAction(LocalAction.QR_DISPLAYED, profile.label)
                }
            }
        }
        if (payload.omitted.isNotEmpty()) addBlock(col, noticePanel("QR 용량 때문에 일부를 뺐어요", payload.omitted.joinToString(" · ")))
        if (!payload.encodable) addBlock(col, noticePanel("필수 정보가 QR 용량을 넘었어요", "위 내용을 화면에서 직접 보여 주세요."))
        addBlock(col, text("휴대폰으로 스캔해 내용을 확인할 수 있어요. 자동 전송은 하지 않아요.", 20f, color = C_DIM))
        val actions = mutableListOf(button("공유 목적 다시 선택", false) {
            resetHandoff()
            body.scrollTo(0, 0)
            refresh(force = true)
        })
        handoffError?.let { addBlock(col, noticePanel(it.first, it.second)) }
        if (payload.encodable && bitmap == null) actions += button(if (handoffError != null) "다시 시도" else "QR 만들기", true) {
            // Stage log: request. Only profile name, sizes and outcomes are logged, never the QR text or any value in it.
            val motion = motionNow().motion
            Log.i(tag, "QR_STAGE request profile=${profile.name} motion=$motion chars=${payload.text.length} bytes=${payload.text.toByteArray().size} omitted=${payload.omitted.size}")
            if (motion == Motion.MOVING) {
                handoffError = "차가 움직이고 있어요" to "정차한 뒤에 QR을 만들 수 있어요."
                Log.w(tag, "QR_STAGE blocked reason=moving")
                refresh(force = true)
                return@button
            }
            val t0 = SystemClock.elapsedRealtime()
            val image = QrRenderer.encode(payload.text)
            val ms = SystemClock.elapsedRealtime() - t0
            val readable = image != null && QrRenderer.selfCheck(image, payload.text)
            Log.i(tag, "QR_STAGE encode image=${image != null} selfCheck=$readable ms=$ms steps=${QrRenderer.lastReport}")
            if (!readable) {
                handoffError = "QR을 만들지 못했어요" to "다시 시도해 보세요. 계속 안 되면 위 내용을 화면으로 직접 보여 주세요."
                Log.w(tag, "QR_STAGE failed")
                refresh(force = true)
            } else {
                handoffError = null
                handoffBitmap = image
                passportAction(LocalAction.QR_GENERATED, profile.label)
                Log.i(tag, "QR_STAGE state bitmap=set displayedFlag=$handoffDisplayed")
                refresh(force = true)
            }
        }
        actions += button("사고 기록으로", false) { resetHandoff(); go(Screen.PASSPORT) }
        footer(col, actions, show119 = true)
    }

    /**
     * App actions as two columns: a fixed time column and a text column, so every wrapped line starts at the same x.
     * Spaces inside an item are non-breaking, so a line only ever breaks between items (at " · "), never mid-phrase.
     * Entries stored as "제목 / 항목 / 항목" put each item on its own row.
     */
    private fun appActionsCard(events: List<LogEvent>): View {
        fun glue(s: String) = s.split(" · ").joinToString("\u00A0· ") { it.replace(' ', '\u00A0') }
        val timeWidth = dp(120)
        val rows = mutableListOf<View>(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(sourceBadge("앱 동작", 22f)) })
        fun row(time: String, body: String, indent: Boolean) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            line.addView(text(time, 20f, color = C_DIM), LinearLayout.LayoutParams(timeWidth, WRAP_CONTENT))
            line.addView(text(glue(body), 20f, color = if (indent) C_TEXT else C_TEXT), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            rows += line
        }
        if (events.isEmpty()) rows += text("아직 기록된 동작 없음", 20f)
        for (event in events) {
            val time = passportTime(event.wall).takeLast(8)
            val parts = event.text.split(" / ")
            row(time, parts.first(), false)
            parts.drop(1).forEach { row("", it, true) }
        }
        return card(C_CARD, *rows.toTypedArray())
    }
    /** A few short sentences instead of a data dump: moving or not, gear, battery, and whether a pre-departure record exists. */
    private fun vehicleSummary(all: List<LogEvent>, highlights: List<LogEvent>): List<String> {
        fun v(key: String) = highlights.firstOrNull { it.key == key }?.takeIf { it.quality != "오래된 신호" }?.value
        val gear = v("GEAR"); val speed = v("SPEED"); val soc = v("EV_SOC")
        val stopped = speed?.startsWith("0 ") == true
        return listOfNotNull(
            when {
                gear != null && stopped -> "정차 중 · 기어 $gear"
                gear != null -> "기어 $gear · 움직이는 중일 수 있어요"
                else -> "기어 확인 못 함"
            },
            soc?.let { "배터리 $it" },
            if (prep.handoverWall != null) "출발 전 기록이 있어요" else null
        )
    }

    /** The one thing to say to 119 / a tow operator, first on the record screen: where the car is. */
    private fun passportLocationCard(): View {
        val typed = incidentLog.fact("location")?.value
        val fix = hub.fix?.takeIf { it.fresh() }
        val place = hub.freshNaviAddress()
        val (value, detail) = when {
            fix != null -> (place ?: fix.text) to listOfNotNull("차량이 알려 준 위치예요", typed?.let { "직접 적은 위치: $it" }).joinToString("\n")
            typed != null -> typed to "직접 적은 위치예요"
            else -> "위치 없음" to "직접 말하거나 입력하세요"
        }
        return statusCard("위치", value, detail, if (fix != null || typed != null) C_TEXT else C_WARN)
    }

    private fun passportSection(title: String, lines: List<String>): View {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(sourceBadge(title, 22f)) }
        return card(C_CARD, head, text(lines.ifEmpty { listOf("아직 기록 없음") }.joinToString("\n"), 20f))
    }

    private fun passportSeatConfirmations(col: LinearLayout) {
        grid(col, seatCells().map { (area, label, sensor) ->
            val fact = incidentLog.fact(sensor)
            val confirmation = incidentLog.fact("seat-check:$area")?.takeIf { it.value == "true" }
            val views = mutableListOf<View>(text(label, 26f, true),
                text("차량 · ${fact?.value ?: fact?.quality ?: "미확인"}", 21f),
                text(confirmation?.let { "직접 확인 · ${passportTime(it.wall)}" } ?: "직접 확인 · 미확인", 21f))
            if (confirmation != null && confirmation.quality != seatFingerprint(fact)) {
                views += text("확인 뒤 차량 보고 변경", 20f, true, C_WARN)
            }
            views += button(if (confirmation == null) "좌석 직접 확인" else "확인 취소", confirmation == null) {
                if (motionNow().motion == Motion.MOVING) return@button
                observePassport()
                val now = System.currentTimeMillis()
                incidentLog.add(LogEvent(now, EventKind.HUMAN,
                    "$label · ${if (confirmation == null) "직접 확인" else "확인 취소"}",
                    key = "seat-check:$area", value = (confirmation == null).toString(),
                    quality = seatFingerprint(incidentLog.fact(sensor))))
                refresh(force = true)
            }
            card(C_CARD, *views.toTypedArray())
        })
    }

    private fun seatFingerprint(fact: LogEvent?) = listOf(fact?.value, fact?.quality).joinToString("|")

    private fun clearPassport() {
        AlertDialog.Builder(this).setTitle("사고 기록을 지울까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("지우기") { _, _ ->
                if (incidentLog.clear()) {
                    passportHistory = false
                    passportSeats = false
                    passportVehicle = false
                    Session.reset()
                    go(Screen.HOME)
                } else refresh(force = true)
            }.show()
    }

    private fun roadsideParked(mob: MobilityState): Boolean =
        mob.motion == Motion.STOPPED_PARKED && !mob.conflict && mob.gear == 4 &&
            mob.speedKmh?.let { it.isFinite() && kotlin.math.abs(it) <= 0.1f } == true

    private fun canEditRoadside() = Session.screen == Screen.ROADSIDE && roadsideParked(motionNow())

    private fun startRehearsal() {
        if (!canEditRoadside()) return
        towRehearsal = TowRehearsal()
        redrawRehearsal()
    }

    private fun towPrep(col: LinearLayout) {
        val location = towLocation
        val edit = button(if (location == null) "위치 입력" else "위치 수정", false) {
            showLocationDialog(forTow = true)
        }.apply { size(22f) }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val car = hub.fix?.takeIf { it.fresh() }
            addView(text(when {
                location != null -> "위치 · 직접 입력"
                car != null -> "위치 · 차량 좌표 · ${car.ageSec()}초 전"
                else -> "위치 미확인 · ${hub.locationStatus}"
            }, 20f, true, C_NOTE))
            addView(text(location ?: car?.let { listOfNotNull(hub.freshNaviAddress(), it.text).joinToString("\n") }
                ?: "도로 이름 · 방향 · 가까운 표지판", 28f, true))
            if (location == null && car != null) addView(text("좌표와 함께 도로 이름·방향도 말하세요", 20f, color = C_DIM))
        }
        val practice = button("통화 연습", false, onClick = ::startRehearsal).apply { size(22f) }
        val locationRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(fields, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(edit, LinearLayout.LayoutParams(dp(160), dp(76)).apply { marginStart = dp(16) })
            addView(practice, LinearLayout.LayoutParams(dp(160), dp(76)).apply { marginStart = dp(12) })
        }
        addBlock(col, card(C_CARD,
            text("견인 통화 준비", 29f, true),
            locationRow,
            text("통화 첫마디 · “견인이 필요합니다.”", 22f, color = C_DIM)
        ))
    }

    private fun redrawRehearsal() {
        refresh(force = true)
        body.removeCallbacks(restoreScroll)
        body.scrollTo(0, 0)
    }

    private fun rehearsal(col: LinearLayout, drill: TowRehearsal) {
        fun act(change: () -> Unit) {
            if (!canEditRoadside() || towRehearsal !== drill) return
            change()
            redrawRehearsal()
        }
        fun exit() { towRehearsal = null; redrawRehearsal() }
        fun question(step: String, prompt: String, hint: String, warning: Boolean = false) {
            addBlock(col, card(C_CARD,
                text(step, 21f, true, C_NOTE),
                text(prompt, 34f, true),
                text(hint, 22f, bold = warning, color = if (warning) C_WARN else C_DIM)
            ))
        }
        fun reply(label: String, primary: Boolean = false, action: () -> Unit) =
            largeAction(label, primary) { act(action) }
        col.addView(text(if (drill.challenge) "복창 오류 연습" else "견인 통화 연습", 34f, true))
        col.addView(text("모의 통화 · 실제 전화나 접수 없음", 22f, true, C_NOTE))
        when (drill.step) {
            TowRehearsal.Step.LOCATION -> {
                question("1/3 · 상담원", "지금 어디에 계세요?",
                    if (drill.challenge) "이 가상 위치를 말해 보세요. 상담원이 들은 내용과 비교할 거예요."
                    else "도로 이름, 진행 방향, 가까운 표지판 순서로 말해 보세요.")
                val choices = mutableListOf<View>()
                if (drill.challenge) {
                    choices += reply(TowRehearsal.Place.SAMPLE.label, true) { drill.choosePlace(TowRehearsal.Place.SAMPLE) }
                } else {
                    towLocation?.let { place ->
                        choices += reply("입력한 위치로 연습", true) { drill.choosePlace(TowRehearsal.Place.typed(place)) }
                    }
                    choices += reply("가상 위치로 연습", choices.isEmpty()) { drill.choosePlace(TowRehearsal.Place.SAMPLE) }
                    choices += reply("위치를 모르겠어요") { drill.choosePlace(TowRehearsal.Place.UNKNOWN) }
                }
                grid(col, choices, columns = choices.size, expand = true, equalRows = true)
                footer(col, listOf(button("연습 끝내기", false) { exit() }))
            }
            TowRehearsal.Step.MOBILITY -> {
                question("2/3 · 상담원", "차량을 옮길 수 있나요?", "이미 아는 내용만 답하세요. 확인을 위해 차를 움직일 필요는 없어요.")
                val choices = TowRehearsal.MobilityAnswer.entries.map { answer ->
                    reply(answer.label) { drill.chooseMobility(answer) }
                }
                grid(col, choices, columns = 3, expand = true, equalRows = true)
                footer(col, listOf(
                    button("이전", false) { act { drill.editPlace() } },
                    button("연습 끝내기", false) { exit() }
                ))
            }
            TowRehearsal.Step.READBACK -> {
                question("3/3 · 상담원", "제가 들은 내용이 맞나요?",
                    if (drill.showMismatchHint) "표지 번호가 달라요. 먼저 번호를 바로잡으세요."
                    else "틀린 내용이 있으면 확인하기 전에 고쳐 보세요.",
                    warning = drill.showMismatchHint)
                val place = checkNotNull(drill.place)
                val mobility = checkNotNull(drill.mobility)
                grid(col, listOf(
                    if (drill.challenge) statusCard("상담원 복창 · 연습 예시", checkNotNull(drill.readbackPlace),
                        "내가 말한 위치 · ${place.label}")
                    else statusCard("위치 · ${place.source}", place.label),
                    statusCard("차량 이동 가능 여부 · 사용자 답변", mobility.label)
                ), columns = 2, expand = true, equalRows = true)
                footer(col, listOf(
                    button("연습 끝내기", false) { exit() },
                    if (drill.challenge) button("표지 번호 정정", drill.showMismatchHint, enabled = drill.hasReadbackMismatch) {
                        act { drill.correctReadback() }
                    } else button("위치 다시 답하기", false) { act { drill.editPlace() } },
                    button("차량 다시 답하기", false) { act { drill.editMobility() } },
                    button("복창이 맞아요", !drill.showMismatchHint, enabled = !drill.showMismatchHint) {
                        act { drill.confirmReadback() }
                    }
                ))
            }
            TowRehearsal.Step.DONE -> {
                question("복창 확인", "통화 연습을 마쳤어요", "실제로 연결되거나 접수된 내용은 없어요.")
                addBlock(col, statusCard("기억할 순서", "위치 → 차량을 옮길 수 있는지 → 상대가 들은 내용 확인"))
                footer(col, listOf(
                    button("다시 연습", false) { act { towRehearsal = TowRehearsal() } },
                    button("복창 오류 연습", false) { act { towRehearsal = TowRehearsal(challenge = true) } },
                    button(if (roadsideStep == 3) "연락 화면으로" else "보험·견인으로", true) { exit() }
                ))
            }
        }
    }

    private fun roadside(col: LinearLayout, mob: MobilityState) {
        fun step(next: Int) {
            if (!canEditRoadside()) return
            roadsideStep = next
            towLocation = null
            body.scrollTo(0, 0)
            refresh(force = true)
        }
        if (!roadsideParked(mob)) {
            addBlock(col, card(C_CARD, text("주차한 뒤 준비하세요", 32f, true),
                text("견인 통화 준비와 정보 입력은 차가 멈추고 기어가 P일 때 열립니다.", 22f)))
            val number = prep.insurerPhone ?: prep.generalPhone
            grid(col, listOfNotNull(
                number?.let { largeAction("보험사 전화", true) { openDial(it, "insurer") } },
                largeAction("고속도로 긴급견인\n${HandoffCards.NUM_EXPRESSWAY_TOW}", false) {
                    openDial(HandoffCards.NUM_EXPRESSWAY_TOW, "expressway-tow")
                }
            ), columns = if (number == null) 1 else 2)
            footer(col, listOf(button("홈으로", false) { go(Screen.HOME) }), show119 = true)
            return
        }
        towRehearsal?.let { drill -> rehearsal(col, drill); return }
        val question = when (roadsideStep) {
            1 -> "좌석별 부상 기록"
            2 -> "어떤 상황인가요?"
            else -> "연락 및 공유"
        }
        col.addView(flowHeading(roadsideStep, 3, question, 32f))
        when (roadsideStep) {
            1 -> seatInjuryPicker(col, expand = true)
            2 -> grid(col, HandoffCards.CATEGORIES.map { value ->
                choiceButton(categoryLabel(value), null, Session.category == value, box = false, big = true) {
                    if (!canEditRoadside()) return@choiceButton
                    setPassportCategory(value)
                    Log.i(tag, "CATEGORY=$value")
                    step(3)
                }.apply { minHeight = dp(100) }
            }, expand = true, equalRows = true)
            else -> {
                col.addView(text("부상 입력 ${Session.injuriesBySeat.size}명 · ${injuryCounts()}", 24f, color = C_DIM))
                col.addView(text("사고 유형 · ${categoryLabel(Session.category)}", 22f, color = C_DIM))
                towPrep(col)
                val numbers = insurerNumbers()
                grid(col, listOf(
                    largeAction(if (numbers.isNotEmpty()) "보험사 전화" else "보험사 번호 등록", true) {
                        if (numbers.isNotEmpty()) callInsurer(numbers) else showInsurerDialog()
                    },
                    largeAction("고속도로 긴급견인\n${HandoffCards.NUM_EXPRESSWAY_TOW}", false) {
                        openDial(HandoffCards.NUM_EXPRESSWAY_TOW, "expressway-tow")
                    },
                    largeAction("QR로 공유", false) {
                        if (!canEditRoadside()) return@largeAction
                        Session.qrReturn = Screen.ROADSIDE
                        go(Screen.QR)
                    }
                ), columns = 3, expand = true, equalRows = true)
            }
        }
        val actions = mutableListOf(button("홈으로", false) { go(Screen.HOME) })
        if (roadsideStep == 1) actions += button("통화 연습", false, onClick = ::startRehearsal)
        if (roadsideStep > 1) actions += button("이전", false) { step(roadsideStep - 1) }
        if (roadsideStep < 3) actions += button("다음", true) { step(roadsideStep + 1) }
        if (roadsideStep == 3 && insurerNumbers().isNotEmpty()) actions += button("보험사 번호 관리", false) { showInsurerDialog() }
        footer(col, actions, show119 = true)
    }

    private fun seatNoteLines(): List<String> =
        seatCells().mapNotNull { (area, _, _) -> notes.get(area)?.let { "${qrSeat(area)} ${SeatNotes.short(it)}" } }

    /** Compact seat names for the 600-byte QR text (left-hand-drive layout: 앞왼 = driver seat). */
    private fun qrSeat(area: String) = when (area) { "ROW_1_LEFT" -> "앞왼"; "ROW_1_RIGHT" -> "앞오"; "ROW_2_LEFT" -> "뒤왼"; else -> "뒤오" }

    internal fun addBlock(col: LinearLayout, view: View) {
        col.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    internal fun grid(col: LinearLayout, cells: List<View>, columns: Int = 2, expand: Boolean = false, equalRows: Boolean = false,
                     rowWeights: List<Float> = emptyList()) {
        cells.chunked(columns).forEachIndexed { rowIndex, group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            group.forEachIndexed { index, view ->
                row.addView(view, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                    if (index < group.lastIndex) marginEnd = dp(12)
                })
            }
            col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, if (equalRows) 0 else WRAP_CONTENT, if (expand) (rowWeights.getOrNull(rowIndex) ?: 1f) else 0f).apply {
                topMargin = dp(12)
            })
        }
    }

    private fun statusCard(label: String, value: String, detail: String? = null, valueColor: Int = C_TEXT): View {
        val views = mutableListOf<View>(text(label, 22f, true, C_DIM), text(value, 30f, true, valueColor))
        detail?.let { views += text(it, 21f, color = C_DIM) }
        return card(C_CARD, *views.toTypedArray()).apply { gravity = Gravity.CENTER_VERTICAL }
    }

    private fun infoCard(label: String, value: String, source: String?): View {
        val caption = if (source == null) label else "$label · $source"
        return card(C_CARD, text(caption, 22f, true, C_DIM), text(value, 25f, true)).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
    }

    internal fun largeAction(label: String, primary: Boolean, enabled: Boolean = true, color: Int? = null,
                            action: () -> Unit): Button = button(label, primary, enabled, color, action).apply {
        size(30f)
        minHeight = dp(100)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun guide(col: LinearLayout, mobility: MobilityState) {
        if (mobility.motion == Motion.UNKNOWN && !Session.stationaryConfirmed) {
            addBlock(col, noticePanel("차량의 정차 상태를 확인할 수 없어요.", "차가 멈춘 것을 직접 확인했다면 아래 버튼을 누르세요."))
            col.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addBlock(col, largeAction("정차했어요", true) { confirmStoppedForGuide() }.apply { minHeight = dp(120) })
            footer(col, listOf(button("홈으로", false) { Session.reset(); refresh(force = true) }), show119 = true)
            return
        }
        col.addView(text("직접 한 항목을 누르세요 · ${Session.guide.count { it }}/4", 22f, color = C_DIM))
        val items = listOf(
            "비상등 켜기" to "",
            "뒤차에 알리기" to "트렁크를 열어 뒤차에 알립니다",
            "안전한 곳에서 대기" to "상황을 보고 가드레일 밖 등 안전한 곳에서\n기다립니다.",
            "도움 요청" to "112 · 119 · 도로공사 1588-2504"
        )
        grid(col, items.mapIndexed { i, (title, detail) ->
            val checked = Session.guide[i]
            choiceButton(title, detail, checked, big = i < 2) {
                Session.guide[i] = !checked
                recordInput("guide:$i", "$title · ${if (checked) "확인 취소" else "직접 확인"}",
                    (!checked).toString(), EventKind.HUMAN)
                refresh(force = true)
            }.apply { minHeight = dp(120) }
        }, expand = true, equalRows = true)
        footer(col, listOf(
            button("이전", false) { Session.active = false; go(Screen.HOME) },
            button("다음", true) { go(Screen.INJURY) }
        ), show119 = true, compactBack = true)
    }

    private fun injury(col: LinearLayout) {
        seatInjuryPicker(col, expand = true)
        nav(col, Screen.GUIDE, Screen.CONTEXT)
    }

    private fun seatInjuryPicker(col: LinearLayout, expand: Boolean = false, heading: Boolean = true) {
        if (heading) {
            col.addView(text("부상 입력 ${Session.injuriesBySeat.size}명 · ${injuryCounts()}", 24f, true))
            col.addView(text("다친 사람의 좌석을 선택하세요. 다시 누르면 취소됩니다.", 20f, color = C_DIM))
        }
        grid(col, seatCells().map { (area, label, _) ->
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            HandoffCards.INJURED.forEachIndexed { index, value ->
                val selected = Session.injuriesBySeat[area] == value
                val severe = value == HandoffCards.SEVERE_INJURY
                val choice = button(if (selected) "✓ $value" else value, selected, color = C_EMERG.takeIf { severe && selected }) {
                    if (Session.screen == Screen.ROADSIDE && !canEditRoadside()) return@button
                    if (!beginPassport()) return@button
                    val newValue = value.takeUnless { selected }
                    if (newValue == null) Session.injuriesBySeat.remove(area) else Session.injuriesBySeat[area] = newValue
                    recordInput("injury:$area", "$label · ${newValue ?: "부상 입력 취소"}", newValue)
                    Log.i(tag, "INJURY_SEAT $area=${Session.injuriesBySeat[area] ?: "unset"} total=${Session.injuriesBySeat.size}")
                    refresh(force = true)
                }.apply {
                    contentDescription = "$label, $value"
                    isSelected = selected
                    setPadding(dp(6), 0, dp(6), 0)
                    // Unselected options look the same (white); only a chosen 중상 turns red, so the state is readable at a glance.
                    if (severe && !selected) setTextColor(C_SEVERE)
                }
                actions.addView(choice, LinearLayout.LayoutParams(0, dp(76), 1f).apply {
                    if (index == 0) marginEnd = dp(10)
                })
            }
            card(C_CARD, text(label, 28f, true), actions).apply { gravity = Gravity.CENTER_VERTICAL }
        }, expand = expand, equalRows = expand)
    }

    private fun observation(key: String, format: (String) -> String): String {
        val r = hub.reading(key) ?: return "신호 없음"
        return when (r.status) {
            SignalStatus.PERMISSION_DENIED -> "조회 권한 없음"
            SignalStatus.UNAVAILABLE -> "조회할 수 없음"
            SignalStatus.ERROR -> "읽기 실패"
            SignalStatus.OK -> when {
                !hub.isFresh(r) -> "정보가 오래됐어요"
                r.raw.isNullOrBlank() -> "신호 없음"
                r.category == Category.OBSERVED_INCOMPLETE -> "${format(r.raw)} · 일부 확인"
                else -> format(r.raw)
            }
        }
    }

    private fun doorsText(forQr: Boolean = false): String {
        val labels = if (forQr) listOf("앞왼쪽", "앞오른쪽", "뒤왼쪽", "뒤오른쪽") else seatCells().map { "${it.second} 문" }
        val names = seatCells().map { "DOOR_${it.first}" }.zip(labels).toMap() + ("DOOR_REAR" to "트렁크")
        val rs = names.keys.map { it to hub.reading(it) }
        if (rs.any { it.second == null || it.second!!.status != SignalStatus.OK || !hub.isFresh(it.second) }) return "일부 또는 전체 문·트렁크 신호 없음"
        val open = rs.filter { (it.second?.raw?.toIntOrNull() ?: 0) > 0 }.map { names[it.first] }
        return if (open.isEmpty()) "모두 닫힘 보고" else "열림 보고: ${open.joinToString(", ")}"
    }

    private fun movementTitle(mob: MobilityState) = when (mob.motion) {
        Motion.MOVING -> "이동 중"
        Motion.STOPPED_PARKED -> "정차 · 기어 P"
        Motion.STOPPED_NOT_PARKED -> "정차 · 주차 기어 아님"
        Motion.UNKNOWN -> "이동 상태 확인 불가"
    }

    private fun context(col: LinearLayout, mob: MobilityState) {
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sourceBadge("차량 보고"))
            addView(text("  차량이 기록한 값이에요. 사람이 확인한 내용이 아니에요.", 20f, color = C_DIM))
        })
        val gear = gearLabel(mob.gear).takeUnless { it.startsWith("raw=") } ?: "확인 불가"
        val speed = mob.speedKmh?.let { "%.0f km/h".format(it) } ?: "확인 불가"
        val wiper = observation("WIPER_FRONT") { if (it.equals("ON", true)) "작동 중" else "꺼짐" }
        val beam = observation("LOWBEAM") { if (it.equals("ON", true)) "켜짐" else "꺼짐" }
        grid(col, listOf(
            statusCard("이동 상태", movementTitle(mob), if (mob.motion == Motion.STOPPED_PARKED) "속도 $speed" else "속도 $speed · 기어 $gear"),
            statusCard("문과 트렁크", doorsText()),
            statusCard("앞유리 와이퍼", wiper, "하향등 · $beam"),
            statusCard("에너지 잔량", energyText())
        ), expand = true)
        if (energyLow()) addBlock(col, card(C_WARN_BG,
            text("에너지 잔량 낮음 · ${LOW_ENERGY_PCT}% 이하", 24f, true, C_WARN),
            text("견인·충전·급유를 요청할 때 잔량을 함께 알려 주세요.", 20f)))
        nav(col, Screen.INJURY, Screen.SUMMARY)
    }

    private fun summaryInput(mob: MobilityState) = SummaryInput(
        System.currentTimeMillis(), mob,
        emptyList(),
        doorsText(forQr = true), wiperShort(), lowShort(), Session.category, Session.guide.count { it }, 4, false, Session.includeState,
        energyShort(), seatNoteLines(), Session.includeNotes,
        injuryCounts().takeIf { Session.injuriesBySeat.isNotEmpty() })

    /** Fresh OK percentage for an energy key, else null. */
    private fun pct(key: String): Int? = hub.reading(key)?.takeIf { it.status == SignalStatus.OK && hub.isFresh(it) }?.raw?.toFloatOrNull()?.toInt()

    private fun energyText(): String {
        val parts = mutableListOf<String>()
        hub.reading("EV_SOC")?.let { parts += "배터리 " + observation("EV_SOC") { v -> v.toFloatOrNull()?.let { "${it.toInt()}%" } ?: "값 확인 불가" } }
        val fuel = hub.reading("FUEL_PCT")
        if (fuel != null && (fuel.status == SignalStatus.OK || parts.isEmpty())) parts += "연료 " + observation("FUEL_PCT") { v -> v.toFloatOrNull()?.let { "${it.toInt()}%" } ?: "값 확인 불가" }
        return if (parts.isEmpty()) "신호 없음" else parts.joinToString("\n")
    }

    private fun energyShort(): String {
        val p = listOfNotNull(pct("EV_SOC")?.let { "배터리 $it%" }, pct("FUEL_PCT")?.let { "연료 $it%" })
        return if (p.isEmpty()) "신호없음" else p.joinToString(" · ") + if (energyLow()) "(낮음 보고)" else ""
    }

    private fun energyLow() = listOfNotNull(pct("EV_SOC"), pct("FUEL_PCT")).any { it <= LOW_ENERGY_PCT }

    private fun wiperShort() = hub.reading("WIPER_FRONT").let { if (it == null || it.status != SignalStatus.OK || !hub.isFresh(it)) "신호없음" else if (it.raw.equals("ON", true)) "작동" else "꺼짐" }
    private fun lowShort() = hub.reading("LOWBEAM").let { if (it == null || it.status != SignalStatus.OK || !hub.isFresh(it)) "신호없음" else if (it.raw.equals("ON", true)) "켜짐" else "꺼짐" }

    private var editSummary = false

    private fun categoryLabel(value: String?) = when (value) {
        "차량 간" -> "차량 간 사고"
        "단독" -> "단독 사고"
        "모르겠음" -> "모르겠어요"
        null -> "미입력"
        else -> value
    }

    private fun readableSummary(col: LinearLayout, mob: MobilityState, res: IncidentSummary.Result) {
        val details = mutableListOf(injuryCounts())
        details += injuryDetails()
        details += "사고 유형 · ${categoryLabel(Session.category)}"
        if ("사용자 확인" !in res.dropped) details += "행동 확인 · ${Session.guide.count { it }}/4"
        val state = mutableListOf<String>()
        if ("에너지(차량)" !in res.dropped) state += energyShort().replace("신호없음", "신호 없음")
        if ("문/트렁크(차량)" !in res.dropped) state += "문과 트렁크 · ${doorsText()}"
        if ("와이퍼(차량)" !in res.dropped) state += "와이퍼 · ${wiperShort().replace("신호없음", "신호 없음")} / 하향등 · ${lowShort().replace("신호없음", "신호 없음")}"
        val cards = mutableListOf(sourceCard("사용자 입력", "부상 입력 ${Session.injuriesBySeat.size}명", details.joinToString("\n")))
        if (Session.includeState) cards += sourceCard("차량 보고", movementTitle(mob), state.joinToString("\n").ifEmpty { null })
        grid(col, cards)
        if (Session.includeNotes) {
            val notesToShare = seatCells().mapNotNull { (area, label, _) ->
                notes.get(area)?.let { statusCard(label, noteLabel(it)) }
            }
            if (notesToShare.isNotEmpty()) {
                addBlock(col, text("출발 전 메모", 23f, true))
                grid(col, notesToShare)
            }
        }
        if (res.dropped.isNotEmpty()) addBlock(col, text("QR 용량에 맞춰 일부 항목을 뺐어요.", 18f, color = C_DIM))
    }

    private fun summary(col: LinearLayout, mob: MobilityState) {
        val res = IncidentSummary.build(summaryInput(mob))
        if (!editSummary) {
            readableSummary(col, mob, res)
            addBlock(col, button("공유 내용 수정", false) { editSummary = true; body.scrollTo(0, 0); refresh(force = true) })
            nav(col, Screen.CONTEXT, Screen.QR, "QR 만들기")
            return
        }
        col.addView(sectionTitle("QR에 포함할 항목", "체크한 항목만 QR 요약에 들어가요."))
        val toggles = listOf(
            Triple("차량 상태", Session.includeState, { Session.includeState = !Session.includeState }),
            Triple("좌석 메모", Session.includeNotes, { Session.includeNotes = !Session.includeNotes })
        )
        grid(col, toggles.map { (label, included, toggle) ->
            choiceButton(label, if (included) "QR에 포함돼요" else "QR에서 제외돼요", included) { toggle(); refresh(force = true) }
        })
        incidentChoices(col)
        footer(col, listOf(button("요약으로 돌아가기", true) {
            editSummary = false
            body.scrollTo(0, 0)
            refresh(force = true)
        }), show119 = true)
    }

    private fun incidentChoices(col: LinearLayout) {
        addBlock(col, sectionTitle("좌석별 부상 기록", "부상 입력 ${Session.injuriesBySeat.size}명 · ${injuryCounts()} · 다시 누르면 취소돼요"))
        seatInjuryPicker(col, heading = false)
        addBlock(col, sectionTitle("사고 유형", "선택 사항 · 사용자 입력이며 차량이 감지한 사고가 아니에요"))
        grid(col, HandoffCards.CATEGORIES.map { value ->
            val selected = Session.category == value
            choiceButton(categoryLabel(value), null, selected, box = false) {
                setPassportCategory(value.takeUnless { selected })
                Log.i(tag, "CATEGORY=${Session.category}")
                refresh(force = true)
            }
        }, 3)
    }

    private fun qr(col: LinearLayout, mob: MobilityState) {
        val res = IncidentSummary.build(summaryInput(mob))
        val cached = qrCache
        val triple = if (cached != null && cached.first == res.text) cached else {
            val bmp = QrRenderer.encode(res.text)
            val ok = bmp != null && QrRenderer.selfCheck(bmp, res.text)
            Log.i(tag, "QR generated bytes=${res.bytes} selfCheckDecodeMatch=$ok dropped=${res.dropped} steps=${QrRenderer.lastReport}")
            bmp?.let { Triple(res.text, it, ok) }.also { qrCache = it }
        }
        if (triple == null || !triple.third) {
            addBlock(col, card(C_WARN_BG, text("QR을 읽을 수 없어요", 26f, true, C_WARN), text("아래 내용을 사진으로 남길 수 있어요.", 21f)))
            addBlock(col, button("QR 다시 만들기", true) { qrCache = null; Log.i(tag, "QR_STAGE retry screen=QR"); refresh(force = true) })
            readableSummary(col, mob, res)
        } else {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val img = ImageView(this).apply { setImageBitmap(triple.second); setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)) }
            if (lastD5Display != res.text) {
                lastD5Display = res.text
                passportAction(LocalAction.QR_GENERATED, "기본 요약")
                img.post {
                    if (!destroyed && visible && img.isShown && Session.screen == Screen.QR) {
                        passportAction(LocalAction.QR_DISPLAYED, "기본 요약")
                    }
                }
            }
            row.addView(img, LinearLayout.LayoutParams(dp(520), dp(520)))
            val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, 0, 0) }
            side.addView(text("휴대폰 카메라로 스캔", 31f, true))
            side.addView(text("신고나 메시지는 전송되지 않아요.", 21f, color = C_DIM))
            addBlock(side, sourceCard("사용자 입력", "부상 입력 ${Session.injuriesBySeat.size}명",
                "${injuryCounts()}\n사고 유형 · ${categoryLabel(Session.category)}"))
            if (Session.includeState) addBlock(side, sourceCard("차량 보고", movementTitle(mob)))
            row.addView(side, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        val back = Session.qrReturn
        if (back != null) {
            // Opened from a handoff card: return there; the record flow's "처음으로(종료)" is not shown here.
            footer(col, listOf(button("이전 화면", true) { Session.qrReturn = null; go(back) }), show119 = back == Screen.ROADSIDE)
        } else nav(col, Screen.SUMMARY, Screen.DONE, "완료")
    }

    private fun done(col: LinearLayout) {
        col.addView(emptyState(R.drawable.ic_check, "상황 정리를 마쳤어요", "기록은 사고 기록에 남아 있어요. 필요하면 다시 열 수 있어요."),
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        grid(col, listOf(
            sourceCard("직접 확인", "행동 확인 ${Session.guide.count { it }}/4"),
            sourceCard("사용자 입력", "부상 입력 ${Session.injuriesBySeat.size}명", injuryCounts())
        ))
        addBlock(col, text("신고나 메시지는 전송되지 않았어요.", 22f, color = C_DIM))
        footer(col, listOf(button("홈으로", true) { Log.i(tag, "ASSIST_COMPLETED"); Session.reset(); refresh(force = true) }), show119 = true)
    }

    // --- small view helpers --------------------------------------------------------------------------

    private fun motionLabel(m: Motion) = when (m) {
        Motion.MOVING -> "이동 중"; Motion.STOPPED_PARKED -> "정차·주차"; Motion.STOPPED_NOT_PARKED -> "정차(P 아님)"; Motion.UNKNOWN -> "상태 확인 불가"
    }
    private fun gearLabel(g: Int?) = when (g) { null -> "신호 없음"; 4 -> "P"; 8 -> "D"; 2 -> "R"; 1 -> "N"; else -> "raw=$g" }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density * UI_SCALE).toInt()
    /** Four type roles for the whole app: caption 18, body 22, subtitle 28, title 36. Display numerals (>=40) all use 48. */
    private fun tier(x: Float): Float = when {
        x >= 40f -> 48f
        x >= 32f -> 36f
        x >= 25f -> 28f
        x >= 20.5f -> 22f
        else -> 18f
    }
    private fun Button.size(x: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, tier(x) * UI_SCALE)

    internal fun text(s: String, size: Float, bold: Boolean = false, color: Int = C_TEXT) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, tier(size) * UI_SCALE); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    internal fun card(bg: Int, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(20).toFloat()
            setStroke(dp(1), if (bg == C_WARN_BG) C_WARN_LINE else C_HAIR)
        }
        setPadding(dp(24), dp(16), dp(24), dp(16))
        // Reference 02/06: information blocks sit on the page under a rule, not inside boxes. Warnings keep their fill.
        if (bg == C_CARD) { background = RuleDrawable(C_LINE, dp(2).toFloat()); setPadding(dp(2), dp(18), dp(8), dp(12)) }
        views.forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    }

    internal fun button(label: String, primary: Boolean, enabled: Boolean = true, color: Int? = null, onClick: () -> Unit) = Button(this).apply {
        text = label
        isSingleLine = false
        size(26f)
        isAllCaps = false
        isEnabled = enabled
        minHeight = dp(76)
        if (color != null) setTypeface(typeface, Typeface.BOLD)
        // Coloured buttons (119 / 112 / green) keep white text; the pink primary and the white cards use plum text.
        setTextColor(if (!enabled) C_DIM else if (color != null && color != C_STANDARD) Color.WHITE else C_TEXT)
        background = GradientDrawable().apply {
            setColor(if (!enabled) C_DISABLED else color ?: if (primary) C_ACCENT else C_CARD); cornerRadius = dp(14).toFloat()
            if (!primary || color == C_STANDARD) setStroke(dp(2), C_LINE)
        }
        setOnClickListener {
            val since = SystemClock.uptimeMillis() - activatedAt
            // inside Button.apply, `tag` would be View.getTag(); use the activity's log tag
            if (since < ACTIVATION_GUARD_MS) { Log.i(this@ProductActivity.tag, "CLICK_IGNORED_AFTER_ACTIVATION '$label' ${since}ms"); return@setOnClickListener }
            if (isEnabled) { Log.i(this@ProductActivity.tag, "CLICK '$label'"); onClick() }
        }
    }

    internal companion object {
        val C_STANDARD = 0xFFFFFFFF.toInt()
        const val PERM_SPEED = "android.car.permission.CAR_SPEED"
        /** Head-unit speech service that answers android.intent.action.TTS_SERVICE on the Pleos emulator. */
        const val TTS_ENGINE = "ai.pleos.playground.caas"
        const val LOCATION_MAX = 60
        const val UI_SCALE = 1.45f // 2560px / 160dpi display: provisional scaling until designer tokens arrive
        // Premium Blush tokens (Phase 01). Pink accent never stronger than #FF8FAB. Semantic colours (119 red, 112 blue,
        // protection green, caution amber, severe red) stay as safety exceptions. C_WARN is darker than #D4AA4B so text stays readable.
        val C_FRAME = 0xFFFFD6E6.toInt()   // blush pink
        val C_BG = 0xFFF7F6F3.toInt()      // primary background (pearl / ivory, decided 2026-11-13)
        val C_BG2 = 0xFFF4F1ED.toInt()     // secondary background (rail, strips)
        val C_PLUM = 0xFF71364C.toInt()    // dark plum: numerals, selected state, limited emphasis (8.7:1 on white)
        val C_CARD = 0xFFFFFFFF.toInt()    // white surface
        val C_LINE = 0xFFD9D4CB.toInt()    // border / divider
        val C_TEXT = 0xFF20242B.toInt()    // main text (charcoal)
        val C_DIM = 0xFF59616C.toInt()     // secondary text
        val C_ACCENT = 0xFFFF8FAB.toInt()  // accent pink
        val C_SOFT = 0xFFFFE5EC.toInt()    // soft pink
        val C_OK = 0xFF0E7360.toInt()   // protection green, darkened for text (>=5:1 on white)
        val C_WARN = 0xFF8A6200.toInt()
        val C_WARN_LINE = 0xFFD4AA4B.toInt()
        val C_WARN_BG = 0xFFFFF3D6.toInt()
        val C_NOTE = 0xFF71364C.toInt()   // plum for guidance lines
        val C_EMERG = 0xFFC9363E.toInt()
        val C_POLICE = 0xFF2868B7.toInt()
        val C_SEVERE = 0xFFC93F48.toInt()
        val C_DISABLED = 0xFFECE9E4.toInt()
        val C_EMERG_BG = 0xFFFFF1F0.toInt()
        // R2D SOS (orange family, fixed 2026-11-14): main text/icon, fill, line
        val C_SOS = 0xFFC65A08.toInt()
        val C_SOS_BG = 0xFFFFF3E6.toInt()
        val C_SOS_LINE = 0xFFF0CCA4.toInt()
        val C_POLICE_BG = 0xFFEEF7FF.toInt()
        // R2D 112 round quick button (fixed 2026-11-14): main blue, halo fill, halo line
        val C_QUICK = 0xFF2F6FD6.toInt()
        val C_QUICK_BG = 0xFFEAF2FF.toInt()
        val C_QUICK_LINE = 0xFF8CB3F3.toInt()
        val C_OK_BG = 0xFFF0FAF6.toInt()
        val C_SEVERE_BG = 0xFFFDECEE.toInt()
        val C_GRAY = 0xFFECE9E4.toInt()
        val C_TILE = 0xFFFFE5EC.toInt()
        val C_TILE_ICON = 0xFF20242B.toInt()
        val C_HAIR = 0xFFE7E3DC.toInt()         // hairline for flat white surfaces
        val C_RAIL_OFF = 0xFF9AA0A8.toInt()     // rail item disabled while moving
        val C_PEARL = 0xFFFFFCFD.toInt()        // pearl white SUV body
        val C_EMPTY_SEAT = 0xFFFFFFFF.toInt()   // seat map: reported empty (white, thin dark outline)
        val C_SEAT_LINE = 0xFF7C838C.toInt()    // seat map: seat outline (>=3:1 on white)
        const val LOW_ENERGY_PCT = 10
        const val ACTIVATION_GUARD_MS = 700L
    }
}

