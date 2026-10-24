package com.englishcoach.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.englishcoach.EnglishCoachApp
import com.englishcoach.core.model.ChatMessage
import com.englishcoach.core.model.Correction
import com.englishcoach.core.model.CorrectionMode
import com.englishcoach.core.model.InputMode
import com.englishcoach.core.model.PracticeSession
import com.englishcoach.core.model.Role
import com.englishcoach.core.model.Scenario
import com.englishcoach.speech.AsrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** 对话页 UI 状态（单一 StateFlow 驱动） */
data class ChatUiState(
    val scenario: Scenario? = null,
    val messages: List<ChatMessage> = emptyList(),
    val partialText: String = "",          // ASR 实时识别文字
    val isListening: Boolean = false,      // 按住说话中
    val isThinking: Boolean = false,       // AI 回复中
    val inputMode: InputMode = InputMode.VOICE,
    val isImmersive: Boolean = false,      // 沉浸模式（结束统一复盘）
    val summary: List<Correction> = emptyList(), // 复盘/小结结果
    val showSummary: Boolean = false,
    val summaryAuto: Boolean = false,      // true = 轮数/时长达标自动小结；false = 手动结束
    val pendingText: String = "",          // 文字模式待发送内容
    val hint: String? = null,              // ASR 错误提示等
    val exit: Boolean = false              // 结束练习 → 退出对话页
)

/**
 * 对话页状态管理：持有 ChatOrchestrator，收集 ASR 事件流，驱动 UI。
 * UI 层只跟它打交道，不含业务逻辑。
 */
class ChatViewModel(
    private val app: EnglishCoachApp,
    private val scenarioId: String
) : ViewModel() {

    companion object {
        private const val TAG = "EC-ChatVm"

        /** 目标轮数兜底值：仅当场景加载失败读不到 targetRounds 时使用（正常口径走 Scenario.targetRounds） */
        private const val FALLBACK_TARGET_ROUNDS = 6

        /** 或本会话开口满 3 分钟（与打卡保底一致） */
        private const val AUTO_FINISH_SPEAKING_MS = 180_000L
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var session: PracticeSession? = null

    /** 串行化：AI 回复期间禁止并发第二轮，避免两个协程覆盖 session 丢消息 */
    private val utteranceMutex = kotlinx.coroutines.sync.Mutex()

    /** 本会话累计开口毫秒（自动小结用；续接旧会话时从本轮起算，轮数从持久化消息算） */
    private var sessionSpeakingMs = 0L

    init {
        // 1) 初始化场景 + 续接上次会话（同场景未完成 → 接着聊）
        viewModelScope.launch {
            val settings = app.settingsStore.settings.first()
            val scenario = app.scenarioRepository.getById(scenarioId)
            _uiState.update {
                it.copy(
                    scenario = scenario,
                    inputMode = settings.inputMode,
                    isImmersive = settings.correctionMode == CorrectionMode.IMMERSIVE
                )
            }
            if (scenario == null) {
                Log.e(TAG, "找不到场景 $scenarioId")
                return@launch
            }
            val last = app.conversationStore.loadLastUnfinished()
            val existing = if (last != null && last.scenarioId == scenarioId && !last.finished) last else null
            val started = app.orchestrator.startSession(scenario, existing)
            session = started
            _uiState.update { it.copy(messages = started.messages) }
        }
        // 2) 收集 ASR 事件流（SharedFlow，不用回调嵌套）
        viewModelScope.launch {
            app.asrEngine.events.collect { event ->
                when (event) {
                    is AsrEvent.Partial -> _uiState.update { it.copy(partialText = event.text) }
                    is AsrEvent.Final -> {
                        _uiState.update { it.copy(partialText = "", isListening = false) }
                        handleUtterance(event.text, event.durationMs)
                    }
                    is AsrEvent.Error -> _uiState.update {
                        it.copy(isListening = false, partialText = "", hint = event.message)
                    }
                }
            }
        }
    }

    // ---------- 按住说话 ----------

    fun onMicPressed() {
        _uiState.update { it.copy(isListening = true, hint = null) }
        app.asrEngine.startListening()
    }

    fun onMicReleased() {
        app.asrEngine.stopListening()
        // Final 事件到达时 isListening 才置 false（期间显示"识别中"）
    }

    private fun handleUtterance(text: String, durationMs: Long) {
        if (text.isBlank()) return
        viewModelScope.launch {
            // 互斥锁串行化：上一轮没处理完时，新的一句话排队（UI 侧 isThinking 也会置灰按钮）。
            // session 在锁内读取，避免与手动结束/自动小结并发时拿到过期值
            utteranceMutex.withLock {
                val current = session ?: return@withLock
                sessionSpeakingMs += durationMs
                _uiState.update { it.copy(isThinking = true) }
                val updated = app.orchestrator.processUserUtterance(current, text, durationMs)
                session = updated
                _uiState.update { it.copy(isThinking = false, messages = updated.messages) }
            }
            // 完成即自动小结：达到轮数或开口时长阈值 → 与手动结束共用同一 summarize 流程
            maybeAutoFinish()
        }
    }

    /** 完成即自动小结：聊满目标轮数（读 Scenario.targetRounds，与首页卡/对话页进度线同源）或开口满 3 分钟 */
    private suspend fun maybeAutoFinish() {
        val current = session ?: return
        val scenario = app.scenarioRepository.getById(current.scenarioId)
        val targetRounds = scenario?.targetRounds ?: FALLBACK_TARGET_ROUNDS
        val turns = current.messages.count { it.role == Role.USER }
        if (turns >= targetRounds || sessionSpeakingMs >= AUTO_FINISH_SPEAKING_MS) {
            finishPracticeInternal(auto = true)
        }
    }

    // ---------- 文字输入模式 ----------

    fun onPendingTextChange(text: String) {
        _uiState.update { it.copy(pendingText = text) }
    }

    fun sendText() {
        val text = _uiState.value.pendingText.trim()
        if (text.isEmpty()) return
        _uiState.update { it.copy(pendingText = "") }
        handleUtterance(text, 0L)
    }

    // ---------- 辅助按钮 ----------

    /** 🔊 重听 */
    fun replay(text: String) {
        viewModelScope.launch { app.orchestrator.repeatLast(text) }
    }

    /** 🐢 慢速 */
    fun speakSlowly(text: String) {
        viewModelScope.launch { app.orchestrator.speakSlowly(text) }
    }

    /** 🌐 翻译（结果写回消息的 chinese 字段并持久化） */
    fun translate(message: ChatMessage) {
        if (message.chinese != null) return
        viewModelScope.launch {
            val chinese = app.orchestrator.translate(message.text)
            val current = session ?: return@launch
            val index = current.messages.indexOfFirst { it.id == message.id }
            if (index < 0) return@launch
            val newList = current.messages.toMutableList()
            newList[index] = newList[index].copy(chinese = chinese)
            val updated = current.copy(messages = newList)
            session = updated
            app.conversationStore.saveSession(updated)
            _uiState.update { it.copy(messages = newList) }
        }
    }

    // ---------- 结束练习 ----------

    /**
     * 手动结束练习（自动小结也走同一条 finishPracticeInternal 路径）：
     * 学习/沉浸模式共用 summarize 流程出小结卡——学习模式的手动/自动结束也出会话复盘，
     * 与逐轮纠错粒度不同、互为补充，属预期行为（产品决策：保留）。
     * 会话标记完成后置 session = null：持锁出小结卡的 3~5 秒内，排队的 utterance 直接跳过，
     * 避免把消息写进已 finished 的会话导致小结卡重复弹出。
     * UI 收到 exit=true 后调 onBack()。
     */
    fun endPractice() {
        viewModelScope.launch { finishPracticeInternal(auto = false) }
    }

    private suspend fun finishPracticeInternal(auto: Boolean) {
        utteranceMutex.withLock {
            val current = session ?: run { _uiState.update { it.copy(exit = true) }; return }
            app.orchestrator.cancelPlayback()
            _uiState.update { it.copy(isThinking = true) }
            val corrections = app.orchestrator.summarize(current)
            app.conversationStore.saveSession(current.copy(finished = true))
            // 通关判定（PM 口径）：触发自动小结（本次轮数 ≥ 目标 或 开口 ≥ 3 分钟）→ cleared=true；
            // 手动提前结束只累积最高轮数，不清关。已通关可重练，cleared 永不降级。
            val rounds = current.messages.count { it.role == Role.USER }
            runCatching {
                app.levelProgressStore.recordProgress(current.scenarioId, rounds, cleared = auto)
            }.onFailure { Log.w(TAG, "关卡进度写入失败: ${it.message}") }
            // 置 null：小结卡展示期间（锁已让出）到达的 ASR Final / 文字发送不再进入已完成的会话
            session = null
            _uiState.update {
                it.copy(
                    isThinking = false,
                    summary = corrections,
                    showSummary = true,
                    summaryAuto = auto
                )
            }
        }
    }

    /** 小结卡"完成"→ 退出（会话已在 finishPracticeInternal 里存档为 finished，无需重复写盘） */
    fun dismissSummary() {
        _uiState.update { it.copy(showSummary = false, exit = true) }
    }

    override fun onCleared() {
        // 离开对话页：停止播放，ASR 若还在听也停掉
        app.orchestrator.cancelPlayback()
        app.asrEngine.stopListening()
        super.onCleared()
    }
}

/** 手动构造工厂（不用 Hilt） */
class ChatViewModelFactory(
    private val app: EnglishCoachApp,
    private val scenarioId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(app, scenarioId) as T
}
