package com.englishcoach.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englishcoach.EnglishCoachApp
import com.englishcoach.core.model.ChatMessage
import com.englishcoach.core.model.Correction
import com.englishcoach.core.model.InputMode
import com.englishcoach.core.model.NotableWord
import com.englishcoach.core.model.Role
import com.englishcoach.ui.common.WaveBarsIndicator
import com.englishcoach.ui.common.voiceDomeGesture
import com.englishcoach.ui.theme.CardWhite
import com.englishcoach.ui.theme.CorrectGreen
import com.englishcoach.ui.theme.CorrectionBarBg
import com.englishcoach.ui.theme.ErrorRed
import com.englishcoach.ui.theme.HyperBlue
import com.englishcoach.ui.theme.PageBg
import com.englishcoach.ui.theme.TextPrimary
import com.englishcoach.ui.theme.TextSecondary
import com.englishcoach.ui.theme.XiaomiOrange
import kotlinx.coroutines.launch

/** 对话页：气泡 + 纠错条 + 按住说话大按钮 + 🔊重听/🐢慢速/🌐翻译 */
@Composable
fun ChatScreen(
    scenarioId: String,
    onBack: () -> Unit
) {
    val app = LocalContext.current.applicationContext as EnglishCoachApp
    val viewModel: ChatViewModel = viewModel(
        key = "chat_$scenarioId",
        factory = ChatViewModelFactory(app, scenarioId)
    )
    val state by viewModel.uiState.collectAsState()
    val dome = com.englishcoach.ui.common.rememberVoiceDomeState()
    // v11.6 P0-2：录音实时音量转发 → 圆顶声波条/高度呼吸/涟漪
    LaunchedEffect(Unit) {
        app.asrEngine.rmsLevel.collect { dome.rms = it }
    }

    // 结束练习 → 退出
    LaunchedEffect(state.exit) {
        if (state.exit) onBack()
    }

    // 圆顶收起联动：新消息上屏（识别结果已被编排层处理）或错误提示出现
    LaunchedEffect(state.messages.size, state.hint) {
        dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
    }

    // 系统打断（来电/切后台）：停引擎 + 复位圆顶；页面销毁补停 ASR
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                app.asrEngine.stopListening()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            app.asrEngine.stopListening()
        }
    }

    // 生词本：已收词集合（纠错卡"＋收进单词本"按钮按此去重显示"已收"）
    var addedWords by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        runCatching { app.vocabStore.getAll().map { it.word.lowercase() } }
            .onSuccess { addedWords = it.toSet() }
    }
    val addWord: (NotableWord) -> Unit = { notable ->
        scope.launch {
            val added = runCatching { app.vocabStore.add(notable, scenarioId) }.getOrDefault(false)
            if (added) addedWords = addedWords + notable.word.trim().lowercase()
        }
    }

    // 顶栏标题：启用关卡显示"第 N 关 · 场景名"
    val scenario = state.scenario
    val topTitle = if (scenario != null && scenario.order > 0) {
        "第 ${scenario.order} 关 · ${scenario.title}"
    } else {
        scenario?.title ?: "对话练习"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PageBg)
                .imePadding()
        ) {
        // 顶栏（52dp）
        ChatTopBar(
            title = topTitle,
            subtitle = scenario?.titleEn ?: "",
            isImmersive = state.isImmersive,
            onBack = onBack,
            onEnd = { viewModel.endPractice() }
        )
        // 关卡进度条（4dp）：当前轮数 / targetRounds，与首页关卡卡同源；达标（可通关）变绿
        if (scenario != null && scenario.order > 0) {
            val turns = state.messages.count { it.role == Role.USER }
            LevelProgressBar(turns = turns, target = scenario.targetRounds)
        }

        // 消息列表
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageItem(
                    message = message,
                    addedWords = addedWords,
                    onAddWord = addWord,
                    onReplay = { viewModel.replay(message.text) },
                    onSlow = { viewModel.speakSlowly(message.text) },
                    onTranslate = { viewModel.translate(message) }
                )
            }
            if (state.isThinking) {
                item(key = "thinking") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "AI 正在回复…", fontSize = 13.sp, color = TextSecondary)
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
        // 新消息到底
        LaunchedEffect(state.messages.size, state.isThinking) {
            val target = if (state.isThinking) state.messages.size else state.messages.size - 1
            if (target >= 0) listState.animateScrollToItem(target)
        }

        // 底部输入区
        InputArea(
            state = state,
            hint = state.hint,
            dome = dome,
            onMicPressed = { viewModel.onMicPressed() },
            onMicReleased = { viewModel.onMicReleased() },
            onPendingTextChange = { viewModel.onPendingTextChange(it) },
            onSend = { viewModel.sendText() }
        )
        }

        // 微信式圆顶录音 overlay（1b：三页共用）
        com.englishcoach.ui.common.VoiceDomeOverlay(state = dome)
    }

    // 复盘/小结卡（手动结束 = 复盘；自动触发 = 小结）
    if (state.showSummary) {
        SummaryDialog(
            corrections = state.summary,
            isAuto = state.summaryAuto,
            addedWords = addedWords,
            onAddWord = addWord,
            onDismiss = { viewModel.dismissSummary() }
        )
    }
}

/** 顶栏：← + 场景名 + 沉浸模式标签 + 结束练习 */
@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String,
    isImmersive: Boolean,
    onBack: () -> Unit,
    onEnd: () -> Unit
) {
    Surface(color = PageBg) {
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
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    if (isImmersive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(XiaomiOrange.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = "沉浸模式", fontSize = 11.sp, color = XiaomiOrange)
                        }
                    }
                }
                Text(text = subtitle, fontSize = 11.sp, color = TextSecondary)
            }
            TextButton(onClick = onEnd) {
                Text(text = "结束练习", fontSize = 15.sp, color = HyperBlue)
            }
        }
    }
}

/** 关卡进度条（4dp）：当前轮数 / 目标轮数；与首页关卡卡进度线同源同值（PM 口径） */
@Composable
private fun LevelProgressBar(turns: Int, target: Int) {
    val fraction = if (target > 0) (turns.toFloat() / target).coerceIn(0f, 1f) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(TextSecondary.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(if (turns >= target) CorrectGreen else HyperBlue)
        )
    }
}

/** 单条消息：AI 白气泡（带动作行）/ 用户蓝气泡（下挂黄色纠错条） */
@Composable
private fun MessageItem(
    message: ChatMessage,
    addedWords: Set<String>,
    onAddWord: (NotableWord) -> Unit,
    onReplay: () -> Unit,
    onSlow: () -> Unit,
    onTranslate: () -> Unit
) {
    // 气泡弹性渐入：translateY(10)→0 + alpha，420ms
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, animationSpec = tween(420)) }

    when (message.role) {
        Role.USER -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = appear.value
                        translationY = (1f - appear.value) * 10.dp.toPx()
                    },
                horizontalAlignment = Alignment.End
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
                    color = HyperBlue
                ) {
                    Text(
                        text = message.text,
                        color = Color.White,
                        fontSize = 17.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                // 纠错条（学习模式）：黄底挂在用户气泡下
                message.correction?.let { correction ->
                    Spacer(modifier = Modifier.height(6.dp))
                    CorrectionBar(
                        correction = correction,
                        addedWords = addedWords,
                        onAddWord = onAddWord
                    )
                }
            }
        }

        Role.AI -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = appear.value
                        translationY = (1f - appear.value) * 10.dp.toPx()
                    }
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
                    color = CardWhite,
                    shadowElevation = 1.dp
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(text = message.text, color = TextPrimary, fontSize = 17.sp)
                        // 🌐 翻译结果（点过翻译后才显示）
                        message.chinese?.let { chinese ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = chinese, color = TextSecondary, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row {
                            TextAction("重听", onReplay)
                            Spacer(modifier = Modifier.width(16.dp))
                            TextAction("慢速", onSlow)
                            Spacer(modifier = Modifier.width(16.dp))
                            TextAction(
                                if (message.chinese == null) "翻译" else "已翻译",
                                onTranslate,
                                enabled = message.chinese == null
                            )
                        }
                    }
                }
            }
        }

        Role.CORRECTION -> {
            // MVP 中纠错挂在用户消息上，此角色仅作数据兼容占位
            Box(Modifier.size(0.dp))
        }
    }
}

/** 黄色纠错条：原话（红删除线）→ 正确说法（绿）+ 中文解释 + 值得学的词（逐词可收进单词本） */
@Composable
private fun CorrectionBar(
    correction: Correction,
    addedWords: Set<String>,
    onAddWord: (NotableWord) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CorrectionBarBg,
        modifier = Modifier.widthIn(max = 300.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = correction.typeLabel(),
                    fontSize = 11.sp,
                    color = XiaomiOrange,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = correction.original,
                    fontSize = 14.sp,
                    color = ErrorRed,
                    textDecoration = TextDecoration.LineThrough
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "✓ ${correction.fixed}",
                fontSize = 14.sp,
                color = CorrectGreen,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = correction.note, fontSize = 13.sp, color = TextSecondary)
            // 值得学的词（LLM 顺带返回，每轮 ≤3 个）：逐词展示 + 轻按钮收进单词本
            if (correction.notableWords.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "值得学的词", fontSize = 11.sp, color = TextSecondary)
                correction.notableWords.forEach { notable ->
                    val added = notable.word.trim().lowercase() in addedWords
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = notable.word,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = notable.meaning,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // 轻按钮：未收 = "＋收进单词本"，已收 = "✓ 已收"（同词去重，不可重复点）
                        Text(
                            text = if (added) "✓ 已收" else "＋收进单词本",
                            fontSize = 12.sp,
                            color = if (added) CorrectGreen else HyperBlue,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .clickable(enabled = !added) { onAddWord(notable) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 纠错类型的中文标签 */
private fun Correction.typeLabel(): String = when (type) {
    "GRAMMAR" -> "语法"
    "WORDING" -> "用词"
    "PRONUNCIATION" -> "发音"
    else -> "表达"
}

/** 小文字动作按钮（🔊重听 / 🐢慢速 / 🌐翻译） */
@Composable
private fun TextAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        text = label,
        fontSize = 13.sp,
        color = if (enabled) HyperBlue else TextSecondary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.6f)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp)
    )
}

/**
 * 底部输入区：语音模式 = 按住说话大按钮（pill，52dp+）；文字模式 = 输入框 + 发送。
 * 按住说话：pointerInput onPress → 按下开始录音，松开 stopListening（说完自动出 Final）。
 */
@Composable
private fun InputArea(
    state: ChatUiState,
    hint: String?,
    dome: com.englishcoach.ui.common.VoiceDomeState,
    onMicPressed: () -> Unit,
    onMicReleased: () -> Unit,
    onPendingTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    // v11.6 P1-2：取消态触感宿主
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PageBg)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 实时识别文字 / 错误提示
        if (state.isListening && state.partialText.isNotBlank()) {
            Text(
                text = state.partialText,
                fontSize = 15.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (!state.isListening && hint != null) {
            Text(
                text = hint,
                fontSize = 13.sp,
                color = ErrorRed,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (!micGranted && state.inputMode == InputMode.VOICE) {
            Text(
                text = "未授权麦克风，请到系统设置开启，或切到设置页改用文字输入",
                fontSize = 13.sp,
                color = ErrorRed,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (state.inputMode == InputMode.VOICE) {
            // 按住说话大按钮（AI 回复中置灰禁用，防止并发第二轮）
            val canTalk = !state.isThinking
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        when {
                            state.isListening -> ErrorRed
                            canTalk -> HyperBlue
                            else -> TextSecondary.copy(alpha = 0.35f) // AI 回复中置灰
                        }
                    )
                    .then(
                        if (canTalk) {
                            // 微信式圆顶手势（1b 三页共用）：按下录音、上滑取消、<300ms 防误触
                            Modifier.voiceDomeGesture(
                                state = dome,
                                enabled = true,
                                onPressStart = { onMicPressed() },
                                onPressCancel = {
                                    dome.phase = com.englishcoach.ui.common.DomePhase.HIDDEN
                                    onMicReleased() // 停止监听；无有效结果由编排层丢弃
                                },
                                onPressRelease = { _ -> onMicReleased() },
                                // v11.6 P1-2：滑入取消区触感一下
                                hapticFeedback = {
                                    hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            )
                        } else {
                            Modifier // isThinking 期间不挂手势
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
                        // 录音中视觉态：麦克风 + 均衡器声浪条（微信发语音效果）
                        WaveBarsIndicator(color = Color.White)
                    } else {
                        Text(
                            text = if (canTalk) "按住说话" else "AI 回复中…",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            // 文字模式
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = state.pendingText,
                    onValueChange = onPendingTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("用英语输入…", fontSize = 15.sp) },
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 3
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onSend,
                    enabled = state.pendingText.isNotBlank() && !state.isThinking
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (state.pendingText.isNotBlank()) HyperBlue else TextSecondary
                    )
                }
            }
        }
    }
}

/** 复盘/小结卡：本次练习的错误汇总（手动结束与自动小结共用，标题区分；同样可收生词） */
@Composable
private fun SummaryDialog(
    corrections: List<Correction>,
    isAuto: Boolean,
    addedWords: Set<String>,
    onAddWord: (NotableWord) -> Unit,
    onDismiss: () -> Unit
) {
    val title = when {
        corrections.isEmpty() && isAuto -> "自动小结"
        corrections.isEmpty() -> "本次复盘"
        isAuto -> "本次小结 · ${corrections.size} 个改进点"
        else -> "本次复盘 · ${corrections.size} 个改进点"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        shape = RoundedCornerShape(28.dp), // M3 权限弹窗同款大圆角
        title = {
            Text(text = title, fontWeight = FontWeight.SemiBold)
        },
        text = {
            if (corrections.isEmpty()) {
                Text(
                    text = if (isAuto) "这一小节没有发现明显错误，说得不错！继续保持 🔥" else "没有发现明显错误，说得不错！继续保持 🔥",
                    color = TextSecondary
                )
            } else {
                // 限高，防止 LazyColumn 在 AlertDialog 里拿无限高度约束
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(corrections.size) { index ->
                        CorrectionBar(
                            correction = corrections[index],
                            addedWords = addedWords,
                            onAddWord = onAddWord
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = HyperBlue, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
