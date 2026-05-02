package com.xposed.douyinhelper.hook

import com.xposed.douyinhelper.util.ClassFinder
import com.xposed.douyinhelper.util.ContextHelper
import com.xposed.douyinhelper.util.HookUtils
import com.xposed.douyinhelper.util.MediaCache
import com.xposed.douyinhelper.util.MediaDownloader
import com.xposed.douyinhelper.util.UrlParser
import com.xposed.douyinhelper.util.VideoFieldDumper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Feed 流视频 Hook
 *
 * 自适应策略:
 * 1. 优先通过类名查找 Aweme/Video
 * 2. 如果找到 Video 对象，自动扫描所有字段找 URL
 * 3. 不依赖硬编码字段名，兼容各版本抖音
 */
class FeedHook : BaseHook {

    companion object {
        private const val TAG = "FeedHook"
        private var dumped = false  // 只 dump 一次
    }

    override fun init(classLoader: ClassLoader) {
        hookAwemeModel(classLoader)
    }

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
                HookUtils.log("$TAG: 找到 Aweme 类: ${awemeClass.name}")
                hookAwemeGetters(awemeClass, classLoader)
            } else {
                HookUtils.log("$TAG: 未找到 Aweme 类")
            }
        }
    }

    /**
     * Hook Aweme 的所有返回非 void 的无参方法
     * 自动检测返回值是否包含视频/图片 URL
     */
    private fun hookAwemeGetters(awemeClass: Class<*>, classLoader: ClassLoader) {
        HookUtils.safeHook {
            val methods = awemeClass.declaredMethods.filter { m ->
                m.parameterCount == 0 &&
                m.returnType != Void.TYPE::class.java &&
                m.returnType != Boolean::class.javaPrimitiveType &&
                m.returnType != Int::class.javaPrimitiveType &&
                m.returnType != Long::class.javaPrimitiveType &&
                m.returnType != Float::class.javaPrimitiveType &&
                m.returnType != Double::class.javaPrimitiveType
            }

            HookUtils.log("$TAG: Hooking ${methods.size} 个 Aweme getter 方法")

            for (method in methods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val result = param.result ?: return
                            MediaCache.setCurrentAweme(param.thisObject)

                            // 首次命中时 dump 一次字段结构
                            if (!dumped) {
                                dumped = true
                                VideoFieldDumper.dumpAwemeFields(param.thisObject)
                            }

                            processResult(result, classLoader, method.name)
                        } catch (_: Throwable) {}
                    }
                })
            }
        }
    }

    /**
     * 自适应处理返回值
     * 递归扫描对象，找到包含 URL 的字段并替换
     */
    private fun processResult(result: Any, classLoader: ClassLoader, source: String) {
        when (result) {
            is String -> {
                if (result.contains("playwm") && UrlParser.isVideoUrl(result)) {
                    HookUtils.log("$TAG: [$source] 直接返回了视频URL，替换中")
                    // 注意：这里无法直接修改返回值，因为是在 afterHookedMethod
                    // 但我们可以记录 URL 供其他地方使用
                }
            }
            is List<*> -> {
                for (item in result) {
                    if (item != null) processResult(item, classLoader, "$source.list")
                }
            }
            else -> {
                // 扫描对象的所有字段
                scanAndReplaceUrls(result, classLoader, source, depth = 0)
            }
        }
    }

    /**
     * 递归扫描对象字段，查找并替换视频 URL
     * 最大深度 5 层，避免无限递归
     */
    private fun scanAndReplaceUrls(obj: Any, classLoader: ClassLoader, source: String, depth: Int) {
        if (depth > 5) return

        val clazz = obj::class.java
        val fields = try { clazz.declaredFields } catch (_: Throwable) { return }

        for (field in fields) {
            try {
                field.isAccessible = true
                val value = field.get(obj) ?: continue

                when (value) {
                    is String -> {
                        if (value.contains("playwm") || (value.contains("douyinvod") && value.contains("/play"))) {
                            val newUrl = UrlParser.getNoWatermarkUrl(value)
                            if (newUrl != value) {
                                field.set(obj, newUrl)
                                HookUtils.log("$TAG: [$source] 替换字段 ${clazz.simpleName}.${field.name}")
                            }
                        }
                    }
                    is List<*> -> {
                        replaceUrlList(value, obj, field, clazz, source)
                    }
                    else -> {
                        // 递归扫描子对象
                        scanAndReplaceUrls(value, classLoader, "$source.${field.name}", depth + 1)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * 替换 List 中的 URL
     */
    private fun replaceUrlList(
        list: List<*>, parent: Any, field: java.lang.reflect.Field,
        parentClass: Class<*>, source: String
    ) {
        var changed = false
        val newList = list.toMutableList()

        for (i in newList.indices) {
            val item = newList[i] ?: continue

            when (item) {
                is String -> {
                    if (item.contains("playwm") || (item.contains("douyinvod") && item.contains("/play"))) {
                        val newUrl = UrlParser.getNoWatermarkUrl(item)
                        if (newUrl != item) {
                            newList[i] = newUrl
                            changed = true
                            HookUtils.log("$TAG: [$source] 替换 ${parentClass.simpleName}.${field.name}[$i]")
                        }
                    }
                }
                else -> {
                    // 递归处理 List 中的复杂对象
                    scanAndReplaceUrls(item, parent.javaClass.classLoader ?: return,
                        "$source.${field.name}[$i]", depth = 3)
                }
            }
        }

        if (changed) {
            try {
                field.set(parent, newList)
            } catch (_: Throwable) {}
        }
    }
}
