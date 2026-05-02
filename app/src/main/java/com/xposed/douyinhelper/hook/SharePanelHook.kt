package com.xposed.douyinhelper.hook

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.xposed.douyinhelper.util.ContextHelper
import com.xposed.douyinhelper.util.HookUtils
import com.xposed.douyinhelper.util.MediaCache
import com.xposed.douyinhelper.util.MediaDownloader
import com.xposed.douyinhelper.util.UrlParser
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 分享面板 Hook
 *
 * 策略: 不依赖硬编码类名，通过 View 树特征检测分享面板
 * 在面板底部注入「保存到相册」按钮
 */
class SharePanelHook : BaseHook {

    companion object {
        private const val TAG = "SharePanelHook"
        private const val BUTTON_TAG = "dou_plus_save_btn"
    }

    override fun init(classLoader: ClassLoader) {
        hookViewAdded(classLoader)
    }

    /**
     * Hook View.onAttachedToWindow
     * 每个 View 被添加到窗口时检查是否为分享面板
     */
    private fun hookViewAdded(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val viewClass = XposedHelpers.findClass("android.view.View", classLoader)

            XposedBridge.hookAllMethods(viewClass, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? ViewGroup ?: return
                        // 延迟检查，等 View 树构建完成
                        view.post { checkAndInject(view) }
                    } catch (_: Throwable) {}
                }
            })

            HookUtils.log("$TAG: View 附加 Hook 已安装")
        }
    }

    /**
     * 检查 ViewGroup 是否为分享面板并注入按钮
     */
    private fun checkAndInject(container: ViewGroup) {
        if (container.findViewWithTag<View>(BUTTON_TAG) != null) return
        if (!isSharePanel(container)) return

        HookUtils.log("$TAG: 检测到分享面板，注入保存按钮")

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                container.addView(createSaveButtonRow(container.context))
                HookUtils.log("$TAG: 保存按钮注入成功")
            } catch (t: Throwable) {
                HookUtils.log("$TAG: 注入按钮失败: ${t.message}")
            }
        }
    }

    /**
     * 分享面板检测 — 宽松策略
     *
     * 满足以下任一条件即认为是分享面板:
     * 1. View tag/description 包含 "share"
     * 2. 包含"分享"/"转发"/"发送给" 文本 + 有 3+ 子项的子 View
     * 3. 是 BottomSheetDialog 的内容 View + 包含多个可点击子项
     * 4. 包含 RecyclerView/GridView + 分享相关文本
     */
    private fun isSharePanel(view: ViewGroup): Boolean {
        if (view.childCount < 2) return false

        // 快速检查: tag/description
        val tag = view.tag?.toString()?.lowercase() ?: ""
        val desc = view.contentDescription?.toString()?.lowercase() ?: ""
        if (tag.contains("share") || desc.contains("share") ||
            tag.contains("分享") || desc.contains("分享")) {
            return true
        }

        // 检查类名
        val className = view::class.java.name.lowercase()
        if (className.contains("share") && className.contains("panel") ||
            className.contains("share") && className.contains("dialog") ||
            className.contains("sharesheet") || className.contains("shareboard")) {
            return true
        }

        // 内容检查
        var hasShareText = false
        var hasGridOrList = false
        var clickableChildCount = 0

        for (i in 0 until minOf(view.childCount, 10)) {
            val child = view.getChildAt(i)

            if (child is TextView) {
                val text = child.text?.toString() ?: ""
                if (text.contains("分享") || text.contains("转发") ||
                    text.contains("发送给") || text.contains("复制链接") ||
                    text.contains("保存本地") || text.contains("保存到")) {
                    hasShareText = true
                }
            }

            if (child is ViewGroup) {
                if (child.childCount >= 3) clickableChildCount++
                val childClass = child::class.java.name.lowercase()
                if (childClass.contains("recycler") || childClass.contains("grid") ||
                    childClass.contains("listview")) {
                    hasGridOrList = true
                }
            }
        }

        // 宽松匹配: 分享文本 + (grid/list 或 多个子项)
        if (hasShareText && (hasGridOrList || clickableChildCount >= 2)) return true

        // 更宽松: 如果有足够多的可点击子项，且在底部弹出位置
        if (clickableChildCount >= 3) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val screenHeight = view.resources.displayMetrics.heightPixels
            // 分享面板通常在屏幕下半部分
            if (location[1] > screenHeight * 0.4) return true
        }

        return false
    }

    /**
     * 创建保存按钮行
     */
    private fun createSaveButtonRow(context: android.content.Context): LinearLayout {
        val density = context.resources.displayMetrics.density

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = BUTTON_TAG
            isClickable = true
            isFocusable = true

            val hPad = (16 * density).toInt()
            val vPad = (12 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)

            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val icon = TextView(context).apply {
            text = "\uD83D\uDCBE"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (12 * density).toInt()
            }
        }

        val label = TextView(context).apply {
            text = "保存到相册"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#161823"))
            typeface = Typeface.DEFAULT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(icon)
        row.addView(label)
        row.setOnClickListener { v -> onDownloadClicked(v) }

        return row
    }

    /**
     * 下载按钮点击处理
     */
    private fun onDownloadClicked(view: View) {
        val context = view.context

        val aweme = MediaCache.getCurrentAweme()
        if (aweme != null) {
            downloadFromAweme(context, aweme)
            return
        }

        HookUtils.showToast(context, "无法获取当前作品信息，请重试")
        HookUtils.log("$TAG: 无可用的 Aweme 缓存")
    }

    /**
     * 从 Aweme 对象下载媒体 — 自适应扫描
     */
    private fun downloadFromAweme(context: android.content.Context, aweme: Any) {
        try {
            // 自适应查找视频 URL
            val videoUrl = findVideoUrl(aweme)
            if (videoUrl != null) {
                val noWmUrl = UrlParser.getNoWatermarkUrl(videoUrl)
                HookUtils.showToast(context, "正在保存视频...")
                MediaDownloader.download(context, noWmUrl, "douyin_${System.currentTimeMillis()}.mp4",
                    onComplete = { HookUtils.showToast(context, "视频已保存 ✓") },
                    onError = { t -> HookUtils.showToast(context, "保存失败: ${t.message}") }
                )
                return
            }

            // 自适应查找图片 URL
            val imageUrls = findImageUrls(aweme)
            if (imageUrls.isNotEmpty()) {
                HookUtils.showToast(context, "正在保存 ${imageUrls.size} 张图片...")
                for ((index, url) in imageUrls.withIndex()) {
                    val noWmUrl = UrlParser.getNoWatermarkUrl(url)
                    val ext = if (noWmUrl.contains("heic", ignoreCase = true)) "heic" else "jpg"
                    MediaDownloader.download(context, noWmUrl, "douyin_${System.currentTimeMillis()}_$index.$ext",
                        onComplete = {
                            if (index == imageUrls.size - 1) {
                                HookUtils.showToast(context, "图片已保存 ✓")
                            }
                        },
                        onError = { t -> HookUtils.log("$TAG: 图片[$index] 保存失败: ${t.message}") }
                    )
                }
                return
            }

            HookUtils.showToast(context, "未找到可保存的媒体")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 下载失败: ${t.message}")
            HookUtils.showToast(context, "保存失败")
        }
    }

    /**
     * 自适应查找视频 URL — 遍历对象字段
     */
    private fun findVideoUrl(obj: Any, depth: Int = 0): String? {
        if (depth > 6) return null

        val clazz = obj::class.java
        for (field in clazz.declaredFields) {
            try {
                field.isAccessible = true
                val value = field.get(obj) ?: continue

                when (value) {
                    is String -> {
                        if (value.contains("/play") || value.contains("playwm") ||
                            value.contains("douyinvod") || value.contains(".mp4")) {
                            if (value.startsWith("http")) return value
                        }
                    }
                    is List<*> -> {
                        for (item in value) {
                            if (item is String && item.startsWith("http") &&
                                (item.contains("/play") || item.contains("playwm"))) {
                                return item
                            }
                        }
                    }
                    else -> {
                        val result = findVideoUrl(value, depth + 1)
                        if (result != null) return result
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * 自适应查找图片 URL 列表
     */
    private fun findImageUrls(obj: Any, depth: Int = 0): List<String> {
        if (depth > 6) return emptyList()

        val urls = mutableListOf<String>()
        val clazz = obj::class.java

        for (field in clazz.declaredFields) {
            try {
                field.isAccessible = true
                val value = field.get(obj) ?: continue

                val fieldName = field.name.lowercase()
                if (fieldName.contains("image") || fieldName.contains("pic") ||
                    fieldName.contains("photo") || fieldName.contains("url")) {

                    when (value) {
                        is List<*> -> {
                            for (item in value) {
                                when (item) {
                                    is String -> if (item.startsWith("http") && UrlParser.isImageUrl(item)) urls.add(item)
                                    else -> {
                                        // Image 对象，递归找 urlList
                                        val nested = if (item != null) findImageUrls(item, depth + 1) else emptyList()
                                        urls.addAll(nested)
                                    }
                                }
                            }
                        }
                        is String -> if (value.startsWith("http") && UrlParser.isImageUrl(value)) urls.add(value)
                    }
                }
            } catch (_: Throwable) {}
        }

        return urls
    }
}
