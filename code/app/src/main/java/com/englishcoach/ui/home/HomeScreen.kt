package com.englishcoach.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englishcoach.EnglishCoachApp
import com.englishcoach.core.model.NceLessonMeta
import com.englishcoach.core.model.WordItem
import com.englishcoach.ui.common.voiceDomeGesture
import com.englishcoach.ui.theme.HyperBlue
import com.englishcoach.ui.theme.PageBg
import com.englishcoach.ui.theme.TextPrimary
import com.englishcoach.ui.theme.TextSecondary
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow

/**
 * NCE 关卡卡 UI 模型：一课 + 进度 + 三态（已通关/进行中/锁定）。
 * 关卡 key = "nce_{no}"（统一顺数第 N 课，1..276，不显示册数）。
 */
data class NceLevelCardUi(
    val meta: NceLessonMeta,
    val cleared: Boolean,        // 整课跟读完成即通关
    val unlocked: Boolean,       // 第 1 课或上一课 cleared
    val bestScore: Int,          // 历史最高分（只升不降；0 = 从未完成）
    val prevTitle: String? = null // 上一课名称（锁定 toast 提示用）
) {
    /** bestScore 映射星级（与 NceScorer 同口径：90+/75+/60+） */
    val stars: Int
        get() = when {
            bestScore >= 90 -> 3
            bestScore >= 75 -> 2
            bestScore >= 60 -> 1
            else -> 0
        }
}

/** 口语页 UI 状态 */
data class HomeUiState(
    val words: List<WordItem> = emptyList(),
    val wordIndex: Int = 0,           // 当前词下标（deck 语义：滑动顺序切词）
    val word: WordItem? = null,       // 当前展示的单词（= words[wordIndex]，冗余便于渲染）
    val levels: List<NceLevelCardUi> = emptyList(),
    val currentLevelIndex: Int = 0    // 进行中关卡（Pager 初始定位）
)

/**
 * 口语页 ViewModel：NCE 词池词表 + 卡片内滑动切词 + NCE 276 关三态计算。
 * v10.4：词表切到《新概念英语》课文高频词（tools/gen_nce_words.py 生成的新 words.json）。
 */
class HomeViewModel(private val app: EnglishCoachApp) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** 进首页时刷新：加载词表 + NCE 目录 + 计算关卡状态（解锁 = 第 1 课或上一课 cleared） */
    fun refresh() {
        viewModelScope.launch {
            val words = app.wordRepository.loadAll()
            val metas = app.nceCatalog.loadAll()
            val levelStates = app.levelProgressStore.getAll()

            val cards = metas.map { meta ->
                val key = "nce_${meta.no}"
                val state = levelStates[key]
                val previousKey = "nce_${meta.no - 1}"
                val unlocked = meta.no == 1 || (levelStates[previousKey]?.cleared == true)
                NceLevelCardUi(
                    meta = meta,
                    cleared = state?.cleared == true,
                    unlocked = unlocked,
                    bestScore = state?.bestScore ?: 0,
                    prevTitle = metas.getOrNull(meta.no - 2)?.title // 下标 no-2 = 上一课
                )
            }
            // 当前关 = 第一个"已解锁未通关"的关卡；全部通关则定位到最后一关
            val current = cards.indexOfFirst { it.unlocked && !it.cleared }
                .let { if (it < 0) (cards.size - 1).coerceAtLeast(0) else it }

            // v11.11：起始词恢复上次进度（冷启动回到上次的词继续按顺序学习；
            // 序号越界/文件缺失兜底 0，与 store 损坏防护同口径）
            val wordIndex = if (words.isEmpty()) 0 else {
                runCatching { app.wordDeckProgressStore.getIndex() }
                    .getOrDefault(0)
                    .coerceIn(0, words.size - 1)
            }
            _uiState.update {
                it.copy(
                    words = words,
                    wordIndex = wordIndex,
                    word = words.getOrNull(wordIndex),
                    levels = cards,
                    currentLevelIndex = current
                )
            }
        }
    }

    /** "换一个"：顺序切到下一个词（deck 语义，首页卡内滑动同源） */
    fun nextWord() {
        var saved = -1
        _uiState.update { state ->
            if (state.words.isEmpty()) {
                state
            } else {
                val i = (state.wordIndex + 1).mod(state.words.size)
                saved = i
                state.copy(wordIndex = i, word = state.words[i])
            }
        }
        if (saved >= 0) persistWordIndex(saved)
    }

    /** 滑动切词提交：设当前词下标 */
    fun setWordIndex(index: Int) {
        var saved = -1
        _uiState.update { state ->
            if (state.words.isEmpty()) {
                state
            } else {
                val c = index.coerceIn(0, state.words.size - 1)
                saved = c
                state.copy(wordIndex = c, word = state.words[c])
            }
        }
        if (saved >= 0) persistWordIndex(saved)
    }

    /** v11.11：切词即写盘（后台 IO，失败只记日志不影响 UI） */
    private fun persistWordIndex(index: Int) {
        viewModelScope.launch {
            runCatching { app.wordDeckProgressStore.setIndex(index) }
        }
    }

    /**
     * 预加载：后台预合成指定词的 TTS 音频（落磁盘缓存）。
     * 范围 = 当前词 + 词表中当前词的后继（下一个词）——用户读题快、点得快时下一个词已就绪。
     * 在 viewModelScope 里跑（不随 UI LaunchedEffect 取消）；与点击共享 SiliconFlowTtsClient 的在途去重，
     * 同词并发只发一次网络请求。
     */
    fun preloadTts(words: List<String>) {
        viewModelScope.launch {
            words.filter { it.isNotBlank() }.forEach { w ->
                launch { runCatching { app.ttsClient.synthesize(w) } }
            }
        }
    }

    companion object {
        fun factory(app: EnglishCoachApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(app) as T
            }
    }
}

/**
 * 口语页（v10.4）：大标题 → 快捷跟读卡（卡内滑动切词 + 单击发音/双击释义/按住圆顶跟读）→ NCE 关卡卡堆 → 位置指示。
 * v10.4：滑动切词直接在首页卡片上做，不再跳页；跟读练习按住卡片即开始（VoiceDome 首页弹出）。
 */
@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
@Composable
fun HomeScreen(
    onOpenLesson: (Int) -> Unit
) {
    val app = LocalContext.current.applicationContext as EnglishCoachApp
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(app))
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Pager 状态：页数随关卡数变化；初始定位到当前关
    val pagerState = rememberPagerState(pageCount = { state.levels.size })
    LaunchedEffect(state.currentLevelIndex, state.levels.size) {
        if (state.levels.isNotEmpty()) {
            val target = state.currentLevelIndex.coerceIn(0, state.levels.size - 1)
            if (pagerState.currentPage != target && !pagerState.isScrollInProgress) {
                pagerState.scrollToPage(target)
            }
        }
    }

    // 圆顶跟读（首页内嵌）：按住快捷跟读卡开始，复用全局 AsrEngine
    val dome = com.englishcoach.ui.common.rememberVoiceDomeState()
    // v11.6 P0-2：录音实时音量转发 → 圆顶声波条/高度呼吸/涟漪
    LaunchedEffect(Unit) {
        app.asrEngine.rmsLevel.collect { dome.rms = it }
    }
    var practiceWord by remember { mutableStateOf<String?>(null) }
    var practiceResult by remember { mutableStateOf<String?>(null) }

    // ASR 结果收集：Final 与练习词比对（归一化），Error 给提示；都收起圆顶
    LaunchedEffect(Unit) {
        app.asrEngine.events.collect { event ->
            when (event) {
                is com.englishcoach.speech.AsrEvent.Final -> {
                    val target = practiceWord
                    if (target != null) {
                        val match = normalizeForPractice(event.text) == normalizeForPractice(target)
                        practiceResult = if (match) {
                            "✓ 一样！「$target」说得真棒"
                        } else {
                            "听到的是「${event.text}」，按住卡片再试一次"
                        }
                    }
                    dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                    practiceWord = null
                }

                is com.englishcoach.speech.AsrEvent.Error -> {
                    dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                    // 显示引擎下发的具体原因（权限/网络/引擎忙等），不再一律"没听清"
                    practiceResult = event.message
                    practiceWord = null
                }

                else -> {}
            }
        }
    }

    // 系统打断（来电/切后台）：停 ASR + 收圆顶
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                app.asrEngine.cancelListening()
                practiceWord = null // 打断后清跟读目标，解冻切词手势（QA 差异表 P2-1 关联项）
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            app.asrEngine.cancelListening()
        }
    }

    // 双击释义弹层状态（DictStore 三级查询）
    var meaningWord by remember { mutableStateOf<String?>(null) }
    var meaningEntry by remember { mutableStateOf<com.englishcoach.data.DictStore.Entry?>(null) }
    var meaningLoading by remember { mutableStateOf(false) }
    LaunchedEffect(meaningWord) {
        val word = meaningWord ?: return@LaunchedEffect
        meaningLoading = true
        meaningEntry = null
        meaningEntry = runCatching { app.dictStore.lookup(word) }.getOrNull()
        meaningLoading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 大标题
            item {
                Text(
                    text = "口语",
                    style = MaterialTheme.typography.displayLarge,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp)
                )
            }
            // 快捷跟读卡（v10.4：卡内滑动切词，不跳页）
            item {
                val currentWord = state.word
                // 预加载：当前词 + 下一个词（viewModelScope 后台跑，与点击共享在途去重）
                LaunchedEffect(state.wordIndex, state.words.size) {
                    viewModel.preloadTts(
                        buildList {
                            currentWord?.let { add(it.word) }
                            state.words.getOrNull(state.wordIndex + 1)?.let { add(it.word) }
                        }
                    )
                }
                if (state.words.isNotEmpty()) {
                    HomeWordDeck(
                        words = state.words,
                        index = state.wordIndex,
                        app = app,
                        onMeaning = { meaningWord = it },
                        // 切词即清旧结果
                        onSelect = { viewModel.setWordIndex(it) }
                    )
                }
            }
            // NCE 关卡卡堆（水平轮播，聚焦卡居中）
            item {
                HorizontalPager(
                    state = pagerState,
                    // 等宽方案（PM v2 需求1·A）：contentPadding 16dp → 当前卡 = 屏宽 − 32dp，
                    // 与上方单词快捷卡左右边距完全对齐，不露边；相邻卡滑出/滑入屏幕
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    pageSpacing = 12.dp,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    val level = state.levels.getOrNull(page) ?: return@HorizontalPager
                    // 聚焦卡 scale 1 / 侧卡 0.95；透明度 1 / 0.55（v5.5 几何）
                    val distance = kotlin.math.abs(pagerState.currentPage - page) +
                        kotlin.math.abs(pagerState.currentPageOffsetFraction)
                    val t = distance.coerceIn(0f, 1f)
                    NceLevelCard(
                        level = level,
                        modifier = Modifier.graphicsLayer {
                            val scale = lerp(1f, 0.95f, t)
                            scaleX = scale
                            scaleY = scale
                            alpha = lerp(1f, 0.55f, t)
                        },
                        onClick = {
                            if (level.unlocked) {
                                onOpenLesson(level.meta.no)
                            } else {
                                // 锁定提示指明具体前置课（如"先完成「Excuse Me」解锁"）
                                val message = level.prevTitle?.let { "先完成「$it」解锁" } ?: "完成上一课解锁"
                                android.widget.Toast.makeText(
                                    context, message, android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
            // 位置指示（276 关不适合圆点，改为"当前 / 总数"计数）
            item {
                Text(
                    text = "${(pagerState.currentPage + 1).coerceAtMost(state.levels.size)} / ${state.levels.size}",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            // 口径注脚（v8 规范文案）
            item {
                Text(
                    text = "通关 = 整课句子全部跟读完成 · 分数只做评价，不做门槛",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // 微信式圆顶跟读浮层（按住快捷跟读卡弹出）
        com.englishcoach.ui.common.VoiceDomeOverlay(
            state = dome,
            // v11.3：识别中点按任意区域 = 等价取消——用户永远有出口
            onRecognizingTap = {
                app.asrEngine.cancelListening()
                dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                practiceWord = null
                practiceResult = "已取消本次识别"
            },
            // v11.3：识别中超 8s 强制收回 + 提示（不管引擎是否还活着）
            onRecognizingTimeout = {
                app.asrEngine.cancelListening()
                dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                practiceWord = null
                practiceResult = "识别超时，请再试一次"
            }
        )

        // 双击释义弹层
        if (meaningWord != null) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { meaningWord = null }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = meaningWord.orEmpty(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    when {
                        meaningLoading -> androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = HyperBlue
                        )

                        meaningEntry?.meaning?.isNotBlank() == true -> Text(
                            text = meaningEntry!!.meaning,
                            fontSize = 16.sp,
                            color = TextPrimary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        else -> Text(text = "暂无释义，稍后再试", fontSize = 14.sp, color = TextSecondary)
                    }
                    if (meaningEntry?.example?.isNotBlank() == true) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = meaningEntry!!.example,
                            fontSize = 14.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }
    }
}

/**
 * 首页快捷跟读卡片（堆叠卡片效果）
 *
 * 视觉效果：
 * - 当前卡在最前（完整显示）
 * - 后续卡片向右堆叠（每层向右+向下偏移，逐渐缩小，形成扇形）
 * - 最多显示4张卡片
 *
 * 交互：
 * - 左滑：切换到下一个单词（当前卡飞出，后面的卡片前移）
 * - 右滑：切换到上一个单词（上一张卡从左侧滑入）
 * - 单击：播放发音
 * - 双击：查看释义
 */
@Composable
private fun HomeWordDeck(
    words: List<WordItem>,
    index: Int,
    app: EnglishCoachApp,
    onMeaning: (String) -> Unit,
    onSelect: (Int) -> Unit
) {
    // ========== 布局参数 ==========
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // 与关卡卡片对齐：contentPadding 16dp，所以卡宽 = 屏宽 - 32dp
    val cardWidthDp = (screenWidthDp - 32).dp
    val cardWidthPx = with(density) { cardWidthDp.toPx() }
    val screenWidthPx = with(density) { screenWidthDp.dp.toPx() }

    // 堆叠效果参数
    val stepX = with(density) { 16.dp.toPx() }    // 每层右移16dp
    val stepY = with(density) { 6.dp.toPx() }     // 每层下移6dp
    val scaleStep = 0.035f                         // 每层缩小3.5%
    val rotationStep = -1.5f                       // 每层逆时针1.5度

    val scope = rememberCoroutineScope()
    val total = words.size

    // ========== 动画状态 ==========
    var dragX by remember { mutableFloatStateOf(0f) }
    val flyOutAnim = remember { Animatable(0f) }
    val moveUpAnim = remember { Animatable(0f) }
    val slideInAnim = remember { Animatable(0f) }
    var animating by remember { mutableStateOf(false) }

    // ========== 手势状态 ==========
    var gestureStartTime by remember { mutableStateOf(0L) }
    var hasMoved by remember { mutableStateOf(false) }
    val minDragDistance = with(density) { 10.dp.toPx() } // 最小拖拽距离

    // ========== 发音播放 ==========
    var loadingWord by remember { mutableStateOf<String?>(null) }
    fun speakWord(word: String) {
        scope.launch {
            val loadingJob = launch {
                kotlinx.coroutines.delay(300)
                loadingWord = word
            }
            val audio = withTimeoutOrNull(8000) {
                runCatching { app.ttsClient.synthesize(word) }.getOrNull()
            }
            loadingJob.cancel()
            loadingWord = null
            if (audio != null) {
                runCatching { app.audioPlayer.play(audio) {} }
            }
        }
    }

    // ========== 生词收藏 ==========
    var collected by remember { mutableStateOf(false) }
    var collecting by remember { mutableStateOf(false) }
    var collectPulse by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(index, words) {
        val w = words.getOrNull(index)?.word ?: return@LaunchedEffect
        collected = runCatching { app.vocabStore.contains(w) }.getOrDefault(false)
    }

    fun toggleCollect() {
        val item = words.getOrNull(index) ?: return
        if (collecting) return

        scope.launch {
            collecting = true
            if (collected) {
                val removed = runCatching { app.vocabStore.remove(item.word) }.getOrDefault(false)
                if (removed && words.getOrNull(index)?.word == item.word) {
                    collected = false
                }
            } else {
                val added = runCatching {
                    app.vocabStore.add(
                        com.englishcoach.core.model.NotableWord(
                            word = item.word,
                            phonetic = item.phonetic,
                            meaning = item.meaning,
                            example = item.chinese
                        ),
                        "home_deck"
                    )
                }.getOrDefault(false)
                if (added) collectPulse++
                if (words.getOrNull(index)?.word == item.word) {
                    collected = collected || added
                }
            }
            collecting = false
        }
    }

    // ========== 手势处理 ==========
    val cardGestures: Modifier = Modifier
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                gestureStartTime = System.currentTimeMillis()
                hasMoved = false
                var totalDrag = 0f

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    val delta = change.positionChange().x
                    totalDrag += kotlin.math.abs(delta)

                    // 累计移动超过阈值，标记为拖拽
                    if (totalDrag > minDragDistance) {
                        hasMoved = true
                    }

                    if (event.changes.none { it.pressed }) break
                }
            }
        }
        .draggable(
            orientation = Orientation.Horizontal,
            enabled = !animating,
            state = rememberDraggableState { delta ->
                if (animating) return@rememberDraggableState
                val atStart = index == 0
                val atEnd = index == total - 1
                // 边界阻尼
                val damped = (atStart && delta > 0f) || (atEnd && delta < 0f)
                dragX += if (damped) delta * 0.3f else delta
            },
            onDragStopped = { velocity ->
                scope.launch {
                    // 判断是否切换
                    val velocityThreshold = 600f
                    val distanceThreshold = cardWidthPx * 0.18f
                    val swipeLeft = dragX < 0f
                    val targetIndex = if (swipeLeft) index + 1 else index - 1
                    val canMove = targetIndex in 0 until total

                    val byVelocity = kotlin.math.abs(velocity) >= velocityThreshold &&
                        ((velocity < 0f && swipeLeft) || (velocity > 0f && !swipeLeft))
                    val byDistance = kotlin.math.abs(dragX) >= distanceThreshold
                    val shouldSwitch = canMove && (byVelocity || byDistance)

                    if (shouldSwitch) {
                        animating = true
                        if (swipeLeft) {
                            // 左滑：下一个单词
                            flyOutAnim.snapTo(dragX)
                            moveUpAnim.snapTo(0f)
                            dragX = 0f
                            // 先完成动画，再更新 index
                            coroutineScope {
                                launch {
                                    flyOutAnim.animateTo(
                                        -screenWidthPx * 1.2f,
                                        spring(dampingRatio = 0.85f, stiffness = 500f),
                                        initialVelocity = velocity * 0.6f
                                    )
                                }
                                launch {
                                    moveUpAnim.animateTo(
                                        1f,
                                        spring(dampingRatio = 0.85f, stiffness = 500f)
                                    )
                                }
                            }
                            // 动画完成后立即更新状态
                            onSelect(index + 1)
                            // 等待一帧让 recomposition 完成
                            kotlinx.coroutines.delay(16)
                            flyOutAnim.snapTo(0f)
                            moveUpAnim.snapTo(0f)
                            animating = false
                        } else {
                            // 右滑：上一个单词
                            slideInAnim.snapTo(0f)
                            dragX = 0f
                            slideInAnim.animateTo(
                                1f,
                                spring(dampingRatio = 0.85f, stiffness = 500f)
                            )
                            onSelect(index - 1)
                            kotlinx.coroutines.delay(16)
                            slideInAnim.snapTo(0f)
                            animating = false
                        }
                    } else {
                        // 弹回
                        val bounceAnim = Animatable(dragX)
                        dragX = 0f
                        bounceAnim.animateTo(
                            0f,
                            spring(dampingRatio = 0.8f, stiffness = 400f),
                            initialVelocity = velocity
                        )
                    }
                }
            }
        )
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = {
                    // 只有未发生拖拽且不在动画中才响应点击
                    if (!hasMoved && !animating) {
                        val clickDuration = System.currentTimeMillis() - gestureStartTime
                        // 快速点击才算，避免长按后触发
                        if (clickDuration < 300) {
                            words.getOrNull(index)?.word?.let { speakWord(it) }
                        }
                    }
                },
                onDoubleTap = {
                    if (!hasMoved && !animating) {
                        words.getOrNull(index)?.word?.let { onMeaning(it) }
                    }
                }
            )
        }

    // ========== 卡片渲染 ==========
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.CenterStart
        ) {
        // 计算需要渲染的卡片
        val isSwipingLeft = animating && flyOutAnim.value != 0f
        val isSwipingRight = animating && slideInAnim.value != 0f
        val isDraggingRight = !animating && dragX > 0f

        // 定义卡片槽位
        data class CardSlot(
            val wordIndex: Int,
            val x: Float,
            val y: Float,
            val scale: Float,
            val rotation: Float,
            val alpha: Float,
            val depth: Float
        )

        val slots = mutableListOf<CardSlot>()

        when {
            isSwipingLeft -> {
                // 左滑动画
                val oldIndex = index - 1
                if (oldIndex >= 0) {
                    val fade = (1f - kotlin.math.abs(flyOutAnim.value) / (screenWidthPx * 0.7f)).coerceIn(0f, 1f)
                    if (fade > 0.02f) {
                        slots.add(CardSlot(oldIndex, flyOutAnim.value, 0f, 1f, 0f, fade, -1f))
                    }
                }
                // 后续卡片前移
                for (i in 0..3) {
                    val wordIdx = index + i
                    if (wordIdx >= total) break
                    val depth = i.toFloat() + (1f - moveUpAnim.value)
                    slots.add(
                        CardSlot(
                            wordIndex = wordIdx,
                            x = depth * stepX,
                            y = depth * stepY,
                            scale = 1f - depth * scaleStep,
                            rotation = depth * rotationStep,
                            alpha = 1f,
                            depth = depth
                        )
                    )
                }
            }
            isSwipingRight -> {
                // 右滑动画
                val newIndex = index
                val progress = slideInAnim.value
                slots.add(
                    CardSlot(
                        wordIndex = newIndex,
                        x = (progress - 1f) * cardWidthPx,
                        y = 0f,
                        scale = 1f,
                        rotation = 0f,
                        alpha = 1f,
                        depth = 0f
                    )
                )
                // 其他卡后移
                for (i in 1..3) {
                    val wordIdx = index + i
                    if (wordIdx >= total) break
                    val depth = (i - 1).toFloat() + progress
                    slots.add(
                        CardSlot(
                            wordIndex = wordIdx,
                            x = depth * stepX,
                            y = depth * stepY,
                            scale = 1f - depth * scaleStep,
                            rotation = depth * rotationStep,
                            alpha = 1f,
                            depth = depth
                        )
                    )
                }
            }
            isDraggingRight -> {
                // 右拖拽
                val prevIndex = index - 1
                if (prevIndex >= 0) {
                    val progress = (dragX / cardWidthPx).coerceIn(0f, 1f)
                    slots.add(
                        CardSlot(
                            wordIndex = prevIndex,
                            x = (progress - 1f) * cardWidthPx,
                            y = 0f,
                            scale = 1f,
                            rotation = 0f,
                            alpha = progress,
                            depth = -0.5f
                        )
                    )
                }
                // 当前卡及后续
                for (i in 0..3) {
                    val wordIdx = index + i
                    if (wordIdx >= total) break
                    val depth = i.toFloat()
                    slots.add(
                        CardSlot(
                            wordIndex = wordIdx,
                            x = if (i == 0) dragX else depth * stepX,
                            y = depth * stepY,
                            scale = 1f - depth * scaleStep,
                            rotation = if (i == 0) 0f else depth * rotationStep,
                            alpha = 1f,
                            depth = depth
                        )
                    )
                }
            }
            else -> {
                // 静止或左拖拽
                for (i in 0..3) {
                    val wordIdx = index + i
                    if (wordIdx >= total) break
                    val depth = i.toFloat()
                    slots.add(
                        CardSlot(
                            wordIndex = wordIdx,
                            x = if (i == 0) dragX else depth * stepX,
                            y = depth * stepY,
                            scale = 1f - depth * scaleStep,
                            rotation = if (i == 0 && dragX < 0f) 0f else depth * rotationStep,
                            alpha = 1f,
                            depth = depth
                        )
                    )
                }
            }
        }

        slots.sortByDescending { it.depth }

        // 渲染卡片
        for (slot in slots) {
            val isCurrent = slot.wordIndex == index && slot.depth >= 0f
            HomeWordCard(
                item = words[slot.wordIndex],
                index = slot.wordIndex,
                total = total,
                isCurrent = isCurrent,
                collected = if (isCurrent) collected else false,
                collecting = if (isCurrent) collecting else false,
                collectPulse = if (isCurrent) collectPulse else 0,
                onCollect = if (isCurrent) ::toggleCollect else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = slot.scale
                        scaleY = slot.scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        rotationZ = slot.rotation
                        translationX = slot.x
                        translationY = slot.y
                        alpha = slot.alpha
                    }
                    .then(if (isCurrent) cardGestures else Modifier)
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 提示文字
    Text(
        text = if (loadingWord != null) "发音加载中…" else "单击听发音 · 双击看释义 · 左右滑动切词",
        fontSize = 12.sp,
        color = TextSecondary,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
    }
}

/**
 * 首页快捷跟读单卡：极浅蓝渐变 + 固定 168dp 高（任何单词零跳动）+ 单词大字（maxLines 截断）。
 * 当前卡含 🔊 发音钮；后层预告卡同版式简化。
 */
@Composable
private fun HomeWordCard(
    item: WordItem,
    index: Int,
    total: Int,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    // v11.15 需求2 / v11.7 规范化：收进生词本（仅当前卡显示；null = 不显示入口）
    collected: Boolean = false,
    collecting: Boolean = false,
    collectPulse: Int = 0,
    onCollect: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .height(168.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isCurrent) Brush.verticalGradient(listOf(Color(0xFFEAF2FF), Color(0xFFF8FAFF)))
                else Brush.verticalGradient(listOf(Color(0xFFF1F4F9), Color(0xFFF1F4F9)))
            )
            .then(
                if (!isCurrent) {
                    Modifier.border(1.dp, Color(0xFFE2E7EF), RoundedCornerShape(20.dp))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
                    Modifier.border(1.dp, Color(0xFFE2E7EF), RoundedCornerShape(20.dp))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column {
            // v11.11 1a：标签行提升为跨全宽首行——右组（词位+星标）右缘 = 内容右缘 = 播放钮右缘，
            // 三者同一基线。原布局标签行嵌在 Column(weight 1f) 内，右缘比播放钮右缘短
            // 12+54=66dp，即真机反馈"没跟播放钮右对齐"的根因
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(HyperBlue.copy(alpha = 0.10f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(text = "快捷跟读", fontSize = 11.sp, color = HyperBlue)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // 右组整体：词位指示 + 星标（有星标时二者成组贴右缘，无星标时文字单独贴右）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // v11.16 追加：序号改回 1 起（用户推翻 0 起要求）——"第 1 / N 词" =
                            // 第一个词；仅显示层 +1，WordDeckProgressStore 内部仍存 0-based index。
                            // 当前卡/预告卡/右滑入场卡共用本组件，一处改即全统一
                            text = "第 ${index + 1} / $total 词",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        // v11.10 改动一：星标加大（视觉 36dp 圆 / 图标 22dp / 环 16dp，
                        // 命中外扩至 40dp；Crossfade 150ms、成功脉冲 0.6→1.15→1 不变，仅当前卡）
                        if (isCurrent && onCollect != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            val pulse = remember { Animatable(1f) }
                            LaunchedEffect(collectPulse) {
                                if (collectPulse > 0) {
                                    pulse.snapTo(0.6f)
                                    pulse.animateTo(1.15f, spring(stiffness = 380f, dampingRatio = 0.55f))
                                    pulse.animateTo(1f, spring(stiffness = 380f, dampingRatio = 0.55f))
                                }
                            }
                            val collectState = when {
                                collecting -> 1
                                collected -> 2
                                else -> 0
                            }
                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        scaleX = pulse.value
                                        scaleY = pulse.value
                                    }
                                    // 视觉 36dp；命中区仅向左/上/下外扩 2dp（右侧不占位，
                                    // 视觉圆右缘贴齐播放钮右缘基线），触控 38×40dp
                                    .padding(start = 2.dp, top = 2.dp, bottom = 2.dp)
                                    .size(36.dp)
                                    // v11.12：已收藏可点击取消；仅收集中禁点
                                    .clickable(
                                        enabled = !collecting,
                                        onClick = onCollect
                                    )
                            ) {
                                Crossfade(
                                    targetState = collectState,
                                    animationSpec = tween(150),
                                    label = "collectStar"
                                ) { s ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                            .then(
                                                if (s == 2) {
                                                    // 已收藏：琥珀黄 12% 底
                                                    Modifier.background(Color(0xFFFFB300).copy(alpha = 0.12f))
                                                } else {
                                                    // 未收藏/收集中：透明底
                                                    Modifier
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        when (s) {
                                            1 -> {
                                                // 收集中：灰星标 + 16dp 进度环叠加，禁点
                                                Icon(
                                                    imageVector = Icons.Filled.StarBorder,
                                                    contentDescription = null,
                                                    tint = Color(0xFF9AA3B2),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 1.5.dp,
                                                    color = Color(0xFF9AA3B2)
                                                )
                                            }
                                            2 -> Icon(
                                                imageVector = Icons.Filled.Star,
                                                contentDescription = "已收藏，点按移出生词本",
                                                tint = Color(0xFFFFB300),
                                                modifier = Modifier.size(22.dp)
                                            )
                                            else -> Icon(
                                                imageVector = Icons.Filled.StarBorder,
                                                contentDescription = "收进生词本",
                                                tint = Color(0xFF9AA3B2),
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 第二行：单词信息 + 发音播放钮（播放钮右缘与上方星标组右缘同一基线：内容右缘）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    // 单词大字（超长截断）
                    Text(
                        text = item.word,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 音标 / 词性释义（超长截断）
                    Text(
                        text = "${item.phonetic} · ${item.meaning}",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // v11.27：英语例句（主要内容）
                    if (item.example.isNotBlank()) {
                        Text(
                            text = item.example,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    // 中文例句（辅助内容，弱化显示）
                    Text(
                        text = item.chinese,
                        fontSize = 11.sp,
                        color = TextSecondary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // v11.15：TTS「发音加载中…」状态行已移出卡片（deck 末尾卡外提示行）——
                    // 原状态行使内容超出 168dp 固定预算被裁切（真机截图）；卡内不再有时变高度内容。
                    // v10.7：「换一个」pill 已删（切词用卡片左右滑动）
                }
                if (isCurrent) {
                    Spacer(modifier = Modifier.width(12.dp))
                    // 🔊 54dp 蓝色实心圆钮（TTS 播报，音源跟随设置页路由）
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(HyperBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VolumeUp,
                            contentDescription = "发音",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 首页跟读比对归一化：小写、只保留字母和空格、压空白（与 WordRepeatScreen 同口径） */
private fun normalizeForPractice(text: String): String {
    return text.lowercase()
        .filter { it in 'a'..'z' || it == ' ' }
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .trim()
}

/** NCE 关卡专属渐变：四色循环（不显示册数，颜色只作节奏区分）；锁定灰 */
private fun levelGradient(lessonNo: Int, locked: Boolean): List<Color> {
    if (locked) {
        return listOf(Color(0xFFB9BEC7), Color(0xFF8E939C)) // 灰渐变（saturate 0）
    }
    return when ((lessonNo - 1).mod(4)) {
        0 -> listOf(Color(0xFFFFA94D), Color(0xFFE85D04)) // 橙
        1 -> listOf(Color(0xFF45D6A0), Color(0xFF0F9D77)) // 绿
        2 -> listOf(Color(0xFF9B8CFF), Color(0xFF5B4BD5)) // 紫
        else -> listOf(Color(0xFF5CA9FF), Color(0xFF1F6FE0)) // 蓝
    }
}

/**
 * Wallet 风格 NCE 关卡卡：比例 1.586:1、圆角 14dp、主题渐变 + 白字 + 光泽层。
 * 三态：✅已通关（白勾徽章 + 星级/最高分）/ ▶进行中（白胶囊"开始学习"）/ 🔒锁定（灰卡 + 锁）。
 */
@Composable
private fun NceLevelCard(
    level: NceLevelCardUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val meta = level.meta
    val locked = !level.unlocked
    val context = LocalContext.current
    val app = context.applicationContext as EnglishCoachApp

    // v3 需求 3：关卡插画 assets/nce/art/nce_{no}.svg（缺文件自动回退现有版式，绝不空窗）
    var hasArt by remember(meta.no) { mutableStateOf(false) }
    LaunchedEffect(meta.no) {
        hasArt = runCatching {
            context.assets.list("nce/art")?.contains("nce_${meta.no}.svg") == true
        }.getOrDefault(false)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.586f) // 真卡几何（v5.5）
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(levelGradient(meta.no, locked)))
            .clickable(enabled = true, onClick = onClick) // 锁定卡也可点（给 toast 提示）
    ) {
        // 插画装饰层（置于 sheen 之下；透明度 ≤35%；锁定关饱和度 0）
        if (hasArt) {
            coil.compose.AsyncImage(
                model = "file:///android_asset/nce/art/nce_${meta.no}.svg",
                contentDescription = null,
                imageLoader = app.nceArtImageLoader,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = 40.dp, bottom = 30.dp)
                    .graphicsLayer {
                        alpha = 0.30f
                    },
                colorFilter = if (locked) {
                    androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }
                    )
                } else null
            )
        }
        // 光泽层（左上 → 右下淡白）
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.16f), Color.Transparent, Color.Transparent)
                    )
                )
        )
        Column(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // 第一行：LESSON 001 + 右上角状态（白勾徽章 / 锁）
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LEVEL ${meta.no}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.75f),
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                when {
                    level.cleared -> {
                        // 白勾徽章
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "已通关",
                                tint = Color(0xFF0F9D77),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    locked -> {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "未解锁",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    else -> {}
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 课名 + NCE · Lesson N 副行（v8：不显示册数）
            Text(
                text = meta.title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "NCE · Lesson ${meta.no}",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.padding(top = 1.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            when {
                // ✅已通关：星级（卡面金星 #FFE08A）+ 最高分 + 重练
                level.cleared -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "★".repeat(level.stars),
                            fontSize = 12.5.sp,
                            letterSpacing = 1.5.sp,
                            color = Color(0xFFFFE08A)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (level.bestScore > 0) "${level.bestScore} 分 · 点按重练" else "点按重练",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // ▶进行中（已解锁未通关）：v8 CTA「继续跟读」
                !locked -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    // 白底胶囊"继续跟读"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "▶ 继续跟读",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFC05E02)
                        )
                    }
                }

                // 🔒锁定
                else -> {
                    Text(
                        text = "完成上一课解锁",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}
