package com.xposed.douyinhelper.hook

/**
 * Hook 模块基础接口
 *
 * 所有 Hook 模块都需要实现此接口，
 * 提供统一的初始化入口和类加载器引用。
 */
interface BaseHook {
    /**
     * 初始化 Hook 模块
     *
     * @param classLoader 抖音应用的类加载器，用于反射查找目标类
     */
    fun init(classLoader: ClassLoader)
}
