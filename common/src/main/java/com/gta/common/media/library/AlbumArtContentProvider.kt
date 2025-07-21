package com.gta.common.media.library


import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.bumptech.glide.Glide
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

// The amount of time to wait for the album art file to download before timing out.
const val DOWNLOAD_TIMEOUT_SECONDS = 30L

//作为一个读取专辑封面文件的服务
//通过自定义的 URI 获取专辑封面文件，并且具备从远程下载图像和缓存图像的能力，同时实现超时机制以保证用户体验
//ContentProvider 是用于访问应用内数据的一种方式
internal class AlbumArtContentProvider : ContentProvider() {
    /**
     * 向Android系统其他部分（如Android Auto、Google Assistant或应用内部其他模块）隐藏媒体的真实来源。
     * 系统无需知道音乐是存储在设备本地、SD卡还是从服务器流式传输，
     * 只需通过稳定的唯一标识符（content://...）引用媒体内容。
     * 当需要变更音乐来源（比如从网络服务器改为本地数据库）时，
     * 只需修改ContentProvider内部逻辑，内容URI可保持不变，其他应用或组件不会受到影响。
     * 直接使用文件路径（file:///...）或原始网络URL存在安全风险，
     * 且可能因Android存储限制（如分区存储）导致其他应用无法访问。
     * 通过强制要求经由内容URI访问，可在ContentProvider中检查权限并控制内容访问。
     * 为使用MediaItem对象列表，每个MediaItem需要稳定唯一的mediaId。
     * 这种内容URI格式恰好适合作为mediaId，为Android系统提供了请求媒体播放的标准方式
     * 在构建 MediaItem 的 mediaId 时，可以直接用这种内容 URI，
     * 系统媒体浏览器（MediaBrowserService）或控制器（MediaController）拿到 mediaId 后，
     * 通过 ContentResolver.openFile() 就能获取封面，无需额外逻辑
     */
    companion object {
        private val uriMap = mutableMapOf<Uri, Uri>()
        //将输入的 URI 映射到内部对应的 URI
        fun mapUri(uri: Uri): Uri {
            //字符串处理，去掉前面的斜杠并将斜杠替换为冒号
            val path = uri.encodedPath?.substring(1)?.replace('/', ':') ?: return Uri.EMPTY
            val contentUri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)//协议：内容URI
                .authority("com.example.android.uamp")//内容提供者
                .path(path)
                .build()
            uriMap[contentUri] = uri
            return contentUri
        }
    }

    override fun onCreate() = true//表示 ContentProvider 成功创建

    //方法用于打开一个文件并返回其文件描述符（ParcelFileDescriptor）。它接受一个 URI 和文件模式作为参数
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = this.context ?: return null
        //从 uriMap 中获取与传入 URI 对应的远程 URI，如果没有找到映射关系，则抛出 FileNotFoundException
        val remoteUri = uriMap[uri] ?: throw FileNotFoundException(uri.path)
        //通过 context.cacheDir 创建一个本地文件对象，用于缓存下载的专辑封面图像
        var file = File(context.cacheDir, uri.path)
        //如果文件不存在，则使用 Glide 下载远程 URI 指向的专辑封面图像，并将其作为文件保存
        if (!file.exists()) {
            // Use Glide to download the album art.
            val cacheFile = Glide.with(context)
                .asFile()
                .load(remoteUri)
                .submit()//submit() 返回一个 Future，调用 get() 方法来等待下载结果，
                .get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)//设置了超时为 DOWNLOAD_TIMEOUT_SECONDS 秒。
            //一旦下载完成，Glide 会将文件存储到临时缓存中，通过 renameTo() 方法
            // 将缓存文件重命名为符合我们本地路径要求的文件，
            // Rename the file Glide created to match our own scheme.
            cacheFile.renameTo(file)
            //并将 file 变量指向这个缓存文件。
            file = cacheFile
        }
        //通过 ParcelFileDescriptor.open() 打开文件，并指定为只读模式
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    //不涉及数据库的增删改查操作
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ) = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    //getType() 方法返回 URI 对应的数据类型，返回 null，表示我们没有定义 MIME 类型，
    // 因为 ContentProvider 主要是提供文件访问功能，而不是传统的数据类型。
    override fun getType(uri: Uri): String? = null

}