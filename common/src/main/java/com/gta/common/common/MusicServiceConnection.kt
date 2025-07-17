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
    val isConnected = MutableLiveData<Boolean>()
        .apply { postValue(false) }
    val networkFailure = MutableLiveData<Boolean>()
        .apply { postValue(false) }
    //连接状态回调
    private val mediaBrowserConnectionCallback = MediaBrowserConnectionCallback(context)
    //新建一个MediaBrowser，并且立即连接，连接的结果通过回调返回
    private val mediaBrowser = MediaBrowserCompat(
        context,
        serviceComponent,
        mediaBrowserConnectionCallback, null
    ).apply { connect() }
    //获取根节点的MediaId，这些在Service的onGetRoot方法里处理
    val rootMediaId: String get() = mediaBrowser.root
    //PlaybackStateCompat：Android 媒体库中表示播放状态的类（如播放/暂停/缓冲）
    val playbackState = MutableLiveData<PlaybackStateCompat>()
        .apply { postValue(EMPTY_PLAYBACK_STATE) }
    //当前正在播放的MediaMetadataCompat对象，这里初始化的是空的歌曲
    val nowPlaying = MutableLiveData<MediaMetadataCompat>()
        .apply { postValue(NOTHING_PLAYING) }

    //遥控器，用来控制播放
    val transportControls: MediaControllerCompat.TransportControls
        get() = mediaController.transportControls

    private lateinit var mediaController: MediaControllerCompat

    private inner class MediaControllerCallback : MediaControllerCompat.Callback() {
        //播放器控制之后的状态回调
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            playbackState.postValue(state ?: EMPTY_PLAYBACK_STATE)
        }
        //播放器控制之后，返回当前正在播放的歌曲的元数据
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
    //用于 订阅指定媒体ID下的内容变化，当该目录的子项更新时（如新增歌曲），自动通知客户端。
    fun subscribe(parentId: String, callback: MediaBrowserCompat.SubscriptionCallback) {
        mediaBrowser.subscribe(parentId, callback)
    }
    fun unsubscribe(parentId: String, callback: MediaBrowserCompat.SubscriptionCallback) {
        mediaBrowser.unsubscribe(parentId, callback)
    }
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
        // For Singleton instantiation.
        @Volatile
        private var instance: MusicServiceConnection? = null

        fun getInstance(context: Context, serviceComponent: ComponentName) =
            instance ?: synchronized(this) {
                instance ?: MusicServiceConnection(context, serviceComponent)
                    .also { instance = it }
            }
    }
}

@Suppress("PropertyName")
val EMPTY_PLAYBACK_STATE: PlaybackStateCompat = PlaybackStateCompat.Builder()
    .setState(PlaybackStateCompat.STATE_NONE, 0, 0f)
    .build()

@Suppress("PropertyName")
val NOTHING_PLAYING: MediaMetadataCompat = MediaMetadataCompat.Builder()
    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "")
    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0)
    .build()