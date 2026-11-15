package com.contest.signalprobe

import android.app.Instrumentation
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Separate, same-signed test APK. Never packaged in release. No permission or VHAL changes. */
class CaseCInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = StringBuilder()
        val screenField = Class.forName("com.contest.signalprobe.Session").getDeclaredField("screen").apply { isAccessible=true }
        var hub: VehicleHub? = null
        try {
            val initial = startActivitySync(Intent(targetContext, ProductActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProductActivity
            val a = verifyActivityRecreation(initial, results)
            val testFile = File.createTempFile("p52-activity-", ".json", targetContext.cacheDir)
            check(testFile.delete())
            val testLog = IncidentLog(testFile)
            runOnMainSync {
                a.javaClass.getDeclaredField("incidentLog").apply { isAccessible = true }.set(a, testLog)
            }
            verifyPassport(results)
            verifyHandoffProfiles(results)
            verifyTowRehearsal(results)
            verifyDFoundation(results)
            hub = field(a, "hub") as VehicleHub
            val h = hub
            SystemClock.sleep(4000)
            runOnMainSync { h.stop() }
            SystemClock.sleep(2500) // finish any already executing read
            @Suppress("UNCHECKED_CAST")
            val readings = field(h, "readings") as MutableMap<String, Reading>
            fun refresh() { a.javaClass.getDeclaredMethod("refresh", Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(a,true) }
            fun startSituation() {
                a.javaClass.getDeclaredMethod("startSituation").apply { isAccessible = true }.invoke(a)
            }
            fun hasButton(view: View, label: String): Boolean {
                if (view is Button && view.text.toString() == label) return true
                return view is ViewGroup && (0 until view.childCount).any { hasButton(view.getChildAt(it), label) }
            }
            fun hasButton(label: String) = hasButton(a.window.decorView, label)
            fun setParked() {
                val now=SystemClock.elapsedRealtime()
                readings["SPEED"]=Reading("SPEED","TEST ONLY","0.0",SignalStatus.OK,Category.VERIFIED,now,System.currentTimeMillis())
                readings["GEAR"]=Reading("GEAR","TEST ONLY","4",SignalStatus.OK,Category.VERIFIED,now,System.currentTimeMillis())
            }
            var failure: Throwable? = null
            runOnMainSync { try {
                check(MobilityEvaluator.decide(30f,4,false).motion==Motion.MOVING)
                check(MobilityEvaluator.decide(30f,4,false).conflict)
                check(MobilityEvaluator.decide(0f,8,false).motion==Motion.STOPPED_NOT_PARKED)
                check(MobilityEvaluator.decide(0f,8,true).motion==Motion.STOPPED_PARKED)
                check(MobilityEvaluator.decide(null,4,false).motion==Motion.UNKNOWN)
                results.appendLine("PASS four motion states and speed/P conflict (synthetic adapter inputs)")
                screenField.set(null,Screen.HOME); readings.clear(); refresh()
                check(MobilityEvaluator.evaluate(h).motion==Motion.UNKNOWN)
                startSituation()
                check(screenField.get(null) == Screen.GUIDE && hasButton("정차했어요") && !hasButton("다음"))
                results.appendLine("PASS missing: guide opens directly with inline stationary confirmation")
                setParked(); refresh()
                check(hasButton("다음") && !hasButton("정차했어요"))
                results.appendLine("PASS missing recovery: fresh parked state enables guide progression")
                for ((k,r) in readings.toMap()) readings[k]=r.copy(at=SystemClock.elapsedRealtime()-6000)
                refresh()
                check(MobilityEvaluator.evaluate(h).motion == Motion.UNKNOWN && hasButton("정차했어요") && !hasButton("다음"))
                results.appendLine("PASS stale >5s: guide requires inline stationary confirmation")
                fun short(name:String)=a.javaClass.getDeclaredMethod(name).apply{isAccessible=true}.invoke(a).toString()
                val doors = a.javaClass.getDeclaredMethod("doorsText", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(a, false).toString()
                check(doors.contains("신호 없음"))
                check(short("wiperShort").contains("신호없음"))
                check(short("lowShort").contains("신호없음"))
                results.appendLine("PASS stale/missing context: doors/wiper/lowbeam do not report normal defaults")
                screenField.set(null, Screen.PASSPORT)
                val changedAt = SystemClock.elapsedRealtime()
                readings["SPEED"] = Reading("SPEED", "TEST ONLY", "12", SignalStatus.OK, Category.VERIFIED, changedAt, System.currentTimeMillis())
                refresh()
                check(!hasButton("기록 지우기") && !hasButton("좌석 직접 확인"))
                results.appendLine("PASS moving state blocks passport editing and sharing")
                val stepField = a.javaClass.getDeclaredField("roadsideStep").apply { isAccessible = true }
                val locationField = a.javaClass.getDeclaredField("towLocation").apply { isAccessible = true }
                val drillField = a.javaClass.getDeclaredField("towRehearsal").apply { isAccessible = true }
                screenField.set(null, Screen.ROADSIDE)
                stepField.setInt(a, 3)
                setParked()
                refresh()
                check(hasButton("위치 입력") && hasButton("QR로 공유"))
                locationField.set(a, "가상도로 서울 방향 123 km 표지")
                refresh()
                check(hasButton("위치 수정"))
                drillField.set(a, TowRehearsal())
                refresh()
                check(hasButton("가상 위치로 연습") && hasButton("입력한 위치로 연습"))
                check(!hasButton("QR로 공유") && !hasButton("보험사 전화"))
                fun assertRoadsideLocked() {
                    refresh()
                    check(!hasButton("위치 입력") && !hasButton("위치 수정") && !hasButton("QR로 공유"))
                    check(hasButton("119") && hasButton("홈으로") && locationField.get(a) == null)
                    check(drillField.get(a) == null && !hasButton("가상 위치로 연습"))
                }
                readings["SPEED"] = readings.getValue("SPEED").copy(raw = "0.3")
                assertRoadsideLocked()
                setParked()
                readings["GEAR"] = readings.getValue("GEAR").copy(raw = "8")
                assertRoadsideLocked()
                setParked()
                readings["GEAR"] = readings.getValue("GEAR").copy(at = SystemClock.elapsedRealtime() - 6000)
                assertRoadsideLocked()
                setParked()
                readings["SPEED"] = readings.getValue("SPEED").copy(raw = "12")
                for (step in 1..3) {
                    stepField.setInt(a, step)
                    assertRoadsideLocked()
                    check(!hasButton("다음") && !hasButton("경미한 부상"))
                }
                setParked()
                refresh()
                check(hasButton("위치 입력"))
                results.appendLine("PASS PM15 roadside: fresh P/zero opens preparation; low speed, D, stale gear and moving lock editing; draft clears; contact remains (synthetic adapter inputs)")
                readings.clear()
                for (p in SeatPresence.entries) for (confirmed in listOf(false,true)) {
                    val input=SummaryInput(System.currentTimeMillis(),MobilityEvaluator.decide(null,null,null),
                        listOf("앞왼","앞오","뒤왼","뒤오").map{SeatLine(it,p,confirmed,true)},
                        "신호 없음","신호 없음","신호 없음","사고",4,4,true,true)
                    val result=IncidentSummary.build(input)
                    check(result.bytes<=IncidentSummary.MAX_BYTES) { "bytes=${result.bytes} p=$p" }
                    check(result.text.contains("사람") && result.text.contains("신고가 아님")) { "text=${result.text}" }
                    check(QrRenderer.selfCheck(checkNotNull(QrRenderer.encode(result.text)),result.text)) { "selfCheck p=$p bytes=${result.bytes} confirmed=$confirmed text=${result.text}" }
                }
                check(QrRenderer.encode("")==null)
                results.appendLine("PASS six seat-summary combinations <=${IncidentSummary.MAX_BYTES}B; QR UTF-8 roundtrip; empty payload error handled")
                // RC4: worst-case payload with seat notes + energy must stay <=600B and keep the notes line.
                val worst=IncidentSummary.build(SummaryInput(System.currentTimeMillis(),MobilityEvaluator.decide(30f,4,true),
                    listOf("앞왼","앞오","뒤왼","뒤오").map{SeatLine(it,SeatPresence.POSSIBLY_OCCUPIED,false,true)},
                    "열림 보고: 앞왼쪽, 앞오른쪽, 뒤왼쪽, 뒤오른쪽, 트렁크","신호없음","신호없음","기타",4,4,true,true,
                    "배터리 4% · 연료 4%(낮음 보고)",listOf("앞왼 거동불편","앞오 반려동물","뒤왼 영유아","뒤오 거동불편"),true,"모름"))
                check(worst.bytes<=IncidentSummary.MAX_BYTES) { "worst bytes=${worst.bytes}\n${worst.text}" }
                check(worst.text.contains("다친 사람 모름")) { worst.text }
                check(worst.text.contains("사전메모(입력)") && worst.text.contains("신고가 아님"))
                check(QrRenderer.selfCheck(checkNotNull(QrRenderer.encode(worst.text)),worst.text))
                val excluded=IncidentSummary.build(SummaryInput(System.currentTimeMillis(),MobilityEvaluator.decide(0f,4,true),
                    emptyList(),"x","x","x",null,0,4,false,false,"배터리 50%",listOf("뒤오 영유아"),false))
                check(!excluded.text.contains("사전메모") && !excluded.text.contains("배터리"))
                results.appendLine("PASS RC4 worst-case payload ${worst.bytes}B <=${IncidentSummary.MAX_BYTES} keeps notes+injured; notes/state toggles exclude lines")
                // Stage 1: handoff cards (pure templates) and contact intents (built, never launched here).
                val unknownMob=MobilityEvaluator.decide(null,null,null)
                val ci=CardInput(System.currentTimeMillis(),unknownMob,
                    listOf(SeatInfo("앞줄 왼쪽",SeatPresence.POSSIBLY_OCCUPIED,true,null),SeatInfo("앞줄 오른쪽",SeatPresence.REPORTED_EMPTY,false,null),
                        SeatInfo("뒷줄 왼쪽",SeatPresence.UNKNOWN,false,null),SeatInfo("뒷줄 오른쪽",SeatPresence.REPORTED_EMPTY,false,"영유아(카시트)")),
                    "일부 또는 전체 문·트렁크 신호 없음","신호없음",null,null)
                val c119=HandoffCards.card119(ci)
                check(c119.first().src==Src.APP && c119.first().text.contains("위치를 읽지 않습니다")) { "location line" }
                check(c119.any{it.label=="다친 사람" && it.src==Src.USER && it.text.contains("아직 입력 안 함")}) { "injured unknown" }
                val seatLine=c119.first{it.label=="좌석 상태"}.text
                check(seatLine.contains("뒷줄 오른쪽") && seatLine.contains("메모(입력) 영유아(카시트)") && seatLine.contains("사람 확인 안 함")) { seatLine }
                check(seatLine.contains("앞줄 왼쪽: 센서 착석 가능성 · 사람 확인함") && seatLine.contains("뒷줄 왼쪽: 센서 신호 없음")) { seatLine }
                check(!seatLine.contains("앞줄 오른쪽")) { "reported-empty seat without note must not be listed: $seatLine" }
                val all=(c119+HandoffCards.card112(ci)+HandoffCards.cardInsurance(ci)+HandoffCards.cardTow(ci)).joinToString("\n"){it.text}
                for (bad in listOf("신고되었습니다","신고 완료","출동 중","도움이 오고","명 탑승","대피 완료","심정지","부상입니다")) check(!all.contains(bad)) { "forbidden claim: $bad" }
                check(HandoffCards.cardInsurance(ci).any{it.label=="과실" && it.text.contains("정하지 않아도")}) { "fault disclaimer" }
                check(HandoffCards.cardInsurance(ci).none{it.text.contains("영유아")}) { "insurer card must not carry seat notes" }
                check(HandoffCards.pinned(ci.copy(category="보행자 관련")).first()==HandoffCards.PEDESTRIAN_FIRST)
                check(HandoffCards.pinned(ci).none{it==HandoffCards.PEDESTRIAN_FIRST})
                val sms=HandoffCards.smsBody119(ci)
                check(sms.lines()[1].startsWith("위치:") && sms.contains("다친 사람: 모름") && sms.contains("뒷줄 오른쪽(메모 영유아(카시트))") && sms.length<300) { sms }
                val dial=HandoffCards.dialIntent("119")
                check(dial.action==android.content.Intent.ACTION_DIAL && dial.data.toString()=="tel:119") { "dial=$dial" }
                check(HandoffCards.dialIntent("1588-2504").data.toString()=="tel:15882504")
                val smsI=HandoffCards.smsIntent("119",sms)
                check(smsI.action==android.content.Intent.ACTION_SENDTO && smsI.data.toString()=="smsto:119" && smsI.getStringExtra("sms_body")==sms)
                check(HandoffCards.validPhone("000-0000-0000") && !HandoffCards.validPhone("12") && !HandoffCards.validPhone("abc-123") && !HandoffCards.validPhone(""))
                results.appendLine("PASS stage1 cards: unknowns stated, provenance tagged, no forbidden claims, insurer card excludes notes, pedestrian pinned; ACTION_DIAL/SENDTO only (sms ${sms.length} chars)")
                check(short("energyShort").contains("신호없음"))
                results.appendLine("PASS RC4 stale/missing energy shows 신호없음, never a default percentage")
                val notesObj=checkNotNull(field(a,"notes"))
                val get=notesObj.javaClass.getDeclaredMethod("get",String::class.java).apply{isAccessible=true}
                val set=notesObj.javaClass.getDeclaredMethod("set",String::class.java,String::class.java).apply{isAccessible=true}
                val before=get.invoke(notesObj,"ROW_2_RIGHT") as String?
                set.invoke(notesObj,"ROW_2_RIGHT","영유아(카시트)")
                val lines=a.javaClass.getDeclaredMethod("seatNoteLines").apply{isAccessible=true}.invoke(a) as List<*>
                check(lines.contains("뒤오 영유아")) { "lines=$lines" }
                set.invoke(notesObj,"ROW_2_RIGHT","임의 텍스트"); check(get.invoke(notesObj,"ROW_2_RIGHT")==null)
                set.invoke(notesObj,"ROW_2_RIGHT",before)
                results.appendLine("PASS RC4 seat note stored locally, mapped to QR short form; unknown values rejected; original restored")
                setParked()
                screenField.set(null, Screen.HOME)
                refresh()
                startSituation()
                check(screenField.get(null) == Screen.GUIDE && hasButton("다음"))
                results.appendLine("PASS fresh parked state opens GUIDE without an intermediate dialog")
                readings.clear(); refresh()
                a.javaClass.getDeclaredMethod("confirmStoppedForGuide").apply { isAccessible = true }.invoke(a)
                check(screenField.get(null) == Screen.GUIDE && hasButton("다음"))
                results.appendLine("PASS UNKNOWN explicit in-screen stationary confirmation allows progression")

                // ---- R2D (2026-11-14): emergency layout, SOS / location screens, moving home. Submission variant only draws the offline SOS screen. ----
                fun all(view: View, into: MutableList<View> = mutableListOf()): MutableList<View> {
                    into += view; if (view is ViewGroup) for (i in 0 until view.childCount) all(view.getChildAt(i), into); return into
                }
                fun buttons() = all(a.window.decorView).filterIsInstance<Button>()
                fun buttonStarting(prefix: String) = buttons().firstOrNull { it.text.toString().startsWith(prefix) }
                fun described(prefix: String) = all(a.window.decorView).firstOrNull { it.contentDescription?.toString()?.startsWith(prefix) == true }
                setParked(); screenField.set(null, Screen.HOME); refresh()
                val b119 = checkNotNull(buttonStarting("119 전화 연결")) { "119 banner missing" }
                val bSos = checkNotNull(buttons().firstOrNull { it.text.toString().startsWith("SOS") || it.text.toString().startsWith("주변 SOS") }) { "SOS banner missing" }
                val p119 = b119.layoutParams as android.widget.LinearLayout.LayoutParams; val pSos = bSos.layoutParams as android.widget.LinearLayout.LayoutParams
                check(b119.parent === bSos.parent && p119.weight == pSos.weight && p119.width == 0 && pSos.width == 0 && p119.height == pSos.height) { "119/SOS are not an equal 50:50 pair" }
                check(b119.paddingLeft == bSos.paddingLeft && b119.minHeight == bSos.minHeight) { "119/SOS padding or min height differ" }
                check(buttons().none { it.text.toString().startsWith("112") }) { "a 112 banner is still on Home" }
                val q112 = checkNotNull(described("긴급 연락 112")) { "112 quick button missing" }
                check(q112.isClickable && described("긴급 연락 119") == null)
                results.appendLine("PASS R2D home: 119/SOS equal 50:50 banners, no 112 banner, round 112 quick button in the rail")

                screenField.set(null, Screen.SOS); refresh()
                check(buttonStarting("현재 위치 확인") != null && buttonStarting("사고 기록 확인") != null && buttonStarting("119 전화 연결") != null && buttonStarting("112 전화 연결") != null)
                check(buttons().none { it.text.toString().contains("주변 SOS 요청") || it.text.toString().contains("받기 켜기") }) { "nearby-SOS controls leaked into the submission SOS screen" }
                check(!BuildConfig.NEARBY_SOS && !BuildConfig.ACCIDENT_SIM)
                results.appendLine("PASS R2D submission SOS screen: dialer cards, location and record entries, no nearby-SOS network controls")

                screenField.set(null, Screen.SOS_LOC); refresh()
                check(hasButton("119 전화 연결") && hasButton("사고 기록 열기"))
                check(buttons().none { it.text.toString().contains("전송") && !it.text.toString().contains("않") })
                results.appendLine("PASS R2D location screen: 119 dialer and record entry, nothing that sends the position")

                val d112 = HandoffCards.dialIntent("112")
                check(d112.action == Intent.ACTION_DIAL && d112.data.toString() == "tel:112" && HandoffCards.dialIntent("119").action == Intent.ACTION_DIAL)
                check(targetContext.packageManager.getPackageInfo(targetContext.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
                    .none { it == "android.permission.CALL_PHONE" || it == "android.permission.INTERNET" })
                results.appendLine("PASS R2D dialer intents are ACTION_DIAL only; no CALL_PHONE or INTERNET permission in this variant")

                val now2 = SystemClock.elapsedRealtime()
                readings["SPEED"] = Reading("SPEED", "TEST ONLY", "10.0", SignalStatus.OK, Category.VERIFIED, now2, System.currentTimeMillis())
                readings["GEAR"] = Reading("GEAR", "TEST ONLY", "8", SignalStatus.OK, Category.VERIFIED, now2, System.currentTimeMillis())
                screenField.set(null, Screen.HOME); refresh()
                check(MobilityEvaluator.evaluate(h).motion == Motion.MOVING)
                val locked = buttons().filter { it.text.toString().endsWith("정차 후 이용") }
                check(locked.size == 4 && locked.none { it.isEnabled }) { "moving home should lock exactly four after-stop cards, found ${locked.size}" }
                check(buttonStarting("119 전화 연결")?.isEnabled == true && buttonStarting("보험·견인")?.isEnabled == true && buttonStarting("사고 기록 확인")?.isEnabled == true)
                results.appendLine("PASS R2D moving home: four after-stop cards locked, 119/SOS, insurance and record stay open (synthetic adapter inputs)")
                setParked()
                screenField.set(null,Screen.HOME); h.start(); refresh()
            } catch(t:Throwable) { failure=t } }
            failure?.let { throw it }
            testLog.clear()
            testFile.delete()
            results.appendLine("PASS restoration: polling restarted; no permissions changed")
            finish(0,Bundle().apply{putString("stream",results.toString())})
        } catch(t:Throwable) {
            hub?.start()
            finish(1,Bundle().apply{putString("stream",results.toString()+"FAIL "+t.stackTraceToString())})
        }
    }

    private fun verifyDFoundation(results: StringBuilder) {
        fun signal(key: String, raw: String, at: Long) =
            Reading(key, "synthetic", raw, SignalStatus.OK, Category.VERIFIED, at, 0)
        val speed = signal("SPEED", "0", 10_000)
        val gear = signal("GEAR", "4", 10_000)
        check(DParkGate.blocked(speed, gear, 10_000) == null)
        check(DParkGate.blocked(speed.copy(raw = "-0.1"), gear, 10_000) != null)
        check(DParkGate.blocked(speed.copy(raw = "NaN"), gear, 10_000) != null)
        check(DParkGate.blocked(speed, gear.copy(raw = "8"), 10_000) != null)
        check(DParkGate.blocked(speed, gear, 13_000) != null)
        check(DParkGate.blocked(speed, gear.copy(at = 8_400), 10_000) != null)
        check(DParkGate.blocked(speed.copy(at = 10_001), gear, 10_000) != null)
        check(DParkGate.blocked(speed, gear.copy(status = SignalStatus.PERMISSION_DENIED), 10_000) != null)

        data class Job(val at: Long, val work: () -> Unit)
        val queue = java.util.PriorityQueue(compareBy<Job> { it.at })
        var now = 0L
        val schedule: (Long, () -> Unit) -> Unit = { delay, work -> queue.add(Job(now + delay, work)); Unit }
        fun drain() {
            while (queue.isNotEmpty()) {
                val job = queue.remove()
                now = job.at
                job.work()
            }
        }
        val sample = DProtectionRun(DSimulatedProtection(schedule), { null }, schedule) {}
        sample.start()
        drain()
        check(!sample.running && sample.result(DActuator.TAIL).outcome == DOutcome.ALREADY)
        check(sample.result(DActuator.REAR_FOG) == DResult(DOutcome.MISMATCH, DPhase.AFTER, false))
        check(sample.result(DActuator.CABIN_AMBIENT) == DResult(DOutcome.VERIFIED, DPhase.AFTER, true))
        check(sample.result(DActuator.DOOR_LOCK) == DResult(DOutcome.DENIED, DPhase.CHECK))

        var parkReason: String? = null
        var sends = 0
        var late: ((DReply<Unit>) -> Unit)? = null
        val delayed = object : DProtectionPort {
            override fun supports(action: DActuator, done: (DReply<Boolean>) -> Unit) { done(DReply.Value(true)) }
            override fun readAtTarget(action: DActuator, done: (DReply<Boolean>) -> Unit) { done(DReply.Value(false)) }
            override fun requestTarget(action: DActuator, done: (DReply<Unit>) -> Unit) { sends++; late = done }
        }
        val interrupted = DProtectionRun(delayed, { parkReason }, schedule) {}
        interrupted.start()
        interrupted.start()
        check(sends == 1)
        parkReason = "left P"
        interrupted.recheck()
        late?.invoke(DReply.Value(Unit))
        drain()
        check(sends == 1 && interrupted.result(DActuator.TAIL).outcome == DOutcome.UNCERTAIN)
        check(interrupted.result(DActuator.REAR_FOG).outcome == DOutcome.STOPPED)

        parkReason = null
        val timed = DProtectionRun(delayed, { parkReason }, schedule) {}
        timed.start()
        drain()
        check(sends == 2 && timed.result(DActuator.TAIL) == DResult(DOutcome.TIMEOUT, DPhase.WRITE))
        check(timed.result(DActuator.REAR_FOG).outcome == DOutcome.STOPPED)
        results.appendLine("PASS D protection: fresh paired P/zero, phase/readback, mismatch, denied, duplicate start, late callback, timeout and no further writes (simulation)")

        // D plan F1: a blocked gate (moving / stale) sends nothing at all, not even a capability query.
        var queried = 0
        val counting = object : DProtectionPort {
            override fun supports(action: DActuator, done: (DReply<Boolean>) -> Unit) { queried++; done(DReply.Value(true)) }
            override fun readAtTarget(action: DActuator, done: (DReply<Boolean>) -> Unit) { queried++; done(DReply.Value(false)) }
            override fun requestTarget(action: DActuator, done: (DReply<Unit>) -> Unit) { queried++; done(DReply.Value(Unit)) }
        }
        val moving = DProtectionRun(counting, { "차가 완전히 멈춘 뒤 실행하세요." }, schedule) {}
        moving.start(); drain()
        check(queried == 0 && !moving.running && DActuator.entries.all { moving.result(it).outcome == DOutcome.STOPPED })
        // Undo touches only what the app changed: a restore run over a subset never queries the others.
        val touched = mutableListOf<DActuator>()
        val recording = object : DProtectionPort {
            val state = mutableMapOf<DActuator, Boolean>()
            override fun supports(action: DActuator, done: (DReply<Boolean>) -> Unit) { touched += action; done(DReply.Value(true)) }
            override fun readAtTarget(action: DActuator, done: (DReply<Boolean>) -> Unit) { done(DReply.Value(state[action] == true)) }
            override fun requestTarget(action: DActuator, done: (DReply<Unit>) -> Unit) { state[action] = true; done(DReply.Value(Unit)) }
        }
        val undo = DProtectionRun(recording, { null }, schedule, listOf(DActuator.TAIL, DActuator.DOOR_LOCK)) {}
        undo.start(); drain()
        check(touched == listOf(DActuator.TAIL, DActuator.DOOR_LOCK) && !undo.has(DActuator.REAR_FOG) && !undo.has(DActuator.CABIN_AMBIENT))
        check(undo.result(DActuator.TAIL).outcome == DOutcome.VERIFIED && undo.result(DActuator.DOOR_LOCK).outcome == DOutcome.VERIFIED)
        // Undo must not trust "already original": a pending earlier request can land later. It writes the original anyway.
        var undoWrites = 0
        val looksRestored = object : DProtectionPort {
            override fun supports(action: DActuator, done: (DReply<Boolean>) -> Unit) { done(DReply.Value(true)) }
            override fun readAtTarget(action: DActuator, done: (DReply<Boolean>) -> Unit) { done(DReply.Value(true)) }
            override fun requestTarget(action: DActuator, done: (DReply<Unit>) -> Unit) { undoWrites++; done(DReply.Value(Unit)) }
        }
        val forced = DProtectionRun(looksRestored, { null }, schedule, listOf(DActuator.TAIL, DActuator.DOOR_LOCK), alwaysWrite = true) {}
        forced.start(); drain()
        check(undoWrites == 2 && forced.result(DActuator.TAIL).outcome == DOutcome.VERIFIED)
        val normal = DProtectionRun(looksRestored, { null }, schedule, listOf(DActuator.TAIL)) {}
        normal.start(); drain()
        check(undoWrites == 2 && normal.result(DActuator.TAIL).outcome == DOutcome.ALREADY)
        results.appendLine("PASS D protect gate: moving/stale sends nothing (0 SDK calls); undo run touches only changed items and always re-sends the original")

        // D plan F2: vehicle position freshness (local receipt clock) and sharing.
        val car = VehicleFix(37.5, 127.0, 1_794_486_959_084, 100_000, 5f)
        check(car.text == "37.50000, 127.00000" && car.fresh(110_000) && car.ageSec(110_000) == 10L)
        check(!car.fresh(130_001) && !car.fresh(99_000))
        val facts = listOf(LogEvent(1L, EventKind.VEHICLE, "기어", key = "GEAR", value = "P", quality = "확인된 신호"))
        val withCar = PassportHandoff.prepare(HandoffProfile.FIELD, "11-12 10:00:00", "차량 간 사고", "경미한 부상 1명",
            facts, facts, Long::toString, null, "37.50000, 127.00000 (11-12 10:00:05 기준)")
        check(withCar.encodable && withCar.text.contains("위치(차량): 37.50000, 127.00000 (11-12 10:00:05 기준)"))
        check(withCar.text.indexOf("위치(차량)") < withCar.text.indexOf("위치(입력)"))
        check(QrRenderer.selfCheck(checkNotNull(QrRenderer.encode(withCar.text)), withCar.text))
        for (profile in HandoffProfile.entries) {
            val none = PassportHandoff.prepare(profile, "11-12 10:00:00", "차량 간 사고", null, facts, facts, Long::toString)
            check(!none.text.contains("위치(차량)"))
            val p = PassportHandoff.prepare(profile, "11-12 10:00:00", "차량 간 사고", null, facts, facts, Long::toString, null, "37.50000, 127.00000")
            check(p.encodable && p.bytes <= IncidentSummary.MAX_BYTES)
        }
        // F3: navigation address is used only when its position agrees with the vehicle fix (<= 300 m).
        check(VehicleHub.Geo.agree(37.4127212, 127.094681, 37.4130, 127.0950))
        check(!VehicleHub.Geo.agree(37.5, 127.0, 37.4127212, 127.094681))
        check(!VehicleHub.Geo.agree(37.5, 127.0, Double.NaN, 127.0))
        // F6: VIN shape check (emulator returns a 9-char placeholder) and handover line in the insurance QR.
        check(isVin("KMHL14JA5PA123456") && !isVin("\"invalid\"") && !isVin("KMHL14JA5PA12345O") && !isVin("kmhl14ja5pa123456"))
        val ins = PassportHandoff.prepare(HandoffProfile.INSURANCE, "11-12 10:00:00", "차량 간 사고", "경미한 부상 1명", facts, facts,
            Long::toString, null, "수정구 시흥동 · 37.41272, 127.09468 (11-12 10:00:05 기준)", "11-12 08:00 · 차대 …123456 · 누적 12,345km · 가능 320km")
        check(ins.encodable && ins.bytes <= IncidentSummary.MAX_BYTES && ins.text.contains("출발 전(차량): 11-12 08:00 · 차대 …123456") && ins.omitted.isEmpty())
        check(!PassportHandoff.prepare(HandoffProfile.FIELD, "s", "c", null, facts, facts, Long::toString, null, null, "x").text.contains("출발 전"))
        results.appendLine("PASS D handover: VIN shape (placeholder rejected), insurance QR carries 출발 전(차량) or drops it first, field QR never")
        results.appendLine("PASS D navi address: used only when navigation and vehicle positions agree within 300 m; NaN rejected")
        results.appendLine("PASS D vehicle location: 30 s freshness on local clock, 위치(차량) line before typed location in QR, absent when stale/none, QR roundtrip")
    }

    /** Test double: four distinct outcomes without any Vehicle SDK call (moved out of the release APK). */
    private class DSimulatedProtection(private val schedule: (Long, () -> Unit) -> Unit) : DProtectionPort {
        private var cabin = false
        override fun supports(action: DActuator, done: (DReply<Boolean>) -> Unit) = schedule(120L) {
            done(if (action == DActuator.DOOR_LOCK) DReply.Failure(DOutcome.DENIED) else DReply.Value(true))
        }
        override fun readAtTarget(action: DActuator, done: (DReply<Boolean>) -> Unit) = schedule(140L) {
            done(DReply.Value(action == DActuator.TAIL || action == DActuator.CABIN_AMBIENT && cabin))
        }
        override fun requestTarget(action: DActuator, done: (DReply<Unit>) -> Unit) = schedule(180L) {
            if (action == DActuator.CABIN_AMBIENT) cabin = true
            done(DReply.Value(Unit))
        }
    }

    private fun verifyTowRehearsal(results: StringBuilder) {
        val drill = TowRehearsal()
        drill.confirmReadback()
        check(drill.step == TowRehearsal.Step.LOCATION)
        drill.choosePlace(TowRehearsal.Place.SAMPLE)
        drill.chooseMobility(TowRehearsal.MobilityAnswer.UNKNOWN)
        check(drill.step == TowRehearsal.Step.READBACK)
        check(drill.place?.source == "연습 예시" && drill.mobility == TowRehearsal.MobilityAnswer.UNKNOWN)
        drill.editPlace()
        drill.confirmReadback()
        check(drill.place == null && drill.step == TowRehearsal.Step.LOCATION)
        drill.choosePlace(TowRehearsal.Place.UNKNOWN)
        check(drill.step == TowRehearsal.Step.READBACK && drill.place?.label == "위치 미확인")
        drill.editMobility()
        drill.chooseMobility(TowRehearsal.MobilityAnswer.CANNOT_MOVE)
        check(drill.place == TowRehearsal.Place.UNKNOWN)
        drill.confirmReadback()
        check(drill.step == TowRehearsal.Step.DONE && drill.mobility == TowRehearsal.MobilityAnswer.CANNOT_MOVE)
        val fresh = TowRehearsal()
        check(fresh.place == null && fresh.mobility == null)
        results.appendLine("PASS DEEP rehearsal: explicit source, unknown stays unknown, independent corrections, no confirmation before both replies, reset to blank")
        val challenge = TowRehearsal(challenge = true)
        challenge.choosePlace(TowRehearsal.Place.typed("must not enter the simulated mismatch"))
        check(challenge.step == TowRehearsal.Step.LOCATION && challenge.place == null)
        challenge.choosePlace(TowRehearsal.Place.SAMPLE)
        challenge.chooseMobility(TowRehearsal.MobilityAnswer.UNKNOWN)
        check(challenge.readbackPlace?.contains("132 km") == true && challenge.place?.label?.contains("123 km") == true)
        challenge.confirmReadback()
        check(challenge.step == TowRehearsal.Step.READBACK && challenge.showMismatchHint)
        challenge.correctReadback()
        check(challenge.readbackPlace == challenge.place?.label && challenge.mobility == TowRehearsal.MobilityAnswer.UNKNOWN)
        challenge.confirmReadback()
        check(challenge.step == TowRehearsal.Step.DONE)
        results.appendLine("PASS DEEP mismatch challenge: synthetic only, wrong marker cannot complete, correction preserves mobility")
    }
    private fun field(o:Any,n:String):Any? = o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)

    private fun verifyActivityRecreation(activity: ProductActivity, results: StringBuilder): ProductActivity {
        val log = field(activity, "incidentLog") as IncidentLog
        val recordId = log.id
        val before = log.facts().filter { it.kind == EventKind.HUMAN || it.kind == EventKind.INPUT }
        val ready = CountDownLatch(1)
        var recreated: ProductActivity? = null
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, state: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {
                if (a is ProductActivity && a !== activity) {
                    recreated = a
                    ready.countDown()
                }
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        val application = activity.application
        runOnMainSync {
            application.registerActivityLifecycleCallbacks(callback)
            activity.recreate()
        }
        try {
            check(ready.await(10, TimeUnit.SECONDS)) { "Activity did not recreate" }
            val result = checkNotNull(recreated)
            runOnMainSync {
                val reopened = field(result, "incidentLog") as IncidentLog
                check(reopened.id == recordId)
                check(reopened.facts().filter { it.kind == EventKind.HUMAN || it.kind == EventKind.INPUT } == before)
            }
            results.appendLine("PASS Activity recreation: record identity and ${before.size} saved human/input facts restored")
            return result
        } finally {
            runOnMainSync { application.unregisterActivityLifecycleCallbacks(callback) }
        }
    }

    private fun verifyPassport(results: StringBuilder) {
        val file = File.createTempFile("p52-store-", ".json", targetContext.cacheDir)
        check(file.delete())
        try {
            val store = IncidentLog(file)
            check(store.begin(200))
            val seats = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT")
            val raw = listOf("true", "false", null, "true")
            check(raw.map(SdkSeatAdapter::interpretReported) == listOf(SeatPresence.REPORTED_EMPTY,
                SeatPresence.POSSIBLY_OCCUPIED, SeatPresence.UNKNOWN, SeatPresence.REPORTED_EMPTY))
            seats.zip(raw).forEach { (seat, value) ->
                store.add(LogEvent(190, if (value == null) EventKind.UNKNOWN else EventKind.VEHICLE,
                    seat, key = "SEAT_$seat", value = value, quality = if (value == null) "미확인" else "확인",
                    receivedWall = 190, insertedWall = 201))
            }
            store.add(LogEvent(210, EventKind.HUMAN, "운전석 직접 확인", key = "seat-check:ROW_1_LEFT", value = "true"))
            store.add(LogEvent(210, EventKind.INPUT, "중상", key = "injury:ROW_1_LEFT", value = "중상"))
            store.add(LogEvent(220, EventKind.VEHICLE, "ROW_1_LEFT", key = "SEAT_ROW_1_LEFT", value = "false"))
            LocalAction.entries.forEach { store.add(LogEvent(225, EventKind.HANDOFF, it.label, value = it.name)) }
            store.flush()
            val restored = IncidentLog(file)
            check(restored.id == store.id && restored.startedWall == 200L)
            check(restored.all().first().wall == 190L && restored.all().first().insertedWall == 201L)
            check(restored.all().filter { it.wall == 210L }.map { it.kind } == listOf(EventKind.HUMAN, EventKind.INPUT))
            check(restored.fact("seat-check:ROW_1_LEFT")?.wall == 210L && restored.fact("SEAT_ROW_1_LEFT")?.value == "false")
            check(restored.fact("SEAT_ROW_2_LEFT")?.kind == EventKind.UNKNOWN)
            check(restored.all().count { it.kind == EventKind.HANDOFF } == LocalAction.entries.size)
            check(LocalAction.DIAL_REQUESTED.label.contains("미확인") && LocalAction.SMS_DRAFT_REQUESTED.label.contains("미확인") &&
                LocalAction.QR_DISPLAYED.label.contains("미확인"))
            repeat(90) { restored.add(LogEvent(300L + it, EventKind.SYSTEM, "테스트 $it")) }
            restored.flush()
            val bounded = IncidentLog(file)
            check(bounded.all().size == 80 && bounded.fact("seat-check:ROW_1_LEFT")?.value == "true")
            check(bounded.clear() && !IncidentLog(file).active)
            file.writeText("invalid test payload")
            val broken = IncidentLog(file)
            check(broken.error != null && !broken.begin(300) && file.readText() == "invalid test payload")
            check(broken.clear() && !IncidentLog(file).active)
            results.appendLine("PASS P5.2 storage: four-seat mapping, unknown, pre-start reception, insertion, same-time ordering, independent confirmation, local actions, bounded history, restart, clear and malformed-file recovery")
        } finally {
            file.delete()
        }
    }

    private fun verifyHandoffProfiles(results: StringBuilder) {
        val facts = listOf(
            LogEvent(10, EventKind.VEHICLE, "기어", key = "GEAR", value = "P", quality = "확인된 신호"),
            LogEvent(11, EventKind.VEHICLE, "배터리", key = "EV_SOC", value = "75%", quality = "일부 확인"),
            LogEvent(12, EventKind.UNKNOWN, "속도", key = "SPEED", quality = "오래된 신호"),
            LogEvent(13, EventKind.HUMAN, "운전석 · 직접 확인", key = "seat-check:ROW_1_LEFT", value = "true"),
            LogEvent(14, EventKind.HUMAN, "조수석 · 확인 취소", key = "seat-check:ROW_1_RIGHT", value = "false"),
            LogEvent(15, EventKind.HANDOFF, LocalAction.QR_DISPLAYED.label, value = LocalAction.QR_DISPLAYED.name)
        )
        val payloads = HandoffProfile.entries.associateWith { profile ->
            PassportHandoff.prepare(profile, "11-12 10:00:00", "차량 간 사고", "경미한 부상 1명",
                facts, facts, Long::toString).also {
                check(it.encodable && it.bytes == it.text.toByteArray(Charsets.UTF_8).size)
                check(QrRenderer.selfCheck(checkNotNull(QrRenderer.encode(it.text)), it.text))
                check(it.text.contains("수신 여부 미확인") && it.text.contains("경미한 부상 1명"))
            }.text
        }
        val field = payloads.getValue(HandoffProfile.FIELD)
        check(field.contains("좌석(직접 확인): 운전석") && field.contains("위치(입력): 기록 없음"))
        check(!field.contains("조수석") && !field.contains("배터리") && !field.contains("최근 앱 동작"))
        val insurance = payloads.getValue(HandoffProfile.INSURANCE)
        check(insurance.contains("기록 시작(기기)") && insurance.contains("배터리(차량): 75% · 일부 확인") &&
            insurance.contains("속도(차량): 오래된 신호") && !insurance.contains("운전석"))
        check(payloads.getValue(HandoffProfile.PERSONAL).contains(LocalAction.QR_DISPLAYED.label))
        val missing = PassportHandoff.prepare(HandoffProfile.FIELD, "미확인", "미입력", null, emptyList(), emptyList(), Long::toString)
        check(missing.text.contains("기어(차량): 미확인") && missing.text.contains("부상(입력): 미입력"))
        val base = PassportHandoff.encode(HandoffProfile.FIELD, listOf(PassportHandoff.Field("필수", "한글")))
        val padding = "a".repeat(IncidentSummary.MAX_BYTES - base.bytes)
        val boundary = PassportHandoff.encode(HandoffProfile.FIELD, listOf(PassportHandoff.Field("필수", "한글$padding")))
        check(boundary.encodable && boundary.bytes == IncidentSummary.MAX_BYTES)
        check(QrRenderer.selfCheck(checkNotNull(QrRenderer.encode(boundary.text)), boundary.text))
        val overflow = PassportHandoff.encode(HandoffProfile.FIELD,
            listOf(PassportHandoff.Field("필수", "한글${padding}가")))
        check(!overflow.encodable && overflow.text.contains("한글${padding}가"))
        val drop = PassportHandoff.encode(HandoffProfile.FIELD, listOf(
            PassportHandoff.Field("필수", "한글"), PassportHandoff.Field("우선", "남음", 1),
            PassportHandoff.Field("선택1", "가".repeat(240), 3), PassportHandoff.Field("선택2", "나".repeat(240), 3)))
        check(drop.encodable && drop.omitted == listOf("선택2", "선택1") && drop.text.contains("우선: 남음"))
        // FORGE: user-typed location (no permission, no coordinates)
        val place = "경부고속도로 서울 방향 123.4km 갓길 · 졸음쉼터 직전"
        HandoffProfile.entries.forEach { pr ->
            val p = PassportHandoff.prepare(pr, "11-12 10:00:00", "차량 간 사고", "경미한 부상 1명", facts, facts, Long::toString, place)
            check(p.encodable && p.bytes <= IncidentSummary.MAX_BYTES) { "$pr ${p.bytes}" }
            check(p.text.contains("위치(입력): $place") || p.omitted.contains("위치(입력)")) { "$pr ${p.text}" }
            if (pr != HandoffProfile.PERSONAL) check(p.text.contains("위치(입력): $place")) { "$pr must keep location" }
            check(QrRenderer.selfCheck(checkNotNull(QrRenderer.encode(p.text)), p.text))
        }
        val longPlace = "가".repeat(60)
        HandoffProfile.entries.forEach { pr ->
            val p = PassportHandoff.prepare(pr, "11-12 10:00:00", "차량 간 사고", "경미한 부상 1명", facts, facts, Long::toString, longPlace)
            check(p.encodable && p.bytes <= IncidentSummary.MAX_BYTES) { "$pr long ${p.bytes}" }
            if (pr == HandoffProfile.FIELD) check(p.text.contains(longPlace)) { "field keeps 60-char location" }
        }
        check(PassportHandoff.prepare(HandoffProfile.FIELD, "미확인", "미입력", null, emptyList(), emptyList(), Long::toString, "   ").text.contains("위치(입력): 기록 없음"))
        results.appendLine("PASS FORGE location: typed location shown as (입력) in all profiles (personal may omit under budget), blank stays 기록 없음, 60-char Korean within 560 B, QR roundtrip")
        results.appendLine("PASS P5.2 handoff: three policies, unknown/partial/stale, Korean UTF-8, mixed confirmation, exact 560 B QR, deterministic omissions, required-field overflow blocked")
    }
}
