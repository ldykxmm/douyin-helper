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
 * 评论区 Hook — 自适应版
 *
 * 不依赖硬编码类名，通过字段特征自动发现评论区媒体
 */
class CommentHook : BaseHook {

    companion object {
        private const val TAG = "CommentHook"
    }

    override fun init(classLoader: ClassLoader) {
        hookCommentModel(classLoader)
    }

    private fun hookCommentModel(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val commentClasses = listOf(
                "com.ss.android.ugc.aweme.comment.model.Comment",
                "com.ss.ugc.aweme.Comment"
            )

            val commentClass = ClassFinder.findClass(classLoader, commentClasses)

            if (commentClass != null) {
                HookUtils.log("$TAG: 找到 Comment 类: ${commentClass.name}")
                hookCommentGetters(commentClass, classLoader)
            } else {
                HookUtils.log("$TAG: 未找到 Comment 类，跳过")
            }
        }
    }

    /**
     * Hook Comment 的所有 getter，自适应扫描图片/实况照片
     */
    private fun hookCommentGetters(commentClass: Class<*>, classLoader: ClassLoader) {
        HookUtils.safeHook {
            val methods = commentClass.declaredMethods.filter { m ->
                m.parameterCount == 0 &&
                m.returnType != Void.TYPE::class.java &&
                m.returnType != Boolean::class.javaPrimitiveType &&
                m.returnType != Int::class.javaPrimitiveType &&
                m.returnType != Long::class.javaPrimitiveType
            }

            for (method in methods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val result = param.result ?: return
                            scanCommentMedia(result, classLoader)
                        } catch (_: Throwable) {}
                    }
                })
            }

            HookUtils.log("$TAG: Hooking ${methods.size} 个 Comment getter")
        }
    }

    /**
     * 递归扫描评论对象中的媒体
     */
    private fun scanCommentMedia(obj: Any, classLoader: ClassLoader, depth: Int = 0) {
        if (depth > 5) return

        val clazz = obj::class.java
        for (field in clazz.declaredFields) {
            try {
                field.isAccessible = true
                val value = field.get(obj) ?: continue

                val fieldName = field.name.lowercase()

                when (value) {
                    is String -> {
                        // 可能是图片 URL
                        if (value.startsWith("http") && UrlParser.isImageUrl(value)) {
                            HookUtils.log("$TAG: 发现评论图片 URL")
                            val context = ContextHelper.getContext()
                            if (context != null) {
                                val noWmUrl = UrlParser.getNoWatermarkUrl(value)
                                MediaDownloader.download(context, noWmUrl,
                                    "comment_img_${System.currentTimeMillis()}.jpg")
                                HookUtils.showToast(context, "正在保存评论图片...")
                            }
                        }
                    }
                    is List<*> -> {
                        // image_list, sticker 等
                        if (fieldName.contains("image") || fieldName.contains("pic") ||
                            fieldName.contains("sticker") || fieldName.contains("url")) {
                            processCommentImageList(value, classLoader)
                        }
                    }
                    else -> {
                        // 检查是否有 livePhoto 字段
                        if (fieldName.contains("live") || fieldName.contains("photo")) {
                            scanLivePhoto(value, classLoader)
                        }
                        scanCommentMedia(value, classLoader, depth + 1)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun processCommentImageList(list: List<*>, classLoader: ClassLoader) {
        for (item in list) {
            when (item) {
                is String -> {
                    if (item.startsWith("http") && UrlParser.isImageUrl(item)) {
                        val context = ContextHelper.getContext()
                        if (context != null) {
                            val noWmUrl = UrlParser.getNoWatermarkUrl(item)
                            MediaDownloader.download(context, noWmUrl,
                                "comment_img_${System.currentTimeMillis()}.jpg")
                        }
                    }
                }
                else -> if (item != null) scanCommentMedia(item, classLoader, depth = 2)
            }
        }
    }

    private fun scanLivePhoto(obj: Any, classLoader: ClassLoader) {
        val clazz = obj::class.java
        var videoUrl: String? = null
        var imageUrl: String? = null

        for (field in clazz.declaredFields) {
            try {
                field.isAccessible = true
                val value = field.get(obj) ?: continue

                when (value) {
                    is String -> {
                        if (value.startsWith("http")) {
                            if (UrlParser.isVideoUrl(value)) videoUrl = value
                            else if (UrlParser.isImageUrl(value)) imageUrl = value
                        }
                    }
                    is List<*> -> {
                        for (item in value) {
                            if (item is String && item.startsWith("http")) {
                                if (UrlParser.isVideoUrl(item)) videoUrl = item
                                else if (UrlParser.isImageUrl(item)) imageUrl = item
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        if (videoUrl != null && imageUrl != null) {
            val id = "comment_live_${System.currentTimeMillis()}"
            MediaCache.cacheLivePhotoUrls(id, imageUrl, videoUrl)
            HookUtils.log("$TAG: 缓存评论区实况照片")

            val context = ContextHelper.getContext()
            if (context != null) {
                MediaCache.downloadLivePhoto(context, imageUrl, videoUrl)
                HookUtils.showToast(context, "正在保存实况照片...")
            }
        }
    }
}
