package com.gta.common.media


import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.gta.common.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

//通知渠道 ID，用于 Android 8.0+ 创建通知渠道
const val NOW_PLAYING_CHANNEL_ID = "com.example.android.uamp.media.NOW_PLAYING"
//通知 ID，用于同一渠道下更新/隐藏通知
const val NOW_PLAYING_NOTIFICATION_ID = 0xb339 // Arbitrary number used to identify our notification

//模块内可见，其他模块无法直接访问
internal class UampNotificationManager(
    private val context: Context,
    sessionToken: MediaSessionCompat.Token,
    notificationListener: PlayerNotificationManager.NotificationListener
) {

    private var player: Player? = null
    //创建协程作用域，用于主线程异步操作（加载封面图等
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val notificationManager: PlayerNotificationManager
    private val platformNotificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        //使用 sessionToken 创建 MediaControllerCompat，通过它可以获取当前媒体会话的元数据、控制意图等
        val mediaController = MediaControllerCompat(context, sessionToken)
        Log.d("wzhhh", "context: $context, sessionToken: $sessionToken, notificationListener: $notificationListener")
        //指定 context、通知 ID、渠道 ID
        val builder = PlayerNotificationManager.Builder(context, NOW_PLAYING_NOTIFICATION_ID, NOW_PLAYING_CHANNEL_ID)
        with (builder) {
            //提供封面、标题、子标题、跳转 PendingIntent 的适配器
            setMediaDescriptionAdapter(DescriptionAdapter(mediaController))
            //当通知被用户滑掉或点击时的回调
            setNotificationListener(notificationListener)
            //渠道的名称和描述资源，以便系统通知设置页展示
            setChannelNameResourceId(R.string.notification_channel)
            setChannelDescriptionResourceId(R.string.notification_channel_description)
        }
        notificationManager = builder.build()
        //关联 MediaSession Token，使通知上的播放/暂停等操作真正生效
        notificationManager.setMediaSessionToken(sessionToken)
        //设置小图标、禁用“快退”和“快进”按钮（根据业务需要关闭多余操作）
        notificationManager.setSmallIcon(R.drawable.ic_notification)
        notificationManager.setUseRewindAction(false)
        notificationManager.setUseFastForwardAction(false)
    }
//解绑播放器，通知被移除
    fun hideNotification() {
        notificationManager.setPlayer(null)
    }
//将 ExoPlayer 传入通知管理器，通知会跟随播放器状态自动更新
    fun showNotificationForPlayer(player: Player){
        notificationManager.setPlayer(player)
    }
//负责提供通知上显示的标题、子标题、点击跳转意图和大图标
    private inner class DescriptionAdapter(private val controller: MediaControllerCompat) :
        PlayerNotificationManager.MediaDescriptionAdapter {
//缓存当前的封面 URI 和已下载的 Bitmap，避免重复加载
        var currentIconUri: Uri? = null
        var currentBitmap: Bitmap? = null
//返回 MediaSession 中的 sessionActivity，即点击通知后启动到的 Activity
        override fun createCurrentContentIntent(player: Player): PendingIntent? =
            controller.sessionActivity

        override fun getCurrentContentText(player: Player) =
            controller.metadata.description.subtitle.toString()

        override fun getCurrentContentTitle(player: Player) =
            controller.metadata.description.title.toString()

        override fun getCurrentLargeIcon(
            player: Player,
            callback: PlayerNotificationManager.BitmapCallback
        ): Bitmap? {
            val iconUri = controller.metadata.description.iconUri
            return if (currentIconUri != iconUri || currentBitmap == null) {

                // Cache the bitmap for the current song so that successive calls to
                // `getCurrentLargeIcon` don't cause the bitmap to be recreated.
                currentIconUri = iconUri
                serviceScope.launch {
                    currentBitmap = iconUri?.let {
                        resolveUriAsBitmap(it)
                    }
                    currentBitmap?.let { callback.onBitmap(it) }
                }
                null
            } else {
                currentBitmap
            }
        }
//挂在 IO 线程上同步下载并返回 Bitmap
        private suspend fun resolveUriAsBitmap(uri: Uri): Bitmap? {
            return withContext(Dispatchers.IO) {
                // Block on downloading artwork.
                Glide.with(context).applyDefaultRequestOptions(glideOptions)
                    .asBitmap()
                    .load(uri)
                    .submit(NOTIFICATION_LARGE_ICON_SIZE, NOTIFICATION_LARGE_ICON_SIZE)
                    .get()
            }
        }
    }
}

//大图标的固定尺寸
const val NOTIFICATION_LARGE_ICON_SIZE = 144 // px
//Glide 加载选项，指定占位图和缓存策略
private val glideOptions = RequestOptions()
    .fallback(R.drawable.default_art)
    .diskCacheStrategy(DiskCacheStrategy.DATA)

private const val MODE_READ_ONLY = "r"