package com.xposed.douyinhelper

import com.xposed.douyinhelper.hook.CommentHook
import com.xposed.douyinhelper.hook.DownloadDialogHook
import com.xposed.douyinhelper.hook.DownloadHook
import com.xposed.douyinhelper.hook.FeedHook
import com.xposed.douyinhelper.hook.LivePhotoHook
import com.xposed.douyinhelper.hook.ShareHook
import com.xposed.douyinhelper.util.ContextHelper
import com.xposed.douyinhelper.util.HookUtils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * DouyinHelper - Xposed 模块入口
 *
 * 作用域: com.ss.android.ugc.aweme (抖音)
 * 功能: 无水印保存视频、图集、实况照片、评论区媒体
 *
 * 兼容: LSPosed 2.0.x (API 101) / LSPosed 1.x (API 93+)
 *
 * 作者: DouyinHelper
 * 版本: 1.0.0
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        /** 统一日志 TAG */
        const val LOG_TAG = "DouyinHelper"

        /** 抖音包名 */
        const val TARGET_PACKAGE = "com.ss.android.ugc.aweme"

        /** 模块版本 */
        const val MODULE_VERSION = "1.0.0"
    }

    /**
     * Xposed 模块加载入口
     * 当目标应用加载时被调用
     *
     * @param lpparam 加载包参数，包含包名、类加载器等信息
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 仅在抖音进程中执行
        if (lpparam.packageName != TARGET_PACKAGE) return

        HookUtils.log("========== DouyinHelper v$MODULE_VERSION ==========")
        HookUtils.log("模块加载中... 包名: ${lpparam.packageName}")
        HookUtils.log("进程名: ${lpparam.processName}")
        HookUtils.log("类加载器: ${lpparam.classLoader::class.java.name}")

        // 检测 LSPosed 版本 (通过反射)
        logLSPosedVersion()

        try {
            // 初始化 Context 获取工具
            ContextHelper.init(lpparam)

            // 注册各个 Hook 模块
            val hooks = listOf(
                FeedHook(),           // Feed 流视频 Hook
                ShareHook(),          // 分享拦截 Hook
                DownloadHook(),       // 下载流程 Hook
                CommentHook(),        // 评论区 Hook
                LivePhotoHook(),      // 实况照片 Hook
                DownloadDialogHook()  // 下载弹窗 Hook
            )

            // 依次初始化所有 Hook
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

    /**
     * 检测并记录 LSPosed 版本信息
     * 通过反射读取 LSPosed 框架版本
     */
    private fun logLSPosedVersion() {
        try {
            // 尝试读取 LSPosed 版本 (通过 SystemProperties 或框架内部类)
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java, String::class.java)

            // LSPosed 2.x 会在系统属性中写入版本信息
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
