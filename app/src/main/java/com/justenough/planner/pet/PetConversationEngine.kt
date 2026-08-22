package com.justenough.planner.pet

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.justenough.planner.appContainer
import com.justenough.planner.data.FeedbackKinds
import com.justenough.planner.data.PetPromptEntity
import com.justenough.planner.data.PetPromptStatuses
import com.justenough.planner.data.PetVisibility
import com.justenough.planner.data.RunStatuses
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Keeps Xiaoman's speech contextual while keeping every decision local and bounded. */
object PetConversationEngine {
    private data class Choice(val code: String, val label: String, val score: Int)

    suspend fun upcoming(context: Context, blockId: Long, minutes: Int) {
        val c = context.appContainer
        val value = c.repository.todaySnapshot().todayBlocks.firstOrNull { it.block.id == blockId } ?: return
        val names = value.plans.take(2).joinToString("、") { it.name }.ifBlank { value.block.name }
        val statement = if (minutes >= 30) "还有30分钟，$names 就要开始了，可以慢慢准备。" else "还有5分钟，$names 就要开始了哦。"
        if (minutes >= 30 && value.plans.size == 1 && questionAllowed(context)) {
            ask(
                context = context,
                kind = FeedbackKinds.ANTICIPATED_DIFFICULTY,
                text = "${value.plans.first().name}快开始了，你觉得会有点难吗？",
                choices = listOf(Choice("EASY", "不难", 1), Choice("SOME", "有一点", 2), Choice("HARD", "挺难", 4), Choice("VERY_HARD", "很难", 5)),
                planId = value.plans.first().id,
                runId = null,
                expiresIn = 40 * 60_000L,
            )
        } else showStatement(context, statement, priority = if (minutes <= 5) 2 else 1)
    }

    suspend fun blockStarted(context: Context, blockId: Long) {
        val c=context.appContainer
        val value = c.repository.todaySnapshot().todayBlocks.firstOrNull { it.block.id == blockId } ?: return
        val names = value.plans.take(3).joinToString("、") { it.name }.ifBlank { value.block.name }
        val text="$names 到时间了。要开始哪一项？"
        c.repository.addAiMessage("assistant",text,"PET_BUBBLE","LOCAL")
        val options=value.plans.take(3).joinToString(";"){"START_${it.id}|开始 ${it.name.take(8)}|0"}
        AiPetService.trigger(context,AiPetService.ACTION_REMIND,text,options=options,persistent=true)
    }

    suspend fun afterTask(context: Context, runId: Long, completed: Boolean) {
        val c = context.appContainer
        val run = c.repository.getRun(runId) ?: return
        if (!questionAllowed(context)) return
        ask(
            context,
            FeedbackKinds.MOOD,
            if (completed) "做完这件事，现在的感觉怎么样？" else "做到这里已经够了，现在感觉怎么样？",
            listOf(Choice("LOW", "不太好", 1), Choice("OK", "还可以", 3), Choice("HAPPY", "挺开心", 5)),
            run.planId,
            run.id,
            2 * 60 * 60_000L,
        )
    }

    suspend fun duringTask(context: Context, runId: Long) {
        val c = context.appContainer
        val run = c.repository.getRun(runId) ?: return
        if (run.status !in setOf(RunStatuses.ACTIVE, RunStatuses.PAUSED) || !questionAllowed(context)) return
        val shortTask=run.plannedMinutes<=20
        ask(
            context,
            if(shortTask)FeedbackKinds.EASE else FeedbackKinds.FATIGUE,
            if(shortTask)"${c.repository.getPlan(run.planId)?.name ?: "这件事"}做到现在，轻松吗？" else "${c.repository.getPlan(run.planId)?.name ?: "这件事"}做到现在，累不累？",
            if(shortTask)listOf(Choice("HARD", "不轻松", 1),Choice("OK", "差不多", 3),Choice("EASY", "挺轻松", 5)) else listOf(Choice("NONE", "不累", 1), Choice("LITTLE", "有一点", 2), Choice("TIRED", "挺累", 4), Choice("VERY_TIRED", "超累", 5)),
            run.planId,
            run.id,
            90 * 60_000L,
        )
    }

    suspend fun casual(context: Context) {
        val c = context.appContainer
        val snapshot = c.repository.todaySnapshot()
        val now = Instant.now().atZone(ZoneId.systemDefault())
        val minute = now.toLocalTime().toSecondOfDay() / 60
        val next = snapshot.todayBlocks.firstOrNull { it.block.startMinute > minute }
        val message = when {
            snapshot.activeRun != null -> "${snapshot.activeRun.name}正在进行，按自己的节奏来。"
            next != null && next.block.startMinute - minute <= 60 -> "${next.block.name}还有${next.block.startMinute - minute}分钟开始。"
            snapshot.recommendations.isNotEmpty() -> "现在可以做：${snapshot.recommendations.take(2).joinToString("、") { it.name }}。"
            snapshot.fulfillmentTotal >= 5 -> "今天已经很充实了，停下来也完全可以。"
            else -> "现在没有必须赶着做的事，慢一点也可以。"
        }
        showStatement(context, aiWording(context, message), priority = 0)
    }

    suspend fun chat(context: Context, text: String): Result<String> {
        val c = context.appContainer
        val cleaned = text.trim()
        if (cleaned.isBlank()) return Result.failure(IllegalArgumentException("先写一点想说的话"))
        c.repository.addAiMessage("user", cleaned)
        val result = c.aiClient.chat(cleaned)
        return result.onSuccess { reply ->
            c.repository.addAiMessage("assistant", reply, source = c.aiClient.lastSource)
            showStatement(context, reply.take(30), priority = 1)
        }
    }

    suspend fun answer(context: Context, promptId: Long, code: String?, freeText: String? = null) {
        val c = context.appContainer
        val prompt = c.repository.getPetPrompt(promptId) ?: return
        if (prompt.status != PetPromptStatuses.PENDING) return
        val choice = decode(prompt.options).firstOrNull { it.code == code }
        val answer = freeText?.trim()?.takeIf { it.isNotBlank() } ?: choice?.label ?: return
        prompt.planId?.let { planId -> c.repository.addFeedback(planId, prompt.runId, prompt.kind, choice?.score, choice?.code, answer, prompt.source) }
        c.repository.answerPetPrompt(prompt.id, PetPromptStatuses.ANSWERED)
        c.repository.addAiMessage("user", answer, "PET_FEEDBACK")
        val response = when (prompt.kind) {
            FeedbackKinds.FATIGUE -> if ((choice?.score ?: 3) >= 4) "知道了，累了就先停一下，不必硬撑。" else "收到，我会记住这件事的真实负担。"
            FeedbackKinds.ANTICIPATED_DIFFICULTY -> "收到，我会把这次预期难度一起记下来。"
            else -> "收到，我会结合这次感受理解你的节奏。"
        }
        showStatement(context, response, priority = 1)
    }

    suspend fun dismiss(context: Context, promptId: Long) {
        context.appContainer.repository.answerPetPrompt(promptId, PetPromptStatuses.DISMISSED)
    }

    private suspend fun ask(context: Context, kind: String, text: String, choices: List<Choice>, planId: Long?, runId: Long?, expiresIn: Long) {
        if (!questionAllowed(context)) return
        val c = context.appContainer
        val wording = aiWording(context, text, question = true)
        val prompt = PetPromptEntity(kind = kind, text = wording, options = encode(choices), priority = 4, planId = planId, runId = runId, source = if (wording == text) "LOCAL" else c.aiClient.lastSource, expiresAt = System.currentTimeMillis() + expiresIn)
        val id = c.repository.addPetPrompt(prompt)
        c.repository.addAiMessage("assistant", wording, "PET_QUESTION", prompt.source)
        AiPetService.trigger(context, AiPetService.ACTION_REMIND, wording, id, prompt.options, persistent = true)
    }

    private suspend fun showStatement(context: Context, text: String, priority: Int) {
        val c = context.appContainer
        val settings = c.settings.state.first()
        if (!PetVisibility.isVisible(settings.petVisibility) || !settings.petActiveMessages) return
        if (settings.petSoundEnabled) com.justenough.planner.task.TaskSoundPlayer.play("LIGHT")
        c.repository.addAiMessage("assistant", text.take(80), "PET_BUBBLE", "LOCAL")
        AiPetService.trigger(context, AiPetService.ACTION_REMIND, text.take(80), persistent = false)
    }

    private suspend fun questionAllowed(context: Context): Boolean {
        val c = context.appContainer
        val s = c.settings.state.first()
        if (!PetVisibility.isVisible(s.petVisibility) || !s.petAskQuestions || s.petQuestionLimit <= 0) return false
        val minute = java.time.LocalTime.now().toSecondOfDay() / 60
        val quiet = if (s.petQuietStartMinute <= s.petQuietEndMinute) minute in s.petQuietStartMinute until s.petQuietEndMinute else minute >= s.petQuietStartMinute || minute < s.petQuietEndMinute
        if (quiet) return false
        val pending = c.repository.pendingPetPrompt()
        if (pending != null && (pending.expiresAt == null || pending.expiresAt > System.currentTimeMillis())) return false
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return c.repository.petQuestionCount(start) < s.petQuestionLimit
    }

    private suspend fun aiWording(context: Context, local: String, question: Boolean = false): String {
        val c = context.appContainer
        val s = c.settings.state.first()
        val day = LocalDate.now().toEpochDay()
        val used = if (s.analysisEpochDay == day) s.analysisCount else 0
        if (!s.aiConnectionVerified || used >= s.autoAnalysisLimit) return local
        val instruction = if (question) "把下面问题改写成小满温和自然的口吻，保留原意和选项含义，只回复30字以内的问题：$local" else "把下面提示改写成小满温和务实的口吻，只回复30字以内：$local"
        val result = c.aiClient.chat(instruction)
        c.settings.update { it.copy(analysisEpochDay = day, analysisCount = used + 1) }
        return result.getOrNull()?.trim()?.take(30) ?: local
    }

    private fun encode(values: List<Choice>) = values.joinToString(";") { "${it.code}|${it.label}|${it.score}" }
    private fun decode(value: String) = value.split(';').mapNotNull { part ->
        val fields = part.split('|'); if (fields.size != 3) null else fields[2].toIntOrNull()?.let { Choice(fields[0], fields[1], it) }
    }

    fun scheduleDuringTask(context: Context, runId: Long, plannedMinutes: Int) {
        val delay = (plannedMinutes / 2).coerceIn(5, 30).toLong()
        val request = OneTimeWorkRequestBuilder<PetTaskQuestionWorker>().addTag("xiaoman-question").setInitialDelay(delay, TimeUnit.MINUTES).setInputData(workDataOf("run_id" to runId)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("xiaoman-task-$runId", ExistingWorkPolicy.REPLACE, request)
    }
}

class PetTaskQuestionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        PetConversationEngine.duringTask(applicationContext, inputData.getLong("run_id", -1))
        return Result.success()
    }
}
