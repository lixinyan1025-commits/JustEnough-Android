package com.justenough.planner.data

object RecommendationEngine {
    fun recommend(
        candidates: List<PlanDetails>,
        offset: Int,
        availableMinutes: Int = Int.MAX_VALUE,
    ): List<PlanDetails> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedWith(
            compareBy<PlanDetails> { if (it.estimatedMinutes <= availableMinutes) 0 else 1 }
                .thenBy { it.lastChosenEpochMillis ?: 0L }
                .thenBy { it.estimatedMinutes }
                .thenBy { it.name },
        )
        val start = ((offset % sorted.size) + sorted.size) % sorted.size
        return List(minOf(3, sorted.size)) { sorted[(start + it) % sorted.size] }
    }
}
