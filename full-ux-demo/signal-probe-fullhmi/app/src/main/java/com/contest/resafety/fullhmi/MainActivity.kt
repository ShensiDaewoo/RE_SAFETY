package com.contest.resafety.fullhmi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * RE:SAFETY Full HMI concept (stand-alone demonstration).
 *
 * One full-screen WebView that shows assets/index.html. The page is a concept of an OEM-integrated accident HMI:
 * it does NOT modify, cover or replace any Pleos system screen.
 * aurora13: on an automotive head unit (Pleos Connect) protect mode uses the real Pleos Vehicle SDK through [VehicleLink]
 * (the R2D submission app's verified code: park gate on fresh gear P + 0 km/h, read before, write, read again, undo of what this app changed).
 * On any other device the page keeps the aurora12 simulation.
 *
 *  - prot():       snapshot of the vehicle protect state for the page ("" = no vehicle on this device)
 *  - protStart():  user tap "on" / "off"; returns "" when started, otherwise the reason it was not started
 *  - protScreen(): whether the protect screen is shown (leaving it stops a running request, as in R2D)
 *
 * Native bridge (window.Native):
 *  - cfg():   whether a TEST relay is configured and the VIRTUAL position (never a real location)
 *  - relay(): one HTTP/JSON call to the nearby-SOS TEST relay (protocol v1). Only "/v1/..." paths on the configured base.
 *  - dial():  opens the dialer with 119 or 112 entered (ACTION_DIAL). The app never places a call and has no CALL_PHONE permission.
 *  - qr():    offline QR module matrix for the incident summary (ZXing core).
 *  - bg():    which contract background pictures (HMI_BG_01_NORMAL / HMI_BG_02_ACCIDENT) are bundled under assets/hmi.
 *
 * Test credentials arrive as intent extras (relay_base, relay_id, relay_token, relay_east, relay_north) and stay in memory only.
 */
class MainActivity : Activity() {
    private val tag = "FullHMI"
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    @Volatile private var base: String? = null
    @Volatile private var clientId: String? = null
    @Volatile private var token: String? = null
    @Volatile private var east = Double.NaN
    @Volatile private var north = Double.NaN
    @Volatile private var persisted = false
    @Volatile private var pendingEvent: String? = null
    @Volatile private var pageReady = false
    @Volatile private var pendingLabQr: String? = null      // HARDENING build only: text for the lab QR check
    private val labPrefs by lazy { getSharedPreferences("fullhmi_lab", MODE_PRIVATE) }

    // ---- aurora13: protect mode on the real vehicle (main thread only, except the @Volatile fields) ----
    private var veh: VehicleLink? = null
    private var protPort: VehicleProtectionPort? = null
    private var protRun: DProtectionRun? = null
    private var restorePort: VehicleProtectionPort? = null
    private var restoreRun: DProtectionRun? = null
    private var runId = 0L
    private var runMode = ""
    private var loggedRun: DProtectionRun? = null
    @Volatile private var protScreenShown = false
    @Volatile private var resumed = false
    @Volatile private var vehSnapshot = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras(intent)
        if (packageManager.hasSystemFeature("android.hardware.type.automotive")) {
            veh = VehicleLink(this) { ui.post { vehTick() } }.also { it.start() }
            refreshSnapshot(push = false)
            // CAR_SPEED (park gate) and FUSED_LOCATION (vehicle position) are runtime permissions; asked once together, as in R2D.
            // Without CAR_SPEED the park gate stays closed and nothing is requested; without FUSED_LOCATION the summary says the position is unknown.
            val missing = listOf(PERM_SPEED, VehicleLink.PERM_FUSED).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
            startSpeech()
            startStt()
            Log.i(tag, "VEHICLE_MODE automotive=true")
        }
        takeGleo(intent)
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true            // the page itself is file:///android_asset/
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        web.webViewClient = object : WebViewClient() {
            // Nothing but the bundled page may load in this view.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith("file:///android_asset/")
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean { Log.i(tag, m.message()); return true }
        }
        web.addJavascriptInterface(Bridge(), "Native")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        Log.i(tag, "START relay=${base != null} testPos=${!east.isNaN() && !north.isNaN()}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readExtras(intent)
        Log.i(tag, "NEW_INTENT relay=${base != null}")
        takeGleo(intent)
        if (pageReady) pendingGleo?.let { g -> pendingGleo = null; web.evaluateJavascript("window.onGleo(${JSONObject.quote(g)})", null) }
        if (intent.getBooleanExtra("reload", false)) { pageReady = false; web.reload(); return }
        if (pageReady) pendingLabQr?.let { q -> pendingLabQr = null; web.evaluateJavascript("window.onLabQr(${JSONObject.quote(q)})", null) }
        if (pageReady) pendingEvent?.let { ev ->                                // the page is already up: hand the virtual event over now
            pendingEvent = null
            web.evaluateJavascript("window.onVehicleEvent(${JSONObject.quote(ev)})", null)
        }
    }

    override fun onPause() { super.onPause(); resumed = false; vehTick(); Log.i(tag, "LIFECYCLE pause") }
    override fun onResume() {
        super.onResume(); resumed = true; Log.i(tag, "LIFECYCLE resume")
        if (pageReady) web.evaluateJavascript("window.onShown && window.onShown()", null)      // adaptive: the page re-reads the scene when it is shown again
    }
    override fun onStop() { super.onStop(); Log.i(tag, "LIFECYCLE stop") }
    override fun onDestroy() {
        protRun?.cancel("앱이 종료됐어요."); restoreRun?.cancel("앱이 종료됐어요."); veh?.release(); veh = null
        try { pleosTts?.release() } catch (_: Throwable) {}
        try { pleosStt?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ---- adaptive6: Gleo Interface (assets/schema.json). Gleo starts this activity with the function name as the intent action. ----
    @Volatile private var pendingGleo: String? = null
    private fun takeGleo(i: Intent?) {
        val a = i?.action ?: return
        if (a !in GLEO_ACTIONS) return
        pendingGleo = a
        Log.i(tag, "GLEO_ACTION received $a param=${i.getStringExtra("ACTION_PARAM")?.length ?: 0} chars")
        i.action = Intent.ACTION_MAIN                                          // handled once
    }

    /** Callback to Gleo (Pleos guide "Code Details with Examples": broadcast APP_ACTION_RESPONSE with packageName and a JSON response). */
    private fun gleoRespond(function: String, ok: Boolean, message: String) {
        val response = JSONObject().put("functionName", function).put("status", if (ok) "success" else "failure").put("message", message).toString()
        try {
            sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE").putExtra("packageName", packageName).putExtra("response", response))
            Log.i(tag, "GLEO_CALLBACK sent $response")
        } catch (e: Throwable) { Log.i(tag, "GLEO_CALLBACK failed ${e.javaClass.simpleName}") }
    }

    // ---- adaptive6: voice input through the Pleos SpeechToText SDK, on-device mode, on a tap only (no always-on listening) ----
    private var pleosStt: ai.pleos.playground.stt.SpeechToText? = null
    @Volatile private var sttState = "off"
    @Volatile private var sttField = ""
    @Volatile private var sttTestPcm: ByteArray? = null                         // lab hook: PCM handed in by a test (16 kHz, 16-bit, mono)

    private fun sttToPage(text: String, final: Boolean, state: String) {
        val f = sttField
        ui.post { web.evaluateJavascript("window.onStt && window.onStt(${JSONObject.quote(f)},${JSONObject.quote(text)},$final,${JSONObject.quote(state)})", null) }
    }

    private fun startStt() {
        try {
            val s = ai.pleos.playground.stt.SpeechToText(applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
            pleosStt = s
            val results = object : ai.pleos.playground.stt.listener.ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) { Log.i(tag, "STT_RESULT final=$isFinal chars=${text.length}"); sttToPage(text, isFinal, if (isFinal) "final" else "partial") }
                override fun onUpdatedEpdData(a: Long, b: Long) {}
                override fun onStartedRecognition() { Log.i(tag, "STT_EVENT started"); sttToPage("", false, "listening") }
                override fun onEndedRecognition() { Log.i(tag, "STT_EVENT ended"); sttToPage("", false, "ended") }
                override fun onError(error: ai.pleos.playground.stt.constant.ErrorType) { Log.i(tag, "STT_EVENT error $error"); sttToPage("", false, "error:$error") }
                override fun onReady() { Log.i(tag, "STT_EVENT ready") }
                override fun onUpdatedRms(rms: Double) {}
            }
            // Same order as the TTS SDK: initialize() first, then listener and locale.
            s.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                override fun onSuccess() {
                    try { s.addListener(results) } catch (e: Throwable) { Log.i(tag, "STT listener ${e.javaClass.simpleName}") }
                    try { s.setLocale(java.util.Locale.KOREA) } catch (e: Throwable) { Log.i(tag, "STT locale ${e.javaClass.simpleName}") }
                    sttState = "ready"; Log.i(tag, "STT_INIT pleos on-device ready"); ui.post { refreshSnapshot(push = true) }
                }
                override fun onFailure(error: ai.pleos.playground.stt.constant.ErrorType) { sttState = "error"; Log.i(tag, "STT_INIT failed $error"); ui.post { refreshSnapshot(push = true) } }
            })
            sttState = "waiting"
        } catch (e: Throwable) {
            sttState = "error"; Log.i(tag, "STT_INIT exception ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
        }
    }

    private fun sttStart(field: String): Boolean {
        val s = pleosStt
        if (s == null || sttState != "ready") return false
        sttField = field
        val pcm = sttTestPcm
        return try {
            if (pcm != null) {                                                   // test: the SDK's "send audio" path instead of the microphone
                sttTestPcm = null
                thread(name = "stt-test-audio") {
                    var i = 0
                    while (i < pcm.size) { val n = minOf(3_200, pcm.size - i); s.sendAudio(pcm.copyOfRange(i, i + n)); i += n; Thread.sleep(100L) }
                    s.completeAudioSend(); Log.i(tag, "STT_TEST_AUDIO sent ${pcm.size} bytes")
                }
            } else s.request()
            Log.i(tag, "STT_START field=$field source=${if (pcm != null) "test-audio" else "microphone"}"); true
        } catch (e: Throwable) { Log.i(tag, "STT_START failed ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}"); false }
    }

    // ---- aurora14: spoken result through the Pleos TextToSpeech SDK, on-device mode (no network, no app registration) ----
    private var pleosTts: ai.pleos.playground.tts.TextToSpeech? = null
    @Volatile private var ttsState = "off"

    private fun startSpeech() {
        try {
            val t = ai.pleos.playground.tts.TextToSpeech(applicationContext, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
            pleosTts = t
            val events = object : ai.pleos.playground.tts.listener.EventListener {
                override fun onStart() { Log.i(tag, "TTS_EVENT start") }
                override fun onReady() {}
                override fun onDone() { Log.i(tag, "TTS_EVENT done") }
                override fun onError(error: ai.pleos.playground.tts.constant.ErrorType) { Log.i(tag, "TTS_EVENT error $error") }
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) {}
            }
            // Order from the Pleos guide: initialize() first, then addEventListener(), setLocale(), speak(). (Adding the listener first throws.)
            t.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                override fun onSuccess() {
                    try { t.addEventListener(events) } catch (e: Throwable) { Log.i(tag, "TTS listener ${e.javaClass.simpleName}") }
                    // ko-KR: the on-device engine rejects the bare language "ko" ("unsupported locale: ko-", aurora16 run)
                    try { t.setLocale(java.util.Locale.KOREA) } catch (e: Throwable) { Log.i(tag, "TTS locale ${e.javaClass.simpleName}") }
                    ttsState = "ready"; Log.i(tag, "TTS_INIT pleos on-device ready"); ui.post { refreshSnapshot(push = true) }
                }
                override fun onFailure(error: ai.pleos.playground.tts.constant.ErrorType) { ttsState = "error"; Log.i(tag, "TTS_INIT failed $error"); ui.post { refreshSnapshot(push = true) } }
            })
            ttsState = "waiting"
        } catch (e: Throwable) {
            ttsState = "error"; Log.i(tag, "TTS_INIT exception ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
        }
    }

    private fun speak(text: String) {
        val t = pleosTts
        if (t == null || ttsState != "ready" || text.isBlank()) { Log.i(tag, "TTS_SKIPPED state=$ttsState"); return }
        try { t.speak(text); Log.i(tag, "TTS_SPEAK chars=${text.length}") } catch (e: Throwable) { Log.i(tag, "TTS_SPEAK failed ${e.javaClass.simpleName}") }
    }

    // ---- adaptive: while the Pleos navigation is in front this app's page is asleep, so "parked again" is watched here, next to the
    //      vehicle signals (the same P + 0 km/h rule as the protect gate, held for 2 s). The page still decides the scene once it is shown.
    @Volatile private var pageScene = "READY"
    private var parkedSinceMs = 0L
    private var returnAskedAt = 0L
    private fun sceneWatch() {
        if (veh == null) return
        val native = pageScene == "DRIVING" || pageScene == "ACCIDENT_PENDING"
        val now = android.os.SystemClock.elapsedRealtime()
        if (!native || parkBlock() != null) { parkedSinceMs = 0L; return }
        if (parkedSinceMs == 0L) parkedSinceMs = now
        if (now - parkedSinceMs < 2_000L || resumed || now - returnAskedAt < 5_000L) return
        returnAskedAt = now
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            Log.i(tag, "SCENE_RETURN requested by the vehicle watch (parked 2 s, page scene $pageScene)")
        } catch (e: Throwable) { Log.i(tag, "SCENE_RETURN refused ${e.javaClass.simpleName}") }
        ui.postDelayed({ Log.i(tag, "SCENE_RETURN watch result: ${if (resumed) "in front" else "not in front (driver opens the app, or OEM integration)"}") }, 2500)
    }

    // ---- adaptive: what the app itself may do at a scene change on Pleos (everything else is the system's or needs OEM integration) ----
    private fun onScene(next: String, prev: String) {
        val v = veh ?: return                                                  // stand-alone prototype: the page shows its place-holder, nothing to do here
        val toNative = next == "DRIVING" || next == "ACCIDENT_PENDING"
        val fromNative = prev == "DRIVING" || prev == "ACCIDENT_PENDING"
        // Ordinary driving: the screen goes to the Pleos navigation. With an accident signal the app stays where it is (the system keeps
        // it covered while the car moves), so that the accident screen is there by itself the moment the car is parked.
        if (next == "DRIVING" && !fromNative) v.requestNavigationForeground { r -> Log.i(tag, "SCENE_HANDOVER to Pleos navigation: $r") }
        if (next == "ACCIDENT_PENDING") speak("시뮬레이션 안내입니다. 사고 신호가 감지되었습니다. 안전한 곳에 정차하면 사고 대응 화면이 열립니다.")
        if (!toNative && fromNative) {
            // Parked again: READY or ACCIDENT_RESPONSE is ours. Coming back to the front by itself is an ordinary activity start; when the
            // platform refuses it for an app in the background, the driver opens RE:SAFETY from the dock (automatic return = OEM integration).
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                Log.i(tag, "SCENE_RETURN requested for $next")
            } catch (e: Throwable) { Log.i(tag, "SCENE_RETURN refused ${e.javaClass.simpleName}") }
            ui.postDelayed({ Log.i(tag, "SCENE_RETURN result for $next: ${if (resumed) "in front" else "not in front (driver opens the app, or OEM integration)"}") }, 2500)
            if (next == "ACCIDENT_RESPONSE") speak("정차를 확인했습니다. 사고 대응 화면이 준비되었습니다.")
        }
    }

    /** Short sentence for the driver's ears: only what the vehicle reported back (R2D dSpoken). */
    private fun spoken(run: DProtectionRun, restoring: Boolean): String {
        val parts = run.actions.mapNotNull { a ->
            when (run.result(a).outcome) {
                DOutcome.VERIFIED -> if (restoring) "${a.label} 원래대로" else "${a.label} ${a.target}"
                DOutcome.ALREADY -> if (restoring) null else "${a.label} 이미 ${a.target}"
                DOutcome.UNSUPPORTED -> null
                else -> "${a.label} 확인 안 됨"
            }
        }
        if (parts.isEmpty()) return ""
        return (if (restoring) "되돌리기 결과. " else "보호 모드 결과. ") + parts.joinToString(", ") + "."
    }

    // ---- aurora13: protect mode on the real vehicle --------------------------------------------------------------

    private fun parkBlock(): String? { val v = veh ?: return "차량에 연결되어 있지 않아요."; return DParkGate.blocked(v.reading("SPEED"), v.reading("GEAR"), android.os.SystemClock.elapsedRealtime()) }
    private fun gate(): String? = if (!resumed || !protScreenShown) "보호 모드 화면을 벗어났어요." else parkBlock()
    private val dispatch: (Long, () -> Unit) -> Unit = { delay, work -> ui.postDelayed({ work() }, delay) }
    private fun busy() = protRun?.running == true || restoreRun?.running == true

    /** Actions whose state this app wrote and whose original value is known, not yet put back (R2D dUndoable). */
    private fun undoable(): List<DActuator> {
        val run = protRun ?: return emptyList(); val port = protPort ?: return emptyList()
        if (run.running) return emptyList()
        return DActuator.entries.filter { port.wrote(it) && port.originals.has(it) &&
            restoreRun?.takeIf { r -> r.has(it) }?.result(it)?.outcome.let { o -> o != DOutcome.VERIFIED && o != DOutcome.ALREADY } }
    }

    private fun startProtect(mode: String) {
        val v = veh ?: return
        if (busy() || gate() != null || !v.sdkReady) return
        runId = System.currentTimeMillis(); runMode = mode
        if (mode == "on") {
            val port = VehicleProtectionPort(v, { work -> ui.post(work) })
            protPort = port; restorePort = null; restoreRun = null
            protRun = DProtectionRun(port, ::gate, dispatch, changed = ::runChanged).also { it.start() }
            Log.i(tag, "PROTECT_START user tap")
        } else {
            val port = protPort ?: return
            val undo = undoable(); if (undo.isEmpty()) return
            val rp = VehicleProtectionPort(v, { work -> ui.post(work) }, restore = port.originals)
            restorePort = rp
            restoreRun = DProtectionRun(rp, ::gate, dispatch, undo, alwaysWrite = true, changed = ::runChanged).also { it.start() }
            Log.i(tag, "PROTECT_UNDO user tap ${undo.joinToString()}")
        }
    }

    private fun outcomeText(a: DActuator, o: DOutcome, restoring: Boolean): String = when (o) {
        DOutcome.VERIFIED -> if (restoring) "원래대로 확인" else "${a.target} 확인"
        DOutcome.ALREADY -> if (restoring) "이미 원래대로" else "이미 ${a.target}"
        DOutcome.MISMATCH -> "반영 대기"; DOutcome.UNSUPPORTED -> "미지원 건너뜀"; DOutcome.DENIED -> "권한 없음"
        DOutcome.WAITING -> "대기"; DOutcome.UNCERTAIN -> "확인 필요"; DOutcome.STOPPED -> "중단"; else -> o.name
    }

    private fun runChanged() = ui.post {
        for ((run, restoring) in listOf(protRun to false, restoreRun to true)) {
            if (run == null || run.running || loggedRun === run || run.actions.all { run.result(it).outcome == DOutcome.WAITING }) continue
            loggedRun = run
            Log.i(tag, "PROTECT_RESULT restoring=$restoring ${run.actions.joinToString(" / ") { "${it.label} ${outcomeText(it, run.result(it).outcome, restoring)}" }}" +
                (run.stoppedBecause?.let { " / stopped=$it" } ?: ""))
            speak(spoken(run, restoring))
        }
        refreshSnapshot(push = true)
    }

    private fun vehTick() {
        protRun?.recheck(); restoreRun?.recheck(); sceneWatch()
        refreshSnapshot(push = true)
    }

    /** One JSON for the page. Built on the main thread; the bridge only returns the last copy. */
    private fun refreshSnapshot(push: Boolean) {
        val v = veh ?: return
        val o = JSONObject()
        o.put("sdk", if (v.sdkReady) "ready" else if (v.sdkError != null) "error" else "waiting")
        o.put("gate", parkBlock().orEmpty())
        val sp = v.reading("SPEED"); val gr = v.reading("GEAR")
        o.put("kmh", sp?.takeIf { it.status == SignalStatus.OK }?.raw?.toDoubleOrNull()?.let { Math.round(it * 3.6) } ?: JSONObject.NULL)
        o.put("gear", gr?.takeIf { it.status == SignalStatus.OK }?.raw?.toIntOrNull()?.let { mapOf(4 to "P", 2 to "R", 1 to "N", 8 to "D")[it] ?: it.toString() } ?: JSONObject.NULL)
        o.put("perm", checkSelfPermission(PERM_SPEED) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        val restoring = runMode == "off"
        val run = if (restoring) restoreRun else protRun
        val port = if (restoring) restorePort else protPort
        o.put("id", if (run == null) "" else runId.toString()).put("mode", runMode).put("running", run?.running == true).put("stopped", run?.stoppedBecause.orEmpty())
        val items = JSONObject()
        for (a in DActuator.entries) {
            val j = JSONObject()
            j.put("o", if (run != null && run.has(a)) run.result(a).outcome.name else "")
            j.put("now", (port?.shown?.get(a) ?: protPort?.shown?.get(a)).orEmpty())
            items.put(a.key, j)
        }
        o.put("items", items).put("canUndo", !busy() && undoable().isNotEmpty())
        // aurora14: what else the vehicle reports (reads only)
        val now = android.os.SystemClock.elapsedRealtime()
        val f = v.fix?.takeIf { it.fresh(now) }
        o.put("loc", JSONObject().put("status", v.locationStatus)
            .put("lat", f?.lat ?: JSONObject.NULL).put("lon", f?.lon ?: JSONObject.NULL).put("addr", v.freshAddress(now) ?: JSONObject.NULL))
        val seats = JSONObject()
        for ((k, _) in VehicleLink.SEATS) seats.put(k, JSONObject().put("occ", v.seatOcc[k].orEmpty()).put("belt", v.seatBelt[k] ?: "-"))
        o.put("seats", seats).put("beltAt", v.beltAskedWall)
        val d = JSONObject(); for ((k, _) in VehicleLink.DOOR_KEYS) d.put(k, v.doors[k].orEmpty())
        o.put("doors", d).put("tts", ttsState).put("stt", sttState)
        // adaptive6: the rest of the R2D reads
        val x = JSONObject(); for (k in listOf("WIPER", "LOWBEAM", "EV_SOC", "FUEL_PCT")) x.put(k, v.extra[k] ?: JSONObject.NULL)
        o.put("extra", x)
        val h = v.handover
        o.put("handover", if (h == null) JSONObject.NULL else JSONObject().put("vin6", h.vin6 ?: JSONObject.NULL).put("odoKm", h.odometerKm ?: JSONObject.NULL)
            .put("rangeKm", h.rangeKm ?: JSONObject.NULL).put("at", h.atWall))
        val s = o.toString()
        if (s == vehSnapshot) return
        vehSnapshot = s
        if (push) web.evaluateJavascript("window.onVeh && window.onVeh()", null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private fun readExtras(i: Intent?) {
        val e = i?.extras
        if (e != null) {
            if (e.getBoolean("relay_clear", false)) clearRelayConfig()
            e.getString("relay_base")?.let { base = it.trimEnd('/') }
            e.getString("relay_id")?.let { clientId = it }
            e.getString("relay_token")?.let { token = it }
            (e.get("relay_east") as? Number)?.let { east = it.toDouble() }
            (e.get("relay_north") as? Number)?.let { north = it.toDouble() }
            if (e.getBoolean("pos_clear", false)) { east = Double.NaN; north = Double.NaN }
            // Lab convenience: keep the TEST relay settings (test id, test token, virtual position) on the device so the app can be
            // reopened from the launcher during a lab session. Only on request, never real credentials, erasable in the app.
            if (e.getBoolean("relay_persist", false) && base != null && clientId != null && token != null) {
                labPrefs.edit().putString("base", base).putString("id", clientId).putString("token", token)
                    .putString("east", east.toString()).putString("north", north.toString()).commit()
                persisted = true
                Log.i(tag, "LAB_CONFIG_SAVED")
            }
            // Virtual vehicle event (JSON, base64). The only event source in this build; a real source needs an OEM-provided, protected interface.
            e.getString("lab_qr_b64")?.let {
                try { pendingLabQr = String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) } catch (x: Exception) { Log.i(tag, "LABQR_BAD_PAYLOAD") }
            }
            // adaptive6 lab hook: 16 kHz 16-bit mono PCM for the speech-recognition test (fed through SpeechToText.sendAudio on the next "음성 입력" tap)
            e.getString("stt_test_pcm_b64")?.let {
                try { sttTestPcm = android.util.Base64.decode(it, android.util.Base64.DEFAULT); Log.i(tag, "STT_TEST_AUDIO loaded ${sttTestPcm?.size} bytes") } catch (x: Exception) { Log.i(tag, "STT_TEST_AUDIO bad payload") }
            }
            e.getString("vevent_b64")?.let {
                try { pendingEvent = String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) } catch (x: Exception) { Log.i(tag, "VEVENT_BAD_PAYLOAD") }
            }
        }
        if (base == null && labPrefs.contains("base")) {                       // no extras: fall back to a saved lab configuration
            base = labPrefs.getString("base", null); clientId = labPrefs.getString("id", null); token = labPrefs.getString("token", null)
            east = labPrefs.getString("east", "NaN")!!.toDoubleOrNull() ?: Double.NaN
            north = labPrefs.getString("north", "NaN")!!.toDoubleOrNull() ?: Double.NaN
            persisted = true
            Log.i(tag, "LAB_CONFIG_LOADED")
        }
    }

    private fun clearRelayConfig() {
        base = null; clientId = null; token = null; east = Double.NaN; north = Double.NaN; persisted = false
        labPrefs.edit().clear().commit()
        Log.i(tag, "LAB_CONFIG_CLEARED")
    }

    private inner class Bridge {
        /** aurora13: protect-state snapshot; "" when this device has no vehicle (the page then keeps its simulation). */
        @JavascriptInterface
        fun prot(): String = vehSnapshot

        /** aurora13: user tap. "" = started; otherwise why not (nothing was requested). */
        @JavascriptInterface
        fun protStart(mode: String): String {
            val v = veh ?: return "차량에 연결되어 있지 않아요."
            if (mode != "on" && mode != "off") return "알 수 없는 요청이에요."
            if (!v.sdkReady) return "차량 연결을 확인하고 있어요."
            parkBlock()?.let { return it }
            ui.post { startProtect(mode) }
            return ""
        }

        /**
         * adaptive: the page's scene controller reports every scene change (READY / DRIVING / ACCIDENT_PENDING / ACCIDENT_RESPONSE).
         * Only on a vehicle does this side act: scene B is handed to the Pleos navigation, and the two accident changes are spoken.
         */
        @JavascriptInterface
        fun scene(next: String, prev: String) { pageScene = next; Log.i(tag, "SCENE_NATIVE $prev -> $next vehicle=${veh != null}"); ui.post { onScene(next, prev) } }

        /** aurora14: user tap "안전벨트 조회": ask the vehicle what each buckle reports (read only). false = no vehicle. */
        @JavascriptInterface
        fun beltQuery(): Boolean { val v = veh ?: return false; if (!v.sdkReady) return false; v.requestBeltReports(); return true }

        /** adaptive6: user tap "차량 정보 읽기" on the pre-drive screen (R2D handover record). */
        @JavascriptInterface
        fun handoverQuery(): Boolean { val v = veh ?: return false; if (!v.sdkReady) return false; v.requestHandover(); return true }

        /** adaptive6: user tap "음성 입력" next to a field. false = no Pleos speech service here. */
        @JavascriptInterface
        fun sttStart(field: String): Boolean = this@MainActivity.sttStart(field)

        @JavascriptInterface
        fun sttStop() { try { pleosStt?.stop() } catch (_: Throwable) {} }

        /** adaptive6: a Gleo function that arrived with the launch intent (the page asks once it is ready). */
        @JavascriptInterface
        fun takeGleo(): String { val g = pendingGleo; pendingGleo = null; return g.orEmpty() }

        /** adaptive6: the page reports what it did with a Gleo function; this side answers Gleo. */
        @JavascriptInterface
        fun gleoDone(function: String, ok: Boolean, message: String) = gleoRespond(function, ok, message)

        /** aurora13: the page reports whether the protect screen is on screen; leaving it stops a running request. */
        @JavascriptInterface
        fun protScreen(shown: Boolean) {
            if (shown == protScreenShown) return
            protScreenShown = shown
            ui.post { vehTick() }
        }

        /** A virtual vehicle event that arrived with the launch intent (the page asks once it is ready). */
        @JavascriptInterface
        fun takeLabQr(): String { val q = pendingLabQr; pendingLabQr = null; return q.orEmpty() }

        @JavascriptInterface
        fun takeEvent(): String { pageReady = true; val e = pendingEvent; pendingEvent = null; return e.orEmpty() }

        /** Forget the lab TEST relay settings (memory and device). */
        @JavascriptInterface
        fun clearRelay() = clearRelayConfig()

        @JavascriptInterface
        fun cfg(): String {
            val o = JSONObject().put("relay", base != null && clientId != null && token != null).put("persisted", persisted).put("base", base ?: "")
            if (!east.isNaN() && !north.isNaN()) o.put("pos", JSONObject().put("lat", north / M_PER_DEG).put("lon", east / M_PER_DEG)) else o.put("pos", JSONObject.NULL)
            return o.toString()
        }

        @JavascriptInterface
        fun dial(number: String) {
            // 119 / 112, or a number the user typed (insurer, tow). Digits only. ACTION_DIAL opens the dialer; the user places the call.
            if (!Regex("^[0-9]{3,15}$").matches(number)) return
            val shown = if (number == "119" || number == "112") number else "user-entered(${number.length} digits)"   // typed numbers are not written to the log
            ui.post {
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                    Log.i(tag, "DIAL_SCREEN_OPENED number=$shown (call not placed by app)")
                } catch (e: ActivityNotFoundException) {
                    Log.i(tag, "DIAL_UNAVAILABLE number=$shown")
                }
            }
        }

        /** QR module matrix for [text] (ZXing core, offline). Returns {"n":size,"rows":["0101..",..]} or {} on failure. Nothing is sent anywhere. */
        @JavascriptInterface
        fun qr(text: String): String = try {
            val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
            // HARDENING: the text is never cut silently. A text that does not fit is reported as an error and no QR is shown.
            val m = Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
            val rows = JSONArray()
            for (y in 0 until m.height) rows.put(buildString(m.width) { for (x in 0 until m.width) append(if (m.get(x, y).toInt() == 1) '1' else '0') })
            Log.i(tag, "QR_CREATED modules=${m.width} bytes=${text.toByteArray(Charsets.UTF_8).size}")
            JSONObject().put("n", m.width).put("rows", rows).toString()
        } catch (e: com.google.zxing.WriterException) {
            Log.i(tag, "QR_TOO_LONG bytes=${text.toByteArray(Charsets.UTF_8).size}")
            "{\"error\":\"too_long\"}"
        } catch (e: Exception) {
            Log.i(tag, "QR_FAILED ${e.javaClass.simpleName}")
            "{\"error\":\"failed\"}"
        }

        /** Device-only storage for the incident record, the in-progress guide and the SOS consent flag. commit() writes before returning. */
        @JavascriptInterface
        fun load(key: String): String = if (key in STORE_KEYS) prefs.getString(key, "").orEmpty() else ""

        @JavascriptInterface
        fun save(key: String, value: String): Boolean =
            key in STORE_KEYS && value.length <= 200_000 && prefs.edit().putString(key, value).commit()

        /**
         * Which driving-view background pictures are bundled in this APK (hmi-assets/ASSET_CONTRACT.json):
         * assets/hmi/HMI_BG_01_NORMAL.png and assets/hmi/HMI_BG_02_ACCIDENT.png, installed by scripts/prepare_hmi_assets.py after it has
         * verified the delivered PNG files. Nothing is generated here; without the files the page keeps its own vector drawing.
         */
        @JavascriptInterface
        fun bg(): String {
            fun has(name: String) = try { assets.open("assets/hmi/$name").use { it.read() >= 0 } } catch (e: Exception) { false }
            val n = has("HMI_BG_01_NORMAL.png"); val a = has("HMI_BG_02_ACCIDENT.png")
            Log.i(tag, "BG_ASSETS normal=$n accident=$a")
            return JSONObject().put("normal", n).put("accident", a).toString()
        }

        /** status: HTTP code, -1 = relay unreachable, -2 = no relay configured or path not allowed. */
        @JavascriptInterface
        fun relay(reqId: Int, method: String, path: String, body: String) {
            val b = base; val id = clientId; val t = token
            if (b == null || id == null || t == null || !path.startsWith("/v1/") || (method != "GET" && method != "POST")) return reply(reqId, -2, "")
            thread(name = "relay") {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(b + path).openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        connectTimeout = 3000
                        readTimeout = 3000
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("X-Client-Id", id)
                        setRequestProperty("X-Client-Token", t)
                        if (method == "POST") { doOutput = true; outputStream.use { it.write((if (body.isEmpty()) "{}" else body).toByteArray(Charsets.UTF_8)) } }
                    }
                    val code = conn.responseCode
                    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    Log.i(tag, "RELAY $method $path -> $code")
                    reply(reqId, code, text)
                } catch (e: Exception) {
                    Log.i(tag, "RELAY $method $path -> unreachable")
                    reply(reqId, -1, "")
                } finally {
                    conn?.disconnect()
                }
            }
        }
    }

    private fun reply(reqId: Int, status: Int, text: String) {
        val js = "window.onRelay($reqId,$status,${JSONObject.quote(text)})"
        ui.post { web.evaluateJavascript(js, null) }
    }

    private val prefs by lazy { getSharedPreferences("fullhmi_store", MODE_PRIVATE) }

    private companion object {
        const val M_PER_DEG = 111_320.0
        const val PERM_SPEED = "android.car.permission.CAR_SPEED"
        val GLEO_ACTIONS = setOf("OPEN_ACCIDENT_RESPONSE", "OPEN_ACCIDENT_GUIDE", "OPEN_EMERGENCY_CALL_SCREEN")
        val STORE_KEYS = setOf("rec", "guide", "lastGuide", "sosConsent", "prot", "seats", "tow", "pre", "evs", "sosReq")
    }
}
