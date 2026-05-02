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
 * 实况照片 Hook
 *
 * 功能:
 * - 专门处理 Live Photo 数据
 * - 提取 live_photo.video_url
 * - 同时保存图片和视频部分
 * - 缓存实况照片 URL 供 DownloadDialogHook 使用
 *
 * 实况照片数据结构:
 * image -> live_photo -> video_url (视频部分)
 * image -> url_list[] (图片部分)
 */
class LivePhotoHook : BaseHook {

    companion object {
        private const val TAG = "LivePhotoHook"
    }

    override fun init(classLoader: ClassLoader) {
        hookLivePhotoModel(classLoader)
        hookLivePhotoDisplay(classLoader)
        hookLivePhotoViewer(classLoader)
    }

    /**
     * Hook 实况照片查看器
     *
     * 当用户在评论区点击查看实况照片时，捕获查看事件并缓存 URL。
     * 这样后续长按下载时可以直接使用缓存的 URL。
     */
    private fun hookLivePhotoViewer(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // Hook 可能的实况照片查看器
            val viewerClasses = listOf(
                "com.ss.android.ugc.aweme.comment.image.CommentImageActivity",
                "com.ss.android.ugc.aweme.comment.image.ImageViewerActivity",
                "com.ss.android.ugc.aweme.detail.ui.LivePhotoActivity",
                "com.ss.android.ugc.aweme.feed.ui.LivePhotoViewerActivity",
                "com.ss.android.ugc.aweme.livephoto.LivePhotoViewActivity"
            )

            for (className in viewerClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到实况照片查看器: $className")

                    // Hook onCreate 获取 Intent 中的实况照片数据
                    XposedBridge.hookAllMethods(clazz, "onCreate", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val activity = param.thisObject as? android.app.Activity ?: return
                                extractLivePhotoFromIntent(activity)
                            } catch (t: Throwable) {
                                HookUtils.log("$TAG: 处理查看器 onCreate 失败: ${t.message}")
                            }
                        }
                    })

                    break
                } catch (_: ClassNotFoundException) { }
            }

            // 通用方案: Hook Fragment 的 onResume
            hookFragmentForLivePhoto(classLoader)
        }
    }

    /**
     * Hook Fragment 检测实况照片查看
     */
    private fun hookFragmentForLivePhoto(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val fragmentClass = try {
                XposedHelpers.findClass("androidx.fragment.app.Fragment", classLoader)
            } catch (_: Throwable) {
                try {
                    XposedHelpers.findClass("android.app.Fragment", classLoader)
                } catch (_: Throwable) { null }
            } ?: return

            XposedBridge.hookAllMethods(fragmentClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val fragment = param.thisObject
                        val className = fragment.javaClass.name

                        // 只处理可能包含实况照片的 Fragment
                        if (className.contains("Image", ignoreCase = true) ||
                            className.contains("Photo", ignoreCase = true) ||
                            className.contains("Live", ignoreCase = true) ||
                            className.contains("Comment", ignoreCase = true)) {

                            // 尝试从 Fragment 的 arguments 中获取实况照片 URL
                            val args = HookUtils.callMethod(fragment, "getArguments")
                            if (args is android.os.Bundle) {
                                extractLivePhotoFromBundle(args, className)
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })
        }
    }

    /**
     * 从 Intent 中提取实况照片数据
     */
    private fun extractLivePhotoFromIntent(activity: android.app.Activity) {
        val intent = activity.intent ?: return
        val extras = intent.extras ?: return

        extractLivePhotoFromBundle(extras, activity.javaClass.name)

        // 也检查 Intent data URI
        val data = intent.data?.toString()
        if (data != null && data.contains("live", ignoreCase = true)) {
            HookUtils.log("$TAG: Intent data 包含实况照片信息: $data")
        }
    }

    /**
     * 从 Bundle 中提取实况照片 URL
     */
    private fun extractLivePhotoFromBundle(bundle: android.os.Bundle, source: String) {
        var videoUrl: String? = null
        var imageUrl: String? = null

        for (key in bundle.keySet()) {
            val value = bundle.get(key) ?: continue

            when (value) {
                is String -> {
                    if (value.contains("live", ignoreCase = true) || value.contains("/play")) {
                        if (value.contains(".mov") || value.contains("video") || value.contains("/play")) {
                            videoUrl = value
                        } else if (value.contains(".heic") || value.contains(".jpg") || value.contains("image")) {
                            imageUrl = value
                        }
                    }
                }
                is android.os.Bundle -> {
                    extractLivePhotoFromBundle(value, source)
                }
            }
        }

        if (videoUrl != null && imageUrl != null) {
            val id = "intent_${System.currentTimeMillis()}"
            MediaCache.cacheLivePhotoUrls(id, imageUrl, videoUrl)
            HookUtils.log("$TAG: 从 $source 缓存实况照片 URL")
        }
    }

    /**
     * Hook 实况照片数据模型
     */
    private fun hookLivePhotoModel(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val imageClasses = listOf(
                "com.ss.android.ugc.aweme.feed.model.ImageModel",
                "com.ss.android.ugc.aweme.feed.model.Image",
                "com.ss.ugc.aweme.ImageModel"
            )

            for (className in imageClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到图片模型类: $className")

                    clazz.declaredMethods
                        .filter { it.parameterCount == 0 && it.returnType != Void.TYPE }
                        .forEach { method ->
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    try {
                                        checkLivePhoto(param, classLoader)
                                    } catch (_: Throwable) {}
                                }
                            })
                        }

                    break
                } catch (_: ClassNotFoundException) { }
            }
        }
    }

    /**
     * 检查返回值是否包含实况照片信息
     */
    private fun checkLivePhoto(param: XC_MethodHook.MethodHookParam, classLoader: ClassLoader) {
        val result = param.result ?: return

        try {
            val livePhoto = XposedHelpers.getObjectField(result, "livePhoto")
                ?: XposedHelpers.getObjectField(result, "live_photo")
                ?: XposedHelpers.getObjectField(result, "livePhotoInfo")

            if (livePhoto != null) {
                HookUtils.log("$TAG: 发现实况照片数据!")
                processLivePhoto(livePhoto, result, classLoader)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Hook 实况照片的显示过程
     */
    private fun hookLivePhotoDisplay(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val loadClasses = listOf(
                "com.ss.android.ugc.aweme.feed.model.Aweme",
                "com.ss.android.ugc.aweme.detail.model.DetailAweme"
            )

            for (className in loadClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)

                    val imageListMethods = clazz.declaredMethods.filter { method ->
                        method.returnType.name.contains("List") &&
                        (method.name.contains("Image") || method.name.contains("image") ||
                         method.name.contains("Pic") || method.name.contains("pic"))
                    }

                    for (method in imageListMethods) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    processImageList(param.result, classLoader)
                                } catch (t: Throwable) {
                                    HookUtils.log("$TAG: 处理图片列表失败: ${t.message}")
                                }
                            }
                        })
                    }

                    break
                } catch (_: ClassNotFoundException) { }
            }
        }
    }

    /**
     * 处理图片列表，查找实况照片
     */
    private fun processImageList(imageList: Any?, classLoader: ClassLoader) {
        if (imageList !is List<*>) return

        for (image in imageList) {
            if (image == null) continue

            try {
                val clazz = image::class.java
                val livePhotoField = try {
                    clazz.getDeclaredField("livePhoto")
                } catch (_: NoSuchFieldException) {
                    try {
                        clazz.getDeclaredField("live_photo")
                    } catch (_: NoSuchFieldException) { null }
                }

                if (livePhotoField != null) {
                    livePhotoField.isAccessible = true
                    val livePhoto = livePhotoField.get(image)
                    if (livePhoto != null) {
                        HookUtils.log("$TAG: 图片列表中发现实况照片")
                        processLivePhoto(livePhoto, image, classLoader)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * 处理实况照片数据
     *
     * 提取图片和视频 URL，缓存并触发下载
     */
    private fun processLivePhoto(livePhoto: Any, parentImage: Any, classLoader: ClassLoader) {
        try {
            val videoUrl = extractLivePhotoVideoUrl(livePhoto)
            val imageUrl = extractLivePhotoImageUrl(parentImage)

            if (videoUrl != null && imageUrl != null) {
                HookUtils.log("$TAG: 实况照片 - 视频: $videoUrl")
                HookUtils.log("$TAG: 实况照片 - 图片: $imageUrl")

                // 缓存 URL 供后续长按下载使用
                val id = "live_${System.currentTimeMillis()}"
                MediaCache.cacheLivePhotoUrls(id, imageUrl, videoUrl)

                // 自动下载
                val context = ContextHelper.getContext()
                if (context != null) {
                    MediaCache.downloadLivePhoto(context, imageUrl, videoUrl)
                }
            } else {
                HookUtils.log("$TAG: 实况照片 URL 提取不完整 (video=$videoUrl, image=$imageUrl)")
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 处理实况照片失败: ${t.message}")
        }
    }

    /**
     * 提取实况照片的视频 URL
     */
    private fun extractLivePhotoVideoUrl(livePhoto: Any): String? {
        try {
            val possibleFields = listOf(
                "videoUrl", "video_url", "videoUri", "video_uri",
                "url", "uri", "videoUrlList"
            )

            for (fieldName in possibleFields) {
                try {
                    val value = XposedHelpers.getObjectField(livePhoto, fieldName)
                    when (value) {
                        is String -> if (value.isNotEmpty()) return value
                        is List<*> -> {
                            val url = value.filterIsInstance<String>().firstOrNull { it.isNotEmpty() }
                            if (url != null) return url
                        }
                    }
                } catch (_: Throwable) { }
            }

            livePhoto::class.java.declaredFields
                .filter { it.type == String::class.java }
                .forEach { field ->
                    field.isAccessible = true
                    val value = field.get(livePhoto) as? String
                    if (value != null && value.isNotEmpty() && UrlParser.isVideoUrl(value)) {
                        return value
                    }
                }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 提取视频URL失败: ${t.message}")
        }
        return null
    }

    /**
     * 提取实况照片的图片 URL
     */
    private fun extractLivePhotoImageUrl(parentImage: Any): String? {
        try {
            val possibleFields = listOf(
                "urlList", "url_list", "downloadUrl", "download_url"
            )

            for (fieldName in possibleFields) {
                try {
                    val value = XposedHelpers.getObjectField(parentImage, fieldName)
                    if (value is List<*>) {
                        return value.filterIsInstance<String>().firstOrNull { it.isNotEmpty() }
                    }
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 提取图片URL失败: ${t.message}")
        }
        return null
    }
}
