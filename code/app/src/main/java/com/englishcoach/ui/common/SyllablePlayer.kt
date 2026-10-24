package com.englishcoach.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.englishcoach.EnglishCoachApp
import com.englishcoach.data.Syllables
import com.englishcoach.ui.theme.HyperBlue
import com.englishcoach.ui.theme.TextPrimary
import com.englishcoach.ui.theme.TextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 分段发音播放器（需求 v5：单词分段点击发音，词卡弹层与单词跟读堆叠卡共用）。
 * - 复用现有 TTS 链路：synthesize（磁盘缓存 key=text|voice|speed，音节文本极短命中率极高）
 *   + 在途去重 + ratePercent 调速——本类只做编排，不碰网络细节
 * - 点段 → 只发该段音（按钮级 loading = 该段高亮，连续点不同段直接切播不排队）
 * - 整词慢读：speed 0.65 = ratePercent -35（语速映射 (100+rate)/100）
 * - 分段连读：依次播各音节、间隔 150ms，播完自动停；连读中再点 = 中止
 */
class SyllablePlayer(
    private val app: EnglishCoachApp,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    /** 正在播放/加载的音节下标（-1 = 空闲）；点别的段直接覆盖 = 切播不排队 */
    var activeSegment by mutableStateOf(-1)
        private set

    /** 分段连读进行中 */
    var seqPlaying by mutableStateOf(false)
        private set

    private var seqJob: Job? = null
    private var generation = 0 // 播放代际：新请求使旧请求的收尾失效（切播/中止防串）

    /** 点某段：只发该段音 */
    fun playSegment(syllable: String, index: Int) {
        stopSequence()
        generation++
        val gen = generation
        scope.launch {
            activeSegment = index
            runCatching { app.ttsClient.synthesize(syllable) }.getOrNull()?.let {
                app.audioPlayer.play(it) {}
            }
            if (gen == generation) activeSegment = -1
        }
    }

    /** 整词慢读：speed 0.65 */
    fun playWholeSlow(word: String) {
        stopSequence()
        generation++
        scope.launch {
            runCatching { app.ttsClient.synthesize(word, ratePercent = -35) }.getOrNull()?.let {
                app.audioPlayer.play(it) {}
            }
        }
    }

    /** 整词常速 */
    fun playWhole(word: String) {
        stopSequence()
        generation++
        scope.launch {
            runCatching { app.ttsClient.synthesize(word) }.getOrNull()?.let {
                app.audioPlayer.play(it) {}
            }
        }
    }

    /** 分段连读：依次播各音节、间隔 150ms、播完自动停；连读中再点 = 中止 */
    fun playSequence(syllables: List<String>) {
        if (seqPlaying) {
            stopSequence()
            return
        }
        generation++
        val gen = generation
        seqJob = scope.launch {
            seqPlaying = true
            for (i in syllables.indices) {
                if (!seqPlaying || gen != generation) break
                activeSegment = i
                runCatching { app.ttsClient.synthesize(syllables[i]) }.getOrNull()?.let {
                    app.audioPlayer.play(it) {}
                }
                delay(150)
            }
            if (gen == generation) {
                activeSegment = -1
                seqPlaying = false
            }
        }
    }

    /** 中止连读 + 复位（离开弹层/卡片时调用） */
    fun stopAll() {
        stopSequence()
        generation++
        activeSegment = -1
    }

    private fun stopSequence() {
        seqPlaying = false
        seqJob?.cancel()
        seqJob = null
    }
}

/** remember 便捷入口：持 App 容器与调用方作用域 */
@Composable
fun rememberSyllablePlayer(): SyllablePlayer {
    val app = LocalContext.current.applicationContext as EnglishCoachApp
    val scope = rememberCoroutineScope()
    val player = remember { SyllablePlayer(app, scope) }
    DisposableEffect(Unit) {
        onDispose { player.stopAll() } // 弹层收起/卡片销毁：中止连读并复位
    }
    return player
}

/**
 * 分段发音区（需求 v5 交互规格，词卡弹层与堆叠卡共用）：
 * - 多音节词（查到数据）：音节行（4~6dp 间隔、重音加粗主色）+【整词慢读】【分段连读】两个 pill
 * - 单音节词（s.size<=1）：整词渲染 + 常速/慢读两 pill，无分段区
 * - 词表外词（lookup null）：灰字"分段暂不支持" + 仅整词慢读 pill（降级，不运行时补拆）
 * 查询异步进行：未返回前渲染整词占位（不闪跳）。
 */
@Composable
fun SyllableBar(
    word: String,
    player: SyllablePlayer,
    modifier: Modifier = Modifier,
    segmentFontSize: Int = 24
) {
    val app = LocalContext.current.applicationContext as EnglishCoachApp
    var syllables by remember(word) { mutableStateOf<Syllables?>(null) }
    var lookedUp by remember(word) { mutableStateOf(false) }
    val normalWord = word.trim().lowercase()

    LaunchedEffect(word) {
        lookedUp = false
        syllables = runCatching { app.syllableStore.lookup(normalWord) }.getOrNull()
        lookedUp = true
    }

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            // 查询未返回：整词占位（不闪跳）
            !lookedUp -> {
                Text(
                    text = normalWord,
                    fontSize = segmentFontSize.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // 词表外：降级（宁缺勿错，不运行时补拆）
            lookedUp && syllables == null -> {
                Text(
                    text = "分段暂不支持",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(text = "整词慢读", filled = false) { player.playWholeSlow(normalWord) }
                }
            }

            // 单音节：整词渲染 + 常速/慢读两 pill，无分段区
            lookedUp && syllables != null && syllables!!.s.size <= 1 -> {
                PillButton(text = "整词慢读", filled = false) { player.playWholeSlow(normalWord) }
            }

            // 多音节：分段行 + 两个 pill
            lookedUp && syllables != null -> {
                val syl = syllables!!
                Row(verticalAlignment = Alignment.CenterVertically) {
                    syl.s.forEachIndexed { i, seg ->
                        val stressed = i == syl.st
                        val active = player.activeSegment == i
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) HyperBlue.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { player.playSegment(seg, i) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            if (active) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = HyperBlue
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                            Text(
                                text = seg,
                                fontSize = segmentFontSize.sp,
                                fontWeight = if (stressed) FontWeight.Bold else FontWeight.Normal,
                                color = if (stressed) HyperBlue else TextPrimary
                            )
                        }
                        if (i < syl.s.size - 1) {
                            Spacer(modifier = Modifier.width(5.dp)) // 音节间细微间隔
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(text = "整词慢读", filled = false) { player.playWholeSlow(normalWord) }
                    PillButton(
                        text = if (player.seqPlaying) "停止连读" else "分段连读",
                        filled = player.seqPlaying
                    ) { player.playSequence(syl.s) }
                }
            }
        }
    }
}

/** 轻量 pill 按钮（整词慢读/分段连读） */
@Composable
private fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (filled) HyperBlue else HyperBlue.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (filled) Color.White else HyperBlue
        )
    }
}
