package com.gta.myuamp02.viewmodels


import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaBrowserCompat.SubscriptionCallback
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import com.gta.common.common.MusicServiceConnection
import com.gta.common.media.extensions.id
import com.gta.common.media.extensions.isPlaying
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.gta.common.common.EMPTY_PLAYBACK_STATE
import com.gta.common.common.NOTHING_PLAYING
import com.gta.myuamp02.MediaItemData
import com.gta.myuamp02.R

/**
 * 依赖于 MusicServiceConnection 来与音乐服务交互
 */
class MediaItemFragmentViewModel(
    private val mediaId: String,
    musicServiceConnection: MusicServiceConnection
) :ViewModel() {
    private val _mediaItems = MutableLiveData<List<MediaItemData>>()
    val mediaItems: LiveData<List<MediaItemData>> = _mediaItems
    val networkError = musicServiceConnection.networkFailure.map { it }

    //当 MediaBrowser 的 subscribe(mediaId, ...) 返回子节点列表时，会回调 onChildrenLoaded
    private val subscriptionCallback = object : SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: List<MediaItem>) {
            //遍历 children，将系统的 MediaItem 转成自定义的 MediaItemData
            val itemsList = children.map { child ->
                val subtitle = child.description.subtitle ?: ""
                MediaItemData(
                    child.mediaId!!,
                    child.description.title.toString(),
                    subtitle.toString(),
                    child.description.iconUri!!,
                    child.isBrowsable,
                    getResourceForMediaId(child.mediaId!!)
                )
            }
            //将转换后的列表 postValue 到 _mediaItems，触发 UI 更新
            _mediaItems.postValue(itemsList)
        }
    }
    //播放，暂停，缓冲，播放状态的回调，播放暂停的状态要影响UI的显示
    private val playbackStateObserver = Observer<PlaybackStateCompat> {
        //获取当前播放状态 playbackState
        val playbackState = it
        //从后台获取当前播放的媒体元数据 metadata
        val metadata = musicServiceConnection.nowPlaying.value ?: NOTHING_PLAYING
        //这个mediaItems是UI展示的数据列表，因为我们要在正在播放的歌曲上显示播放/暂停图标，所以需要更新这个列表。
        //如果 metadata 中携带有效的 MEDIA_ID，则调用 updateState 生成新的 MediaItemData 列表，更新播放/暂停图标
        if (metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) != null) {
            _mediaItems.postValue(updateState(playbackState, metadata))
        }
    }
    //类似 playbackStateObserver，但针对 nowPlaying（当前播放元数据）的变化。每当歌曲切换，也会重新 updateState，保证图标跟随切换。
    private val mediaMetadataObserver = Observer<MediaMetadataCompat> {
        val playbackState = musicServiceConnection.playbackState.value ?: EMPTY_PLAYBACK_STATE
        val metadata = it
        if (metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) != null) {
            _mediaItems.postValue(updateState(playbackState, metadata))
        }
    }
    //在 ViewModel 初始化时，马上调用 subscribe(mediaId, subscriptionCallback)，开始加载该 mediaId 下的子项
    private val musicServiceConnection = musicServiceConnection.also {
        it.subscribe(mediaId, subscriptionCallback)
    //对播放状态和当前播放元数据注册永久观察者，以便随时更新 UI
        it.playbackState.observeForever(playbackStateObserver)
        it.nowPlaying.observeForever(mediaMetadataObserver)
    }

    private fun getResourceForMediaId(mediaId: String): Int {
        val isActive = mediaId == musicServiceConnection.nowPlaying.value?.id
        val isPlaying = musicServiceConnection.playbackState.value?.isPlaying ?: false
        return when {
            !isActive -> NO_RES
            isPlaying -> R.drawable.ic_pause_black_24dp
            else -> R.drawable.ic_play_arrow_black_24dp
        }
    }
    //根据最新的播放状态和元数据，产生新的列表：
    private fun updateState(
        playbackState: PlaybackStateCompat,
        mediaMetadata: MediaMetadataCompat
    ): List<MediaItemData> {
        //表里的每个元素如果是当前播放项就带上相应的播放/暂停图标，否则不带图标。
        val newResId = when (playbackState.isPlaying) {
            true -> R.drawable.ic_pause_black_24dp
            else -> R.drawable.ic_play_arrow_black_24dp
        }

        return mediaItems.value?.map {
            val useResId = if (it.mediaId == mediaMetadata.id) newResId else NO_RES
            it.copy(playbackRes = useResId)
        } ?: emptyList()
    }
    override fun onCleared() {
        super.onCleared()

        // 取消对 playbackState 和 nowPlaying 的永久观察，避免内存泄漏。
        musicServiceConnection.playbackState.removeObserver(playbackStateObserver)
        musicServiceConnection.nowPlaying.removeObserver(mediaMetadataObserver)

        //取消对 mediaId 的订阅，停止接收媒体子项数据。
        musicServiceConnection.unsubscribe(mediaId, subscriptionCallback)
    }

    class Factory(
        private val mediaId: String,
        private val musicServiceConnection: MusicServiceConnection
    ) : ViewModelProvider.NewInstanceFactory() {

        @Suppress("unchecked_cast")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MediaItemFragmentViewModel(mediaId, musicServiceConnection) as T
        }
    }
}

private const val TAG = "MediaItemFragmentVM"
private const val NO_RES = 0