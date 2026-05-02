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
 * 下载流程 Hook
 *
 * 功能:
 * - Hook 抖音自带的下载功能
 * - 拦截下载请求，替换为无水印 URL
 * - Hook 下载完成回调
 *
 * 抖音的下载流程通常经过:
 * 1. 用户点击下载按钮
 * 2. 构建下载请求 (包含 URL)
 * 3. 发起 HTTP 下载
 * 4. 保存到本地
 *
 * 我们在步骤 2 介入，替换 URL 为无水印版本。
 */
class DownloadHook : BaseHook {

    companion object {
        private const val TAG = "DownloadHook"
    }

    override fun init(classLoader: ClassLoader) {
        hookDownloadManager(classLoader)
        hookDownloadTask(classLoader)
    }

    /**
     * Hook 下载管理器
     * 拦截下载请求的创建过程
     */
    private fun hookDownloadManager(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // 尝试查找抖音的下载管理类
            val downloadClasses = listOf(
                "com.ss.android.ugc.aweme.download.api.DownloadManager",
                "com.ss.android.ugc.aweme.download.DownloadManager",
                "com.ss.android.ugc.download.api.DownloadManager"
            )

            for (className in downloadClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到下载管理类: $className")

                    // Hook 所有包含 URL 参数的方法
                    clazz.declaredMethods.forEach { method ->
                        val hasStringParam = method.parameterTypes.any { it == String::class.java }
                        if (hasStringParam) {
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        replaceUrlInArgs(param)
                                    } catch (t: Throwable) {
                                        HookUtils.log("$TAG: 替换URL失败: ${t.message}")
                                    }
                                }
                            })
                        }
                    }
                    break // 找到一个就够了
                } catch (_: ClassNotFoundException) { }
            }
        }
    }

    /**
     * Hook 下载任务
     * 拦截下载任务的 URL 设置
     */
    private fun hookDownloadTask(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val taskClasses = listOf(
                "com.ss.android.ugc.aweme.download.api.DownloadTask",
                "com.ss.android.ugc.download.api.DownloadTask"
            )

            for (className in taskClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到下载任务类: $className")

                    // Hook URL 设置方法
                    val urlSetters = clazz.declaredMethods.filter { method ->
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == String::class.java &&
                        (method.name.contains("url", ignoreCase = true) ||
                         method.name.contains("Url", ignoreCase = true) ||
                         method.name.contains("set", ignoreCase = true))
                    }

                    for (setter in urlSetters) {
                        XposedBridge.hookMethod(setter, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val url = param.args[0] as? String ?: return
                                if (url.contains("playwm") || url.contains("douyinvod")) {
                                    val newUrl = UrlParser.getNoWatermarkUrl(url)
                                    if (newUrl != url) {
                                        param.args[0] = newUrl
                                        HookUtils.log("$TAG: 下载URL已替换为无水印版本")
                                    }
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
     * 替换方法参数中的 URL
     */
    private fun replaceUrlInArgs(param: XC_MethodHook.MethodHookParam) {
        for (i in param.args.indices) {
            val arg = param.args[i]
            if (arg is String && (arg.contains("playwm") || arg.contains("douyinvod") || arg.contains("bytecdn"))) {
                val newUrl = UrlParser.getNoWatermarkUrl(arg)
                if (newUrl != arg) {
                    param.args[i] = newUrl
                    HookUtils.log("$TAG: 参数[$i] URL已替换")
                }
            }
        }
    }
}
