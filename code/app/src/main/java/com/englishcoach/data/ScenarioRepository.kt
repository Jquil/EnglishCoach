package com.englishcoach.data

import android.content.Context
import android.util.Log
import com.englishcoach.core.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 场景仓库接口 */
interface ScenarioRepository {
    suspend fun loadAll(): List<Scenario>
    suspend fun getById(id: String): Scenario?
}

/** 从 assets/scenarios.json 加载（加场景只改 JSON，不改代码） */
class AssetScenarioRepository(private val context: Context) : ScenarioRepository {

    companion object {
        private const val TAG = "EC-ScenarioRepo"
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val mutex = Mutex()
    private var cache: List<Scenario>? = null

    override suspend fun loadAll(): List<Scenario> {
        return mutex.withLock {
            cache ?: loadFromAssetsLocked().also { cache = it }
        }
    }

    override suspend fun getById(id: String): Scenario? {
        return loadAll().find { it.id == id }
    }

    private suspend fun loadFromAssetsLocked(): List<Scenario> {
        return withContext(Dispatchers.IO) {
            try {
                val text = context.assets.open("scenarios.json").bufferedReader().use { it.readText() }
                json.decodeFromString(ListSerializer(Scenario.serializer()), text)
            } catch (e: Exception) {
                Log.e(TAG, "加载 scenarios.json 失败: ${e.message}")
                emptyList()
            }
        }
    }
}
