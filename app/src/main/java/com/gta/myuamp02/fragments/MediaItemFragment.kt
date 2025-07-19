package com.gta.myuamp02.fragments


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.gta.myuamp02.MediaItemAdapter
import com.gta.myuamp02.databinding.FragmentMediaitemListBinding
import com.gta.myuamp02.utils.InjectorUtils
import com.gta.myuamp02.viewmodels.MainActivityViewModel
import com.gta.myuamp02.viewmodels.MediaItemFragmentViewModel

class MediaItemFragment : Fragment(){
    private val mainActivityViewModel by activityViewModels<MainActivityViewModel> {
        InjectorUtils.provideMainActivityViewModel(requireContext())
    }
    private val mediaItemFragmentViewModel by viewModels<MediaItemFragmentViewModel> {
        InjectorUtils.provideMediaItemFragmentViewModel(requireContext(), mediaId)
    }
//表示当前 Fragment 对应的媒体项 ID。这个 ID 将用来加载特定的媒体数据
    private lateinit var mediaId: String
    private lateinit var binding: FragmentMediaitemListBinding
    //适配器的点击事件会调用 mainActivityViewModel.mediaItemClicked() 方法，传递被点击的媒体项
    private val listAdapter = MediaItemAdapter { clickedItem ->
        mainActivityViewModel.mediaItemClicked(clickedItem)
    }
    companion object {
        fun newInstance(mediaId: String): MediaItemFragment {

            return MediaItemFragment().apply {
                arguments = Bundle().apply {
                    putString(MEDIA_ID_ARG, mediaId)
                }
            }
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentMediaitemListBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onViewCreated(view:View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Always true, but lets lint know that as well.
        mediaId = arguments?.getString(MEDIA_ID_ARG) ?: return
//观察 mediaItemFragmentViewModel 的 mediaItems LiveData，根据是否有数据决定加载动画
        mediaItemFragmentViewModel.mediaItems.observe(viewLifecycleOwner,
            Observer { list ->
                binding.loadingSpinner.visibility =
                    if (list?.isNotEmpty() == true) View.GONE else View.VISIBLE
                listAdapter.submitList(list)
            })
        //当发生网络错误时，隐藏加载中的旋转器，并显示网络错误视图
        mediaItemFragmentViewModel.networkError.observe(viewLifecycleOwner,
            Observer { error ->
                if (error) {
                    binding.loadingSpinner.visibility = View.GONE
                    binding.networkError.visibility = View.VISIBLE
                } else {
                    binding.networkError.visibility = View.GONE
                }
            })

        // Set the adapter
        binding.list.adapter = listAdapter
    }
}
private const val MEDIA_ID_ARG = "com.example.android.uamp.fragments.MediaItemFragment.MEDIA_ID"