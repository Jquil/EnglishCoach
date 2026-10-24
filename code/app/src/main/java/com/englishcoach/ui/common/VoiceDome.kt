package com.englishcoach.ui.common

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.englishcoach.ui.theme.ErrorRed
import com.englishcoach.ui.theme.HyperBlue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 圆顶状态机：隐藏 → 录音中 / 取消区 → 识别中 → 隐藏 */
enum class DomePhase { HIDDEN, RECORDING, CANCEL, RECOGNIZING }

/** 微信式圆顶录音交互的共享状态（手势与 overlay 之间、页面与组件之间） */
class VoiceDomeState {
    var phase by mutableStateOf(DomePhase.HIDDEN)
        internal set
    var elapsedMs by mutableLongStateOf(0L)
        internal set
    internal var pressAtMs = 0L

    /** v11.6 P0-2：录音实时音量 0..1（宿主页从 AsrEngine.rmsLevel 转发），
     *  overlay 内做 EMA 后驱动声波条 / 高度呼吸 / 音量涟漪；无 RMS 路径保持 0 走兜底 */
    var rms by mutableStateOf(0f)

    /** v11.6 P1-1：首页 hold 确认期状态位。0f = 确认中（宿主页据此驱动 250ms 进度环动画）；
     *  -1f = 非确认期（手势结束，环立即消失） */
    var confirmProgress by mutableFloatStateOf(-1f)
}

@Composable
fun rememberVoiceDomeState(): VoiceDomeState = remember { VoiceDomeState() }

/** v11.26 优化：降低防误触阈值，提升响应速度 */
private const val MIN_PRESS_MS = 200L

/** v11.26 优化：降低取消滑动距离，更容易取消 */
private val CANCEL_SLIDE_DP = 40.dp

/** v11.26 新增：录音最大时长（15秒），避免用户一直按住 */
private const val MAX_RECORD_MS = 15000L

/**
 * "按住说话"手势（微信交互）：按下 → 录音（圆顶升起），上滑 ≥56dp → 取消区，
 * 松手：<300ms 防误触（等价丢弃）/ 正常结束（进入识别中）。
 * 宿主只接五个回调：onPressStart（启动 ASR）/ onEnterCancel（滑入取消区瞬间，隔离本次结果）
 * / onExitCancel（滑回恢复，重启 ASR 会话）/ onPressCancel（丢弃）/ onPressRelease（进入识别）。
 *
 * v10.5 holdThresholdMs（首页快捷跟读卡用，Nce 默认 0 行为不变）：
 * >0 时按下不立即录音——[holdThresholdMs] 内松手或横向滑动（切词意图）→ 本次手势完全放手，
 * 单击/双击/横滑交给宿主卡片上的其他手势处理；按住超过阈值才确认进入录音流程。
 * 确认后逐帧 consume 事件，冻结切词/tap（P1-3/P2-2 互斥），上滑取消照常可用（P2-4），
 * 圆顶计时 pressAtMs 由本手势设置（P1-2），<300ms 松手等价丢弃与 Nce 口径一致（P2-5）。
 *
 * v11.6：
 * - hold 确认期置 [VoiceDomeState.confirmProgress] = 0（结束置 -1），宿主页据此驱动确认进度环（P1-1）
 * - 滑入取消区瞬间触发 [hapticFeedback] 一次（P1-2，滑回恢复不触感）
 */
fun Modifier.voiceDomeGesture(
    state: VoiceDomeState,
    enabled: Boolean,
    holdThresholdMs: Long = 0L,
    onPressStart: () -> Unit,
    onPressCancel: () -> Unit,
    onPressRelease: (elapsedMs: Long) -> Unit,
    onEnterCancel: () -> Unit = {},
    onExitCancel: () -> Unit = {},
    hapticFeedback: (() -> Unit)? = null
): Modifier = this.pointerInput(enabled, holdThresholdMs) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!enabled) return@awaitEachGesture
        val downAtMs = SystemClock.elapsedRealtime()

        // hold 阈值确认：等待期内松手/横滑 → 放手（不弹圆顶、不启动录音）
        // 写法对齐官方 detectTapGestures 长按检测：withTimeoutOrNull 内直接 await 事件
        if (holdThresholdMs > 0) {
            // v11.6 P1-1：确认期状态位（0=确认中 / -1=结束）——宿主页据此用
            // Animatable 驱动 0→360° 进度环（时长=阈值），确认/放手即消失，视觉接力圆顶升起
            state.confirmProgress = 0f
            val confirmed = withTimeoutOrNull(holdThresholdMs) {
                val startX = down.position.x
                val slopPx = viewConfiguration.touchSlop
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull()
                        ?: return@withTimeoutOrNull false
                    if (!ch.pressed) return@withTimeoutOrNull false // 短按松手：tap 语义
                    if (kotlin.math.abs(ch.position.x - startX) > slopPx) {
                        return@withTimeoutOrNull false // 横滑：切词语义
                    }
                }
                @Suppress("UNREACHABLE_CODE") true
            } ?: true // 超时且仍按着：确认按住说话
            state.confirmProgress = -1f
            if (!confirmed) return@awaitEachGesture
        }

        state.pressAtMs = downAtMs
        state.elapsedMs = 0L
        state.phase = DomePhase.RECORDING
        onPressStart()
        val startY = down.position.y
        val cancelPx = CANCEL_SLIDE_DP.toPx()
        var cancelled = false

        // v11.26 新增：录音时长监控，超过15秒自动停止
        val maxRecordJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            kotlinx.coroutines.delay(MAX_RECORD_MS)
            // 如果还在录音状态，自动触发松手
            if (state.phase == DomePhase.RECORDING) {
                state.phase = DomePhase.RECOGNIZING
                onPressRelease(MAX_RECORD_MS)
            }
        }

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break // 松手
            // 录音确认后逐帧消费：冻结切词/tap 等并行手势，识别词与屏显词不再错位
            change.consume()
            val dy = change.position.y - startY
            if (dy < -cancelPx) {
                if (state.phase != DomePhase.CANCEL) {
                    state.phase = DomePhase.CANCEL
                    // v11.6 P1-2：滑入取消区触感一下（滑回恢复不触感）
                    hapticFeedback?.invoke()
                    // QA P2-4：滑入取消区瞬间通知宿主隔离本次已积累的识别结果，
                    // 堵"停留期间 ASR 提前吐 Final 被误消费"的竞态
                    onEnterCancel()
                }
                cancelled = true
            } else if (cancelled) {
                // 滑回原位恢复录音态（微信同款）：宿主重启 ASR 会话继续听
                state.phase = DomePhase.RECORDING
                cancelled = false
                onExitCancel()
            }
        }
        maxRecordJob.cancel() // 松手后取消超时任务
        val elapsed = SystemClock.elapsedRealtime() - downAtMs
        state.elapsedMs = elapsed
        when {
            cancelled -> {
                state.phase = DomePhase.HIDDEN
                onPressCancel()
            }

            elapsed < MIN_PRESS_MS -> {
                // v11.26：极短按防误触 200ms，比300ms更灵敏
                state.phase = DomePhase.HIDDEN
                onPressCancel()
            }

            else -> {
                state.phase = DomePhase.RECOGNIZING
                onPressRelease(elapsed)
            }
        }
    }
}

/** v11.26 优化：识别超时从8秒延长到12秒，给用户更多时间 */
private const val RECOGNIZING_TIMEOUT_MS = 12000L

/** v11.6：RMS EMA 采样周期（与 PcmRecorder ~80ms 节流对齐） */
private const val RMS_SAMPLE_MS = 80L
/** v11.9 3-B：EMA 平滑系数 0.35 → 0.20（波形线逐帧连续绘制，包络更顺滑） */
private const val RMS_EMA_ALPHA = 0.20f
/** v11.9 3-B：静默判定阈值（ema<0.05）与时长（1s）——转极缓微呼吸兜底 */
private const val RMS_SILENCE_EMA = 0.05f
private const val RMS_FALLBACK_MS = 1000L
/** v11.9 3-B：静默兜底微呼吸周期（秒），与静息起伏 3.2s 同口径 */
private const val IDLE_BREATH_SECONDS = 3.2f

/**
 * 微信式底部圆顶 overlay（v11.26 优化：录音时显示句子内容，避免遮挡）：
 * - 圆顶弧顶约到屏幕 46%（识别中保持不变），全宽大弧线缓而饱满
 * - 录音态：Mic 下方 5 根声波条随 RMS 起伏（EMA α=0.20），圆顶高度固定，
 *   峰值涟漪扩散环；ema<0.02 持续 1s → 声波条/涟漪淡出回退现状微起伏
 * - 识别等待期：46dp 白色不确定进度环 + 文案两段式「正在上传录音…」→「AI 听取中…」
 * - 取消态：触感（手势侧）+ 红/蓝渐变 180ms 过渡
 * - 信息层次：主提示白 96%、计时白 60% 弱化、RECORDING 常驻「上滑取消」白 40%
 * - v11.26 新增：录音时在圆顶上方显示当前句子内容，用户可边看边跟读
 *
 * v11.3 识别卡死三道修复（圆顶侧，保持）：
 * - [onRecognizingTap]：RECOGNIZING 态点按 scrim 或圆顶任意区域 = 等价取消——用户永远有出口
 * - [onRecognizingTimeout]：RECOGNIZING 停留超 8s 强制回调（宿主收圆顶 + 提示）
 *
 * @param sentenceContent 当前句子内容（录音时显示在圆顶上方），格式："英文\n中文"
 */
@Composable
fun VoiceDomeOverlay(
    state: VoiceDomeState,
    recordHint: String = "松开跟读",
    cancelHint: String = "松开取消",
    sentenceContent: String? = null,
    onRecognizingTap: (() -> Unit)? = null,
    onRecognizingTimeout: (() -> Unit)? = null
) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    // 计时：录音/取消区时每 200ms 刷新秒数
    LaunchedEffect(state.phase) {
        while (state.phase == DomePhase.RECORDING || state.phase == DomePhase.CANCEL) {
            state.elapsedMs = SystemClock.elapsedRealtime() - state.pressAtMs
            delay(200)
        }
    }
    // v11.3：识别中 8s 超时兜底——引擎沉默/死了无任何回调时圆顶也能自己收回来
    LaunchedEffect(state.phase) {
        if (state.phase == DomePhase.RECOGNIZING && onRecognizingTimeout != null) {
            delay(RECOGNIZING_TIMEOUT_MS)
            if (state.phase == DomePhase.RECOGNIZING) onRecognizingTimeout()
        }
    }

    // —— v11.9 3-B：RMS EMA（α=0.20）+ 静默兜底判定（ema<0.05 持续 1s → 极缓微呼吸） ——
    var ema by remember { mutableFloatStateOf(0f) }
    var rmsFallback by remember { mutableStateOf(false) }
    var silentSinceMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.phase) {
        if (state.phase == DomePhase.RECORDING || state.phase == DomePhase.CANCEL) {
            while (isActive) {
                ema += RMS_EMA_ALPHA * (state.rms - ema)
                val now = SystemClock.elapsedRealtime()
                if (ema < RMS_SILENCE_EMA) {
                    if (silentSinceMs == 0L) silentSinceMs = now
                    else if (now - silentSinceMs > RMS_FALLBACK_MS) rmsFallback = true
                } else {
                    silentSinceMs = 0L
                    rmsFallback = false
                }
                delay(RMS_SAMPLE_MS)
            }
        } else {
            ema = 0f
            rmsFallback = false
            silentSinceMs = 0L
        }
    }

    // v11.9 3-B：60fps 帧时钟（秒）——波形线逐帧连续起伏（消 80ms 步进跳变）；
    // 静默兜底时 displayEma 走 3.2s 极缓正弦微呼吸（幅度 3dp 基线附近，不劣化死直线）
    var frameT by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.phase) {
        if (state.phase == DomePhase.RECORDING || state.phase == DomePhase.CANCEL) {
            while (isActive) {
                withFrameNanos { nanos -> frameT = nanos / 1_000_000_000f }
            }
        }
    }
    val displayEma = if (rmsFallback) {
        0.05f + 0.03f * kotlin.math.sin(2f * Math.PI.toFloat() * frameT / IDLE_BREATH_SECONDS)
    } else {
        ema
    }

    // 圆顶表面波浪起伏（v11.9：静息起伏周期 900ms → 3.2s，更缓更安静；RMS 兜底回退基线保留）
    val wave = rememberInfiniteTransition(label = "domeWave").animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "domeWaveY"
    )

    val isCancel = state.phase == DomePhase.CANCEL
    // v11.26 UI优化：圆顶高度保持恒定，不再根据状态变化（避免松开跟读后尺寸突变）
    val domeHeight = (screenHeightDp * 0.46f).dp
    // v11.26 UI优化：移除取消态的scale缩放，保持尺寸一致性
    val domeScale = 1f
    // v11.6 P1-2：红/蓝渐变 180ms 过渡（替换原硬切）
    val gradTop by animateColorAsState(
        targetValue = if (isCancel) Color(0xFFFF8E86) else Color(0xFF74B2FF),
        animationSpec = tween(180), label = "gradTop"
    )
    val gradMid by animateColorAsState(
        targetValue = if (isCancel) Color(0xFFF2605A) else HyperBlue,
        animationSpec = tween(180), label = "gradMid"
    )
    val gradBottom by animateColorAsState(
        targetValue = if (isCancel) Color(0xFFE04B45) else Color(0xFF2E7BE0),
        animationSpec = tween(180), label = "gradBottom"
    )
    val domeBrush = Brush.verticalGradient(listOf(gradTop, gradMid, gradBottom))

    // v11.3：识别中点按任意区域（scrim/圆顶）= 等价取消——用户永远有出口，
    // 不再可能被"识别中弹窗关不掉"困住
    val recognizingTap = androidx.compose.runtime.rememberUpdatedState(onRecognizingTap)
    // v11.4 热修（真机回归：首页滑不动快捷跟读卡/点不了关卡卡）：
    // v11.3 把点按取消手势常驻挂在这个全屏 Box 上——pointerInput 回调内提前 return
    // 只是不响应手势，手势节点本身仍常驻 composition 并参与全局命中测试，圆顶隐藏时
    // 依然截胡整页触摸。现改为以 phase 为 key 条件挂载：仅 RECOGNIZING 时该
    // pointerInput 存在，其余 phase（尤其 HIDDEN）modifier 链整体重建、手势节点从
    // composition 完全移除，全屏层不再参与任何命中测试。（v11.6 保持不变）
    Box(
        modifier = if (state.phase == DomePhase.RECOGNIZING) {
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { recognizingTap.value?.invoke() }
                }
        } else {
            Modifier.fillMaxSize()
        }
    ) {
        // 背景压暗（录音期间拦截其它点击；v11.26：保持一致透明度）
        AnimatedVisibility(
            visible = state.phase != DomePhase.HIDDEN,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }
        // v11.26：录音/取消态时，在圆顶上方显示当前句子内容（用户可边看边跟读）
        if ((state.phase == DomePhase.RECORDING || state.phase == DomePhase.CANCEL) && !sentenceContent.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.95f))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val lines = sentenceContent.split("\n")
                    if (lines.isNotEmpty()) {
                        Text(
                            text = lines[0],
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0E0E12),
                            textAlign = TextAlign.Center,
                            lineHeight = 26.sp
                        )
                    }
                    if (lines.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = lines[1],
                            fontSize = 14.sp,
                            color = Color(0xFF6C727F),
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
        // 主列：提示文案（圆顶上方压暗背景上）+ 圆顶，整体贴底居中
        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            AnimatedVisibility(
                visible = state.phase != DomePhase.HIDDEN,
                enter = fadeIn(animationSpec = tween(120)),
                exit = fadeOut(animationSpec = tween(150))
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (state.phase) {
                        DomePhase.RECORDING -> {
                            // v11.26：显示录音时长和提示，15秒超时警告
                            val elapsed = state.elapsedMs / 1000
                            val isNearMax = elapsed >= 12
                            Text(
                                text = recordHint,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isNearMax) Color(0xFFFFB300) else Color.White.copy(alpha = 0.96f)
                            )
                            // 计时弱化：白 60% + 字距
                            Text(
                                text = formatSeconds(state.elapsedMs) + if (isNearMax) " / 15s" else "",
                                fontSize = 15.sp,
                                color = if (isNearMax) Color(0xFFFFB300) else Color.White.copy(alpha = 0.60f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            // v11.26 优化：取消提示更明显
                            Text(
                                text = "上滑取消 ↑",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.50f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        DomePhase.CANCEL -> {
                            Text(
                                text = cancelHint,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = ErrorRed
                            )
                            Text(
                                text = "${formatSeconds(state.elapsedMs)} · 录音作废",
                                fontSize = 15.sp,
                                color = Color.White.copy(alpha = 0.60f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        else -> {
                            // v11.6 P0-1：等待期文案两段式（跨fade 150ms）——解释 1~3s 在做什么
                            var recognizeStage by remember { mutableIntStateOf(0) }
                            LaunchedEffect(Unit) {
                                delay(600)
                                recognizeStage = 1
                            }
                            Crossfade(
                                targetState = recognizeStage,
                                animationSpec = tween(150),
                                label = "recognizingText"
                            ) { stage ->
                                Text(
                                    text = if (stage == 0) "正在上传录音…" else "AI 听取中…",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.92f)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(30.dp))
                }
            }
            // 圆顶主体：全屏宽、大弧线缓而饱满
            AnimatedVisibility(
                visible = state.phase != DomePhase.HIDDEN,
                enter = slideInVertically(
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 340f)
                ) { it } + fadeIn(),
                exit = slideOutVertically(animationSpec = tween(180)) { it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(domeHeight)
                        .graphicsLayer {
                            if (state.phase == DomePhase.RECORDING) {
                                translationY = wave.value
                            } else {
                                translationY = 0f
                            }
                            // v11.26：移除scale变化，保持尺寸一致
                            scaleX = 1f
                            scaleY = 1f
                        }
                        .clip(
                            RoundedCornerShape(
                                topStart = 110.dp, topEnd = 110.dp,
                                bottomStart = 0.dp, bottomEnd = 0.dp
                            )
                        )
                        .background(domeBrush),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.phase == DomePhase.RECOGNIZING) {
                        // v11.6 P0-1：46dp 白色不确定进度环（stroke 3dp）替代裸三点
                        CircularProgressIndicator(
                            modifier = Modifier.size(46.dp),
                            strokeWidth = 3.dp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    } else {
                        // v11.9 3-B：录音/取消态 = Mic（音量脉冲）+ 连续音量光晕 + iOS 听写式波形线
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(contentAlignment = Alignment.Center) {
                                // 连续音量光晕（96dp，与波形线同源 displayEma）：替代原离散涟漪
                                Box(
                                    modifier = Modifier
                                        .size(96.dp)
                                        .graphicsLayer {
                                            alpha = (displayEma * 0.5f).coerceIn(0f, 0.5f)
                                        }
                                        .background(
                                            Brush.radialGradient(
                                                listOf(
                                                    Color.White.copy(alpha = 0.35f),
                                                    Color.Transparent
                                                )
                                            ),
                                            CircleShape
                                        )
                                )
                                Icon(
                                    imageVector = Icons.Filled.Mic,
                                    contentDescription = if (isCancel) "取消录音" else "录音中",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .graphicsLayer {
                                            // Mic 本体脉冲：scale = 1 + ema × 0.06
                                            val s = 1f + displayEma * 0.06f
                                            scaleX = s
                                            scaleY = s
                                        }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            WaveformLine(ema = displayEma, timeSec = frameT)
                        }
                    }
                }
            }
        }
    }
}

/**
 * v11.9 改动 3：iOS 听写式连续波形线，替换原 5 根声波条与涟漪环。
 * 180dp 宽 Canvas，60fps 帧时钟驱动；3 个叠加正弦分量 × ema 包络 × 两端渐隐窗：
 * y(x) = 中线 + [sin(x·0.05 + t·4.2)·0.5 + sin(x·0.09 − t·3.1)·0.3 + sin(x·0.16 + t·5.7)·0.2]
 *        × (3 + ema × 26)dp × window(x)
 * window(x) 为两端 20% 宽度的 cos 渐隐窗；线粗 2.5dp 圆头白 95%。
 * 逐帧连续起伏，消除原 80ms RMS 步进跳变；静默兜底时 ema 已由上层注入极缓微呼吸。
 */
@Composable
private fun WaveformLine(ema: Float, timeSec: Float) {
    Canvas(
        modifier = Modifier
            .width(180.dp)
            .height(56.dp)
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val amp = 3f.dp.toPx() + ema * 26f.dp.toPx()
        val strokePx = 2.5f.dp.toPx()
        // 180dp 宽 ≈ 60 个采样点（每 3dp 一点）
        val steps = 60
        val widthDp = 180f
        val path = Path()
        for (i in 0..steps) {
            val x = w * i / steps
            val xDp = widthDp * i / steps
            val nx = i / steps.toFloat()
            // 两端 20% cos 渐隐窗：0~0.2 与 0.8~1.0 区间平滑收敛到 0
            val win = when {
                nx < 0.2f -> 0.5f - 0.5f * kotlin.math.cos((nx / 0.2f) * kotlin.math.PI.toFloat())
                nx > 0.8f -> 0.5f - 0.5f * kotlin.math.cos(((1f - nx) / 0.2f) * kotlin.math.PI.toFloat())
                else -> 1f
            }
            val t = timeSec
            val offset = kotlin.math.sin(xDp * 0.05f + t * 4.2f) * 0.5f +
                kotlin.math.sin(xDp * 0.09f - t * 3.1f) * 0.3f +
                kotlin.math.sin(xDp * 0.16f + t * 5.7f) * 0.2f
            val y = midY + offset * amp * win
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.95f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
    }
}

/** 已录秒数 → "0:08" 格式 */
private fun formatSeconds(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
