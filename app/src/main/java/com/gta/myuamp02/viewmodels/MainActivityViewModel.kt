package com.gta.myuamp02.viewmodels


import android.util.Log
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.map
import com.gta.common.common.MusicServiceConnection
import com.gta.common.media.extensions.id
import com.gta.common.media.extensions.isPlayEnabled
import com.gta.common.media.extensions.isPlaying
import com.gta.common.media.extensions.isPrepared
import com.gta.myuamp02.MediaItemData
import com.gta.myuamp02.fragments.NowPlayingFragment
import com.gta.myuamp02.utils.Event

//直接把Repository传进来,疯狂用用用
class MainActivityViewModel(
    private val musicServiceConnection: MusicServiceConnection
) : ViewModel() {
    //观察 musicServiceConnection.isConnected，如果连接成功，则返回 rootMediaId，否则返回 null
    val rootMediaId: LiveData<String?> =
        musicServiceConnection.isConnected.map { isConnected ->
            if (isConnected) {
                musicServiceConnection.rootMediaId
            } else {
                null
            }
        }

    //Event 包装器模式来解决 LiveData 的"粘性事件"问题，确保导航事件不会被重复消费。
    val navigateToMediaItem: LiveData<Event<String>> get() = _navigateToMediaItem
    private val _navigateToMediaItem = MutableLiveData<Event<String>>()

    val navigateToFragment: LiveData<Event<FragmentNavigationRequest>> get() = _navigateToFragment
    private val _navigateToFragment = MutableLiveData<Event<FragmentNavigationRequest>>()
//点击列表项时触发的方法
    fun mediaItemClicked(clickedItem: MediaItemData) {
        if (clickedItem.browsable) {
            browseToItem(clickedItem)
        } else {
            playMedia(clickedItem, pauseAllowed = false)
            showFragment(NowPlayingFragment.newInstance())
        }
    }
//触发界面上的导航操作
    fun showFragment(fragment: Fragment, backStack: Boolean = true, tag: String? = null) {
        _navigateToFragment.value = Event(FragmentNavigationRequest(fragment, backStack, tag))
    }
//更新 LiveData，通知界面导航到该媒体项
    private fun browseToItem(mediaItem: MediaItemData) {
        _navigateToMediaItem.value = Event(mediaItem.mediaId)
    }

    /**
     * 点击列表项时触发的方法, 播放指定的媒体项，这个方法涉及与后台交互
     * 和下面一个方法的行为基本是一样的，只是参数不同，但是都是取mediaId来播
     */
    fun playMedia(mediaItem: MediaItemData, pauseAllowed: Boolean = true) {
        val nowPlaying = musicServiceConnection.nowPlaying.value
        val transportControls = musicServiceConnection.transportControls
        val isPrepared = musicServiceConnection.playbackState.value?.isPrepared ?: false
        //如果当前的媒体已经准备好，并且点击的媒体是正在播放的媒体，则根据播放状态决定是暂停还是播放
        if (isPrepared && mediaItem.mediaId == nowPlaying?.id) {
            musicServiceConnection.playbackState.value?.let { playbackState ->
                when {
                    playbackState.isPlaying ->
                        if (pauseAllowed) transportControls.pause() else Unit

                    playbackState.isPlayEnabled ->
                        transportControls.play()

                    else -> {
                        Log.w(
                            TAG, "Playable item clicked but neither play nor pause are enabled!" +
                                    " (mediaId=${mediaItem.mediaId})"
                        )
                    }
                }
            }
        } else {
            //如果当前媒体没有准备好，或点击的媒体不是正在播放的媒体则调用 playFromMediaId 方法播放指定的媒体项
            transportControls.playFromMediaId(mediaItem.mediaId, null)
        }
    }

    /**
     * 点击播放按钮，根据当前正在播放的媒体 ID 播放或暂停音乐
     */
    fun playMediaId(mediaId: String) {
        val nowPlaying = musicServiceConnection.nowPlaying.value
        val transportControls = musicServiceConnection.transportControls
        val isPrepared = musicServiceConnection.playbackState.value?.isPrepared ?: false

        if (isPrepared && mediaId == nowPlaying?.id) {
            musicServiceConnection.playbackState.value?.let { playbackState ->
                when {
                    playbackState.isPlaying -> transportControls.pause()
                    playbackState.isPlayEnabled -> transportControls.play()
                    else -> {
                        Log.w(
                            TAG, "Playable item clicked but neither play nor pause are enabled!" +
                                    " (mediaId=$mediaId)"
                        )
                    }
                }
            }
        } else {
            //如果当前媒体没有准备好，则调用 playFromMediaId 方法播放指定的媒体项
            transportControls.playFromMediaId(mediaId, null)
        }
    }

    /**
     * 播放上一曲
     */
    fun prevMedia() {
        musicServiceConnection.transportControls.skipToPrevious()
    }

    /**
     * 播放下一曲
     */
    fun nextMedia() {
        musicServiceConnection.transportControls.skipToNext()
    }

    class Factory(
        private val musicServiceConnection: MusicServiceConnection
    ) : ViewModelProvider.NewInstanceFactory() {
        @Suppress("unchecked_cast")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainActivityViewModel(musicServiceConnection) as T
        }
    }
}


/**
 * Helper class used to pass fragment navigation requests between MainActivity
 * and its corresponding ViewModel.
 * 用于传递 Fragment 导航请求
 */
data class FragmentNavigationRequest(
    val fragment: Fragment,
    val backStack: Boolean = false,
    val tag: String? = null
)

private const val TAG = "MainActivitytVM"