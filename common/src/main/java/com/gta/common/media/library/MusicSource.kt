package com.gta.common.media.library


import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import androidx.annotation.IntDef

interface MusicSource : Iterable<MediaMetadataCompat> {
    //实现了这个接口的类就表示"可以被遍历的一系列元素"。
    //集合都实现了这个，就是有个才能用for-in
    suspend fun load()
    fun whenReady(performAction: (Boolean) -> Unit): Boolean
//    fun search(query: String, extras: Bundle): List<MediaMetadataCompat>
}


/**
 * 定义注解，有限状态集合，替代枚举类
 */
@IntDef(
    STATE_CREATED,
    STATE_INITIALIZING,
    STATE_INITIALIZED,
    STATE_ERROR
)
//注释保留策略：SOURCE 表示只保留在源代码中，编译后会被移除不会编译到 class 文件中，运行时不可见
@Retention(AnnotationRetention.SOURCE)
annotation class State

const val STATE_CREATED = 1
const val STATE_INITIALIZING = 2
const val STATE_INITIALIZED = 3
const val STATE_ERROR = 4

abstract class AbstractMusicSource : MusicSource {
    private val onReadyListeners = mutableListOf<(Boolean) -> Unit>()
    //搞了一个抽象类部分实现接口作为base，
//然后管理一个state，根据赋值，决定是否通知监听者，
//用一个list管理监听者，其实就是函数类型的回调方法
    @State
    var state: Int = STATE_CREATED
        set(value) {
            if (value == STATE_INITIALIZED || value == STATE_ERROR) {
                synchronized(onReadyListeners) {
                    field = value
                    onReadyListeners.forEach { listener ->
                        listener(state == STATE_INITIALIZED)
                    }
                }
            } else {
                field = value
            }
        }
    //所以这个whenRead其实是一个接收函数的入口
//根据状态state返回是否Ready
//Ready的直接执行函数，
//没有准备好就存到集合，好了之后（state赋值）自动通知执行
    override fun whenReady(performAction: (Boolean) -> Unit): Boolean =
        when (state) {
            STATE_CREATED, STATE_INITIALIZING -> {
                onReadyListeners += performAction
                false
            }

            else -> {
                performAction(state != STATE_ERROR)
                true
            }
        }

}
private const val TAG = "MusicSource"