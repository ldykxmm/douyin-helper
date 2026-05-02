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
 * Feed 流视频 Hook
 *
 * 功能:
 * - Hook Aweme 数据模型，拦截视频信息
 * - 从 video.play_addr.url_list 提取视频 URL
 * - 替换为无水印 URL (playwm → play)
 * - 支持 H264/H265 多码率
 * - 缓存当前 Aweme 对象供 SharePanelHook 使用
 *
 * 抖音视频数据结构(大致):
 * Aweme -> video -> play_addr -> url_list[]
 * Aweme -> video -> play_addr_h264 -> url_list[]
 * Aweme -> video -> bit_rate[] -> play_addr -> url_list[]
 * Aweme -> images[] -> url_list[]  (图集)
 */
class FeedHook : BaseHook {

    companion object {
        private const val TAG = "FeedHook"
    }

    override fun init(classLoader: ClassLoader) {
        hookAwemeModel(classLoader)
    }

    /**
     * Hook Aweme 数据模型
     * 拦截视频数据的设置过程，替换为无水印 URL
     */
    private fun hookAwemeModel(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val awemeClass = ClassFinder.findClass(
                classLoader,
                listOf(
                    "com.ss.android.ugc.aweme.feed.model.Aweme",
                    "com.ss.ugc.aweme.Aweme"
                )
            )

            if (awemeClass != null) {
                hookVideoField(awemeClass, classLoader)
                // 缓存当前 Aweme
                hookAwemeCache(awemeClass)
            } else {
                HookUtils.log("$TAG: 未找到 Aweme 类，尝试备用方案")
                hookBySignature(classLoader)
            }
        }
    }

    /**
     * 缓存当前 Aweme 对象
     *
     * Hook Aweme 的 setter 或包含 Aweme 参数的方法，
     * 当 Feed 流设置当前 Aweme 时缓存它。
     */
    private fun hookAwemeCache(awemeClass: Class<*>) {
        HookUtils.safeHook {
            // Hook 常见的包含 Aweme 参数的类
            val containerClasses = listOf(
                "com.ss.android.ugc.aweme.feed.model.VideoItemParams",
                "com.ss.android.ugc.aweme.feed.param.FeedParam"
            )

            for (className in containerClasses) {
                try {
                    val clazz = Class.forName(className, false, awemeClass.classLoader)

                    // Hook 设置 Aweme 的方法
                    clazz.declaredMethods
                        .filter { method ->
                            method.parameterTypes.size == 1 &&
                            (method.parameterTypes[0] == awemeClass ||
                             method.name.contains("aweme", ignoreCase = true) ||
                             method.name.contains("Aweme", ignoreCase = true))
                        }
                        .forEach { method ->
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    try {
                                        val arg = param.args.firstOrNull()
                                        if (arg != null && awemeClass.isInstance(arg)) {
                                            MediaCache.setCurrentAweme(arg)
                                        }
                                    } catch (_: Throwable) {}
                                }
                            })
                        }
                    break
                } catch (_: ClassNotFoundException) { }
            }

            // 额外: Hook Aweme 的所有 setter 方法
            awemeClass.declaredMethods
                .filter { method ->
                    method.parameterTypes.size == 1 &&
                    method.name.startsWith("set") &&
                    method.name.length > 3
                }
                .take(10)  // 限制数量，避免过度 hook
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                // Aweme 的 setter 被调用说明该 Aweme 正在被使用
                                val aweme = param.thisObject
                                MediaCache.setCurrentAweme(aweme)
                            } catch (_: Throwable) {}
                        }
                    })
                }
        }
    }

    /**
     * 通过已知类名 Hook 视频字段
     */
    private fun hookVideoField(awemeClass: Class<*>, classLoader: ClassLoader) {
        HookUtils.safeHook {
            val videoMethods = awemeClass.declaredMethods.filter { method ->
                method.returnType.name.contains("Video") ||
                method.name.contains("getVideo") ||
                method.name == "getVideo"
            }

            for (method in videoMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            // 缓存 Aweme
                            MediaCache.setCurrentAweme(param.thisObject)
                            processVideoObject(param.result, classLoader)
                        } catch (t: Throwable) {
                            HookUtils.log("$TAG: 处理视频对象失败: ${t.message}")
                        }
                    }
                })
            }

            // 同时 Hook 图集相关方法
            val imageMethods = awemeClass.declaredMethods.filter { method ->
                method.returnType.name.contains("List") &&
                (method.name.contains("Image") || method.name.contains("image") ||
                 method.name.contains("Pic") || method.name.contains("pic"))
            }

            for (method in imageMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            MediaCache.setCurrentAweme(param.thisObject)
                        } catch (_: Throwable) {}
                    }
                })
            }

            HookUtils.log("$TAG: 已 Hook ${videoMethods.size} 个视频方法, ${imageMethods.size} 个图片方法")
        }
    }

    /**
     * 处理视频对象，提取并替换无水印 URL
     */
    private fun processVideoObject(videoObj: Any?, classLoader: ClassLoader) {
        if (videoObj == null) return

        try {
            processPlayAddr(XposedHelpers.getObjectField(videoObj, "playAddr"), "playAddr")

            try {
                processPlayAddr(XposedHelpers.getObjectField(videoObj, "playAddrH264"), "playAddrH264")
            } catch (_: Throwable) { }

            try {
                val bitRateArray = XposedHelpers.getObjectField(videoObj, "bitRate") as? Array<*>
                bitRateArray?.forEach { bitRateObj ->
                    if (bitRateObj != null) {
                        processPlayAddr(
                            XposedHelpers.getObjectField(bitRateObj, "playAddr"),
                            "bitRate"
                        )
                    }
                }
            } catch (_: Throwable) { }

        } catch (t: Throwable) {
            HookUtils.log("$TAG: processVideoObject 失败: ${t.message}")
        }
    }

    /**
     * 处理 play_addr 对象，提取 URL 列表并替换
     */
    private fun processPlayAddr(playAddr: Any?, source: String) {
        if (playAddr == null) return

        try {
            val urlList = XposedHelpers.getObjectField(playAddr, "urlList") as? List<*>
            if (urlList.isNullOrEmpty()) return

            val originalUrl = urlList.firstOrNull { it is String && it.toString().isNotEmpty() } as? String
                ?: return

            val noWmUrl = UrlParser.getNoWatermarkUrl(originalUrl)

            if (noWmUrl != originalUrl) {
                HookUtils.log("$TAG: [$source] 发现水印URL，已替换")

                val newList = mutableListOf<String>()
                newList.add(noWmUrl)
                urlList.filterIsInstance<String>().drop(1).forEach { newList.add(it) }

                XposedHelpers.setObjectField(playAddr, "urlList", newList)
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: processPlayAddr($source) 失败: ${t.message}")
        }
    }

    /**
     * 备用方案: 通过方法签名特征查找并 Hook
     */
    private fun hookBySignature(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val urlClasses = listOf(
                "com.ss.android.ugc.aweme.feed.model.VideoItemParams",
                "com.ss.android.ugc.aweme.feed.model.Video"
            )

            for (className in urlClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到备用类: $className")

                    clazz.declaredMethods
                        .filter { it.returnType == String::class.java && it.parameterCount == 0 }
                        .forEach { method ->
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    val value = param.result as? String ?: return
                                    if (value.contains("playwm") || value.contains("douyinvod")) {
                                        val newUrl = UrlParser.getNoWatermarkUrl(value)
                                        if (newUrl != value) {
                                            param.result = newUrl
                                            HookUtils.log("$TAG: [备用] 替换URL成功")
                                        }
                                    }
                                }
                            })
                        }
                } catch (_: ClassNotFoundException) { }
            }
        }
    }
}
