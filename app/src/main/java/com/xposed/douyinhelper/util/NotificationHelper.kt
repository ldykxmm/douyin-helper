package com.xposed.douyinhelper.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 通知工具
 *
 * 功能:
 * - 下载进度通知
 * - 下载完成通知
 * - 创建通知渠道
 *
 * 需要 Android 8.0+ 创建通知渠道
 * 需要 Android 13+ 请求通知权限
 */
object NotificationHelper {

    private const val CHANNEL_ID = "douyin_helper_download"
    private const val CHANNEL_NAME = "下载通知"
    private const val CHANNEL_DESC = "DouyinHelper 下载进度和完成通知"

    /** 通知 ID 计数器 */
    private var notificationId = 1000

    /**
     * 创建通知渠道
     * 必须在发送通知前调用
     *
     * @param context Android Context
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW  // 低重要性，不发声
            ).apply {
                description = CHANNEL_DESC
                setShowBadge(false)
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 显示下载进度通知
     *
     * @param context Android Context
     * @param fileName 文件名
     * @param progress 进度 (0-100)
     * @param totalSize 总大小 (bytes)
     * @return 通知 ID，用于后续更新
     */
    fun showProgress(context: Context, fileName: String, progress: Int, totalSize: Long = 0): Int {
        createNotificationChannel(context)

        val id = notificationId++

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载")
            .setContentText(fileName)
            .setProgress(100, progress, false)
            .setOngoing(true)  // 不可滑动删除
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (totalSize > 0) {
            builder.setSubText("${formatSize(totalSize)} - $progress%")
        }

        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (t: Throwable) {
            HookUtils.log("NotificationHelper: 显示进度通知失败: ${t.message}")
        }

        return id
    }

    /**
     * 更新下载进度通知
     *
     * @param context Android Context
     * @param notificationId 通知 ID
     * @param fileName 文件名
     * @param progress 进度 (0-100)
     */
    fun updateProgress(context: Context, notificationId: Int, fileName: String, progress: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载")
            .setContentText(fileName)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: Throwable) { }
    }

    /**
     * 显示下载完成通知
     *
     * @param context Android Context
     * @param fileName 文件名
     * @param filePath 文件路径 (可选)
     */
    fun showComplete(context: Context, fileName: String, filePath: String? = null) {
        createNotificationChannel(context)

        val id = notificationId++

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)  // 点击后自动取消

        if (filePath != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("已保存到: $filePath"))
        }

        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (t: Throwable) {
            HookUtils.log("NotificationHelper: 显示完成通知失败: ${t.message}")
        }
    }

    /**
     * 显示下载失败通知
     *
     * @param context Android Context
     * @param fileName 文件名
     * @param error 错误信息
     */
    fun showError(context: Context, fileName: String, error: String) {
        createNotificationChannel(context)

        val id = notificationId++

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("下载失败")
            .setContentText(fileName)
            .setStyle(NotificationCompat.BigTextStyle().bigText("错误: $error"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: Throwable) { }
    }

    /**
     * 取消通知
     *
     * @param context Android Context
     * @param notificationId 通知 ID
     */
    fun cancel(context: Context, notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (_: Throwable) { }
    }

    /**
     * 取消所有 DouyinHelper 通知
     *
     * @param context Android Context
     */
    fun cancelAll(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancelAll()
        } catch (_: Throwable) { }
    }

    /**
     * 格式化文件大小
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }
}
