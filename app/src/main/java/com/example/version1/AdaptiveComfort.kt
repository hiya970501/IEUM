package com.example.version1

import kotlin.math.abs
import kotlin.math.exp

enum class ComfortMode { LEARNING, AUTOMATIC }
enum class ComfortPhase { ARRIVAL, SETTLED }

/** Actual cabin temperature is never the HVAC target. Unknowns remain null. */
data class ComfortContext(
    val profile: String, val vehicle: String, val activity: String?, val wet: Boolean,
    val phase: ComfortPhase, val outdoorC: Float? = null, val humidity: Float? = null,
    val radiation: Float? = null, val cabinC: Float? = null, val bpm: Int? = null,
    val minutesAfterExercise: Int? = null, val weatherTime: Long? = null
) {
    val climate: String get() = when {
        outdoorC == null -> "unknown"
        outdoorC < 12 -> "cold"
        outdoorC >= 26 -> "hot"
        else -> "mild"
    }
    val bucket: String get() = "$vehicle:$activity:$wet:${phase.name}:$climate"
    fun valid(): Boolean = profile.length in 1..64 && vehicle.length in 1..80 &&
        listOf(outdoorC, cabinC).all { it == null || it.isFinite() && it in -60f..85f } &&
        (humidity == null || humidity.isFinite() && humidity in 0f..100f) &&
        (radiation == null || radiation.isFinite() && radiation in 0f..1500f) &&
        (bpm == null || bpm in 40..220) && (minutesAfterExercise == null || minutesAfterExercise in 0..1440)
}

data class LearnedSetting(val temperature: Float, val fan: Int, val heat: Int?, val ventilation: Int?,
                          val steeringHeat: Int? = null) {
    fun valid() = temperature.isFinite() && temperature in 16f..32f && fan in 0..12 &&
        (heat == null || heat in -3..3) && (ventilation == null || ventilation in 0..3) &&
        !((heat ?: 0) != 0 && (ventilation ?: 0) > 0) && (steeringHeat == null || steeringHeat in 0..3)
    fun asPlan() = RecoveryPlan(temperature, fan, (heat ?: 0).coerceAtLeast(0), ventilation ?: 0,
        "내가 선택한 공조", "유사 조건에서 만족 확인한 설정",
        controlsSeat = heat != null && ventilation != null,
        seatCooling = (-(heat ?: 0)).coerceAtLeast(0), steeringHeat = steeringHeat)
}

data class ComfortCase(val session: String, val at: Long, val context: ComfortContext,
                       val setting: LearnedSetting, val satisfied: Boolean, val rating: Int? = null) {
    fun validFeedback() = rating == null || (rating in 1..5 && satisfied == (rating >= 4))
}
data class AdaptiveDecision(val setting: LearnedSetting?, val sessions: Int,
                            val reason: String, val spreadC: Float = 0f)

/** Bounded case retrieval, not a trained physiological model. Product distances need field validation. */
object AdaptiveComfort {
    const val MAX_CANDIDATES = 96
    const val MAX_NEIGHBOURS = 12
    fun similarity(a: ComfortContext, b: ComfortContext): Double {
        if (!a.valid() || !b.valid() || a.profile != b.profile || a.vehicle != b.vehicle ||
            a.wet != b.wet || a.phase != b.phase) return 0.0
        var distance = 0.0
        var evidence = 0
        fun compare(x: Float?, y: Float?, scale: Float, weight: Double) {
            if (x != null && y != null) { distance += (abs(x - y) / scale) * weight; evidence++ }
            else distance += weight * 0.45
        }
        compare(a.outdoorC, b.outdoorC, 8f, 1.2)
        compare(a.cabinC, b.cabinC, 6f, 1.5)
        compare(a.humidity, b.humidity, 30f, .4)
        compare(a.radiation, b.radiation, 500f, .4)
        compare(a.bpm?.toFloat(), b.bpm?.toFloat(), 35f, .15)
        compare(a.minutesAfterExercise?.toFloat(), b.minutesAfterExercise?.toFloat(), 20f, .4)
        if (a.activity != null && b.activity != null && a.activity != b.activity) distance += .35
        // Neither an exercise name nor a pulse alone can justify climate matching.
        if (evidence < 2 || (a.outdoorC == null && a.cabinC == null)) return 0.0
        return exp(-distance)
    }
    fun recommend(context: ComfortContext, cases: List<ComfortCase>, now: Long): AdaptiveDecision {
        if (!context.valid()) return AdaptiveDecision(null, 0, "환경 값 확인 필요 · 기본 선호 유지")
        val valid = cases.take(MAX_CANDIDATES).filter { it.validFeedback() && it.at in 1..now && it.context.valid() && it.setting.valid() }
        val disliked = valid.filter { it.rating in 1..2 && similarity(context, it.context) >= .22 }
        val neighbours = valid.asSequence()
            .filter { it.satisfied && disliked.none { bad -> bad.at >= it.at &&
                abs(bad.setting.temperature-it.setting.temperature) <= .5f && abs(bad.setting.fan-it.setting.fan) <= 1 &&
                bad.setting.heat == it.setting.heat && bad.setting.ventilation == it.setting.ventilation &&
                bad.setting.steeringHeat == it.setting.steeringHeat } }
            .map { it to similarity(context, it.context) }.filter { it.second >= .22 }
            .sortedByDescending { it.second }.distinctBy { it.first.session }.take(MAX_NEIGHBOURS)
            .map { (case, similarity) ->
                val ageDays = (now - case.at) / 86_400_000.0
                case to similarity * (.5 + .5 * exp(-ageDays / 90.0)) * (if (case.rating == 5) 1.15 else 1.0)
            }.toList()
        if (neighbours.size < 3) return AdaptiveDecision(null, neighbours.size,
            if (disliked.isNotEmpty()) "낮은 평가 설정 제외 · 만족 기록 ${neighbours.size}/3회 · 기본 선호 유지"
            else "유사 조건 만족 기록 ${neighbours.size}/3회 · 기본 선호 유지")
        val temperatures = neighbours.map { it.first.setting.temperature }
        val spread = temperatures.max() - temperatures.min()
        if (spread > 2f) return AdaptiveDecision(null, neighbours.size,
            "유사 조건의 선택이 ${spread}°C 차이 · 자동 추정 보류", spread)
        val fans = neighbours.map { it.first.setting.fan }
        val seatChoices = neighbours.groupingBy { (case, _) ->
            when { case.setting.heat == null || case.setting.ventilation == null -> "unknown"
                case.setting.heat > 0 -> "heat"; case.setting.heat < 0 -> "cool"; case.setting.ventilation > 0 -> "vent"; else -> "off" }
        }.eachCount()
        val steeringChoices = neighbours.groupingBy { it.first.setting.steeringHeat }.eachCount()
        if (fans.max() - fans.min() > 2 || seatChoices.values.max().toDouble() / neighbours.size < .75 ||
            steeringChoices.values.max().toDouble() / neighbours.size < .75)
            return AdaptiveDecision(null, neighbours.size, "풍량·좌석 선호가 일치하지 않아 자동 추정 보류", spread)
        // Choose one real setting, avoiding a synthetic combination of incompatible preferences.
        val selected = neighbours.minBy { candidate -> neighbours.sumOf { other ->
            val a = candidate.first.setting; val b = other.first.setting
            other.second * (abs(a.temperature - b.temperature) + .3 * abs(a.fan - b.fan) +
                .5 * abs((a.heat ?: 0) - (b.heat ?: 0)) + .5 * abs((a.ventilation ?: 0) - (b.ventilation ?: 0)) +
                .3 * abs((a.steeringHeat ?: 0) - (b.steeringHeat ?: 0)))
        } }.first.setting
        return AdaptiveDecision(selected, neighbours.size,
            "날씨·실내·운동 상태가 유사한 ${neighbours.size}회 만족 기록 참고", spread)
    }
}

/** Constant-size trend buffer; duplicates and stale/non-watch values never contribute. */
class PulseTrend {
    private val times = LongArray(60)
    private val values = IntArray(60)
    private var count = 0
    private var cursor = 0
    private var last = 0L
    fun add(bpm: Int, measuredAt: Long, now: Long) {
        if (bpm !in 40..220 || measuredAt <= last || now - measuredAt !in 0..29_999) return
        times[cursor] = measuredAt; values[cursor] = bpm; cursor = (cursor + 1) % 60
        count = (count + 1).coerceAtMost(60); last = measuredAt
    }
    fun clear() { count = 0; cursor = 0; last = 0; times.fill(0); values.fill(0) }
}
