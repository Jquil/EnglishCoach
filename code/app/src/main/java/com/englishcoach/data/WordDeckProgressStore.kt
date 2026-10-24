package com.englishcoach.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 首页快捷跟读卡词进度（v11.11 需求 1c）：
 * - 只记当前词序号（words 列表的 0-based index），App 冷启动恢复到上次的词继续学习
 * - 切词后即写盘；崩溃/重装语义与现有 store 一致（读失败按 0 重建）
 */
@Serializable
data class WordDeckProgress(val index: Int = 0)

/** 词进度存储接口 */
interface WordDeckProgressStore {
    /** 上次停留的词序号（文件缺失/损坏返回 0） */
    suspend fun getIndex(): Int

    /** 切词后持久化当前序号（调用方保证已 coerce 到词表范围） */
    suspend fun setIndex(index: Int)
}

/** JSON 文件实现：filesDir/word_deck_progress.json（单对象，Mutex + Dispatchers.IO，与其他 Store 同做法） */
class JsonWordDeckProgressStore(context: Context) : WordDeckProgressStore {

    companion object {
        private const val TAG = "EC-WordDeckProgress"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    private val mutex = Mutex()
    private val file: File = File(context.filesDir, "word_deck_progress.json")

    override suspend fun getIndex(): Int {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (!file.exists()) 0
                    else json.decodeFromString(WordDeckProgress.serializer(), file.readText()).index
                } catch (e: Exception) {
                    Log.e(TAG, "word_deck_progress.json 读取失败（按 0 重建）: ${e.message}")
                    0
                }
            }
        }
    }

    override suspend fun setIndex(index: Int) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    file.writeText(json.encodeToString(WordDeckProgress.serializer(), WordDeckProgress(index)))
                } catch (e: Exception) {
                    Log.w(TAG, "写入词进度失败: ${e.message}")
                }
            }
        }
    }
}
