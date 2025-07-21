package com.gta.myuamp02


import android.net.Uri
import androidx.recyclerview.widget.DiffUtil

/**
 * 用于UI显示d数据模型
 */
data class MediaItemData(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val albumArtUri: Uri,
    val browsable: Boolean,
    var playbackRes: Int
) {

    companion object {
        /**
         * Indicates [playbackRes] has changed.
         */
        const val PLAYBACK_RES_CHANGED = 1

        /**
         * [DiffUtil.ItemCallback] for a [MediaItemData].
         *
         * Since all [MediaItemData]s have a unique ID, it's easiest to check if two
         * items are the same by simply comparing that ID.
         *
         * To check if the contents are the same, we use the same ID, but it may be the
         * case that it's only the play state itself which has changed (from playing to
         * paused, or perhaps a different item is the active item now). In this case
         * we check both the ID and the playback resource.
         *
         * To calculate the payload, we use the simplest method possible:
         * - Since the title, subtitle, and albumArtUri are constant (with respect to mediaId),
         *   there's no reason to check if they've changed. If the mediaId is the same, none of
         *   those properties have changed.
         * - If the playback resource (playbackRes) has changed to reflect the change in playback
         *   state, that's all that needs to be updated. We return [PLAYBACK_RES_CHANGED] as
         *   the payload in this case.
         * - If something else changed, then refresh the full item for simplicity.
         */
        //diffCallback 是 DiffUtil.ItemCallback<MediaItemData> 的实现，用于在 RecyclerView 中优化数据更新。
        val diffCallback = object : DiffUtil.ItemCallback<MediaItemData>() {
            //判断两个 MediaItemData 是否是同一项。通过比较它们的 mediaId 来确定是否是相同的媒体项，因为 mediaId 是唯一标识符
            override fun areItemsTheSame(
                oldItem: MediaItemData,
                newItem: MediaItemData
            ): Boolean =
                oldItem.mediaId == newItem.mediaId
//检查两个 MediaItemData 的内容是否相同。除了 mediaId，它还比较了 playbackRes，即播放状态图标是否相同。
// 只有当 mediaId 和 playbackRes 都相同时，认为这两个媒体项的内容相同
            override fun areContentsTheSame(oldItem: MediaItemData, newItem: MediaItemData) =
                oldItem.mediaId == newItem.mediaId && oldItem.playbackRes == newItem.playbackRes
//用于返回一个“负载”，即指示数据项具体哪个部分发生了变化。
// 如果 playbackRes 发生了变化，返回 [PLAYBACK_RES_CHANGED] 作为负载；否则返回 null 表示没有变化
            override fun getChangePayload(oldItem: MediaItemData, newItem: MediaItemData) =
                if (oldItem.playbackRes != newItem.playbackRes) {
                    PLAYBACK_RES_CHANGED
                } else null
        }
    }
}