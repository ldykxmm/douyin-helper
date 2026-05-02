package com.xposed.douyinhelper.util

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Video 类字段探测器
 *
 * 运行时自动扫描 Video 类的所有字段和方法，
 * 找到包含视频 URL 的正确字段名。
 * 结果写入 LSPosed 日志，用于适配新版抖音。
 */
object VideoFieldDumper {

    private const val TAG = "VideoFieldDumper"

    /**
     * 探测 Video 对象的所有字段，找出包含 URL 的字段
     */
    fun dumpVideoFields(videoObj: Any?) {
        if (videoObj == null) {
            HookUtils.log("$TAG: videoObj is null")
            return
        }

        val clazz = videoObj::class.java
        HookUtils.log("$TAG: Video 类名: ${clazz.name}")

        // 遍历所有字段
        HookUtils.log("$TAG: === Video 字段 ===")
        for (field in clazz.declaredFields) {
            field.isAccessible = true
            val value = try { field.get(videoObj) } catch (_: Throwable) { null }
            val valueStr = when (value) {
                null -> "null"
                is String -> if (value.length > 80) "String(${value.length}): ${value.take(80)}..." else "String: $value"
                is List<*> -> "List(${value.size})"
                is Array<*> -> "Array(${value.size})"
                else -> value::class.java.name
            }
            HookUtils.log("$TAG:   ${field.name}: ${field.type.simpleName} = $valueStr")

            // 如果是 List 类型，检查内容
            if (value is List<*> && value.isNotEmpty()) {
                val first = value.first()
                if (first is String && first.length > 20) {
                    HookUtils.log("$TAG:     [0]: ${first.take(100)}")
                }
            }
        }

        // 遍历所有无参方法（getter）
        HookUtils.log("$TAG: === Video 无参方法返回值 ===")
        for (method in clazz.declaredMethods) {
            if (method.parameterCount != 0) continue
            if (method.returnType == Void.TYPE) continue

            method.isAccessible = true
            val value = try { method.invoke(videoObj) } catch (_: Throwable) { null }
            val valueStr = when (value) {
                null -> "null"
                is String -> if (value.length > 80) "String(${value.length}): ${value.take(80)}..." else "String: $value"
                is List<*> -> "List(${value.size})"
                is Array<*> -> "Array(${value.size})"
                else -> value::class.java.name
            }
            HookUtils.log("$TAG:   ${method.name}(): ${method.returnType.simpleName} = $valueStr")

            // 如果返回值包含 URL，深入探测
            if (value != null && mightContainUrl(value)) {
                dumpUrlContainer(value, "  ${method.name}")
            }
        }
    }

    /**
     * 探测 Aweme 对象的字段结构
     */
    fun dumpAwemeFields(awemeObj: Any?) {
        if (awemeObj == null) return

        val clazz = awemeObj::class.java
        HookUtils.log("$TAG: Aweme 类名: ${clazz.name}")

        // 只找包含 URL 的关键字段
        for (method in clazz.declaredMethods) {
            if (method.parameterCount != 0) continue
            val name = method.name.lowercase()
            if (!name.contains("video") && !name.contains("image") &&
                !name.contains("url") && !name.contains("play")) continue

            method.isAccessible = true
            val value = try { method.invoke(awemeObj) } catch (_: Throwable) { null }
            HookUtils.log("$TAG: Aweme.${method.name}() = ${value?.let { it::class.java.name } ?: "null"}")

            if (value != null && mightContainUrl(value)) {
                dumpUrlContainer(value, "  Aweme.${method.name}")
            }
        }
    }

    /**
     * 深入探测包含 URL 的容器对象
     */
    private fun dumpUrlContainer(obj: Any, prefix: String) {
        val clazz = obj::class.java

        // 如果是 String，直接输出
        if (obj is String) {
            HookUtils.log("$TAG: $prefix -> ${obj.take(120)}")
            return
        }

        // 如果是 List，检查元素
        if (obj is List<*>) {
            for ((i, item) in obj.take(3).withIndex()) {
                when (item) {
                    is String -> HookUtils.log("$TAG: $prefix[$i] = ${item.take(120)}")
                    else -> {
                        HookUtils.log("$TAG: $prefix[$i] = ${item?.let { it::class.java.name } ?: "null"}")
                        if (item != null) dumpUrlContainer(item, "$prefix[$i]")
                    }
                }
            }
            return
        }

        // 遍历对象字段找 URL
        for (field in clazz.declaredFields) {
            field.isAccessible = true
            val value = try { field.get(obj) } catch (_: Throwable) { null }
            when (value) {
                is String -> {
                    if (value.contains("http") && value.length > 20) {
                        HookUtils.log("$TAG: $prefix.${field.name} = ${value.take(120)}")
                    }
                }
                is List<*> -> {
                    if (value.isNotEmpty()) {
                        HookUtils.log("$TAG: $prefix.${field.name} = List(${value.size})")
                        for ((i, item) in value.take(2).withIndex()) {
                            if (item is String) {
                                HookUtils.log("$TAG: $prefix.${field.name}[$i] = ${item.take(120)}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mightContainUrl(obj: Any): Boolean {
        return obj is String || obj is List<*> || obj::class.java.declaredFields.any {
            it.type == String::class.java || List::class.java.isAssignableFrom(it.type)
        }
    }
}
