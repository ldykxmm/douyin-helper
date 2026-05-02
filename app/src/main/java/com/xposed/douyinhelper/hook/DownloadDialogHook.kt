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
 * 下载弹窗 Hook — 自适应版
 *
 * Hook 系统级 ContextMenu 和 BottomSheet，
 * 拦截保存操作并替换 URL
 */
class DownloadDialogHook : BaseHook {

    companion object {
        private const val TAG = "DownloadDialogHook"
    }

    override fun init(classLoader: ClassLoader) {
        hookContextMenu(classLoader)
        hookDialogClicks(classLoader)
    }

    /**
     * Hook Activity.onContextItemSelected
     * 拦截长按菜单中的"保存"/"下载"选项
     */
    private fun hookContextMenu(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val activityClass = XposedHelpers.findClass("android.app.Activity", classLoader)

            XposedBridge.hookAllMethods(activityClass, "onContextItemSelected", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val menuItem = param.args.firstOrNull() ?: return
                        val title = HookUtils.callMethod(menuItem, "getTitle")?.toString() ?: ""

                        if (title.contains("保存") || title.contains("下载") ||
                            title.contains("save", ignoreCase = true) ||
                            title.contains("download", ignoreCase = true)) {

                            if (tryHandleLivePhotoSave()) {
                                param.result = true
                                return
                            }

                            replaceUrlInArgs(param)
                        }
                    } catch (t: Throwable) {
                        HookUtils.log("$TAG: 处理上下文菜单失败: ${t.message}")
                    }
                }
            })

            HookUtils.log("$TAG: ContextMenu Hook 已安装")
        }
    }

    /**
     * Hook 各种 Dialog 的点击事件
     */
    private fun hookDialogClicks(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // Hook 所有 Dialog 子类的 onClick
            val dialogClasses = listOf(
                "com.google.android.material.bottomsheet.BottomSheetDialog",
                "android.app.Dialog"
            )

            for (className in dialogClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    XposedBridge.hookAllMethods(clazz, "onClick", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (tryHandleLivePhotoSave()) {
                                    param.result = null
                                    return
                                }
                                replaceUrlInArgs(param)
                            } catch (_: Throwable) {}
                        }
                    })
                } catch (_: ClassNotFoundException) {}
            }

            // 通用方案: Hook View.OnClickListener
            hookOnClickListener(classLoader)
        }
    }

    /**
     * Hook OnClickListener — 最通用的点击拦截
     */
    private fun hookOnClickListener(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val viewClass = XposedHelpers.findClass("android.view.View", classLoader)

            XposedBridge.hookAllMethods(viewClass, "performClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? android.view.View ?: return
                        val text = extractViewText(view)

                        if (text.contains("保存") || text.contains("下载") ||
                            text.contains("save", ignoreCase = true)) {

                            if (tryHandleLivePhotoSave()) {
                                return
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })
        }
    }

    /**
     * 从 View 树提取文本
     */
    private fun extractViewText(view: android.view.View): String {
        val sb = StringBuilder()
        if (view is android.widget.TextView) {
            sb.append(view.text?.toString() ?: "")
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                sb.append(extractViewText(view.getChildAt(i)))
            }
        }
        return sb.toString()
    }

    /**
     * 尝试处理实况照片保存
     */
    private fun tryHandleLivePhotoSave(): Boolean {
        val context = ContextHelper.getContext() ?: return false
        val allCached = MediaCache.getAllCachedLivePhotos()
        if (allCached.isEmpty()) return false

        val latest = allCached.maxByOrNull { it.key } ?: return false
        val (imageUrl, videoUrl) = latest.value

        HookUtils.log("$TAG: 检测到实况照片缓存，直接保存")
        HookUtils.showToast(context, "正在保存实况照片...")

        MediaCache.downloadLivePhoto(context, imageUrl, videoUrl)
        MediaCache.removeLivePhoto(latest.key)
        return true
    }

    /**
     * 替换方法参数中的 URL
     */
    private fun replaceUrlInArgs(param: XC_MethodHook.MethodHookParam) {
        for (i in param.args.indices) {
            val arg = param.args[i]
            if (arg is String && arg.contains("playwm")) {
                val newUrl = UrlParser.getNoWatermarkUrl(arg)
                if (newUrl != arg) {
                    param.args[i] = newUrl
                    HookUtils.log("$TAG: 替换参数[$i] URL")
                }
            }
        }
    }
}
