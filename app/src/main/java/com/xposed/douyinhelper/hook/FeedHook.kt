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
 * Feed 流视频 Hook
 *
 * 功能:
 * - Hook Aweme 数据模型，拦截视频信息
 * - 从 video.play_addr.url_list 提取视频 URL
 * - 替换为无水印 URL (playwm → play)
 * - 支持 H264/H265 多码率
 *
 * 抖音视频数据结构(大致):
 * Aweme -> video -> play_addr -> url_list[]
 * Aweme -> video -> play_addr_h264 -> url_list[]
 * Aweme -> video -> bit_rate[] -> play_addr -> url_list[]
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
            // 尝试查找 Aweme 视频模型类
            // 抖音的类名会混淆，通过特征字段来定位
            val awemeClass = ClassFinder.findClass(
                classLoader,
                listOf(
                    "com.ss.android.ugc.aweme.feed.model.Aweme",
                    "com.ss.ugc.aweme.Aweme"
                )
            )

            if (awemeClass != null) {
                hookVideoField(awemeClass, classLoader)
            } else {
                HookUtils.log("$TAG: 未找到 Aweme 类，尝试备用方案")
                hookBySignature(classLoader)
            }
        }
    }

    /**
     * 通过已知类名 Hook 视频字段
     */
    private fun hookVideoField(awemeClass: Class<*>, classLoader: ClassLoader) {
        HookUtils.safeHook {
            // Hook getVideo 方法或 video 字段的 getter
            val videoMethods = awemeClass.declaredMethods.filter { method ->
                method.returnType.name.contains("Video") ||
                method.name.contains("getVideo") ||
                method.name == "getVideo"
            }

            for (method in videoMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            processVideoObject(param.result, classLoader)
                        } catch (t: Throwable) {
                            HookUtils.log("$TAG: 处理视频对象失败: ${t.message}")
                        }
                    }
                })
            }

            HookUtils.log("$TAG: 已 Hook ${videoMethods.size} 个视频相关方法")
        }
    }

    /**
     * 处理视频对象，提取并替换无水印 URL
     */
    private fun processVideoObject(videoObj: Any?, classLoader: ClassLoader) {
        if (videoObj == null) return

        try {
            // 获取 play_addr (标准画质)
            processPlayAddr(XposedHelpers.getObjectField(videoObj, "playAddr"), "playAddr")

            // 尝试 H264 地址
            try {
                processPlayAddr(XposedHelpers.getObjectField(videoObj, "playAddrH264"), "playAddrH264")
            } catch (_: Throwable) { }

            // 尝试 bit_rate 数组 (多码率)
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

            // 提取第一个有效 URL
            val originalUrl = urlList.firstOrNull { it is String && it.toString().isNotEmpty() } as? String
                ?: return

            // 转换为无水印 URL
            val noWmUrl = UrlParser.getNoWatermarkUrl(originalUrl)

            if (noWmUrl != originalUrl) {
                HookUtils.log("$TAG: [$source] 发现水印URL，已替换")
                HookUtils.log("$TAG: 原始: $originalUrl")
                HookUtils.log("$TAG: 替换: $noWmUrl")

                // 替换 URL 列表
                val newList = mutableListOf<String>()
                newList.add(noWmUrl)
                // 保留其他 URL 作为备用
                urlList.filterIsInstance<String>().drop(1).forEach { newList.add(it) }

                XposedHelpers.setObjectField(playAddr, "urlList", newList)
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: processPlayAddr($source) 失败: ${t.message}")
        }
    }

    /**
     * 备用方案: 通过方法签名特征查找并 Hook
     * 适用于类名完全混淆的情况
     */
    private fun hookBySignature(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // 尝试直接 Hook URL 设置相关的方法
            // 抖音内部的网络请求会经过特定的 URL 处理流程
            val urlClasses = listOf(
                "com.ss.android.ugc.aweme.feed.model.VideoItemParams",
                "com.ss.android.ugc.aweme.feed.model.Video"
            )

            for (className in urlClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到备用类: $className")

                    // Hook 所有返回 String 的方法，检查是否返回 URL
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
