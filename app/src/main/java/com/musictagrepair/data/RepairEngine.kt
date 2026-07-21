package com.musictagrepair.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 修复引擎：扫描目录、在线搜索、获取完整元数据、写入标签
 */
class RepairEngine(
    private val tagService: TagService = TagService,
    private val onlineService: OnlineService = OnlineService(),
) {
    companion object { private const val TAG = "RepairEngine" }

    /**
     * 扫描目录
     */
    suspend fun scanDirectory(dirPath: String): List<FileStatus> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FileStatus>()
        val root = File(dirPath)
        if (!root.exists() || !root.isDirectory) return@withContext emptyList()

        root.walkTopDown().forEach { file ->
            if (file.isFile && TagService.isSupportedFile(file.absolutePath)) {
                val tags = tagService.readTags(file.absolutePath)
                if (tags != null) {
                    val report = CompletenessReport.check(tags)
                    result.add(
                        FileStatus(
                            path = file.absolutePath,
                            filename = file.name,
                            report = report,
                        ),
                    )
                }
            }
        }
        result
    }

    /**
     * 通过 [LocalScanner]（MediaStore 优先）发现音频文件，然后逐个读取标签构建完整性报告。
     *
     * 扫描分两个阶段，分别触发回调：
     * 1. **发现阶段**：MediaStore 查询本身很快，但文件数多时仍需滚动展示进度；
     *    调用 [onDiscovered] 上报 `(已发现数, 当前文件名)`。
     * 2. **读标签阶段**：jaudiotagger 逐文件解析，是最耗时的部分；
     *    调用 [onTagsRead] 上报 `(已读标签数, 总数, 当前文件名)`，UI 进度条基于此驱动。
     *
     * @param pathPrefix 仅保留此路径前缀下的文件；null 表示不限制
     */
    suspend fun scanViaMediaStore(
        context: Context,
        pathPrefix: String? = null,
        onDiscovered: (Int, String) -> Unit = { _, _ -> },
        onTagsRead: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): List<FileStatus> = withContext(Dispatchers.IO) {
        // 1) 一次性通过 MediaStore 拉取全部音频文件清单
        val audioFiles = LocalScanner.queryAllAudio(context, pathPrefix, onProgress = onDiscovered)
        if (audioFiles.isEmpty()) return@withContext emptyList()

        val total = audioFiles.size
        val result = mutableListOf<FileStatus>()

        // 2) 逐个读取标签
        for ((index, audio) in audioFiles.withIndex()) {
            val tags = tagService.readTags(audio.path)
            if (tags != null) {
                val report = CompletenessReport.check(tags)
                result.add(
                    FileStatus(
                        path = audio.path,
                        filename = audio.name,
                        report = report,
                    ),
                )
            }
            onTagsRead(index + 1, total, audio.name)
        }
        result
    }

    /**
     * 在线搜索匹配
     */
    suspend fun searchOnline(fileStatus: FileStatus): List<OnlineMusicInfo> {
        val tags = fileStatus.report.currentTags
        val keyword = when {
            !tags.title.isNullOrBlank() && !tags.artist.isNullOrBlank() -> "${tags.artist} ${tags.title}"
            !tags.title.isNullOrBlank() -> tags.title!!
            else -> fileStatus.filename
                .substringBeforeLast('.')
                .replace(Regex("[\\(\\[（].*?[\\)\\]）]"), "")
                .trim()
        }

        val results = mutableListOf<OnlineMusicInfo>()
        results.addAll(onlineService.searchNetease(keyword))
        results.addAll(onlineService.searchKuGou(keyword))
        return results
    }

    /**
     * 获取完整元数据（在线歌曲 → 歌词 + 封面）
     */
    suspend fun fetchFullMetadata(info: OnlineMusicInfo): MusicTags {
        val tags = MusicTags(
            title = info.name,
            artist = info.singer,
            album = info.album,
        )

        // 歌词
        if (info.sourceId == "wy") {
            try {
                val lyrics = onlineService.getNeteaseLyrics(info.id)
                val lyric = lyrics?.get("lyric")
                if (!lyric.isNullOrBlank()) {
                    tags.lyrics = lyric
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Get lyrics failed: ${e.message}")
            }
        }

        // 封面
        try {
            val coverUrl = info.coverUrl ?: if (info.sourceId == "wy") onlineService.getNeteaseCover(info.id) else null
            if (coverUrl != null) {
                val coverData = onlineService.downloadCover(coverUrl)
                if (coverData != null) {
                    tags.coverData = coverData
                    tags.coverMime = "image/jpeg"
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Get cover failed: ${e.message}")
        }

        return tags
    }

    /**
     * 写入标签
     */
    suspend fun writeTagsToFile(filePath: String, tags: MusicTags): Boolean = withContext(Dispatchers.IO) {
        tagService.writeTags(filePath, tags)
    }

    fun dispose() {
        onlineService.dispose()
    }
}
