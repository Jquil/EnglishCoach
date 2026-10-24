package com.englishcoach.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.englishcoach.EnglishCoachApp
import com.englishcoach.MainActivity
import com.englishcoach.R
import com.englishcoach.data.todayString
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 督促通知：
 * - 每日定时提醒（WorkManager 周期任务，重启后自动恢复；到点先查当天是否已打卡，打了就不打扰）
 * - 断签次日温和召回（只发一次，文案不羞辱）
 */
object ReminderScheduler {

    private const val TAG = "EC-Reminder"
    private const val WORK_NAME = "ec_daily_reminder"
    const val CHANNEL_REMINDER = "ec_reminder"
    const val CHANNEL_RECALL = "ec_recall"

    /** 创建通知渠道（minSdk 26，无需版本判断） */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val reminder = NotificationChannel(
            CHANNEL_REMINDER, "每日练习提醒", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "每天固定时间提醒开口练英语" }
        val recall = NotificationChannel(
            CHANNEL_RECALL, "断签召回", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "断签后第二天温和召回一次" }
        manager.createNotificationChannel(reminder)
        manager.createNotificationChannel(recall)
    }

    /**
     * 排程每日提醒（设置页改时间/开关时重新调用，REPLACE 旧任务）。
     * @param timeString "20:00"
     */
    fun scheduleDaily(context: Context, timeString: String, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "每日提醒已关闭")
            return
        }
        val delay = delayUntil(timeString)
        val request = PeriodicWorkRequestBuilder<DailyReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        // 用 REPLACE 而不是 UPDATE：UPDATE 会保留旧任务的执行锚点，改时间后实际触发点会漂移；
        // 每次启动/设置变更都用新锚点重排，保证"到点才提醒"精准
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request)
        Log.d(TAG, "每日提醒已排程：$timeString，${delay.toMinutes()} 分钟后首次触发")
    }

    /** 距下一次 timeString 的时长 */
    private fun delayUntil(timeString: String): Duration {
        val parts = timeString.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 20
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(hour, minute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    /** 断签召回（App 启动时调用；StreakCalculator 内部保证"只发一次"） */
    suspend fun maybeNotifyRecall(context: Context) {
        val app = context.applicationContext as EnglishCoachApp
        if (app.streakCalculator.shouldSendRecallNotification()) {
            showNotification(
                context = context,
                channelId = CHANNEL_RECALL,
                notificationId = 1002,
                title = "火苗还在 🔥",
                text = "昨天歇了一天，回来点上就行。3 分钟也算赢！"
            )
        }
    }

    /** 发通知（无权限时静默跳过，不崩溃） */
    fun showNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        text: String
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "无通知权限，跳过通知")
            return
        }
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }
}

/**
 * 每日提醒 Worker：到点先查当天是否已打卡（3 分钟保底），打了就不打扰。
 */
class DailyReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext as EnglishCoachApp
            val today = app.statsStore.getRecord(todayString())
            if (today?.checkedIn == true) {
                Log.d("EC-Reminder", "今天已打卡，不打扰")
            } else {
                ReminderScheduler.showNotification(
                    context = applicationContext,
                    channelId = ReminderScheduler.CHANNEL_REMINDER,
                    notificationId = 1001,
                    title = "今天的口语练习还没开始",
                    text = "每天开口 3 分钟也算赢，火苗等你自己点 🔥"
                )
            }
            Result.success()
        } catch (e: Exception) {
            Log.w("EC-Reminder", "提醒任务失败: ${e.message}")
            Result.success() // 自用提醒任务，失败不重试轰炸
        }
    }
}
