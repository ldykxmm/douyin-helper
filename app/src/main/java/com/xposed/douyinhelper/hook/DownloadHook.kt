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
 * 下载流程 Hook — 双策略
 *
 * 策略1: Hook OkHttp 拦截器，拦截所有 HTTP 请求替换 URL
 * 策略2: Hook 抖音下载管理类（如果存在）
 */
class DownloadHook : BaseHook {

    companion object {
        private const val TAG = "DownloadHook"
    }

    override fun init(classLoader: ClassLoader) {
        hookOkHttpInterceptor(classLoader)
        hookDownloadManager(classLoader)
    }

    /**
     * Hook OkHttp RealCall — 拦截所有 HTTP 请求
     *
     * 这是最通用的策略，不依赖抖音内部类名
     * 在请求发出前检查 URL 是否包含水印标记
     */
    private fun hookOkHttpInterceptor(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // 尝试多种 OkHttp 类名
            val callClasses = listOf(
                "okhttp3.internal.http.RealInterceptorChain",
                "okhttp3.RealCall",
                "com.ss.android.ugc.aweme.okhttp.OkHttpCall"
            )

            for (className in callClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)

                    // Hook proceed 方法（拦截器链）
                    val proceedMethod = try {
                        clazz.getDeclaredMethod("proceed",
                            Class.forName("okhttp3.Request", false, classLoader))
                    } catch (_: Throwable) { null }

                    if (proceedMethod != null) {
                        XposedBridge.hookMethod(proceedMethod, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val request = param.args[0]
                                    replaceUrlInOkHttpRequest(request, classLoader)
                                } catch (_: Throwable) {}
                            }
                        })
                        HookUtils.log("$TAG: Hook OkHttp ${className}.proceed()")
                        break
                    }
                } catch (_: ClassNotFoundException) {}
            }
        }
    }

    /**
     * 替换 OkHttp Request 中的 URL
     */
    private fun replaceUrlInOkHttpRequest(request: Any, classLoader: ClassLoader) {
        try {
            val requestClass = request::class.java

            // 获取 Request.url()
            val urlMethod = requestClass.getMethod("url")
            val httpUrl = urlMethod.invoke(request) ?: return
            val urlStr = httpUrl.toString()

            if (!urlStr.contains("playwm")) return

            val newUrlStr = UrlParser.getNoWatermarkUrl(urlStr)
            if (newUrlStr == urlStr) return

            // 替换 URL: 创建新的 HttpUrl
            val httpUrlClass = Class.forName("okhttp3.HttpUrl", false, classLoader)
            val parseMethod = httpUrlClass.getMethod("get", String::class.java)
            val newHttpUrl = parseMethod.invoke(null, newUrlStr)

            // 替换 Request 中的 url 字段
            val urlField = try { requestClass.getDeclaredField("url") } catch (_: Throwable) { null }
            if (urlField != null) {
                urlField.isAccessible = true
                urlField.set(request, newHttpUrl)
                HookUtils.log("$TAG: OkHttp URL 已替换")
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 替换 OkHttp URL 失败: ${t.message}")
        }
    }

    /**
     * Hook 抖音下载管理类 — 备用策略
     */
    private fun hookDownloadManager(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val downloadClasses = listOf(
                "com.ss.android.ugc.aweme.download.api.DownloadManager",
                "com.ss.android.ugc.aweme.download.DownloadManager",
                "com.ss.android.ugc.download.api.DownloadManager"
            )

            for (className in downloadClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到下载管理类: $className")

                    clazz.declaredMethods.forEach { method ->
                        val hasStringParam = method.parameterTypes.any { it == String::class.java }
                        if (hasStringParam) {
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    for (i in param.args.indices) {
                                        val arg = param.args[i]
                                        if (arg is String && arg.contains("playwm")) {
                                            param.args[i] = UrlParser.getNoWatermarkUrl(arg)
                                            HookUtils.log("$TAG: 下载管理器 URL 已替换")
                                        }
                                    }
                                }
                            })
                        }
                    }
                    break
                } catch (_: ClassNotFoundException) {}
            }
        }
    }
}
