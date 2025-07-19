package com.gta.myuamp02


import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.gta.myuamp02.MediaItemData.Companion.PLAYBACK_RES_CHANGED
import com.gta.myuamp02.databinding.FragmentMediaitemBinding

//虽然播放器处理MediaMetadataCompat，但是UI使用MediaItemData来呈现信息
//itemClickedListener 是一个回调函数，用于处理项被点击时的逻辑
//istAdapter 是 RecyclerView.Adapter 的一个扩展类，具有内置的差异更新功能，
// 可以在数据集发生变化时自动计算哪些项需要更新,MediaItemData.diffCallback 是传递给
// ListAdapter 的 DiffUtil.ItemCallback，用于比较和更新列表项
class MediaItemAdapter(
    private val itemClickedListener: (MediaItemData) -> Unit
) : ListAdapter<MediaItemData, MediaViewHolder>(MediaItemData.diffCallback) {
//用于创建每个列表项的 ViewHolder
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = FragmentMediaitemBinding.inflate(inflater, parent, false)
        return MediaViewHolder(binding, itemClickedListener)
    }
    //用于将数据绑定到 ViewHolder 中
    //payloads 是一个优化机制，只有在局部更新数据时才会使用，通常用于只更新列表项的某个部分，而不是整个项
    //fullRefresh 标志位用于指示是否需要完全刷新整个列表项
    override fun onBindViewHolder(
        holder: MediaViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {

        val mediaItem = getItem(position)
        var fullRefresh = payloads.isEmpty()
//如果 payloads 非空，表示有局部更新。在这里，我们检查 payload 是否为 PLAYBACK_RES_CHANGED，
// 如果是，表示播放状态图标发生了变化，因此只更新 playbackState
// 如果 payload 是未处理的其他类型（可能是未来的扩展），则设置 fullRefresh 为 true，表示需要完全刷新项
        if (payloads.isNotEmpty()) {
            payloads.forEach { payload ->
                when (payload) {
                    PLAYBACK_RES_CHANGED -> {
                        holder.playbackState.setImageResource(mediaItem.playbackRes)
                    }
                    // If the payload wasn't understood, refresh the full item (to be safe).
                    else -> fullRefresh = true
                }
            }
        }

        // Normally we only fully refresh the list item if it's being initially bound, but
        // we might also do it if there was a payload that wasn't understood, just to ensure
        // there isn't a stale item.
        //如果需要完全刷新项，更新 ViewHolder 中的所有视图
        if (fullRefresh) {
            holder.item = mediaItem
            holder.titleView.text = mediaItem.title//设置标题 (titleView)
            holder.subtitleView.text = mediaItem.subtitle//副标题 (subtitleView)
            holder.playbackState.setImageResource(mediaItem.playbackRes)//播放状态图标 (playbackState)
            //使用 Glide 加载专辑封面图像，并设置占位符图像 (default_art)
            Glide.with(holder.albumArt)
                .load(mediaItem.albumArtUri)
                .placeholder(R.drawable.default_art)
                .into(holder.albumArt)
        }
    }
//这是 onBindViewHolder 的另一个重载方法，当没有 payloads 时调用。它直接调用上面带有 payloads 的 onBindViewHolder 方法
    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf())
    }
}
//用于持有并绑定item布局
class MediaViewHolder(
    binding: FragmentMediaitemBinding,
    itemClickedListener: (MediaItemData) -> Unit
) : RecyclerView.ViewHolder(binding.root) {

    val titleView: TextView = binding.title
    val subtitleView: TextView = binding.subtitle
    val albumArt: ImageView = binding.albumArt
    val playbackState: ImageView = binding.itemState
//    用于保存当前绑定的 MediaItemData 对象
    var item: MediaItemData? = null

    init {
        //为根视图设置点击监听器，当点击项时，触发 itemClickedListener 回调，并传递当前 item
        binding.root.setOnClickListener {
            item?.let { itemClickedListener(it) }
        }
    }
}