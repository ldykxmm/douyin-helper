package com.xposed.douyinhelper.util

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Context 获取工具
 *
 * 功能:
 * - 从各种途径获取 Application Context
 * - 用于下载、通知等需要 Context 的操作
 *
 * 在 Xposed 模块中，Context 不是直接可用的，
 * 需要通过 Hook 来获取。
 *
 * 兼容 LSPosed 2.0.x (API 101):
 * - LSPosed 2.x 使用 Pine 引擎，Hook 行为与原版 Xposed 一致
 * - ActivityThread 反射路径不变
 * - Application.onCreate 仍然是最可靠的 Context 来源
 */
object ContextHelper {

    /** 缓存的 Application Context */
    @Volatile
    private var applicationContext: Context? = null

    /** 是否已初始化 */
    private var initialized = false

    /** LSPosed 2.x 中 Application 的完整类名 */
    private const val APPLICATION_CLASS = "android.app.Application"
    private const val ACTIVITY_CLASS = "android.app.Activity"
    private const val ACTIVITY_THREAD_CLASS = "android.app.ActivityThread"

    /**
     * 初始化 Context 获取
     * 会自动 Hook Application 和 Activity 来获取 Context
     *
     * @param lpparam 加载包参数
     */
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (initialized) return
        initialized = true

        hookApplication(lpparam)
        hookActivity(lpparam)

        // LSPosed 2.x 备用方案: 尝试立即通过反射获取
        tryGetContextByReflection()
    }

    /**
     * Hook Application.onCreate
     * 这是最可靠的获取 Context 的方式
     *
     * LSPosed 2.x 兼容: XposedHelpers.findClass 使用 BootClassLoader
     * 查找系统类，与 lpparam.classLoader 无关
     */
    private fun hookApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.safeHook {
            // 系统类使用 null classLoader 或 bootClassLoader
            val appClass = XposedHelpers.findClass(APPLICATION_CLASS, lpparam.classLoader)

            XposedBridge.hookAllMethods(appClass, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val app = param.thisObject as Application
                        applicationContext = app.applicationContext
                        HookUtils.log("ContextHelper: 通过 Application.onCreate 获取 Context")
                    } catch (t: Throwable) {
                        HookUtils.log("ContextHelper: Application Hook 失败: ${t.message}")
                    }
                }
            })
        }
    }

    /**
     * Hook Activity.onCreate
     * 作为备用方案，某些情况下 Application.onCreate 可能延迟
     */
    private fun hookActivity(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.safeHook {
            val activityClass = XposedHelpers.findClass(ACTIVITY_CLASS, lpparam.classLoader)

            XposedBridge.hookAllMethods(activityClass, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (applicationContext == null) {
                            val activity = param.thisObject as android.app.Activity
                            applicationContext = activity.applicationContext
                            HookUtils.log("ContextHelper: 通过 Activity.onCreate 获取 Context")
                        }
                    } catch (_: Throwable) { }
                }
            })
        }
    }

    /**
     * 获取 Application Context
     *
     * @return Context，如果尚未获取则返回 null
     */
    fun getContext(): Context? {
        return applicationContext ?: tryGetContextByReflection()
    }

    /**
     * 获取 Application Context (非空版本)
     * 如果尚未获取，尝试通过反射获取
     *
     * @return Context
     * @throws IllegalStateException 如果无法获取 Context
     */
    fun requireContext(): Context {
        return applicationContext ?: tryGetContextByReflection()
            ?: throw IllegalStateException("Context 尚未初始化，请确保抖音已启动")
    }

    /**
     * 通过反射尝试获取当前 Application
     *
     * LSPosed 2.x 兼容:
     * - ActivityThread.currentActivityThread() 在 Android 9+ 仍然可用
     * - getApplication() 返回当前 Application 实例
     * - 这个方法在 Hook 回调之外调用可能返回 null
     */
    private fun tryGetContextByReflection(): Context? {
        if (applicationContext != null) return applicationContext

        return try {
            val activityThreadClass = Class.forName(ACTIVITY_THREAD_CLASS)
            val currentActivityThread = XposedHelpers.callStaticMethod(
                activityThreadClass, "currentActivityThread"
            )
            if (currentActivityThread != null) {
                val app = XposedHelpers.callMethod(currentActivityThread, "getApplication") as? Application
                if (app != null) {
                    applicationContext = app.applicationContext
                    HookUtils.log("ContextHelper: 通过反射 ActivityThread 获取 Context")
                    return applicationContext
                }
            }
            null
        } catch (t: Throwable) {
            // LSPosed 2.x 下部分设备可能无法通过此方式获取
            null
        }
    }

    /**
     * 检查 Context 是否已就绪
     */
    fun isReady(): Boolean = applicationContext != null

    /**
     * 重置 (用于测试)
     */
    fun reset() {
        applicationContext = null
        initialized = false
    }
}
