package com.xposed.douyinhelper.hook

import com.xposed.douyinhelper.util.ClassFinder
import com.xposed.douyinhelper.util.ContextHelper
import com.xposed.douyinhelper.util.HookUtils
import com.xposed.douyinhelper.util.MediaCache
import com.xposed.douyinhelper.util.MediaDownloader
import com.xposed.douyinhelper.util.UrlParser
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 实况照片 Hook — 自适应版
 *
 * 通过字段特征自动发现实况照片数据，不依赖硬编码类名
 */
class LivePhotoHook : BaseHook {

    companion object {
        private const val TAG = "LivePhotoHook"
    }

    override fun init(classLoader: ClassLoader) {
        hookImageModel(classLoader)
    }

    /**
     * Hook 图片模型类
     */
    private fun hookImageModel(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val imageClasses = listOf(
                "com.ss.android.ugc.aweme.feed.model.ImageModel",
                "com.ss.android.ugc.aweme.feed.model.Image",
                "com.ss.ugc.aweme.ImageModel"
            )

            val imageClass = ClassFinder.findClass(classLoader, imageClasses)

            if (imageClass != null) {
                HookUtils.log("$TAG: 找到图片模型: ${imageClass.name}")
                hookImageGetters(imageClass, classLoader)
            } else {
                HookUtils.log("$TAG: 未找到图片模型类")
            }
        }
    }

    /**
     * Hook 图片模型的所有 getter，自动检测实况照片
     */
    private fun hookImageGetters(imageClass: Class<*>, classLoader: ClassLoader) {
        HookUtils.safeHook {
            val methods = imageClass.declaredMethods.filter { m ->
                m.parameterCount == 0 && m.returnType != Void.TYPE::class.java
            }

            for (method in methods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            checkForLivePhoto(param.thisObject, classLoader)
                        } catch (_: Throwable) {}
                    }
                })
            }

            HookUtils.log("$TAG: Hooking ${methods.size} 个图片模型 getter")
        }
    }

    /**
     * 自适应检测实况照片 — 扫描对象字段
     */
    private fun checkForLivePhoto(imageObj: Any, classLoader: ClassLoader) {
        val clazz = imageObj::class.java
        var videoUrl: String? = null
        var imageUrl: String? = null

        for (field in clazz.declaredFields) {
            try {
                field.isAccessible = true
                val value = field.get(imageObj) ?: continue
                val fieldName = field.name.lowercase()

                when (value) {
                    is String -> {
                        if (value.startsWith("http")) {
                            if (fieldName.contains("video") || fieldName.contains("live") ||
                                UrlParser.isVideoUrl(value)) {
                                videoUrl = value
                            } else if (fieldName.contains("url") || fieldName.contains("image") ||
                                UrlParser.isImageUrl(value)) {
                                imageUrl = value
                            }
                        }
                    }
                    is List<*> -> {
                        // url_list 等
                        if (fieldName.contains("url")) {
                            for (item in value) {
                                if (item is String && item.startsWith("http")) {
                                    if (imageUrl == null) imageUrl = item
                                }
                            }
                        }
                    }
                    else -> {
                        // 嵌套对象（如 livePhoto 字段）
                        val nestedVideo = findStringField(value, "video")
                        val nestedUrl = findStringField(value, "url")
                        if (nestedVideo != null) videoUrl = nestedVideo
                        if (nestedUrl != null && imageUrl == null) imageUrl = nestedUrl
                    }
                }
            } catch (_: Throwable) {}
        }

        if (videoUrl != null && imageUrl != null) {
            val id = "live_${System.currentTimeMillis()}"
            MediaCache.cacheLivePhotoUrls(id, imageUrl, videoUrl)
            HookUtils.log("$TAG: 缓存实况照片 URL")

            val context = ContextHelper.getContext()
            if (context != null) {
                MediaCache.downloadLivePhoto(context, imageUrl, videoUrl)
            }
        }
    }

    /**
     * 在对象中查找包含指定关键字的 String 字段
     */
    private fun findStringField(obj: Any, keyword: String): String? {
        for (field in obj::class.java.declaredFields) {
            try {
                field.isAccessible = true
                val value = field.get(obj) ?: continue
                if (value is String && value.startsWith("http") &&
                    field.name.lowercase().contains(keyword)) {
                    return value
                }
            } catch (_: Throwable) {}
        }
        return null
    }
}
