package com.englishcoach.domain

import android.util.Log
import com.englishcoach.core.model.ChatMessage
import com.englishcoach.core.model.Correction
import com.englishcoach.core.model.CorrectionMode
import com.englishcoach.core.model.PracticeSession
import com.englishcoach.core.model.Role
import com.englishcoach.core.model.Scenario
import com.englishcoach.data.ConversationStore
import com.englishcoach.data.ScenarioRepository
import com.englishcoach.data.SettingsStore
import com.englishcoach.data.StatsStore
import com.englishcoach.data.VocabStore
import com.englishcoach.llm.LlmClient
import com.englishcoach.llm.LlmMessage
import com.englishcoach.llm.PromptBuilder
import com.englishcoach.speech.AsrEngine
import com.englishcoach.speech.AudioPlayer
import com.englishcoach.speech.TtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 对话编排层：整个 App 的大脑。
 * 串起 ASR → LLM（chat + correct 并行）→ TTS 连播 + 时长统计 + 会话存档。
 * 错误处理约定：内部 try-catch，失败发 AI 错误气泡，不抛到 UI 崩溃；外部调用失败重试 1 次。
 */
class ChatOrchestrator(
    private val asr: AsrEngine,
    private val llm: LlmClient,
    private val tts: TtsClient,
    private val player: AudioPlayer,
    private val conversations: ConversationStore,
    private val stats: StatsStore,
    private val scenarios: ScenarioRepository,
    private val settings: SettingsStore,
    private val promptBuilder: PromptBuilder,
    // 生词本（第二轮反馈需求 2：纠错 notable_words 自动收词，不再依赖用户逐词手点）
    private val vocab: VocabStore? = null
) {
    companion object {
        private const val TAG = "EC-Orchestrator"
        private const val ERROR_REPLY = "网络开小差了，请再试一次。（Network hiccup — please try again.）"

        /** 无场景兜底 system prompt（理论上走不到） */
        private const val FALLBACK_SYSTEM =
            "You are a friendly English conversation partner. Chat with the user in simple spoken English."
    }

    /** 后台播放作用域：TTS 播放不阻塞 UI 返回消息列表 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 开始一次场景练习。
     * @param existing 非空且未完成 → 直接续上（"继续上次练习"），不重新打招呼
     */
    suspend fun startSession(scenario: Scenario, existing: PracticeSession? = null): PracticeSession {
        settings.update { it.copy(lastScenarioId = scenario.id) }
        if (existing != null && !existing.finished) {
            Log.d(TAG, "续上会话 ${existing.sessionId}，共 ${existing.messages.size} 条")
            return existing
        }

        val sessionId = "s_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var session = PracticeSession(
            sessionId = sessionId,
            scenarioId = scenario.id,
            startedAt = System.currentTimeMillis()
        )

        // AI 先开口打招呼（LLM 失败给固定开场白，不阻塞进入对话）
        val greeting = try {
            retryOnce { llm.chat(emptyList(), promptBuilder.chatSystemPrompt(scenario)) }
                .ifBlank { "Hi! I'm your ${scenario.aiRole}. Say something in English!" }
        } catch (e: Exception) {
            Log.w(TAG, "开场白生成失败: ${e.message}")
            "Hi! I'm your ${scenario.aiRole}. Say something in English!"
        }

        session = session.copy(
            messages = listOf(
                ChatMessage(
                    id = newId(),
                    role = Role.AI,
                    text = greeting,
                    timestamp = System.currentTimeMillis()
                )
            )
        )
        conversations.saveSession(session)

        // 开场白语音后台播，UI 立即返回
        scope.launch {
            try {
                speak(greeting)
            } catch (e: Exception) {
                Log.w(TAG, "开场白 TTS 失败: ${e.message}")
            }
        }
        Log.d(TAG, "新会话 $sessionId 场景=${scenario.id}（消息条数仅记录，不打请求体）")
        return session
    }

    /**
     * 核心链路：把用户的一句话走完整条链。
     * @param speakingDurationMs ASR Final 事件里的开口时长（真实开口口径，不是前台时长）
     */
    suspend fun processUserUtterance(
        session: PracticeSession,
        text: String,
        speakingDurationMs: Long = 0L
    ): PracticeSession {
        val scenario = scenarios.getById(session.scenarioId)
        val userMsg = ChatMessage(
            id = newId(),
            role = Role.USER,
            text = text,
            timestamp = System.currentTimeMillis()
        )

        // 统计：开口秒数按 ASR 会话时长累加，同时记一轮对话（失败不影响主链路）
        try {
            stats.addSpeakingSeconds(seconds = (speakingDurationMs / 1000).toInt(), newTurn = true)
        } catch (e: Exception) {
            Log.w(TAG, "统计写入失败: ${e.message}")
        }

        val mode = try {
            settings.settings.first().correctionMode
        } catch (e: Exception) {
            CorrectionMode.LEARNING
        }

        val history = toLlmHistory(session.messages)
        val system = scenario?.let { promptBuilder.chatSystemPrompt(it) } ?: FALLBACK_SYSTEM

        var reply = ""
        var correction: Correction? = null
        try {
            if (mode == CorrectionMode.LEARNING && scenario != null) {
                // 学习模式：chat 与 correct 并行请求（沉浸模式跳过纠错）
                coroutineScope {
                    val chatDeferred = async { retryOnce { llm.chat(history, system) } }
                    val correctDeferred = async<Correction?> {
                        runCatching { retryOnce { llm.correct(text, scenario.systemPrompt) } }.getOrNull()
                    }
                    reply = chatDeferred.await()
                    correction = correctDeferred.await()
                }
            } else {
                reply = retryOnce { llm.chat(history, system) }
            }
        } catch (e: Exception) {
            // 已重试仍失败：发错误气泡，不崩溃
            Log.e(TAG, "LLM 调用失败（已重试）: ${e.message}")
            reply = ERROR_REPLY
        }
        if (reply.isBlank()) reply = ERROR_REPLY

        // 生词自动收集（第二轮反馈需求 2 根因修复）：纠错响应里的 notable_words 直接收进单词本，
        // 不再依赖用户在纠错条上逐词手点。同词去重在 VocabStore.add 内处理；失败不影响主链路。
        val notable = correction?.notableWords.orEmpty()
        if (vocab != null && notable.isNotEmpty()) {
            try {
                notable.forEach { word -> vocab.add(word, session.scenarioId) }
            } catch (e: Exception) {
                Log.w(TAG, "生词自动收集失败: ${e.message}")
            }
        }

        val aiMsg = ChatMessage(
            id = newId(),
            role = Role.AI,
            text = reply,
            timestamp = System.currentTimeMillis()
        )
        // 纠错挂在用户消息上（NONE 或批改失败则不挂）
        val userWithCorrection =
            if (correction != null && correction!!.type != "NONE" && correction!!.fixed != correction!!.original) {
                userMsg.copy(correction = correction)
            } else {
                userMsg
            }
        val updated = session.copy(
            messages = session.messages + userWithCorrection + aiMsg
        )
        try {
            conversations.saveSession(updated)
        } catch (e: Exception) {
            Log.w(TAG, "会话存档失败: ${e.message}")
        }

        // TTS 连播：先 AI 回复，后纠错说法（学习模式）；播放不阻塞返回
        val correctionToSpeak = if (userWithCorrection.correction != null) userWithCorrection.correction else null
        scope.launch {
            try {
                speak(reply)
                if (correctionToSpeak != null) {
                    delay(300)
                    speak(correctionToSpeak.fixed)
                }
            } catch (e: Exception) {
                Log.w(TAG, "TTS 播放失败: ${e.message}")
            }
        }
        return updated
    }

    /** 沉浸模式：练习结束时统一生成复盘（失败返回空列表，UI 显示"没有可复盘的错误"） */
    suspend fun summarize(session: PracticeSession): List<Correction> {
        val scenario = scenarios.getById(session.scenarioId) ?: return emptyList()
        val utterances = session.messages.filter { it.role == Role.USER }.map { it.text }
        if (utterances.isEmpty()) return emptyList()

        val system = promptBuilder.summarizeSystemPrompt(scenario)
        val user = utterances.joinToString("\n") { "- $it" }
        return try {
            val content = retryOnce { llm.chat(listOf(LlmMessage(role = "user", content = user)), system) }
            parseCorrections(content).filter {
                it.type != "NONE" && it.fixed != it.original
            }
        } catch (e: Exception) {
            Log.e(TAG, "复盘失败（已重试）: ${e.message}")
            emptyList()
        }
    }

    // ---------- 辅助按钮 ----------

    /** 🔊 重听 */
    suspend fun repeatLast(text: String) {
        try {
            speak(text)
        } catch (e: Exception) {
            Log.w(TAG, "重听失败: ${e.message}")
        }
    }

    /** 🐢 慢速（rate=-30%） */
    suspend fun speakSlowly(text: String) {
        try {
            speak(text, ratePercent = -30)
        } catch (e: Exception) {
            Log.w(TAG, "慢速播放失败: ${e.message}")
        }
    }

    /** 🌐 一键中文翻译 */
    suspend fun translate(text: String): String {
        return try {
            retryOnce {
                llm.chat(
                    history = listOf(LlmMessage(role = "user", content = text)),
                    system = promptBuilder.translationSystemPrompt()
                )
            }.ifBlank { "（翻译失败，请重试）" }
        } catch (e: Exception) {
            Log.e(TAG, "翻译失败（已重试）: ${e.message}")
            "（翻译失败，请重试）"
        }
    }

    /** 停止当前播放（离开对话页时调用） */
    fun cancelPlayback() {
        player.stop()
    }

    fun shutdown() {
        scope.cancel()
        player.stop()
    }

    // ---------- 私有工具 ----------

    /** 合成并播放（语音连播的基础单元） */
    private suspend fun speak(text: String, ratePercent: Int = 0) {
        val voice = try {
            settings.settings.first().azureVoiceName // 当前 TTS 音色（CosyVoice 预置）
        } catch (e: Exception) {
            "FunAudioLLM/CosyVoice2-0.5B:anna"
        }
        val audio = retryOnce { tts.synthesize(text, voice, ratePercent) }
        player.play(audio) {}
    }

    /** 历史消息 → LLM 格式（跳过纠错信息与错误气泡） */
    private fun toLlmHistory(messages: List<ChatMessage>): List<LlmMessage> {
        return messages
            .filter { it.role == Role.USER || it.role == Role.AI }
            .filter { it.text != ERROR_REPLY }
            .map { LlmMessage(role = if (it.role == Role.USER) "user" else "assistant", content = it.text) }
    }

    private fun parseCorrections(content: String): List<Correction> {
        var text = content.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```")
            if (text.endsWith("```")) text = text.removeSuffix("```")
        }
        return try {
            json.decodeFromString(ListSerializer(Correction.serializer()), text.trim())
        } catch (e: Exception) {
            Log.w(TAG, "复盘 JSON 解析失败: ${e.message}")
            emptyList()
        }
    }

    private suspend fun <T> retryOnce(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "调用失败，重试 1 次: ${e.message}")
            block()
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
