package com.xposed.douyinhelper.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * DouyinHelper 设置管理
 *
 * 使用 SharedPreferences 存储模块配置。
 * 所有 Hook 模块通过此类读取用户偏好。
 */
object DouyinHelperSettings {

    private const val PREFS_NAME = "douyin_helper_settings"

    // ==================== Key 定义 ====================
    private const val KEY_AUTO_SAVE_VIDEO = "auto_save_video"
    private const val KEY_AUTO_SAVE_IMAGES = "auto_save_images"
    private const val KEY_AUTO_SAVE_LIVE_PHOTO = "auto_save_live_photo"
    private const val KEY_SAVE_COMMENT_MEDIA = "save_comment_media"
    private const val KEY_SAVE_DIRECTORY = "save_directory"
    private const val KEY_DOWNLOAD_QUALITY = "download_quality"
    private const val KEY_SHOW_TOAST = "show_toast"
    private const val KEY_SHOW_NOTIFICATION = "show_notification"

    // ==================== 默认值 ====================
    private const val DEFAULT_AUTO_SAVE_VIDEO = true
    private const val DEFAULT_AUTO_SAVE_IMAGES = true
    private const val DEFAULT_AUTO_SAVE_LIVE_PHOTO = true
    private const val DEFAULT_SAVE_COMMENT_MEDIA = true
    private const val DEFAULT_SAVE_DIRECTORY = "DouyinHelper"
    private const val DEFAULT_DOWNLOAD_QUALITY = "high"
    private const val DEFAULT_SHOW_TOAST = true
    private const val DEFAULT_SHOW_NOTIFICATION = true

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * 初始化设置管理器
     *
     * @param context Android Context
     */
    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun getPrefs(): SharedPreferences {
        return prefs ?: throw IllegalStateException("DouyinHelperSettings 尚未初始化")
    }

    // ==================== 自动保存视频 ====================

    fun isAutoSaveVideo(): Boolean {
        return getPrefs().getBoolean(KEY_AUTO_SAVE_VIDEO, DEFAULT_AUTO_SAVE_VIDEO)
    }

    fun setAutoSaveVideo(enabled: Boolean) {
        getPrefs().edit().putBoolean(KEY_AUTO_SAVE_VIDEO, enabled).apply()
    }

    // ==================== 自动保存图集 ====================

    fun isAutoSaveImages(): Boolean {
        return getPrefs().getBoolean(KEY_AUTO_SAVE_IMAGES, DEFAULT_AUTO_SAVE_IMAGES)
    }

    fun setAutoSaveImages(enabled: Boolean) {
        getPrefs().edit().putBoolean(KEY_AUTO_SAVE_IMAGES, enabled).apply()
    }

    // ==================== 自动保存实况照片 ====================

    fun isAutoSaveLivePhoto(): Boolean {
        return getPrefs().getBoolean(KEY_AUTO_SAVE_LIVE_PHOTO, DEFAULT_AUTO_SAVE_LIVE_PHOTO)
    }

    fun setAutoSaveLivePhoto(enabled: Boolean) {
        getPrefs().edit().putBoolean(KEY_AUTO_SAVE_LIVE_PHOTO, enabled).apply()
    }

    // ==================== 评论区媒体保存 ====================

    fun isSaveCommentMedia(): Boolean {
        return getPrefs().getBoolean(KEY_SAVE_COMMENT_MEDIA, DEFAULT_SAVE_COMMENT_MEDIA)
    }

    fun setSaveCommentMedia(enabled: Boolean) {
        getPrefs().edit().putBoolean(KEY_SAVE_COMMENT_MEDIA, enabled).apply()
    }

    // ==================== 保存目录 ====================

    fun getSaveDirectory(): String {
        return getPrefs().getString(KEY_SAVE_DIRECTORY, DEFAULT_SAVE_DIRECTORY) ?: DEFAULT_SAVE_DIRECTORY
    }

    fun setSaveDirectory(directory: String) {
        getPrefs().edit().putString(KEY_SAVE_DIRECTORY, directory).apply()
    }

    // ==================== 下载质量 ====================

    /**
     * 获取下载质量偏好
     * @return "high" / "medium" / "low"
     */
    fun getDownloadQuality(): String {
        return getPrefs().getString(KEY_DOWNLOAD_QUALITY, DEFAULT_DOWNLOAD_QUALITY) ?: DEFAULT_DOWNLOAD_QUALITY
    }

    fun setDownloadQuality(quality: String) {
        getPrefs().edit().putString(KEY_DOWNLOAD_QUALITY, quality).apply()
    }

    // ==================== Toast 提示 ====================

    fun isShowToast(): Boolean {
        return getPrefs().getBoolean(KEY_SHOW_TOAST, DEFAULT_SHOW_TOAST)
    }

    fun setShowToast(enabled: Boolean) {
        getPrefs().edit().putBoolean(KEY_SHOW_TOAST, enabled).apply()
    }

    // ==================== 通知 ====================

    fun isShowNotification(): Boolean {
        return getPrefs().getBoolean(KEY_SHOW_NOTIFICATION, DEFAULT_SHOW_NOTIFICATION)
    }

    fun setShowNotification(enabled: Boolean) {
        getPrefs().edit().putBoolean(KEY_SHOW_NOTIFICATION, enabled).apply()
    }

    // ==================== 批量操作 ====================

    /** 重置所有设置为默认值 */
    fun resetToDefaults() {
        getPrefs().edit().clear().apply()
    }

    /** 导出所有设置 (调试用) */
    fun exportAll(): Map<String, Any?> {
        return getPrefs().all
    }
}
