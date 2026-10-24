package com.englishcoach.ui.nce

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.englishcoach.EnglishCoachApp
import com.englishcoach.core.model.NceLesson
import com.englishcoach.core.model.NceMilestones
import com.englishcoach.core.model.NceSentence
import com.englishcoach.core.model.NotableWord
import com.englishcoach.data.DictStore
import com.englishcoach.domain.AiCoachReview
import com.englishcoach.domain.NceAttempt
import com.englishcoach.domain.NceScoreReport
import com.englishcoach.domain.NceScorer
import com.englishcoach.speech.AsrEvent
import com.englishcoach.ui.common.VoiceDomeOverlay
import com.englishcoach.ui.common.WaveBarsIndicator
import com.englishcoach.ui.common.rememberVoiceDomeState
import com.englishcoach.ui.common.voiceDomeGesture
import com.englishcoach.ui.theme.CorrectGreen
import com.englishcoach.ui.theme.HyperBlue
import com.englishcoach.ui.theme.PageBg
import com.englishcoach.ui.theme.TextPrimary
import com.englishcoach.ui.theme.TextSecondary
import com.englishcoach.ui.theme.XiaomiOrange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// v8/v3 设计规范色
private val SentCardBg1 = Color(0xFFEAF2FF)  // 句子卡浅蓝渐变起
private val SentCardBg2 = Color(0xFFF8FAFF)  // 句子卡浅蓝渐变止
private val StarGold = Color(0xFFFFB300)     // 页面金星
private val StarOff = Color(0x26000000)      // 未得星（15% 黑）
private val NcePurple1 = Color(0xFF9B8CFF)   // AI 评语卡紫渐变起
private val NcePurple2 = Color(0xFF5B4BD5)   // AI 评语卡紫渐变止
private val HoldOrange1 = Color(0xFFFF8F1F)  // 按住跟读橙渐变起
private val HoldOrange2 = Color(0xFFFF6900)  // 按住跟读橙渐变止
private val Hairline = Color(0x0F000000)     // 6% 黑分隔线

/** 跟读页阶段：加载中 → 原声播放中 → 等待跟读 → 成绩单 */
enum class NcePhase { LOADING, PLAYING, REPEAT, REPORT }

/** 点词词卡 UI 状态 */
data class WordCardUi(
    val rawToken: String,          // 句中原始 token（展示原样）
    val entry: DictStore.Entry?,   // 词典释义（null = 查询失败）
    val loading: Boolean,
    val inVocab: Boolean           // 是否已在单词本
)

/** 跟读页 UI 状态 */
data class NceRepeatUiState(
    val lesson: NceLesson? = null,
    val objectives: List<String> = emptyList(),   // 学习目标（占位 JSON / LLM 预生成）
    val currentIndex: Int = 0,                    // 当前句下标
    val phase: NcePhase = NcePhase.LOADING,
    val partial: String = "",                     // ASR 实时识别文本
    val hint: String = "",                        // 提示（识别失败/收词成功/防误触等）
    val attempts: Map<Int, NceAttempt> = emptyMap(), // 已跟读句 → 打分输入
    val pendingReferenceMs: Long = 0L,            // 当前句参考时长（mp3 句 = lrc 时间差；TTS 兜底 = 0）
    val report: NceScoreReport? = null,           // 整课成绩单（完成后）
    val collectedWords: Set<String> = emptySet(), // 单词本已有词（小写集合；句中下划线区分）
    val wordCard: WordCardUi? = null,             // 点词词卡（null = 关闭）
    val milestone: Int? = null,                   // 本次通关跨过的里程碑（10/50/100/276）
    val bestRefreshed: String? = null,            // 最高分刷新文案（"88 → 93"）
    val aiReview: AiCoachReview? = null,          // AI 教练点评（null = 加载中/未发起）
    val aiReviewLocal: Boolean = false,           // true = 本地简评（无 Key/调用失败）
    val errorTick: Int = 0,                       // v11.3 ASR 错误脉冲：每次 Error 递增，驱动圆顶"报错即收"
    val audioMissing: Boolean = false             // v11.8 音频包未安装（底部横幅引导导入；TTS 跟读照常可用）
) {
    val sentenceCount: Int get() = lesson?.sentences?.size ?: 0
    val currentSentence: NceSentence? get() = lesson?.sentences?.getOrNull(currentIndex)

    /** 剩余未通关句数（2b：剩最后 2 句时提示"还差 N 句就通关"） */
    val remainToClear: Int get() = (sentenceCount - currentIndex).coerceIn(0, sentenceCount)
}

/**
 * NCE 逐句跟读页 ViewModel（PM v3：P0 手势修复 + 词卡 + 奖励感）。
 * 状态机：听原声（mp3 seek 优先，TTS 兜底）→ 按住跟读（圆顶组件）→ ASR 识别即过 → 下一句 → 整课成绩单。
 */
class NceRepeatViewModel(
    private val app: EnglishCoachApp,
    private val lessonNo: Int
) : ViewModel() {

    companion object {
        private const val TAG = "EC-NceRepeat"

        /** 极短按防误触阈值（与 VoiceDome 组件一致，双保险） */
        private const val MIN_PRESS_MS = 300L

        fun factory(app: EnglishCoachApp, lessonNo: Int): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NceRepeatViewModel(app, lessonNo) as T
            }
    }

    private val _uiState = MutableStateFlow(NceRepeatUiState())
    val uiState: StateFlow<NceRepeatUiState> = _uiState.asStateFlow()

    private var pressAtMs = 0L

    /** 防误触/系统打断后吞掉下一个 ASR 结果（不出结果不残留） */
    private var ignoreNextAsrResult = false

    init {
        loadLesson()
        // 全局 ASR 事件流：只在本页 REPEAT 阶段消费（SharedFlow 广播语义，多页 collect 互不截走）
        viewModelScope.launch {
            app.asrEngine.events.collect { event -> onAsrEvent(event) }
        }
    }

    /** 加载课文 + 学习目标 + 词本集合，随后自动播第一句 */
    private fun loadLesson() {
        viewModelScope.launch {
            val meta = runCatching { app.nceCatalog.findByLevelKey("nce_$lessonNo") }.getOrNull()
            val lesson = meta?.let { runCatching { app.nceCatalog.loadLesson(it) }.getOrNull() }
            if (lesson == null || lesson.sentences.isEmpty()) {
                // 单课坏文件不让页面崩溃：给提示，用户可返回
                _uiState.update { it.copy(hint = "课文加载失败，请返回重试") }
                return@launch
            }
            val objectives = runCatching { app.nceCatalog.objectivesFor(lesson.levelKey) }.getOrDefault(emptyList())
            val collected = runCatching { app.vocabStore.getAll() }.getOrDefault(emptyList())
                .map { it.word.lowercase() }.toSet()
            // v10.5：进关不再自动播放原声，停在第一句等用户主动点播放
            // v11.15：进关即预检音频包——缺失立即置 audioMissing 显示导入横幅，
            // 不再等用户点播放/下一句才暴露（真机反馈：点下一句才知道没装）
            val audioMissing = runCatching {
                app.nceAudioResolver.resolve(lesson.mp3Path) == null
            }.getOrDefault(false)
            _uiState.update {
                it.copy(
                    lesson = lesson,
                    objectives = objectives,
                    collectedWords = collected,
                    phase = NcePhase.REPEAT,
                    audioMissing = audioMissing,
                    // v11.16：进关提示「点 ▶ 听原声，再按住跟读」按用户要求移除——
                    // hint 置空（提示行 isNotBlank 条件渲染，不塌陷）；错误/导入等
                    // 运行时提示仍走该行，「第 x / y 句」序号行保留不受影响
                    hint = ""
                )
            }
        }
    }

    /**
     * 按下（圆顶手势回调）。返回 false = 未真正启动录音（宿主据此复位圆顶）。
     * P0-① 非跟读态：明确提示"请先听原声"，不再静默无反应
     * P0-② 引擎忙（其他页残留/麦克风占用）：独立提示
     * v10.1 修复：检查通过后在此启动 ASR——此前无任何调用点，按住跟读永远收不到
     * Partial/Final（真机"语音识别卡死"的直接根因）。
     */
    fun onPress(): Boolean {
        val state = _uiState.value
        if (state.phase != NcePhase.REPEAT) {
            _uiState.update { it.copy(hint = "请先听原声，播放完再跟读") }
            return false
        }
        if (app.asrEngine.isListening.value) {
            _uiState.update { it.copy(hint = "麦克风正忙，稍候一秒再试") }
            return false
        }
        pressAtMs = SystemClock.elapsedRealtime()
        // 启动失败（不支持/引擎忙/ROM 抛异常）：明确提示并让圆顶复位，不静默弹空圆顶
        val started = app.asrEngine.startListening() // v11.5：云端 ASR，start 即开录（主线程调用）
        if (!started) {
            _uiState.update { it.copy(hint = "启动录音失败，稍候一秒再按住") }
            return false
        }
        return true
    }

    /** 正常松手（>=300ms 已由圆顶组件保证）：停引擎收 Final。极短按双保险兜底 */
    fun onRelease(elapsedMs: Long) {
        if (elapsedMs in 1 until MIN_PRESS_MS) {
            // 极短按：cancelListening 立即复位 + 事件隔离，不留迟到结果
            app.asrEngine.cancelListening()
            _uiState.update { it.copy(partial = "") }
            return
        }
        app.asrEngine.stopListening()
    }

    /** 取消/系统打断：cancelListening 立即复位 isListening + 隔离迟到事件，清实时文本 */
    fun abortListening() {
        ignoreNextAsrResult = true
        app.asrEngine.cancelListening()
        _uiState.update { it.copy(partial = "") }
    }

    /**
     * QA P2-4：滑入取消区瞬间触发——立即隔离本次已积累的识别结果，
     * 堵"停留取消区期间 ASR 提前吐 Final 被误消费"的竞态。
     */
    fun onEnterCancelZone() {
        ignoreNextAsrResult = true
        app.asrEngine.cancelListening()
        _uiState.update { it.copy(partial = "") }
    }

    /** 滑回恢复录音态（微信同款）：重启 ASR 会话继续听（旧会话已被 cancel 隔离） */
    fun onExitCancelZone() {
        ignoreNextAsrResult = false
        // v11.3 B 方案：cancel 已销毁实例，start 用全新实例——无 service 释放冷却需求，
        // 400ms 缩到 100ms 仅作事件落定缓冲
        viewModelScope.launch {
            kotlinx.coroutines.delay(100)
            // v11.2 D4：缓冲期内用户已再次按住（新会话接管）→ 跳过重启与提示
            if (app.asrEngine.isListening.value) return@launch
            val started = app.asrEngine.startListening()
            if (!started) {
                // 重启失败：提示用户重新按住，不让圆顶停在无引擎支撑的录音态
                _uiState.update { it.copy(hint = "录音中断，请重新按住跟读") }
            }
        }
    }

    /**
     * v11.3 识别中超时兜底（圆顶 8s 计时触发）：强制取消 + 收提示，
     * 不管引擎是否还活着——用户永远有出口，圆顶永远收得回来。
     */
    fun onRecognizingTimeout() {
        abortListening()
        _uiState.update { it.copy(hint = "识别超时，请再试一次") }
    }

    /** 在途原声播放协程（下一句钮过句时取消，防其完成回调覆盖新句状态） */
    private var playbackJob: kotlinx.coroutines.Job? = null

    /** v11.8：音频包导入成功 → 解除缺失态并自动重新加载当前课（REPEAT 态直接重播原声） */
    fun onAudioPackImported() {
        _uiState.update { it.copy(audioMissing = false, hint = "音频包导入成功") }
        if (_uiState.value.phase == NcePhase.REPEAT) playCurrent()
    }

    /** v11.8：导入失败提示（errorTick 脉冲驱动刷新） */
    fun onImportFailed(message: String?) {
        _uiState.update {
            it.copy(hint = message ?: "导入失败，请重试", errorTick = it.errorTick + 1)
        }
    }

    /** 播当前句原声（mp3 seek 优先；失败/缺失回退 TTS），播完进入 REPEAT */
    fun playCurrent() {
        val state = _uiState.value
        val lesson = state.lesson ?: return
        val sentence = lesson.sentences.getOrNull(state.currentIndex) ?: return
        // v11.8 音频出包：前置解析检查——缺失不碰播放器，置缺失态（横幅引导导入），
        // 页面其余功能（点词/lrc/跟读识别/TTS 兜底）照常，绝不崩溃
        if (app.nceAudioResolver.resolve(lesson.mp3Path) == null) {
            _uiState.update {
                it.copy(
                    phase = NcePhase.REPEAT, pendingReferenceMs = 0L, partial = "",
                    audioMissing = true,
                    hint = "音频包未安装：点横幅导入后可听原声，当前可直接按住跟读"
                )
            }
            return
        }
        _uiState.update { it.copy(phase = NcePhase.PLAYING, partial = "", hint = "", audioMissing = false) }
        playbackJob = viewModelScope.launch {
            var referenceMs = 0L
            if (lesson.mp3Path.isNotBlank()) {
                referenceMs = runCatching {
                    app.nceAudioPlayer.playSentence(lesson.mp3Path, sentence.startMs, sentence.endMs)
                }.getOrElse { 0L }
            }
            if (!isActive) return@launch // 页面退出/重开/过句取消时不再走 TTS 兜底
            if (referenceMs <= 0L) {
                // TTS 兜底：无原声或播放失败；参考时长未知（流利度给中间值）
                val ttsOk = runCatching {
                    val audio = app.ttsClient.synthesize(sentence.english)
                    app.audioPlayer.play(audio) {}
                }.isSuccess
                if (!isActive) return@launch // v11.7：过句取消在途播放时不得覆盖新句状态
                referenceMs = 0L
                // mp3 与 TTS 两路都拿不到原声：明确提示（不静默），用户仍可直接跟读
                if (!ttsOk) {
                    _uiState.update { it.copy(phase = NcePhase.REPEAT, pendingReferenceMs = 0L, hint = "原声播放失败，可直接按住跟读") }
                    return@launch
                }
            }
            _uiState.update { it.copy(phase = NcePhase.REPEAT, pendingReferenceMs = referenceMs) }
        }
    }

    /**
     * v11.7 下一句钮：快速过句（"下一句"名义的主动出口，非"跳过整关"回归）。
     * - 停当前原声播放（取消在途 playCurrent 协程 + 停 mp3/TTS 双路）；
     *   圆顶录音激活时按钮禁用不可达，此处再兜底 abort
     * - 本句【不计 attempt】：不写成绩单输入、不计开口统计（addSpeakingSeconds/
     *   addRepeatSentences 均不走），与正常跟读完成/失败明确区分
     * - 推进 = 前进一句并自动播下一句原声（与正常跟读完成后 advance() 同路径）；
     *   最后一句点击 → 整课成绩单（finishLesson；attempts 为空时沿用其
     *   "至少跟读一句才算完成本课"兜底提示，不写通关成绩，不构成跳过整关）
     */
    fun skipCurrent() {
        val state = _uiState.value
        val lesson = state.lesson ?: return
        if (state.phase == NcePhase.REPORT || state.phase == NcePhase.LOADING) return
        playbackJob?.cancel()
        playbackJob = null
        runCatching { app.nceAudioPlayer.stop() }
        runCatching { app.audioPlayer.stop() }
        if (app.asrEngine.isListening.value) abortListening()
        val next = state.currentIndex + 1
        if (next < lesson.sentences.size) {
            _uiState.update { it.copy(currentIndex = next, partial = "", hint = "", pendingReferenceMs = 0L) }
            playCurrent()
        } else {
            finishLesson()
        }
    }

    /**
     * v11.13 上一句钮：回退一句重学。
     * - 停当前原声播放 + 兜底 abort（与 skipCurrent 同套停止逻辑）；
     * - 本句【不计 attempt】：不写成绩单输入、不计开口统计（与 skipCurrent 同口径）；
     * - 回退 = currentIndex-1 并自动播上一句原声；
     * - 第一句时 UI 层禁用不可达，此处 prev<0 兜底不动。
     */
    fun skipToPrevious() {
        val state = _uiState.value
        val lesson = state.lesson ?: return
        if (state.phase == NcePhase.REPORT || state.phase == NcePhase.LOADING) return
        playbackJob?.cancel()
        playbackJob = null
        runCatching { app.nceAudioPlayer.stop() }
        runCatching { app.audioPlayer.stop() }
        if (app.asrEngine.isListening.value) abortListening()
        val prev = state.currentIndex - 1
        if (prev >= 0) {
            _uiState.update { it.copy(currentIndex = prev, partial = "", hint = "", pendingReferenceMs = 0L) }
            playCurrent()
        }
    }

    /** ASR 事件处理：Partial 上屏 / Final 记分并推进 / Error 提示重试 */
    private fun onAsrEvent(event: AsrEvent) {
        val state = _uiState.value
        if (state.phase != NcePhase.REPEAT) return // 播放中/成绩单阶段不消费
        when (event) {
            is AsrEvent.Partial -> {
                if (ignoreNextAsrResult) return
                _uiState.update { it.copy(partial = event.text, hint = "") }
            }

            is AsrEvent.Final -> {
                if (ignoreNextAsrResult) {
                    ignoreNextAsrResult = false
                    return
                }
                val lesson = state.lesson ?: return
                val index = state.currentIndex
                if (index >= lesson.sentences.size) return
                // 识别即过：不卡准确度，成绩单里再算相似度
                _uiState.update {
                    it.copy(
                        attempts = it.attempts + (
                            index to NceAttempt(
                                sentenceIndex = index,
                                recognized = event.text,
                                repeatMs = event.durationMs,
                                referenceMs = it.pendingReferenceMs
                            )
                            ),
                        partial = ""
                    )
                }
                // 打卡：任何开口计入（PM 口径），至少 1 秒；每日跟读句数 +1（统计页数据源）
                viewModelScope.launch {
                    runCatching {
                        app.statsStore.addSpeakingSeconds(
                            (event.durationMs / 1000L).toInt().coerceAtLeast(1),
                            newTurn = true
                        )
                        app.dailyStatsStore.addRepeatSentences(1)
                    }
                }
                advance()
            }

            is AsrEvent.Error -> {
                if (ignoreNextAsrResult) {
                    ignoreNextAsrResult = false
                    return
                }
                // v11.3 报错即收：errorTick 递增（同文案连续报错时 hint 不变，
                // LaunchedEffect(state.hint) 不会重触发——必须靠独立脉冲驱动圆顶收起）
                _uiState.update { it.copy(partial = "", hint = event.message, errorTick = it.errorTick + 1) }
            }
        }
    }

    /** 前进一句；最后一句之后出整课成绩单 */
    private fun advance() {
        val lesson = _uiState.value.lesson ?: return
        val next = _uiState.value.currentIndex + 1
        if (next < lesson.sentences.size) {
            _uiState.update { it.copy(currentIndex = next) }
            playCurrent()
        } else {
            finishLesson()
        }
    }

    /**
     * 整课完成：本地打分 + 通关记录（cleared=true，bestScore 只升不降）。
     * 第二轮反馈：跳过入口已全部移除，逐句完成才到得了这里。
     * PM v3 2b：最高分刷新提示 + 里程碑徽章（10/50/100/276）。
     */
    private fun finishLesson() {
        val state = _uiState.value
        val lesson = state.lesson ?: return
        // 评分异常兜底：不让计算异常把整课成果吞掉，明确提示可重试
        val report = runCatching {
            NceScorer.score(
                levelKey = lesson.levelKey,
                lessonNo = lesson.no,
                sentences = lesson.sentences,
                attempts = state.attempts
            )
        }.getOrElse {
            Log.e(TAG, "NceScorer.score 异常", it)
            _uiState.update { s -> s.copy(hint = "成绩计算异常，请点\"再来一次\"重试") }
            return
        }
        _uiState.update {
            it.copy(
                phase = NcePhase.REPORT,
                report = report,
                partial = "",
                hint = if (state.attempts.isEmpty()) "至少跟读一句才算完成本课，下一课还没有解锁哦" else ""
            )
        }
        if (state.attempts.isEmpty()) return // 理论走不到（无跳过入口后逐句必完成），保留兜底
        viewModelScope.launch {
            runCatching {
                app.dailyStatsStore.addClearedLesson(1) // 每日通关计数（统计页数据源）
                val oldBest = app.levelProgressStore.getState(lesson.levelKey).bestScore
                val oldCleared = app.levelProgressStore.countCleared()
                app.levelProgressStore.recordScore(lesson.levelKey, report.total)
                val newCleared = app.levelProgressStore.countCleared()
                val milestone = NceMilestones.crossed(oldCleared, newCleared).lastOrNull()
                val bestRefreshed = if (oldBest in 1 until report.total) {
                    "$oldBest → ${report.total}"
                } else {
                    null
                }
                _uiState.update { it.copy(milestone = milestone, bestRefreshed = bestRefreshed) }
            }
        }
    }

    /** 成绩单"再来一次"：清空本轮进度从第一句重来 */
    fun retry() {
        aiReviewRequested = false
        _uiState.update { state ->
            NceRepeatUiState(lesson = state.lesson, objectives = state.objectives, collectedWords = state.collectedWords)
        }
        playCurrent()
    }

    // ---------- AI 教练点评（第二轮反馈需求 1） ----------

    private var aiReviewRequested = false

    /**
     * 进成绩单时发起一次 AI 点评（走 LlmRouter 跟随设置；哈希缓存到 cacheDir，
     * 同成绩重复进页不重复调用；失败由生成器内部落本地简评，不阻塞成绩单）。
     */
    fun loadAiReview() {
        val report = _uiState.value.report ?: return
        if (aiReviewRequested) return
        aiReviewRequested = true
        viewModelScope.launch {
            val review = runCatching {
                app.aiCoachReviewGenerator.generate(report, _uiState.value.attempts)
            }.getOrElse {
                // 生成器内部已兜底，这里再兜一层：极端异常也不阻塞成绩单
                AiCoachReview(
                    overall = "本课完成了，继续保持每天跟读的节奏。",
                    problems = emptyList(),
                    encouragement = "你已经在快够到的位置了，加油！",
                    isLocal = true
                )
            }
            _uiState.update { it.copy(aiReview = review, aiReviewLocal = review.isLocal) }
        }
    }

    // ---------- 点词释义词卡（PM v3 需求 4） ----------

    /** 点句中词：三级查询（内置词典 → 本地缓存 → LLM），弹底部词卡 */
    fun lookupWord(rawToken: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(wordCard = WordCardUi(rawToken = rawToken, entry = null, loading = true, inVocab = false))
            }
            val entry = runCatching { app.dictStore.lookup(rawToken) }.getOrNull()
            val normalized = rawToken.trim().trim('.', ',', '!', '?', ';', ':', '"', '\'').lowercase()
            val inVocab = runCatching { app.vocabStore.contains(normalized) }.getOrDefault(false)
            _uiState.update { state ->
                // 词卡可能已被关闭/换词，只更新仍然匹配的查询结果
                if (state.wordCard?.rawToken == rawToken) {
                    state.copy(wordCard = state.wordCard?.copy(entry = entry, loading = false, inVocab = inVocab))
                } else {
                    state
                }
            }
        }
    }

    /** 词卡【加入单词本】：带音标/释义/例句收进 VocabStore（来源 "nce_l{no}"） */
    fun collectCurrentWord() {
        val card = _uiState.value.wordCard ?: return
        val lesson = _uiState.value.lesson ?: return
        val entry = card.entry ?: return
        viewModelScope.launch {
            val added = runCatching {
                app.vocabStore.add(
                    word = NotableWord(
                        word = entry.word,
                        phonetic = entry.phonetic,
                        meaning = entry.meaning,
                        example = if (entry.exampleZh.isBlank()) entry.example
                        else "${entry.example}（${entry.exampleZh}）"
                    ),
                    sourceScenarioId = "nce_l${lesson.no}"
                )
            }.getOrDefault(false)
            _uiState.update {
                it.copy(
                    collectedWords = it.collectedWords + entry.word.lowercase(),
                    wordCard = it.wordCard?.copy(inVocab = true),
                    hint = if (added) "已收入单词本「${entry.word}」" else "「${entry.word}」已在单词本里"
                )
            }
        }
    }

    /** 关闭词卡 */
    fun closeWordCard() {
        _uiState.update { it.copy(wordCard = null) }
    }
}

/**
 * NCE 逐句跟读页（v3：圆顶录音组件 + 点词词卡 + 奖励感）：
 * 顶栏进度 → 学习目标（含奖励行）→ 句子卡（点词弹词卡）→ 底部固定操作区（圆顶按住跟读）；
 * 整课完成弹成绩单（星级 / 三维分 / 评语 / 徽章卡 / 你学会了 / 再来一次）。
 * 原声走 NceAudioPlayer（lrc 时间戳 seek mp3），缺失/失败回退现有 TTS 路由。
 */
@Composable
fun NceRepeatScreen(
    lessonNo: Int,
    onBack: () -> Unit
) {
    val app = LocalContext.current.applicationContext as EnglishCoachApp
    val viewModel: NceRepeatViewModel = viewModel(
        key = "nce_$lessonNo", // 同课重进复用，不同课独立实例
        factory = NceRepeatViewModel.factory(app, lessonNo)
    )
    val state by viewModel.uiState.collectAsState()
    val dome = rememberVoiceDomeState()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    // v11.6 P0-2：录音实时音量转发 → 圆顶声波条/高度呼吸/涟漪；P1-2 取消态触感宿主
    LaunchedEffect(Unit) {
        app.asrEngine.rmsLevel.collect { dome.rms = it }
    }
    val hapticView = androidx.compose.ui.platform.LocalView.current

    // v11.8 音频包导入（SAF 选 zip → 后台流式解压+276 校验 → 成功自动重载当前课）；
    // 解压期间 importBusy=true 禁止重复触发，横幅显示已解压数/276
    var importBusy by remember { mutableStateOf(false) }
    var importProgress by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importBusy = true
                importProgress = 0
                val result = app.nceAudioResolver.importFromZip(uri) { importProgress = it }
                importBusy = false
                result.onSuccess { viewModel.onAudioPackImported() }
                    .onFailure { viewModel.onImportFailed(it.message) }
            }
        }
    }

    // v11.23 音频包在线同步（GitHub raw 逐文件下载；文件粒度断点续传——已下载自动跳过，
    // 中断/取消后再点即续传）。进度状态与 zip 导入相互独立，同步中两入口均禁用
    var syncBusy by remember { mutableStateOf(false) }
    var syncDone by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var syncTotal by remember { androidx.compose.runtime.mutableIntStateOf(276) }
    var syncFailed by remember { mutableStateOf<List<String>>(emptyList()) }
    fun startAudioSync() {
        if (syncBusy || importBusy) return
        syncBusy = true
        syncDone = 0
        syncFailed = emptyList()
        scope.launch {
            val report = app.nceAudioDownloader.sync { done, total ->
                syncDone = done
                syncTotal = total
            }
            syncBusy = false
            syncFailed = report.failedPaths
            // 全部成功才解除缺失态并重载当前课；有失败保留横幅（点同步即重试，已下载不重下）
            if (report.failedPaths.isEmpty()) {
                viewModel.onAudioPackImported()
            }
        }
    }

    // P0-④⑤：系统打断（来电/切后台 ON_PAUSE）停引擎并复位圆顶；页面销毁补停 ASR + 原声
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                viewModel.abortListening()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            app.asrEngine.stopListening()
            app.nceAudioPlayer.stop()
        }
    }

    // 圆顶收起联动：识别出结果（进入下一句播放/成绩单）或出现错误提示时收起
    LaunchedEffect(state.phase, state.hint) {
        if (state.phase != NcePhase.REPEAT || state.hint.isNotBlank()) {
            dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
        }
    }
    // v11.3 报错即收：errorTick 独立脉冲——同文案连续报错时 hint 值不变不会重触发上面那条，
    // 必须靠 tick 强制收圆顶，杜绝"识别中弹窗关不掉"
    LaunchedEffect(state.errorTick) {
        if (state.errorTick > 0) {
            dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
        }
    }

    val report = state.report
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PageBg)
        ) {
            if (state.phase == NcePhase.REPORT && report != null) {
                // ---------- 成绩单（v8 ④ + v3 徽章/刷新） ----------
                ScoreReportPanel(
                    report = report,
                    lesson = state.lesson,
                    milestone = state.milestone,
                    bestRefreshed = state.bestRefreshed,
                    hint = state.hint,
                    aiReview = state.aiReview,
                    aiReviewLocal = state.aiReviewLocal,
                    onLoadAiReview = { viewModel.loadAiReview() },
                    onRetry = { viewModel.retry() },
                    onFinish = onBack
                )
            } else {
                // ---------- 顶栏 ----------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = TextPrimary
                        )
                    }
                    Text(
                        text = "第 ${lessonNo} 关 · ${state.lesson?.title.orEmpty()}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // v11.13：播放原句钮改轻量 icon 风格（48dp IconButton、无背景无阴影，
                    // VolumeUp 24dp HyperBlue；禁用灰 #C6C7CC）。点击行为不变：
                    // REPEAT 播放 / PLAYING 中再点停播（playCurrent 内部处理）
                    IconButton(
                        onClick = { viewModel.playCurrent() },
                        enabled = state.phase == NcePhase.REPEAT || state.phase == NcePhase.PLAYING,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VolumeUp,
                            contentDescription = "播放原句",
                            tint = if (state.phase == NcePhase.REPEAT || state.phase == NcePhase.PLAYING) {
                                HyperBlue
                            } else {
                                Color(0xFFC6C7CC)
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    // v11.14：上一句钮已移至底部行（与下一句对称分列橙胶囊两侧），顶栏只留
                    // 返回 + 标题 + 播放（轻量 icon 风格）
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // ---------- 课头：逐句跟读 + 句进度（2b：最后 2 句冲刺提示） ----------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "逐句跟读",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            val remain = state.remainToClear
                            Text(
                                text = if (state.phase == NcePhase.REPEAT && remain in 1..2) {
                                    "还差 $remain 句就通关，冲！"
                                } else {
                                    "先听原声，再跟读一遍"
                                },
                                fontSize = 12.sp,
                                color = if (state.phase == NcePhase.REPEAT && remain in 1..2) XiaomiOrange else TextSecondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (state.sentenceCount > 0) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${state.currentIndex + 1} / ${state.sentenceCount} 句",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = HyperBlue
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .width(86.dp)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(TextPrimary.copy(alpha = 0.05f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(
                                                (state.currentIndex.toFloat() / state.sentenceCount)
                                                    .coerceIn(0f, 1f)
                                            )
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(Color(0xFF5CA9FF), HyperBlue)
                                                )
                                            )
                                    )
                                }
                            }
                        }
                    }

                    // ---------- 学习目标（v8 group 样式 + v3 奖励行） ----------
                    if (state.objectives.isNotEmpty()) {
                        Text(
                            text = "通过本关卡，你将学会什么",
                            fontSize = 13.5.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 8.dp)
                        )
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp)
                        ) {
                            Column {
                                state.objectives.forEachIndexed { index, objective ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.5.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(HyperBlue.copy(alpha = 0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${index + 1}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = HyperBlue
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            // 兼容两种数据：占位数据带"跟读并学会说：「」"壳，LLM 正式数据为纯句子
                                            text = objective.removePrefix("跟读并学会说：「").removeSuffix("」"),
                                            fontSize = 14.sp,
                                            color = TextPrimary,
                                            lineHeight = 21.sp
                                        )
                                    }
                                    if (index < state.objectives.lastIndex) {
                                        Box(
                                            modifier = Modifier
                                                .padding(start = 16.dp)
                                                .fillMaxWidth()
                                                .height(0.5.dp)
                                                .background(Hairline)
                                        )
                                    }
                                }
                                // 2b-① 奖励行
                                Box(
                                    modifier = Modifier
                                        .padding(start = 16.dp)
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(Hairline)
                                )
                                Text(
                                    text = "通关可得：⭐成绩单 · 解锁下一关 · 徽章进度",
                                    fontSize = 11.5.sp,
                                    color = HyperBlue.copy(alpha = 0.85f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }

                    // ---------- 当前句大卡（v8 sent-card 浅蓝渐变，点词弹词卡） ----------
                    val sentence = state.currentSentence
                    if (sentence != null) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 18.dp, vertical = 14.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(Brush.verticalGradient(listOf(SentCardBg1, SentCardBg2)))
                                .padding(horizontal = 20.dp, vertical = 18.dp)
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(HyperBlue.copy(alpha = 0.12f))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "跟读这一句",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = HyperBlue
                                    )
                                }
                                // 英文按词拆分：点词弹释义词卡；已在词本的词加下划线（v3 需求 4）
                                Spacer(modifier = Modifier.height(12.dp))
                                WordTapText(
                                    english = sentence.english,
                                    collectedWords = state.collectedWords,
                                    onWordTap = { viewModel.lookupWord(it) }
                                )
                                Spacer(modifier = Modifier.height(9.dp))
                                Text(
                                    text = sentence.chinese,
                                    fontSize = 13.5.sp,
                                    color = TextSecondary,
                                    lineHeight = 20.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                // 卡内底部提示行（hairline 分隔）
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(Color(0x243482FF))
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "点句中词语查释义、收进单词本",
                                    fontSize = 11.5.sp,
                                    color = TextSecondary.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ---------- 固定底部操作区（第二轮反馈需求 4：大胶囊跟读钮为唯一主角，留白收敛） ----------
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PageBg)
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    val repeatEnabled = state.phase == NcePhase.REPEAT

                    // v11.8 音频包未安装横幅（固定底部操作区顶部常驻可见；导入/同步中显示进度禁重复触发）
                    // v11.23：新增"在线同步"次级入口（GitHub 直链 ≈647MB 建议Wi-Fi；断点续传）
                    if (state.audioMissing && state.phase != NcePhase.REPORT) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFFF4E5))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "音频包未安装",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = XiaomiOrange
                                )
                                Text(
                                    text = when {
                                        syncBusy -> "在线同步中 $syncDone/$syncTotal · 已下载文件自动跳过"
                                        syncFailed.isNotEmpty() ->
                                            "${syncFailed.size} 个失败，点在线同步重试（已下载不重下）"
                                        else -> "导入后可听 NCE 原声；当前仍可直接跟读"
                                    },
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
                            if (importBusy) {
                                Text(
                                    text = "导入中 $importProgress / 276",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextSecondary
                                )
                            } else if (syncBusy) {
                                // v11.23 同步中：进度 + 取消（已下载文件保留，取消后可续传）
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "同步中 $syncDone/$syncTotal",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "取消",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextSecondary,
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(TextSecondary.copy(alpha = 0.10f))
                                            .clickable { app.nceAudioDownloader.cancel() }
                                            .padding(horizontal = 12.dp, vertical = 5.dp)
                                    )
                                }
                            } else {
                                // v11.23 双入口：在线同步（次级）/ SAF 导入 zip（原有）
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "在线同步 ≈647MB",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextSecondary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(TextSecondary.copy(alpha = 0.10f))
                                            .clickable { startAudioSync() }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "导入音频包",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = HyperBlue,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(HyperBlue.copy(alpha = 0.10f))
                                            .clickable {
                                                importLauncher.launch(
                                                    arrayOf("application/zip", "application/octet-stream")
                                                )
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 动态反馈行（实时识别 / 错误提示，非静态描述）
                    if (repeatEnabled && state.partial.isNotBlank()) {
                        Text(
                            text = state.partial,
                            fontSize = 14.sp,
                            color = TextPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    } else if (repeatEnabled && state.hint.isNotBlank()) {
                        Text(
                            text = state.hint,
                            fontSize = 12.sp,
                            color = XiaomiOrange,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // 当前句提示行（v11.5：播放功能移至右侧圆钮，此行只保留序号/播放中状态）
                    Text(
                        text = if (state.phase == NcePhase.PLAYING) {
                            "原声播放中…"
                        } else {
                            "第 ${state.currentIndex + 1} / ${state.sentenceCount} 句"
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 2.dp)
                    )

                    // 底部行（v11.14 三元素对称）：上一句 icon 钮 + 按住 跟读 橙胶囊(weight) + 下一句 icon 钮。
                    // 两侧 icon 钮同款轻量风格：48dp IconButton 无背景无阴影，24dp 双箭头图标
                    // （KeyboardDoubleArrowLeft/Right，与播放三角形态区分防误读）；可用 HyperBlue / 禁用灰 #C6C7CC；
                    // 间距 10dp。行为沿用 skipToPrevious()/skipCurrent()（过句不计 attempt，PLAYING 中可点=停播跳转）
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 6.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val domeActive = dome.phase != com.englishcoach.ui.common.DomePhase.HIDDEN
                        // 上一句：第一句 / LOADING / REPORT / 圆顶激活 禁用；PLAYING 可点（停播回退）
                        val prevEnabled = state.currentIndex > 0 &&
                            state.phase != NcePhase.LOADING &&
                            state.phase != NcePhase.REPORT &&
                            !domeActive
                        // 下一句：PLAYING 或 REPEAT 可用；圆顶激活/成绩单/加载 禁用（原 skipEnabled 判定式不变）
                        val skipEnabled = (state.phase == NcePhase.PLAYING || repeatEnabled) && !domeActive
                        IconButton(
                            onClick = { viewModel.skipToPrevious() },
                            enabled = prevEnabled,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardDoubleArrowLeft,
                                contentDescription = "上一句",
                                tint = if (prevEnabled) HyperBlue else Color(0xFFC6C7CC),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        // 按住 跟读（大胶囊，圆顶手势宿主；录音视觉由 VoiceDomeOverlay 全屏接管）
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(
                                    if (repeatEnabled) {
                                        Brush.horizontalGradient(listOf(HoldOrange1, HoldOrange2))
                                    } else {
                                        Brush.horizontalGradient(listOf(Color(0xFFC6C7CC), Color(0xFF9EA0A8)))
                                    }
                                )
                                .voiceDomeGesture(
                                    state = dome,
                                    enabled = repeatEnabled,
                                    onPressStart = {
                                        // P0-①②：非跟读态/引擎忙时 VM 给明确提示且不启动录音
                                        val ok = viewModel.onPress()
                                        if (!ok) dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                                    },
                                    onPressCancel = {
                                        // 上滑取消 / <300ms 防误触：丢弃本次结果
                                        viewModel.abortListening()
                                    },
                                    onEnterCancel = {
                                        // QA P2-4：滑入取消区瞬间隔离本次结果（防提前吐 Final 的竞态）
                                        viewModel.onEnterCancelZone()
                                    },
                                    onExitCancel = {
                                        // 滑回恢复：重启 ASR 会话继续听
                                        viewModel.onExitCancelZone()
                                    },
                                onPressRelease = { elapsed ->
                                    // 正常松手：停引擎收 Final（<300ms 双保险在 VM 再拦一次）
                                    viewModel.onRelease(elapsed)
                                },
                                // v11.6 P1-2：滑入取消区触感一下
                                hapticFeedback = {
                                    hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Mic,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "按住 跟读",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        // 下一句 icon 钮（右侧，与上一句对称同款）：最后一句仍可点 → 进成绩单；
                        // 原文字胶囊的时机 alpha（PLAYING 1.0/REPEAT 0.75）随样式移除，
                        // 以可用蓝/禁用灰区分（简单为准）
                        IconButton(
                            onClick = { viewModel.skipCurrent() },
                            enabled = skipEnabled,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardDoubleArrowRight,
                                contentDescription = "下一句",
                                tint = if (skipEnabled) HyperBlue else Color(0xFFC6C7CC),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        // 微信式圆顶录音 overlay（1b：三页共用组件）
        VoiceDomeOverlay(
            state = dome,
            recordHint = "松开跟读",
            cancelHint = "松开取消",
            // v11.3：识别中点按任意区域 = 等价取消——用户永远有出口
            onRecognizingTap = {
                viewModel.abortListening()
                dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
            },
            // v11.3：识别中超 8s 强制收回 + 提示（不管引擎是否还活着）
            onRecognizingTimeout = {
                viewModel.onRecognizingTimeout()
                dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
            }
        )

        // ---------- 点词释义词卡（v3 需求 4） ----------
        state.wordCard?.let { card ->
            WordCardSheet(
                card = card,
                onSpeak = { word ->
                    // 发音复用 TtsRouter（磁盘缓存/在途去重/增益播放链路）
                    scope.launch {
                        runCatching {
                            val audio = app.ttsClient.synthesize(word)
                            app.audioPlayer.play(audio) {}
                        }
                    }
                },
                onCollect = { viewModel.collectCurrentWord() },
                onDismiss = { viewModel.closeWordCard() }
            )
        }
    }
}

/**
 * 点词释义词卡底部弹层：词 + 音标 + 发音钮 + 释义 + 例句 + 【加入单词本】/已在单词本。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WordCardSheet(
    card: WordCardUi,
    onSpeak: (String) -> Unit,
    onCollect: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val syllablePlayer = com.englishcoach.ui.common.rememberSyllablePlayer()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            val entry = card.entry
            // 词 + 音标 + 发音钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.rawToken.trim('.', ',', '!', '?', ';', ':', '"', '\''),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    if (entry != null && entry.phonetic.isNotBlank()) {
                        Text(
                            text = entry.phonetic,
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(HyperBlue)
                        .clickable { onSpeak(card.rawToken) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "发音",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            // 分段发音区（需求 v5：点段发音 + 整词慢读/分段连读；词表外自动降级）
            com.englishcoach.ui.common.SyllableBar(
                word = card.rawToken,
                player = syllablePlayer
            )

            Spacer(modifier = Modifier.height(14.dp))
            if (card.loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = HyperBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "查询释义中…", fontSize = 13.sp, color = TextSecondary)
                }
            } else if (entry == null) {
                Text(
                    text = "暂时查不到这个词的释义，稍后再试试",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            } else {
                // 释义
                Text(
                    text = entry.meaning,
                    fontSize = 15.sp,
                    color = TextPrimary,
                    lineHeight = 22.sp
                )
                // 例句
                if (entry.example.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = HyperBlue.copy(alpha = 0.06f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = entry.example,
                                fontSize = 13.5.sp,
                                color = TextPrimary,
                                lineHeight = 19.sp
                            )
                            if (entry.exampleZh.isNotBlank()) {
                                Text(
                                    text = entry.exampleZh,
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                    }
                }
                // 加入单词本 / 已在单词本
                Spacer(modifier = Modifier.height(16.dp))
                val inVocab = card.inVocab
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (inVocab) HyperBlue.copy(alpha = 0.10f) else HyperBlue)
                        .clickable(enabled = !inVocab, onClick = onCollect),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (inVocab) "✓ 已在单词本" else "加入单词本",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (inVocab) HyperBlue else Color.White
                    )
                }
            }
        }
    }
}

/** 英文按词拆分展示：点词弹词卡；已在单词本的词下划线 + 紫色（v3 需求 4 样式区分） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordTapText(
    english: String,
    collectedWords: Set<String>,
    onWordTap: (String) -> Unit
) {
    // 按空白切词，保留原始标点用于展示，点击时由 ViewModel 去标点
    val tokens = english.split(Regex("\\s+")).filter { it.isNotBlank() }
    FlowRow(modifier = Modifier.fillMaxWidth()) {
        tokens.forEachIndexed { index, token ->
            val clean = token.trim('.', ',', '!', '?', ';', ':', '"', '\'').lowercase()
            val collected = collectedWords.contains(clean)
            Text(
                text = if (index == tokens.lastIndex) token else "$token ",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 38.sp,
                color = if (collected) NcePurple2 else TextPrimary,
                textDecoration = if (collected) TextDecoration.Underline else null,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onWordTap(token) }
                    .padding(vertical = 1.dp)
            )
        }
    }
}

/**
 * 整课成绩单（v8 ④ + v3 奖励感）：
 * 星级 → 总分 → 徽章卡（里程碑达成时）/最高分刷新 caption → AI 评语卡 → 三卡维度 → 你学会了 → 行动引导。
 */
@Composable
private fun ScoreReportPanel(
    report: NceScoreReport,
    lesson: NceLesson?,
    milestone: Int?,
    bestRefreshed: String?,
    hint: String,
    aiReview: AiCoachReview?,
    aiReviewLocal: Boolean,
    onLoadAiReview: () -> Unit,
    onRetry: () -> Unit,
    onFinish: () -> Unit
) {
    // 进成绩单即请求 AI 教练点评（幂等：VM 内部防重复发起）
    LaunchedEffect(Unit) { onLoadAiReview() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onFinish) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextPrimary
                )
            }
            Text(
                text = "第 ${report.lessonNo} 关 · 成绩单",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }

        // ---------- 总分 + 星级（score-hero） ----------
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${report.total}",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary,
                    letterSpacing = (-2.5).sp
                )
                Text(
                    text = " / 100",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary.copy(alpha = 0.35f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            Spacer(modifier = Modifier.height(9.dp))
            // 星级：金星 #FFB300，未得星灰 15%
            Row {
                repeat(3) { i ->
                    Text(
                        text = "★",
                        fontSize = 23.sp,
                        color = if (i < report.stars) StarGold else StarOff,
                        modifier = Modifier.padding(horizontal = 2.5.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            // caption：最高分刷新优先展示（2b-④），否则鼓励式文案
            val caption = when {
                bestRefreshed != null -> "最高分刷新！$bestRefreshed 🎉"
                report.stars >= 3 -> "第 ${report.lessonNo} 关完成！三星满分，稳！"
                else -> "第 ${report.lessonNo} 关完成！再涨 ${90 - report.total} 分就是三星"
            }
            Text(text = caption, fontSize = 13.5.sp, color = TextSecondary)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ---------- 里程碑徽章卡（2b-③：达成当次插入） ----------
        if (milestone != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFFFF6E0),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "🎉", fontSize = 26.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "第 $milestone 关达成！${NceMilestones.label(milestone)}徽章点亮",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "累计通关 $milestone 课，继续冲下一枚！",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ---------- AI 评语卡（白卡 + 紫渐变圆标 icon） ----------
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(NcePurple1, NcePurple2))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "AI", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(modifier = Modifier.width(11.dp))
                Column {
                    report.commentLines.forEachIndexed { index, line ->
                        Text(
                            text = line,
                            fontSize = 14.sp,
                            color = TextPrimary,
                            lineHeight = 22.sp
                        )
                        if (index < report.commentLines.lastIndex) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                    if (hint.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = hint, fontSize = 11.5.sp, color = XiaomiOrange)
                    }
                }
            }
        }

        // ---------- AI 教练点评卡（第二轮反馈需求 1：LLM 结构化点评 / 本地简评兜底） ----------
        AiCoachReviewCard(review = aiReview, isLocal = aiReviewLocal)

        Spacer(modifier = Modifier.height(12.dp))

        // ---------- 三卡横排维度分（v8 dims） ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DimensionCard("准确度", "50%", report.accuracy, Modifier.weight(1f))
            DimensionCard("完整度", "30%", report.completeness, Modifier.weight(1f))
            DimensionCard("流利度", "20%", report.fluency, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ---------- 你学会了（v8 group：绿勾圆标 + hairline） ----------
        if (lesson != null && report.sentenceScores.isNotEmpty()) {
            Text(
                text = "这一关，你学会了 ${report.sentenceScores.count { it.completed }} / ${report.sentenceScores.size} 句",
                fontSize = 13.5.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 18.dp, bottom = 8.dp)
            )
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
            ) {
                Column {
                    report.sentenceScores.forEachIndexed { index, score ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (score.completed) {
                                            Color(0xFF43CF7C).copy(alpha = 0.16f)
                                        } else {
                                            TextPrimary.copy(alpha = 0.05f)
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (score.completed) "✓" else "○",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (score.completed) {
                                        Color(0xFF30A24C)
                                    } else {
                                        TextSecondary.copy(alpha = 0.4f)
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = score.sentence.english,
                                    fontSize = 14.sp,
                                    color = if (score.completed) TextPrimary else TextSecondary,
                                    lineHeight = 20.sp
                                )
                                Text(
                                    text = score.sentence.chinese,
                                    fontSize = 12.sp,
                                    color = TextSecondary.copy(alpha = 0.7f),
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        if (index < report.sentenceScores.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 16.dp)
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(Hairline)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---------- 行动引导：主 CTA（actionLine）+ 次级完成 ----------
        Box(
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(HyperBlue)
                .clickable(onClick = onRetry),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = report.actionLine,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
        Text(
            text = "返回关卡轨道 →",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = HyperBlue,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onFinish)
                .padding(vertical = 12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * AI 教练点评卡（第二轮反馈需求 1）：
 * review == null → 加载中骨架占位；isLocal → 标注"本地简评"；逐句渲染问题 + 建议。失败不阻塞成绩单其他内容。
 */
@Composable
private fun AiCoachReviewCard(review: AiCoachReview?, isLocal: Boolean) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 13.dp)
        ) {
            // 卡头：紫渐变 AI 圆标 + 标题 +（可选）本地简评徽标
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(NcePurple1, NcePurple2))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "AI", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(modifier = Modifier.width(11.dp))
                Text(
                    text = "AI 教练点评",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (isLocal) {
                    Text(
                        text = "本地简评",
                        fontSize = 10.sp,
                        color = TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(TextPrimary.copy(alpha = 0.06f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when {
                // 加载中：骨架占位（三行灰条呼吸）
                review == null -> {
                    val alpha = rememberInfiniteTransition(label = "skeleton").animateFloat(
                        initialValue = 0.35f,
                        targetValue = 0.8f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(700, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "skeletonA"
                    )
                    repeat(3) { i ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (i == 2) 0.55f else 0.92f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(TextPrimary.copy(alpha = alpha.value * 0.18f))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = "AI 教练点评生成中…",
                        fontSize = 11.5.sp,
                        color = TextSecondary.copy(alpha = 0.7f)
                    )
                }

                else -> {
                    // 总评一句
                    Text(
                        text = review.overall,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        lineHeight = 22.sp
                    )
                    // 逐句问题 + 建议
                    review.problems.forEach { problem ->
                        Spacer(modifier = Modifier.height(9.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "第 ${problem.sentenceIdx} 句",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = HyperBlue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(HyperBlue.copy(alpha = 0.10f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = problem.issue,
                                    fontSize = 13.sp,
                                    color = TextPrimary,
                                    lineHeight = 19.sp
                                )
                                Text(
                                    text = "建议：${problem.tip}",
                                    fontSize = 12.5.sp,
                                    color = TextSecondary,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                    // 鼓励一句
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = review.encouragement,
                        fontSize = 13.sp,
                        color = CorrectGreen,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}

/** 维度小分卡（v8 dim：名称+权重 / 大数字） */
@Composable
private fun DimensionCard(label: String, weight: String, value: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 11.sp, color = TextSecondary)
            Text(
                text = weight,
                fontSize = 9.5.sp,
                color = TextSecondary.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 1.dp)
            )
            Text(
                text = "$value",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
