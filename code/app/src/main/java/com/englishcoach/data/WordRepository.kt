package com.englishcoach.data

import android.content.Context
import android.util.Log
import com.englishcoach.core.model.WordItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 单词仓库接口（v1 占位数据源：内置高频场景词表；P1 生词本落地后可加实现替换） */
interface WordRepository {
    suspend fun loadAll(): List<WordItem>
}

/** 从 assets/words.json 加载（做法与 ScenarioRepository 一致：IO 线程 + 缓存） */
class AssetWordRepository(private val context: Context) : WordRepository {

    companion object {
        private const val TAG = "EC-WordRepo"
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val mutex = Mutex()
    private var cache: List<WordItem>? = null

    override suspend fun loadAll(): List<WordItem> {
        return mutex.withLock {
            cache ?: loadFromAssetsLocked().also { cache = it }
        }
    }

    private suspend fun loadFromAssetsLocked(): List<WordItem> {
        return withContext(Dispatchers.IO) {
            try {
                val text = context.assets.open("words.json").bufferedReader().use { it.readText() }
                json.decodeFromString(ListSerializer(WordItem.serializer()), text)
            } catch (e: Exception) {
                Log.e(TAG, "加载 words.json 失败: ${e.message}")
                emptyList()
            }
        }
    }
}
