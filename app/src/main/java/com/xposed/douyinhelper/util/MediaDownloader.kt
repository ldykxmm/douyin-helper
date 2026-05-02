package com.xposed.douyinhelper.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 媒体下载器
 *
 * 功能:
 * - 异步下载媒体文件
 * - 保存到相册 (使用 MediaStore API)
 * - 通知下载进度
 * - 支持视频、图片、实况照片
 *
 * 兼容性:
 * - Android 10+ 使用 MediaStore API (Scoped Storage)
 * - Android 9 及以下使用传统文件写入
 */
object MediaDownloader {

    private const val TAG = "MediaDownloader"
    private const val BUFFER_SIZE = 8192

    /**
     * 下载媒体文件并保存到相册
     *
     * @param context Android Context
     * @param url 媒体 URL
     * @param fileName 文件名 (包含扩展名)
     * @param onComplete 下载完成回调
     * @param onError 下载失败回调
     */
    fun download(
        context: Context,
        url: String,
        fileName: String,
        onComplete: ((Uri?) -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {
        // 使用线程异步下载
        Thread {
            try {
                HookUtils.log("$TAG: 开始下载: $fileName")

                val connection = createConnection(url)
                if (connection == null) {
                    HookUtils.log("$TAG: 无法创建连接")
                    onError?.invoke(RuntimeException("无法创建连接"))
                    return@Thread
                }

                val contentType = connection.contentType ?: ""
                val totalSize = connection.contentLength

                HookUtils.log("$TAG: 文件大小: ${totalSize}bytes, 类型: $contentType")

                val uri = saveToMediaStore(context, connection.inputStream, fileName, contentType, totalSize)

                connection.disconnect()

                if (uri != null) {
                    HookUtils.log("$TAG: 下载完成: $uri")
                    onComplete?.invoke(uri)
                } else {
                    HookUtils.log("$TAG: 保存失败")
                    onError?.invoke(RuntimeException("保存到MediaStore失败"))
                }
            } catch (t: Throwable) {
                HookUtils.log("$TAG: 下载异常: ${t.message}")
                onError?.invoke(t)
            }
        }.start()
    }

    /**
     * 创建 HTTP 连接
     */
    private fun createConnection(urlStr: String): HttpURLConnection? {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                setRequestProperty("Referer", "https://www.douyin.com/")
                instanceFollowRedirects = true
            }
            connection.connect()

            // 处理重定向
            if (connection.responseCode in 301..308) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (newUrl != null) return createConnection(newUrl)
            }

            if (connection.responseCode == 200) connection else {
                connection.disconnect()
                null
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 创建连接失败: ${t.message}")
            null
        }
    }

    /**
     * 保存到 MediaStore (兼容 Android 10+ Scoped Storage)
     */
    private fun saveToMediaStore(
        context: Context,
        inputStream: InputStream,
        fileName: String,
        contentType: String,
        totalSize: Int
    ): Uri? {
        val isVideo = contentType.contains("video") || fileName.endsWith(".mp4") || fileName.endsWith(".mov")
        val isImage = contentType.contains("image") || fileName.endsWith(".jpg") || fileName.endsWith(".heic")

        val collection = when {
            isVideo -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            isImage -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
        }

        val mimeType = when {
            fileName.endsWith(".mp4") -> "video/mp4"
            fileName.endsWith(".mov") -> "video/quicktime"
            fileName.endsWith(".heic") -> "image/heic"
            fileName.endsWith(".heif") -> "image/heif"
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".webp") -> "image/webp"
            else -> contentType
        }

        val relativePath = when {
            isVideo -> Environment.DIRECTORY_MOVIES + "/Dou+"
            isImage -> Environment.DIRECTORY_PICTURES + "/Dou+"
            else -> Environment.DIRECTORY_DOWNLOADS + "/Dou+"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                inputStream.copyTo(outputStream, BUFFER_SIZE)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            uri
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 写入文件失败: ${t.message}")
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * 同步下载 (用于小文件)
     *
     * @param url URL
     * @return 下载的字节数组，失败返回 null
     */
    fun downloadSync(url: String): ByteArray? {
        return try {
            val connection = createConnection(url) ?: return null
            val bytes = connection.inputStream.readBytes()
            connection.disconnect()
            bytes
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 同步下载失败: ${t.message}")
            null
        }
    }

    /**
     * 获取文件的 MIME 类型
     */
    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".mp4") -> "video/mp4"
            fileName.endsWith(".mov") -> "video/quicktime"
            fileName.endsWith(".heic") -> "image/heic"
            fileName.endsWith(".heif") -> "image/heif"
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".webp") -> "image/webp"
            else -> "application/octet-stream"
        }
    }
}
