package com.contest.signalprobe

internal enum class DBeltReport(val label: String) {
    WAITING("미조회"), READING("조회 중"), BUCKLED("체결 보고"), UNBUCKLED("미체결 보고"),
    UNSUPPORTED("지원 항목 없음"), NO_VALUE("신호 미확인"), DENIED("권한 거부"),
    ERROR("조회 오류"), TIMEOUT("응답 시간 초과"), STALE("이전 조회 · 다시 확인")
}
