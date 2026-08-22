package com.justenough.planner.ai

import com.justenough.planner.data.AppSettings
import com.justenough.planner.data.PlanKinds
import com.justenough.planner.data.PlanQuadrants
import com.justenough.planner.data.PlannerRepository
import com.justenough.planner.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class AiPlannerClient(
    private val repository: PlannerRepository,
    private val settingsStore: AppSettings,
    private val secureKeyStore: SecureKeyStore,
) {
    @Volatile var lastSource: String = "PRIMARY"
        private set
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    suspend fun testConnection(): Result<String> = request(TEST_PROMPT).mapCatching {
        json.parseToJsonElement(extractJson(it)).jsonObject
        "地址、鉴权、模型、对话和JSON输出均正常"
    }

    suspend fun testConnection(baseUrl: String, model: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey.takeIf { it.isNotBlank() } ?: requireNotNull(secureKeyStore.getApiKey()) { "请填写API Key" }
            val content = execute(baseUrl, model, key, TEST_PROMPT, true)
            json.parseToJsonElement(extractJson(content)).jsonObject
            "地址、鉴权、模型、对话和JSON输出均正常"
        }
    }

    suspend fun chat(userText: String): Result<String> {
        val snapshot = repository.snapshot()
        val state = repository.state.first()
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val recent = snapshot.runs.filter { it.startedAt >= cutoff }
        val context = buildString {
            append("你是刚刚好的任务辅助AI。核心职责是帮助用户看清下一步、降低开始阻力，回答简洁务实。未经确认不得修改计划。\n")
            append("当前计划："); append(json.encodeToString(snapshot.plans.filter { !it.archived })); append('\n')
            append("时间安排："); append(json.encodeToString(snapshot.blocks)); append('\n')
            append("最近30天：完成"); append(recent.count { it.status == "COMPLETED" }); append("次，充实度总计")
            append(recent.mapNotNull { it.fulfillmentPoints }.sum()); append("格。\n")
            append("任务真实感受聚合："); append(feedbackSummary(snapshot, cutoff)); append('\n')
            val limitedHistory = state.aiMessages.takeLast(8).joinToString("\n") { "${it.role}：${it.content.take(500)}" }
            if (limitedHistory.isNotBlank()) { append("有限对话历史：\n"); append(limitedHistory); append('\n') }
            append("用户消息："); append(userText)
        }
        return request(context, expectJson = false)
    }

    suspend fun propose(freeText: String): Result<PlanProposal> = withContext(Dispatchers.IO) {
        runCatching {
            require(freeText.isNotBlank()) { "请先输入想讨论或调整的内容" }
            val snapshot = repository.snapshot()
            val recentCutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            val recentRuns = snapshot.runs.filter { it.startedAt >= recentCutoff }
            val prompt = """
                你是克制、务实的生活规划助手。只输出严格JSON，不要Markdown。
                JSON结构：{"summary":"","plans":[{"name":"","minimumGoal":"","estimatedMinutes":20,"quadrant":"IMPORTANT_NOT_URGENT"}],"schedules":[{"name":"","kind":"CHOICE","startMinute":360,"endMinute":420,"weekdays":[1,2,3,4,5,6,7],"planNames":[""]}],"planChanges":[{"existingName":"","newName":null,"minimumGoal":null,"estimatedMinutes":null,"archive":null,"quadrant":null}],"scheduleChanges":[{"existingName":"","newName":null,"kind":null,"startMinute":null,"endMinute":null,"weekdays":null,"planNames":null}],"warnings":[""]}
                quadrant只能是IMPORTANT_URGENT、IMPORTANT_NOT_URGENT、NOT_IMPORTANT_URGENT、NOT_IMPORTANT_NOT_URGENT；说明分类理由写入summary或warnings。kind只能ANCHOR或CHOICE；分钟范围有效；ANCHOR只能有一个计划；CHOICE至少一个计划。不要擅自塞满全天。
                用户输入：$freeText
                当前计划：${json.encodeToString(snapshot.plans.filter { !it.archived })}
                当前安排：${json.encodeToString(snapshot.blocks)}
                最近30天聚合：完成${recentRuns.count { it.status == "COMPLETED" }}次，放弃${recentRuns.count { it.status == "ABANDONED" }}次，充实度总计${recentRuns.mapNotNull { it.fulfillmentPoints }.sum()}格。
                任务真实感受聚合：${feedbackSummary(snapshot, recentCutoff)}
            """.trimIndent()
            validate(json.decodeFromString<PlanProposal>(extractJson(request(prompt).getOrThrow())))
        }
    }

    suspend fun apply(proposal: PlanProposal) {
        val previous = repository.snapshot()
        try { repository.applyProposal(proposal) } catch (failure: Throwable) {
            runCatching { repository.replaceAll(previous) }
            throw failure
        }
    }

    private suspend fun request(prompt: String, expectJson: Boolean = true): Result<String> = withContext(Dispatchers.IO) {
        val result = runCatching {
            val s = settingsStore.state.first()
            require(s.aiConsent) { "请先在设置中同意将计划摘要发送给所选AI服务" }
            val primaryKey = requireNotNull(secureKeyStore.getApiKey()) { "请先配置主要接口API Key" }
            var primaryFailure: Throwable? = null
            repeat(2) {
                try { lastSource = "PRIMARY"; return@runCatching execute(s.aiBaseUrl, s.aiModel, primaryKey, prompt, expectJson) }
                catch (t: Throwable) { primaryFailure = t; if (!retryable(t)) throw t }
            }
            if (!s.fallbackEnabled) throw requireNotNull(primaryFailure)
            val fallbackKey = requireNotNull(secureKeyStore.getFallbackApiKey()) { "主要接口失败，备用接口未填写Key" }
            settingsStore.update { it.copy(aiConnectionDetail = "主要接口失败，本次已切换备用DeepSeek") }
            repository.addAiMessage("assistant", "主要接口暂不可用，本次回复来自备用DeepSeek。", "SYSTEM", "FALLBACK")
            lastSource = "FALLBACK"
            execute(s.fallbackBaseUrl, s.fallbackModel, fallbackKey, prompt, expectJson)
        }
        settingsStore.update { it.copy(aiRuntimeOffline = result.isFailure) }
        result
    }

    private fun execute(baseUrl: String, model: String, key: String, prompt: String, expectJson: Boolean): String {
        val payload = ChatRequest(model, listOf(ChatMessage("user", prompt)), if (expectJson) ResponseFormat() else null)
        val request = Request.Builder().url(normalizeEndpoint(baseUrl)).header("Authorization", "Bearer $key")
            .post(json.encodeToString(payload).toRequestBody("application/json".toMediaType())).build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw HttpFailure(response.code, readableHttpReason(response.code, body))
                return json.decodeFromString<ChatResponse>(body).choices.firstOrNull()?.message?.content ?: error("接口没有返回内容")
            }
        } catch (e: SocketTimeoutException) { throw e }
        catch (e: HttpFailure) { throw e }
        catch (e: IOException) { throw NetworkFailure("网络连接失败：${e.message ?: "请检查网络"}", e) }
    }

    internal fun normalizeEndpoint(input: String): String {
        val base = input.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) { "接口地址必须以http://或https://开头" }
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun retryable(t: Throwable) = shouldUseFallback(t)
    private fun readableHttpReason(code: Int, body: String) = when (code) {
        401, 403 -> "鉴权失败，请检查API Key"
        402 -> "账户余额不足"
        429 -> "请求过于频繁或额度已用完"
        in 500..599 -> "服务暂时不可用（$code）"
        else -> "请求失败（$code），请检查接口地址、模型名和请求参数"
    }

    private fun extractJson(raw: String): String {
        val value = raw.substringAfter("```json", raw).substringAfter("```", raw).substringBeforeLast("```", raw).trim()
        val start = value.indexOf('{'); val end = value.lastIndexOf('}')
        return if (start >= 0 && end > start) value.substring(start, end + 1) else value
    }

    private fun feedbackSummary(snapshot: com.justenough.planner.data.DatabaseSnapshot, cutoff: Long): String {
        val names=snapshot.plans.associate{it.id to it.name}
        val groups=snapshot.feedback.filter{it.createdAt>=cutoff&&it.score!=null}.groupBy{it.planId to it.kind}
        return groups.entries.take(30).joinToString("；") { (key,values) -> "${names[key.first]?:"计划"}/${key.second}:均值${"%.1f".format(values.mapNotNull{it.score}.average())},${values.size}次" }.ifBlank{"暂无"}
    }

    private fun validate(value: PlanProposal): PlanProposal {
        value.plans.forEach { require(it.name.isNotBlank() && it.minimumGoal.isNotBlank() && it.estimatedMinutes in 1..1440 && PlanQuadrants.isValid(it.quadrant)) }
        value.schedules.forEach { requireValidSchedule(it.name, it.kind, it.startMinute, it.endMinute, it.weekdays, it.planNames) }
        value.planChanges.forEach { require(it.existingName.isNotBlank()); it.estimatedMinutes?.let { minutes -> require(minutes in 1..1440) };it.quadrant?.let{q->require(PlanQuadrants.isValid(q))} }
        value.scheduleChanges.forEach { change ->
            require(change.existingName.isNotBlank())
            change.kind?.let { require(it == PlanKinds.ANCHOR || it == PlanKinds.CHOICE) }
            change.startMinute?.let { require(it in 0..1439) }; change.endMinute?.let { require(it in 1..1440) }
            change.weekdays?.let { require(it.isNotEmpty() && it.all { day -> day in 1..7 }) }
        }
        return value
    }

    private fun requireValidSchedule(name: String, kind: String, start: Int, end: Int, days: List<Int>, names: List<String>) {
        require(name.isNotBlank() && kind in setOf(PlanKinds.ANCHOR, PlanKinds.CHOICE)); require(start in 0..1439 && end in 1..1440 && end > start)
        require(days.isNotEmpty() && days.all { it in 1..7 }); require(names.isNotEmpty() && names.all { it.isNotBlank() })
        require(kind != PlanKinds.ANCHOR || names.distinct().size == 1)
    }

    internal class HttpFailure(val code: Int, message: String) : IOException(message)
    private class NetworkFailure(message: String, cause: Throwable) : IOException(message, cause)

    companion object {
        private const val TEST_PROMPT = "只返回严格JSON：{\"ok\":true,\"message\":\"连接成功\"}"
        internal fun shouldUseFallback(t: Throwable) = t is SocketTimeoutException || (t is HttpFailure && (t.code == 401 || t.code == 403 || t.code == 429 || t.code >= 500))
    }
}
