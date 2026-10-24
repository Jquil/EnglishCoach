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
                        practiceWord = practiceWord,
                        practiceWordSetter = { practiceWord = it },
                        practiceResultSetter = { practiceResult = it },
                        practiceResult = practiceResult,
                        dome = dome,
                        onMeaning = { meaningWord = it },
                        // P2-1：切词即清旧结果，避免显示与屏显词不符的上一次识别
                        onSelect = {
                            viewModel.setWordIndex(it)
                            practiceResult = null
                        }
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
 * 首页快捷跟读卡（v11.24 经典堆叠牌堆重做，替换 v11.21~v11.22 水平轮播）：
 * - 同轴重叠堆放：当前词卡顶层（depth 0：scale 1.0 / y 0 / alpha 1.0），后两层为下一词、
 *   下下词（depth d=1,2：scale = 1−0.06d、translationY = −14dp×d 向上露边、alpha 0.7）；
 *   depth>2 段按 (d−2) 线性渐隐（提交时最底卡无跳帧入场/退场）。卡宽 = 屏宽 − 40dp，
 *   高 168dp 冻结不变；HomeWordCard 版式不动
 * - 左滑（下一词）：拖动顶层卡 x 1:1 跟手 + rotation=(x/卡宽)×12°（±12° 封顶），后层不动；
 *   松手过阈值（30% 卡宽或 |vx|≥1500px/s）→ 顶层卡 spring 飞出屏外（alpha 随行程→0），
 *   后层 spring 升一级补位（spring 320/ζ1.0，初速 vx×0.5）
 * - 右滑（上一词）＝堆叠逆放：拖动中顶层卡向 depth1 渐沉（scale 1→0.94 / y 0→−14dp）、
 *   上一词卡从 depth1 态向顶层态连续插值（满程 = 0.5 卡宽）；过阈值 spring 完成交换
 * - flip-first 架构与 v11.22 收敛不变量沿用：提交瞬间 onSelect 翻层 + 动画通道 snap 到
 *   视觉等价位，所有 settle 目标恒为"相对当前 index 的静止态"，settle 结束（含被打断）
 *   try/finally 防御归位 + 代际守卫（新提交接管时旧 finally 不误清锁）
 * - 未过阈值 / 边界（首词右滑/末词左滑 0.3 倍阻尼）：spring(380/ζ0.88) 弹回原位
 * - 侧层卡（depth≥1）不可交互不响应点击；顶层卡交互全保留：单击发音/双击释义/按住圆顶
 *   跟读/星标收藏；飞出卡在动画结束（alpha→0）后从组合中移除
 * - v11.20 手势仲裁沿用：pointerInput 位于 draggable/detectTapGestures 之前，水平位移越过
 *   touchSlop 判拖拽 → 本次手势全程禁 tap（防滑动误播）
 * - 历史教训：translationY 是牌堆静态层级摆位（恒向上露边），飞出为横向+旋转——
 *   严禁任何"卡片向下坠落"路径（用户否过的掉落感来源）
 */
@Composable
private fun HomeWordDeck(
    words: List<WordItem>,
    index: Int,
    app: EnglishCoachApp,
    practiceWord: String?,
    practiceWordSetter: (String?) -> Unit,
    practiceResultSetter: (String?) -> Unit,
    practiceResult: String?,
    dome: com.englishcoach.ui.common.VoiceDomeState,
    onMeaning: (String) -> Unit,
    onSelect: (Int) -> Unit
) {
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // v11.24 堆叠牌堆几何：卡宽 = 屏宽 − 40dp（堆叠无 peek 需求），高 168dp 冻结
    val cardWidthDp = (screenWidthDp - 40).dp
    val cardWidthPx = with(density) { cardWidthDp.toPx() }
    val screenWidthPx = with(density) { screenWidthDp.dp.toPx() }
    val peekYPx = with(density) { 14.dp.toPx() } // 每层向上露边（真机方向不对改 +peekYPx 一行翻转）
    val scope = rememberCoroutineScope()
    // v11.6 P1-2：取消态触感宿主
    val view = androidx.compose.ui.platform.LocalView.current

    // —— v11.24 经典堆叠牌堆：深度模型动画通道 ——
    // 拖动相 dragX 1:1 跟手（左滑为负=平移/旋转，右滑为正=换 depth 进度）；松手相各通道
    // spring 承接（初速继承），所有目标恒为静止态；settle 结束 try/finally 防御归位
    var dragX by remember { mutableFloatStateOf(0f) }
    val bounceX = remember { Animatable(0f) }             // 左滑弹回通道（x 口径）
    val bounceT = remember { Animatable(0f) }             // 右滑弹回通道（depth 进度口径）
    val shiftAnim = remember { Animatable(0f) }           // 左滑提交：后层 depth 整体 +1 → 0 升顶补位
    val flightAnim = remember { Animatable(0f) }          // 左滑提交：旧顶卡飞出 x（→∓屏宽）
    val swapAnim = remember { Animatable(0f) }            // 右滑交换进度（提交相 t₀ → 1）
    var swapStart by remember { mutableFloatStateOf(1f) } // 右滑提交捕获的起点（后层顺移 q 归一化）
    var committing by remember { mutableStateOf(false) }  // 提交动画中（锁拖拽/锁按住跟读）
    var commitDir by remember { mutableStateOf(0) }       // 1=左滑飞出 / -1=右滑交换 / 0=无
    var commitGen by remember { mutableStateOf(0) }       // 提交代际（并发提交接管时旧 finally 不误清锁）
    val xThreshPx = cardWidthPx * 0.30f                   // 设计师冻结：snap 阈值 = 30% 卡宽
    // v11.20 手势仲裁标志沿用：本次手势水平位移越过 touch slop 即判拖拽 → 全程禁 tap
    var dragArbitration by remember { mutableStateOf(false) }
    val total = words.size

    // 发音 loading（150ms 延迟出现，缓存命中不闪；v11.15 状态行移至卡外提示行）
    var loadingWord by remember { mutableStateOf<String?>(null) }
    val speakWord: (String) -> Unit = { w ->
        scope.launch {
            val loadingJob = launch {
                kotlinx.coroutines.delay(150)
                loadingWord = w
            }
            // v11.15 卡死修复（真机"一直显示发音加载中"）：
            // ①根因 1：原 `if (loadingWord != w)` 防重入——loadingWord 残留后同词重试被锁死；
            //   去掉拦截，每次点击开新协程（synthesize 端有在途去重兜并发）
            // ②根因 2：synthesize 网络挂起无超时——withTimeoutOrNull(8s) 强制兜底
            // ③完成/失败/超时三路必清 loadingWord；失败走卡外提示行明确报错
            val audio = withTimeoutOrNull(8000) {
                runCatching { app.ttsClient.synthesize(w) }.getOrNull()
            }
            loadingJob.cancel()
            loadingWord = null
            if (audio != null) {
                runCatching { app.audioPlayer.play(audio) {} }
            } else {
                practiceResultSetter("发音加载失败，请稍后重试")
            }
        }
    }

    // —— 收进生词本（v11.5 需求2）：与对话纠错/点词释义同一 VocabStore，来源标记 home_deck ——
    // v11.12：星标双向 toggle——未收藏点击收词，已收藏再点移出生词本（remove）
    var collected by remember { mutableStateOf(false) }
    var collecting by remember { mutableStateOf(false) }
    // v11.7：收藏成功脉冲计数（added 时递增，钮上播放一次性 1.06 缩放）
    var collectPulse by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // 切词后按当前词刷新收藏态（已收藏显示 tint 完成态，重复点击不写库）
    LaunchedEffect(index, words) {
        val w = words.getOrNull(index)?.word ?: return@LaunchedEffect
        collected = runCatching { app.vocabStore.contains(w) }.getOrDefault(false)
    }
    val toggleCollect: () -> Unit = {
        val item = words.getOrNull(index)
        if (item != null && !collecting) {
            if (collected) {
                // 已收藏 → 移出生词本（取消成功不加脉冲，保持简单；处理中复用 collecting 禁点）
                collecting = true
                scope.launch {
                    val removed = runCatching { app.vocabStore.remove(item.word) }.getOrDefault(false)
                    collecting = false
                    practiceResultSetter(if (removed) "已移出生词本" else "移出失败，请重试")
                    // v11.11 竞态口径：仅仍停留该词时更新（切词后由 LaunchedEffect 按真实库态刷新）
                    if (removed && words.getOrNull(index)?.word == item.word) {
                        collected = false
                    }
                }
            } else {
                collecting = true
                scope.launch {
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
                    collecting = false
                    if (added) collectPulse++ // 仅真实新增触发成功脉冲（切词刷新不触发）
                    // 轻量反馈：走现有结果提示行（✓ 前缀 = 绿色）
                    practiceResultSetter(if (added) "✓ 已收进生词本" else "该词已在生词本")
                    // v11.11 竞态修复：仅当收词完成后仍停留在这一个词时才更新 collected。
                    // 否则 add 的完成回调会把旧词的收藏结果写到已切到的新词上（新词误亮星标，
                    // 再次切回时 contains 刷新又熄灭 → "已收藏又回未收藏"的真机现象）
                    if (words.getOrNull(index)?.word == item.word) {
                        collected = collected || added
                    }
                }
            }
        }
    }

    // 跟读中标志 = practiceWord != null（QA P1-3：跟读期间冻结切词手势，
    // 识别词与屏显词不再错位）

    // 当前词（rememberUpdatedState：手势回调跨重组始终读到最新词，避免闭包捕获旧词）
    val currentWord by androidx.compose.runtime.rememberUpdatedState(words.getOrNull(index)?.word)

    // 当前卡手势链（仅 isCurrent 卡挂载；侧卡不可交互、不响应点击）。
    // v11.20 手势仲裁沿用：pointerInput 必须位于 draggable / detectTapGestures 之前——
    // up 事件先经本仲裁更新标志、同帧同序再被 tap 的 onTap 读到；判定口径与 draggable
    // 一致（viewConfiguration.touchSlop）：一旦判拖拽，本次手势（含松手瞬间）不再触发
    // 单击发音/双击释义
    val currentGestures: Modifier = Modifier
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                dragArbitration = false
                var totalX = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    totalX += change.positionChange().x
                    if (kotlin.math.abs(totalX) > viewConfiguration.touchSlop) {
                        dragArbitration = true
                    }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
        .draggable(
            orientation = Orientation.Horizontal,
            enabled = practiceWord == null && !committing, // P1-3 跟读互斥；提交动画中锁拖拽
            state = rememberDraggableState { delta ->
                // v11.22 错位修复：committing 守卫——draggable 的 enabled 只在手势启动时读一次，
                // 提交相启动后到重组生效前有一帧 stale 窗口，漏进的拖拽会写 dragX 且无人归零
                // （卡带永久偏移的另一来源）。此间忽略一切 delta，dragX 保持 0
                if (practiceWord != null || committing) return@rememberDraggableState
                val atStart = index == 0
                val atEnd = index == total - 1
                // v11.21 边界阻尼：首词右滑 / 末词左滑位移 ×0.3（可拖出，松手必弹回）
                val damped = (atStart && delta > 0f) || (atEnd && delta < 0f)
                var next = dragX + if (damped) delta * 0.3f else delta // 1:1 跟手
                // 软限位（设计师口径沿用）：超过 0.5 屏后按 0.85 次幂衰减（防一次手势甩出多卡位）
                val ax = kotlin.math.abs(next)
                val limPx = screenWidthPx * 0.5f
                if (ax > limPx) {
                    val sign = if (next < 0f) -1f else 1f
                    next = sign * (limPx + (ax - limPx).toDouble().pow(0.85).toFloat() * 0.5f)
                }
                dragX = next
            },
            onDragStopped = { velocity ->
                scope.launch {
                    // 右滑交换进度（0..1）：满程 = 0.5 卡宽，dragT 0.6 ≈ 30% 卡宽阈值
                    val dragT = (dragX / (cardWidthPx * 0.5f)).coerceIn(0f, 1f)
                    if (practiceWord != null) {
                        // 跟读中松手：不切词，仅归位（两通道一并归零）
                        val bx = if (dragX < 0f) dragX else 0f
                        val bt = if (dragX > 0f) dragT else 0f
                        dragX = 0f
                        bounceX.snapTo(bx)
                        bounceT.snapTo(bt)
                        try {
                            coroutineScope {
                                if (bx != 0f) {
                                    launch {
                                        bounceX.animateTo(
                                            0f,
                                            spring(dampingRatio = 0.88f, stiffness = 380f)
                                        )
                                    }
                                }
                                if (bt != 0f) {
                                    launch {
                                        bounceT.animateTo(
                                            0f,
                                            spring(dampingRatio = 0.88f, stiffness = 380f)
                                        )
                                    }
                                }
                            }
                        } finally {
                            if (!bounceX.isRunning) bounceX.snapTo(0f)
                            if (!bounceT.isRunning) bounceT.snapTo(0f)
                        }
                        return@launch
                    }
                    // 提交判定（v11.3 规范 B：方向 sign(x+vx)，快甩速度主导）
                    val byDistanceLeft = kotlin.math.abs(dragX) >= xThreshPx
                    val byVelocity = kotlin.math.abs(velocity) >= 1500f
                    val dir = if (dragX + velocity < 0f) 1 else -1 // 左滑（负）= 切下一词
                    val canMove = if (dir == 1) index < total - 1 else index > 0
                    val overThreshold = if (dir == 1) {
                        byDistanceLeft || byVelocity
                    } else {
                        dragT >= 0.6f || byVelocity // dragT 0.6 ≈ 30% 卡宽（同一阈值口径）
                    }
                    val commit = overThreshold && canMove && (dragX != 0f || velocity != 0f)
                    if (commit && dir == 1) {
                        // —— 左滑提交（flip-first，v11.22 收敛不变量沿用）——
                        // 翻层先行：动画目标恒为"相对新 index 的静止态"。飞出卡 spring 滑向
                        // 屏外（rotation/alpha 由 x 连续导出），后层 depth 整体 +1 → 0
                        // spring 升顶补位（spring 320/ζ1.0，初速 vx×0.5）。禁 tween
                        val gen = ++commitGen
                        committing = true
                        commitDir = 1
                        onSelect(index + 1)
                        flightAnim.snapTo(dragX)  // 飞出通道从拖拽位置接力，无跳帧
                        shiftAnim.snapTo(1f)      // 翻层后视觉等价位：后层 depth 整体 +1
                        dragX = 0f
                        try {
                            coroutineScope {
                                launch {
                                    flightAnim.animateTo(
                                        -screenWidthPx,
                                        spring(dampingRatio = 1f, stiffness = 320f),
                                        initialVelocity = velocity * 0.5f
                                    )
                                }
                                launch {
                                    shiftAnim.animateTo(
                                        0f,
                                        spring(dampingRatio = 1f, stiffness = 320f),
                                        initialVelocity = velocity * 0.5f / cardWidthPx
                                    )
                                }
                            }
                        } finally {
                            // v11.22 防御性归位沿用：settle 结束（含被打断）后所有通道收敛静止态；
                            // isRunning=true 说明新提交已接管（目标同为静止态），不抢写免互取消
                            if (!flightAnim.isRunning) flightAnim.snapTo(0f)
                            if (!shiftAnim.isRunning) shiftAnim.snapTo(0f)
                            // 代际守卫：新提交接管时旧 finally 不清锁（由新提交的 finally 复位）
                            if (commitGen == gen) {
                                commitDir = 0
                                committing = false
                            }
                        }
                    } else if (commit && dir == -1) {
                        // —— 右滑提交（堆叠逆放，flip-first）——
                        // 旧顶卡沉入 depth1（scale→0.94 / y→−14dp / alpha→0.7），上一词卡升顶，
                        // 更深后层顺位下移；swapAnim 从拖拽进度 t₀ spring 到 1（静止态），
                        // 后层顺移按 q=(swapT−t₀)/(1−t₀) 归一化（拖动相未动，提交相才走）
                        val gen = ++commitGen
                        committing = true
                        commitDir = -1
                        onSelect(index - 1)
                        swapStart = dragT
                        swapAnim.snapTo(dragT) // 翻层后渲染口径切到新 index，视觉等价无跳帧
                        dragX = 0f
                        try {
                            swapAnim.animateTo(
                                1f,
                                spring(dampingRatio = 1f, stiffness = 320f),
                                initialVelocity = velocity / cardWidthPx
                            )
                        } finally {
                            if (!swapAnim.isRunning) swapAnim.snapTo(1f)
                            if (commitGen == gen) {
                                commitDir = 0
                                committing = false
                            }
                        }
                    } else {
                        // 未过阈值 / 边界：spring(380/ζ0.88) 弹回原位，初速继承
                        val bx = if (dragX < 0f) dragX else 0f
                        val bt = if (dragX > 0f) dragT else 0f
                        dragX = 0f
                        bounceX.snapTo(bx)
                        bounceT.snapTo(bt)
                        try {
                            coroutineScope {
                                if (bx != 0f) {
                                    launch {
                                        bounceX.animateTo(
                                            0f,
                                            spring(dampingRatio = 0.88f, stiffness = 380f),
                                            initialVelocity = velocity
                                        )
                                    }
                                }
                                if (bt != 0f) {
                                    launch {
                                        bounceT.animateTo(
                                            0f,
                                            spring(dampingRatio = 0.88f, stiffness = 380f),
                                            initialVelocity = velocity / (cardWidthPx * 0.5f)
                                        )
                                    }
                                }
                            }
                        } finally {
                            if (!bounceX.isRunning) bounceX.snapTo(0f)
                            if (!bounceT.isRunning) bounceT.snapTo(0f)
                        }
                    }
                }
            }
        )
        .voiceDomeGesture(
            state = dome,
            enabled = true,
            holdThresholdMs = 250L, // 按住 ≥250ms 确认跟读；期间松手/横滑放手
            onPressStart = {
                // v11.21：提交动画滑行中不接受新的按住跟读（此时 snap 归零会打断 spring 滑行）
                if (committing) return@voiceDomeGesture
                // P1-4：引擎忙前置检查（忙时圆顶复位并提示，不弹空圆顶）
                if (app.asrEngine.isListening.value) {
                    practiceResultSetter("麦克风正忙，稍候一秒再按住")
                    dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                    return@voiceDomeGesture
                }
                // P2-3：打断在播的 TTS 发音，避免扬声器声音混进麦克风
                runCatching { app.audioPlayer.stop() }
                // P1-3：跟读确认瞬间清掉残留拖拽偏移，卡片归位
                scope.launch {
                    bounceX.snapTo(0f)
                    bounceT.snapTo(0f)
                    dragX = 0f
                }
                val started = app.asrEngine.startListening()
                if (!started) {
                    practiceResultSetter("启动录音失败，稍候一秒再按住")
                    dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                    return@voiceDomeGesture
                }
                practiceResultSetter(null)
                practiceWordSetter(currentWord)
            },
            onPressCancel = {
                // 极短按 / 上滑取消：等价丢弃（事件隔离，不出结果不残留）
                app.asrEngine.cancelListening()
                practiceWordSetter(null)
            },
            onPressRelease = {
                app.asrEngine.stopListening() // 圆顶已 RECOGNIZING，Final/Error 到达后收起
            },
            onEnterCancel = {
                // 微信同款：滑入取消区瞬间隔离已积累的识别结果
                app.asrEngine.cancelListening()
            },
            onExitCancel = {
                scope.launch {
                    // v11.3 B 方案：cancel 已销毁实例，start 用全新实例——无冷却需求，
                    // 100ms 仅作事件落定缓冲；D4 检查保留（缓冲期内新会话接管则跳过）
                    kotlinx.coroutines.delay(100)
                    if (app.asrEngine.isListening.value) return@launch
                    val ok = app.asrEngine.startListening()
                    if (!ok) {
                        practiceResultSetter("录音中断，请重新按住")
                        dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                    }
                }
            },
            // v11.6 P1-2：滑入取消区触感一下
            hapticFeedback = {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
        )
        .pointerInput(index) {
            detectTapGestures(
                onTap = {
                    // P2-2：跟读确认后事件已被 consume，tap 不会触发；这里只处理短按播发音
                    // v11.20：手势仲裁——本手势发生过拖拽（越过 slop）则禁 tap，
                    // 杜绝"滑动了还播放"
                    if (!dragArbitration) {
                        currentWord?.takeIf { practiceWord == null }?.let { speakWord(it) }
                    }
                },
                onDoubleTap = {
                    // v11.20：双击释义同样受仲裁保护，滑动不触发
                    if (!dragArbitration && practiceWord == null) {
                        currentWord?.let { onMeaning(it) }
                    }
                }
            )
        }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(204.dp), // 卡 168dp 固定（v11.15 冻结）+ 两层向上露边（2×14dp）余量
        contentAlignment = Alignment.Center
    ) {
        // —— v11.24 经典堆叠牌堆渲染 ——
        // 深度模型：depth 0=顶层（scale 1 / y 0 / alpha 1）、1/2=后层（scale 1−0.06d、
        // y −14dp×d 向上露边、alpha 0.7）、depth>2 按 (d−2) 线性渐隐（提交时最底卡无跳帧
        // 入场/退场）。绘制顺序按 depth 降序（最深的先画，顶层最后画盖在最上）。
        // 左滑拖动：顶层 x 1:1 + rotation（±12° 封顶）；右滑拖动：顶层沉 depth1、上一词卡升顶，
        // 两层按拖动进度连续插值；后层顺移（q）仅在右滑提交相进行
        val shiftV = shiftAnim.value
        val swapV = swapAnim.value
        val qNorm = if (swapStart < 1f) {
            ((swapV - swapStart) / (1f - swapStart)).coerceIn(0f, 1f)
        } else {
            1f
        }
        val bounceXv = bounceX.value
        val bounceTv = bounceT.value
        val dragTv = (dragX / (cardWidthPx * 0.5f)).coerceIn(0f, 1f)
        val leftCommit = committing && commitDir == 1
        val rightCommit = committing && commitDir == -1
        val windowStart = (index - 1).coerceAtLeast(0)
        val windowEnd = (index + 3).coerceAtMost(total - 1)

        class DeckSlot(val j: Int, val depth: Float, val isFlight: Boolean)
        val slots = mutableListOf<DeckSlot>()
        for (j in windowStart..windowEnd) {
            val rel = j - index
            if (leftCommit && rel == -1) {
                // 飞出卡：独立通道（x/rotation/alpha 随飞行衰减），alpha 归零即从组合移除
                val fade = (1f - kotlin.math.abs(flightAnim.value) / (screenWidthPx * 0.7f))
                    .coerceIn(0f, 1f)
                if (fade > 0.02f) slots.add(DeckSlot(j, 0f, true))
                continue
            }
            val depth: Float = when {
                leftCommit -> rel + shiftV // 后层整体 +1 → 0 升顶补位
                rightCommit -> when (rel) {
                    0 -> 1f - swapV        // 新顶卡升顶（连续承接右滑拖动插值）
                    1 -> swapV             // 原顶卡沉入 depth1
                    2 -> 1f + qNorm        // 顺位下移（拖动相未动，提交相才走）
                    3 -> 2f + qNorm
                    else -> rel.toFloat()  // rel≤−1：屏下不渲染
                }
                !committing && (dragX > 0f || bounceTv > 0f) -> when (rel) { // 右滑拖动/弹回
                    0 -> (dragTv + bounceTv).coerceIn(0f, 1f)
                    -1 -> 1f - (dragTv + bounceTv).coerceIn(0f, 1f)
                    else -> rel.toFloat()
                }
                else -> rel.toFloat()      // 静止 / 左滑拖动（仅顶层加 x/rotation，见下）
            }
            if (depth < -0.01f || depth > 3.01f) continue
            val alpha = when {
                depth <= 1f -> lerp(1f, 0.7f, depth.coerceIn(0f, 1f))
                depth <= 2f -> 0.7f
                else -> lerp(0.7f, 0f, (depth - 2f).coerceIn(0f, 1f))
            }
            if (alpha <= 0.02f) continue
            slots.add(DeckSlot(j, depth, false))
        }
        slots.sortByDescending { it.depth }
        for (slot in slots) {
            val j = slot.j
            val depth = slot.depth
            val isCurrent = j == index && !slot.isFlight
            val topDx = dragX + bounceXv // 左滑拖动/弹回：顶层卡横向位移
            HomeWordCard(
                item = words[j],
                index = j,
                total = total,
                isCurrent = isCurrent,
                collected = if (isCurrent) collected else false,
                collecting = if (isCurrent) collecting else false,
                collectPulse = if (isCurrent) collectPulse else 0,
                onCollect = if (isCurrent) toggleCollect else null,
                modifier = Modifier
                    .width(cardWidthDp)
                    .graphicsLayer {
                        scaleX = 1f - 0.06f * depth
                        scaleY = 1f - 0.06f * depth
                        // 牌堆静态层级摆位：恒向上露边（历史教训：禁"向下坠落"路径；
                        // 真机方向不对改 +peekYPx 一行翻转）
                        translationY = -peekYPx * depth
                        if (slot.isFlight) {
                            // 左滑提交：飞出卡横向滑向屏外，rotation 由 x 连续导出（±12° 封顶）
                            translationX = flightAnim.value
                            rotationZ = ((flightAnim.value / cardWidthPx) * 12f)
                                .coerceIn(-12f, 12f)
                            alpha = (1f - kotlin.math.abs(flightAnim.value) / (screenWidthPx * 0.7f))
                                .coerceIn(0f, 1f)
                        } else {
                            alpha = when {
                                depth <= 1f -> lerp(1f, 0.7f, depth.coerceIn(0f, 1f))
                                depth <= 2f -> 0.7f
                                else -> lerp(0.7f, 0f, (depth - 2f).coerceIn(0f, 1f))
                            }
                            if (!committing && (dragX < 0f || bounceXv != 0f) && j == index) {
                                // 左滑拖动/弹回：顶层卡 x 1:1 跟手 + rotation（±12° 封顶）；
                                // 静止时 topDx=0 无副作用；右滑拖动（dragX>0）不加 x
                                translationX = topDx
                                rotationZ = ((topDx / cardWidthPx) * 12f).coerceIn(-12f, 12f)
                            }
                        }
                    }
                    // 侧层卡不可交互、不响应点击：手势链只挂当前卡
                    .then(if (isCurrent) currentGestures else Modifier)
            )
        }
        // v11.6 P1-1：首页 250ms 按住确认进度环（确认空窗反馈，确认/放手即消失，
        // 与圆顶升起形成视觉接力）。手势侧只置状态位（0=确认中 / -1=结束），
        // 环的 0→360° 逐帧由这里 Animatable 用满 250ms 驱动
        val confirmArc = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(dome.confirmProgress) {
            if (dome.confirmProgress == 0f) {
                confirmArc.snapTo(0f)
                confirmArc.animateTo(
                    1f,
                    androidx.compose.animation.core.tween(
                        250, easing = androidx.compose.animation.core.LinearEasing
                    )
                )
            } else {
                confirmArc.snapTo(0f)
            }
        }
        if (dome.confirmProgress == 0f && confirmArc.value > 0f) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier.size(36.dp)
            ) {
                drawArc(
                    color = HyperBlue,
                    startAngle = -90f,
                    sweepAngle = confirmArc.value * 360f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.5.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
            }
        }
    }

    // 练习结果 / 操作提示行（跟读结果优先展示）；v11.15：TTS「发音加载中…」状态行并入此行
    // （原卡内状态行因 168dp 固定预算裁切移出——本行在卡外从未裁切），加载态优先于结果提示
    Text(
        text = when {
            loadingWord != null -> "发音加载中…"
            practiceResult != null -> practiceResult!!
            else -> "单击听发音 · 双击看释义 · 按住跟读 · 左右滑动切词"
        },
        fontSize = 12.sp,
        color = if (practiceResult?.startsWith("✓") == true) {
            com.englishcoach.ui.theme.CorrectGreen
        } else {
            TextSecondary
        },
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    )
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
            .height(168.dp) // v11.15：恢复固定高（用户不要高度跳动）——TTS 加载行已移出卡片到
            // deck 末尾卡外提示行，卡内内容（两行结构）恒约 159dp，任何状态零裁切高度恒定
            .clip(RoundedCornerShape(20.dp))
            .background(
                // v11.22 牌堆可见性：侧卡 #F1F4F9 底 + 1dp #E2E7EF 描边（屏幕边缘 14dp peek
                // 在浅色页面上可辨；无阴影——用户明确否过阴影）。当前卡保持原白底浅蓝渐变
                // 无描边，滑回当前位自动恢复
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
                    Text(
                        text = item.chinese,
                        fontSize = 11.sp,
                        color = TextSecondary.copy(alpha = 0.8f),
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
