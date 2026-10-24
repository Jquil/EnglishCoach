package com.englishcoach.data

import android.content.Context
import android.util.Log
import com.englishcoach.core.model.LevelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** 关卡进度存储接口 */
interface LevelProgressStore {
    suspend fun getState(scenarioId: String): LevelState

    /**
     * 记录一次会话的关卡进度：maxRounds 取历史最大（中断也算，PM 口径），
     * cleared 由调用方判定（触发自动小结才置 true，中断/手动提前退出不清关）。
     */
    suspend fun recordProgress(scenarioId: String, rounds: Int, cleared: Boolean)

    /**
     * NCE 关成绩单记录（PM v2）：整课跟读完成即调用——cleared=true（通关不设分数门槛），
     * bestScore 只升不降（刷新纪录口径）；levelKey = "nce_1".."nce_276"。
     */
    suspend fun recordScore(levelKey: String, score: Int)

    /** 累计通关课数（PM v3 2b 里程碑徽章：10/50/100/276 各一枚） */
    suspend fun countCleared(): Int

    suspend fun getAll(): Map<String, LevelState>
}

/** JSON 文件实现：filesDir/levels.json（一个数组，Mutex + Dispatchers.IO，与 ConversationStore 同做法） */
class JsonLevelProgressStore(context: Context) : LevelProgressStore {

    companion object {
        private const val TAG = "EC-LevelStore"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    private val mutex = Mutex()
    private val file: File = File(context.filesDir, "levels.json")

    override suspend fun getState(scenarioId: String): LevelState {
        return getAll()[scenarioId] ?: LevelState(scenarioId = scenarioId)
    }

    override suspend fun recordProgress(scenarioId: String, rounds: Int, cleared: Boolean) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readAllLocked().toMutableList()
                val index = all.indexOfFirst { it.scenarioId == scenarioId }
                val base = if (index >= 0) all[index] else LevelState(scenarioId = scenarioId)
                val updated = base.copy(
                    maxRounds = maxOf(base.maxRounds, rounds),
                    cleared = base.cleared || cleared // 已通关永不清除（可重练，不降级）
                )
                if (index >= 0) all[index] = updated else all.add(updated)
                writeAllLocked(all)
            }
        }
    }

    override suspend fun recordScore(levelKey: String, score: Int) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readAllLocked().toMutableList()
                val index = all.indexOfFirst { it.scenarioId == levelKey }
                val base = if (index >= 0) all[index] else LevelState(scenarioId = levelKey)
                val updated = base.copy(
                    cleared = true, // 整课跟读完成即通关（分数只做评价，不做通行证）
                    bestScore = if (score > base.bestScore) score else base.bestScore // 只升不降
                )
                if (index >= 0) all[index] = updated else all.add(updated)
                writeAllLocked(all)
            }
        }
    }

    override suspend fun countCleared(): Int {
        return withContext(Dispatchers.IO) {
            mutex.withLock { readAllLocked().count { it.cleared } }
        }
    }

    override suspend fun getAll(): Map<String, LevelState> {
        return withContext(Dispatchers.IO) {
            mutex.withLock { readAllLocked().associateBy { it.scenarioId } }
        }
    }

    /** 损坏防护：反序列化失败只记日志并返回空列表（= 空 map，随后按需重建），坏文件不会让每次读都异常 */
    private fun readAllLocked(): List<LevelState> {
        return try {
            if (!file.exists()) emptyList()
            else json.decodeFromString(ListSerializer(LevelState.serializer()), file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "levels.json 读取失败（按空进度重建）: ${e.message}")
            emptyList()
        }
    }

    private fun writeAllLocked(states: List<LevelState>) {
        try {
            file.writeText(json.encodeToString(ListSerializer(LevelState.serializer()), states))
        } catch (e: Exception) {
            Log.w(TAG, "写入关卡进度失败: ${e.message}")
        }
    }
}
