package com.musictagrepair.data

/**
 * 音乐标签数据
 */
data class MusicTags(
    var title: String? = null,
    var artist: String? = null,
    var album: String? = null,
    var albumArtist: String? = null,
    var year: Int? = null,
    var trackNumber: Int? = null,
    var trackTotal: Int? = null,
    var discNumber: Int? = null,
    var discTotal: Int? = null,
    var genre: String? = null,
    var coverMime: String? = null,
    var coverData: ByteArray? = null,
    var lyrics: String? = null,
    var durationMs: Long = 0L,
    var bitrate: Int = 0,
    var sampleRate: Int = 0,
    var channels: Int = 0,
) {
    val hasTag: Boolean get() = !title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MusicTags
        return title == other.title && artist == other.artist && album == other.album
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        return result
    }
}

/**
 * 缺失字段
 */
enum class MissingField(val label: String) {
    TITLE("标题"),
    ARTIST("歌手"),
    ALBUM("专辑"),
    COVER("封面"),
    LYRICS("歌词");
}

/**
 * 完整性报告
 */
data class CompletenessReport(
    val hasTitle: Boolean,
    val hasArtist: Boolean,
    val hasAlbum: Boolean,
    val hasCover: Boolean,
    val hasLyrics: Boolean,
    val missingFields: List<MissingField>,
    val currentTags: MusicTags,
    val score: Int,
) {
    val isComplete: Boolean get() = missingFields.isEmpty()

    companion object {
        fun check(tags: MusicTags): CompletenessReport {
            val missing = mutableListOf<MissingField>()

            val hasTitle = !tags.title.isNullOrBlank()
            if (!hasTitle) missing.add(MissingField.TITLE)

            val hasArtist = !tags.artist.isNullOrBlank()
            if (!hasArtist) missing.add(MissingField.ARTIST)

            val hasAlbum = !tags.album.isNullOrBlank()
            if (!hasAlbum) missing.add(MissingField.ALBUM)

            val hasCover = tags.coverData?.isNotEmpty() == true
            if (!hasCover) missing.add(MissingField.COVER)

            val lyricsText = tags.lyrics
            val hasLyrics = lyricsText != null && lyricsText.length > 10
            if (!hasLyrics) missing.add(MissingField.LYRICS)

            val total = 5
            val complete = total - missing.size
            val score = (complete * 100 / total)

            return CompletenessReport(
                hasTitle = hasTitle,
                hasArtist = hasArtist,
                hasAlbum = hasAlbum,
                hasCover = hasCover,
                hasLyrics = hasLyrics,
                missingFields = missing,
                currentTags = tags,
                score = score,
            )
        }
    }
}

/**
 * 文件状态
 */
data class FileStatus(
    val path: String,
    val filename: String,
    val report: CompletenessReport,
)

/**
 * 在线音乐信息
 */
data class OnlineMusicInfo(
    val id: String,
    val name: String,
    val singer: String,
    val album: String,
    val interval: String? = null,
    val coverUrl: String? = null,
    val lyrics: String? = null,
    val tlyrics: String? = null,
    val rlyrics: String? = null,
    val sourceId: String,
)
