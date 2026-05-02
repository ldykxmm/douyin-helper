package com.xposed.douyinhelper.hook

import com.xposed.douyinhelper.util.ContextHelper
import com.xposed.douyinhelper.util.HookUtils
import com.xposed.douyinhelper.util.MediaDownloader
import com.xposed.douyinhelper.util.UrlParser
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 分享拦截 Hook
 *
 * 功能:
 * - Hook Intent 的 startActivity 方法
 * - 拦截抖音分享的 Intent
 * - 从 extras 中提取视频/图片信息
 * - 弹出保存选项
 *
 * 抖音分享时会通过 Intent 传递媒体 URL，
 * 我们拦截这些 URL 并替换为无水印版本。
 */
class ShareHook : BaseHook {

    companion object {
        private const val TAG = "ShareHook"
    }

    override fun init(classLoader: ClassLoader) {
        hookShareIntent(classLoader)
    }

    /**
     * Hook 分享 Intent
     * 拦截抖音发出的分享 Intent，提取其中的媒体 URL
     */
    private fun hookShareIntent(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // Hook Activity.startActivity(Intent)
            val activityClass = XposedHelpers.findClass(
                "android.app.Activity",
                classLoader
            )

            XposedBridge.hookAllMethods(activityClass, "startActivity", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val intent = param.args.firstOrNull() as? android.content.Intent ?: return
                        processShareIntent(intent, classLoader)
                    } catch (t: Throwable) {
                        HookUtils.log("$TAG: 处理分享Intent失败: ${t.message}")
                    }
                }
            })

            // 同时 Hook startActivityForResult
            XposedBridge.hookAllMethods(activityClass, "startActivityForResult", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        // startActivityForResult 的 Intent 参数位置可能不同
                        val intent = param.args.filterIsInstance<android.content.Intent>().firstOrNull() ?: return
                        processShareIntent(intent, classLoader)
                    } catch (t: Throwable) {
                        HookUtils.log("$TAG: 处理startActivityForResult失败: ${t.message}")
                    }
                }
            })

            HookUtils.log("$TAG: 分享Intent Hook 已安装")
        }
    }

    /**
     * 处理分享 Intent
     * 检查是否包含抖音媒体 URL，如果有则处理
     */
    private fun processShareIntent(intent: android.content.Intent, classLoader: ClassLoader) {
        val extras = intent.extras ?: return

        // 检查是否来自抖音
        val callingPackage = intent.`package` ?: ""
        val component = intent.component?.packageName ?: ""
        if (!callingPackage.contains("aweme") && !component.contains("aweme") &&
            !callingPackage.contains("douyin") && !component.contains("douyin")) {
            return
        }

        // 遍历 extras 查找 URL
        for (key in extras.keySet()) {
            val value = extras.get(key) ?: continue

            when (value) {
                is String -> {
                    if (UrlParser.isVideoUrl(value) || UrlParser.isImageUrl(value)) {
                        val noWmUrl = UrlParser.getNoWatermarkUrl(value)
                        HookUtils.log("$TAG: 发现分享URL [$key]")
                        // 可以在这里弹出保存对话框
                        downloadMedia(noWmUrl, classLoader)
                    }
                }
                is CharSequence -> {
                    val str = value.toString()
                    if (UrlParser.isVideoUrl(str) || UrlParser.isImageUrl(str)) {
                        val noWmUrl = UrlParser.getNoWatermarkUrl(str)
                        HookUtils.log("$TAG: 发现分享文本URL")
                        downloadMedia(noWmUrl, classLoader)
                    }
                }
                is android.os.Bundle -> {
                    // 递归检查嵌套 Bundle
                    processBundle(value, classLoader)
                }
            }
        }
    }

    /**
     * 递归处理 Bundle 中的 URL
     */
    private fun processBundle(bundle: android.os.Bundle, classLoader: ClassLoader) {
        for (key in bundle.keySet()) {
            val value = bundle.get(key) ?: continue
            if (value is String && (UrlParser.isVideoUrl(value) || UrlParser.isImageUrl(value))) {
                val noWmUrl = UrlParser.getNoWatermarkUrl(value)
                HookUtils.log("$TAG: Bundle中发现URL [$key]")
                downloadMedia(noWmUrl, classLoader)
            }
        }
    }

    /**
     * 下载媒体文件
     */
    private fun downloadMedia(url: String, classLoader: ClassLoader) {
        try {
            val context = ContextHelper.getContext()
            if (context != null) {
                val isVideo = UrlParser.isVideoUrl(url)
                val ext = if (isVideo) "mp4" else "jpg"
                MediaDownloader.download(context, url, "douyin_share_${System.currentTimeMillis()}.$ext")
                HookUtils.showToast(context, "正在保存无水印${if (isVideo) "视频" else "图片"}...")
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 下载失败: ${t.message}")
        }
    }
}
