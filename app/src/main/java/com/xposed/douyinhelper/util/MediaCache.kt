package com.xposed.douyinhelper.util

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * 媒体缓存
 *
 * 用于在不同 Hook 模块之间共享当前正在浏览的媒体信息。
 * 主要场景:
 * - FeedHook 处理 Aweme 数据时缓存当前 Aweme
 * - LivePhotoHook 检测到实况照片时缓存 URL
 * - SharePanelHook 用户点击保存按钮时从缓存读取
 * - DownloadDialogHook 用户长按保存实况时从缓存读取
 */
object MediaCache {

    /** 当前正在浏览的 Aweme 对象 */
    @Volatile
    private var currentAweme: Any? = null

    /** 实况照片 URL 缓存: livePhotoId -> Pair(imageUrl, videoUrl) */
    private val livePhotoCache = ConcurrentHashMap<String, Pair<String, String>>()

    // ==================== Aweme 缓存 ====================

    fun setCurrentAweme(aweme: Any?) {
        currentAweme = aweme
    }

    fun getCurrentAweme(): Any? = currentAweme

    // ==================== 实况照片缓存 ====================

    fun cacheLivePhotoUrls(id: String, imageUrl: String, videoUrl: String) {
        livePhotoCache[id] = Pair(imageUrl, videoUrl)
        HookUtils.log("MediaCache: 缓存实况照片 [$id]")
    }

    fun getLivePhotoUrls(id: String): Pair<String, String>? = livePhotoCache[id]

    fun removeLivePhoto(id: String) {
        livePhotoCache.remove(id)
    }

    fun clearLivePhotoCache() {
        livePhotoCache.clear()
    }

    /** 获取所有缓存的实况照片 (供 DownloadDialogHook 遍历) */
    fun getAllCachedLivePhotos(): Map<String, Pair<String, String>> = livePhotoCache.toMap()

    /**
     * 根据 URL 特征查找匹配的实况照片缓存
     * 当不知道确切 ID 时，通过 URL 匹配
     */
    fun findLivePhotoByUrl(url: String): Pair<String, String>? {
        for ((_, pair) in livePhotoCache) {
            if (url.contains(pair.first) || url.contains(pair.second)) {
                return pair
            }
        }
        return null
    }

    // ==================== 辅助方法 ====================

    /** 获取 Aweme 中的视频 URL */
    fun getVideoUrlFromAweme(aweme: Any): String? {
        try {
            val video = getField(aweme, "video") ?: return null
            // 尝试 playAddr
            val playAddr = getField(video, "playAddr") ?: getField(video, "play_addr")
            if (playAddr != null) {
                val urlList = getField(playAddr, "urlList") as? List<*>
                val url = urlList?.filterIsInstance<String>()?.firstOrNull { it.isNotEmpty() }
                if (url != null) return url
            }
            // 尝试 playAddrH264
            try {
                val playAddrH264 = getField(video, "playAddrH264")
                if (playAddrH264 != null) {
                    val urlList = getField(playAddrH264, "urlList") as? List<*>
                    val url = urlList?.filterIsInstance<String>()?.firstOrNull { it.isNotEmpty() }
                    if (url != null) return url
                }
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            HookUtils.log("MediaCache: getVideoUrl 失败: ${t.message}")
        }
        return null
    }

    /** 获取 Aweme 中的图片 URL 列表 */
    fun getImageUrlsFromAweme(aweme: Any): List<String> {
        val urls = mutableListOf<String>()
        try {
            val imageFields = listOf("images", "imageList", "image_list", "picList", "pic_list")
            for (fieldName in imageFields) {
                val imageList = getField(aweme, fieldName)
                if (imageList is List<*>) {
                    for (image in imageList) {
                        if (image == null) continue
                        val urlList = getField(image, "urlList") as? List<*>
                            ?: getField(image, "url_list") as? List<*>
                        if (urlList != null) {
                            val url = urlList.filterIsInstance<String>().firstOrNull { it.isNotEmpty() }
                            if (url != null) urls.add(url)
                        }
                    }
                    if (urls.isNotEmpty()) break
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("MediaCache: getImageUrls 失败: ${t.message}")
        }
        return urls
    }

    /** 下载实况照片 (图片 + 视频) */
    fun downloadLivePhoto(context: android.content.Context, imageUrl: String, videoUrl: String) {
        val timestamp = System.currentTimeMillis()
        MediaDownloader.download(context, videoUrl, "live_photo_${timestamp}.mov",
            onComplete = { uri ->
                HookUtils.log("MediaCache: 实况视频已保存")
            },
            onError = { t ->
                HookUtils.log("MediaCache: 实况视频保存失败: ${t.message}")
            }
        )
        MediaDownloader.download(context, imageUrl, "live_photo_${timestamp}.heic",
            onComplete = { uri ->
                HookUtils.showToast(context, "实况照片已保存 ✓")
            },
            onError = { t ->
                HookUtils.log("MediaCache: 实况图片保存失败: ${t.message}")
            }
        )
    }

    /** 判断 URL 是否为视频 */
    fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/play") || lower.contains("playwm") ||
                lower.contains(".mp4") || lower.contains(".mov") ||
                lower.contains("video")
    }

    private fun getField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        return try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(obj)
        } catch (_: Throwable) {
            null
        }
    }
}
