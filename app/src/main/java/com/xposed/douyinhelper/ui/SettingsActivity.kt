package com.xposed.douyinhelper.ui

import android.os.Bundle
import android.preference.PreferenceActivity
import android.preference.PreferenceFragment

/**
 * DouyinHelper 设置页面
 *
 * 使用传统 PreferenceActivity + PreferenceFragment 实现。
 * 用户可通过 LSPosed 管理器或模块自带入口打开此页面。
 *
 * 配置项:
 * - 自动保存视频
 * - 自动保存图集
 * - 自动保存实况照片
 * - 评论区媒体保存
 * - 保存目录
 * - 下载质量偏好
 */
class SettingsActivity : PreferenceActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    /**
     * 设置 Fragment
     */
    class SettingsFragment : PreferenceFragment() {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            addPreferencesFromResource(com.xposed.douyinhelper.R.xml.prefs)

            // 初始化设置管理
            DouyinHelperSettings.init(activity)

            // 绑定偏好变更监听
            bindPreferenceListeners()
        }

        /**
         * 绑定偏好变更监听
         * 用户修改设置时实时写入 DouyinHelperSettings
         */
        private fun bindPreferenceListeners() {
            findPreference("auto_save_video")?.setOnPreferenceChangeListener { _, newValue ->
                DouyinHelperSettings.setAutoSaveVideo(newValue as Boolean)
                true
            }

            findPreference("auto_save_images")?.setOnPreferenceChangeListener { _, newValue ->
                DouyinHelperSettings.setAutoSaveImages(newValue as Boolean)
                true
            }

            findPreference("auto_save_live_photo")?.setOnPreferenceChangeListener { _, newValue ->
                DouyinHelperSettings.setAutoSaveLivePhoto(newValue as Boolean)
                true
            }

            findPreference("save_comment_media")?.setOnPreferenceChangeListener { _, newValue ->
                DouyinHelperSettings.setSaveCommentMedia(newValue as Boolean)
                true
            }

            findPreference("save_directory")?.setOnPreferenceChangeListener { _, newValue ->
                DouyinHelperSettings.setSaveDirectory(newValue as String)
                true
            }

            findPreference("download_quality")?.setOnPreferenceChangeListener { _, newValue ->
                DouyinHelperSettings.setDownloadQuality(newValue as String)
                true
            }

            findPreference("show_toast")?.setOnPreferenceChangeListener { _, newValue ->
                DouyinHelperSettings.setShowToast(newValue as Boolean)
                true
            }

            findPreference("show_notification")?.setOnPreferenceChangeListener { _, newValue ->
                DouyinHelperSettings.setShowNotification(newValue as Boolean)
                true
            }
        }
    }
}
