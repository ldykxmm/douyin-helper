package com.xposed.douyinhelper.util

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XposedBridge

/**
 * Hook 通用工具
 *
 * 功能:
 * - safeHook: 安全执行 hook，捕获异常
 * - log: 统一日志
 * - showToast: 弹 Toast
 * - 反射工具方法
 */
object HookUtils {

    /** 统一日志 TAG */
    private const val TAG = "Dou+"

    /** 主线程 Handler，用于弹 Toast */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 安全执行 hook 操作
     * 捕获所有异常，确保不影响宿主应用
     *
     * @param block 要执行的操作
     */
    fun safeHook(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("Hook 异常: ${t.message}")
            log("堆栈: ${t.stackTraceToString().take(500)}")
        }
    }

    /**
     * 输出日志
     * 使用 XposedBridge.log，日志会写入 LSPosed 日志
     *
     * @param message 日志内容
     */
    fun log(message: String) {
        XposedBridge.log("[$TAG] $message")
    }

    /**
     * 显示 Toast
     * 必须在主线程执行
     *
     * @param context Android Context
     * @param message Toast 消息
     * @param duration 显示时长，默认 SHORT
     */
    fun showToast(
        context: android.content.Context,
        message: String,
        duration: Int = Toast.LENGTH_SHORT
    ) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(context, message, duration).show()
            } else {
                mainHandler.post {
                    Toast.makeText(context, message, duration).show()
                }
            }
        } catch (t: Throwable) {
            log("显示Toast失败: ${t.message}")
        }
    }

    /**
     * 反射获取字段值
     *
     * @param obj 目标对象
     * @param fieldName 字段名
     * @return 字段值，失败返回 null
     */
    fun getField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        return try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(obj)
        } catch (t: Throwable) {
            log("反射获取字段失败: ${obj.javaClass.name}.$fieldName - ${t.message}")
            null
        }
    }

    /**
     * 反射设置字段值
     *
     * @param obj 目标对象
     * @param fieldName 字段名
     * @param value 要设置的值
     */
    fun setField(obj: Any?, fieldName: String, value: Any?) {
        if (obj == null) return
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(obj, value)
        } catch (t: Throwable) {
            log("反射设置字段失败: ${obj.javaClass.name}.$fieldName - ${t.message}")
        }
    }

    /**
     * 反射调用方法
     *
     * @param obj 目标对象
     * @param methodName 方法名
     * @param paramTypes 参数类型数组
     * @param args 参数值数组
     * @return 方法返回值，失败返回 null
     */
    fun callMethod(
        obj: Any?,
        methodName: String,
        paramTypes: Array<Class<*>> = emptyArray(),
        vararg args: Any?
    ): Any? {
        if (obj == null) return null
        return try {
            val method = obj.javaClass.getDeclaredMethod(methodName, *paramTypes)
            method.isAccessible = true
            method.invoke(obj, *args)
        } catch (t: Throwable) {
            log("反射调用方法失败: ${obj.javaClass.name}.$methodName - ${t.message}")
            null
        }
    }

    /**
     * 反射获取父类字段值
     * 递归向上查找字段
     *
     * @param obj 目标对象
     * @param fieldName 字段名
     * @return 字段值，失败返回 null
     */
    fun getFieldDeep(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null

        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            } catch (t: Throwable) {
                log("反射获取字段失败: $clazz.$fieldName - ${t.message}")
                return null
            }
        }
        return null
    }

    /**
     * 检查类是否存在
     *
     * @param classLoader 类加载器
     * @param className 类名
     * @return true 如果类存在
     */
    fun classExists(classLoader: ClassLoader, className: String): Boolean {
        return try {
            Class.forName(className, false, classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    /**
     * 获取对象的所有字段名
     *
     * @param obj 目标对象
     * @return 字段名列表
     */
    fun getFieldNames(obj: Any?): List<String> {
        if (obj == null) return emptyList()

        val names = mutableListOf<String>()
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            clazz.declaredFields.forEach { names.add(it.name) }
            clazz = clazz.superclass
        }
        return names
    }

    /**
     * 获取对象的所有方法名
     *
     * @param obj 目标对象
     * @return 方法名列表
     */
    fun getMethodNames(obj: Any?): List<String> {
        if (obj == null) return emptyList()

        val names = mutableListOf<String>()
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            clazz.declaredMethods.forEach { names.add(it.name) }
            clazz = clazz.superclass
        }
        return names
    }

    /**
     * 格式化字节数组为十六进制字符串 (用于调试)
     */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
