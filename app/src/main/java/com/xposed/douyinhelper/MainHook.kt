package com.xposed.douyinhelper

import com.xposed.douyinhelper.hook.CommentHook
import com.xposed.douyinhelper.hook.DownloadDialogHook
import com.xposed.douyinhelper.hook.DownloadHook
import com.xposed.douyinhelper.hook.FeedHook
import com.xposed.douyinhelper.hook.LivePhotoHook
import com.xposed.douyinhelper.hook.ShareHook
import com.xposed.douyinhelper.hook.SharePanelHook
import com.xposed.douyinhelper.util.ContextHelper
import com.xposed.douyinhelper.util.HookUtils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Dou+ - Xposed 模块入口
 *
 * 作用域: com.ss.android.ugc.aweme (抖音)
 * 功能: 无水印保存视频、图集、实况照片、评论区媒体，分享面板一键保存
 *
 * 兼容: LSPosed 2.0.x (API 101) / LSPosed 1.x (API 93+)
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val LOG_TAG = "Dou+"
        const val TARGET_PACKAGE = "com.ss.android.ugc.aweme"
        const val MODULE_VERSION = "1.2.0"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        HookUtils.log("========== Dou+ v$MODULE_VERSION ==========")
        HookUtils.log("模块加载中... 包名: ${lpparam.packageName}")
        HookUtils.log("进程名: ${lpparam.processName}")
        HookUtils.log("类加载器: ${lpparam.classLoader::class.java.name}")

        logLSPosedVersion()

        try {
            ContextHelper.init(lpparam)

            val hooks = listOf(
                FeedHook(),           // Feed 流视频 Hook + Aweme 缓存
                ShareHook(),          // 分享拦截 Hook
                DownloadHook(),       // 下载流程 Hook
                CommentHook(),        // 评论区 Hook
                LivePhotoHook(),      // 实况照片 Hook + URL 缓存
                DownloadDialogHook(), // 下载弹窗 Hook
                SharePanelHook()      // 分享面板保存按钮 Hook (新增)
            )

            var successCount = 0
            var failCount = 0
            for (hook in hooks) {
                try {
                    hook.init(lpparam.classLoader)
                    successCount++
                    HookUtils.log("${hook::class.simpleName} 初始化成功")
                } catch (t: Throwable) {
                    failCount++
                    HookUtils.log("${hook::class.simpleName} 初始化失败: ${t.message}")
                }
            }

            HookUtils.log("Hook 模块加载完成: 成功=$successCount, 失败=$failCount")
            HookUtils.log("==============================================")
        } catch (t: Throwable) {
            HookUtils.log("模块加载失败: ${t.message}")
            HookUtils.log("堆栈: ${t.stackTraceToString().take(300)}")
        }
    }

    private fun logLSPosedVersion() {
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
            val lsposedVersion = getMethod.invoke(null, "persist.lsposed.version", "unknown") as String
            val apiVersion = getMethod.invoke(null, "persist.lsposed.api", "unknown") as String
            if (lsposedVersion != "unknown") {
                HookUtils.log("LSPosed 版本: $lsposedVersion, API: $apiVersion")
            } else {
                HookUtils.log("LSPosed 版本: 未能检测 (可能是 LSPosed 2.0.x)")
            }
        } catch (_: Throwable) {
            HookUtils.log("LSPosed 版本检测跳过")
        }
    }
}
