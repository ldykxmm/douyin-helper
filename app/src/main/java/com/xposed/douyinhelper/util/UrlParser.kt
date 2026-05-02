package com.xposed.douyinhelper.util

import java.net.URI
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * URL 解析工具
 *
 * 功能:
 * - getNoWatermarkUrl(): 多种去水印策略
 * - extractAwemeId(): 从 URL 提取视频 ID
 * - isVideoUrl(): 判断是否视频 URL
 * - isImageUrl(): 判断是否图片 URL
 *
 * 去水印核心原理:
 * 抖音的带水印视频 URL 中包含 /playwm/ 路径段，
 * 将其替换为 /play/ 即可获得无水印版本。
 * 同时可能需要移除 watermark 相关参数。
 */
object UrlParser {

    /** 抖音视频 CDN 域名特征 */
    private val VIDEO_DOMAINS = listOf(
        "douyinvod.com",
        "bytevcloudcdn.com",
        "bytecdn.cn",
        "byteimg.com",
        "snssdk.com",
        "douyin.com",
        "amemv.com",
        "pstatp.com",
        "bytedance.com",
        "ibytedtos.com",
        "zijieapi.com",
        "bdurl.net"
    )

    /** 图片 CDN 域名特征 */
    private val IMAGE_DOMAINS = listOf(
        "byteimg.com",
        "pstatp.com",
        "snssdk.com",
        "douyin.com",
        "bytedance.com",
        "ibytedtos.com"
    )

    /** 视频文件扩展名 */
    private val VIDEO_EXTENSIONS = listOf(
        ".mp4", ".mov", ".m3u8", ".flv", ".webm", ".avi"
    )

    /** 图片文件扩展名 */
    private val IMAGE_EXTENSIONS = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif"
    )

    /** playwm 路径模式 */
    private val PLAYWM_PATTERN = Pattern.compile("/playwm/")

    /** play 路径模式 (用于检测是否已经是无水印) */
    private val PLAY_PATTERN = Pattern.compile("/play/")

    /**
     * 获取无水印 URL
     *
     * 采用多种策略尝试去除水印:
     * 1. 替换 /playwm/ 为 /play/
     * 2. 移除 watermark 相关参数
     * 3. 调整 ratio 参数
     * 4. 调整字幕水印参数
     *
     * @param originalUrl 原始 URL
     * @return 无水印 URL，如果无法处理则返回原始 URL
     */
    fun getNoWatermarkUrl(originalUrl: String): String {
        if (originalUrl.isEmpty()) return originalUrl

        var url = originalUrl

        try {
            // 策略1: 替换 /playwm/ 为 /play/ (最核心的去水印方式)
            if (url.contains("/playwm/")) {
                url = url.replace("/playwm/", "/play/")
            }

            // 策略2: 移除 watermark 参数
            url = removeWatermarkParams(url)

            // 策略3: 调整 ratio 参数 (某些情况下影响水印)
            url = adjustRatioParam(url)

            // 策略4: 移除字幕水印参数
            url = removeSubtitleWatermark(url)

            // 策略5: 处理 URL 编码的情况
            if (url.contains("%2Fplaywm%2F")) {
                url = url.replace("%2Fplaywm%2F", "%2Fplay%2F")
            }

        } catch (t: Throwable) {
            HookUtils.log("UrlParser: 处理URL失败: ${t.message}")
            return originalUrl
        }

        return url
    }

    /**
     * 移除 URL 中的水印相关参数
     */
    private fun removeWatermarkParams(url: String): String {
        try {
            val uri = URI(url)
            val query = uri.query ?: return url

            // 过滤掉水印相关参数
            val params = query.split("&")
                .filter { param ->
                    val key = param.split("=").firstOrNull()?.lowercase() ?: ""
                    !key.contains("watermark") &&
                    !key.contains("wm_") &&
                    !key.contains("logo") &&
                    !key.contains("brand")
                }
                .joinToString("&")

            // 重建 URL
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return url
            val path = uri.path ?: ""
            val port = if (uri.port > 0) ":${uri.port}" else ""

            return if (params.isNotEmpty()) {
                "$scheme://$host$port$path?$params"
            } else {
                "$scheme://$host$port$path"
            }
        } catch (_: Throwable) {
            return url
        }
    }

    /**
     * 调整 ratio 参数
     * 某些情况下 ratio 参数会影响视频质量和水印
     */
    private fun adjustRatioParam(url: String): String {
        // 如果 URL 包含 ratio 参数，尝试设置为最高质量
        return url.replace(Regex("ratio=\\d+x\\d+"), "ratio=1080p")
    }

    /**
     * 移除字幕水印参数
     */
    private fun removeSubtitleWatermark(url: String): String {
        return url
            .replace(Regex("[?&]subtitle=\\w+"), "")
            .replace(Regex("[?&]caption=\\w+"), "")
    }

    /**
     * 从 URL 中提取抖音视频 ID
     *
     * @param url 视频 URL
     * @return 视频 ID，如果无法提取则返回 null
     */
    fun extractAwemeId(url: String): String? {
        if (url.isEmpty()) return null

        try {
            // 尝试从 URL 路径中提取
            // 格式: /video/1234567890 或 /v1234567890
            val patterns = listOf(
                Pattern.compile("/video/(\\d+)"),
                Pattern.compile("/v(\\d+)"),
                Pattern.compile("aweme_id=(\\d+)"),
                Pattern.compile("item_id=(\\d+)"),
                Pattern.compile("awemeId=(\\d+)"),
                Pattern.compile("itemId=(\\d+)")
            )

            for (pattern in patterns) {
                val matcher = pattern.matcher(url)
                if (matcher.find()) {
                    return matcher.group(1)
                }
            }

            // 尝试从查询参数中提取
            val uri = URI(url)
            val query = uri.query
            if (query != null) {
                val params = query.split("&").associate {
                    val parts = it.split("=")
                    parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
                }

                val id = params["aweme_id"] ?: params["item_id"] ?: params["awemeId"] ?: params["itemId"]
                if (!id.isNullOrEmpty()) return id
            }
        } catch (t: Throwable) {
            HookUtils.log("UrlParser: 提取AwemeId失败: ${t.message}")
        }

        return null
    }

    /**
     * 判断 URL 是否为视频 URL
     *
     * @param url 要检查的 URL
     * @return true 如果是视频 URL
     */
    fun isVideoUrl(url: String): Boolean {
        if (url.isEmpty()) return false

        val lowerUrl = url.lowercase()

        // 检查文件扩展名
        if (VIDEO_EXTENSIONS.any { lowerUrl.contains(it) }) return true

        // 检查域名特征
        if (VIDEO_DOMAINS.any { lowerUrl.contains(it) }) {
            // 域名匹配后，进一步检查路径特征
            if (lowerUrl.contains("/play") || lowerUrl.contains("/video") ||
                lowerUrl.contains("playwm") || lowerUrl.contains("aweme")) {
                return true
            }
        }

        // 检查特定路径模式
        if (lowerUrl.contains("/playwm/") || lowerUrl.contains("/play/")) return true

        return false
    }

    /**
     * 判断 URL 是否为图片 URL
     *
     * @param url 要检查的 URL
     * @return true 如果是图片 URL
     */
    fun isImageUrl(url: String): Boolean {
        if (url.isEmpty()) return false

        val lowerUrl = url.lowercase()

        // 检查文件扩展名
        if (IMAGE_EXTENSIONS.any { lowerUrl.contains(it) }) return true

        // 检查域名特征
        if (IMAGE_DOMAINS.any { lowerUrl.contains(it) }) {
            if (lowerUrl.contains("/image") || lowerUrl.contains("/pic") ||
                lowerUrl.contains("/photo") || lowerUrl.contains("/img")) {
                return true
            }
        }

        // 检查特定路径模式
        if (lowerUrl.contains("/aweme/") && lowerUrl.contains("image")) return true

        return false
    }

    /**
     * 判断 URL 是否为实况照片的视频部分
     *
     * @param url 要检查的 URL
     * @return true 如果是实况照片视频 URL
     */
    fun isLivePhotoVideoUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val lowerUrl = url.lowercase()
        return lowerUrl.contains("live") && isVideoUrl(url)
    }

    /**
     * 从 URL 中提取文件名
     *
     * @param url URL
     * @return 文件名，如果无法提取则返回 null
     */
    fun extractFileName(url: String): String? {
        if (url.isEmpty()) return null

        try {
            val uri = URI(url)
            val path = uri.path ?: return null
            val fileName = path.substringAfterLast("/")
            return if (fileName.isNotEmpty()) URLDecoder.decode(fileName, "UTF-8") else null
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * 获取 URL 的域名
     *
     * @param url URL
     * @return 域名
     */
    fun getDomain(url: String): String? {
        return try {
            URI(url).host
        } catch (_: Throwable) {
            null
        }
    }
}
