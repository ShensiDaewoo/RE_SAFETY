package com.contest.signalprobe

import android.app.Activity
import android.app.AlertDialog
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Signal Probe / 사고 후 도움 요청 prototype (verification stage, not the final product UI).
 *
 * Three concepts are kept apart:
 *  A. vehicle state observation   - what the supported APIs actually returned
 *  B. eligibility                 - gear P + speed 0 only means "stationary", never "accident"
 *  C. explicit user confirmation  - the assistance flow starts only after the user confirms
 *
 * Nothing here detects an accident, calls emergency services, sends data, or controls the vehicle.
 * Every SDK/AAOS read runs on a worker thread; the main thread only draws.
 * Logcat: SignalProbe (values), ProbeTiming (timing), ProbeCallback (raw SDK callbacks).
 */
class MainActivity : Activity() {

    private val logTag = "SignalProbe"
    private val main = Handler(Looper.getMainLooper())
    private val sdkThread = HandlerThread("Probe-SDK").apply { start() }
    private val aaosThread = HandlerThread("Probe-AAOS").apply { start() }
    private val sdkWorker = Handler(sdkThread.looper)
    private val aaosWorker = Handler(aaosThread.looper)

    @Volatile private var destroyed = false
    @Volatile private var visible = false
    @Volatile private var sdkReady = false
    private var background = true

    private val pollLock = Any()
    private var pollScheduled = false

    private var heartbeatExpected = 0L
    private val heartbeat = object : Runnable {
        override fun run() {
            if (destroyed) return
            val now = SystemClock.uptimeMillis()
            Log.i("ProbeTiming", "UI_HEARTBEAT delayMs=${now - heartbeatExpected}")
            heartbeatExpected = now + 1000
            if (visible) refreshState()
            main.postDelayed(this, 1000)
        }
    }
    private val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    // --- views -------------------------------------------------------------------------------
    private lateinit var stageAView: TextView
    private lateinit var stageBView: TextView
    private lateinit var assistButton: Button
    private lateinit var assistCard: TextView
    private lateinit var endAssistButton: Button
    private lateinit var statusView: TextView
    private lateinit var eventView: TextView

    // --- state -------------------------------------------------------------------------------
    private val latest = linkedMapOf<String, String>()
    private val events = ArrayDeque<String>()
    private val lastSdkValue = HashMap<String, String>()
    private val seatSdk = HashMap<String, String>() // "ROW_1_LEFT" -> "true"/"false"/failure text (main thread only)
    private var sdkProbe: VehicleSdkProbe? = null

    @Volatile private var gearRaw: Int? = null
    @Volatile private var speedMps: Float? = null
    @Volatile private var speedSeenAt = 0L
    private var assistActive = false
    private var lastGateSummary = ""

    private val sdkPoll = Runnable {
        synchronized(pollLock) { pollScheduled = false }
        if (!destroyed && visible) {
            measured("SDK.pollOnce") { sdkProbe?.pollOnce() }
            schedulePoll(1000)
        }
    }

    /** Polling runs only while the screen is visible; the platform freezes hidden apps anyway. */
    private fun schedulePoll(delayMs: Long) {
        synchronized(pollLock) {
            if (pollScheduled || destroyed || !visible || !sdkReady) return
            pollScheduled = true
        }
        (if (background) sdkWorker else main).postDelayed(sdkPoll, delayMs)
    }

    private val properties = listOf(
        VehiclePropertyIds.HAZARD_LIGHTS_STATE,
        VehiclePropertyIds.HAZARD_LIGHTS_SWITCH,
        VehiclePropertyIds.TURN_SIGNAL_STATE,
        VehiclePropertyIds.PERF_VEHICLE_SPEED,
        VehiclePropertyIds.GEAR_SELECTION,
        VehiclePropertyIds.CURRENT_GEAR,
        VehiclePropertyIds.PARKING_BRAKE_ON,
        VehiclePropertyIds.SEAT_OCCUPANCY,
        VehiclePropertyIds.DOOR_POS,
        VehiclePropertyIds.NIGHT_MODE,
        VehiclePropertyIds.HEADLIGHTS_STATE,
        VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE,
        VehiclePropertyIds.FUEL_LEVEL,
        VehiclePropertyIds.EV_BATTERY_LEVEL,
    )

    /**
     * Only permissions an ordinary app can actually be granted at runtime. CAR_EXTERIOR_LIGHTS is
     * signature|privileged (pm grant fails, 2026-11-11), so asking for it only produced a useless
     * permission dialog on every launch. The hazard property therefore stays unreadable.
     */
    private val runtimePermissions = arrayOf(
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_ENERGY",
    )

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            observeTyped(value.propertyId, value.value)
            val key = keyOf(value.propertyId, value.areaId)
            val text = describe(value.propertyId, value.value)
            main.post { record(key, text, isEvent = true) }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            val key = keyOf(propertyId, areaId)
            main.post { record(key, "오류 이벤트", isEvent = true) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        background = intent.getBooleanExtra("background", true)
        Log.i("ProbeTiming", "START pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()} background=$background")
        setContentView(buildLayout())
        heartbeatExpected = SystemClock.uptimeMillis() + 1000
        main.postDelayed(heartbeat, 1000)
        requestMissingPermissionsOrConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(logTag, "onNewIntent: reused existing instance (launchMode=singleTask)")
    }

    override fun onStart() {
        super.onStart()
        visible = true
        schedulePoll(0)
        refreshState()
    }

    override fun onStop() {
        visible = false
        sdkWorker.removeCallbacks(sdkPoll)
        main.removeCallbacks(sdkPoll)
        synchronized(pollLock) { pollScheduled = false }
        super.onStop()
    }

    private fun requestMissingPermissionsOrConnect() {
        val missing = runtimePermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            runtimePermissions.forEach { record("권한 " + it.substringAfterLast('.'), "허용(이미 부여됨)") }
            connectAndProbe()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        permissions.forEachIndexed { i, permission ->
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            record("권한 " + permission.substringAfterLast('.'), if (granted) "허용" else "거부")
        }
        connectAndProbe()
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(sdkPoll)
        sdkWorker.removeCallbacksAndMessages(null)
        aaosWorker.removeCallbacksAndMessages(null)
        sdkWorker.post {
            sdkProbe?.release()
            sdkThread.quitSafely()
        }
        aaosWorker.post {
            try { propertyManager?.unregisterCallback(callback) }
            catch (e: Exception) { Log.w(logTag, "unregister failed", e) }
            car?.disconnect()
            aaosThread.quitSafely()
        }
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            Log.i("ProbeTiming", "UI_TOUCH lagMs=${SystemClock.uptimeMillis() - event.eventTime}")
        }
        return super.dispatchTouchEvent(event)
    }

    // --- SDK / AAOS --------------------------------------------------------------------------

    private fun startSdkProbe() {
        if (sdkProbe != null) return
        val probe = measured("SDK.construct") { VehicleSdkProbe(this) { key, value ->
            Log.d("ProbeCallback", "$key = $value t=${SystemClock.elapsedRealtime()} thread=${Thread.currentThread().name}")
            main.post {
                if (destroyed) return@post
                if (key.startsWith(SEAT_KEY_PREFIX)) seatSdk[key.removePrefix(SEAT_KEY_PREFIX)] = value
                if (lastSdkValue[key] != value) {
                    lastSdkValue[key] = value
                    record(key, value, isEvent = true)
                }
            }
        } }
        sdkProbe = probe
        if (measured("SDK.initialize") { probe.initialize() }) {
            sdkReady = true
            schedulePoll(0)
        }
    }

    private fun connectAndProbe() {
        Log.i("ProbeTiming", "UI_READ_CLICK background=$background")
        if (background) {
            sdkWorker.post { if (!destroyed) startSdkProbe() }
            aaosWorker.post { if (!destroyed) measured("AAOS.connectAndProbe") { probeAaos() } }
        } else {
            startSdkProbe()
            measured("AAOS.connectAndProbe") { probeAaos() }
        }
    }

    private fun probeAaos() {
        if (car == null) {
            try {
                car = Car.createCar(this)
                propertyManager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            } catch (e: Exception) {
                record("Car 연결", "실패: ${e.javaClass.simpleName}: ${e.message}")
                return
            }
        }
        if (propertyManager == null) {
            record("Car 연결", "CarPropertyManager 없음")
            return
        }
        record("Car 연결", "성공")
        propertyManager?.unregisterCallback(callback)
        properties.forEach { id -> measured("AAOS.${VehiclePropertyIds.toString(id)}") { probe(id) } }
    }

    private fun probe(propertyId: Int) {
        val manager = propertyManager ?: return
        val name = VehiclePropertyIds.toString(propertyId)

        val config = try {
            manager.getCarPropertyConfig(propertyId)
        } catch (e: Exception) {
            record(name, "설정 조회 실패: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (config == null) {
            // Returned both when the property does not exist and when the app lacks its permission.
            record(name, "미지원 또는 권한 없음(설정 null)")
            return
        }

        val areas = if (config.isGlobalProperty || config.areaIds.isEmpty()) intArrayOf(0) else config.areaIds
        for (areaId in areas) {
            val key = keyOf(propertyId, areaId)
            try {
                val value = manager.getProperty<Any>(propertyId, areaId)
                observeTyped(propertyId, value.value)
                record(key, describe(propertyId, value.value) + " (status=${value.status})")
            } catch (e: SecurityException) {
                record(key, "권한 거부: ${e.message}")
            } catch (e: Exception) {
                record(key, "읽기 실패: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // changeMode 2 = CONTINUOUS (e.g. speed) needs a sampling rate; others are on-change.
        val rate = if (config.changeMode == 2) CarPropertyManager.SENSOR_RATE_UI else CarPropertyManager.SENSOR_RATE_ONCHANGE
        try {
            val ok = manager.registerCallback(callback, propertyId, rate)
            if (!ok) record("$name 구독", "실패(false)")
        } catch (e: Exception) {
            record("$name 구독", "실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Keeps the typed values the Stage B gate needs. Called from binder/worker threads. */
    private fun observeTyped(propertyId: Int, value: Any?) {
        when (propertyId) {
            VehiclePropertyIds.GEAR_SELECTION -> gearRaw = value as? Int
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                speedMps = value as? Float
                speedSeenAt = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun keyOf(propertyId: Int, areaId: Int): String {
        val name = VehiclePropertyIds.toString(propertyId)
        return if (areaId == 0) name else "$name@0x${Integer.toHexString(areaId)}"
    }

    /** Raw value plus a human hint. The seat hint is deliberately conservative (see SdkSeatAdapter). */
    private fun describe(propertyId: Int, value: Any?): String {
        val raw = when (value) {
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
            else -> value.toString()
        }
        val hint = when (propertyId) {
            VehiclePropertyIds.HAZARD_LIGHTS_STATE,
            VehiclePropertyIds.HAZARD_LIGHTS_SWITCH,
            VehiclePropertyIds.HEADLIGHTS_STATE -> when (value) {
                0 -> "꺼짐"; 1 -> "켜짐"; 2 -> "주간등"; else -> null
            }
            VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR -> when (value) {
                1 -> "N"; 2 -> "R"; 4 -> "P"; 8 -> "D"; else -> null
            }
            VehiclePropertyIds.TURN_SIGNAL_STATE -> when (value) {
                0 -> "없음"; 1 -> "오른쪽"; 2 -> "왼쪽"; 3 -> "양쪽"; else -> null
            }
            VehiclePropertyIds.SEAT_OCCUPANCY -> when (value) {
                0 -> "알 수 없음"; 1 -> "빈 좌석"; 2 -> "착석"; else -> null
            }
            VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                (value as? Float)?.let { String.format(Locale.KOREA, "%.1f km/h", it * 3.6f) }
            else -> null
        }
        return if (hint == null) raw else "$raw → $hint"
    }

    // --- recording / state ---------------------------------------------------------------------

    private fun record(key: String, text: String, isEvent: Boolean = false) {
        if (destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { record(key, text, isEvent) }
            return
        }
        if (isEvent && latest[key] == text) return
        latest[key] = text
        val line = "${time.format(Date())}  $key = $text"
        Log.i(logTag, line)
        if (isEvent) {
            events.addFirst(line)
            while (events.size > 40) events.removeLast()
            eventView.text = events.joinToString("\n")
        }
        statusView.text = latest.entries.joinToString("\n") { "${it.key}\n    ${it.value}" }
        refreshState()
    }

    private fun seatLine(label: String, area: String): String {
        val reported = seatSdk[area]
        val presence = SdkSeatAdapter.interpretReported(reported)
        val rawNote = if (reported == null) "미수신" else if (presence == SeatPresence.UNKNOWN) "조회 실패" else "SDK=$reported"
        return "  $label: ${presence.label}  [$rawNote]"
    }

    private fun refreshState() {
        if (destroyed || Looper.myLooper() != Looper.getMainLooper()) return
        val speedFresh = SystemClock.elapsedRealtime() - speedSeenAt < SPEED_FRESH_MS
        val gate = StationaryGate.evaluate(gearRaw, speedMps, speedFresh)

        stageAView.text = buildString {
            append("A. 차량 상태 관측 (지원 API가 실제로 돌려준 값)\n")
            append("  기어: ${latest["GEAR_SELECTION"] ?: "미수신"}  [AAOS GEAR_SELECTION · ${Category.VERIFIED.label}(에뮬레이터)]\n")
            append("  속도: ${latest["PERF_VEHICLE_SPEED"] ?: "미수신"}  [AAOS PERF_VEHICLE_SPEED · ${Category.OBSERVED_INCOMPLETE.label}]\n")
            append("  좌석 (SDK Boolean 해석: true=빈 좌석 보고, false=착석 가능성/불확실. ${Category.INFERRED.label})\n")
            append(seatLine("운전석", "ROW_1_LEFT")).append('\n')
            append(seatLine("조수석", "ROW_1_RIGHT")).append('\n')
            append(seatLine("뒷줄 왼쪽", "ROW_2_LEFT")).append('\n')
            append(seatLine("뒷줄 오른쪽", "ROW_2_RIGHT")).append('\n')
            append("  비상등: 검증된 공개 API로 확인 불가 / 플랫폼 지원 대기  [${Category.UNAVAILABLE.label}]\n")
            append("  에어백 전개 · 연료 %: 이 에뮬레이터에 속성 없음  [${Category.UNAVAILABLE.label}]\n")
            append("  TPMS 현재 압력: 공개 API 확인 안 됨  [${Category.UNAVAILABLE.label}]")
        }

        if (gate.summary != lastGateSummary) {
            lastGateSummary = gate.summary
            Log.i(logTag, "GATE eligible=${gate.eligible} (${gate.summary})")
        }
        stageBView.text = buildString {
            append("B. 사전 조건\n")
            append("  정차 상태: ${if (gate.eligible) "충족" else "미충족"} — ${gate.summary}\n")
            append("  ※ 기어 P·속도 0은 정차 상태일 뿐 사고 발생을 뜻하지 않습니다. 사고를 자동 판정하지 않습니다.")
        }
        assistButton.isEnabled = gate.eligible && !assistActive
        assistButton.text = if (gate.eligible) "사고 후 도움 요청 시작" else "사고 후 도움 요청 시작 (정차 상태 확인 필요)"

        if (assistActive) assistCard.text = assistText(gate)
    }

    private fun assistText(gate: Eligibility) = buildString {
        append("D. 도움 요청 안내 (프로토타입 · 사용자가 직접 시작함)\n")
        append("  · 앱이 사고를 판정한 것이 아닙니다. 사용자의 확인으로 시작된 흐름입니다.\n")
        append("  · 119 신고, 문자·위치 전송, 차량 제어는 수행하지 않습니다. 필요하면 직접 연락하세요.\n")
        append("  · 탑승자 상태는 직접 확인하세요. 아래 좌석 값은 인원 확인이나 구조 판단 근거가 아닙니다.\n")
        append("  현재 정차 상태: ${if (gate.eligible) "유지" else "해제됨"} (${gate.summary})\n")
        append(seatLine("운전석", "ROW_1_LEFT")).append('\n')
        append(seatLine("조수석", "ROW_1_RIGHT")).append('\n')
        append(seatLine("뒷줄 왼쪽", "ROW_2_LEFT")).append('\n')
        append(seatLine("뒷줄 오른쪽", "ROW_2_RIGHT"))
    }

    private fun confirmAssist() {
        AlertDialog.Builder(this)
            .setTitle("사고 후 도움 요청 시작")
            .setMessage(
                "시작하시겠습니까?\n" +
                    "· 사고를 자동 판정하지 않음\n" +
                    "· 신고·전송·차량 제어 없음"
            )
            .setPositiveButton("시작") { _, _ ->
                assistActive = true
                Log.i(logTag, "ASSIST_CONFIRMED by user at ${time.format(Date())} gate=$lastGateSummary")
                assistCard.visibility = View.VISIBLE
                endAssistButton.visibility = View.VISIBLE
                refreshState()
            }
            .setNegativeButton("취소") { _, _ -> Log.i(logTag, "ASSIST_CANCELLED by user") }
            .show()
    }

    private fun endAssist() {
        assistActive = false
        Log.i(logTag, "ASSIST_ENDED by user")
        assistCard.visibility = View.GONE
        endAssistButton.visibility = View.GONE
        refreshState()
    }

    // --- layout ----------------------------------------------------------------------------------

    private fun buildLayout(): ScrollView {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun heading(text: String) = TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        column.addView(TextView(this).apply {
            text = "Signal Probe — 사고 후 도움 요청 프로토타입 (검증 단계)"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            text = "차량 신호 접근 가능성을 확인하는 프로토타입입니다. 사고를 자동 판정하지 않고, 신고·전송·차량 제어를 하지 않습니다. " +
                "검증 SDK: ${SdkSeatAdapter.VALIDATED_SDK}"
            textSize = 15f
        })
        stageAView = TextView(this).apply { textSize = 16f; typeface = Typeface.MONOSPACE }
        column.addView(stageAView)
        stageBView = TextView(this).apply { textSize = 16f; typeface = Typeface.MONOSPACE }
        column.addView(stageBView)
        assistButton = Button(this).apply {
            textSize = 20f
            isEnabled = false
            text = "사고 후 도움 요청 시작 (정차 상태 확인 필요)"
            setOnClickListener { confirmAssist() }
        }
        column.addView(assistButton)
        assistCard = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }
        column.addView(assistCard)
        endAssistButton = Button(this).apply {
            textSize = 18f
            text = "도움 요청 종료"
            visibility = View.GONE
            setOnClickListener { endAssist() }
        }
        column.addView(endAssistButton)

        column.addView(heading("진단"))
        column.addView(Button(this).apply {
            text = "다시 읽기"
            textSize = 20f
            setOnClickListener { connectAndProbe() }
        })
        column.addView(TextView(this).apply {
            text = "DIAG 경고등 항목은 진단 전용입니다(PRIVATE_CAR_SAFETY: 공식 문서상 signature|privileged, 에뮬레이터 debug 빌드에서만 읽힘). 제품 기능이 아닙니다."
            textSize = 14f
        })
        column.addView(heading("변경 이벤트 (최신순)"))
        eventView = TextView(this).apply { textSize = 16f; typeface = Typeface.MONOSPACE }
        column.addView(eventView)
        column.addView(heading("현재 값"))
        statusView = TextView(this).apply { textSize = 16f; typeface = Typeface.MONOSPACE }
        column.addView(statusView)
        return ScrollView(this).apply {
            addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val SEAT_KEY_PREFIX = "SDK 좌석점유 "
        const val SPEED_FRESH_MS = 5000L
    }
}
