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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 分享面板 Hook
 *
 * 功能:
 * - 在抖音分享弹窗中注入一行"保存"按钮
 * - 用户点击后直接下载当前视频/图片的无水印版本
 * - 支持视频、图集、实况照片
 *
 * 实现原理:
 * Hook ViewGroup.addView，当检测到抖音分享面板被添加到窗口时，
 * 在面板底部追加一行保存按钮。
 */
class SharePanelHook : BaseHook {

    companion object {
        private const val TAG = "SharePanelHook"
        private const val BUTTON_TAG = "douyin_helper_save_btn"
    }

    override fun init(classLoader: ClassLoader) {
        hookSharePanel(classLoader)
    }

    /**
     * Hook ViewGroup.addView
     * 当分享面板出现时注入保存按钮
     */
    private fun hookSharePanel(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val viewGroupClass = XposedHelpers.findClass("android.view.ViewGroup", classLoader)

            XposedBridge.hookAllMethods(viewGroupClass, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val parent = param.thisObject as? ViewGroup ?: return
                        injectSaveButton(parent)
                    } catch (_: Throwable) {}
                }
            })

            HookUtils.log("$TAG: 分享面板 Hook 已安装")
        }
    }

    /**
     * 检测并注入保存按钮
     */
    private fun injectSaveButton(container: ViewGroup) {
        // 避免重复添加
        if (container.findViewWithTag<View>(BUTTON_TAG) != null) return

        // 判断是否为分享面板: 包含多个可点击子项的水平滚动布局
        if (!isSharePanel(container)) return

        HookUtils.log("$TAG: 检测到分享面板，注入保存按钮")

        // 主线程创建 UI
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                val saveRow = createSaveButtonRow(container.context)
                container.addView(saveRow)
                HookUtils.log("$TAG: 保存按钮注入成功")
            } catch (t: Throwable) {
                HookUtils.log("$TAG: 注入按钮失败: ${t.message}")
            }
        }
    }

    /**
     * 判断是否为分享面板
     *
     * 抖音分享面板特征:
     * - 是垂直布局 (LinearLayout vertical) 或 FrameLayout
     * - 包含"分享到"标题或分享平台图标列表
     * - 内部有 RecyclerView 或 GridView 展示分享渠道
     */
    private fun isSharePanel(view: ViewGroup): Boolean {
        if (view.childCount < 2) return false

        // 检查是否包含"分享"相关文本
        var hasShareText = false
        var hasClickableChildren = false

        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)

            // 检查文本
            if (child is TextView) {
                val text = child.text?.toString() ?: ""
                if (text.contains("分享") || text.contains("share", ignoreCase = true) ||
                    text.contains("转发") || text.contains("发送给")) {
                    hasShareText = true
                }
            }

            // 检查是否有多个可点击子项 (分享渠道图标)
            if (child is ViewGroup && child.childCount >= 3) {
                hasClickableChildren = true
            }
        }

        // 检查 view tag 或 content description
        val tag = view.tag?.toString() ?: ""
        val desc = view.contentDescription?.toString() ?: ""
        if (tag.contains("share", ignoreCase = true) || desc.contains("share", ignoreCase = true)) {
            return true
        }

        return hasShareText && hasClickableChildren
    }

    /**
     * 创建保存按钮行
     *
     * 布局结构:
     * ┌──────────────────────────────────────┐
     * │  💾 保存到相册                         │
     * └──────────────────────────────────────┘
     */
    private fun createSaveButtonRow(context: android.content.Context): LinearLayout {
        val density = context.resources.displayMetrics.density

        // 外层容器
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = BUTTON_TAG
            isClickable = true
            isFocusable = true

            // 内边距: 16dp
            val hPad = (16 * density).toInt()
            val vPad = (12 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)

            // 背景: 点击涟漪效果
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 图标
        val icon = TextView(context).apply {
            text = "\uD83D\uDCBE"  // 💾 软盘 emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (12 * density).toInt()
            }
        }

        // 文字
        val label = TextView(context).apply {
            text = "保存到相册"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#161823"))  // 抖音深色文本
            typeface = Typeface.DEFAULT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(icon)
        row.addView(label)

        // 点击事件
        row.setOnClickListener { v ->
            onDownloadClicked(v)
        }

        return row
    }

    /**
     * 下载按钮点击处理
     *
     * 从 Aweme 缓存中获取当前视频/图片信息并下载
     */
    private fun onDownloadClicked(view: View) {
        val context = view.context

        // 方式1: 从缓存获取 Aweme
        val aweme = MediaCache.getCurrentAweme()
        if (aweme != null) {
            downloadFromAweme(context, aweme)
            return
        }

        // 方式2: 从 Activity 的 intent/extras 中尝试获取 URL
        if (downloadFromActivity(context)) return

        HookUtils.showToast(context, "无法获取当前作品信息，请重试")
        HookUtils.log("$TAG: 无可用的 Aweme 缓存")
    }

    /**
     * 从 Aweme 对象下载媒体
     */
    private fun downloadFromAweme(context: android.content.Context, aweme: Any) {
        try {
            // 优先下载视频
            val videoUrl = MediaCache.getVideoUrlFromAweme(aweme)
            if (videoUrl != null) {
                HookUtils.showToast(context, "正在保存视频...")
                MediaDownloader.download(context, videoUrl, "douyin_${System.currentTimeMillis()}.mp4",
                    onComplete = { uri ->
                        HookUtils.showToast(context, "视频已保存 ✓")
                    },
                    onError = { t ->
                        HookUtils.showToast(context, "保存失败: ${t.message}")
                    }
                )
                return
            }

            // 尝试图片
            val imageUrls = MediaCache.getImageUrlsFromAweme(aweme)
            if (imageUrls.isNotEmpty()) {
                HookUtils.showToast(context, "正在保存 ${imageUrls.size} 张图片...")
                for ((index, url) in imageUrls.withIndex()) {
                    val ext = if (url.contains("heic", ignoreCase = true)) "heic" else "jpg"
                    MediaDownloader.download(context, url, "douyin_${System.currentTimeMillis()}_$index.$ext",
                        onComplete = { uri ->
                            if (index == imageUrls.size - 1) {
                                HookUtils.showToast(context, "图片已保存 ✓")
                            }
                        },
                        onError = { t ->
                            HookUtils.log("$TAG: 图片[$index] 保存失败: ${t.message}")
                        }
                    )
                }
                return
            }

            // 尝试实况照片
            val livePhoto = MediaCache.findLivePhotoByUrl("")
            if (livePhoto != null) {
                MediaCache.downloadLivePhoto(context, livePhoto.first, livePhoto.second)
                return
            }

            HookUtils.showToast(context, "未找到可保存的媒体")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 下载失败: ${t.message}")
            HookUtils.showToast(context, "保存失败")
        }
    }

    /**
     * 从 Activity 上下文尝试获取媒体 URL
     * 作为缓存未命中时的备用方案
     */
    private fun downloadFromActivity(context: android.content.Context): Boolean {
        try {
            // 遍历 Activity 的 view 层级查找包含 URL 的内容
            val activity = context as? android.app.Activity ?: return false
            val rootView = activity.window.decorView as? ViewGroup ?: return false

            val urls = mutableListOf<String>()
            findMediaUrlsInView(rootView, urls)

            if (urls.isNotEmpty()) {
                val url = urls.first()
                val isVideo = MediaCache.isVideoUrl(url)
                val ext = if (isVideo) "mp4" else "jpg"

                HookUtils.showToast(activity, "正在保存${if (isVideo) "视频" else "图片"}...")
                MediaDownloader.download(activity, url, "douyin_${System.currentTimeMillis()}.$ext",
                    onComplete = { HookUtils.showToast(activity, "已保存 ✓") },
                    onError = { t -> HookUtils.showToast(activity, "保存失败") }
                )
                return true
            }
        } catch (_: Throwable) {}
        return false
    }

    /**
     * 递归遍历 View 树查找媒体 URL
     */
    private fun findMediaUrlsInView(view: View, urls: MutableList<String>) {
        if (urls.size >= 3) return  // 最多找3个

        // 检查 view tag
        val tag = view.tag
        if (tag is String && (tag.contains("playwm") || tag.contains("douyinvod") || tag.contains("/play/"))) {
            urls.add(tag)
        }

        // 检查 TextView 内容
        if (view is TextView) {
            val text = view.text?.toString() ?: ""
            val urlPattern = Regex("https?://[^\\s]+")
            urlPattern.find(text)?.let {
                val url = it.value
                if (url.contains("douyinvod") || url.contains("playwm") || url.contains("/play/")) {
                    urls.add(url)
                }
            }
        }

        // 递归子 View
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findMediaUrlsInView(view.getChildAt(i), urls)
            }
        }
    }
}
