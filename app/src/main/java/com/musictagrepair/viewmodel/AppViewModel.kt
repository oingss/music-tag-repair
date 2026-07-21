package com.musictagrepair.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.musictagrepair.data.FileStatus
import com.musictagrepair.data.MusicTags
import com.musictagrepair.data.OnlineMusicInfo
import com.musictagrepair.data.RepairEngine
import com.musictagrepair.data.TagService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI 状态
 */
data class UiState(
    val scanning: Boolean = false,
    val scanStatus: String = "",
    /** 扫描阶段：发现文件中 / 读标签中 / 完成。 */
    val scanPhase: ScanPhase = ScanPhase.Idle,
    /** 已发现的音频文件总数（MediaStore 查询出来的数量）。 */
    val discoveredCount: Int = 0,
    /** 已读取标签的文件数。 */
    val scannedCount: Int = 0,
    /** 本次扫描的目标总数（读标签阶段使用）。 */
    val scanTotal: Int = 0,
    /** 正在处理的文件名。 */
    val currentFileName: String = "",
    val files: List<FileStatus> = emptyList(),
    val searching: Boolean = false,
    val searchResults: List<OnlineMusicInfo> = emptyList(),
    val repairing: Boolean = false,
    val repairMessage: String = "",
    val currentFile: FileStatus? = null,
    /** All Files Access 授权状态。true 表示已授权（或低版本不需要）。 */
    val allFilesAccessGranted: Boolean = true,
    /** 是否正在批量修复。 */
    val batchRepairing: Boolean = false,
    /** 本次批量修复的总文件数。 */
    val batchTotal: Int = 0,
    /** 已处理（成功或失败）的文件数。 */
    val batchDone: Int = 0,
    /** 批量修复中成功的文件数。 */
    val batchSucceeded: Int = 0,
    /** 批量修复中失败的文件数。 */
    val batchFailed: Int = 0,
    /** 当前正在处理的文件名。 */
    val batchCurrentFileName: String = "",
    /** 最近一次批量修复的总结（用于完成后展示），null 表示未完成 / 进行中。 */
    val batchSummary: String? = null,
) {
    val totalCount: Int get() = files.size
    val completeCount: Int get() = files.count { it.report.isComplete }
    val incompleteCount: Int get() = files.count { !it.report.isComplete }

    /** 0f..1f，用于驱动 UI 进度条；发现阶段固定 -1f 表示无具体百分比。 */
    val scanProgress: Float
        get() = when (scanPhase) {
            ScanPhase.Discovering -> -1f
            ScanPhase.ReadingTags -> if (scanTotal > 0) scannedCount.toFloat() / scanTotal else 0f
            else -> 0f
        }
}

/** 扫描阶段。 */
enum class ScanPhase {
    Idle,
    Discovering,
    ReadingTags,
    Done,
}

/**
 * 全局 ViewModel
 */
class AppViewModel(
    app: Application,
) : AndroidViewModel(app) {

    private val engine = RepairEngine()
    private val rootPathResolver = RootPathResolver(app)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // 启动时刷新一次 All Files Access 状态；用户从设置页返回时由 UI 主动调 [refreshPermissions]
        refreshPermissions()
    }

    /** 重新检测权限状态。从系统设置页返回时调用。 */
    fun refreshPermissions() {
        _uiState.update {
            it.copy(allFilesAccessGranted = PermissionUtil.hasAllFilesAccess())
        }
    }

    /**
     * 通过 SAF 返回的 tree Uri 扫描音乐。
     *
     * 优先走 MediaStore（路径前缀过滤为所选目录）；如果 SAF URI 无法解析为文件路径
     * （例如某些厂商定制的 SAF），仍走 MediaStore 但不限制路径——即扫描全设备音频。
     */
    fun scanDirectory(treeUri: Uri) {
        viewModelScope.launch {
            val realPath = rootPathResolver.resolve(treeUri)
            _uiState.update {
                it.copy(
                    scanning = true,
                    scanPhase = ScanPhase.Discovering,
                    scanStatus = if (realPath != null) "正在通过媒体库发现 $realPath 下的音频..."
                    else "SAF 目录无法解析为路径，将扫描全设备音频...",
                    discoveredCount = 0,
                    scannedCount = 0,
                    scanTotal = 0,
                    currentFileName = "",
                )
            }
            doScanViaMediaStore(pathPrefix = realPath)
        }
    }

    /**
     * 不需要 SAF 选目录，直接扫描整台设备的音频（MediaStore 索引）。
     */
    fun scanAllMedia() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    scanning = true,
                    scanPhase = ScanPhase.Discovering,
                    scanStatus = "正在通过媒体库发现音频...",
                    discoveredCount = 0,
                    scannedCount = 0,
                    scanTotal = 0,
                    currentFileName = "",
                )
            }
            doScanViaMediaStore(pathPrefix = null)
        }
    }

    private suspend fun doScanViaMediaStore(pathPrefix: String?) {
        try {
            val files = engine.scanViaMediaStore(
                context = getApplication(),
                pathPrefix = pathPrefix,
                onDiscovered = { count, name ->
                    // 发现阶段只在每 20 个或前几个时刷新，避免 O(N²) UI 抖动
                    if (count <= 5 || count % 20 == 0) {
                        _uiState.update {
                            it.copy(
                                discoveredCount = count,
                                currentFileName = name,
                                scanStatus = "正在发现音频 $count ...",
                            )
                        }
                    }
                },
                onTagsRead = { done, total, name ->
                    // 读标签阶段是最耗时的，每个文件都要刷新进度
                    if (done <= 5 || done % 5 == 0 || done == total) {
                        _uiState.update {
                            it.copy(
                                scanPhase = ScanPhase.ReadingTags,
                                scannedCount = done,
                                scanTotal = total,
                                currentFileName = name,
                                scanStatus = "读取标签 $done / $total ...",
                            )
                        }
                    }
                },
            )
            _uiState.update {
                it.copy(
                    scanning = false,
                    scanPhase = ScanPhase.Done,
                    scanStatus = "扫描完成，共 ${files.size} 个文件",
                    files = files,
                    currentFileName = "",
                )
            }
        } catch (e: Throwable) {
            _uiState.update {
                it.copy(
                    scanning = false,
                    scanPhase = ScanPhase.Idle,
                    scanStatus = "扫描失败: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    /**
     * 扫描已知路径（用于测试或扫描内置 Music 目录）
     */
    fun scanDirectoryPath(path: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    scanning = true,
                    scanPhase = ScanPhase.Discovering,
                    scanStatus = "正在通过媒体库扫描 $path ...",
                    discoveredCount = 0,
                    scannedCount = 0,
                    scanTotal = 0,
                    currentFileName = "",
                )
            }
            doScanViaMediaStore(pathPrefix = path)
        }
    }

    /**
     * 在线搜索匹配
     */
    fun searchOnline(file: FileStatus) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentFile = file,
                    searching = true,
                    searchResults = emptyList(),
                    repairMessage = "",
                )
            }
            try {
                val results = engine.searchOnline(file)
                _uiState.update {
                    it.copy(searching = false, searchResults = results)
                }
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(searching = false, searchResults = emptyList())
                }
            }
        }
    }

    /**
     * 修复当前文件
     */
    fun repair(info: OnlineMusicInfo, onDone: (Boolean) -> Unit) {
        val current = _uiState.value.currentFile ?: run {
            onDone(false); return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(repairing = true, repairMessage = "正在获取元数据...") }
            try {
                val tags = engine.fetchFullMetadata(info)
                val existing = current.report.currentTags
                val merged = MusicTags(
                    title = tags.title ?: existing.title,
                    artist = tags.artist ?: existing.artist,
                    album = tags.album ?: existing.album,
                    albumArtist = tags.albumArtist ?: existing.albumArtist,
                    year = tags.year ?: existing.year,
                    trackNumber = tags.trackNumber ?: existing.trackNumber,
                    trackTotal = tags.trackTotal ?: existing.trackTotal,
                    discNumber = tags.discNumber ?: existing.discNumber,
                    discTotal = tags.discTotal ?: existing.discTotal,
                    genre = tags.genre ?: existing.genre,
                    coverMime = tags.coverMime ?: existing.coverMime,
                    coverData = tags.coverData ?: existing.coverData,
                    lyrics = tags.lyrics ?: existing.lyrics,
                    durationMs = existing.durationMs,
                    bitrate = existing.bitrate,
                    sampleRate = existing.sampleRate,
                    channels = existing.channels,
                )

                _uiState.update { it.copy(repairMessage = "正在写入标签...") }
                val success = engine.writeTagsToFile(current.path, merged)

                // 更新文件状态
                if (success) {
                    val newTags = TagService.readTags(current.path) ?: merged
                    val newReport = com.musictagrepair.data.CompletenessReport.check(newTags)
                    val newFile = current.copy(report = newReport)
                    _uiState.update { ui ->
                        ui.copy(
                            files = ui.files.map { if (it.path == current.path) newFile else it },
                            currentFile = newFile,
                            repairMessage = "修复成功",
                        )
                    }
                } else {
                    _uiState.update { it.copy(repairMessage = "写入失败") }
                }

                onDone(success)
            } catch (e: Throwable) {
                _uiState.update { it.copy(repairMessage = "修复失败: ${e.message}") }
                onDone(false)
            } finally {
                _uiState.update { it.copy(repairing = false) }
            }
        }
    }

    /**
     * 批量修复：遍历所有 [isComplete]=false 的文件，对每个文件做在线搜索 → 取第一个匹配结果
     * → 拉取完整元数据（歌词 + 封面） → 写入文件 → 重新读 tags 验证。
     *
     * 选择「第一个搜索结果」作为自动匹配的依据：搜索接口通常按相关度排序，首个最贴合。
     * 用户如果对某个文件不满意，可单独点进 MatchScreen 手动选择其他结果。
     *
     * 串行执行而非并发：jaudiotagger 写入会修改文件元数据，并发写同一目录下的文件
     * 可能触发底层 I/O 竞争；同时串行也方便 UI 显示精确进度。
     *
     * 每处理完一个文件就实时更新 [files] 列表中的对应项，用户能在列表中直接看到分数变化。
     *
     * @param onlyMissing true 表示仅处理 [CompletenessReport.isComplete]=false 的文件；
     *                    false 表示处理所有文件（强制覆盖已有标签）
     */
    fun repairAll(onlyMissing: Boolean = true) {
        // 防止重复触发
        if (_uiState.value.batchRepairing) return
        if (_uiState.value.scanning) return

        val pending = _uiState.value.files.filter {
            !it.report.isComplete || !onlyMissing
        }
        if (pending.isEmpty()) {
            _uiState.update { it.copy(batchSummary = "没有需要修复的文件") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    batchRepairing = true,
                    batchTotal = pending.size,
                    batchDone = 0,
                    batchSucceeded = 0,
                    batchFailed = 0,
                    batchCurrentFileName = "",
                    batchSummary = null,
                )
            }

            var succeeded = 0
            var failed = 0
            for ((index, file) in pending.withIndex()) {
                _uiState.update {
                    it.copy(
                        batchCurrentFileName = file.filename,
                        batchDone = index,
                    )
                }

                val ok = try {
                    // 1) 在线搜索
                    val results = engine.searchOnline(file)
                    val first = results.firstOrNull()
                    if (first == null) {
                        failed++
                        false
                    } else {
                        // 2) 拉完整元数据 + 合并已有标签
                        val tags = engine.fetchFullMetadata(first)
                        val existing = file.report.currentTags
                        val merged = MusicTags(
                            title = tags.title ?: existing.title,
                            artist = tags.artist ?: existing.artist,
                            album = tags.album ?: existing.album,
                            albumArtist = tags.albumArtist ?: existing.albumArtist,
                            year = tags.year ?: existing.year,
                            trackNumber = tags.trackNumber ?: existing.trackNumber,
                            trackTotal = tags.trackTotal ?: existing.trackTotal,
                            discNumber = tags.discNumber ?: existing.discNumber,
                            discTotal = tags.discTotal ?: existing.discTotal,
                            genre = tags.genre ?: existing.genre,
                            coverMime = tags.coverMime ?: existing.coverMime,
                            coverData = tags.coverData ?: existing.coverData,
                            lyrics = tags.lyrics ?: existing.lyrics,
                            durationMs = existing.durationMs,
                            bitrate = existing.bitrate,
                            sampleRate = existing.sampleRate,
                            channels = existing.channels,
                        )

                        // 3) 写入文件
                        val writeOk = engine.writeTagsToFile(file.path, merged)
                        if (writeOk) {
                            // 4) 重新读 tags 验证，更新列表中对应项
                            val newTags = TagService.readTags(file.path) ?: merged
                            val newReport = com.musictagrepair.data.CompletenessReport.check(newTags)
                            val newFile = file.copy(report = newReport)
                            _uiState.update { ui ->
                                ui.copy(
                                    files = ui.files.map { if (it.path == file.path) newFile else it },
                                )
                            }
                            succeeded++
                        } else {
                            failed++
                        }
                        writeOk
                    }
                } catch (e: Throwable) {
                    failed++
                    false
                }

                _uiState.update {
                    it.copy(
                        batchDone = index + 1,
                        batchSucceeded = succeeded,
                        batchFailed = failed,
                    )
                }
            }

            _uiState.update {
                it.copy(
                    batchRepairing = false,
                    batchCurrentFileName = "",
                    batchDone = pending.size,
                    batchSucceeded = succeeded,
                    batchFailed = failed,
                    batchSummary = "批量修复完成：成功 $succeeded / 失败 $failed / 共 ${pending.size}",
                )
            }
        }
    }

    /** 清除批量修复的总结提示。 */
    fun dismissBatchSummary() {
        _uiState.update { it.copy(batchSummary = null) }
    }

    /**
     * 重新扫描当前目录
     */
    fun rescan() {
        val first = _uiState.value.files.firstOrNull()
        if (first == null) {
            scanAllMedia()
        } else {
            val dir = first.path.substringBeforeLast('/')
            scanDirectoryPath(dir)
        }
    }

    fun clearCurrentFile() {
        _uiState.update {
            it.copy(
                currentFile = null,
                searchResults = emptyList(),
                repairMessage = "",
            )
        }
    }

    override fun onCleared() {
        engine.dispose()
        super.onCleared()
    }
}
