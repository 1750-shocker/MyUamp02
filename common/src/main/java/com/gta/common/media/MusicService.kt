package com.gta.common.media


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
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaBrowserServiceCompat.BrowserRoot.EXTRA_RECENT
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Player.EVENT_MEDIA_ITEM_TRANSITION
import com.google.android.exoplayer2.Player.EVENT_PLAY_WHEN_READY_CHANGED
import com.google.android.exoplayer2.Player.EVENT_POSITION_DISCONTINUITY
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
import androidx.core.net.toUri

open class MusicService : MediaBrowserServiceCompat() {

    private lateinit var mediaSource: MusicSource

    //协程标准用法，没有用Android包里的扩展函数啥的
    //使用协程管理异步任务，SupervisorJob 确保子任务失败不会取消整个服务
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    //将播放状态、元数据暴露给系统控制（锁屏、通知、外部设备）
    protected lateinit var mediaSession: MediaSessionCompat

    //连接 ExoPlayer 与 MediaSession 的桥梁，将系统控制指令转发给播放器!!!关键，很多流程细节隐藏于此
    protected lateinit var mediaSessionConnector: MediaSessionConnector

    //保存当前播放列表的元数据和正在播放的索引，方便实现“下一首/上一首”及恢复播放位置
    private var currentPlaylistItems: List<MediaMetadataCompat> = emptyList()
    private var currentMediaItemIndex: Int = 0


    /**
     * 这里必须是懒加载，让我看看onLoadChildren
     */
    //懒加载构建一个树形的媒体浏览结构，根据 mediaSource 构造分层的浏览目录
    private val browseTree: BrowseTree by lazy {
        BrowseTree(applicationContext, mediaSource)
    }

    //标记当前服务是否已经以前台服务形式运行，用于管理通知生命周期
    private var isForegroundService = false

    private val remoteJsonSource: Uri =
        "https://storage.googleapis.com/uamp/catalog.json".toUri()

    //配置音频属性，保证在其他媒体（导航提示、通知音）和音乐之间的合理混音与优先级
    private val uAmpAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    //ExoPlayer 事件监听器实例，用于同步媒体状态到 MediaSession 并处理错误和播放进度
    private val playerListener = PlayerEventListener()

    //延迟初始化 ExoPlayer，并绑定音频属性、耳机拔出自动暂停、事件监听
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build().apply {
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

        // 创建 MediaSession 并激活，
        mediaSession = MediaSessionCompat(this, "MusicService")
            .apply {
                setSessionActivity(sessionActivityPendingIntent)
                isActive = true//向整个 Android 系统宣告：“我是一个活跃的媒体应用，请把媒体控制权（如蓝牙按钮、系统通知栏控制）交给我。”
            }
        //公开 sessionToken 供外部（MediaController）连接
        sessionToken = mediaSession.sessionToken


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
        //收到 `playFromMediaId` 等准备指令时，应该把任务交给谁来处理
        mediaSessionConnector.setPlaybackPreparer(UampPlaybackPreparer())
        mediaSessionConnector.setQueueNavigator(UampQueueNavigator(mediaSession))
        mediaSessionConnector.setPlayer(exoPlayer)
    }

    /**
     *从最近任务中移除应用时停止UAMP的播放。
     *  是否这样做是应用特定的选择，有些应用会停止播放，
     *  而有些则允许播放继续并通过通知让用户停止。
     */
    //初始化本地存储，用于保存“最近播放”信息
    override fun onTaskRemoved(rootIntent: Intent) {
        super.onTaskRemoved(rootIntent)

        /**
         * 通过停止播放，播放器将转为[Player.STATE_IDLE]状态，
         * 这会触发[Player.EventListener.onPlayerStateChanged]被调用。
         * 这将导致通知被隐藏，并触发
         * [PlayerNotificationManager.NotificationListener.onNotificationCancelled]被调用。
         * 然后服务将自行移除前台服务状态，并调用[stopSelf]。
         */
        exoPlayer.stop()
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

    //返回可浏览的根媒体 ID。如果请求带有 EXTRA_RECENT 标志，则返回“最近播放”根；否则返回普通目录根。
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        val rootExtras = Bundle().apply {
            putBoolean(
                MEDIA_SEARCH_SUPPORTED,
                false
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

    //根据 parentMediaId 返回子项列表
    override fun onLoadChildren(
        parentMediaId: String,
        result: Result<List<MediaItem>>
    ) {

        /**
         * If the caller requests the recent root, return the most recently played song.
         * 如果是最近播放根，则从本地存储取一条记录
         */
            //否则等 mediaSource 就绪后，通过 browseTree 查找对应子节点并发送给客户端
            //那个musicSource在load的时候会变化state，如果是ready的话这里就会直接执行函数，否则会存进缓存:
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

        // 如果结果尚未就绪，服务必须在方法返回前"分离"结果。
        // 当数据源就绪后，上面的lambda表达式将会执行，
        // 调用方会收到结果已就绪的通知。
        //
        // 关于如何传递到UI并在[RecyclerView]中显示，
        // 请参考[MediaItemFragmentViewModel.subscriptionCallback]的实现。
            if (!resultsSent) {
                //未就绪时调用 result.detach()，待加载完成后再回调
                result.detach()
            }

    }


    /**
     * 加载歌曲和歌曲列表给播放器
     */
    private fun preparePlaylist(
        metadataList: List<MediaMetadataCompat>,
        itemToPlay: MediaMetadataCompat?,
        playWhenReady: Boolean,
        playbackStartPositionMs: Long
    ) {
        //找到歌曲所在专辑的索引
        val initialWindowIndex = if (itemToPlay == null) 0 else metadataList.indexOf(itemToPlay)
        currentPlaylistItems = metadataList
        //停止当前播放
        exoPlayer.playWhenReady = playWhenReady
        exoPlayer.stop()
        //这里才是播放器真正开始播放的地方
        //设置新列表并准备播放
        exoPlayer.setMediaItems(
            metadataList.map { it.toMediaItem() }, initialWindowIndex, playbackStartPositionMs
        )
        exoPlayer.prepare()
    }

    /*
    将 `MediaMetadataCompat` 列表转换为 `MediaSessionCompat.QueueItem` 列表。
    调用 `mediaSession.setQueue(queueItemList)`。
    调用 `mediaSession.setQueueTitle(title)`。
    当播放到队列中下一项时，你需要手动调用 `mediaSession.setActiveQueueItemId(id)` 来告诉系统当前播放的是队列中的哪一项。
     负责响应 `onSkipToNext`、`onSkipToPrevious`、`onSkipToQueueItem` 等队列相关的命令。
     `MediaSessionConnector` 会利用这个 `QueueNavigator` 来自动更新播放器的播放列表，
     并同步更新 `MediaSession` 的队列状态，包括自动设置 active item。
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

    //一旦你的 `PlaybackPreparer` 把播放列表交给了播放器并调用了 `prepare()`，后续的一切
    // （如自动播放下一首、缓冲、暂停）都是播放器内部的行为。`Player.Listener` 就是你用来监控这些内部行为的工具。
    private inner class UampPlaybackPreparer : MediaSessionConnector.PlaybackPreparer {
        /*
        `PlaybackPreparer` 的核心职责是：覆盖MediaSessionCompat.Callback 的一堆回调
        场景：用户点击专辑中的第3首歌
        MediaSession 收到 ACTION_PLAY_FROM_MEDIA_ID
        调用 onPrepareFromMediaId("song3", true, null)
        从 mediaSource 查找ID为"song3"的歌曲
        构建该歌曲所属专辑的完整列表（12首歌）
        通知播放器准备播放（从第3首开始）
        */
        override fun getSupportedPrepareActions(): Long =
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH

        /**
         * - **触发时机**：当客户端只调用了 `play()` 或 `prepare()`，没有提供具体的 `mediaId`mediaId` 时。例如，用户在蓝牙设备上按下了“播放按钮。
         * - **你的任务**：决定在这种“通用播放”请求下应该播放什么。通常是恢复上次的播放列表，或者播放一个默认/推荐的歌单。这里原来是从本地缓存读取“最近播放”
         */
        override fun onPrepare(playWhenReady: Boolean) {
            onPrepareFromMediaId(
                "wake_up_01",
                playWhenReady,
                null
            )
        }

        /**
         * **触发时机**：当客户端调用 `MediaControllerCompat.getTransportControls().playFromMediaId(...)` 或 `prepareFromMediaId(...)` 时
         * 根据传入的 `mediaId`，找出这首歌所在的完整播放列表（例如，它所属的专辑或歌单）
         * 调用我们之前分析过的 `preparePlaylist` 方法
         */
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
                    //C.TIME_UNSET代表一个“未设置”或“无效”的时间值。播放器看到这个值，就会明白应该从头开始播放。
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

        /**
         * - **触发时机**：响应语音搜索等搜索请求。
         * - **你的**你的任务**：根据搜索查询词 `query`，生成一个播放列表并准备播放。
         */
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

        //按专辑排序生成列表
        private fun buildPlaylist(item: MediaMetadataCompat): List<MediaMetadataCompat> =
            mediaSource.filter { it.album == item.album }.sortedBy { it.trackNumber }
    }


    /**
     * 管理通知栏的前台服务生命周期，监听 ExoPlayer 状态变化
     * 一旦你的 `PlaybackPreparer` 把播放列表交给了播放器并调用了 `prepare()`，
     * 后续的一切（如自动播放下一首、缓冲、暂停）都是播放器内部的行为。`Player.Listener` 就是你用来监控这些内部行为的工具。
     */
    private inner class PlayerEventListener : Player.Listener {
        /**
         * onMediaItemTransition(MediaItem? mediaItem, int reason)
         * 触发时机：当播放器切换到播放列表中的另一首歌曲时。这包括自动播放完毕切换、
         * 用户手动调用 `skipToNext`skipToNext()` 等所有情况。
         * 这是**更新媒体元数据 (`MediaMetadataCompat`) 的最佳位置**。从 `mediaItem` 中获取当前新歌曲的 `mediaId`。
         * 根据 `mediaId` 找到完整的 `MediaMetadataCompat` 对象。
         * 调用 `mediaSession.setMetadata(...)`，将新的元数据广播出去。这样，UI
         */
        //手动：当播放状态改变时，根据这些回调，构建一个 `PlaybackStateCompat` 对象，
        // 然后调用 `mediaSession.setPlaybackState(...)` 来更新播放状态。这会同步更新通知栏和所有客户端的播放/暂停按钮状态

        //playWhenReady 表示播放器是否准备好播放并继续播放；playbackState 是当前的播放器状态（如播放中、暂停、缓冲等）。开发者可以根据这些信息来更新 UI 或进行其他操作。
        @Deprecated("Deprecated in Java")
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING,
                Player.STATE_READY -> {
                    if (playbackState == Player.STATE_READY) {

                        if (!playWhenReady) {
                            // 如果播放暂停，我们移除前台状态，这将允许通知被关闭。
                            // 另一种方案是在通知中提供一个"关闭"按钮，
                            // 用于停止播放并清除通知。
                            stopForeground(STOP_FOREGROUND_DETACH)
                            isForegroundService = false
                        }
                    }
                }

                else -> {}
            }
        }

        //在 onEvents 中，多个事件可以同时发生，因此可以一次性捕捉并响应多个变化，而无需为每个事件分别编写单独的回调方法
        //位置或曲目切换时更新 currentMediaItemIndex(当前播放歌曲在播放列表中的位置)
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(EVENT_POSITION_DISCONTINUITY)
                || events.contains(EVENT_MEDIA_ITEM_TRANSITION)//媒体项发生切换
                || events.contains(EVENT_PLAY_WHEN_READY_CHANGED)
            ) {
                currentMediaItemIndex = if (currentPlaylistItems.isNotEmpty()) {
                    //通过 constrainValue 确保索引值始终在有效范围内（0 ≤ index ≤ 列表长度-1），避免数组越界异常
                    constrainValue(
                        player.currentMediaItemIndex,
                        /* min= */ 0,
                        /* max= */ currentPlaylistItems.size - 1
                    )
                } else 0
            }
        }

        //当播放器遇到错误时，该方法会被调用。PlaybackException 包含了错误的详细信息，开发者可以根据错误类型进行处理或展示提示信息
        override fun onPlayerError(error: PlaybackException) {
            var message = R.string.generic_error
            Log.e(TAG, "Player error: " + error.errorCodeName + " (" + error.errorCode + ")")
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            ) {
                message = R.string.error_media_not_found
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

//private const val UAMP_USER_AGENT = "uamp.next"
const val MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS = "playback_start_position_ms"
private const val TAG = "MusicService"