package com.englishcoach.ui.word

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englishcoach.EnglishCoachApp
import com.englishcoach.core.model.WordItem
import com.englishcoach.data.DictStore
import com.englishcoach.speech.AsrEvent
import com.englishcoach.ui.common.WaveBarsIndicator
import com.englishcoach.ui.common.VoiceDomeOverlay
import com.englishcoach.ui.common.rememberVoiceDomeState
import com.englishcoach.ui.common.voiceDomeGesture
import com.englishcoach.ui.theme.CorrectGreen
import com.englishcoach.ui.theme.ErrorRed
import com.englishcoach.ui.theme.HyperBlue
import com.englishcoach.ui.theme.PageBg
import com.englishcoach.ui.theme.TextPrimary
import com.englishcoach.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 单词跟读页位置记忆（App 级单例，需求 5）：
 * 离开单词跟读页再回来必须停在离开时的那个单词——当前索引存在这里，
 * 不在 Composable 进入时重置。EnglishCoachApp 持有唯一实例。
 */
class WordPracticeState {
    var lastIndex: Int = -1 // -1 = 尚未进入过（首次进入用入口单词播种）
}

/** 单词跟读页 UI 状态（v10.2 堆叠卡改版） */
data class WordRepeatUiState(
    val loading: Boolean = true,
    val words: List<WordItem> = emptyList(),
    val index: Int = 0,
    val loadingWord: String? = null,        // 发音加载中（延迟 ~150ms 才出现，缓存命中不闪）
    val meaningWord: String? = null,        // 双击释义弹层正在展示的词（null = 收起）
    val meaningEntry: DictStore.Entry? = null,
    val meaningLoading: Boolean = false,
    // 跟读（ASR）
    val isListening: Boolean = false,
    val partial: String = "",
    val result: String? = null,             // null=未跟读 "match"/"mismatch"/"error"
    val heard: String = ""
) {
    val current: WordItem? get() = words.getOrNull(index)
}

/**
 * 单词跟读页 ViewModel：堆叠卡数据 + 发音（延迟 loading）+ 双击释义（DictStore 三级查询）+ ASR 跟读。
 * 索引变更实时同步 WordPracticeState 单例（需求 5：位置记忆）。
 */
class WordRepeatViewModel(
    private val app: EnglishCoachApp,
    entryWord: String
) : ViewModel() {

    companion object {
        fun factory(app: EnglishCoachApp, entryWord: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WordRepeatViewModel(app, entryWord) as T
            }
    }

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(WordRepeatUiState())
    val uiState: kotlinx.coroutines.flow.StateFlow<WordRepeatUiState> = _uiState

    private var ignoreResult = false
    private var practiceWord: String? = null // 本次跟读的目标词（防止中途切卡后比对错位）

    init {
        viewModelScope.launch {
            val words = runCatching { app.wordRepository.loadAll() }.getOrDefault(emptyList())
            val remembered = app.wordPracticeState.lastIndex
            // 入口语义判定（两种入口并存）：
            // ① 带指定单词进入（首页点词卡，参数里有目标词且能命中词表）→ "点谁开谁"，跳到该词；
            // ② 无指定单词进入（导航参数为空/目标词不在词表）→ "回来还在原地"，用 lastIndex 停在离开时的词。
            val entryIndex = words.indexOfFirst { it.word.equals(entryWord, ignoreCase = true) }
            val initial = when {
                words.isEmpty() -> 0
                entryIndex >= 0 -> entryIndex
                remembered in words.indices -> remembered
                else -> 0
            }
            _uiState.value = WordRepeatUiState(loading = false, words = words, index = initial)
            app.wordPracticeState.lastIndex = initial // 无论哪种入口都回写，后续切卡由 moveTo 实时维护
            preload(words.getOrNull(initial)?.word)
            preload(words.getOrNull(initial + 1)?.word)
        }
    }

    /** 切卡（拖拽提交后调用）：更新索引 + 单例记忆 + 预加载当前/下一词发音 */
    fun moveTo(index: Int) {
        val words = _uiState.value.words
        if (index !in words.indices) return
        _uiState.value = _uiState.value.copy(index = index, result = null, heard = "", partial = "")
        app.wordPracticeState.lastIndex = index
        preload(words[index].word)
        preload(words.getOrNull(index + 1)?.word)
    }

    private var lastPreloaded: String? = null

    /** 预合成落 TTS 磁盘缓存（同词去重，避免重复合成） */
    private fun preload(word: String?) {
        if (word == null || word == lastPreloaded) return
        lastPreloaded = word
        viewModelScope.launch {
            runCatching { app.ttsClient.synthesize(word) }
        }
    }

    /**
     * 单击播放（需求 3/4）：不跳转任何页面。
     * loading 指示延迟 150ms 出现——缓存命中秒播时完全不闪；超 150ms 未出声才显示小圈，开始播放即消失。
     */
    fun speak() {
        val word = _uiState.value.current?.word ?: return
        if (_uiState.value.loadingWord == word) return
        viewModelScope.launch {
            val loadingJob = launch {
                delay(150)
                if (isActive) _uiState.value = _uiState.value.copy(loadingWord = word)
            }
            val audio = runCatching { app.ttsClient.synthesize(word) }.getOrNull()
            loadingJob.cancel()
            _uiState.value = _uiState.value.copy(loadingWord = null)
            if (audio != null) runCatching { app.audioPlayer.play(audio) {} }
        }
    }

    /** 双击释义（需求 3）：DictStore 内置词典 → 本地缓存 → LLM 兜底，底部弹层展示不打断堆叠浏览 */
    fun openMeaning() {
        val word = _uiState.value.current?.word ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(meaningWord = word, meaningEntry = null, meaningLoading = true)
            val entry = runCatching { app.dictStore.lookup(word) }.getOrNull()
            // 弹层可能已被切词/关闭：只在仍是该词时回填
            if (_uiState.value.meaningWord == word) {
                _uiState.value = _uiState.value.copy(meaningEntry = entry, meaningLoading = false)
            }
        }
    }

    fun dismissMeaning() {
        _uiState.value = _uiState.value.copy(meaningWord = null, meaningEntry = null, meaningLoading = false)
    }

    // ---------- 跟读（ASR，v10.1 引擎：cancel 事件隔离 + 看门狗） ----------

    fun startPractice() {
        val current = _uiState.value.current ?: return
        practiceWord = current.word
        _uiState.value = _uiState.value.copy(result = null, heard = "", partial = "", isListening = true)
        app.asrEngine.startListening()
    }

    /** 上滑取消 / <300ms 防误触：cancelListening 立即复位 + 事件隔离 */
    fun cancelPractice() {
        ignoreResult = true
        app.asrEngine.cancelListening()
        _uiState.value = _uiState.value.copy(isListening = false, partial = "")
    }

    /** 正常松手：停引擎收 Final（引擎沉默由 AsrEngine 看门狗兜底） */
    fun releasePractice() {
        app.asrEngine.stopListening()
        _uiState.value = _uiState.value.copy(isListening = false)
    }

    fun onAsrEvent(event: AsrEvent) {
        when (event) {
            is AsrEvent.Partial -> {
                if (!ignoreResult) _uiState.value = _uiState.value.copy(partial = event.text)
            }
            is AsrEvent.Final -> {
                if (ignoreResult) {
                    ignoreResult = false
                    return
                }
                val target = practiceWord ?: _uiState.value.current?.word ?: return
                val match = normalize(event.text) == normalize(target)
                _uiState.value = _uiState.value.copy(
                    partial = "",
                    isListening = false,
                    heard = event.text,
                    result = if (match) "match" else "mismatch"
                )
            }
            is AsrEvent.Error -> {
                if (ignoreResult) {
                    ignoreResult = false
                    return
                }
                _uiState.value = _uiState.value.copy(isListening = false, partial = "", result = "error")
            }
        }
    }

    /** 归一化比较：小写、只保留字母和空格、压空白 */
    private fun normalize(text: String): String {
        return text.lowercase()
            .filter { it in 'a'..'z' || it == ' ' }
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
    }
}

/**
 * 单词跟读页（v10.2 堆叠卡改版）：
 * - 卡片堆叠：当前卡最前，后层露下一/下下张边缘（错位缩放层次）
 * - 水平拖拽跟手，左滑下一个右滑上一个，1/4 卡宽或快速甩动切换，否则 spring 弹回；首尾边界阻尼
 * - 单击播放 / 双击释义（底部弹层，不打断堆叠）
 * - 发音 loading 延迟 150ms 出现（缓存命中不闪）
 * - 位置记忆：索引存 WordPracticeState 单例，离开再回来停在离开时的单词
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordRepeatScreen(wordText: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as EnglishCoachApp
    val viewModel: WordRepeatViewModel = viewModel(factory = WordRepeatViewModel.factory(app, wordText))
    val state by viewModel.uiState.collectAsState()
    val dome = rememberVoiceDomeState()
    val scope = rememberCoroutineScope()
    // v11.6 P0-2：录音实时音量转发 → 圆顶声波条/高度呼吸/涟漪；P1-2 取消态触感宿主
    LaunchedEffect(Unit) {
        app.asrEngine.rmsLevel.collect { dome.rms = it }
    }
    val hapticView = androidx.compose.ui.platform.LocalView.current

    // 系统打断（来电/切后台）：复位圆顶；页面销毁补停 ASR
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                viewModel.cancelPractice()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            app.asrEngine.cancelListening()
        }
    }

    // 圆顶收起联动：结果就绪
    LaunchedEffect(state.result) {
        if (state.result != null) dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PageBg)
        ) {
            // 顶栏：返回 + 标题 + 位置指示（N / M）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 8.dp),
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
                    text = "跟读这个词",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                if (state.words.isNotEmpty()) {
                    Text(
                        text = "${state.index + 1} / ${state.words.size}",
                        fontSize = 14.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(end = 14.dp)
                    )
                }
            }

            // ---------- 卡堆区（拖拽 + 点按） ----------
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                } else if (state.current != null) {
                    val syllablePlayer = com.englishcoach.ui.common.rememberSyllablePlayer()
                    StackedWordDeck(
                        state = state,
                        viewModel = viewModel,
                        player = syllablePlayer,
                        scope = scope
                    )
                }
            }

            // 卡堆下方提示
            Text(
                text = "单击听发音 · 双击看释义 · 左右滑动切词",
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            )

            // 跟读结果行
            val resultText = when (state.result) {
                "match" -> "✓ 一样！说得真棒"
                "mismatch" -> "听到的是「${state.heard}」，再试一次"
                "error" -> "没听清，靠近点再说一次"
                else -> if (state.isListening && state.partial.isNotBlank()) state.partial else "按住下方按钮，读出这个单词"
            }
            Text(
                text = resultText,
                fontSize = 15.sp,
                color = when (state.result) {
                    "match" -> CorrectGreen
                    "mismatch", "error" -> ErrorRed
                    else -> TextSecondary
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 按住说话（圆顶手势宿主；录音视觉由 VoiceDomeOverlay 全屏接管）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (state.isListening) ErrorRed else HyperBlue)
                    .voiceDomeGesture(
                        state = dome,
                        enabled = true,
                        onPressStart = { viewModel.startPractice() },
                        onPressCancel = {
                            dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                            viewModel.cancelPractice()
                        },
                        onPressRelease = { _ ->
                            viewModel.releasePractice()
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
                    if (state.isListening) {
                        WaveBarsIndicator(color = Color.White)
                    } else {
                        Text(
                            text = "按住说话",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // 微信式圆顶录音 overlay（三页共用）
        VoiceDomeOverlay(state = dome)

        // 双击释义底部弹层（DictStore 三级查询，不打断堆叠浏览）
        if (state.meaningWord != null) {
            ModalBottomSheet(onDismissRequest = { viewModel.dismissMeaning() }) {
                MeaningSheetContent(
                    word = state.meaningWord.orEmpty(),
                    entry = state.meaningEntry,
                    loading = state.meaningLoading,
                    onSpeak = { viewModel.speak() }
                )
            }
        }
    }
}

/**
 * 堆叠卡 + 拖拽交互（需求 1/2）：
 * - 三层渲染：index+2（最底）/ 后层卡（拖拽方向决定 next 或 prev）/ 当前卡（最顶）
 * - 拖拽跟手：dragX 即时位移；后层卡以 0.35 倍速视差跟随 + 缩放趋近 1
 * - 松手：位移 > 1/4 卡宽或甩动速度 > 1500px/s → 切卡（当前卡滑出、新卡从对侧 spring 归位）；
 *   否则弹回。首尾边界阻尼（位移 ×0.25）。
 */
@Composable
private fun StackedWordDeck(
    state: WordRepeatUiState,
    viewModel: WordRepeatViewModel,
    player: com.englishcoach.ui.common.SyllablePlayer,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val cardWidthDp = (screenWidthDp - 36).dp
    val cardWidthPx = with(density) { cardWidthDp.toPx() }

    var dragX by remember { mutableFloatStateOf(0f) }                 // 拖拽即时位移（px）
    val settleAnim = remember { Animatable(0f) }                      // 松手后的补间位移

    val index = state.index
    val total = state.words.size
    val dragProgress = (abs(dragX) / cardWidthPx).coerceIn(0f, 1f)
    // 后层卡：拖左显 next，拖右显 prev，静止显 next（牌堆语义）
    val behindIndex = when {
        dragX > 0f -> index - 1
        else -> index + 1
    }
    val deepIndex = index + 2

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        contentAlignment = Alignment.Center
    ) {
        // 第 3 层：index+2（最底，几乎静止）
        state.words.getOrNull(deepIndex)?.let { deep ->
            WordCardLayer(
                item = deep,
                player = player,
                modifier = Modifier
                    .width(cardWidthDp)
                    .graphicsLayer {
                        scaleX = 0.88f
                        scaleY = 0.88f
                        translationY = 24.dp.toPx()
                        alpha = 0.45f
                    }
            )
        }
        // 第 2 层：视差跟随的后层卡（v10.4 打磨：视差系数收敛，含蓄滑入感）
        state.words.getOrNull(behindIndex)?.let { behind ->
            WordCardLayer(
                item = behind,
                player = player,
                modifier = Modifier
                    .width(cardWidthDp)
                    .graphicsLayer {
                        scaleX = 0.95f + 0.05f * dragProgress
                        scaleY = 0.95f + 0.05f * dragProgress
                        translationX = dragX * 0.22f
                        translationY = 12.dp.toPx() * (1f - dragProgress)
                        alpha = 0.55f + 0.45f * dragProgress
                    }
            )
        }
        // 当前卡（最顶）：拖拽跟手 + 松手补间 + 轻微倾斜
        WordCardLayer(
            item = state.current!!,
            player = player,
            isCurrent = true,
            showLoading = state.loadingWord == state.current?.word,
            modifier = Modifier
                .width(cardWidthDp)
                .graphicsLayer {
                    translationX = dragX + settleAnim.value
                    rotationZ = (dragX / cardWidthPx) * 2f // v10.4：倾斜角收敛 ≤2°
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // 首尾边界阻尼：到头后位移衰减为 1/4（"到头了"的手感）
                        val atStart = index == 0
                        val atEnd = index == total - 1
                        val damped = (atStart && delta > 0f) || (atEnd && delta < 0f)
                        dragX += if (damped) delta * 0.25f else delta
                    },
                    onDragStopped = { velocity ->
                        scope.launch {
                            val dir = if (dragX < 0f) 1 else -1 // 左滑 = 下一个
                            val canMove = index + dir in 0 until total
                            val byDistance = abs(dragX) > cardWidthPx / 4f
                            val byVelocity = abs(velocity) > 1500f &&
                                ((dragX < 0f && velocity < 0f) || (dragX > 0f && velocity > 0f))
                            val commit = dragX != 0f && canMove && (byDistance || byVelocity)

                            settleAnim.snapTo(dragX)
                            dragX = 0f
                            if (commit) {
                                // 当前卡滑出（dir=1 左出 / dir=-1 右出，≤180ms）
                                settleAnim.animateTo(-dir * cardWidthPx, tween(durationMillis = 180))
                                viewModel.moveTo(index + dir)
                                // 新当前卡从对侧进入起点，spring 柔和归位（不过弹）
                                settleAnim.snapTo(dir * cardWidthPx)
                                settleAnim.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 300f))
                            } else {
                                // 不足阈值：弹回原位（不过弹）
                                settleAnim.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 280f))
                            }
                        }
                    }
                )
                // 单击播放 / 双击释义（onTap 有 ~300ms 双击判定延迟，可接受）
                .pointerInput(index) {
                    detectTapGestures(
                        onTap = { viewModel.speak() },
                        onDoubleTap = { viewModel.openMeaning() }
                    )
                }
        )
    }
}

/**
 * 单层词卡（堆叠各层共用）：白卡 + 当前卡按音节分段渲染（点段发音 + 整词慢读/分段连读 pill）
 * + 音标 + 发音 loading 小圈；后层卡整词展示。
 */
@Composable
private fun WordCardLayer(
    item: WordItem,
    player: com.englishcoach.ui.common.SyllablePlayer,
    isCurrent: Boolean = false,
    showLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        shadowElevation = if (isCurrent) 6.dp else 2.dp,
        modifier = modifier.height(260.dp) // 固定高度：各层对齐（当前卡含分段+按钮组略高）
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isCurrent) {
                // 单词按音节分段渲染（重音加粗主色；点段只发该段音；下挂整词慢读/分段连读 pill；
                // 单音节/词表外自动降级——查询未返回时先整词占位不闪跳）
                com.englishcoach.ui.common.SyllableBar(
                    word = item.word,
                    player = player,
                    segmentFontSize = 30
                )
            } else {
                Text(
                    text = item.word,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
            if (item.phonetic.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = item.phonetic, fontSize = 15.sp, color = TextSecondary)
            }
            if (isCurrent && showLoading) {
                // 整词发音加载小圈（仅超 150ms 未出声时出现，需求 4 链路复用）
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = HyperBlue
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "发音加载中…", fontSize = 12.sp, color = TextSecondary)
                }
            }
        }
    }
}

/** 释义弹层内容：词 + 音标 + 释义 + 例句（DictStore.Entry；查不到给"暂无释义"） */
@Composable
private fun MeaningSheetContent(
    word: String,
    entry: DictStore.Entry?,
    loading: Boolean,
    onSpeak: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = word, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        if (!entry?.phonetic.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = entry!!.phonetic, fontSize = 14.sp, color = TextSecondary)
        }
        Spacer(modifier = Modifier.height(12.dp))
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = HyperBlue
            )
            entry?.meaning?.isNotBlank() == true -> Text(
                text = entry.meaning,
                fontSize = 16.sp,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            else -> Text(text = "暂无释义，稍后再试", fontSize = 14.sp, color = TextSecondary)
        }
        if (entry?.example?.isNotBlank() == true) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = entry.example,
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            if (entry.exampleZh.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.exampleZh,
                    fontSize = 12.sp,
                    color = TextSecondary.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        // 听发音（复用单击同一条 TTS 链路）
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(HyperBlue.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.VolumeUp,
                contentDescription = null,
                tint = HyperBlue,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "听发音", fontSize = 13.sp, color = HyperBlue)
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}
