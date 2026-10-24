package com.englishcoach.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 音节分段数据（需求 v5：单词分段点击发音）。
 * 来源：tools/gen_syllables.py 离线批量生成 → assets/nce/syllables.json，运行时零成本零延迟。
 * 生成端硬校验（拼接还原 == 原词），运行时不再校验。
 */
@Serializable
data class Syllables(
    val s: List<String>,  // 音节数组（全小写），如 ["fam","i","ly"]
    val st: Int = 0       // 主重音音节下标（0-based）
)

/**
 * 音节查询加载层（仿 DictStore：懒加载 + 内存缓存，启动零开销）。
 * 文件缺失/损坏 → 返回空表（全部词走 UI 降级：整词发音），不阻塞功能。
 */
class SyllableStore(private val context: Context) {

    companion object {
        private const val TAG = "EC-Syllable"
        private const val ASSET_FILE = "nce/syllables.json"
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val mutex = Mutex()

    @Volatile
    private var cache: Map<String, Syllables>? = null

    /**
     * 查一个词的音节分段；未命中返回 null（UI 降级整词发音）。
     * 词按全小写查询（大小写不敏感）。
     */
    suspend fun lookup(word: String): Syllables? {
        val key = word.trim().lowercase()
        if (key.isEmpty()) return null
        val map = getMap() ?: return null
        return map[key]
    }

    private suspend fun getMap(): Map<String, Syllables>? {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                cache?.let { return@withLock it }
                val loaded = loadLocked()
                cache = loaded
                loaded
            }
        }
    }

    private fun loadLocked(): Map<String, Syllables> {
        return try {
            val text = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString(
                MapSerializer(String.serializer(), Syllables.serializer()),
                text
            )
        } catch (e: Exception) {
            // 文件尚未生成（首次交付前）或损坏：全量降级，只记日志
            Log.w(TAG, "syllables.json 加载失败（全量降级整词发音）: ${e.message}")
            emptyMap()
        }
    }
}
