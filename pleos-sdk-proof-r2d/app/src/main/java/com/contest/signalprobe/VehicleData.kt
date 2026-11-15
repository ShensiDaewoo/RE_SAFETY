package com.contest.signalprobe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.location.fused.exception.LocationException
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

enum class SignalStatus { OK, UNAVAILABLE, PERMISSION_DENIED, ERROR }

/** D plan F6. ISO 3779 shape: 17 characters, digits and capital letters except I, O, Q. Anything else is not shown. */
internal fun isVin(s: String) = s.length == 17 && s.all { it in '0'..'9' || it in 'A'..'Z' && it != 'I' && it != 'O' && it != 'Q' }

/** D plan F2: one vehicle position sample. `sourceWall` is the SDK's timestamp, `receivedElapsed` our receipt time. */
data class VehicleFix(val lat: Double, val lon: Double, val sourceWall: Long, val receivedElapsed: Long, val confidence: Float) {
    val text: String get() = String.format(java.util.Locale.ROOT, "%.5f, %.5f", lat, lon)
    /** Seconds since we received it (local monotonic clock; the head-unit wall clock may be off). */
    fun ageSec(nowElapsed: Long = SystemClock.elapsedRealtime()) = ((nowElapsed - receivedElapsed) / 1000L).coerceAtLeast(0L)
    /** Prototype policy (not a Pleos guarantee): older than 30 s is not offered for sharing. */
    fun fresh(nowElapsed: Long = SystemClock.elapsedRealtime()) = nowElapsed - receivedElapsed in 0..MAX_AGE_MS
    companion object { const val MAX_AGE_MS = 30_000L }
}

/**
 * One vehicle observation with provenance. `at` is elapsedRealtime of the last successful or failed read
 * (freshness); `changedWall` is the wall-clock time the value last changed (display).
 */
data class Reading(
    val key: String,
    val source: String,
    val raw: String?,
    val status: SignalStatus,
    val category: Category,
    val at: Long,
    val changedWall: Long,
    val detail: String? = null,
    val receivedWall: Long? = null,
)

/**
 * Release-safe vehicle data layer. Only reads what the product screens need, and only through
 * publicly documented, normal-protection permissions (CAR_SEATS, CAR_DOORS, CAR_WINDOWS, CAR_LIGHTS, CAR_ENERGY,
 * AAOS CAR_SPEED / CAR_POWERTRAIN). Everything runs on worker threads; callers get change callbacks.
 * Errors are stored as status, never converted into default values.
 */
class VehicleHub(context: Context, private val onChange: () -> Unit) {
    private val app = context.applicationContext
    private val tag = "Accident10Hub"
    private val readings = ConcurrentHashMap<String, Reading>()
    private val doorSeen = ConcurrentHashMap<String, Long>()
    @Volatile private var running = false
    @Volatile private var released = false

    private val sdkThread = HandlerThread("Hub-SDK").apply { start() }
    private val aaosThread = HandlerThread("Hub-AAOS").apply { start() }
    private val sdkHandler = Handler(sdkThread.looper)
    private val aaosHandler = Handler(aaosThread.looper)

    private var vehicle: Vehicle? = null
    private var sdkReady = false
    private var car: Car? = null
    private var manager: CarPropertyManager? = null

    fun reading(key: String): Reading? = readings[key]
    fun doorOpenedSeen(doorKey: String): Boolean = doorSeen.containsKey(doorKey)
    fun resetDoorHistory() = doorSeen.clear()
    fun isFresh(r: Reading?, maxAgeMs: Long = FRESH_MS) =
        r != null && SystemClock.elapsedRealtime() - r.at <= maxAgeMs

    /** D lab, on demand only. No writes, no poll changes, and no storage. Callback may run off-main. */
    internal fun requestBeltReports(result: (String, DBeltReport) -> Unit) {
        fun all(report: DBeltReport) = SEATS.forEach { (key, _) -> result(key, report) }
        fun classify(e: Exception) =
            if (e is SecurityException || e.message?.contains("permission", ignoreCase = true) == true) DBeltReport.DENIED
            else DBeltReport.ERROR
        sdkHandler.post {
            if (!running || released) return@post
            val v = vehicle
            if (v == null || !sdkReady) {
                all(DBeltReport.NO_VALUE)
                return@post
            }
            try {
                val seat = v.getSeat()
                seat.checkSeatBeltBuckledCapabilities({ supported ->
                    sdkHandler.post update@{
                        if (!running || released) return@update
                        for ((key, value) in SEATS) {
                            if (supported == null) {
                                result(key, DBeltReport.NO_VALUE)
                            } else if (supported.none { value in it.values }) {
                                result(key, DBeltReport.UNSUPPORTED)
                            } else {
                                try {
                                    seat.getCurrentSeatBeltBuckledState(SeatArea(value), { buckled ->
                                        result(key, when (buckled) {
                                            true -> DBeltReport.BUCKLED
                                            false -> DBeltReport.UNBUCKLED
                                            null -> DBeltReport.NO_VALUE
                                        })
                                    }, { result(key, classify(it)) })
                                } catch (e: Exception) {
                                    result(key, classify(e))
                                }
                            }
                        }
                    }
                }, { all(classify(it)) })
            } catch (e: Exception) {
                all(classify(e))
            }
        }
    }

    /** D plan F1: run one SDK call on the SDK thread. `null` = SDK not ready or hub released. */
    internal fun withVehicle(work: (Vehicle?) -> Unit) {
        sdkHandler.post { work(if (!released && sdkReady) vehicle else null) }
    }
    internal fun withVehicleDelayed(delayMs: Long, work: () -> Unit) { sdkHandler.postDelayed({ if (!released) work() }, delayMs) }

    // --- D plan F2: vehicle position (Pleos FusedLocation) -------------------------------------
    /** Latest fix from the vehicle; null until one arrives. Never synthesised, never cached across restarts. */
    @Volatile var fix: VehicleFix? = null
        private set
    @Volatile var locationStatus: String = "확인 전"
        private set
    private var fused: FusedLocation? = null
    private var fusedRetryAt = 0L

    private fun pollLocation() {
        val now = SystemClock.elapsedRealtime()
        if (app.checkSelfPermission(PERM_FUSED) != PackageManager.PERMISSION_GRANTED) {
            setLocationStatus("위치 권한 없음"); return
        }
        var f = fused
        if (f == null) {
            if (now < fusedRetryAt) return
            fusedRetryAt = now + 10_000L
            try {
                val created = FusedLocation(app)
                created.initialize()
                if (!created.isSupported()) {
                    try { created.release() } catch (_: Exception) {}
                    setLocationStatus("이 차량은 위치 기능 미지원"); return
                }
                fused = created; f = created
                Log.i(tag, "FUSED initialized supported=true")
            } catch (e: Exception) {
                setLocationStatus("위치 서비스 연결 실패"); Log.i(tag, "FUSED init failed ${e.javaClass.simpleName}: ${e.message}"); return
            }
        }
        try {
            f.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(data: LocationData?) {
                    if (data == null) { setLocationStatus("아직 위치 없음"); return }
                    val lat = data.latitude; val lon = data.longitude
                    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) {
                        setLocationStatus("위치 값 이상 · 사용 안 함"); return
                    }
                    val old = fix
                    fix = VehicleFix(lat, lon, data.timestampMillis, SystemClock.elapsedRealtime(), data.confidence)
                    if (old == null || old.lat != lat || old.lon != lon) {
                        Log.i(tag, "OBS LOCATION ${fix?.text} ts=${data.timestampMillis} conf=${data.confidence}")
                        onChange()
                    }
                    setLocationStatus("차량 위치 수신")
                }
                override fun onError(e: Exception) {
                    val code = (e as? LocationException)?.errorCode?.name
                    setLocationStatus(when (code) {
                        "ERROR_SECURITY_PERMISSION" -> "위치 권한 없음"
                        "ERROR_LOCATION_DISABLED" -> "차량 위치 꺼짐"
                        "ERROR_EMPTY_RESULT" -> "아직 위치 없음"
                        "ERROR_DRIVING_RESTRICTED" -> "주행 중 제한"
                        else -> "위치 읽기 실패"
                    })
                }
            })
        } catch (e: Exception) {
            setLocationStatus("위치 읽기 실패"); Log.i(tag, "FUSED read failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // --- D plan F6: pre-departure handover snapshot (on demand, user tap only) -------------------
    /** Raw SDK values; null = not reported. Units are what the SDK returns (checked against the console). */
    data class Handover(val vin: String?, val odometer: Float?, val range: Float?)

    internal fun requestHandover(result: (Handover) -> Unit) {
        sdkHandler.post {
            val v = vehicle
            if (released || v == null || !sdkReady) { result(Handover(null, null, null)); return@post }
            var vin: String? = null; var odo: Float? = null; var range: Float? = null
            var pending = 3
            var delivered = false
            fun done() { if (!delivered && --pending == 0) { delivered = true; result(Handover(vin, odo, range)) } }
            fun guard(name: String, work: () -> Unit) = try { work() } catch (e: Exception) {
                Log.i(tag, "HANDOVER $name failed ${e.javaClass.simpleName}: ${e.message}"); sdkHandler.post { done() }
            }
            guard("vin") { v.getCarInfo().getVINInfo({ s -> sdkHandler.post { vin = s?.trim()?.takeIf { it.isNotEmpty() }; done() } },
                { e -> Log.i(tag, "HANDOVER vin ${e.message}"); sdkHandler.post { done() } }) }
            guard("odometer") { v.getOdometer().getCurrentDistanceDriven({ d -> sdkHandler.post { odo = d?.takeIf { it.isFinite() }; done() } },
                { e -> Log.i(tag, "HANDOVER odometer ${e.message}"); sdkHandler.post { done() } }) }
            guard("range") { v.getOdometer().getRangeRemainingDistance({ d -> sdkHandler.post { range = d?.takeIf { it.isFinite() }; done() } },
                { e -> Log.i(tag, "HANDOVER range ${e.message}"); sdkHandler.post { done() } }) }
            // Never wait forever: whatever arrived in 2.5 s is used, the rest stays "미확인".
            sdkHandler.postDelayed({ if (!delivered) { delivered = true; result(Handover(vin, odo, range)) } }, 2_500L)
        }
    }

    // --- D plan F3: speakable place name from the head-unit navigation (Pleos NaviHelper) ---------
    /** Address text from navigation for the current position, with its receipt time. Never typed by the app. */
    @Volatile var naviAddress: String? = null
        private set
    @Volatile var naviAddressAt: Long = 0L
        private set
    @Volatile var naviStatus: String = "확인 전"
        private set
    @Volatile private var naviLat = Double.NaN
    @Volatile private var naviLon = Double.NaN
    private var navi: NaviHelper? = null
    private var naviRetryAt = 0L
    private var naviAskedAt = 0L
    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            val address = info.address?.trim()?.takeIf { it.isNotEmpty() }
            if (address == null) { naviStatus = "주소 없음"; return }
            val changed = address != naviAddress
            naviAddress = address; naviLat = info.latitude; naviLon = info.longitude
            naviAddressAt = SystemClock.elapsedRealtime(); naviStatus = "내비 주소 수신"
            if (changed) { Log.i(tag, "OBS NAVI_ADDRESS $address lat=${info.latitude} lon=${info.longitude}"); onChange() }
        }
        override fun onError(code: NaviErrorCode) { naviStatus = "내비 오류 $code"; Log.i(tag, "NAVI error $code") }
    }

    private fun pollNavi() {
        val now = SystemClock.elapsedRealtime()
        var n = navi
        if (n == null) {
            if (now < naviRetryAt) return
            naviRetryAt = now + 15_000L
            try {
                val created = NaviHelper(app)
                created.initialize({ Log.i(tag, "NAVI initialized") }, { e -> naviStatus = "내비 연결 실패"; Log.i(tag, "NAVI init failed ${e.javaClass.simpleName}: ${e.message}") })
                created.addListener(naviListener)
                navi = created; n = created
            } catch (e: Exception) {
                naviStatus = "내비 연결 실패"; Log.i(tag, "NAVI create failed ${e.javaClass.simpleName}: ${e.message}"); return
            }
        }
        if (now - naviAskedAt < 5_000L) return
        naviAskedAt = now
        try { n.getCurrentLocationInfo() } catch (e: Exception) {
            naviStatus = "내비 주소 읽기 실패"; Log.i(tag, "NAVI read failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Address is offered only while fresh (same 30 s policy as coordinates). */
    fun freshNaviAddress(nowElapsed: Long = SystemClock.elapsedRealtime()): String? {
        val address = naviAddress?.takeIf { nowElapsed - naviAddressAt in 0..VehicleFix.MAX_AGE_MS } ?: return null
        // Two sources must agree: navigation position within 300 m of the vehicle fix, else no address.
        val car = fix?.takeIf { it.fresh(nowElapsed) } ?: return null
        return address.takeIf { Geo.agree(car.lat, car.lon, naviLat, naviLon) }
    }

    /** Navigation and vehicle positions differ by more than 300 m: the address is withheld. */
    fun naviDisagrees(): Boolean { val car = fix ?: return false; return naviAddress != null && !Geo.agree(car.lat, car.lon, naviLat, naviLon) }

    object Geo {
        const val AGREE_M = 300.0
        fun agree(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
            if (listOf(lat1, lon1, lat2, lon2).any { !it.isFinite() }) return false
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
            return 2 * r * Math.asin(Math.sqrt(a)) <= AGREE_M
        }
    }

    private fun setLocationStatus(s: String) {
        if (s != locationStatus) { locationStatus = s; Log.i(tag, "LOCATION_STATUS $s"); onChange() }
    }

    fun start() {
        if (released) return
        running = true
        sdkHandler.removeCallbacks(sdkLoop); sdkHandler.post(sdkLoop)
        aaosHandler.removeCallbacks(aaosLoop); aaosHandler.post(aaosLoop)
    }

    fun stop() {
        running = false
        sdkHandler.removeCallbacks(sdkLoop)
        aaosHandler.removeCallbacks(aaosLoop)
    }

    fun release() {
        released = true; running = false
        sdkHandler.removeCallbacksAndMessages(null); aaosHandler.removeCallbacksAndMessages(null)
        sdkHandler.post {
            try { vehicle?.release() } catch (_: Exception) {}
            try { fused?.release() } catch (_: Exception) {}
            try { navi?.removeListener(naviListener); navi?.release() } catch (_: Exception) {}
            sdkThread.quitSafely()
        }
        aaosHandler.post { try { car?.disconnect() } catch (_: Exception) {}; aaosThread.quitSafely() }
    }

    // --- recording ----------------------------------------------------------------------------

    private fun put(key: String, source: String, raw: String?, status: SignalStatus, cat: Category, detail: String? = null) {
        val now = SystemClock.elapsedRealtime()
        val old = readings[key]
        val changed = old == null || old.raw != raw || old.status != status
        val received = System.currentTimeMillis()
        readings[key] = Reading(key, source, raw, status, cat, now, if (changed) received else old!!.changedWall, detail, received)
        if (changed) {
            Log.i(tag, "OBS $key raw=$raw status=$status src=$source ${detail ?: ""}")
            onChange()
        }
    }

    private fun fail(key: String, source: String, cat: Category, e: Exception) {
        val m = e.message ?: ""
        val status = when {
            e is SecurityException || m.contains("Permission", true) -> SignalStatus.PERMISSION_DENIED
            m.contains("not supported", true) || m.contains("not in carPropertyConfig", true) -> SignalStatus.UNAVAILABLE
            else -> SignalStatus.ERROR
        }
        put(key, source, null, status, cat, "${e.javaClass.simpleName}: ${m.take(80)}")
    }

    // --- Pleos Vehicle SDK (seats, doors, wiper, low beam) ----------------------------------------

    private val sdkLoop = object : Runnable {
        override fun run() {
            if (!running || released) return
            if (!sdkReady) initSdk()
            if (sdkReady) pollSdk()
            pollLocation()
            pollNavi()
            sdkHandler.postDelayed(this, POLL_MS)
        }
    }

    private fun initSdk() {
        try {
            val v = Vehicle(app)
            v.initialize()
            vehicle = v; sdkReady = true
            Log.i(tag, "SDK initialized")
        } catch (e: Exception) {
            fail("SDK", "Vehicle.initialize", Category.UNAVAILABLE, e)
        }
    }

    private fun pollSdk() {
        val v = vehicle ?: return
        try {
            val seat = v.getSeat()
            for ((key, value) in SEATS) {
                val src = "Pleos Seat.getCurrentSeatOccupancy"
                seat.getCurrentSeatOccupancy(SeatArea(value),
                    { b -> put(key, src, b?.toString(), if (b == null) SignalStatus.UNAVAILABLE else SignalStatus.OK, Category.VERIFIED) },
                    { e -> fail(key, src, Category.VERIFIED, e) })
            }
            val door = v.getDoor()
            for ((key, value) in DOORS) {
                val src = "Pleos Door.getCurrentDoorPosition"
                door.getCurrentDoorPosition(DoorArea(value),
                    { p ->
                        if (p != null && p > 0) doorSeen[key] = System.currentTimeMillis()
                        put(key, src, p?.toString(), if (p == null) SignalStatus.UNAVAILABLE else SignalStatus.OK, Category.VERIFIED)
                    },
                    { e -> fail(key, src, Category.VERIFIED, e) })
            }
            v.getWiper().getCurrentWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD),
                { s -> put("WIPER_FRONT", "Pleos Wiper.getCurrentWiperState", s?.toString(), if (s == null) SignalStatus.UNAVAILABLE else SignalStatus.OK, Category.VERIFIED) },
                { e -> fail("WIPER_FRONT", "Pleos Wiper.getCurrentWiperState", Category.VERIFIED, e) })
            v.getLight().getCurrentLowBeamLightState(
                { s -> put("LOWBEAM", "Pleos Light.getCurrentLowBeamLightState", s?.toString(), if (s == null) SignalStatus.UNAVAILABLE else SignalStatus.OK, Category.OBSERVED_INCOMPLETE) },
                { e -> fail("LOWBEAM", "Pleos Light.getCurrentLowBeamLightState", Category.OBSERVED_INCOMPLETE, e) })
            // RC4: energy level (CAR_ENERGY, normal). Read-only; never interpreted as a cause of stopping.
            v.getEvBattery().getEvBatteryStateOfCharge(
                { p -> put("EV_SOC", "Pleos EvBattery.getEvBatteryStateOfCharge", p?.toString(), if (p == null) SignalStatus.UNAVAILABLE else SignalStatus.OK, Category.OBSERVED_INCOMPLETE) },
                { e -> fail("EV_SOC", "Pleos EvBattery.getEvBatteryStateOfCharge", Category.OBSERVED_INCOMPLETE, e) })
            v.getFuel().getFuelPercent(
                { p -> put("FUEL_PCT", "Pleos Fuel.getFuelPercent", p?.toString(), if (p == null) SignalStatus.UNAVAILABLE else SignalStatus.OK, Category.OBSERVED_INCOMPLETE) },
                { e -> fail("FUEL_PCT", "Pleos Fuel.getFuelPercent", Category.OBSERVED_INCOMPLETE, e) })
        } catch (e: Exception) {
            fail("SDK", "pollSdk", Category.UNAVAILABLE, e)
        }
    }

    // --- AAOS CarPropertyManager (gear, speed, parking brake) -------------------------------------

    private val aaosLoop = object : Runnable {
        override fun run() {
            if (!running || released) return
            if (manager == null) connectCar()
            manager?.let { readAaos(it) }
            aaosHandler.postDelayed(this, POLL_MS)
        }
    }

    private fun connectCar() {
        try {
            car = Car.createCar(app)
            manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (manager == null) put("CAR", "Car.createCar", null, SignalStatus.UNAVAILABLE, Category.UNAVAILABLE, "no CarPropertyManager")
        } catch (e: Exception) {
            fail("CAR", "Car.createCar", Category.UNAVAILABLE, e)
        }
    }

    private fun readAaos(m: CarPropertyManager) {
        readProp(m, "GEAR", "AAOS GEAR_SELECTION", VehiclePropertyIds.GEAR_SELECTION, Category.VERIFIED)
        readProp(m, "SPEED", "AAOS PERF_VEHICLE_SPEED", VehiclePropertyIds.PERF_VEHICLE_SPEED, Category.OBSERVED_INCOMPLETE)
        readProp(m, "PARKING_BRAKE", "AAOS PARKING_BRAKE_ON", VehiclePropertyIds.PARKING_BRAKE_ON, Category.OBSERVED_INCOMPLETE)
    }

    private fun readProp(m: CarPropertyManager, key: String, src: String, id: Int, cat: Category) {
        try {
            val v = m.getProperty<Any>(id, 0).value
            put(key, src, v?.toString(), if (v == null) SignalStatus.UNAVAILABLE else SignalStatus.OK, cat)
        } catch (e: Exception) {
            fail(key, src, cat, e)
        }
    }

    companion object {
        const val PERM_FUSED = "pleos.car.permission.FUSED_LOCATION"
        const val POLL_MS = 1000L
        const val FRESH_MS = 5000L
        val SEATS = listOf(
            "SEAT_ROW_1_LEFT" to SeatValue.ROW_1_LEFT, "SEAT_ROW_1_RIGHT" to SeatValue.ROW_1_RIGHT,
            "SEAT_ROW_2_LEFT" to SeatValue.ROW_2_LEFT, "SEAT_ROW_2_RIGHT" to SeatValue.ROW_2_RIGHT,
        )
        val DOORS = listOf(
            "DOOR_ROW_1_LEFT" to DoorValue.ROW_1_LEFT, "DOOR_ROW_1_RIGHT" to DoorValue.ROW_1_RIGHT,
            "DOOR_ROW_2_LEFT" to DoorValue.ROW_2_LEFT, "DOOR_ROW_2_RIGHT" to DoorValue.ROW_2_RIGHT,
            "DOOR_REAR" to DoorValue.REAR,
        )
    }
}

/** A-3. Four-state mobility model. One evaluator, one place. Unknown is never treated as zero speed. */
enum class Motion { MOVING, STOPPED_PARKED, STOPPED_NOT_PARKED, UNKNOWN }

data class MobilityState(
    val motion: Motion,
    val speedKmh: Float?,
    val gear: Int?,
    val parkingBrake: Boolean?,
    val note: String,
    val conflict: Boolean = false,
)

object MobilityEvaluator {
    const val MOVING_KMH = 5f // _facts.md driving-restriction release threshold (<= 5 km/h); verify on real vehicle
    private const val GEAR_PARK = 4

    fun evaluate(hub: VehicleHub): MobilityState {
        val s = hub.reading("SPEED"); val g = hub.reading("GEAR"); val b = hub.reading("PARKING_BRAKE")
        val speed = if (hub.isFresh(s) && s?.status == SignalStatus.OK) s.raw?.toFloatOrNull()?.times(3.6f) else null
        val gear = if (hub.isFresh(g) && g?.status == SignalStatus.OK) g.raw?.toIntOrNull() else null
        val brake = if (hub.isFresh(b) && b?.status == SignalStatus.OK) b.raw?.toBooleanStrictOrNull() else null
        return decide(speed, gear, brake)
    }

    /** Pure decision function (testable without a vehicle). */
    fun decide(speedKmh: Float?, gear: Int?, brake: Boolean?): MobilityState {
        if (speedKmh == null) return MobilityState(Motion.UNKNOWN, null, gear, brake, "속도 신호 없음 또는 오래됨")
        val parkedSignal = gear == GEAR_PARK || brake == true
        if (speedKmh > MOVING_KMH) {
            val conflict = parkedSignal
            return MobilityState(Motion.MOVING, speedKmh, gear, brake,
                if (conflict) "속도 감지됨 · 기어 P/주차브레이크 신호와 충돌 — 이동 중으로 처리" else "이동 중", conflict)
        }
        if (parkedSignal) return MobilityState(Motion.STOPPED_PARKED, speedKmh, gear, brake, if (gear == GEAR_PARK) "정차 · 기어 P" else "정차 · 주차브레이크")
        if (gear != null) return MobilityState(Motion.STOPPED_NOT_PARKED, speedKmh, gear, brake, "정차 · 기어 P 아님")
        return MobilityState(Motion.UNKNOWN, speedKmh, gear, brake, "기어 신호 없음 또는 오래됨")
    }
}
