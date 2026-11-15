package com.contest.resafety.fullhmi

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * aurora13: the vehicle side of protect mode, taken from the R2D submission app (VehicleHub, DVehicleProtectionPort).
 * Created only on an automotive head unit (MainActivity checks the system feature); on an ordinary Android device the page keeps
 * the simulation of aurora12. Reads gear and speed (AAOS CarPropertyManager, the same source as R2D) once a second for the park gate.
 * Nothing runs without a user tap; windows, trunk, hood and anything automatic are absent.
 */
class VehicleLink(context: Context, private val onChange: () -> Unit) {
    private val app = context.applicationContext
    private val tag = "FullHMIVeh"
    private val readings = ConcurrentHashMap<String, Reading>()
    @Volatile private var released = false
    private val sdkThread = HandlerThread("Veh-SDK").apply { start() }
    private val carThread = HandlerThread("Veh-AAOS").apply { start() }
    private val sdkHandler = Handler(sdkThread.looper)
    private val carHandler = Handler(carThread.looper)
    private var vehicle: Vehicle? = null
    @Volatile var sdkReady = false
        private set
    @Volatile var sdkError: String? = null
        private set
    private var car: Any? = null                        // android.car.Car, only touched on the AAOS thread inside try/catch(Throwable)
    private var manager: Any? = null

    fun reading(key: String): Reading? = readings[key]

    fun start() {
        sdkHandler.post(sdkInit)
        carHandler.post(carLoop)
    }

    fun release() {
        released = true
        sdkHandler.removeCallbacksAndMessages(null); carHandler.removeCallbacksAndMessages(null)
        sdkHandler.post {
            try { vehicle?.release() } catch (_: Throwable) {}
            try { fused?.release() } catch (_: Throwable) {}
            try { navi?.removeListener(naviListener); navi?.release() } catch (_: Throwable) {}
            sdkThread.quitSafely()
        }
        carHandler.post { try { (car as? android.car.Car)?.disconnect() } catch (_: Throwable) {}; carThread.quitSafely() }
    }

    internal fun withVehicle(work: (Vehicle?) -> Unit) { sdkHandler.post { work(if (!released && sdkReady) vehicle else null) } }
    internal fun withVehicleDelayed(delayMs: Long, work: () -> Unit) { sdkHandler.postDelayed({ if (!released) work() }, delayMs) }

    private val sdkInit = object : Runnable {
        override fun run() {
            if (released || sdkReady) return
            try {
                val v = Vehicle(app); v.initialize()
                vehicle = v; sdkReady = true; sdkError = null
                Log.i(tag, "SDK initialized")
                sdkHandler.post(sdkLoop)
            } catch (e: Throwable) {
                sdkError = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}"
                Log.i(tag, "SDK init failed $sdkError")
                sdkHandler.postDelayed(this, 3_000L)
            }
            onChange()
        }
    }

    // ---- aurora14: reads carried over from the R2D submission app's VehicleHub (seats, doors, vehicle position, navigation address) ----

    /** Seat key of the page (D, P, RL, RR) -> what the vehicle reported. occ: "y" / "n" / "" (no value). belt: "y" / "n" / "x" (not supported) / "" (no value). */
    val seatOcc = ConcurrentHashMap<String, String>()
    val seatBelt = ConcurrentHashMap<String, String>()
    /** Door key (FL, FR, RL, RR, TG) -> "open" / "closed". Missing = no value. */
    val doors = ConcurrentHashMap<String, String>()
    @Volatile var beltAskedWall = 0L
        private set

    private val sdkLoop = object : Runnable {
        override fun run() {
            if (released) return
            pollSeatsAndDoors(); pollLocation(); pollNavi()
            sdkHandler.postDelayed(this, 1_000L)
        }
    }

    private fun note(map: ConcurrentHashMap<String, String>, kind: String, key: String, value: String) {
        if (map.put(key, value) != value) { Log.i(tag, "OBS $kind $key=$value"); onChange() }
    }

    private fun pollSeatsAndDoors() {
        val v = vehicle ?: return
        try {
            val seat = v.getSeat()
            for ((key, value) in SEATS) seat.getCurrentSeatOccupancy(ai.pleos.playground.vehicle.api.model.area.SeatArea(value),
                { b -> note(seatOcc, "SEAT_OCC", key, when (b) { true -> "n"; false -> "y"; else -> "" }) /* adaptive8: SDK true = VACANT on this image, as R2D SdkSeatAdapter */ }, { note(seatOcc, "SEAT_OCC", key, "") })
            val door = v.getDoor()
            for ((key, value) in DOOR_KEYS) door.getCurrentDoorPosition(DoorArea(value),
                { p -> if (p != null) note(doors, "DOOR", key, if (p > 0) "open" else "closed") }, { doors.remove(key) })
            // adaptive6: the rest of the R2D reads (VehicleHub.pollSdk): front wiper, low beam, battery, fuel. Read only.
            val miss: (String) -> Unit = { k -> extra.remove(k) }
            v.getWiper().getCurrentWiperState(ai.pleos.playground.vehicle.api.model.area.WindowArea(ai.pleos.playground.vehicle.constant.area.WindowValue.FRONT_WINDSHIELD),
                { s -> if (s != null) note(extra, "WIPER", "WIPER", s.toString()) else miss("WIPER") }, { miss("WIPER") })
            v.getLight().getCurrentLowBeamLightState({ s -> if (s != null) note(extra, "LOWBEAM", "LOWBEAM", s.toString()) else miss("LOWBEAM") }, { miss("LOWBEAM") })
            v.getEvBattery().getEvBatteryStateOfCharge({ p -> if (p != null && p.toFloat().isFinite()) note(extra, "EV_SOC", "EV_SOC", Math.round(p.toFloat()).toString()) else miss("EV_SOC") }, { miss("EV_SOC") })
            v.getFuel().getFuelPercent({ p -> if (p != null && p.toFloat().isFinite()) note(extra, "FUEL_PCT", "FUEL_PCT", Math.round(p.toFloat()).toString()) else miss("FUEL_PCT") }, { miss("FUEL_PCT") })
        } catch (e: Exception) {
            Log.i(tag, "pollSeatsAndDoors ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
        }
    }

    /** adaptive6: WIPER ("ON"/"OFF"…), LOWBEAM, EV_SOC (%), FUEL_PCT (%) as the vehicle reports them. Missing = no value. */
    val extra = ConcurrentHashMap<String, String>()

    /** adaptive6, R2D requestHandover: on a user tap only. VIN (only the last 6 characters leave this class), odometer (km), range (m). */
    data class Handover(val vin6: String?, val odometerKm: Int?, val rangeKm: Int?, val atWall: Long)
    @Volatile var handover: Handover? = null
        private set

    fun requestHandover() {
        sdkHandler.post {
            val v = vehicle
            if (released || v == null || !sdkReady) return@post
            var vin: String? = null; var odo: Float? = null; var range: Float? = null
            var pending = 3; var delivered = false
            fun finish() {
                if (delivered) return; delivered = true
                val v6 = vin?.trim()?.takeIf { it.length == 17 && it.all { c -> c in '0'..'9' || c in 'A'..'Z' && c != 'I' && c != 'O' && c != 'Q' } }?.takeLast(6)
                handover = Handover(v6, odo?.takeIf { it.isFinite() }?.toInt(), range?.takeIf { it.isFinite() }?.let { (it / 1000f).toInt() }, System.currentTimeMillis())
                Log.i(tag, "HANDOVER vin6=${v6 != null} odometer=${handover?.odometerKm} rangeKm=${handover?.rangeKm}")     // the VIN itself is not written to the log
                onChange()
            }
            fun done() = sdkHandler.post { if (--pending == 0) finish() }
            fun guard(name: String, work: () -> Unit) = try { work() } catch (e: Exception) { Log.i(tag, "HANDOVER $name ${e.javaClass.simpleName}"); done() }
            guard("vin") { v.getCarInfo().getVINInfo({ s -> vin = s; done() }, { done() }) }
            guard("odometer") { v.getOdometer().getCurrentDistanceDriven({ d -> odo = d; done() }, { done() }) }
            guard("range") { v.getOdometer().getRangeRemainingDistance({ d -> range = d; done() }, { done() }) }
            sdkHandler.postDelayed({ finish() }, 2_500L)                    // never wait forever: whatever arrived in 2.5 s is used (R2D rule)
        }
    }

    /** On a user tap only (R2D requestBeltReports): what each seat-belt buckle reports. No writes. */
    fun requestBeltReports() {
        sdkHandler.post {
            val v = vehicle
            if (released || v == null || !sdkReady) return@post
            beltAskedWall = System.currentTimeMillis()
            fun all(x: String) = SEATS.forEach { (k, _) -> note(seatBelt, "SEAT_BELT", k, x) }
            try {
                val seat = v.getSeat()
                seat.checkSeatBeltBuckledCapabilities({ supported ->
                    sdkHandler.post {
                        for ((key, value) in SEATS) {
                            if (supported == null) note(seatBelt, "SEAT_BELT", key, "")
                            else if (supported.none { value in it.values }) note(seatBelt, "SEAT_BELT", key, "x")
                            else try {
                                seat.getCurrentSeatBeltBuckledState(ai.pleos.playground.vehicle.api.model.area.SeatArea(value),
                                    { b -> note(seatBelt, "SEAT_BELT", key, when (b) { true -> "y"; false -> "n"; else -> "" }) }, { note(seatBelt, "SEAT_BELT", key, "") })
                            } catch (e: Exception) { note(seatBelt, "SEAT_BELT", key, "") }
                        }
                        onChange()
                    }
                }, { all(""); onChange() })
            } catch (e: Exception) { all(""); onChange() }
        }
    }

    // --- vehicle position (Pleos FusedLocation). Never synthesised, never cached across restarts; older than 30 s is not offered.
    data class Fix(val lat: Double, val lon: Double, val receivedElapsed: Long) {
        fun fresh(now: Long = SystemClock.elapsedRealtime()) = now - receivedElapsed in 0..MAX_AGE_MS
    }
    @Volatile var fix: Fix? = null
        private set
    @Volatile var locationStatus = "확인 전"
        private set
    private var fused: ai.pleos.playground.location.fused.FusedLocation? = null
    private var fusedRetryAt = 0L

    private fun setLocationStatus(s: String) { if (s != locationStatus) { locationStatus = s; Log.i(tag, "LOCATION_STATUS $s"); onChange() } }

    private fun pollLocation() {
        val now = SystemClock.elapsedRealtime()
        if (app.checkSelfPermission(PERM_FUSED) != android.content.pm.PackageManager.PERMISSION_GRANTED) { setLocationStatus("위치 권한 없음"); return }
        var f = fused
        if (f == null) {
            if (now < fusedRetryAt) return
            fusedRetryAt = now + 10_000L
            try {
                val created = ai.pleos.playground.location.fused.FusedLocation(app)
                created.initialize()
                if (!created.isSupported()) { try { created.release() } catch (_: Exception) {}; setLocationStatus("이 차량은 위치 기능 미지원"); return }
                fused = created; f = created
                Log.i(tag, "FUSED initialized supported=true")
            } catch (e: Throwable) {
                setLocationStatus("위치 서비스 연결 실패"); Log.i(tag, "FUSED init failed ${e.javaClass.simpleName}: ${e.message}"); return
            }
        }
        try {
            f.getLastKnownLocation(object : ai.pleos.playground.location.fused.listener.FusedLocationListener {
                override fun onChanged(data: ai.pleos.playground.location.fused.model.LocationData?) {
                    if (data == null) { setLocationStatus("아직 위치 없음"); return }
                    val lat = data.latitude; val lon = data.longitude
                    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) { setLocationStatus("위치 값 이상 · 사용 안 함"); return }
                    val old = fix
                    fix = Fix(lat, lon, SystemClock.elapsedRealtime())
                    if (old == null || old.lat != lat || old.lon != lon) { Log.i(tag, "OBS LOCATION received conf=${data.confidence}"); onChange() }   // coordinates are not written to the log
                    setLocationStatus("차량 위치 수신")
                }
                override fun onError(e: Exception) {
                    val code = (e as? ai.pleos.playground.location.fused.exception.LocationException)?.errorCode?.name
                    setLocationStatus(when (code) {
                        "ERROR_SECURITY_PERMISSION" -> "위치 권한 없음"; "ERROR_LOCATION_DISABLED" -> "차량 위치 꺼짐"
                        "ERROR_EMPTY_RESULT" -> "아직 위치 없음"; "ERROR_DRIVING_RESTRICTED" -> "주행 중 제한"; else -> "위치 읽기 실패"
                    })
                }
            })
        } catch (e: Throwable) {
            setLocationStatus("위치 읽기 실패"); Log.i(tag, "FUSED read failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // --- address text from the head-unit navigation (Pleos NaviHelper.getCurrentLocationInfo). Read only; the app never requests a route.
    @Volatile private var naviAddress: String? = null
    @Volatile private var naviAddressAt = 0L
    @Volatile private var naviLat = Double.NaN
    @Volatile private var naviLon = Double.NaN
    private var navi: ai.pleos.playground.navi.helper.NaviHelper? = null
    private var naviRetryAt = 0L
    private var naviAskedAt = 0L
    private val naviListener = object : ai.pleos.playground.navi.helper.listener.NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: ai.pleos.playground.navi.data.CurrentLocationInfo) {
            val address = info.address?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val changed = address != naviAddress
            naviAddress = address; naviLat = info.latitude; naviLon = info.longitude; naviAddressAt = SystemClock.elapsedRealtime()
            if (changed) { Log.i(tag, "OBS NAVI_ADDRESS received chars=${address.length}"); onChange() }
        }
        override fun onError(code: ai.pleos.playground.navi.constants.NaviErrorCode) { Log.i(tag, "NAVI error $code") }
    }

    private fun pollNavi() {
        val now = SystemClock.elapsedRealtime()
        var n = navi
        if (n == null) {
            if (now < naviRetryAt) return
            naviRetryAt = now + 15_000L
            try {
                val created = ai.pleos.playground.navi.helper.NaviHelper(app)
                created.initialize({ Log.i(tag, "NAVI initialized") }, { e -> Log.i(tag, "NAVI init failed ${e.javaClass.simpleName}: ${e.message}") })
                created.addListener(naviListener)
                navi = created; n = created
            } catch (e: Throwable) { Log.i(tag, "NAVI create failed ${e.javaClass.simpleName}: ${e.message}"); return }
        }
        if (now - naviAskedAt < 5_000L) return
        naviAskedAt = now
        try { n.getCurrentLocationInfo() } catch (e: Throwable) { Log.i(tag, "NAVI read failed ${e.javaClass.simpleName}: ${e.message}") }
    }

    /**
     * adaptive: scene B. Ask the Pleos navigation to come to the front (official NaviHelper.requestNavigationScreen, request "Foreground").
     * The navigation app and the Driving View are the system's; this app draws neither. Result goes to [done] ("ok" or the reason).
     */
    fun requestNavigationForeground(done: (String) -> Unit) {
        sdkHandler.post {
            val n = navi
            if (released || n == null) { done("내비 연결 없음"); return@post }
            try {
                n.requestNavigationScreen(ai.pleos.playground.navi.data.NavigationScreenRequest.Foreground)
                Log.i(tag, "NAVI requestNavigationScreen(Foreground) sent"); done("ok")
            } catch (e: Throwable) {
                Log.i(tag, "NAVI requestNavigationScreen failed ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"); done("${e.javaClass.simpleName}")
            }
        }
    }

    /** Address only while fresh (30 s) and only when the navigation position is within 300 m of the vehicle fix (R2D rule). */
    fun freshAddress(now: Long = SystemClock.elapsedRealtime()): String? {
        val address = naviAddress?.takeIf { now - naviAddressAt in 0..MAX_AGE_MS } ?: return null
        val car = fix?.takeIf { it.fresh(now) } ?: return null
        if (listOf(car.lat, car.lon, naviLat, naviLon).any { !it.isFinite() }) return null
        val r = 6_371_000.0
        val dLat = Math.toRadians(naviLat - car.lat); val dLon = Math.toRadians(naviLon - car.lon)
        val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(car.lat)) * Math.cos(Math.toRadians(naviLat)) * Math.sin(dLon / 2).let { it * it }
        return address.takeIf { 2 * r * Math.asin(Math.sqrt(a)) <= 300.0 }
    }

    companion object {
        const val PERM_FUSED = "pleos.car.permission.FUSED_LOCATION"
        const val MAX_AGE_MS = 30_000L
        val SEATS = listOf("D" to ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_1_LEFT, "P" to ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_1_RIGHT,
            "RL" to ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_2_LEFT, "RR" to ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_2_RIGHT)
        val DOOR_KEYS = listOf("FL" to DoorValue.ROW_1_LEFT, "FR" to DoorValue.ROW_1_RIGHT, "RL" to DoorValue.ROW_2_LEFT, "RR" to DoorValue.ROW_2_RIGHT, "TG" to DoorValue.REAR)
    }

    private val carLoop = object : Runnable {
        override fun run() {
            if (released) return
            if (manager == null) connectCar()
            readProp("GEAR", android.car.VehiclePropertyIds.GEAR_SELECTION)
            readProp("SPEED", android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED)
            onChange()
            carHandler.postDelayed(this, 1_000L)
        }
    }

    private fun connectCar() {
        try {
            val c = android.car.Car.createCar(app)
            car = c
            manager = c?.getCarManager(android.car.Car.PROPERTY_SERVICE) as? android.car.hardware.property.CarPropertyManager
        } catch (e: Throwable) {
            Log.i(tag, "CAR connect failed ${e.javaClass.simpleName}")
        }
    }

    private fun readProp(key: String, id: Int) {
        val now = SystemClock.elapsedRealtime()
        val m = manager as? android.car.hardware.property.CarPropertyManager
        if (m == null) { put(key, Reading(key, null, SignalStatus.UNAVAILABLE, now)); return }
        try {
            val v = m.getProperty<Any>(id, 0).value
            put(key, Reading(key, v?.toString(), if (v == null) SignalStatus.UNAVAILABLE else SignalStatus.OK, now))
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            val st = if (e is SecurityException || msg.contains("permission", true)) SignalStatus.PERMISSION_DENIED else SignalStatus.ERROR
            put(key, Reading(key, null, st, now))
        }
    }

    private fun put(key: String, r: Reading) {
        val old = readings[key]
        readings[key] = r
        if (old == null || old.raw != r.raw || old.status != r.status) Log.i(tag, "OBS $key raw=${r.raw} status=${r.status}")
    }
}

/**
 * The real Pleos Vehicle SDK behind [DProtectionPort] (R2D DVehicleProtectionPort). Read before, write, read again;
 * a write reply alone never counts as success. Restore mode writes back the values captured on the first read.
 */
internal class VehicleProtectionPort(
    private val hub: VehicleLink,
    private val post: (() -> Unit) -> Unit,
    /** null = protect (lights ON, doors unlocked). Non-null = restore these captured originals. */
    private val restore: Originals? = null,
) : DProtectionPort {

    class Originals {
        val lights: MutableMap<DActuator, LightSwitch> = ConcurrentHashMap()
        val doors: MutableMap<DoorArea, Boolean> = ConcurrentHashMap()
        fun has(action: DActuator) = if (action == DActuator.DOOR_LOCK) doors.isNotEmpty() else action in lights
    }

    val originals = Originals()
    /** What the vehicle reported on the last read, for the screen: "켜짐", "꺼짐", "잠김", "풀림", "잠김 2 · 풀림 2". */
    val shown: MutableMap<DActuator, String> = ConcurrentHashMap()
    private val written: MutableSet<DActuator> = ConcurrentHashMap.newKeySet()
    @Volatile private var doorAreas: List<DoorArea> = emptyList()

    fun wrote(action: DActuator) = action in written

    private fun reply(done: (DReply<Boolean>) -> Unit, value: DReply<Boolean>) = post { done(value) }
    private fun log(m: String) = Log.i("FullHMIVeh", (if (restore == null) "PROTECT " else "RESTORE ") + m)

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

    private fun lightTarget(action: DActuator): LightSwitch = restore?.lights?.get(action) ?: LightSwitch.ON
    private fun doorTarget(door: DoorArea): Boolean = restore?.doors?.get(door) ?: false // protect: unlocked so helpers can open the doors

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

    /** After our own write the head unit may apply the change a moment later: re-read up to 6 times (about 2.1 s, inside the 2.5 s step timeout). */
    private fun readWithRetry(action: DActuator, done: (DReply<Boolean>) -> Unit, attempt: Int) {
        readOnce(action) { r ->
            if (r is DReply.Value && !r.value && action in written && attempt < 6) hub.withVehicleDelayed(350L) { readWithRetry(action, done, attempt + 1) }
            else post { done(r) }
        }
    }

    private fun readOnce(action: DActuator, result: (DReply<Boolean>) -> Unit) {
        onVehicle({ result(DReply.Failure(it)) }) { v ->
            val err: (Exception) -> Unit = { e -> result(DReply.Failure(classify(e))) }
            if (action == DActuator.DOOR_LOCK) readDoors(v, 0, mutableMapOf(), result, err) else readLight(v.getLight(), action, result, err)
        }
    }

    private fun readLight(light: Light, action: DActuator, result: (DReply<Boolean>) -> Unit, err: (Exception) -> Unit) {
        val got: (LightSwitch?) -> Unit = { sw ->
            log("$action switch=$sw target=${lightTarget(action)}")
            if (sw == null) result(DReply.Failure(DOutcome.NO_VALUE)) else {
                if (restore == null && action !in originals.lights && action !in written) originals.lights[action] = sw
                shown[action] = when (sw) { LightSwitch.ON -> "켜짐"; LightSwitch.OFF -> "꺼짐"; else -> sw.toString() }
                result(DReply.Value(sw == lightTarget(action)))
            }
        }
        when (action) {
            DActuator.TAIL -> light.getTailLampLightSwitch(got, err)
            DActuator.REAR_FOG -> light.getRearFogLightSwitch(got, err)
            else -> light.getCabinLightSwitch(got, err)
        }
    }

    private fun readDoors(v: Vehicle, i: Int, seen: MutableMap<DoorArea, Boolean>, result: (DReply<Boolean>) -> Unit, err: (Exception) -> Unit) {
        if (i >= doorAreas.size) {
            if (restore == null && originals.doors.isEmpty() && DActuator.DOOR_LOCK !in written) originals.doors.putAll(seen)
            val locked = seen.values.count { it }
            shown[DActuator.DOOR_LOCK] = when (locked) { seen.size -> "잠김"; 0 -> "풀림"; else -> "잠김 $locked · 풀림 ${seen.size - locked}" }
            result(DReply.Value(seen.all { (door, isLocked) -> isLocked == doorTarget(door) }))
            return
        }
        val door = doorAreas[i]
        v.getDoor().getDoorLockState(door, { locked: Boolean? ->
            log("DOOR ${door.values.toList()} locked=$locked")
            if (locked == null) result(DReply.Failure(DOutcome.NO_VALUE)) else { seen[door] = locked; hub.withVehicle { readDoors(v, i + 1, seen, result, err) } }
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
        v.getDoor().setDoorLock(door, doorTarget(door), { _: DoorArea?, _: Boolean? -> hub.withVehicle { writeDoors(v, i + 1, done, err) } }, err)
    }

    companion object {
        val DOORS = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    }
}
