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
        val context = activity ?: return

        // Attach observers to the LiveData coming from this ViewModel
        nowPlayingViewModel.mediaMetadata.observe(viewLifecycleOwner,
            Observer { mediaItem -> updateUI(view, mediaItem) })
        nowPlayingViewModel.mediaButtonRes.observe(viewLifecycleOwner,
            Observer { res ->
                binding.mediaButton.setImageResource(res)
            })
        nowPlayingViewModel.mediaPosition.observe(viewLifecycleOwner,
            Observer { pos ->
                binding.position.text = NowPlayingFragmentViewModel.NowPlayingMetadata.timestampToMSS(context, pos)
            })

        // Setup UI handlers for buttons
        binding.mediaButton.setOnClickListener {
            nowPlayingViewModel.mediaMetadata.value?.let { mainActivityViewModel.playMediaId(it.id) }
        }

        // Initialize playback duration and position to zero
        binding.duration.text = NowPlayingFragmentViewModel.NowPlayingMetadata.timestampToMSS(context, 0L)
        binding.position.text = NowPlayingFragmentViewModel.NowPlayingMetadata.timestampToMSS(context, 0L)
    }

    /**
     * Internal function used to update all UI elements except for the current item playback
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