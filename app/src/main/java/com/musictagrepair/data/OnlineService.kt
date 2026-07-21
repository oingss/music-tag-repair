package com.musictagrepair.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 在线搜索服务（网易云 + 酷狗）
 */
class OnlineService(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"
    }

    /**
     * 搜索网易云音乐
     */
    suspend fun searchNetease(keyword: String, page: Int = 1, limit: Int = 10): List<OnlineMusicInfo> = withContext(Dispatchers.IO) {
        try {
            val data = mapOf(
                "keyword" to keyword,
                "needCorrect" to "1",
                "channel" to "typing",
                "offset" to (limit * (page - 1)).toString(),
                "scene" to "normal",
                "total" to if (page == 1) "true" else "false",
                "limit" to limit.toString(),
            )

            val formData = NeteaseCrypto.eapi("/api/search/song/list/page", data)

            val body = FormBody.Builder().apply {
                formData.forEach { (k, v) -> add(k, v) }
            }.build()

            val request = Request.Builder()
                .url("http://interface.music.163.com/eapi/batch")
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com/")
                .header("Origin", "https://music.163.com")
                .post(body)
                .build()

            val resp = client.newCall(request).execute()
            val respBody = resp.body?.string() ?: return@withContext emptyList()
            val root = json.parseToJsonElement(respBody).jsonObject
            if (root["code"]?.intOrNull() != 200) return@withContext emptyList()

            val resources = root["data"]?.jsonObject?.get("resources")?.jsonArray ?: return@withContext emptyList()

            resources.mapNotNull { r ->
                val baseInfo = r.jsonObject["baseInfo"]?.jsonObject ?: return@mapNotNull null
                val song = baseInfo["simpleSongData"]?.jsonObject ?: return@mapNotNull null
                val album = song["al"]?.jsonObject

                var coverUrl = album?.get("picUrl")?.stringOrNull()
                if (coverUrl != null) coverUrl = "$coverUrl?param=500y500"

                val dt = song["dt"]?.longOrNull() ?: 0L
                val interval = if (dt > 0) formatInterval(dt) else null

                val singers = song["ar"]?.jsonArray?.mapNotNull { ar ->
                    ar.jsonObject["name"]?.stringOrNull()?.takeIf { it.isNotBlank() }
                }?.joinToString("、").orEmpty()

                OnlineMusicInfo(
                    id = song["id"]?.stringOrNull().orEmpty(),
                    name = song["name"]?.stringOrNull().orEmpty(),
                    singer = singers,
                    album = album?.get("name")?.stringOrNull().orEmpty(),
                    interval = interval,
                    coverUrl = coverUrl,
                    sourceId = "wy",
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 搜索酷狗音乐
     */
    suspend fun searchKuGou(keyword: String, page: Int = 1, limit: Int = 10): List<OnlineMusicInfo> = withContext(Dispatchers.IO) {
        try {
            val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=${urlEncode(keyword)}&page=$page&pagesize=$limit&showtype=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .get()
                .build()

            val resp = client.newCall(request).execute()
            val respBody = resp.body?.string() ?: return@withContext emptyList()
            val root = json.parseToJsonElement(respBody).jsonObject
            if (root["status"]?.intOrNull() != 1) return@withContext emptyList()

            val info = root["data"]?.jsonObject?.get("info")?.jsonArray ?: return@withContext emptyList()

            info.mapNotNull { s ->
                val obj = s.jsonObject
                val duration = obj["duration"]?.longOrNull() ?: 0L
                val interval = if (duration > 0) formatInterval(duration * 1000) else null

                OnlineMusicInfo(
                    id = obj["hash"]?.stringOrNull().orEmpty(),
                    name = obj["filename"]?.stringOrNull().orEmpty(),
                    singer = obj["singername"]?.stringOrNull().orEmpty(),
                    album = obj["album_name"]?.stringOrNull().orEmpty(),
                    interval = interval,
                    sourceId = "kg",
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 获取网易云歌词
     */
    suspend fun getNeteaseLyrics(musicId: String): Map<String, String?>? = withContext(Dispatchers.IO) {
        try {
            val data = mapOf(
                "id" to musicId,
                "cp" to false,
                "tv" to 0, "lv" to 0, "rv" to 0, "kv" to 0, "yv" to 0, "ytv" to 0, "yrv" to 0,
            )

            val formData = NeteaseCrypto.eapi("/api/song/lyric/v1", data)

            val body = FormBody.Builder().apply {
                formData.forEach { (k, v) -> add(k, v) }
            }.build()

            val request = Request.Builder()
                .url("http://interface.music.163.com/eapi/batch")
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com/")
                .header("Origin", "https://music.163.com")
                .post(body)
                .build()

            val resp = client.newCall(request).execute()
            val respBody = resp.body?.string() ?: return@withContext null
            val root = json.parseToJsonElement(respBody).jsonObject
            if (root["code"]?.intOrNull() != 200) return@withContext null

            mapOf(
                "lyric" to root["lrc"]?.jsonObject?.get("lyric")?.stringOrNull(),
                "tlyric" to root["tlyric"]?.jsonObject?.get("lyric")?.stringOrNull(),
                "rlyric" to root["romalrc"]?.jsonObject?.get("lyric")?.stringOrNull(),
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 获取网易云封面
     */
    suspend fun getNeteaseCover(musicId: String): String? = withContext(Dispatchers.IO) {
        try {
            val data = mapOf(
                "c" to "[{\"id\":$musicId}]",
                "ids" to "[$musicId]",
            )

            val formData = NeteaseCrypto.weapi(data)

            val body = FormBody.Builder().apply {
                formData.forEach { (k, v) -> add(k, v) }
            }.build()

            val request = Request.Builder()
                .url("https://music.163.com/weapi/v3/song/detail")
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com/")
                .header("Origin", "https://music.163.com")
                .post(body)
                .build()

            val resp = client.newCall(request).execute()
            val respBody = resp.body?.string() ?: return@withContext null
            val root = json.parseToJsonElement(respBody).jsonObject
            if (root["code"]?.intOrNull() != 200) return@withContext null

            val url = root["songs"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("al")?.jsonObject?.get("picUrl")?.stringOrNull()
            url?.let { "$it?param=500y500" }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 下载封面图片
     */
    suspend fun downloadCover(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", UA).get().build()
            val resp = client.newCall(request).execute()
            if (resp.isSuccessful) resp.body?.bytes() else null
        } catch (_: Throwable) {
            null
        }
    }

    fun dispose() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun formatInterval(ms: Long): String {
        val secs = ms / 1000
        return "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

/**
 * JsonElement 工具扩展：安全获取字符串/数字
 */
private fun JsonElement?.stringOrNull(): String? {
    if (this == null) return null
    if (this !is JsonPrimitive) return null
    if (this.isString) return this.content
    return this.content.takeIf { it != "null" }
}

private fun JsonElement?.intOrNull(): Int? = this?.let {
    (it as? JsonPrimitive)?.content?.toIntOrNull()
}

private fun JsonElement?.longOrNull(): Long? = this?.let {
    (it as? JsonPrimitive)?.content?.toLongOrNull()
}
