package com.englishcoach.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val BAR_COUNT = 5

/**
 * 录音中声浪指示器（微信发语音效果，HyperOS 均衡器式跳动条）。
 * 5 根竖条以错相位循环伸缩（InfiniteTransition，非音量驱动——历史链路无实时音量源；
 * v11.5 起 CloudAsrEngine.rmsLevel 已可提供 RMS，此组件保持纯循环不受影响）。
 * 随组合进入/离开自动启停，不阻塞任何事件流；调用方在"按住说话"按钮的录音态内替换文字使用。
 */
@Composable
fun WaveBarsIndicator(
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "waveBars")
    // 每根条独立相位（StartOffset 错开），高度在 35%~100% 间往返
    val bars = (0 until BAR_COUNT).map { i ->
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 110)
            ),
            label = "bar$i"
        )
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        bars.forEachIndexed { index, bar ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 1.5.dp)
                    .width(3.dp)
                    .height((5 + 9 * bar.value).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}
