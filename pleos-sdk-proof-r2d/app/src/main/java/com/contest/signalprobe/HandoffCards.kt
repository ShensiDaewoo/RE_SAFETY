package com.contest.signalprobe

import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Provenance of every line shown on a handoff card. */
enum class Src(val tag: String) { VEHICLE("차량 보고"), HUMAN("직접 확인"), USER("입력"), APP("안내") }

data class CardLine(val label: String, val text: String, val src: Src)

data class SeatInfo(val label: String, val vehicle: SeatPresence, val humanConfirmed: Boolean, val note: String?)

data class CardInput(
    val nowWall: Long,
    val mobility: MobilityState,
    val seats: List<SeatInfo>,
    val doorsText: String,
    val energyText: String,
    val category: String?,
    val injured: String?,
)

/**
 * PHASE 5 stage 1: one incident record, different views per recipient (119 / 112 / insurer / tow).
 * Pure templates, no LLM, no network. Unknown values are written as unknown, never guessed.
 * The app only opens the dialer / SMS composer; a person presses call / send.
 */
object HandoffCards {
    const val NUM_119 = "119"
    const val NUM_112 = "112"
    /** Korea Expressway Corporation emergency towing (secondary sources, see PHASE3_5_CONTEST_EVIDENCE). */
    const val NUM_EXPRESSWAY_TOW = "1588-2504"

    val CATEGORIES = listOf("차량 간", "보행자 관련", "단독", "고장", "물적 피해", "모르겠음")
    const val MINOR_INJURY = "경미한 부상"
    const val SEVERE_INJURY = "중상"
    val INJURED = listOf(MINOR_INJURY, SEVERE_INJURY)
    const val PEDESTRIAN_FIRST = "보행자 관련: 다친 사람 구호와 112·119 연락이 먼저입니다."
    const val EXIT_SIDE = "내릴 때는 가능하면 차로 반대쪽 문으로 내리세요."

    private fun time(i: CardInput) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(i.nowWall))

    fun location() = CardLine("위치", "앱은 위치를 읽지 않습니다 — 고속도로 이정표(km)·진행 방향·근처 출구나 건물을 말하세요", Src.APP)

    fun injuredLine(i: CardInput) = CardLine("다친 사람", i.injured ?: "아직 입력 안 함 (모르면 '모름'이라고 말하세요)", Src.USER)

    fun categoryLine(i: CardInput) = CardLine("사고 유형", i.category ?: "입력 안 함", Src.USER)

    /** Seats worth mentioning to 119: sensor says possibly occupied / no signal, or a pre-set note exists. */
    fun seatsLine(i: CardInput): CardLine {
        val parts = i.seats.filter { it.vehicle != SeatPresence.REPORTED_EMPTY || it.note != null }.map { s ->
            val sensor = when (s.vehicle) {
                SeatPresence.POSSIBLY_OCCUPIED -> "센서 착석 가능성"
                SeatPresence.UNKNOWN -> "센서 신호 없음"
                SeatPresence.REPORTED_EMPTY -> "센서 빈 좌석 보고"
            }
            "${s.label}: $sensor · 사람 ${if (s.humanConfirmed) "확인함" else "확인 안 함"}" + (s.note?.let { " · 메모(입력) $it" } ?: "")
        }
        return CardLine("좌석 상태",
            if (parts.isEmpty()) "모두 빈 좌석으로 보고 · 직접 확인 필요" else parts.joinToString("\n"), Src.VEHICLE)
    }

    fun vehicleLine(i: CardInput) = CardLine("차량 상태", "${i.mobility.note}\n문·트렁크 · ${i.doorsText}", Src.VEHICLE)

    fun card119(i: CardInput): List<CardLine> =
        listOfNotNull(location(), injuredLine(i), seatsLine(i).takeIf { i.seats.isNotEmpty() }, vehicleLine(i), categoryLine(i))

    fun card112(i: CardInput): List<CardLine> =
        listOf(location(), categoryLine(i), injuredLine(i), CardLine("시각", "${time(i)} (기기 시계)", Src.APP), vehicleLine(i))

    fun cardInsurance(i: CardInput): List<CardLine> = listOf(
        CardLine("시각", "${time(i)} (기기 시계)", Src.APP),
        categoryLine(i),
        vehicleLine(i),
        CardLine("에너지", i.energyText, Src.VEHICLE),
        CardLine("과실", "지금 정하지 않아도 됩니다. 판단은 보험사·경찰이 합니다.", Src.APP),
        CardLine("사진", "내 차 · 상대 차 · 번호판 · 도로 전경 (안전한 곳에서만)", Src.APP),
    )

    fun cardTow(i: CardInput): List<CardLine> = listOf(
        location(),
        CardLine("에너지", i.energyText, Src.VEHICLE),
        vehicleLine(i),
        categoryLine(i),
    )

    /** Safety lines pinned above every card. */
    fun pinned(i: CardInput): List<String> = buildList {
        if (i.category == "보행자 관련") add(PEDESTRIAN_FIRST)
        add(EXIT_SIDE)
    }

    /** Short SMS draft for 119. Location first because SMS reports are not known to carry location automatically. */
    fun smsBody119(i: CardInput): String = buildString {
        append("[119 문자 초안 · 보내기 전 확인]\n")
        append("위치: (여기에 직접 적어 주세요)\n")
        append("다친 사람: ${i.injured ?: "모름"}\n")
        val seats = i.seats.filter { it.vehicle == SeatPresence.POSSIBLY_OCCUPIED || it.note != null }
            .joinToString(", ") { s -> s.label + (s.note?.let { "(메모 $it)" } ?: "") }
        if (i.seats.isNotEmpty()) append("차에 남았을 수 있는 자리(센서·메모): ${seats.ifEmpty { "보고 없음" }}\n")
        append("차: ${i.mobility.note}\n")
        append("유형: ${i.category ?: "미입력"}")
    }

    fun digits(number: String) = number.filter { it.isDigit() }

    /** Opens the dialer with the number filled in. Never ACTION_CALL: emergency numbers must be dialled by a person. */
    fun dialIntent(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + digits(number)))

    /** Opens the SMS composer with a draft. Sending is the person's decision. */
    fun smsIntent(number: String, body: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + digits(number))).putExtra("sms_body", body)

    /** Phone numbers entered by the user before driving (insurer emergency line). */
    fun validPhone(s: String): Boolean = s.matches(Regex("^[0-9-]{3,20}$")) && digits(s).length >= 3
}
