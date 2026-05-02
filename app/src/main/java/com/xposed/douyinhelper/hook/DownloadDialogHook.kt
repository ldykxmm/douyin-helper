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
 * 下载弹窗 Hook
 *
 * 功能:
 * - 拦截长按保存等操作
 * - 替换保存的 URL 为无水印版本
 * - 增强保存功能
 *
 * 抖音的长按菜单通常会触发保存操作，
 * 我们拦截保存过程中的 URL，替换为无水印版本。
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

                    // Hook 保存相关方法
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
            // Hook BottomSheetDialog 或类似组件
            val menuClasses = listOf(
                "com.ss.android.ugc.aweme.feed.ui.LongPressLayout",
                "com.ss.android.ugc.aweme.feed.ui.FeedBottomSheetDialog",
                "com.google.android.material.bottomsheet.BottomSheetDialog"
            )

            for (className in menuClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到菜单类: $className")

                    // Hook 点击事件处理
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
                                        // 检查是否有 URL 相关参数
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

            // 同时 Hook URL 拦截器
            hookUrlInterceptor(classLoader)
        }
    }

    /**
     * Hook URL 拦截器
     * 抖音内部可能有专门处理保存 URL 的拦截器
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
