package com.xposed.douyinhelper.util

import java.util.concurrent.ConcurrentHashMap

/**
 * 类查找工具
 *
 * 功能:
 * - 动态查找混淆后的类
 * - 通过特征字段/方法签名查找
 * - 缓存查找结果
 * - 处理不同抖音版本的兼容性
 *
 * 抖音每次更新后类名都会混淆，但类的结构特征(字段类型、方法签名)
 * 相对稳定，通过这些特征可以定位到目标类。
 *
 * LSPosed 2.x 兼容:
 * - 使用标准 Java 反射 API，不依赖 XposedHelpers 内部实现
 * - DexFile 遍历通过 dalvik.system.DexFile 反射完成
 */
object ClassFinder {

    /** 类缓存: key=类名, value=Class对象 */
    private val classCache = ConcurrentHashMap<String, Class<*>>()

    /**
     * 通过候选类名列表查找类
     * 尝试每个候选名，返回第一个找到的
     *
     * @param classLoader 类加载器
     * @param candidates 候选类名列表
     * @return 找到的 Class，全部失败返回 null
     */
    fun findClass(classLoader: ClassLoader, candidates: List<String>): Class<*>? {
        for (name in candidates) {
            val cached = classCache[name]
            if (cached != null) return cached

            try {
                val clazz = Class.forName(name, false, classLoader)
                classCache[name] = clazz
                HookUtils.log("ClassFinder: 找到类 $name")
                return clazz
            } catch (_: ClassNotFoundException) {
                // 继续尝试下一个
            }
        }
        return null
    }

    /**
     * 通过字段特征查找类
     *
     * @param classLoader 类加载器
     * @param packageName 包名前缀
     * @param fieldSignatures 字段特征列表 (格式: "fieldName:fieldType")
     * @return 匹配的 Class 列表
     */
    fun findByFieldSignature(
        classLoader: ClassLoader,
        packageName: String,
        fieldSignatures: List<String>
    ): List<Class<*>> {
        val results = mutableListOf<Class<*>>()

        try {
            val parsedSignatures = fieldSignatures.map { sig ->
                val parts = sig.split(":")
                parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
            }

            val classes = getClassesInPackage(classLoader, packageName)

            for (clazz in classes) {
                try {
                    val fields = clazz.declaredFields
                    var matchCount = 0

                    for ((fieldName, fieldType) in parsedSignatures) {
                        val hasMatch = fields.any { field ->
                            field.name == fieldName &&
                            (fieldType.isEmpty() || field.type.name.contains(fieldType))
                        }
                        if (hasMatch) matchCount++
                    }

                    if (matchCount == parsedSignatures.size) {
                        results.add(clazz)
                    }
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            HookUtils.log("ClassFinder: 字段特征查找失败: ${t.message}")
        }

        return results
    }

    /**
     * 通过方法签名特征查找类
     *
     * @param classLoader 类加载器
     * @param packageName 包名前缀
     * @param methodSignatures 方法特征列表 (格式: "methodName:returnType:paramType1,paramType2")
     * @return 匹配的 Class 列表
     */
    fun findByMethodSignature(
        classLoader: ClassLoader,
        packageName: String,
        methodSignatures: List<String>
    ): List<Class<*>> {
        val results = mutableListOf<Class<*>>()

        try {
            val parsedSignatures = methodSignatures.map { sig ->
                val parts = sig.split(":")
                val methodName = parts.getOrElse(0) { "" }
                val returnType = parts.getOrElse(1) { "" }
                val paramTypes = parts.getOrElse(2) { "" }.split(",").filter { it.isNotEmpty() }
                Triple(methodName, returnType, paramTypes)
            }

            val classes = getClassesInPackage(classLoader, packageName)

            for (clazz in classes) {
                try {
                    var matchCount = 0

                    for ((methodName, returnType, paramTypes) in parsedSignatures) {
                        val hasMatch = clazz.declaredMethods.any { method ->
                            method.name == methodName &&
                            (returnType.isEmpty() || method.returnType.name.contains(returnType)) &&
                            method.parameterTypes.size == paramTypes.size
                        }
                        if (hasMatch) matchCount++
                    }

                    if (matchCount == parsedSignatures.size) {
                        results.add(clazz)
                    }
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            HookUtils.log("ClassFinder: 方法签名查找失败: ${t.message}")
        }

        return results
    }

    /**
     * 获取包下的所有类
     *
     * LSPosed 2.x 兼容:
     * - 使用 dalvik.system.DexFile 反射遍历
     * - pathList -> dexElements -> dexFile -> entries
     * - 所有字段名使用标准 AOSP 命名，未被混淆
     */
    private fun getClassesInPackage(classLoader: ClassLoader, packageName: String): List<Class<*>> {
        val classes = mutableListOf<Class<*>>()

        try {
            // 通过 BaseDexClassLoader.pathList.dexElements 遍历
            val pathListField = classLoader.javaClass.superclass?.getDeclaredField("pathList")
                ?: classLoader.javaClass.getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(classLoader) ?: return emptyList()

            val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
            dexElementsField.isAccessible = true
            val dexElements = dexElementsField.get(pathList) as? Array<*> ?: return emptyList()

            for (dexElement in dexElements) {
                if (dexElement == null) continue

                val dexFileField = dexElement.javaClass.getDeclaredField("dexFile")
                dexFileField.isAccessible = true
                val dexFile = dexFileField.get(dexElement) ?: continue

                // DexFile.entries() 返回 Enumeration<String>
                val entriesMethod = dexFile.javaClass.getMethod("entries")
                @Suppress("UNCHECKED_CAST")
                val entries = entriesMethod.invoke(dexFile) as? java.util.Enumeration<String> ?: continue

                while (entries.hasMoreElements()) {
                    val className = entries.nextElement()
                    if (className.startsWith(packageName) && !className.contains('$')) {
                        try {
                            val clazz = Class.forName(className, false, classLoader)
                            classes.add(clazz)
                        } catch (_: Throwable) { }
                    }
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("ClassFinder: DexFile 遍历失败: ${t.message}")
            // 备用方案: 尝试常见类名
            val commonClasses = listOf(
                "${packageName}.Aweme",
                "${packageName}.Video",
                "${packageName}.Image",
                "${packageName}.Comment",
                "${packageName}.User",
                "${packageName}.feed.model.Aweme",
                "${packageName}.feed.model.Video"
            )
            for (name in commonClasses) {
                try {
                    classes.add(Class.forName(name, false, classLoader))
                } catch (_: ClassNotFoundException) { }
            }
        }

        return classes
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        classCache.clear()
    }

    /**
     * 获取缓存大小
     */
    fun cacheSize(): Int = classCache.size
}
