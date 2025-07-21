package com.gta.common.media


import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaBrowserServiceCompat.BrowserRoot.EXTRA_RECENT
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Player.EVENT_MEDIA_ITEM_TRANSITION
import com.google.android.exoplayer2.Player.EVENT_PLAY_WHEN_READY_CHANGED
import com.google.android.exoplayer2.Player.EVENT_POSITION_DISCONTINUITY
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.util.Util.constrainValue
import com.gta.common.R
import com.gta.common.media.extensions.album
import com.gta.common.media.extensions.flag
import com.gta.common.media.extensions.id
import com.gta.common.media.extensions.toMediaItem
import com.gta.common.media.extensions.trackNumber
import com.gta.common.media.library.BrowseTree
import com.gta.common.media.library.JsonSource
import com.gta.common.media.library.*
import com.gta.common.media.library.MusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * This class is the entry point for browsing and playback commands from the APP's UI
 * and other apps that wish to play music via UAMP (for example, Android Auto or
 * the Google Assistant).
 *
 * Browsing begins with the method [MusicService.onGetRoot], and continues in
 * the callback [MusicService.onLoadChildren].
 *
 * For more information on implementing a MediaBrowserService,
 * visit [https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice.html](https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice.html).
 *
 * This class also handles playback for Cast sessions.
 * When a Cast session is active, playback commands are passed to a
 * [CastPlayer](https://exoplayer.dev/doc/reference/com/google/android/exoplayer2/ext/cast/CastPlayer.html),
 * otherwise they are passed to an ExoPlayer for local playback.
 */
open class MusicService : MediaBrowserServiceCompat() {

    private lateinit var notificationManager: UampNotificationManager
    private lateinit var mediaSource: MusicSource

    // The current player will either be an ExoPlayer (for local playback) or a CastPlayer (for
    // remote playback through a Cast device).
    private lateinit var currentPlayer: Player

    //协程标准用法，没有用Android包里的扩展函数啥的
    //使用协程管理异步任务，SupervisorJob 确保子任务失败不会取消整个服务
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    //将播放状态、元数据暴露给系统控制（锁屏、通知、外部设备）
    protected lateinit var mediaSession: MediaSessionCompat
    //连接 ExoPlayer 与 MediaSession 的桥梁，将系统控制指令转发给播放器
    protected lateinit var mediaSessionConnector: MediaSessionConnector
//    保存当前播放列表的元数据和正在播放的索引，方便实现“下一首/上一首”及恢复播放位置
    private var currentPlaylistItems: List<MediaMetadataCompat> = emptyList()
    private var currentMediaItemIndex: Int = 0
//本地持久化存储，用于保存“最近播放”歌曲信息
    private lateinit var storage: PersistentStorage

    /**
     * This must be `by lazy` because the source won't initially be ready.
     * See [MusicService.onLoadChildren] to see where it's accessed (and first
     * constructed).
     */
    //懒加载构建一个树形的媒体浏览结构，根据 mediaSource 构造分层的浏览目录
    private val browseTree: BrowseTree by lazy {
        BrowseTree(applicationContext, mediaSource)
    }
//标记当前服务是否已经以前台服务形式运行，用于管理通知生命周期
    private var isForegroundService = false

    private val remoteJsonSource: Uri =
        Uri.parse("https://storage.googleapis.com/uamp/catalog.json")
//配置音频属性，保证在其他媒体（导航提示、通知音）和音乐之间的合理混音与优先级
    private val uAmpAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()
//ExoPlayer 事件监听器实例，用于同步媒体状态到 MediaSession 并处理错误和播放进度
    private val playerListener = PlayerEventListener()

//延迟初始化 ExoPlayer，并绑定音频属性、耳机拔出自动暂停、事件监听
    private val exoPlayer: ExoPlayer by lazy {
        SimpleExoPlayer.Builder(this).build().apply {
            setAudioAttributes(uAmpAudioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            addListener(playerListener)
        }
    }


    @ExperimentalCoroutinesApi
    override fun onCreate() {
        super.onCreate()

        // Build a PendingIntent that can be used to launch the UI.
        //通知栏的音乐被点击后可以启动主页面，这个intent是给session的
        val sessionActivityPendingIntent =
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                PendingIntent.getActivity(this, 0, sessionIntent, PendingIntent.FLAG_IMMUTABLE)
            }
        currentPlayer = exoPlayer

        // 创建 MediaSession 并激活，
        mediaSession = MediaSessionCompat(this, "MusicService")
            .apply {
                setSessionActivity(sessionActivityPendingIntent)
                isActive = true
            }
        //公开 sessionToken 供外部（MediaController）连接
        sessionToken = mediaSession.sessionToken

        notificationManager = UampNotificationManager(
            this,
            mediaSession.sessionToken,
            PlayerNotificationListener()
        )

        // The media library is built from a remote JSON file. We'll create the source here,
        // and then use a suspend function to perform the download off the main thread.
        //创建媒体资源来源，并在后台协程中异步加载 JSON 目录
        mediaSource = JsonSource(source = remoteJsonSource)
        serviceScope.launch {
            mediaSource.load()
        }

        // ExoPlayer will manage the MediaSession for us.
        //connector:实现 Player 接口与 MediaSession 的双向同步
        //系统 → 播放器：
        //当用户按下耳机播放键时，MediaSession 收到指令 → MediaSessionConnector 转换为播放器的 play() 方法调用。
        //播放器 → 系统：
        //播放器状态变化（如开始缓冲） → MediaSessionConnector 更新 MediaSession → 系统UI（如通知栏）自动刷新。
        //组件：PlaybackPreparer：处理播放准备逻辑（如按ID/搜索词播放）
        //组件：QueueNavigator：处理播放队列逻辑（如下一首/上一首）
        //组件：PlayerCommandHandler：处理播放器命令（如暂停/快进）自定义控制指令处理
        //组件：MetadataUpdater：动态更新媒体元数据（如标题/封面）
        //统一不同Android版本的媒体控制行为
        //连接播放器与系统媒体框架
        //通过自定义组件满足特殊需求
        mediaSessionConnector = MediaSessionConnector(mediaSession)
        mediaSessionConnector.setPlaybackPreparer(UampPlaybackPreparer())
        mediaSessionConnector.setQueueNavigator(UampQueueNavigator(mediaSession))

//        notificationManager.showNotificationForPlayer(currentPlayer)

//初始化本地存储，用于保存“最近播放”信息
        storage = PersistentStorage.getInstance(applicationContext)
    }

    /**
     * This is the code that causes UAMP to stop playing when swiping the activity away from
     * recents. The choice to do this is app specific. Some apps stop playback, while others allow
     * playback to continue and allow users to stop it with the notification.
     */
    //初始化本地存储，用于保存“最近播放”信息
    override fun onTaskRemoved(rootIntent: Intent) {
        super.onTaskRemoved(rootIntent)

        /**
         * By stopping playback, the player will transition to [Player.STATE_IDLE] triggering
         * [Player.EventListener.onPlayerStateChanged] to be called. This will cause the
         * notification to be hidden and trigger
         * [PlayerNotificationManager.NotificationListener.onNotificationCancelled] to be called.
         * The service will then remove itself as a foreground service, and will call
         * [stopSelf].
         */
        currentPlayer.stop()
    }

    //清理 MediaSession、协程、ExoPlayer 资源，避免内存泄漏
    override fun onDestroy() {
        mediaSession.run {
            isActive = false
            release()
        }

        // Cancel coroutines when the service is going away.
        serviceJob.cancel()

        // Free ExoPlayer resources.
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
    }

    /**
     * Returns the "root" media ID that the client should request to get the list of
     * [MediaItem]s to browse/play.
     */
    //返回可浏览的根媒体 ID。如果请求带有 EXTRA_RECENT 标志，则返回“最近播放”根；否则返回普通目录根。
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        val rootExtras = Bundle().apply {
            putBoolean(
                MEDIA_SEARCH_SUPPORTED,
                true
            )
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
        }

        /**
         * By default return the browsable root. Treat the EXTRA_RECENT flag as a special case
         * and return the recent root instead.语音助手从最近播放开始，其他从__/__根目录开始
         */
        val isRecentRequest = rootHints?.getBoolean(EXTRA_RECENT) ?: false
        val browserRootPath = if (isRecentRequest) UAMP_RECENT_ROOT else UAMP_BROWSABLE_ROOT
        val browserRoot = BrowserRoot(browserRootPath, rootExtras)
        return browserRoot
    }

    /**
     * Returns (via the [result] parameter) a list of [MediaItem]s that are child
     * items of the provided [parentMediaId]. See [BrowseTree] for more details on
     * how this is build/more details about the relationships.
     */
    //根据 parentMediaId 返回子项列表
    override fun onLoadChildren(
        parentMediaId: String,
        result: Result<List<MediaItem>>
    ) {

        /**
         * If the caller requests the recent root, return the most recently played song.
         * 如果是最近播放根，则从本地存储取一条记录
         */
        if (parentMediaId == UAMP_RECENT_ROOT) {
            result.sendResult(storage.loadRecentSong()?.let { song -> listOf(song) })
        } else {
            //否则等 mediaSource 就绪后，通过 browseTree 查找对应子节点并发送给客户端
            // If the media source is ready, the results will be set synchronously here.
            //那个musicsource在load的时候会变化state，如果是ready的话这里就会直接执行函数，否则会存进缓存，
            //mutableListOf<(Boolean) -> Unit>(),,state变成ready后会自动通知来执行
            val resultsSent = mediaSource.whenReady { successfullyInitialized ->
                if (successfullyInitialized) {
                    //operator fun get(mediaId: String) = mediaIdToChildren[mediaId]
                    //把MediaMetadataCompat转换成MediaItem
                    val children = browseTree[parentMediaId]?.map { item ->
                        MediaItem(item.description, item.flag)
                    }
                    result.sendResult(children)
                } else {
                    mediaSession.sendSessionEvent(NETWORK_FAILURE, null)
                    result.sendResult(null)
                }
            }

            // If the results are not ready, the service must "detach" the results before
            // the method returns. After the source is ready, the lambda above will run,
            // and the caller will be notified that the results are ready.
            //
            // See [MediaItemFragmentViewModel.subscriptionCallback] for how this is passed to the
            // UI/displayed in the [RecyclerView].
            if (!resultsSent) {
                //未就绪时调用 result.detach()，待加载完成后再回调
                result.detach()
            }
        }
    }


    /**
     * Load the supplied list of songs and the song to play into the current player.
     */
    private fun preparePlaylist(
        metadataList: List<MediaMetadataCompat>,
        itemToPlay: MediaMetadataCompat?,
        playWhenReady: Boolean,
        playbackStartPositionMs: Long
    ) {
        // Since the playlist was probably based on some ordering (such as tracks
        // on an album), find which window index to play first so that the song the
        // user actually wants to hear plays first.
        //找到起始窗口索引
        val initialWindowIndex = if (itemToPlay == null) 0 else metadataList.indexOf(itemToPlay)
        currentPlaylistItems = metadataList
//停止当前播放
        currentPlayer.playWhenReady = playWhenReady
        currentPlayer.stop()
        // Set playlist and prepare.
        //这里才是播放器真正开始播放的地方
        //设置新列表并准备播放
        currentPlayer.setMediaItems(
            metadataList.map { it.toMediaItem() }, initialWindowIndex, playbackStartPositionMs
        )
        currentPlayer.prepare()
    }

//保存当前播放项及进度到本地，用于“恢复播放”功能（在 onPlayerStateChanged 中调用）
    private fun saveRecentSongToStorage() {

        // Obtain the current song details *before* saving them on a separate thread, otherwise
        // the current player may have been unloaded by the time the save routine runs.
        if (currentPlaylistItems.isEmpty()) {
            return
        }
        val description = currentPlaylistItems[currentMediaItemIndex].description
        val position = currentPlayer.currentPosition

        serviceScope.launch {
            storage.saveRecentSong(
                description,
                position
            )
        }
    }


    /*
    当外部系统（如车载系统/通知栏）需要显示播放队列时
    车载系统通过 MediaSession 请求队列信息
    TimelineQueueNavigator 调用 getMediaDescription
    返回第N首歌的元数据（封面、标题等）
    车载系统渲染UI
    */
    private inner class UampQueueNavigator(
        mediaSession: MediaSessionCompat
    ) : TimelineQueueNavigator(mediaSession) {
        //实现 getMediaDescription，供外部（如车载）展示队列中第 N 首歌的元数据
        override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
            if (windowIndex < currentPlaylistItems.size) {//检查请求的索引是否有效
                //从 currentPlaylistItems 列表获取对应媒体项的 description
                return currentPlaylistItems[windowIndex].description
            }
            return MediaDescriptionCompat.Builder().build()//返回空的 MediaDescriptionCompat（防御性编程）
        }
    }

    private inner class UampPlaybackPreparer : MediaSessionConnector.PlaybackPreparer {
        /*
        负责响应系统发来的“从某媒体 ID 播放”或“从搜索播放”的请求，动态构建播放列表
        场景：用户点击专辑中的第3首歌
        MediaSession 收到 ACTION_PLAY_FROM_MEDIA_ID
        调用 onPrepareFromMediaId("song3", true, null)
        从 mediaSource 查找ID为"song3"的歌曲
        构建该歌曲所属专辑的完整列表（12首歌）
        通知播放器准备播放（从第3首开始）
        */

        /**
         * UAMP supports preparing (and playing) from search, as well as media ID, so those
         * capabilities are declared here.
         *
         * TODO: Add support for ACTION_PREPARE and ACTION_PLAY, which mean "prepare/play something".
         */
        override fun getSupportedPrepareActions(): Long =
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH

        override fun onPrepare(playWhenReady: Boolean) {
            val recentSong = storage.loadRecentSong() ?: return
            onPrepareFromMediaId(
                recentSong.mediaId!!,
                playWhenReady,
                recentSong.description.extras
            )
        }

        //通过媒体ID准备/播放
        override fun onPrepareFromMediaId(
            mediaId: String,
            playWhenReady: Boolean,
            extras: Bundle?
        ) {
            mediaSource.whenReady {
                val itemToPlay: MediaMetadataCompat? = mediaSource.find { item ->
                    item.id == mediaId
                }
                if (itemToPlay == null) {
                    Log.w(TAG, "Content not found: MediaID=$mediaId")
                    // TODO: Notify caller of the error.
                } else {

                    val playbackStartPositionMs =
                        extras?.getLong(
                            MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS,
                            C.TIME_UNSET
                        )
                            ?: C.TIME_UNSET

                    preparePlaylist(
                        buildPlaylist(itemToPlay),
                        itemToPlay,
                        playWhenReady,
                        playbackStartPositionMs
                    )
                }
            }
        }

        override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
            TODO("Not yet implemented")
        }


        override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) = Unit

        override fun onCommand(
            player: Player,
            command: String,
            extras: Bundle?,
            cb: ResultReceiver?
        ) = false

        /**
         * Builds a playlist based on a [MediaMetadataCompat].
         *
         * TODO: Support building a playlist by artist, genre, etc...
         *
         * @param item Item to base the playlist on.
         * @return a [List] of [MediaMetadataCompat] objects representing a playlist.
         */
        //按专辑排序生成列表
        private fun buildPlaylist(item: MediaMetadataCompat): List<MediaMetadataCompat> =
            mediaSource.filter { it.album == item.album }.sortedBy { it.trackNumber }
    }

    /**
     * Listen for notification events.
     */
    private inner class PlayerNotificationListener :
        PlayerNotificationManager.NotificationListener {
            //推送通知并启动前台服务
        override fun onNotificationPosted(
            notificationId: Int,
            notification: Notification,
            ongoing: Boolean
        ) {
            if (ongoing && !isForegroundService) {
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, this@MusicService.javaClass)
                )// 启动前台服务

                startForeground(notificationId, notification)// 将服务设置为前台服务并显示通知
                isForegroundService = true// 更新状态标志
            }
        }
//// 用户取消通知时停止服务
        override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
            stopForeground(true)// 停止前台服务状态
            isForegroundService = false// 重置状态标志
            stopSelf()// 停止服务
        }
    }

    /**
     * 管理通知栏的前台服务生命周期，监听 ExoPlayer 状态变化
     */
    private inner class PlayerEventListener : Player.Listener {
//播放/缓冲时更新通知和保存进度
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING,
                Player.STATE_READY -> {
                    notificationManager.showNotificationForPlayer(currentPlayer)
                    if (playbackState == Player.STATE_READY) {

                        // When playing/paused save the current media item in persistent
                        // storage so that playback can be resumed between device reboots.
                        // Search for "media resumption" for more information.
                        saveRecentSongToStorage()

                        if (!playWhenReady) {
                            // If playback is paused we remove the foreground state which allows the
                            // notification to be dismissed. An alternative would be to provide a
                            // "close" button in the notification which stops playback and clears
                            // the notification.
                            stopForeground(false)
                            isForegroundService = false
                        }
                    }
                }

                else -> {
                    notificationManager.hideNotification()
                }
            }
        }
//位置或曲目切换时更新 currentMediaItemIndex
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(EVENT_POSITION_DISCONTINUITY)
                || events.contains(EVENT_MEDIA_ITEM_TRANSITION)
                || events.contains(EVENT_PLAY_WHEN_READY_CHANGED)
            ) {
                currentMediaItemIndex = if (currentPlaylistItems.isNotEmpty()) {
                    constrainValue(
                        player.currentMediaItemIndex,
                        /* min= */ 0,
                        /* max= */ currentPlaylistItems.size - 1
                    )
                } else 0
            }
        }
//播放错误时在 UI 弹出提示
        override fun onPlayerError(error: PlaybackException) {
            var message = R.string.generic_error;
            Log.e(TAG, "Player error: " + error.errorCodeName + " (" + error.errorCode + ")");
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            ) {
                message = R.string.error_media_not_found;
            }
            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

/*
 * (Media) Session events
 */
const val NETWORK_FAILURE = "com.example.android.uamp.media.session.NETWORK_FAILURE"

/** Content styling constants */
private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
private const val CONTENT_STYLE_LIST = 1
private const val CONTENT_STYLE_GRID = 2

private const val UAMP_USER_AGENT = "uamp.next"

private const val TAG = "MusicService"