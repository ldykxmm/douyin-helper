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
 * 评论区 Hook
 *
 * 功能:
 * - Hook 评论数据模型
 * - 拦截评论中的图片/实况
 * - 提取评论区媒体 URL
 *
 * 评论区数据结构(大致):
 * Comment -> image_list[] -> url_list[]
 * Comment -> sticker -> url_list[]
 */
class CommentHook : BaseHook {

    companion object {
        private const val TAG = "CommentHook"
    }

    override fun init(classLoader: ClassLoader) {
        hookCommentModel(classLoader)
    }

    /**
     * Hook 评论数据模型
     * 拦截评论数据的加载过程，提取其中的媒体 URL
     */
    private fun hookCommentModel(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val commentClasses = listOf(
                "com.ss.android.ugc.aweme.comment.model.Comment",
                "com.ss.android.ugc.aweme.comment.CommentModel"
            )

            for (className in commentClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到评论类: $className")

                    // Hook getImageList 方法
                    val imageMethods = clazz.declaredMethods.filter { method ->
                        method.name.contains("Image") || method.name.contains("image") ||
                        method.name.contains("Sticker") || method.name.contains("sticker") ||
                        method.name.contains("Media") || method.name.contains("media")
                    }

                    for (method in imageMethods) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    processCommentMedia(param.result, classLoader)
                                } catch (t: Throwable) {
                                    HookUtils.log("$TAG: 处理评论媒体失败: ${t.message}")
                                }
                            }
                        })
                    }

                    HookUtils.log("$TAG: 已 Hook ${imageMethods.size} 个评论媒体方法")
                    break
                } catch (_: ClassNotFoundException) { }
            }

            // 同时 Hook 评论列表的加载
            hookCommentListLoad(classLoader)
        }
    }

    /**
     * Hook 评论列表加载
     * 在评论数据加载时批量提取媒体 URL
     */
    private fun hookCommentListLoad(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val listClasses = listOf(
                "com.ss.android.ugc.aweme.comment.model.CommentListResponse",
                "com.ss.android.ugc.aweme.comment.api.CommentApi"
            )

            for (className in listClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)

                    // Hook 评论列表数据获取方法
                    clazz.declaredMethods
                        .filter { it.name.contains("Comment") || it.name.contains("comment") }
                        .forEach { method ->
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    try {
                                        processCommentListResponse(param.result, classLoader)
                                    } catch (t: Throwable) {
                                        HookUtils.log("$TAG: 处理评论列表失败: ${t.message}")
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
     * 处理评论列表响应
     */
    private fun processCommentListResponse(response: Any?, classLoader: ClassLoader) {
        if (response == null) return

        try {
            // 获取评论列表
            val comments = XposedHelpers.getObjectField(response, "comments") as? List<*>
            comments?.forEach { comment ->
                processCommentItem(comment, classLoader)
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 解析评论列表失败: ${t.message}")
        }
    }

    /**
     * 处理单个评论项
     */
    private fun processCommentItem(comment: Any?, classLoader: ClassLoader) {
        if (comment == null) return

        try {
            // 处理评论图片列表
            val imageList = XposedHelpers.getObjectField(comment, "imageList")
                ?: XposedHelpers.getObjectField(comment, "image_list")

            if (imageList is List<*>) {
                for (image in imageList) {
                    processCommentImage(image, classLoader)
                }
            }

            // 处理评论表情/贴纸
            val sticker = XposedHelpers.getObjectField(comment, "sticker")
            if (sticker != null) {
                processSticker(sticker, classLoader)
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 处理评论项失败: ${t.message}")
        }
    }

    /**
     * 处理评论图片
     */
    private fun processCommentImage(image: Any?, classLoader: ClassLoader) {
        if (image == null) return

        try {
            val urlList = XposedHelpers.getObjectField(image, "urlList")
                ?: XposedHelpers.getObjectField(image, "url_list")

            if (urlList is List<*>) {
                val urls = urlList.filterIsInstance<String>()
                for (url in urls) {
                    if (url.isNotEmpty()) {
                        val noWmUrl = UrlParser.getNoWatermarkUrl(url)
                        HookUtils.log("$TAG: 评论区图片URL: $noWmUrl")
                        // 可以在这里触发下载或记录 URL
                    }
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 处理评论图片失败: ${t.message}")
        }
    }

    /**
     * 处理贴纸/表情
     */
    private fun processSticker(sticker: Any, classLoader: ClassLoader) {
        try {
            val url = XposedHelpers.getObjectField(sticker, "url") as? String
            if (!url.isNullOrEmpty()) {
                HookUtils.log("$TAG: 评论区贴纸URL: $url")
            }
        } catch (_: Throwable) { }
    }

    /**
     * 处理评论媒体对象
     */
    private fun processCommentMedia(mediaObj: Any?, classLoader: ClassLoader) {
        if (mediaObj == null) return

        try {
            when (mediaObj) {
                is List<*> -> {
                    for (item in mediaObj) {
                        processCommentMedia(item, classLoader)
                    }
                }
                else -> {
                    // 尝试提取 URL 字段
                    val clazz = mediaObj::class.java
                    clazz.declaredFields
                        .filter { it.type == String::class.java }
                        .forEach { field ->
                            field.isAccessible = true
                            val value = field.get(mediaObj) as? String
                            if (value != null && UrlParser.isImageUrl(value)) {
                                HookUtils.log("$TAG: 发现媒体URL: $value")
                            }
                        }
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: processCommentMedia 失败: ${t.message}")
        }
    }
}
