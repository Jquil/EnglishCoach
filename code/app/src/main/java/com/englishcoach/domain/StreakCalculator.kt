package com.englishcoach.domain

import com.englishcoach.core.model.StreakStatus
import com.englishcoach.data.SettingsStore
import com.englishcoach.data.StatsStore
import com.englishcoach.data.todayString
import java.time.LocalDate
/**
 * 打卡逻辑：
 * - 3 分钟保底：当天开口 ≥180 秒即打卡成功（判定在 StatsStore 写入时已按阈值算好 checkedIn）
 * - 连续天数：从今天（未打卡则从昨天）往前数连续 checkedIn 的天数
 * - 断签召回：昨天断签且今天还没打卡 → 只提醒一次（日期记录在 SettingsStore）
 */
class StreakCalculator(
    private val stats: StatsStore,
    private val settings: SettingsStore
) {
    /** 判定打卡 + 算连续天数（每次打开 App / 首页刷新时调用） */
    suspend fun evaluateToday(): StreakStatus {
        val records = stats.getAllRecords()
        val checkedDates = records.filter { it.checkedIn }.map { it.date }.toSet()

        val todayStr = todayString()
        val checkedToday = checkedDates.contains(todayStr)

        // 今天还没打卡时，火苗先算到昨天（不断签，鼓励今天继续）
        var streak = 0
        var cursor = LocalDate.now()
        if (!checkedToday) cursor = cursor.minusDays(1)
        while (checkedDates.contains(cursor.toString())) {
            streak++
            cursor = cursor.minusDays(1)
        }

        return StreakStatus(
            currentStreak = streak,
            bestStreak = bestStreak(checkedDates),
            missedYesterday = isMissed(records, LocalDate.now().minusDays(1).toString())
        )
    }

    /**
     * 断签次日温和召回：只发一次。
     * 满足全部条件才返回 true，并当场记下"今天已提醒"：
     * 1) 今天还没提醒过  2) 昨天确实断签（且有更早的历史，新用户第一天不算断签）  3) 今天还没打卡
     */
    suspend fun shouldSendRecallNotification(): Boolean {
        val todayStr = todayString()
        if (settings.getRecallNotifiedDate() == todayStr) return false

        val records = stats.getAllRecords()
        val yesterdayStr = LocalDate.now().minusDays(1).toString()
        if (!isMissed(records, yesterdayStr)) return false
        if (records.any { it.date == todayStr && it.checkedIn }) return false

        settings.setRecallNotifiedDate(todayStr)
        return true
    }

    /** 昨天是否断签：那天没打卡，且在那之前至少有过一次记录（避免新用户误判） */
    private fun isMissed(records: List<com.englishcoach.core.model.DailyRecord>, date: String): Boolean {
        val hasHistoryBefore = records.any { it.date < date }
        val checked = records.any { it.date == date && it.checkedIn }
        return hasHistoryBefore && !checked
    }

    /** 历史最佳连续天数 */
    private fun bestStreak(checkedDates: Set<String>): Int {
        if (checkedDates.isEmpty()) return 0
        val sorted = checkedDates.sorted()
        var best = 1
        var run = 1
        var previous = LocalDate.parse(sorted.first())
        for (i in 1 until sorted.size) {
            val current = LocalDate.parse(sorted[i])
            run = if (current == previous.plusDays(1)) run + 1 else 1
            if (run > best) best = run
            previous = current
        }
        return best
    }
}
