package com.englishcoach.ui.debug

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.englishcoach.EnglishCoachApp
import com.englishcoach.core.KeyHolder
import com.englishcoach.core.model.LlmProvider
import com.englishcoach.llm.LlmMessage
import com.englishcoach.speech.AsrEvent
import com.englishcoach.ui.theme.ErrorRed
import com.englishcoach.ui.theme.HyperBlue
import com.englishcoach.ui.theme.PageBg
import com.englishcoach.ui.theme.TextPrimary
import com.englishcoach.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 调试页：ASR / TTS / LLM 三个环节单独测试（跑通链路用，不删也不碍事）。
 * 排障入口：Logcat 过滤 "EC-"。
 */
@Composable
fun DebugSpeechScreen() {
    val app = LocalContext.current.applicationContext as EnglishCoachApp
    val scope = rememberCoroutineScope()
    // 当前对话模型供应商（设置页切换后本卡标题与测试目标跟随）
    val settings by app.settingsStore.settings.collectAsState(initial = null)
    val llmProviderName = when (settings?.llmProvider) {
        LlmProvider.ZHIPU -> "智谱 GLM"
        else -> "DeepSeek"
    }

    // ---- ASR 状态 ----
    val isListening by app.asrEngine.isListening.collectAsState()
    var asrPartial by remember { mutableStateOf("") }
    var asrFinal by remember { mutableStateOf("") }
    var asrError by remember { mutableStateOf("") }
    var asrDuration by remember { mutableStateOf("") }

    // ASR 事件收集（进页面启动一次）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        app.asrEngine.events.collect { event ->
            when (event) {
                is AsrEvent.Partial -> asrPartial = event.text
                is AsrEvent.Final -> {
                    asrFinal = event.text
                    asrDuration = "${event.durationMs / 1000.0} 秒"
                    asrPartial = ""
                }
                is AsrEvent.Error -> asrError = "${event.message} (code=${event.code})"
            }
        }
    }

    // ---- TTS 状态 ----
    var ttsText by remember { mutableStateOf("Hello! This is a voice test for English Coach.") }
    var ttsRate by remember { mutableStateOf(-30f) }
    var ttsStatus by remember { mutableStateOf("") }
    var ttsBusy by remember { mutableStateOf(false) }

    // ---- LLM 状态 ----
    var llmInput by remember { mutableStateOf("What did you do last weekend?") }
    var llmReply by remember { mutableStateOf("") }
    var llmCorrection by remember { mutableStateOf("") }
    var llmBusy by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(text = "调试", style = MaterialTheme.typography.displayLarge, color = TextPrimary)
        }
        item {
            KeyStatusCard()
        }

        // ---- ① ASR ----
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("① ASR 语音识别", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            isListening -> "正在听…松开结束"
                            asrPartial.isNotBlank() -> "Partial: $asrPartial"
                            asrFinal.isNotBlank() -> "Final: $asrFinal（时长 $asrDuration）"
                            asrError.isNotBlank() -> "Error: $asrError"
                            else -> "按住下方按钮说一句英语"
                        },
                        fontSize = 15.sp,
                        color = if (asrError.isNotBlank() && asrFinal.isBlank()) ErrorRed else TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(if (isListening) ErrorRed else HyperBlue)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        asrError = ""
                                        app.asrEngine.startListening()
                                        try {
                                            awaitRelease()
                                        } finally {
                                            app.asrEngine.stopListening()
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isListening) "松开结束" else "按住说话",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // ---- ② TTS ----
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("② 硅基流动 TTS（CosyVoice2）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ttsText,
                        onValueChange = { ttsText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入要朗读的英文") },
                        maxLines = 3
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "语速：${ttsRate.toInt()}%（0 = 正常，负数 = 慢速）",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Slider(
                        value = ttsRate,
                        onValueChange = { ttsRate = it },
                        valueRange = -50f..0f,
                        steps = 9
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                scope.launch {
                                    ttsBusy = true
                                    ttsStatus = "合成中…"
                                    try {
                                        val audio = app.ttsClient.synthesize(ttsText, ratePercent = ttsRate.toInt())
                                        ttsStatus = "播放中…（${audio.size} 字节）"
                                        app.audioPlayer.play(audio) {}
                                        ttsStatus = "播放完成"
                                    } catch (e: Exception) {
                                        ttsStatus = "失败：${e.message}"
                                    }
                                    ttsBusy = false
                                }
                            },
                            enabled = !ttsBusy && ttsText.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = HyperBlue)
                        ) {
                            Text("合成并播放")
                        }
                        if (ttsBusy) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(text = ttsStatus, fontSize = 13.sp, color = TextSecondary)
                    }
                }
            }
        }

        // ---- ③ LLM ----
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("③ ${llmProviderName} LLM", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = llmInput,
                        onValueChange = { llmInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入一句英语") },
                        maxLines = 3
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = {
                                scope.launch {
                                    llmBusy = true
                                    llmReply = "请求中…"
                                    try {
                                        llmReply = app.llmClient.chat(
                                            history = listOf(LlmMessage(role = "user", content = llmInput)),
                                            system = "You are a friendly English chat partner. Reply in 1-2 short sentences."
                                        )
                                    } catch (e: Exception) {
                                        llmReply = "失败：${e.message}"
                                    }
                                    llmBusy = false
                                }
                            },
                            enabled = !llmBusy && llmInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = HyperBlue)
                        ) {
                            Text("chat()")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    llmBusy = true
                                    llmCorrection = "请求中…"
                                    try {
                                        val correction = app.llmClient.correct(llmInput, "自由聊天场景")
                                        llmCorrection = "原话：${correction.original}\n正确：${correction.fixed}\n类型：${correction.type}\n解释：${correction.note}"
                                    } catch (e: Exception) {
                                        llmCorrection = "失败：${e.message}"
                                    }
                                    llmBusy = false
                                }
                            },
                            enabled = !llmBusy && llmInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = HyperBlue)
                        ) {
                            Text("correct()")
                        }
                    }
                    if (llmReply.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(text = "回复：$llmReply", fontSize = 14.sp, color = TextPrimary)
                    }
                    if (llmCorrection.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(text = llmCorrection, fontSize = 14.sp, color = TextPrimary)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** Key 配置状态（只显示配没配，不打 Key 内容） */
@Composable
private fun KeyStatusCard() {
    fun keyLabel(configured: Boolean, isPlaceholder: Boolean): String = when {
        configured -> "已配置"
        isPlaceholder -> "❌ 未配置（仅占位符，请填真实 Key）"
        else -> "❌ 未配置（local.properties 或设置页）"
    }
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("服务状态", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "DeepSeek Key：${keyLabel(KeyHolder.isDeepSeekConfigured, KeyHolder.deepSeekKeyIsPlaceholder)}",
                fontSize = 14.sp,
                color = if (KeyHolder.isDeepSeekConfigured) TextSecondary else ErrorRed
            )
            Text(
                text = "SiliconFlow Key：${keyLabel(KeyHolder.isSiliconflowConfigured, KeyHolder.siliconflowKeyIsPlaceholder)}",
                fontSize = 14.sp,
                color = if (KeyHolder.isSiliconflowConfigured) TextSecondary else ErrorRed
            )
        }
    }
}
