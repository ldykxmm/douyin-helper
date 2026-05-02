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
 * 下载弹窗 Hook
 *
 * 功能:
 * - 拦截长按保存等操作
 * - 替换保存的 URL 为无水印版本
 * - 评论区实况照片长按直接保存 (图片+视频)
 *
 * 评论区实况照片流程:
 * 1. 用户点击评论区实况照片 → 进入浏览 (LivePhotoHook 缓存 URL)
 * 2. 用户长按 → 弹出菜单 → 点击下载
 * 3. 本 Hook 检测到缓存中有实况照片 → 直接保存图片+视频
 */
class DownloadDialogHook : BaseHook {

    companion object {
        private const val TAG = "DownloadDialogHook"
    }

    override fun init(classLoader: ClassLoader) {
        hookSaveDialog(classLoader)
        hookLongPressMenu(classLoader)
    }

    /**
     * Hook 保存对话框
     * 拦截保存操作的 URL
     */
    private fun hookSaveDialog(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val dialogClasses = listOf(
                "com.ss.android.ugc.aweme.feed.ui.SaveDialog",
                "com.ss.android.ugc.aweme.detail.ui.SaveDialog",
                "com.ss.android.ugc.aweme.download.SaveDialog"
            )

            for (className in dialogClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到保存对话框类: $className")

                    clazz.declaredMethods
                        .filter { method ->
                            method.name.contains("save", ignoreCase = true) ||
                            method.name.contains("download", ignoreCase = true) ||
                            method.name.contains("Save", ignoreCase = true)
                        }
                        .forEach { method ->
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        // 先检查是否为实况照片
                                        if (tryHandleLivePhotoSave()) {
                                            param.result = null  // 阻止原方法执行
                                            return
                                        }
                                        replaceUrlInAllArgs(param)
                                    } catch (t: Throwable) {
                                        HookUtils.log("$TAG: 替换URL失败: ${t.message}")
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
     * Hook 长按菜单
     * 拦截长按弹出的保存选项
     */
    private fun hookLongPressMenu(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val menuClasses = listOf(
                "com.ss.android.ugc.aweme.feed.ui.LongPressLayout",
                "com.ss.android.ugc.aweme.feed.ui.FeedBottomSheetDialog",
                "com.google.android.material.bottomsheet.BottomSheetDialog"
            )

            for (className in menuClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到菜单类: $className")

                    clazz.declaredMethods
                        .filter { method ->
                            method.name.contains("onClick", ignoreCase = true) ||
                            method.name.contains("onItemClick", ignoreCase = true) ||
                            method.name.contains("onMenuItemClick", ignoreCase = true)
                        }
                        .forEach { method ->
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        HookUtils.log("$TAG: 长按菜单点击: ${method.name}")

                                        // 检查是否为实况照片保存
                                        if (tryHandleLivePhotoSave()) {
                                            param.result = null
                                            return
                                        }

                                        replaceUrlInAllArgs(param)
                                    } catch (t: Throwable) {
                                        HookUtils.log("$TAG: 处理菜单点击失败: ${t.message}")
                                    }
                                }
                            })
                        }

                    break
                } catch (_: ClassNotFoundException) { }
            }

            // Hook 系统级长按菜单 (Android ContextMenu)
            hookContextMenu(classLoader)

            hookUrlInterceptor(classLoader)
        }
    }

    /**
     * Hook 系统 ContextMenu
     *
     * 抖音评论区实况照片长按时可能弹出系统级上下文菜单，
     * 包含"保存"、"分享"等选项。
     */
    private fun hookContextMenu(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // Hook MenuItem 点击
            val menuItemClass = XposedHelpers.findClass("android.view.MenuItem", classLoader)

            // Hook Activity.onCreateContextMenu
            val activityClass = XposedHelpers.findClass("android.app.Activity", classLoader)
            XposedBridge.hookAllMethods(activityClass, "onContextItemSelected", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val menuItem = param.args.firstOrNull() ?: return
                        val title = HookUtils.callMethod(menuItem, "getTitle")?.toString() ?: ""

                        // 检查是否为保存相关选项
                        if (title.contains("保存") || title.contains("下载") ||
                            title.contains("save", ignoreCase = true) ||
                            title.contains("download", ignoreCase = true)) {

                            // 尝试处理实况照片
                            if (tryHandleLivePhotoSave()) {
                                HookUtils.log("$TAG: 实况照片已通过上下文菜单保存")
                                // 设置返回值为 true 表示已处理
                                param.result = true
                            }
                        }
                    } catch (t: Throwable) {
                        HookUtils.log("$TAG: 处理上下文菜单失败: ${t.message}")
                    }
                }
            })
        }
    }

    /**
     * 尝试处理实况照片保存
     *
     * 检查 MediaCache 中是否有缓存的实况照片 URL，
     * 如果有则直接下载图片和视频部分。
     *
     * @return true 如果处理了实况照片，false 如果没有缓存
     */
    private fun tryHandleLivePhotoSave(): Boolean {
        val context = ContextHelper.getContext() ?: return false

        // 遍历缓存查找实况照片
        // 优先使用最新的缓存
        val allCached = MediaCache.getAllCachedLivePhotos()
        if (allCached.isEmpty()) return false

        // 取最新的一个
        val latest = allCached.maxByOrNull { it.key } ?: return false
        val (imageUrl, videoUrl) = latest.value

        HookUtils.log("$TAG: 检测到实况照片缓存，直接保存")
        HookUtils.showToast(context, "正在保存实况照片...")

        MediaCache.downloadLivePhoto(context, imageUrl, videoUrl)
        MediaCache.removeLivePhoto(latest.key)

        return true
    }

    /**
     * Hook URL 拦截器
     */
    private fun hookUrlInterceptor(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val interceptorClasses = listOf(
                "com.ss.android.ugc.aweme.utils.UrlInterceptor",
                "com.ss.android.ugc.aweme.interceptor.UrlInterceptor"
            )

            for (className in interceptorClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到URL拦截器: $className")

                    clazz.declaredMethods.forEach { method ->
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    replaceUrlInAllArgs(param)
                                } catch (_: Throwable) { }
                            }
                        })
                    }

                    break
                } catch (_: ClassNotFoundException) { }
            }
        }
    }

    /**
     * 替换方法参数中所有 URL
     */
    private fun replaceUrlInAllArgs(param: XC_MethodHook.MethodHookParam) {
        for (i in param.args.indices) {
            val arg = param.args[i]
            if (arg is String) {
                if (arg.contains("playwm") || arg.contains("douyinvod") || arg.contains("bytecdn")) {
                    val newUrl = UrlParser.getNoWatermarkUrl(arg)
                    if (newUrl != arg) {
                        param.args[i] = newUrl
                        HookUtils.log("$TAG: 替换参数[$i] URL为无水印版本")
                    }
                }
            }
        }
    }
}
