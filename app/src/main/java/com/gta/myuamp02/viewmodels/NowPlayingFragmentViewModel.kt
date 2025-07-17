package com.gta.myuamp02.viewmodels


import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.gta.common.common.MusicServiceConnection
import com.gta.common.media.extensions.*
import com.gta.common.common.EMPTY_PLAYBACK_STATE
import com.gta.common.common.NOTHING_PLAYING
import com.gta.myuamp02.R

class NowPlayingFragmentViewModel(
    private val app: Application,
    musicServiceConnection: MusicServiceConnection
) : AndroidViewModel(app) {

    data class NowPlayingMetadata(
        val id: String,
        val albumArtUri: Uri,
        val title: String?,
        val subtitle: String?,
        val duration: String
    ) {
        companion object {
            /**
             * Utility method to convert milliseconds to a display of minutes and seconds
             */
            fun timestampToMSS(context: Context, position: Long): String {
                val totalSeconds = Math.floor(position / 1E3).toInt()
                val minutes = totalSeconds / 60
                val remainingSeconds = totalSeconds - (minutes * 60)
                return if (position < 0) context.getString(R.string.duration_unknown)
                else context.getString(R.string.duration_format).format(minutes, remainingSeconds)
            }
        }
    }

    private var playbackState: PlaybackStateCompat = EMPTY_PLAYBACK_STATE
    val mediaMetadata = MutableLiveData<NowPlayingMetadata>()
    val mediaPosition = MutableLiveData<Long>().apply {
        postValue(0L)
    }
    val mediaButtonRes = MutableLiveData<Int>().apply {
        postValue(R.drawable.ic_album_black_24dp)
    }
    private var updatePosition = true
    private val handler = Handler.createAsync(Looper.getMainLooper())

    private val playbackStateObserver = Observer<PlaybackStateCompat> {
        playbackState = it ?: EMPTY_PLAYBACK_STATE
        val metadata = musicServiceConnection.nowPlaying.value ?: NOTHING_PLAYING
        updateState(playbackState, metadata)
    }
    private val mediaMetadataObserver = Observer<MediaMetadataCompat> {
        updateState(playbackState, it)
    }
    //始终让中间层暴露的livedata驱动UI更新
    private val musicServiceConnection = musicServiceConnection.also {
        it.playbackState.observeForever(playbackStateObserver)
        it.nowPlaying.observeForever(mediaMetadataObserver)
        checkPlaybackPosition()
    }
    //这个页面必须实时更新进度到mediaPosition上
    //做一个 “定时轮询”，不断把播放器的位置刷新到 UI 层暴露的 LiveData 上，
    // 让界面上进度条、时间显示等始终和播放器进度保持同步。
    //这个消息会被“定时” —— 只有当系统时间走到“现在 + delayMillis”之后，
    // Looper 才会把它从队列里取出来，然后在对应线程（这里是主线程）中执行你的 Runnable。
    private fun checkPlaybackPosition(): Boolean = handler.postDelayed({
        val currPosition = playbackState.currentPlayBackPosition
        if (mediaPosition.value != currPosition)
            mediaPosition.postValue(currPosition)
        if (updatePosition)
            checkPlaybackPosition()
    }, POSITION_UPDATE_INTERVAL_MILLIS)
    override fun onCleared() {
        super.onCleared()

        // Remove the permanent observers from the MusicServiceConnection.
        musicServiceConnection.playbackState.removeObserver(playbackStateObserver)
        musicServiceConnection.nowPlaying.removeObserver(mediaMetadataObserver)

        // Stop updating the position
        updatePosition = false
    }
    //UI更新方法，主要是更新播放页的当前状态
    private fun updateState(
        playbackState: PlaybackStateCompat,
        mediaMetadata: MediaMetadataCompat
    ) {

        // Only update media item once we have duration available
        if (mediaMetadata.duration != 0L && mediaMetadata.id != null) {
            val nowPlayingMetadata = NowPlayingMetadata(
                mediaMetadata.id!!,
                mediaMetadata.albumArtUri,
                mediaMetadata.title?.trim(),
                mediaMetadata.displaySubtitle?.trim(),
                NowPlayingMetadata.timestampToMSS(app, mediaMetadata.duration)
            )
            this.mediaMetadata.postValue(nowPlayingMetadata)
        }

        // Update the media button resource ID
        mediaButtonRes.postValue(
            when (playbackState.isPlaying) {
                true -> R.drawable.ic_pause_black_24dp
                else -> R.drawable.ic_play_arrow_black_24dp
            }
        )
    }
    class Factory(
        private val app: Application,
        private val musicServiceConnection: MusicServiceConnection
    ) : ViewModelProvider.NewInstanceFactory() {
        @Suppress("unchecked_cast")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NowPlayingFragmentViewModel(app, musicServiceConnection) as T
        }
    }
}
private const val TAG = "NowPlayingFragmentVM"
private const val POSITION_UPDATE_INTERVAL_MILLIS = 100L