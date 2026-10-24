package com.englishcoach.data

import android.content.Context
import android.util.Log
import com.englishcoach.core.model.NotableWord
import com.englishcoach.core.model.VocabItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** 生词本存储接口（PM v2 需求2 最小可用版：对话收藏 + 三档间隔复习） */
interface VocabStore {
    /** 全部生词（按加入时间倒序由调用方自行排序） */
    suspend fun getAll(): List<VocabItem>

    /** 收进单词本：同词去重（忽略大小写），实际新增返回 true、重复返回 false */
    suspend fun add(word: NotableWord, sourceScenarioId: String): Boolean

    /** 移出生词本（v11.12）：按忽略大小写匹配删除（与 add 去重口径一致）；删除成功返回 true，未找到返回 false */
    suspend fun remove(word: String): Boolean

    /** 是否已在本（忽略大小写） */
    suspend fun contains(word: String): Boolean

    /** 完成一次复习：reviewStage 推进（1→2→3 毕业），记录复习日期；无惩罚不清除 */
    suspend fun markReviewed(word: String)

    /** 今日到期词（reviewStage < 3 且 dueDay <= 今天；到期未复习顺延，不惩罚） */
    suspend fun dueItems(todayEpochDay: Long): List<VocabItem>
}

/** JSON 文件实现：filesDir/vocab.json（一个数组，Mutex + Dispatchers.IO，与 LevelProgressStore 同做法） */
class JsonVocabStore(context: Context) : VocabStore {

    companion object {
        private const val TAG = "EC-VocabStore"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    private val mutex = Mutex()
    private val file: File = File(context.filesDir, "vocab.json")

    override suspend fun getAll(): List<VocabItem> {
        return withContext(Dispatchers.IO) {
            mutex.withLock { readAllLocked() }
        }
    }

    override suspend fun add(word: NotableWord, sourceScenarioId: String): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readAllLocked().toMutableList()
                val exists = all.any { it.word.equals(word.word.trim(), ignoreCase = true) }
                if (exists) {
                    false
                } else {
                    all.add(
                        VocabItem(
                            word = word.word.trim(),
                            phonetic = word.phonetic.trim(),
                            meaning = word.meaning.trim(),
                            example = word.example.trim(),
                            sourceScenarioId = sourceScenarioId,
                            addedAtEpochDay = LocalDate.now().toEpochDay()
                        )
                    )
                    writeAllLocked(all)
                    true
                }
            }
        }
    }

    override suspend fun remove(word: String): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readAllLocked()
                val exists = all.any { it.word.equals(word.trim(), ignoreCase = true) }
                if (exists) {
                    writeAllLocked(all.filterNot { it.word.equals(word.trim(), ignoreCase = true) })
                    true
                } else {
                    false
                }
            }
        }
    }

    override suspend fun contains(word: String): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock { readAllLocked().any { it.word.equals(word.trim(), ignoreCase = true) } }
        }
    }

    override suspend fun markReviewed(word: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readAllLocked().toMutableList()
                val index = all.indexOfFirst { it.word.equals(word.trim(), ignoreCase = true) }
                if (index >= 0) {
                    val base = all[index]
                    all[index] = base.copy(
                        reviewStage = (base.reviewStage + 1).coerceAtMost(VocabItem.REVIEW_INTERVAL_DAYS.size),
                        reviewedEpochDay = LocalDate.now().toEpochDay()
                    )
                    writeAllLocked(all)
                }
            }
        }
    }

    override suspend fun dueItems(todayEpochDay: Long): List<VocabItem> {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                readAllLocked().filter { !VocabItem.isGraduated(it) && VocabItem.dueDay(it) <= todayEpochDay }
            }
        }
    }

    /** 损坏防护：反序列化失败只记日志并返回空列表（生词本读作空，用户可重新收藏），坏文件不会让每次读都异常 */
    private fun readAllLocked(): List<VocabItem> {
        return try {
            if (!file.exists()) emptyList()
            else json.decodeFromString(ListSerializer(VocabItem.serializer()), file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "vocab.json 读取失败（按空生词本重建）: ${e.message}")
            emptyList()
        }
    }

    private fun writeAllLocked(items: List<VocabItem>) {
        try {
            file.writeText(json.encodeToString(ListSerializer(VocabItem.serializer()), items))
        } catch (e: Exception) {
            Log.w(TAG, "写入生词本失败: ${e.message}")
        }
    }
}
