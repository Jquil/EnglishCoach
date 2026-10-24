package com.englishcoach.ui.word

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englishcoach.EnglishCoachApp
import com.englishcoach.core.model.VocabItem
import com.englishcoach.speech.AsrEvent
import com.englishcoach.ui.theme.CorrectGreen
import com.englishcoach.ui.theme.ErrorRed
import com.englishcoach.ui.theme.HyperBlue
import com.englishcoach.ui.theme.PageBg
import com.englishcoach.ui.theme.TextPrimary
import com.englishcoach.ui.theme.TextSecondary
import com.englishcoach.ui.theme.XiaomiOrange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 单词 Tab UI 状态 */
data class VocabUiState(
    val loading: Boolean = true,
    val items: List<VocabItem> = emptyList(),          // 全部生词（加入时间倒序）
    val dueWords: List<String> = emptyList(),          // 今日到期词（reviewStage 未毕业且 dueDay <= 今天）
    val scenarioTitles: Map<String, String> = emptyMap(), // 来源场景 id → 名称（列表徽标）
    val todayEpochDay: Long = 0L,                      // 今天（词行" N 天后"倒计时用）
    val reviewing: Boolean = false,                    // 复习态
    val reviewQueue: List<VocabItem> = emptyList(),    // 本次复习队列
    val reviewIndex: Int = 0                           // 当前复习到第几个
) {
    val dueCount: Int get() = dueWords.size
    val currentReview: VocabItem? get() = reviewQueue.getOrNull(reviewIndex)
}

/** 单词 Tab ViewModel：列表 + 三档复习调度（PM v2 需求2 最小可用版） */
class VocabularyViewModel(private val app: EnglishCoachApp) : ViewModel() {

    private val _uiState = MutableStateFlow(VocabUiState())
    val uiState: StateFlow<VocabUiState> = _uiState.asStateFlow()

    /** 进页刷新：全部生词 + 今日到期 + 场景名映射 */
    fun refresh() {
        viewModelScope.launch {
            val today = LocalDate.now().toEpochDay()
            val items = runCatching { app.vocabStore.getAll() }.getOrDefault(emptyList())
                .sortedByDescending { it.addedAtEpochDay }
            val due = runCatching { app.vocabStore.dueItems(today) }.getOrDefault(emptyList())
            val titles = runCatching { app.scenarioRepository.loadAll().associate { it.id to it.title } }
                .getOrDefault(emptyMap())
            _uiState.update {
                it.copy(
                    loading = false,
                    items = items,
                    dueWords = due.map { item -> item.word },
                    scenarioTitles = titles,
                    todayEpochDay = today
                )
            }
        }
    }

    /** 进入复习态：队列 = 今日到期词（每次 ≤5 个，符合"2 分钟完成"碎片定位） */
    fun startReview() {
        _uiState.update {
            val queue = it.dueWords
                .mapNotNull { word -> it.items.find { item -> item.word.equals(word, ignoreCase = true) } }
                .take(5)
            if (queue.isEmpty()) it else it.copy(reviewing = true, reviewQueue = queue, reviewIndex = 0)
        }
    }

    /** 当前词过（跟读完成或跳过顺延）：标 reviewed 并推进；走完自动退出并刷新 */
    fun passCurrent(markReviewed: Boolean) {
        val current = _uiState.value.currentReview ?: return
        viewModelScope.launch {
            if (markReviewed) {
                runCatching { app.vocabStore.markReviewed(current.word) }
            }
            val next = _uiState.value.reviewIndex + 1
            if (next >= _uiState.value.reviewQueue.size) {
                _uiState.update { it.copy(reviewing = false, reviewQueue = emptyList(), reviewIndex = 0) }
                refresh()
            } else {
                _uiState.update { it.copy(reviewIndex = next) }
            }
        }
    }

    /** 退出复习（未过完的不标，顺延不惩罚） */
    fun exitReview() {
        _uiState.update { it.copy(reviewing = false, reviewQueue = emptyList(), reviewIndex = 0) }
        refresh()
    }

    companion object {
        fun factory(app: EnglishCoachApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = VocabularyViewModel(app) as T
            }
    }
}

/**
 * 「单词」Tab 三态（第二轮反馈需求 2 重做）：
 * ① 空态：引导卡片（跟读页点词收词 / 对话纠错自动收集）；② 列表态：顶部信息区 + 词卡列表；③ 复习态：一次一词大字号跟读。
 * 发音统一走 app.ttsClient（TtsRouter：云端/本机自动跟随设置）。
 */
@Composable
fun VocabularyScreen(onGoPractice: () -> Unit = {}, onOpenWordRepeat: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as EnglishCoachApp
    val viewModel: VocabularyViewModel = viewModel(factory = VocabularyViewModel.factory(app))
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    // ASR 状态（复习态跟读用，与 WordRepeatScreen 同一 AsrEngine）
    var isListening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var heard by remember { mutableStateOf("") } // 复习"跟读一遍即过"，不比对对错（无惩罚）

    // v11.13：待移出生词本的词（二次确认弹层目标）
    var removeTarget by remember { mutableStateOf<com.englishcoach.core.model.VocabItem?>(null) }

    // 切到下一个复习词时清空上一词的识别状态，避免"✓ 过！"残留（QA v2 P2-1）
    LaunchedEffect(state.currentReview?.word) {
        isListening = false
        partial = ""
        heard = ""
    }

    // 预加载：复习态当前词 + 队列下一词音频预合成（落 TTS 磁盘缓存），点发音钮命中缓存秒播
    LaunchedEffect(state.currentReview?.word, state.reviewQueue) {
        val words = listOfNotNull(
            state.currentReview?.word,
            state.reviewQueue.getOrNull(state.reviewIndex + 1)?.word
        )
        words.forEach { w ->
            launch { runCatching { app.ttsClient.synthesize(w) } }
        }
    }

    // v11.12：进页/切回本页（ON_RESUME）都刷新——首页星标取消收藏后，单词页回到前台即重读
    // vocab.json，列表/到期计数与库态一致（此前仅 LaunchedEffect(Unit) 进页刷一次，
    // tab 切回不重跑会显示已移除的词）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        app.asrEngine.events.collect { event ->
            when (event) {
                is AsrEvent.Partial -> partial = event.text
                is AsrEvent.Final -> {
                    partial = ""
                    isListening = false
                    heard = event.text
                    // 跟读一遍即过：收到识别结果就算完成（无惩罚不清除）
                    viewModel.passCurrent(markReviewed = true)
                }
                is AsrEvent.Error -> {
                    isListening = false
                    partial = ""
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
    ) {
        Text(
            text = "单词",
            style = MaterialTheme.typography.displayLarge,
            color = TextPrimary,
            modifier = Modifier.padding(start = 18.dp, top = 12.dp)
        )

        when {
            // —— ③ 复习态：一次一个词，大字号 ——
            state.reviewing && state.currentReview != null -> {
                ReviewView(
                    item = state.currentReview!!,
                    index = state.reviewIndex,
                    total = state.reviewQueue.size,
                    isListening = isListening,
                    partial = partial,
                    heard = heard,
                    onSpeak = {
                        scope.launch {
                            val word = state.currentReview?.word ?: return@launch
                            runCatching {
                                val audio = app.ttsClient.synthesize(word)
                                app.audioPlayer.play(audio) {}
                            }
                        }
                    },
                    onMicPressed = {
                        heard = ""
                        partial = ""
                        app.asrEngine.startListening()
                        isListening = true
                    },
                    onMicReleased = { app.asrEngine.stopListening() },
                    onSkip = { viewModel.passCurrent(markReviewed = false) }, // 顺延不惩罚
                    onExit = { viewModel.exitReview() }
                )
            }

            // —— ① 空态：引导去口语页练对话（v10.7 参考图版） ——
            !state.loading && state.items.isEmpty() -> {
                EmptyView(onGoPractice = onGoPractice)
            }

            // —— 加载中 ——
            state.loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                }
            }

            // —— ② 列表态：今日复习卡 + 词行列表 ——
            else -> {
                VocabListView(
                    state = state,
                    onStartReview = { viewModel.startReview() },
                    onOpenWordRepeat = onOpenWordRepeat,
                    onSpeak = { word ->
                        scope.launch {
                            runCatching {
                                val audio = app.ttsClient.synthesize(word)
                                app.audioPlayer.play(audio) {}
                            }
                        }
                    },
                    // v11.13：移出生词本（二次确认后调 remove，随即 refresh 即时刷新）
                    onRemove = { removeTarget = it }
                )
            }
        }

        // v11.13：移出生词本二次确认（防误触；确认后 remove + 刷新，被移除词若在
        // 复习队列，refresh 重算队列后自动消失）
        removeTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { removeTarget = null },
                title = { Text(text = "移出生词本？") },
                text = {
                    Text(text = "「${target.word}」将被移出生词本，复习进度同时清除。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { app.vocabStore.remove(target.word) }
                            removeTarget = null
                            viewModel.refresh()
                        }
                    }) {
                        Text(text = "移出", color = ErrorRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { removeTarget = null }) {
                        Text(text = "取消", color = TextSecondary)
                    }
                }
            )
        }
    }
}

/**
 * 空态（v10.7 按用户参考图重做，拍板优先于 v10 设计稿）：
 * 浅色背景垂直居中——"+1" 堆叠卡视觉（蓝边白主卡 + 两张白卡错位旋转露边）
 * + 标题"还没有生词" + 两行说明 + 蓝胶囊"🎤 去练一段对话" + 远距灰色注脚。
 * 空态不放渐变头卡 / 去读第 1 关 / 快捷跟读链接（列表态保留不动）。
 */
@Composable
private fun EmptyView(onGoPractice: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // —— "+1" 堆叠卡视觉 ——
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            contentAlignment = Alignment.Center
        ) {
            // 左后白卡（无描边，略左旋下移）
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = (-32).dp, y = 16.dp)
                    .graphicsLayer { rotationZ = -7f }
                    .shadow(elevation = 5.dp, shape = RoundedCornerShape(16.dp), clip = false)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .size(width = 108.dp, height = 68.dp)
            )
            // 右后白卡（略右旋下移）
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = 32.dp, y = 16.dp)
                    .graphicsLayer { rotationZ = 7f }
                    .shadow(elevation = 5.dp, shape = RoundedCornerShape(16.dp), clip = false)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .size(width = 108.dp, height = 68.dp)
            )
            // 最前主卡：浅蓝描边白卡 + "+1"
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-4).dp)
                    .graphicsLayer { rotationZ = -2f }
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(18.dp), clip = false)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .border(1.5.dp, HyperBlue.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                    .size(width = 118.dp, height = 74.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+1",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = HyperBlue
                )
            }
        }
        Spacer(modifier = Modifier.height(28.dp))
        // 标题
        Text(
            text = "还没有生词",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        // 说明两行
        Text(
            text = "去口语页练一段对话，\n说到的生词和好句子会自动收进这里。",
            fontSize = 15.sp,
            color = TextSecondary,
            lineHeight = 23.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(30.dp))
        // 主按钮：蓝胶囊 "🎤 去练一段对话"
        Row(
            modifier = Modifier
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp))
                .background(HyperBlue)
                .clickable(onClick = onGoPractice)
                .padding(horizontal = 26.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "去练一段对话",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
        // 注脚：与按钮间距拉远（按参考图）
        Spacer(modifier = Modifier.height(56.dp))
        Text(
            text = "首页的「快捷跟读」卡每天也会换新词",
            fontSize = 12.5.sp,
            color = TextSecondary.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** 顶部渐变汇总头卡（稿 .wd-hero：eyebrow + 总词数 + 待复习/已掌握/学习中 三组） */
@Composable
private fun VocabHeroCard(total: Int, due: Int, graduated: Int, learning: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF5CA9FF), HyperBlue, Color(0xFF1F6FE0)))
            )
    ) {
        // 右上星形装饰（白 16%，稿 .wd-hero .art）
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.16f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 10.dp, y = (-10).dp)
                .size(96.dp)
        )
        Column(modifier = Modifier.padding(horizontal = 17.dp, vertical = 14.dp)) {
            Text(
                text = "我的生词本",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = "$total",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "个词",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            Row(modifier = Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HeroStat(due, "待复习")
                HeroStat(graduated, "已掌握")
                HeroStat(learning, "学习中")
            }
        }
    }
}

@Composable
private fun HeroStat(value: Int, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text = "$value", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(bottom = 1.dp)
        )
    }
}

/** 列表态（v10.6 对齐设计稿）：渐变汇总头卡 + 今日复习入口（白卡+蓝胶囊）+ 组卡词行（来源徽标+倒计时） */
@Composable
private fun VocabListView(
    state: VocabUiState,
    onStartReview: () -> Unit,
    onOpenWordRepeat: () -> Unit,
    onSpeak: (String) -> Unit,
    onRemove: (VocabItem) -> Unit = {}
) {
    val graduated = state.items.count { VocabItem.isGraduated(it) }
    val learning = (state.items.size - graduated - state.dueCount).coerceAtLeast(0)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 顶部信息区：渐变汇总头卡（总词数 / 待复习 / 已掌握 / 学习中）
        item {
            VocabHeroCard(
                total = state.items.size,
                due = state.dueCount,
                graduated = graduated,
                learning = learning
            )
        }
        // 快捷跟读显式入口（QA 补充需求：与首页单词卡"跟读这个词"同一能力，认知拉齐）
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .clickable(onClick = onOpenWordRepeat)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = HyperBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "快捷跟读 →",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "大卡堆叠浏览 · 点段发音 · 按住跟读",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        }
        // 今日复习入口卡（稿 .rev-card：白卡左文案 + 右蓝胶囊按钮）
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = state.dueCount > 0, onClick = onStartReview)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "今日复习",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (state.dueCount > 0) {
                                "到期 ${state.dueCount} 词 · 跟读一遍即过"
                            } else {
                                "今日没有到期的词，歇一歇"
                            },
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    // 右侧蓝胶囊按钮（稿 .rev-btn）
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(17.dp))
                            .background(if (state.dueCount > 0) HyperBlue else Color(0xFFC6C7CC))
                            .clickable(enabled = state.dueCount > 0, onClick = onStartReview)
                            .padding(horizontal = 15.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "开始复习",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
        // 分节标题：全部生词 + 排序提示（稿 .sec-h）
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(text = "全部生词", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                Spacer(modifier = Modifier.weight(1f))
                Text(text = "按收词时间排序", fontSize = 11.5.sp, color = TextSecondary.copy(alpha = 0.7f))
            }
        }
        // 词卡组：单个白圆角卡包全部词行，行间 hairline（稿 .group + .wrow + .hairline）
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
            ) {
                state.items.forEachIndexed { rowIndex, item ->
                    if (rowIndex > 0) {
                        // hairline：左缩进 16dp 的 0.5dp 分隔线
                        Box(
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(TextPrimary.copy(alpha = 0.06f))
                        )
                    }
                    VocabRow(item = item, state = state, onSpeak = onSpeak, onRemove = onRemove)
                }
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

/** 单个词行（稿 .wrow）：due 色点+词名 | 音标释义+来源徽标+倒计时 | 发音钮 */
@Composable
private fun VocabRow(
    item: VocabItem,
    state: VocabUiState,
    onSpeak: (String) -> Unit,
    onRemove: (VocabItem) -> Unit = {}
) {
    val isDue = state.dueWords.any { it.equals(item.word, ignoreCase = true) }
    // 倒计时口径（稿 .cd）：毕业 → "已毕业 ✓"；dueDay<=今天 → "今天到期"(橙)；否则 " N 天后"
    val dueInDays = VocabItem.dueDay(item) - state.todayEpochDay
    val graduated = VocabItem.isGraduated(item)
    val countdownText = when {
        graduated -> "已毕业 ✓"
        dueInDays <= 0 -> "今天到期"
        else -> "$dueInDays 天后"
    }
    val countdownColor = when {
        graduated -> CorrectGreen
        dueInDays <= 0 -> XiaomiOrange
        else -> TextSecondary.copy(alpha = 0.7f)
    }
    // 来源徽标：NCE 来源紫（稿 .src.nce），口语场景蓝（稿 .src）
    val isNceSource = item.sourceScenarioId.startsWith("nce_l")
    val sourceTitle = if (isNceSource) {
        "NCE L" + item.sourceScenarioId.removePrefix("nce_l")
    } else if (item.sourceScenarioId == "home_deck") {
        // v11.5：首页快捷跟读卡收藏来源
        "快捷跟读"
    } else {
        state.scenarioTitles[item.sourceScenarioId] ?: "对话"
    }
    val sourceColor = if (isNceSource) Color(0xFF6A5BFF) else HyperBlue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.5.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 词名列：due 色点（仅到期词）+ 词
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isDue) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(XiaomiOrange)
                )
                Spacer(modifier = Modifier.width(7.dp))
            }
            Text(
                text = item.word,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        // 信息列：音标释义 + 来源徽标/倒计时
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${item.phonetic} · ${item.meaning}",
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = sourceTitle,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = sourceColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(sourceColor.copy(alpha = 0.09f))
                        .padding(horizontal = 6.dp, vertical = 1.5.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = countdownText,
                    fontSize = 10.5.sp,
                    fontWeight = if (isDue) FontWeight.SemiBold else FontWeight.Normal,
                    color = countdownColor
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        // 发音钮（稿 .say：34dp 圆、蓝 10% 底）
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(HyperBlue.copy(alpha = 0.10f))
                .clickable { onSpeak(item.word) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.VolumeUp,
                contentDescription = "发音",
                tint = HyperBlue,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        // v11.13：移出生词本入口（40dp 命中区、克制灰色小图标；点击触发二次确认）
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable { onRemove(item) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "移出生词本",
                tint = TextSecondary.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 复习态：一次一个词大字号 → 发音钮听 → 按住说话跟读一遍即过 */
@Composable
private fun ReviewView(
    item: VocabItem,
    index: Int,
    total: Int,
    isListening: Boolean,
    partial: String,
    heard: String,
    onSpeak: () -> Unit,
    onMicPressed: () -> Unit,
    onMicReleased: () -> Unit,
    onSkip: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        // 顶栏：← 退出 + 进度
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "‹ 退出",
                fontSize = 15.sp,
                color = HyperBlue,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onExit)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "复习 ${index + 1}/$total",
                fontSize = 14.sp,
                color = TextSecondary
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            // 大字单词卡
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = item.word,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${item.phonetic} · ${item.meaning}",
                        fontSize = 14.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.example.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.example,
                            fontSize = 13.sp,
                            color = TextSecondary.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    // 🔊 听一遍
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(HyperBlue.copy(alpha = 0.08f))
                            .clickable(onClick = onSpeak)
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
                        Text(text = "听一遍", fontSize = 13.sp, color = HyperBlue)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            // 跟读提示（无惩罚：说一遍就过，说错也不纠正）
            val resultText = when {
                isListening && partial.isNotBlank() -> partial
                heard.isNotBlank() -> "✓ 过！"
                else -> "看着词，跟读一遍就过"
            }
            Text(
                text = resultText,
                fontSize = 15.sp,
                color = if (heard.isNotBlank()) CorrectGreen else TextSecondary
            )
            Spacer(modifier = Modifier.weight(1f))

            // 跳过（顺延不惩罚，不标已完成）
            Text(
                text = "跳过这个词",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSkip)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 按住说话（与对话页/跟读页同交互）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (isListening) ErrorRed else HyperBlue)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onMicPressed()
                                try {
                                    awaitRelease()
                                } finally {
                                    onMicReleased()
                                }
                            }
                        )
                    },
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
                        text = if (isListening) "松开结束" else "按住跟读",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
