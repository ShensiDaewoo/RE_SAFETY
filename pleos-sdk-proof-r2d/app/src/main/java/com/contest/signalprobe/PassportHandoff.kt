package com.contest.signalprobe

internal enum class HandoffProfile(val label: String, val description: String) {
    FIELD("현장 도움", "부상 입력 · 직접 확인 · 위치 기록 여부"),
    INSURANCE("보험·사고 정리", "사고 유형 · 기록 시각 · 차량 상태"),
    PERSONAL("개인 보관", "출처별 정보 · 최근 변경과 앱 동작"),
}

/** v1 is separate from the existing D5 v2 summary. The rendered text is the exact QR payload. */
internal object PassportHandoff {
    data class Field(val title: String, val value: String, val dropPriority: Int = 0)
    data class Payload(val text: String, val bytes: Int, val omitted: List<String>, val encodable: Boolean)

    fun prepare(profile: HandoffProfile, started: String, category: String, injuries: String?,
                facts: List<LogEvent>, history: List<LogEvent>, time: (Long) -> String,
                location: String? = null, vehicleLocation: String? = null,
                handover: String? = null): Payload {
        // FORGE: location is only what the user typed (landmark, exit, direction). Never device coordinates, never guessed.
        val place = location?.takeIf { it.isNotBlank() }
        // D plan F2: fresh vehicle coordinates (Pleos FusedLocation) only; absent or stale -> no line at all.
        val car = vehicleLocation?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        fun vehicle(key: String) = facts.firstOrNull { it.key == key }?.let {
            if (it.value == null) it.quality ?: "미확인" else
                listOfNotNull(it.value, it.quality?.takeUnless { quality -> quality == "확인된 신호" }).joinToString(" · ")
        } ?: "미확인"

        val confirmed = facts.filter { it.kind == EventKind.HUMAN && it.key?.startsWith("seat-check:") == true && it.value == "true" }
        val human = if (confirmed.isEmpty()) "기록 없음" else confirmed.joinToString(" · ") { it.text.substringBefore(" · ") }
        val checked = facts.count { it.kind == EventKind.HUMAN && it.key?.startsWith("guide:") == true && it.value == "true" }
        val unknown = facts.count { it.kind == EventKind.UNKNOWN }
        val lastAction = history.lastOrNull { it.kind == EventKind.HANDOFF }
        val lastObservation = history.lastOrNull { it.kind == EventKind.VEHICLE || it.kind == EventKind.UNKNOWN }
        val fields = when (profile) {
            HandoffProfile.FIELD -> listOf<Field>(
                Field("부상(입력)", injuries ?: "미입력"),
                Field("좌석(직접 확인)", human),
                *car.map { Field("위치(차량)", it) }.toTypedArray(),
                Field("위치(입력)", place ?: "기록 없음"),
                Field("기어(차량)", vehicle("GEAR"), 1),
                Field("사고 유형(입력)", category, 2),
                Field("트렁크(차량)", vehicle("DOOR_REAR"), 3),
            )
            HandoffProfile.INSURANCE -> listOf(
                Field("기록 시작(기기)", started),
                Field("사고 유형(입력)", category),
                Field("부상(입력)", injuries ?: "미입력"),
                *car.map { Field("위치(차량)", it, 1) }.toTypedArray(),
                Field("위치(입력)", place ?: "미입력", 2),
                Field("기어(차량)", vehicle("GEAR")),
                Field("속도(차량)", vehicle("SPEED"), 1),
                Field("사고 발생 시각", "미확인"),
                Field("배터리(차량)", vehicle("EV_SOC"), 3),
                Field("트렁크(차량)", vehicle("DOOR_REAR"), 4),
                // D plan F6: pre-departure snapshot (VIN tail, odometer, range, battery, doors). Dropped first if full.
                *listOfNotNull(handover?.takeIf { it.isNotBlank() }).map { Field("출발 전(차량)", it, 5) }.toTypedArray(),
            )
            HandoffProfile.PERSONAL -> listOf(
                Field("기록 시작(기기)", started),
                Field("사고 유형(입력)", category),
                Field("부상(입력)", injuries ?: "미입력"),
                Field("좌석(직접 확인)", human),
                *car.map { Field("위치(차량)", it, 2) }.toTypedArray(),
                Field("위치(입력)", place ?: "미입력", 2),
                Field("행동(직접 확인)", "${checked}/4", 1),
                Field("기어(차량)", vehicle("GEAR"), 2),
                Field("미확인 차량 항목", "${unknown}개", 2),
                Field("최근 차량 기록", lastObservation?.let {
                    "${time(it.wall)} · ${it.text} · ${it.value ?: it.quality ?: "미확인"}"
                } ?: "없음", 4),
                Field("최근 앱 동작", lastAction?.text ?: "없음", 3),
            )
        }
        return encode(profile, fields)
    }

    fun encode(profile: HandoffProfile, fields: List<Field>): Payload {
        val kept = fields.toMutableList()
        val omitted = mutableListOf<String>()
        fun render() = buildString {
            append("[사고 기록 v1] ").append(profile.label)
            kept.forEach { append('\n').append(it.title).append(": ").append(it.value) }
            append("\n수신 여부 미확인")
        }
        while (true) {
            val text = render()
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (bytes <= IncidentSummary.MAX_BYTES) return Payload(text, bytes, omitted, true)
            val victim = kept.withIndex().filter { it.value.dropPriority > 0 }
                .maxWithOrNull(compareBy({ it.value.dropPriority }, { it.index }))
                ?: return Payload(text, bytes, omitted, false)
            omitted += kept.removeAt(victim.index).title
        }
    }
}
