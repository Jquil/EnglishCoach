package com.englishcoach.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englishcoach.EnglishCoachApp
import com.englishcoach.core.model.NceMilestones
import com.englishcoach.ui.theme.CorrectGreen
import com.englishcoach.ui.theme.HyperBlue
import com.englishcoach.ui.theme.PageBg
import com.englishcoach.ui.theme.TextPrimary
import com.englishcoach.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 统计页 UI 状态。
 * 数据源：DailyStatsStore（按日跟读句数/通关次数）+ LevelProgressStore（通关/最高分）。
 */
data class StatsUiState(
    val streakDays: Int = 0,            // 连续打卡天数（每天有跟读/通关即打卡）
    val totalRepeatSentences: Int = 0,  // 累计跟读句数
    val totalCleared: Int = 0,          // 累计通关课数（LevelProgressStore 口径：去重）
    val weekCleared: Int = 0,           // 本周通关关卡数（周一为一周起点）
    val bestScore: Int = 0,             // 历史最高分（全部关卡 bestScore 最大值）
    val badgeRemaining: Int? = null,    // 距下一枚徽章还差 N 关（null = 276 枚已达成）
    val badgeNextThreshold: Int? = null // 下一枚徽章门槛（10/50/100/276）
)

/** 统计页 ViewModel */
class StatsViewModel(private val app: EnglishCoachApp) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val dailies = runCatching { app.dailyStatsStore.getAll() }.getOrDefault(emptyList())
            val progress = runCatching { app.levelProgressStore.getAll() }.getOrDefault(emptyMap())

            // 连续打卡：今天（没学则从昨天）往前数连续"有跟读或通关"的天数
            val activeDates = dailies
                .filter { it.repeatSentences > 0 || it.clearedLessons > 0 }
                .map { it.date }
                .toSet()
            var streak = 0
            var cursor = LocalDate.now()
            if (!activeDates.contains(cursor.toString())) cursor = cursor.minusDays(1)
            while (activeDates.contains(cursor.toString())) {
                streak++
                cursor = cursor.minusDays(1)
            }

            // 本周通关：周一为起点
            val monday = LocalDate.now().with(DayOfWeek.MONDAY).toString()
            val weekCleared = dailies.filter { it.date >= monday }.sumOf { it.clearedLessons }

            val totalCleared = progress.values.count { it.cleared }
            val bestScore = progress.values.maxOfOrNull { it.bestScore } ?: 0
            val nextBadge = NceMilestones.remainingToNext(totalCleared)

            _uiState.update {
                it.copy(
                    streakDays = streak,
                    totalRepeatSentences = dailies.sumOf { d -> d.repeatSentences },
                    totalCleared = totalCleared,
                    weekCleared = weekCleared,
                    bestScore = bestScore,
                    badgeRemaining = nextBadge?.second,
                    badgeNextThreshold = nextBadge?.first
                )
            }
        }
    }

    companion object {
        fun factory(app: EnglishCoachApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = StatsViewModel(app) as T
            }
    }
}

/**
 * 统计页（v11.1 按用户参考图重做）：
 * 「我的进度」行 + 四段式进度条（10/50/100/276，已达成绿/当前蓝+还差 N/未达成灰）
 * + 「徽章墙 · 共 4 档」（铜圆星/银井号/金勾/钻菱形，达成彩色、未达成灰线框）
 * + 紧凑数据条（连续打卡/累计句数/本周通关/最高分，不丢数据）+ 底部注脚。
 */
@Composable
fun StatsScreen() {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as EnglishCoachApp
    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.factory(app))
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(text = "统计", style = MaterialTheme.typography.displayLarge, color = TextPrimary)
        }
        // —— 我的进度：标题行 + 四段式进度条 ——
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "我的进度",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "已通关 ${state.totalCleared} 关",
                        fontSize = 12.5.sp,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val thresholds = NceMilestones.THRESHOLDS
                    thresholds.forEachIndexed { index, threshold ->
                        val prev = if (index == 0) 0 else thresholds[index - 1]
                        val achieved = state.totalCleared >= threshold
                        val current = !achieved && state.totalCleared >= prev
                        val remain = (threshold - state.totalCleared).coerceAtLeast(0)
                        Column(modifier = Modifier.weight(1f)) {
                            // 段条：已达成实心绿 / 当前段高亮蓝 / 未达成浅灰
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when {
                                            achieved -> CorrectGreen
                                            current -> HyperBlue
                                            else -> TextPrimary.copy(alpha = 0.08f)
                                        }
                                    )
                            )
                            Text(
                                text = when {
                                    current -> "${threshold} 关 · 还差 $remain"
                                    else -> "${threshold} 关"
                                },
                                fontSize = 10.sp,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    achieved -> CorrectGreen
                                    current -> HyperBlue
                                    else -> TextSecondary.copy(alpha = 0.7f)
                                },
                                maxLines = 1,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            if (achieved) {
                                Text(
                                    text = "✓",
                                    fontSize = 10.sp,
                                    color = CorrectGreen,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        // —— 徽章墙 · 共 4 档 ——
        item {
            Text(
                text = "徽章墙 · 共 ${NceMilestones.THRESHOLDS.size} 档",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
            )
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
            ) {
                NceMilestones.THRESHOLDS.forEachIndexed { index, threshold ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .padding(start = 72.dp)
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(TextPrimary.copy(alpha = 0.06f))
                        )
                    }
                    BadgeRow(tier = index, threshold = threshold, cleared = state.totalCleared)
                }
            }
        }
        // —— 紧凑数据条（原四格压缩保留，不丢数据） ——
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CompactStat("${state.streakDays}", "天", "连续打卡")
                    CompactStat("${state.totalRepeatSentences}", "句", "累计跟读")
                    CompactStat("${state.weekCleared}", "关", "本周通关")
                    CompactStat("${state.bestScore}", "分", "最高分")
                }
            }
        }
        // —— 底部注脚 ——
        item {
            Text(
                text = "徽章只在达成里程碑时出现，日常练习不打扰 · 图标几何风、随系统浅色",
                fontSize = 11.5.sp,
                color = TextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp)
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** 徽章行：左徽章图标 + 名称/状态 + 右侧关数 */
@Composable
private fun BadgeRow(tier: Int, threshold: Int, cleared: Int) {
    val achieved = cleared >= threshold
    val name = when (threshold) {
        10 -> "入门徽章"
        50 -> "坚持徽章"
        100 -> "百关徽章"
        else -> "通关徽章"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BadgeIcon(achieved = achieved, tier = tier)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = when {
                    achieved -> "已达成 · 连通 $threshold 关"
                    threshold == 276 -> "连通全部 276 关"
                    else -> "连通 $threshold 关 · 还差 ${threshold - cleared} 关"
                },
                fontSize = 12.5.sp,
                color = if (achieved) CorrectGreen else TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            text = "${threshold} 关",
            fontSize = 12.5.sp,
            color = TextSecondary.copy(alpha = 0.7f)
        )
    }
}

/**
 * 徽章图标（几何风）：tier 0=铜圆星 / 1=银井号 / 2=金勾 / 3=钻菱形。
 * 已达成 = 实心彩色；未达成 = 灰线框白底。
 */
@Composable
private fun BadgeIcon(achieved: Boolean, tier: Int) {
    val color = when (tier) {
        0 -> Color(0xFFD0863F) // 铜
        1 -> Color(0xFF98A2AC) // 银
        2 -> Color(0xFFE3A93E) // 金
        else -> Color(0xFF4FC3F7) // 钻
    }
    val line = Color(0xFFC9CDD2)      // 未达成线框灰
    val fg = if (achieved) Color.White else line
    Box(
        modifier = Modifier
            .size(44.dp)
            .then(
                if (achieved) {
                    Modifier.background(color, CircleShape)
                } else {
                    Modifier
                        .background(Color.White, CircleShape)
                        .border(1.5.dp, line, CircleShape)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        when (tier) {
            0 -> Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp)
            )
            1 -> Text(text = "#", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = fg)
            2 -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp)
            )
            else -> Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer { rotationZ = 45f }
                    .border(2.dp, fg, RoundedCornerShape(2.dp))
            )
        }
    }
}

/** 紧凑数据项：数字+单位 / 标签 */
@Composable
private fun CompactStat(value: String, unit: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = " $unit",
                fontSize = 10.5.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        Text(text = label, fontSize = 10.5.sp, color = TextSecondary, modifier = Modifier.padding(top = 1.dp))
    }
}
