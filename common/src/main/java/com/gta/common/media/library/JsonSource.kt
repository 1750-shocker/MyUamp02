package com.gta.common.media.library

import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat.STATUS_NOT_DOWNLOADED
import android.support.v4.media.MediaMetadataCompat
import com.google.android.exoplayer2.C
import com.google.gson.Gson
import com.gta.common.media.extensions.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.util.concurrent.TimeUnit

//模块内可见性修饰符，
internal class JsonSource(private val source: Uri) : AbstractMusicSource() {
    companion object {
        const val ORIGINAL_ARTWORK_URI_KEY = "com.example.android.uamp.JSON_ARTWORK_URI"
    }

    //核心资产，媒体元数据
    private var catalog: List<MediaMetadataCompat> = emptyList()

    init {
        state = STATE_INITIALIZING
    }

    //因为这个interface MusicSource : Iterable<MediaMetadataCompat>
    //所以这个iterator()方法就是把List<MediaMetadataCompat>的遍历能力直接给MusicSource类
    override fun iterator(): Iterator<MediaMetadataCompat> = catalog.iterator()
    override suspend fun load() {
        updateCatalog(source)?.let { updatedCatalog ->
            catalog = updatedCatalog
            state = STATE_INITIALIZED
        } ?: run {
            catalog = emptyList()
            state = STATE_ERROR
        }
    }

    //核心行为，利用传进来的Uri，下载Json文件，解析为MediaMetadataCompat
    private suspend fun updateCatalog(catalogUri: Uri): List<MediaMetadataCompat>? {
        return withContext(Dispatchers.IO) {
            //用网络请求下载Json文件，返回JsonCatalog对象
            val musicCat = try {
                //返回JsonCatalog对象，包含List<JsonMusic>
                downloadJson(catalogUri)
            } catch (ioException: IOException) {
                return@withContext null
            }

            // Get the base URI to fix up relative references later.
            val baseUri = catalogUri.toString().removeSuffix(catalogUri.lastPathSegment ?: "")
            //把JsonCatalog对象的List<JsonMusic>转换为List<MediaMetadataCompat>
            val mediaMetadataCompats = musicCat.music.map { song ->
                // The JSON may have paths that are relative to the source of the JSON
                // itself. We need to fix them up here to turn them into absolute paths.
                //修正 source（音频）和 image（封面）字段
                catalogUri.scheme?.let { scheme ->
                    if (!song.source.startsWith(scheme)) {
                        song.source = baseUri + song.source
                    }
                    if (!song.image.startsWith(scheme)) {
                        song.image = baseUri + song.image
                    }
                }
                //将 image 字符串转换为 Uri，再映射到 ContentProvider URI
                val jsonImageUri = Uri.parse(song.image)
                //调用 AlbumArtContentProvider.mapUri(...)，把网络图片 URI 转成 content://… 形式。
                val imageUri = AlbumArtContentProvider.mapUri(jsonImageUri)
                //从Json文件中映射的对象的属性再映射到MediaItem中，填充元数据，再补充显示用的封面 URI 和 Cast 用的原始地址
                MediaMetadataCompat.Builder()
                    .from(song)//用扩展方法填充常规字段
                    .apply {
                        displayIconUri = imageUri.toString() // Used by ExoPlayer and Notification
                        albumArtUri = imageUri.toString()
                        //保存原始网络地址，用于 Cast Metadata
                        putString(ORIGINAL_ARTWORK_URI_KEY, jsonImageUri.toString())
                    }
                    .build()
            }.toList()
            //把每个 metadata 的 bundle 放到 description.extras 中，确保 ExoPlayer 通知更新时能拿到所有信息
            mediaMetadataCompats.forEach { it.description.extras?.putAll(it.bundle) }
            //返回转换后的List<MediaMetadataCompat>
            mediaMetadataCompats
        }
    }

    @Throws(IOException::class)
    private fun downloadJson(catalogUri: Uri): JsonCatalog {
        val catalogConn = URL(catalogUri.toString())
        val reader = BufferedReader(InputStreamReader(catalogConn.openStream()))
        //请求回来的JSON数据，转换为JsonCatalog对象
        return Gson().fromJson(reader, JsonCatalog::class.java)
    }
}

/**
 * 负责把 JsonMusic 对象里的字段一一映射到 MediaMetadataCompat.Builder：
 *
 * 把 JSON 里以秒为单位的 duration 转成毫秒；
 * -[object Object]媒体 ID、标题、艺术家、专辑、时长、流派、资源 URI、封面 URI、曲目序号和总数；
 *
 * 设置 FLAG 为可播放；
 *
 * 再设置显示用的标题、副标题、描述、图标 URI；
 *
 * 最后设置 downloadStatus，确保 extras Bundle 存在，后续能携带自定义字段。
 *
 *
 */
fun MediaMetadataCompat.Builder.from(jsonMusic: JsonMusic): MediaMetadataCompat.Builder {
    // The duration from the JSON is given in seconds, but the rest of the code works in
    // milliseconds. Here's where we convert to the proper units.
    val durationMs = TimeUnit.SECONDS.toMillis(jsonMusic.duration)

    id = jsonMusic.id
    title = jsonMusic.title
    artist = jsonMusic.artist
    album = jsonMusic.album
    duration = durationMs
    genre = jsonMusic.genre
    mediaUri = jsonMusic.source
    albumArtUri = jsonMusic.image
    trackNumber = jsonMusic.trackNumber
    trackCount = jsonMusic.totalTrackCount
    flag = MediaItem.FLAG_PLAYABLE

    // To make things easier for *displaying* these, set the display properties as well.
    displayTitle = jsonMusic.title
    displaySubtitle = jsonMusic.artist
    displayDescription = jsonMusic.album
    displayIconUri = jsonMusic.image

    // Add downloadStatus to force the creation of an "extras" bundle in the resulting
    // MediaMetadataCompat object. This is needed to send accurate metadata to the
    // media session during updates.
    downloadStatus = STATUS_NOT_DOWNLOADED

    // Allow it to be used in the typical builder style.
    return this
}

class JsonCatalog {
    var music: List<JsonMusic> = ArrayList()
}

@Suppress("unused")
class JsonMusic {
    var id: String = ""
    var title: String = ""
    var album: String = ""
    var artist: String = ""
    var genre: String = ""
    var source: String = ""
    var image: String = ""
    var trackNumber: Long = 0
    var totalTrackCount: Long = 0
    var duration: Long = C.TIME_UNSET
    var site: String = ""
}