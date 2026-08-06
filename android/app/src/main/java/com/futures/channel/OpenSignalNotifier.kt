package com.futures.channel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 开多/开空信号：系统通知 + 提示音 + 短震动。
 */
class OpenSignalNotifier(private val context: Context) {

    companion object {
        private const val TAG = "OpenSignalNotifier"
        /** v3：每次 ensure 若发现 importance/声音被用户改坏，则删除重建 */
        const val CHANNEL_ID = "open_signal_v3"
        const val NOTIFY_ID = 4200
        const val ACTION_DISMISS_ALERT = "com.futures.channel.DISMISS_ALERT"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    /** 必须持有引用，否则 Ringtone 可能在播完前被 GC，听不到声音 */
    private var ringtoneRef: Ringtone? = null
    private var toneGen: ToneGenerator? = null

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            // 用户手动把渠道 importance 降到 NONE 或关掉了声音 → 删掉重建
            val importanceOk = existing.importance >= NotificationManager.IMPORTANCE_HIGH
            val soundOk = existing.sound != null
            if (importanceOk && soundOk) {
                Log.d(TAG, "ensureChannel: 渠道 $CHANNEL_ID 正常，跳过")
                return
            }
            Log.w(
                TAG,
                "ensureChannel: 旧渠道 importance=${existing.importance} sound=${existing.sound}，删除重建"
            )
            try {
                mgr.deleteNotificationChannel(CHANNEL_ID)
            } catch (_: Exception) {
            }
        }
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "开仓信号提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "K 线出现开多/开空标记时提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 100, 180)
            enableLights(true)
            setShowBadge(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            setSound(sound, attrs)
        }
        mgr.createNotificationChannel(channel)
        Log.d(TAG, "ensureChannel: 渠道 $CHANNEL_ID 已创建 (IMPORTANCE_HIGH + 自定义声音/震动)")
    }

    fun canPostNotification(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "canPostNotification: App 级通知开关关闭")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "canPostNotification: POST_NOTIFICATIONS 权限未授予")
                return false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            val ch = mgr?.getNotificationChannel(CHANNEL_ID)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) {
                Log.w(TAG, "canPostNotification: 渠道 $CHANNEL_ID 被用户禁用 (importance=NONE)")
                return false
            }
        }
        return true
    }

    /**
     * 返回渠道是否被用户关掉（importance=NONE）；仅用于 UI 层提示用户去设置里开。
     * O 以下版本永远返回 false。
     */
    fun isChannelBlockedByUser(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return false
        val ch = mgr.getNotificationChannel(CHANNEL_ID) ?: return false
        return ch.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun notifyOpen(
        kind: String,
        title: String,
        body: String,
        sound: Boolean = true,
        vibrate: Boolean = true,
        notification: Boolean = true,
    ): Boolean {
        ensureChannel()
        if (sound) playSound()
        if (vibrate) vibrate()
        wakeScreen() // 亮屏，确保用户能看到提醒
        if (!notification) {
            Log.d(TAG, "notifyOpen: notification=false，跳过系统通知（已播声音+震动）")
            return true
        }
        if (!canPostNotification()) {
            Log.w(TAG, "notifyOpen: 无通知权限/渠道被禁，放弃发送 title=$title")
            return false
        }

        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        // 点击通知 → 打开 App
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(context, 0, launch, piFlags)

        // 全屏 Intent：直接弹到 App 前台，最大化提醒（需 USE_FULL_SCREEN_INTENT 权限）
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPi = PendingIntent.getActivity(context, 1, fullScreenIntent, piFlags)

        // 清除按钮 → 打开 App 并自动清除通知
        val dismissIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_DISMISS_ALERT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val dismissPi = PendingIntent.getActivity(context, 2, dismissIntent, piFlags)

        val color = if (kind == "long") 0xFFD32F2F.toInt() else 0xFF2E7D32.toInt()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true) // 常驻通知：小米 HyperOS 灵动岛只接管常驻通知
            .setContentIntent(pi)
            .setFullScreenIntent(fullScreenPi, true) // 全屏弹出
            .setColor(color)
            .setOnlyAlertOnce(false)
            .setSilent(false)
            .addAction(0, "✕ 清除提醒", dismissPi) // 手动清除按钮

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            var defaults = 0
            if (sound) defaults = defaults or NotificationCompat.DEFAULT_SOUND
            if (vibrate) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
            if (defaults != 0) builder.setDefaults(defaults)
        }

        return try {
            // 固定 ID：新信号覆盖旧通知，保持通知栏只有一条最新的开仓提醒
            NotificationManagerCompat.from(context)
                .notify(NOTIFY_ID, builder.build())
            Log.d(TAG, "notifyOpen: 通知已发送 id=$NOTIFY_ID title=$title (ongoing + fullScreen)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "notifyOpen: SecurityException 发送失败", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "notifyOpen: 发送失败", e)
            false
        }
    }

    /** 手动清除开仓通知（供 MainActivity onNewIntent 调用） */
    fun cancelAlert() {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFY_ID)
            Log.d(TAG, "cancelAlert: 已清除开仓通知 id=$NOTIFY_ID")
        } catch (e: Exception) {
            Log.e(TAG, "cancelAlert: 清除失败", e)
        }
    }

    /** 短暂点亮屏幕，确保用户注意到提醒 */
    private fun wakeScreen() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ChannelStrategy:WakeOnSignal"
            )
            wl.acquire(5000) // 5 秒后自动释放
            mainHandler.postDelayed({
                try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
            }, 5000)
            Log.d(TAG, "wakeScreen: 已点亮屏幕")
        } catch (e: Exception) {
            Log.w(TAG, "wakeScreen: 点亮失败", e)
        }
    }

    private fun playSound() {
        // 1) ToneGenerator：短促、可靠，不依赖通知权限
        try {
            toneGen?.release()
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 92)
            toneGen = tg
            val tone = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            tg.startTone(tone, 320)
            mainHandler.postDelayed({
                try {
                    tg.stopTone()
                    tg.release()
                } catch (_: Exception) {
                }
                if (toneGen === tg) toneGen = null
            }, 450)
        } catch (_: Exception) {
            // 2) 回退系统 Ringtone，并保持强引用直到播完
            try {
                ringtoneRef?.stop()
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.isLooping = false
                }
                ringtoneRef = ringtone
                ringtone.play()
                mainHandler.postDelayed({
                    try {
                        ringtone.stop()
                    } catch (_: Exception) {
                    }
                    if (ringtoneRef === ringtone) ringtoneRef = null
                }, 1500)
            } catch (_: Exception) {
            }
        }
    }

    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null) {
                Log.w(TAG, "vibrate: Vibrator 服务不可用")
                return
            }
            if (!vibrator.hasVibrator()) {
                Log.w(TAG, "vibrate: 设备无震动器")
                return
            }
            val pattern = longArrayOf(0, 300, 100, 300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                Log.d(TAG, "vibrate: VibrationEffect.createWaveform 已调用")
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
                Log.d(TAG, "vibrate: deprecated vibrate(pattern) 已调用")
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrate: 震动失败", e)
        }
    }
}
