package com.gta.myuamp02.fragments


import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.bumptech.glide.Glide
import com.gta.myuamp02.R
import com.gta.myuamp02.databinding.FragmentNowplayingBinding
import com.gta.myuamp02.utils.InjectorUtils
import com.gta.myuamp02.viewmodels.NowPlayingFragmentViewModel
import com.gta.myuamp02.viewmodels.MainActivityViewModel

class NowPlayingFragment : Fragment() {
    private val mainActivityViewModel by activityViewModels<MainActivityViewModel> {
        InjectorUtils.provideMainActivityViewModel(requireContext())
    }
    private val nowPlayingViewModel by viewModels<NowPlayingFragmentViewModel> {
        InjectorUtils.provideNowPlayingFragmentViewModel(requireContext())
    }
    lateinit var binding: FragmentNowplayingBinding
    companion object {
        fun newInstance() = NowPlayingFragment()
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentNowplayingBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Always true, but lets lint know that as well.
        //获取 activity 的引用，如果 activity 为 null（即 Fragment 未附加到活动），则直接返回
        val context = activity ?: return

        // 根据持有的当前mediaMetadata来更新UI
        nowPlayingViewModel.mediaMetadata.observe(viewLifecycleOwner,
            Observer { mediaItem -> updateUI(view, mediaItem) })
        //根据播放状态更新播放按钮的图标
        nowPlayingViewModel.mediaButtonRes.observe(viewLifecycleOwner,
            Observer { res ->
                binding.mediaButton.setImageResource(res)
            })
        //当播放进度发生变化时，更新显示的播放位置
        nowPlayingViewModel.mediaPosition.observe(viewLifecycleOwner,
            Observer { pos ->
                binding.position.text = NowPlayingFragmentViewModel.NowPlayingMetadata.timestampToMSS(context, pos)
            })

        // 当按钮被点击时，通过 mainActivityViewModel.playMediaId() 方法开始播放当前媒体项。
        binding.mediaButton.setOnClickListener {
            nowPlayingViewModel.mediaMetadata.value?.let { mainActivityViewModel.playMediaId(it.id) }
        }

        // 初始化播放时长和播放进度的显示
        binding.duration.text = NowPlayingFragmentViewModel.NowPlayingMetadata.timestampToMSS(context, 0L)
        binding.position.text = NowPlayingFragmentViewModel.NowPlayingMetadata.timestampToMSS(context, 0L)
    }

    /**
     * 显示当前播放媒体的封面、标题、副标题和持续时长
     * 如果 albumArtUri 是空 URI（即没有专辑封面），则显示默认的专辑图标
     * 如果有封面图 URI，则使用 Glide 加载并显示专辑封面图
     */
    private fun updateUI(view: View, metadata: NowPlayingFragmentViewModel.NowPlayingMetadata) = with(binding) {
        if (metadata.albumArtUri == Uri.EMPTY) {
            albumArt.setImageResource(R.drawable.ic_album_black_24dp)
        } else {
            Glide.with(view)
                .load(metadata.albumArtUri)
                .into(albumArt)
        }
        title.text = metadata.title
        subtitle.text = metadata.subtitle
        duration.text = metadata.duration
    }
}