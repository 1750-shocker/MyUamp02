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

class MediaItemFragmentViewModel(
    private val mediaId: String,
    musicServiceConnection: MusicServiceConnection
) :ViewModel() {
    private val _mediaItems = MutableLiveData<List<MediaItemData>>()
    val mediaItems: LiveData<List<MediaItemData>> = _mediaItems
    val networkError = musicServiceConnection.networkFailure.map { it }

    private val subscriptionCallback = object : SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: List<MediaItem>) {
            //将每个 MediaItem 转换为自定义的 MediaItemData
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
            _mediaItems.postValue(itemsList)
        }
    }
    //播放，暂停，缓冲，播放状态的回调，播放暂停的状态要影响UI的显示
    private val playbackStateObserver = Observer<PlaybackStateCompat> {
        //当前播放状态
        val playbackState = it ?: EMPTY_PLAYBACK_STATE
        //当前正在播放的歌曲元数据
        val metadata = musicServiceConnection.nowPlaying.value ?: NOTHING_PLAYING
        //这个mediaItems是UI展示的数据列表，因为我们要在正在播放的歌曲上显示播放/暂停图标，所以需要更新这个列表。
        if (metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) != null) {
            _mediaItems.postValue(updateState(playbackState, metadata))
        }
    }
    //切换歌曲后播放状态图标也应该转移
    private val mediaMetadataObserver = Observer<MediaMetadataCompat> {
        val playbackState = musicServiceConnection.playbackState.value ?: EMPTY_PLAYBACK_STATE
        val metadata = it ?: NOTHING_PLAYING
        if (metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) != null) {
            _mediaItems.postValue(updateState(playbackState, metadata))
        }
    }
    private val musicServiceConnection = musicServiceConnection.also {
        it.subscribe(mediaId, subscriptionCallback)

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

        // Remove the permanent observers from the MusicServiceConnection.
        musicServiceConnection.playbackState.removeObserver(playbackStateObserver)
        musicServiceConnection.nowPlaying.removeObserver(mediaMetadataObserver)

        // And then, finally, unsubscribe the media ID that was being watched.
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