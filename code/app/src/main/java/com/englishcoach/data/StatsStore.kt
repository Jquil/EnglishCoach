package com.englishcoach.data

import android.content.Context
import android.util.Log
import com.englishcoach.core.model.DailyRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** 每日统计存储接口 */
interface StatsStore {
    /** 今日记录（冷流：收集时读一次当前值） */
    val today: Flow<DailyRecord?>

    /** 累加当日记录：开口秒数 +（可选）轮数 +1；打卡状态按 3 分钟保底线自动重算 */
    suspend fun addSpeakingSeconds(seconds: Int, newTurn: Boolean)

    suspend fun getRecord(date: String): DailyRecord?

    /** 全部记录（打卡日历 / 连续天数计算用），按日期升序 */
    suspend fun getAllRecords(): List<DailyRecord>
}

/** JSON 文件实现：filesDir/stats.json（一个数组， Mutex 保证并发安全） */
class JsonStatsStore(context: Context) : StatsStore {

    companion object {
        private const val TAG = "EC-StatsStore"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    private val mutex = Mutex()
    private val file: File = File(context.filesDir, "stats.json")

    override val today: Flow<DailyRecord?>
        get() = flow { emit(getRecord(todayString())) }

    override suspend fun addSpeakingSeconds(seconds: Int, newTurn: Boolean) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val records = readAllLocked().toMutableList()
                val date = todayString()
                val index = records.indexOfFirst { it.date == date }
                val base = if (index >= 0) records[index] else DailyRecord(date = date)
                val updated = base.copy(
                    speakingSeconds = base.speakingSeconds + seconds.coerceAtLeast(0),
                    turnCount = base.turnCount + if (newTurn) 1 else 0,
                    checkedIn = base.speakingSeconds + seconds.coerceAtLeast(0) >=
                        DailyRecord.CHECK_IN_THRESHOLD_SECONDS
                )
                if (index >= 0) records[index] = updated else records.add(updated)
                writeAllLocked(records)
            }
        }
    }

    override suspend fun getRecord(date: String): DailyRecord? {
        return withContext(Dispatchers.IO) {
            mutex.withLock { readAllLocked().find { it.date == date } }
        }
    }

    override suspend fun getAllRecords(): List<DailyRecord> {
        return withContext(Dispatchers.IO) {
            mutex.withLock { readAllLocked().sortedBy { it.date } }
        }
    }

    /** 以下读取/写入都假定已持有 mutex（Lock 后缀） */
    private fun readAllLocked(): List<DailyRecord> {
        return try {
            if (!file.exists()) emptyList()
            else json.decodeFromString(ListSerializer(DailyRecord.serializer()), file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "读取统计失败: ${e.message}")
            emptyList()
        }
    }

    private fun writeAllLocked(records: List<DailyRecord>) {
        try {
            file.writeText(json.encodeToString(ListSerializer(DailyRecord.serializer()), records))
        } catch (e: Exception) {
            Log.w(TAG, "写入统计失败: ${e.message}")
        }
    }
}

/** 本地时区日期字符串（打卡口径：以本地日历日为准，不跨时区换算） */
fun todayString(): String = LocalDate.now().toString()
