package com.englishcoach.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 每日轻量学习记录（第二轮真机反馈需求 3：统计页重做）：
 * - 每天有跟读/通关即视为"打卡"（与 StatsStore 的 3 分钟保底口径不同，本表只记事实）
 * - 记录跟读句数（每句 ASR Final +1）与当日通关次数（整课完成 +1）
 */
@kotlinx.serialization.Serializable
data class DailyStats(
    val date: String,               // "2026-10-23"（本地时区日期字符串）
    val repeatSentences: Int = 0,   // 当日累计跟读句数
    val clearedLessons: Int = 0     // 当日累计通关次数（同一关重练也累计）
)

/** 每日统计存储接口 */
interface DailyStatsStore {
    /** 当日跟读句数 +count（默认 1） */
    suspend fun addRepeatSentences(count: Int = 1)

    /** 当日通关次数 +count（默认 1） */
    suspend fun addClearedLesson(count: Int = 1)

    /** 全部记录，按日期升序 */
    suspend fun getAll(): List<DailyStats>
}

/** JSON 文件实现：filesDir/daily_stats.json（一个数组，Mutex + Dispatchers.IO，与其他 Store 同做法） */
class JsonDailyStatsStore(context: Context) : DailyStatsStore {

    companion object {
        private const val TAG = "EC-DailyStats"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    private val mutex = Mutex()
    private val file: File = File(context.filesDir, "daily_stats.json")

    override suspend fun addRepeatSentences(count: Int) {
        mutateToday { it.copy(repeatSentences = it.repeatSentences + count.coerceAtLeast(0)) }
    }

    override suspend fun addClearedLesson(count: Int) {
        mutateToday { it.copy(clearedLessons = it.clearedLessons + count.coerceAtLeast(0)) }
    }

    override suspend fun getAll(): List<DailyStats> {
        return withContext(Dispatchers.IO) {
            mutex.withLock { readAllLocked().sortedBy { it.date } }
        }
    }

    /** 当日记录原地更新一条（不存在则新建），写入由调用方 map 完成 */
    private suspend fun mutateToday(transform: (DailyStats) -> DailyStats) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val records = readAllLocked().toMutableList()
                val date = todayString()
                val index = records.indexOfFirst { it.date == date }
                val base = if (index >= 0) records[index] else DailyStats(date = date)
                val updated = transform(base)
                if (index >= 0) records[index] = updated else records.add(updated)
                writeAllLocked(records)
            }
        }
    }

    private fun readAllLocked(): List<DailyStats> {
        return try {
            if (!file.exists()) emptyList()
            else json.decodeFromString(ListSerializer(DailyStats.serializer()), file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "daily_stats.json 读取失败（按空记录重建）: ${e.message}")
            emptyList()
        }
    }

    private fun writeAllLocked(records: List<DailyStats>) {
        try {
            file.writeText(json.encodeToString(ListSerializer(DailyStats.serializer()), records))
        } catch (e: Exception) {
            Log.w(TAG, "写入每日统计失败: ${e.message}")
        }
    }
}
