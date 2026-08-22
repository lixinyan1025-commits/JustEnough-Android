package com.justenough.planner.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlanProposal(
    val summary: String = "",
    val plans: List<ProposedPlan> = emptyList(),
    val schedules: List<ProposedSchedule> = emptyList(),
    val planChanges: List<ProposedPlanChange> = emptyList(),
    val scheduleChanges: List<ProposedScheduleChange> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ProposedPlan(
    val name: String,
    val minimumGoal: String,
    val estimatedMinutes: Int,
    val quadrant: String,
)

@Serializable
data class ProposedSchedule(
    val name: String,
    val kind: String,
    val startMinute: Int,
    val endMinute: Int,
    val weekdays: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    val planNames: List<String> = emptyList(),
)

@Serializable
data class ProposedPlanChange(
    val existingName: String,
    val newName: String? = null,
    val minimumGoal: String? = null,
    val estimatedMinutes: Int? = null,
    val archive: Boolean? = null,
    val quadrant: String? = null,
)

@Serializable
data class ProposedScheduleChange(
    val existingName: String,
    val newName: String? = null,
    val kind: String? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val weekdays: List<Int>? = null,
    val planNames: List<String>? = null,
)

@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("response_format") val responseFormat: ResponseFormat? = ResponseFormat(),
    val stream: Boolean = false,
)

@Serializable internal data class ChatMessage(val role: String, val content: String)
@Serializable internal data class ResponseFormat(val type: String = "json_object")
@Serializable internal data class ChatResponse(val choices: List<Choice> = emptyList())
@Serializable internal data class Choice(val message: ChatMessage)
