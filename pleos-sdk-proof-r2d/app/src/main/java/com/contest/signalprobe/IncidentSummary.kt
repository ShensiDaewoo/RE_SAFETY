package com.contest.signalprobe

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SeatLine(
    val label: String,
    val vehicle: SeatPresence,
    val userConfirmed: Boolean,
    val changedAfterConfirm: Boolean,
)

data class SummaryInput(
    val nowWall: Long,
    val mobility: MobilityState,
    val seats: List<SeatLine>,
    val doorsText: String,
    val wiperText: String,
    val lowBeamText: String,
    val userCategory: String?,
    val guideDone: Int,
    val guideTotal: Int,
    val includeSeats: Boolean,
    val includeState: Boolean,
    /** RC4: "배터리 4%" / "연료 12%" / "신호없음". Vehicle report only. */
    val energyText: String = "신호없음",
    /** RC4: user-entered seat notes set before driving, e.g. "뒷줄오른쪽 영유아". Empty = none. */
    val seatNotes: List<String> = emptyList(),
    val includeNotes: Boolean = true,
    /** Stage 1: user-entered "있음/없음/모름". Never inferred from sensors. */
    val injured: String? = null,
)

/**
 * D5 payload v2: human-readable UTF-8 text. No location, name, phone or plate. Seat notes are optional,
 * user-entered, stored only on this device and excluded with one toggle.
 * Vehicle observations, user confirmations and user-provided input are labelled separately.
 * Missing values are written as "신호 없음"/"미확인"; nothing is estimated.
 */
object IncidentSummary {
    // 600 -> 560 (stage 1): a 599-byte payload failed the in-app decode check; phones were tested at 568 bytes.
    const val MAX_BYTES = 560

    data class Result(val text: String, val bytes: Int, val dropped: List<String>)

    private class Line(val text: String, val droppable: Int) // higher droppable = dropped first

    fun build(i: SummaryInput): Result {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(i.nowWall))
        val lines = mutableListOf(Line("[RE:SAFETY] 요약 v2", 0), Line("시각: $time (기기 시계)", 0))
        if (i.userCategory != null || i.injured != null)
            lines += Line("사용자 입력: 유형 ${i.userCategory ?: "미선택"} · 다친 사람 ${i.injured ?: "미입력"}", 0)
        lines += Line("사용자 확인: 행동요령 ${i.guideDone}/${i.guideTotal}", 1)
        if (i.includeState) {
            lines += Line("이동상태(차량): ${i.mobility.note}", 0)
        }
        if (i.includeSeats) {
            val confirmed = i.seats.joinToString(" ") { "${it.label}${if (it.userConfirmed) "✓" else "미확인"}${if (it.changedAfterConfirm) "(변동)" else ""}" }
            lines += Line("좌석 확인(사람): $confirmed", 0)
            // grouped by reported state to save bytes: "착석가능·불확실 앞왼 앞오 / 빈좌석보고 뒤왼"
            val vehicle = i.seats.groupBy { it.vehicle }.entries.sortedBy { it.key.ordinal }
                .joinToString(" / ") { (p, l) -> "${seatShort(p)} ${l.joinToString(" ") { it.label }}" }
            lines += Line("좌석 보고(차량): $vehicle", 1)
        }
        if (i.includeNotes && i.seatNotes.isNotEmpty()) {
            lines += Line("사전메모(입력): ${i.seatNotes.joinToString(" · ")}", 0)
        }
        if (i.includeState) {
            lines += Line("에너지(차량): ${i.energyText}", 2)
            lines += Line("문/트렁크(차량): ${i.doorsText}", 4)
            lines += Line("와이퍼(차량): ${i.wiperText} · 하향등(차량): ${i.lowBeamText}", 3)
        }
        lines += Line("위치: 미확인", 0)
        lines += Line("※ 차량보고·사람확인·입력 구분. 신고가 아님.", 0)
        val dropped = mutableListOf<String>()
        fun render() = lines.joinToString("\n") { it.text }
        while (render().toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            val victim = lines.filter { it.droppable > 0 }.maxByOrNull { it.droppable } ?: break
            lines.remove(victim); dropped += victim.text.substringBefore(':')
        }
        val text = render()
        return Result(text, text.toByteArray(Charsets.UTF_8).size, dropped)
    }

    private fun seatShort(p: SeatPresence) = when (p) {
        SeatPresence.REPORTED_EMPTY -> "빈좌석보고"
        SeatPresence.POSSIBLY_OCCUPIED -> "착석가능·불확실"
        SeatPresence.UNKNOWN -> "신호없음"
    }
}

/** D5 QR rendering with a machine self-check (decode == source). Phone-camera scan is a separate, manual test. */
object QrRenderer {
    /** Per-stage outcome of the last encode, e.g. "x7:fail(hybrid:NotFoundException,global:NotFoundException)>x7:ok(pure)". Never contains the QR text. */
    @Volatile var lastReport: String = ""

    /**
     * Same text, same data, error-correction level M (unchanged). The image is drawn with an exact whole-number pixel scale
     * per module. Root cause of the intermittent "QR not shown" (found by repeat tests): zxing's finder-pattern *detector*
     * rejects some perfectly valid QR images (about 1 in 75 random payloads offline, more for some real payloads), while
     * reading the modules directly (PURE_BARCODE) decodes every one of them. So the check below accepts an image when any
     * of three readers recovers exactly the source text.
     */
    fun encode(text: String, size: Int = 560): Bitmap? {
        val steps = StringBuilder()
        val bmp = encodeExact(text, size)
        if (bmp == null) { lastReport = "encodeNull"; return null }
        val how = decodeKind(bmp, text)
        steps.append("x${bmp.width}:").append(how)
        lastReport = steps.toString()
        return bmp
    }

    private fun encodeExact(text: String, size: Int): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        )
        val base = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints)   // modules + 2 x quiet zone, 1 px each
        val n = base.width
        val scale = maxOf(4, size / n)
        val px = IntArray(n * scale * n * scale) { idx ->
            val x = (idx % (n * scale)) / scale; val y = (idx / (n * scale)) / scale
            if (base.get(x, y)) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(px, n * scale, n * scale, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) { null }

    /**
     * Reads the rendered bitmap back; "ok(...)" only if the decoded text equals the source.
     * The failure reason is the exception class name or "mismatch" — never the text itself.
     */
    private fun decodeKind(bmp: Bitmap, expected: String): String {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val src = RGBLuminanceSource(w, h, px)
        val base = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8", DecodeHintType.TRY_HARDER to true)
        val pure = base + (DecodeHintType.PURE_BARCODE to true)
        val reasons = mutableListOf<String>()
        for ((name, make, hints) in listOf(
            Triple("hybrid", { BinaryBitmap(HybridBinarizer(src)) }, base),
            Triple("global", { BinaryBitmap(GlobalHistogramBinarizer(src)) }, base),
            Triple("pure", { BinaryBitmap(HybridBinarizer(src)) }, pure))) {
            try {
                val res = QRCodeReader().decode(make(), hints)
                if (res.text == expected) return "ok($name)"
                reasons += "$name:mismatch"
            } catch (e: Exception) { reasons += "$name:${e.javaClass.simpleName}" }
        }
        return "fail(" + reasons.joinToString(",") + ")"
    }

    /** True only if the bitmap decodes back to exactly the source text. */
    fun selfCheck(bmp: Bitmap, expected: String): Boolean = decodeKind(bmp, expected).startsWith("ok")
}
