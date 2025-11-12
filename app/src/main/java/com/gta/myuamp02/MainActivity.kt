package com.gta.myuamp02

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import com.gta.myuamp02.fragments.MediaItemFragment
import com.gta.myuamp02.utils.InjectorUtils
import com.gta.myuamp02.viewmodels.MainActivityViewModel

//通过 ViewModel 管理 UI 状态，并监听相关的 LiveData 来更新界面。
class MainActivity : AppCompatActivity() {
    private val viewModel by viewModels<MainActivityViewModel> {
        InjectorUtils.provideMainActivityViewModel(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        //窗口插图的监听器。会调整视图的填充，以确保界面不被系统的状态栏、导航栏遮挡，适应边缘显示。
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.fragmentContainer)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        //设置音量控制流为音乐流，确保音量调节时影响的是音乐音量，而不是其他类型的音量（如铃声或通知音量）
        volumeControlStream = AudioManager.STREAM_MUSIC

        //根据livedata切换fragment
        viewModel.navigateToFragment.observe(this, Observer {
            it?.getContentIfNotHandled()?.let { fragmentRequest ->
                val transaction = supportFragmentManager.beginTransaction()
                transaction.replace(
                    R.id.fragmentContainer, fragmentRequest.fragment, fragmentRequest.tag
                )
                if (fragmentRequest.backStack) transaction.addToBackStack(null)
                transaction.commit()
            }
        })

        /**
         *当前播放媒体的根 ID。当应用启动且与 MusicService 建立连接后，rootMediaId 会被更新，此时会展示初始的媒体列表
         */
        viewModel.rootMediaId.observe(this,
            Observer { rootMediaId ->
                rootMediaId?.let { navigateToMediaItem(it) }
            })

        //当用户请求浏览某个特定的 MediaItem 时，调用 navigateToMediaItem 函数进行页面跳转
        viewModel.navigateToMediaItem.observe(this, Observer {
            it?.getContentIfNotHandled()?.let { mediaId ->
                navigateToMediaItem(mediaId)
            }
        })
    }

    //跳转到具体的媒体项目。如果当前没有对应的 MediaItemFragment，则创建一个新的 MediaItemFragment 并传入 mediaId
    private fun navigateToMediaItem(mediaId: String) {
        var fragment: MediaItemFragment? = getBrowseFragment(mediaId)
        if (fragment == null) {
            fragment = MediaItemFragment.newInstance(mediaId)
            // If this is not the top level media (root), we add it to the fragment
            // back stack, so that actionbar toggle and Back will work appropriately:
            //调用 viewModel.showFragment() 来显示该 fragment。
            viewModel.showFragment(fragment, !isRootId(mediaId), mediaId)
        }
    }

    private fun isRootId(mediaId: String) = mediaId == viewModel.rootMediaId.value
    private fun getBrowseFragment(mediaId: String): MediaItemFragment? {
        return supportFragmentManager.findFragmentByTag(mediaId) as? MediaItemFragment
    }

}