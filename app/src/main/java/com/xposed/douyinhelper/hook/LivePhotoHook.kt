package com.xposed.douyinhelper.hook

import com.xposed.douyinhelper.util.ClassFinder
import com.xposed.douyinhelper.util.ContextHelper
import com.xposed.douyinhelper.util.HookUtils
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
 *
 * 实况照片数据结构:
 * image -> live_photo -> video_url (视频部分)
 * image -> url_list[] (图片部分)
 *
 * 实况照片通常包含:
 * - 一张 HEIC/HEIF 格式的静态图片
 * - 一段 MOV/MP4 格式的短视频
 * 两者需要同时保存才能保持"实况"效果
 */
class LivePhotoHook : BaseHook {

    companion object {
        private const val TAG = "LivePhotoHook"
    }

    override fun init(classLoader: ClassLoader) {
        hookLivePhotoModel(classLoader)
        hookLivePhotoDisplay(classLoader)
    }

    /**
     * Hook 实况照片数据模型
     * 拦截图片数据，检查是否包含 live_photo 信息
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

                    // Hook 所有 getter 方法，检查 live_photo 相关字段
                    clazz.declaredMethods
                        .filter { it.parameterCount == 0 && it.returnType != Void.TYPE }
                        .forEach { method ->
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    try {
                                        checkLivePhoto(param, classLoader)
                                    } catch (t: Throwable) {
                                        // 静默处理，避免影响正常使用
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
     * 检查返回值是否包含实况照片信息
     */
    private fun checkLivePhoto(param: XC_MethodHook.MethodHookParam, classLoader: ClassLoader) {
        val result = param.result ?: return

        try {
            // 检查是否有 livePhoto 字段
            val livePhoto = XposedHelpers.getObjectField(result, "livePhoto")
                ?: XposedHelpers.getObjectField(result, "live_photo")
                ?: XposedHelpers.getObjectField(result, "livePhotoInfo")

            if (livePhoto != null) {
                HookUtils.log("$TAG: 发现实况照片数据!")
                processLivePhoto(livePhoto, result, classLoader)
            }
        } catch (_: Throwable) {
            // 该对象没有 livePhoto 字段，忽略
        }
    }

    /**
     * Hook 实况照片的显示过程
     * 在图片加载时检查是否为实况照片
     */
    private fun hookLivePhotoDisplay(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // Hook 图片加载相关的类
            val loadClasses = listOf(
                "com.ss.android.ugc.aweme.feed.model.Aweme",
                "com.ss.android.ugc.aweme.detail.model.DetailAweme"
            )

            for (className in loadClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)

                    // 查找获取图片列表的方法
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
                // 检查每个图片是否有 livePhoto 字段
                val clazz = image::class.java
                val livePhotoField = try {
                    clazz.getDeclaredField("livePhoto")
                } catch (_: NoSuchFieldException) {
                    try {
                        clazz.getDeclaredField("live_photo")
                    } catch (_: NoSuchFieldException) {
                        null
                    }
                }

                if (livePhotoField != null) {
                    livePhotoField.isAccessible = true
                    val livePhoto = livePhotoField.get(image)
                    if (livePhoto != null) {
                        HookUtils.log("$TAG: 图片列表中发现实况照片")
                        processLivePhoto(livePhoto, image, classLoader)
                    }
                }
            } catch (t: Throwable) {
                // 忽略单个图片的处理错误
            }
        }
    }

    /**
     * 处理实况照片数据
     * 提取图片和视频 URL，并触发下载
     *
     * @param livePhoto 实况照片数据对象
     * @param parentImage 父级图片对象，用于获取图片 URL
     * @param classLoader 类加载器
     */
    private fun processLivePhoto(livePhoto: Any, parentImage: Any, classLoader: ClassLoader) {
        try {
            // 提取视频 URL
            val videoUrl = extractLivePhotoVideoUrl(livePhoto)
            // 提取图片 URL
            val imageUrl = extractLivePhotoImageUrl(parentImage)

            if (videoUrl != null && imageUrl != null) {
                HookUtils.log("$TAG: 实况照片 - 视频: $videoUrl")
                HookUtils.log("$TAG: 实况照片 - 图片: $imageUrl")

                // 下载视频部分
                val context = ContextHelper.getContext()
                if (context != null) {
                    val timestamp = System.currentTimeMillis()
                    MediaDownloader.download(context, videoUrl, "live_photo_${timestamp}.mov")
                    MediaDownloader.download(context, imageUrl, "live_photo_${timestamp}.heic")
                    HookUtils.showToast(context, "正在保存实况照片...")
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
            // 尝试各种可能的字段名
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

            // 尝试通过反射查找所有 String 字段
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
