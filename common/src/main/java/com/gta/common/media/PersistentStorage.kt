package com.gta.common.media


import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import com.bumptech.glide.Glide
import com.gta.common.media.extensions.asAlbumArtContentUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 持有一个 context 引用，用于后续获取 SharedPreferences 和 Glide 操作
 */
internal class PersistentStorage private constructor(val context: Context) {

    /**
     * Store any data which must persist between restarts, such as the most recently played song.
     * 模式为 MODE_PRIVATE，仅本应用可读写
     */
    private var preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    //确保多线程访问时对 instance 的读写可见
    companion object {

        @Volatile
        private var instance: PersistentStorage? = null

        //双重检查锁定（先检查 instance 是否为 null，若为 null 则同步块内再次检查并创建实例）
        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: PersistentStorage(context).also { instance = it }
            }
    }

    suspend fun saveRecentSong(description: MediaDescriptionCompat, position: Long) {

        withContext(Dispatchers.IO) {

            /**
             * After booting, Android will attempt to build static media controls for the most
             * recently played song. Artwork for these media controls should not be loaded
             * from the network as it may be too slow or unavailable immediately after boot. Instead
             * we convert the iconUri to point to the Glide on-disk cache.
             * 利用 Glide 以文件形式下载并缓存 description.iconUri 指定的封面图
             */
            //.submit(...).get() 同步等待下载完成
            //调用扩展函数 asAlbumArtContentUri() 转换为内容提供者 URI，以便 Android 静态媒体控件启动时直接读取磁盘缓存，而不依赖网络
            val localIconUri = Glide.with(context).asFile().load(description.iconUri)
                .submit(
                    NOTIFICATION_LARGE_ICON_SIZE,
                    NOTIFICATION_LARGE_ICON_SIZE
                ).get()
                .asAlbumArtContentUri()
//将以下字段写入本地,歌曲唯一标识,指向本地缓存封面的 Content URI,上次播放进度（毫秒）
            preferences.edit()
                .putString(
                    RECENT_SONG_MEDIA_ID_KEY,
                    description.mediaId
                )
                .putString(
                    RECENT_SONG_TITLE_KEY,
                    description.title.toString()
                )
                .putString(
                    RECENT_SONG_SUBTITLE_KEY,
                    description.subtitle.toString()
                )
                .putString(
                    RECENT_SONG_ICON_URI_KEY,
                    localIconUri.toString()
                )
                .putLong(RECENT_SONG_POSITION_KEY, position)
                .apply()//异步提交，不阻塞主线程
        }
    }

    fun loadRecentSong(): MediaBrowserCompat.MediaItem? {
        //从 SharedPreferences 读取 mediaId，如果为 null，说明没有记录过，返回 null
        val mediaId = preferences.getString(RECENT_SONG_MEDIA_ID_KEY, null)
        return if (mediaId == null) {
            null
        } else {//否则新建一个 Bundle，将保存的播放进度 position 放入 extras，键名为常量 MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS
            val extras = Bundle().also {
                val position = preferences.getLong(RECENT_SONG_POSITION_KEY, 0L)
                it.putLong(MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS, position)
            }
//使用 MediaDescriptionCompat.Builder 重建媒体描述
            //将该 MediaDescriptionCompat 包装成 MediaBrowserCompat.MediaItem，并标记为可播放（FLAG_PLAYABLE），返回给调用者
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(mediaId)
                    .setTitle(
                        preferences.getString(
                            RECENT_SONG_TITLE_KEY,
                            ""
                        )
                    )//从偏好设置中读取对应字段
                    .setSubtitle(
                        preferences.getString(
                            RECENT_SONG_SUBTITLE_KEY,
                            ""
                        )
                    )
                    .setIconUri(
                        Uri.parse(
                            preferences.getString(
                                RECENT_SONG_ICON_URI_KEY,
                                ""
                            )
                        )
                    )
                    .setExtras(extras)
                    .build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        }
    }
}

private const val PREFERENCES_NAME = "uamp"
private const val RECENT_SONG_MEDIA_ID_KEY = "recent_song_media_id"
private const val RECENT_SONG_TITLE_KEY = "recent_song_title"
private const val RECENT_SONG_SUBTITLE_KEY = "recent_song_subtitle"
private const val RECENT_SONG_ICON_URI_KEY = "recent_song_icon_uri"
private const val RECENT_SONG_POSITION_KEY = "recent_song_position"
val MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS = "playback_start_position_ms"