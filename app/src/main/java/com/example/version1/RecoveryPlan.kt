package com.example.version1

/** minutes and demoBpm are sample values for a disconnected-watch demonstration. */
enum class ActivityKind(val title: String, val detail: String, val minutes: Int, val demoBpm: Int) {
    RUN("러닝", "강변을 달린 뒤", 42, 128),
    WATER("수중 활동", "물 밖으로 나온 뒤", 55, 102),
    HIKE("하이킹", "산길을 걸은 뒤", 86, 112)
}

data class Workout(val kind: ActivityKind, val bpm: Int, val wet: Boolean)
data class RecoveryPlan(val temperature: Float, val fan: Int, val heat: Int, val ventilation: Int,
                        val title: String, val reason: String, val durationSeconds: Int = 600,
                        val controlsSeat: Boolean = true, val seatCooling: Int = 0,
                        val steeringHeat: Int? = null) {
    companion object {
        /** Legacy entry point. Unknown thermal feeling never implies that a user is hot. */
        fun forWorkout(workout: Workout): RecoveryPlan = recommend(ComfortRequest(workout)).plan

        /** Pure comfort policy; vehicle capability checks and user confirmation happen later. */
        fun recommend(request: ComfortRequest): ComfortRecommendation = ComfortPolicy.recommend(request)
    }
}
