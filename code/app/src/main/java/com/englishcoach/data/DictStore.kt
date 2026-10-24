package com.englishcoach.data

import android.content.Context
import android.util.Log
import com.englishcoach.core.KeyHolder
import com.englishcoach.llm.LlmClient
import com.englishcoach.llm.LlmMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 点词释义词典（PM v3 需求 4）：三级查询通道——
 * ① 内置离线迷你词典（assets/nce/dict.json，高频口语词，零网络零延迟）
 * ② 本地缓存（filesDir/dict_cache.json，LLM 查询结果落盘，同词二次点击零成本）
 * ③ LLM 单词查询（现有 LlmRouter，prompt 返回固定 JSON；失败返回 null，UI 优雅降级）
 */
class DictStore(
    private val context: Context,
    private val llm: LlmClient
) {

    companion object {
        private const val TAG = "EC-Dict"
        private const val ASSET_FILE = "nce/dict.json"
        private const val CACHE_FILE = "dict_cache.json"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    data class Entry(
        val word: String,
        val phonetic: String = "",
        val meaning: String = "",   // "词性. 中文释义"
        val example: String = "",
        val exampleZh: String = ""
    )

    private val mutex = Mutex()

    @Volatile
    private var builtinMap: Map<String, Entry>? = null

    @Volatile
    private var cacheMap: MutableMap<String, Entry> = mutableMapOf()

    private val cacheFile = java.io.File(context.filesDir, CACHE_FILE)

    private fun loadBuiltinLocked(): Map<String, Entry> {
        builtinMap?.let { return it }
        val loaded = try {
            val text = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString(ListSerializer(Entry.serializer()), text)
                .associateBy { it.word.trim().lowercase() }
        } catch (e: Exception) {
            Log.w(TAG, "内置词典读取失败（走 LLM 通道）: ${e.message}")
            emptyMap()
        }
        builtinMap = loaded
        return loaded
    }

    private fun loadCacheLocked(): MutableMap<String, Entry> {
        if (cacheMap.isNotEmpty()) return cacheMap
        cacheMap = try {
            if (cacheFile.exists()) {
                json.decodeFromString(
                    MapSerializer(String.serializer(), Entry.serializer()),
                    cacheFile.readText()
                ).toMutableMap()
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            Log.w(TAG, "词典缓存读取失败（重建）: ${e.message}")
            mutableMapOf()
        }
        return cacheMap
    }

    private fun writeCacheLocked(map: Map<String, Entry>) {
        try {
            cacheFile.writeText(
                json.encodeToString(MapSerializer(String.serializer(), Entry.serializer()), map)
            )
        } catch (e: Exception) {
            Log.w(TAG, "词典缓存写入失败: ${e.message}")
        }
    }

    /**
     * 查询一个词：内置 → 缓存 → LLM。查不到返回 null（UI 提示暂无释义）。
     * @param rawWord 句中原始 token（带标点），内部去标点归一化
     */
    suspend fun lookup(rawWord: String): Entry? = mutex.withLock {
        val word = rawWord.trim().trim('.', ',', '!', '?', ';', ':', '"', '\'', '。', '，', '！', '？')
            .lowercase()
        if (word.length < 2) return null
        withContext(Dispatchers.IO) {
            loadBuiltinLocked()[word]?.let { return@withContext it }
            loadCacheLocked()[word]?.let { return@withContext it }
            // LLM 兜底
            val entry = llmLookup(word) ?: return@withContext null
            cacheMap[word] = entry
            writeCacheLocked(cacheMap)
            entry
        }
    }

    /** LLM 查询：prompt 强约束 JSON 输出，解析失败返回 null */
    private suspend fun llmLookup(word: String): Entry? {
        return try {
            val system = "你是英汉词典。对给出的英语单词返回严格 JSON（不要任何其他文字、不要代码块围栏）：" +
                "{\"word\":\"原词\",\"phonetic\":\"美式音标（无则空串）\",\"meaning\":\"词性缩写. 简明中文释义（1~2个）\"," +
                "\"example\":\"一个贴近日常的简短英文例句\",\"exampleZh\":\"例句的中文翻译\"}"
            val content = llm.chat(history = listOf(LlmMessage("user", "单词：$word")), system = system)
            val cleaned = content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()
            val entry = json.decodeFromString(Entry.serializer(), cleaned)
            entry.copy(word = word)
        } catch (e: Exception) {
            Log.w(TAG, "LLM 词典查询失败: ${e.message}")
            null
        }
    }
}
