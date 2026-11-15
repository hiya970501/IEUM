package com.example.afterglow.net

object HealthDisplayTime {
    fun age(ageMillis: Long?): String {
        if(ageMillis==null || ageMillis<0) return "시각 확인 필요"
        val minutes=ageMillis/60_000
        return if(minutes<60) "${minutes}분 전" else "${minutes/60}시간 전"
    }
}
