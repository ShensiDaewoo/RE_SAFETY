package com.contest.signalprobe

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

// Retains the P5.1 event kinds and event-time/sequence ordering.
internal enum class EventKind(val tag: String) {
    VEHICLE("차량 관측"), HUMAN("직접 확인"), INPUT("사용자 입력"), UNKNOWN("미확인"),
    HANDOFF("앱 동작"), SYSTEM("기록")
}

internal enum class LocalAction(val label: String) {
    DIAL_REQUESTED("전화 화면 열기 요청 · 통화 여부 미확인"),
    SMS_DRAFT_REQUESTED("문자 초안 화면 열기 요청 · 발송 여부 미확인"),
    QR_GENERATED("QR 생성"),
    QR_DISPLAYED("QR 표시 · 스캔 여부 미확인"),
    PROTECT_REQUESTED("보호 모드 실행 요청"),
    PROTECT_RESULT("보호 모드 결과 · 차량이 다시 알려 준 값 기준"),
    PROTECT_UNDONE("보호 모드 되돌리기 요청"),
}

internal data class LogEvent(
    val wall: Long,
    val kind: EventKind,
    val text: String,
    val seq: Long = 0,
    val key: String? = null,
    val value: String? = null,
    val quality: String? = null,
    val source: String? = null,
    val receivedWall: Long? = null,
    val insertedWall: Long = wall,
) {
    fun sameFact(other: LogEvent) = kind == other.kind && value == other.value &&
        quality == other.quality && source == other.source && text == other.text
}

/** One local record. Accessed on the activity thread. Latest facts survive history rotation. */
internal class IncidentLog(file: File) {
    private val disk = AtomicFile(file)
    private val events = ArrayList<LogEvent>()
    private val latest = LinkedHashMap<String, LogEvent>()
    private var nextSeq = 0L
    private var dirty = false
    var id: String? = null
        private set
    var startedWall: Long? = null
        private set
    var error: String? = null
        private set
    private var unreadable = false
    val active get() = id != null
    val revision get() = nextSeq

    init {
        try {
            val json = disk.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            check(json.getInt("version") == 1)
            id = json.optionalString("id")
            startedWall = json.optionalLong("started")
            nextSeq = json.getLong("next")
            events.addAll(readEvents(json.getJSONArray("events")))
            readEvents(json.getJSONArray("latest")).forEach { event -> event.key?.let { latest[it] = event } }
            check((id == null) == (startedWall == null))
        } catch (_: FileNotFoundException) {
            // No record has been created.
        } catch (_: Exception) {
            resetMemory()
            unreadable = true
            error = "저장된 기록을 읽을 수 없어요. 기록 화면에서 지울 수 있어요."
        }
    }

    fun begin(now: Long): Boolean {
        if (unreadable) return false
        if (!active) {
            id = UUID.randomUUID().toString()
            startedWall = now
            add(LogEvent(now, EventKind.SYSTEM, "기록 시작"))
        }
        return true
    }

    fun add(event: LogEvent) {
        if (!active) return
        val key = event.key
        if (key != null && latest[key]?.sameFact(event) == true) return
        val stored = event.copy(seq = nextSeq++)
        if (events.size == MAX_EVENTS) events.removeAt(0)
        events += stored
        if (key != null) latest[key] = stored
        dirty = true
    }

    fun all(): List<LogEvent> = events.sortedWith(compareBy({ it.wall }, { it.seq }))
    fun fact(key: String): LogEvent? = latest[key]
    fun facts(): List<LogEvent> = latest.values.toList()

    fun clear(): Boolean {
        if (!write(state(empty = true))) return false
        resetMemory()
        unreadable = false
        dirty = false
        return true
    }

    fun flush() {
        if (dirty && write(state())) dirty = false
    }

    private fun resetMemory() {
        id = null
        startedWall = null
        nextSeq = 0
        events.clear()
        latest.clear()
    }

    private fun state(empty: Boolean = false) = JSONObject()
        .put("version", 1)
        .put("id", if (empty) JSONObject.NULL else id ?: JSONObject.NULL)
        .put("started", if (empty) JSONObject.NULL else startedWall ?: JSONObject.NULL)
        .put("next", if (empty) 0L else nextSeq)
        .put("events", encodeEvents(if (empty) emptyList() else events))
        .put("latest", encodeEvents(if (empty) emptyList() else latest.values))

    private fun write(json: JSONObject): Boolean {
        return try {
            val output = disk.startWrite()
            try {
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                disk.finishWrite(output)
            } catch (e: Exception) {
                disk.failWrite(output)
                throw e
            }
            error = null
            true
        } catch (_: Exception) {
            error = "사고 기록을 저장하지 못했어요."
            false
        }
    }

    private fun encodeEvents(list: Collection<LogEvent>) = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("wall", it.wall).put("inserted", it.insertedWall)
                .put("kind", it.kind.name).put("text", it.text).put("seq", it.seq)
                .put("key", it.key).put("value", it.value).put("quality", it.quality)
                .put("source", it.source).put("received", it.receivedWall))
        }
    }

    private fun readEvents(list: JSONArray) = (0 until list.length()).map { index ->
        val item = list.getJSONObject(index)
        LogEvent(item.getLong("wall"), EventKind.valueOf(item.getString("kind")), item.getString("text"),
            item.getLong("seq"), item.optionalString("key"), item.optionalString("value"),
            item.optionalString("quality"), item.optionalString("source"), item.optionalLong("received"),
            item.getLong("inserted"))
    }

    private fun JSONObject.optionalString(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.optionalLong(key: String) = if (isNull(key)) null else getLong(key)

    private companion object { const val MAX_EVENTS = 80 }
}
