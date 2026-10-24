package com.englishcoach.llm

import android.util.Log
import com.englishcoach.core.model.Correction
import com.englishcoach.core.model.LlmProvider
import com.englishcoach.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.http.Body
import retrofit2.http.POST

// ---------- DeepSeek（OpenAI 兼容格式）DTO ----------
// 约定：JSON 字段全用小驼峰，与属性名一致，不写 @SerialName

/** 一条对话消息：role = "system" / "user" / "assistant" */
@Serializable
data class LlmMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val temperature: Double = 0.7
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList()
)

@Serializable
data class ChatChoice(
    val message: LlmMessage = LlmMessage(role = "assistant", content = "")
)

/** Retrofit 接口定义（baseUrl = https://api.deepseek.com/） */
interface DeepSeekApi {
    @POST("chat/completions")
    suspend fun completions(@Body body: ChatCompletionRequest): ChatCompletionResponse
}

/** LLM 客户端接口 */
interface LlmClient {
    /** 普通回复：给历史消息 + 场景 system prompt，返回 AI 的话 */
    suspend fun chat(history: List<LlmMessage>, system: String): String

    /** 纠错：把用户的话单独批改（可与 chat 并行调用，不阻塞回复） */
    suspend fun correct(userUtterance: String, scenarioContext: String): Correction
}

/** DeepSeek 实现：失败重试 1 次；correct() 解析结构化 JSON */
class DeepSeekLlmClient(
    private val api: DeepSeekApi,
    private val model: String = "deepseek-chat"
) : LlmClient {

    companion object {
        private const val TAG = "EC-Llm"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun chat(history: List<LlmMessage>, system: String): String {
        val messages = listOf(LlmMessage(role = "system", content = system)) + history
        val response = retryOnce {
            api.completions(ChatCompletionRequest(model = model, messages = messages))
        }
        return response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
    }

    override suspend fun correct(userUtterance: String, scenarioContext: String): Correction {
        val user = "场景背景：$scenarioContext\n\n我说的话：$userUtterance"
        val response = retryOnce {
            api.completions(
                ChatCompletionRequest(
                    model = model,
                    messages = listOf(
                        LlmMessage(role = "system", content = PromptBuilder.CORRECTION_SYSTEM_PROMPT),
                        LlmMessage(role = "user", content = user)
                    )
                )
            )
        }
        val content = response.choices.firstOrNull()?.message?.content.orEmpty()
        return parseCorrection(content)
            ?: Correction(original = userUtterance, fixed = userUtterance, type = "NONE", note = "这次批改没成功")
    }

    /**
     * 剥掉可能的 ```json 围栏再解析。
     * 容错口径（PM v2 需求2）：notable_words 缺字段/类型错不该让整条纠错丢失——
     * 严格解析失败时退到"只取核心四字段"的宽限解析（生词字段按空数组处理）。
     */
    private fun parseCorrection(content: String): Correction? {
        val cleaned = stripCodeFence(content)
        return try {
            json.decodeFromString(Correction.serializer(), cleaned)
        } catch (e: Exception) {
            Log.w(TAG, "纠错 JSON 严格解析失败，退宽限解析: ${e.message}")
            parseCoreFields(cleaned)
        }
    }

    /** 宽限解析：只取 original/fixed/type/note，notable_words 按空数组处理 */
    private fun parseCoreFields(content: String): Correction? {
        return try {
            val obj = json.parseToJsonElement(content).jsonObject
            val original = obj["original"]?.jsonPrimitive?.content ?: return null
            val fixed = obj["fixed"]?.jsonPrimitive?.content ?: return null
            Correction(
                original = original,
                fixed = fixed,
                type = obj["type"]?.jsonPrimitive?.content ?: "NONE",
                note = obj["note"]?.jsonPrimitive?.content ?: "",
                notableWords = emptyList()
            )
        } catch (e: Exception) {
            Log.w(TAG, "纠错 JSON 宽限解析也失败: ${e.message}")
            null
        }
    }

    private fun stripCodeFence(content: String): String {
        var text = content.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```")
            if (text.endsWith("```")) text = text.removeSuffix("```")
        }
        return text.trim()
    }

    /** 外部服务失败重试 1 次（共享约定） */
    private suspend fun <T> retryOnce(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "LLM 调用失败，重试 1 次: ${e.message}")
            block()
        }
    }
}

/**
 * 智谱 GLM 实现（2026-10-22 新增，OpenAI 兼容端点 open.bigmodel.cn/api/paas/v4）。
 *
 * 与 DeepSeek 实现的差异：
 * - 模型名每次调用从 settings.zhipuModel 现读（用户可在设置页改，如 glm-5.3-flash / glm-5.3）——切换立即生效
 * - GLM-5.3 系列是思考型模型：响应 message 里可能带 reasoning_content（内部推理文本），
 *   解析时依赖 ignoreUnknownKeys 直接忽略，只取 content；content 为空（思考超限未产出答案）时
 *   按失败处理走上层兜底（错误气泡）
 * - 不发 response_format/json 模式参数（智谱端不接受该参数；JSON 格式由 prompt 强制约束）
 * - Key 从 KeyHolder.zhipuKey 每请求读取（Retrofit 拦截器在构建处接线，换 Key 立即生效）
 */
class ZhipuLlmClient(
    private val api: DeepSeekApi, // OpenAI 兼容，同接口复用
    private val settingsStore: SettingsStore,
    private val defaultModel: String = "glm-5.3-flash"
) : LlmClient {

    companion object {
        private const val TAG = "EC-Llm"
    }

    private val json = Json {
        ignoreUnknownKeys = true // 忽略 reasoning_content 等额外字段
        isLenient = true
    }

    override suspend fun chat(history: List<LlmMessage>, system: String): String {
        val messages = listOf(LlmMessage(role = "system", content = system)) + history
        val response = retryOnce {
            api.completions(ChatCompletionRequest(model = currentModel(), messages = messages))
        }
        val content = response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        // 思考型模型可能 content 为空（推理没收敛到答案）：按失败抛出，上层错误气泡兜底
        if (content.isEmpty()) {
            throw IllegalStateException("GLM 返回内容为空（可能思考未完成），请重试或换回 DeepSeek")
        }
        return content
    }

    override suspend fun correct(userUtterance: String, scenarioContext: String): Correction {
        val user = "场景背景：$scenarioContext\n\n我说的话：$userUtterance"
        val response = retryOnce {
            api.completions(
                ChatCompletionRequest(
                    model = currentModel(),
                    messages = listOf(
                        LlmMessage(role = "system", content = PromptBuilder.CORRECTION_SYSTEM_PROMPT),
                        LlmMessage(role = "user", content = user)
                    )
                )
            )
        }
        val content = response.choices.firstOrNull()?.message?.content.orEmpty()
        return parseCorrection(content)
            ?: Correction(original = userUtterance, fixed = userUtterance, type = "NONE", note = "这次批改没成功")
    }

    /** 每次调用现读模型 ID（设置页改完立即生效）；读取失败回退默认 */
    private suspend fun currentModel(): String {
        return runCatching { settingsStore.settings.first().zhipuModel }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: defaultModel
    }

    /** 剥掉可能的 ```json 围栏再解析（与 DeepSeek 同口径：严格失败退宽限解析） */
    private fun parseCorrection(content: String): Correction? {
        val cleaned = stripCodeFence(content)
        return try {
            json.decodeFromString(Correction.serializer(), cleaned)
        } catch (e: Exception) {
            Log.w(TAG, "纠错 JSON 严格解析失败，退宽限解析: ${e.message}")
            parseCoreFields(cleaned)
        }
    }

    /** 宽限解析：只取 original/fixed/type/note，notable_words 按空数组处理 */
    private fun parseCoreFields(content: String): Correction? {
        return try {
            val obj = json.parseToJsonElement(content).jsonObject
            val original = obj["original"]?.jsonPrimitive?.content ?: return null
            val fixed = obj["fixed"]?.jsonPrimitive?.content ?: return null
            Correction(
                original = original,
                fixed = fixed,
                type = obj["type"]?.jsonPrimitive?.content ?: "NONE",
                note = obj["note"]?.jsonPrimitive?.content ?: "",
                notableWords = emptyList()
            )
        } catch (e: Exception) {
            Log.w(TAG, "纠错 JSON 宽限解析也失败: ${e.message}")
            null
        }
    }

    private fun stripCodeFence(content: String): String {
        var text = content.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```")
            if (text.endsWith("```")) text = text.removeSuffix("```")
        }
        return text.trim()
    }

    private suspend fun <T> retryOnce(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "LLM 调用失败，重试 1 次: ${e.message}")
            block()
        }
    }
}

/**
 * LLM 供应商路由：实现 LlmClient，每次调用现读 settings.llmProvider 决定走 DeepSeek 还是智谱
 * （与 TtsRouter 同机制）——设置页切换供应商立即生效，无需重启。
 */
class LlmRouter(
    private val deepSeek: LlmClient,
    private val zhipu: LlmClient,
    private val settingsStore: SettingsStore
) : LlmClient {

    companion object {
        private const val TAG = "EC-Llm"
    }

    override suspend fun chat(history: List<LlmMessage>, system: String): String =
        delegate { it.chat(history, system) }

    override suspend fun correct(userUtterance: String, scenarioContext: String): Correction =
        delegate { it.correct(userUtterance, scenarioContext) }

    private suspend fun <T> delegate(block: suspend (LlmClient) -> T): T {
        val provider = runCatching { settingsStore.settings.first().llmProvider }
            .getOrDefault(LlmProvider.DEEPSEEK)
        return block(if (provider == LlmProvider.ZHIPU) zhipu else deepSeek)
    }
}
