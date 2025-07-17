package com.gta.myuamp02.utils


import android.app.Application
import android.content.ComponentName
import android.content.Context
import com.gta.common.media.MusicService
import com.gta.common.common.MusicServiceConnection
import com.gta.myuamp02.viewmodels.MainActivityViewModel
import com.gta.myuamp02.viewmodels.MediaItemFragmentViewModel
import com.gta.myuamp02.viewmodels.NowPlayingFragmentViewModel


/**
 * Static methods used to inject classes needed for various Activities and Fragments.
 */
object InjectorUtils {
    private fun provideMusicServiceConnection(context: Context): MusicServiceConnection {
        return MusicServiceConnection.getInstance(
            context,
            ComponentName(context, MusicService::class.java)
        )
    }

    fun provideMainActivityViewModel(context: Context): MainActivityViewModel.Factory {
        val applicationContext = context.applicationContext
        val musicServiceConnection = provideMusicServiceConnection(applicationContext)
        return MainActivityViewModel.Factory(musicServiceConnection)
    }

    fun provideMediaItemFragmentViewModel(context: Context, mediaId: String)
            : MediaItemFragmentViewModel.Factory {
        val applicationContext = context.applicationContext
        val musicServiceConnection = provideMusicServiceConnection(applicationContext)
        return MediaItemFragmentViewModel.Factory(mediaId, musicServiceConnection)
    }

    fun provideNowPlayingFragmentViewModel(context: Context)
            : NowPlayingFragmentViewModel.Factory {
        val applicationContext = context.applicationContext
        val musicServiceConnection = provideMusicServiceConnection(applicationContext)
        return NowPlayingFragmentViewModel.Factory(
            applicationContext as Application, musicServiceConnection
        )
    }
}