package com.gta.common.common


import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.MutableLiveData
import androidx.media.MediaBrowserServiceCompat
import com.gta.common.media.NETWORK_FAILURE
import com.gta.common.media.extensions.id

//这层就是Repository层
class MusicServiceConnection(context: Context, serviceComponent: ComponentName) {
    //表示与 MediaBrowserService 的连接状态，初始为 false，供 UI 观察
    val isConnected = MutableLiveData<Boolean>()
        .apply { postValue(false) }
    //当服务发出 NETWORK_FAILURE 事件时，此 LiveData 会置为 true，UI 可据此提示网络错误
    val networkFailure = MutableLiveData<Boolean>()
        .apply { postValue(false) }
    //用于接收与服务的连接、断开、失败等回调
    private val mediaBrowserConnectionCallback = MediaBrowserConnectionCallback(context)
    //创建 MediaBrowserCompat 并立即调用 connect() 发起异步连接
    private val mediaBrowser = MediaBrowserCompat(
        context,
        serviceComponent,
        mediaBrowserConnectionCallback, null
    ).apply { connect() }//后台为你执行了 bindService

    //连接成功后，可通过此属性取到服务端 onGetRoot 返回的根媒体 ID
    val rootMediaId: String get() = mediaBrowser.root
    //保存当前播放状态（播放、暂停、缓冲等），初始为空状态常量。
    val playbackState = MutableLiveData<PlaybackStateCompat>()
        .apply { postValue(EMPTY_PLAYBACK_STATE) }
    //当前播放的媒体元数据，初始为 “无播放” 占位常量
    val nowPlaying = MutableLiveData<MediaMetadataCompat>()
        .apply { postValue(NOTHING_PLAYING) }

    //暴露给外部的播放控制（播放/暂停/跳转等）接口，底层通过 MediaControllerCompat 实现
    val transportControls: MediaControllerCompat.TransportControls
        get() = mediaController.transportControls
//    与服务端 MediaSession 绑定，用于收/发控制和状态更新
    private lateinit var mediaController: MediaControllerCompat

    private inner class MediaControllerCallback : MediaControllerCompat.Callback() {
        //播放状态改变时，将最新状态推送给 playbackState
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            playbackState.postValue(state ?: EMPTY_PLAYBACK_STATE)
        }
        //元数据改变时，更新 nowPlaying；若 ID 为空，说明“停止/未播放”，显示占位
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            // When ExoPlayer stops we will receive a callback with "empty" metadata. This is a
            // metadata object which has been instantiated with default values. The default value
            // for media ID is null so we assume that if this value is null we are not playing
            // anything.
            nowPlaying.postValue(
                if (metadata?.id == null) {
                    NOTHING_PLAYING
                } else {
                    metadata
                }
            )
        }

        override fun onQueueChanged(queue: MutableList<MediaSessionCompat.QueueItem>?) {
        }
//接收自定义会话事件（如网络故障），更新 networkFailure
        override fun onSessionEvent(event: String?, extras: Bundle?) {
            super.onSessionEvent(event, extras)
            when (event) {
                NETWORK_FAILURE -> networkFailure.postValue(true)
            }
        }

        /**
         * Normally if a [MediaBrowserServiceCompat] drops its connection the callback comes via
         * [MediaControllerCompat.Callback] (here). But since other connection status events
         * are sent to [MediaBrowserCompat.ConnectionCallback], we catch the disconnect here and
         * send it on to the other callback.
         * 服务端会话被销毁时，视作连接挂起，转发给 MediaBrowser 回调
         */
        override fun onSessionDestroyed() {
            mediaBrowserConnectionCallback.onConnectionSuspended()
        }
    }
    private inner class MediaBrowserConnectionCallback(private val context: Context) :
        MediaBrowserCompat.ConnectionCallback() {
        /**
         * Invoked after [MediaBrowserCompat.connect] when the request has successfully
         * completed.
         * 连接成功后：
         * 用 sessionToken 构造 MediaControllerCompat 并注册前面定义的回调；
         * 将 isConnected 置为 true
         */
        override fun onConnected() {
            // Get a MediaController for the MediaSession.
            mediaController = MediaControllerCompat(context, mediaBrowser.sessionToken).apply {
                registerCallback(MediaControllerCallback())
            }

            isConnected.postValue(true)
        }

        /**
         * Invoked when the client is disconnected from the media browser.
         */
        override fun onConnectionSuspended() {
            isConnected.postValue(false)
        }

        /**
         * Invoked when the connection to the media browser failed.
         */
        override fun onConnectionFailed() {
            isConnected.postValue(false)
        }
    }
    //对指定 parentId 目录进行订阅/取消订阅，子项变化时会回调给 SubscriptionCallback
    fun subscribe(parentId: String, callback: MediaBrowserCompat.SubscriptionCallback) {
        mediaBrowser.subscribe(parentId, callback)
    }
    fun unsubscribe(parentId: String, callback: MediaBrowserCompat.SubscriptionCallback) {
        mediaBrowser.unsubscribe(parentId, callback)
    }

    //向服务发送自定义命令并可选地接收结果。只有在已连接时才会执行，否则返回 false
    fun sendCommand(command: String, parameters: Bundle?) =
        sendCommand(command, parameters) { _, _ -> }
    fun sendCommand(
        command: String,
        parameters: Bundle?,
        resultCallback: ((Int, Bundle?) -> Unit)
    ) = if (mediaBrowser.isConnected) {
        mediaController.sendCommand(command, parameters, object : ResultReceiver(Handler()) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                resultCallback(resultCode, resultData)
            }
        })
        true
    } else {
        false
    }
    companion object {
        // 使用双重检查锁定实现单例，确保全局只有一份连接管理器
        @Volatile
        private var instance: MusicServiceConnection? = null

        fun getInstance(context: Context, serviceComponent: ComponentName) =
            instance ?: synchronized(this) {
                instance ?: MusicServiceConnection(context, serviceComponent)
                    .also { instance = it }
            }
    }
}
//定义“空播放状态”和“无播放曲目”占位常量，避免 LiveData 中出现 null
@Suppress("PropertyName")
val EMPTY_PLAYBACK_STATE: PlaybackStateCompat = PlaybackStateCompat.Builder()
    .setState(PlaybackStateCompat.STATE_NONE, 0, 0f)
    .build()

@Suppress("PropertyName")
val NOTHING_PLAYING: MediaMetadataCompat = MediaMetadataCompat.Builder()
    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "")
    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0)
    .build()