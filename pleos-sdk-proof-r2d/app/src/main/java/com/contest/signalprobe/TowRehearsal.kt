package com.contest.signalprobe

/** An in-memory practice conversation. It has no dependency on vehicle readings or contact actions. */
internal class TowRehearsal(val challenge: Boolean = false) {
    enum class Step { LOCATION, MOBILITY, READBACK, DONE }
    enum class MobilityAnswer(val label: String) {
        CAN_MOVE("옮길 수 있음"), CANNOT_MOVE("옮길 수 없음"), UNKNOWN("모르겠어요")
    }
    data class Place(val label: String, val source: String) {
        companion object {
            val SAMPLE = Place("가상도로 서울 방향 123 km 표지", "연습 예시")
            val UNKNOWN = Place("위치 미확인", "사용자 답변")
            fun typed(text: String) = Place(text, "직접 입력")
        }
    }

    var step = Step.LOCATION
        private set
    var place: Place? = null
        private set
    var mobility: MobilityAnswer? = null
        private set
    private var readbackCorrected = false
    var showMismatchHint = false
        private set
    val hasReadbackMismatch: Boolean get() = challenge && place == Place.SAMPLE && !readbackCorrected
    val readbackPlace: String? get() =
        if (hasReadbackMismatch) "가상도로 서울 방향 132 km 표지" else place?.label

    fun choosePlace(answer: Place) {
        if (step != Step.LOCATION || (challenge && answer != Place.SAMPLE)) return
        place = answer
        step = if (mobility == null) Step.MOBILITY else Step.READBACK
    }

    fun chooseMobility(answer: MobilityAnswer) {
        if (step != Step.MOBILITY || place == null) return
        mobility = answer
        step = Step.READBACK
    }

    fun editPlace() {
        if (step != Step.READBACK && step != Step.MOBILITY) return
        place = null
        readbackCorrected = false
        showMismatchHint = false
        step = Step.LOCATION
    }

    fun editMobility() {
        if (step != Step.READBACK) return
        mobility = null
        step = Step.MOBILITY
    }

    fun confirmReadback() {
        if (step != Step.READBACK || place == null || mobility == null) return
        if (hasReadbackMismatch) showMismatchHint = true else step = Step.DONE
    }

    fun correctReadback() {
        if (step != Step.READBACK || !hasReadbackMismatch) return
        readbackCorrected = true
        showMismatchHint = false
    }
}
