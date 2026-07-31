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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 开多/开空信号：系统通知 + 提示音 + 短震动。
 */
class OpenSignalNotifier(private val context: Context) {

    companion object {
        /** v2：强制重建频道（旧频道若曾以低优先级创建，系统不允许就地改 importance） */
        const val CHANNEL_ID = "open_signal_v2"
        private const val NOTIFY_ID_BASE = 4200
    }

    private var notifySeq = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 必须持有引用，否则 Ringtone 可能在播完前被 GC，听不到声音 */
    private var ringtoneRef: Ringtone? = null
    private var toneGen: ToneGenerator? = null

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
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
    }

    fun canPostNotification(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true
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
        if (!notification) return true
        if (!canPostNotification()) return false

        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(context, 0, launch, piFlags)

        val color = if (kind == "long") 0xFFD32F2F.toInt() else 0xFF2E7D32.toInt()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setColor(color)
            .setOnlyAlertOnce(false)
            .setSilent(false)

        if (sound || vibrate) {
            var defaults = 0
            if (sound) defaults = defaults or NotificationCompat.DEFAULT_SOUND
            if (vibrate) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
            builder.setDefaults(defaults)
        }

        notifySeq = (notifySeq + 1) % 1000
        return try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFY_ID_BASE + notifySeq, builder.build())
            true
        } catch (_: SecurityException) {
            false
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
            } ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 220, 120, 220), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 220, 120, 220), -1)
            }
        } catch (_: Exception) {
        }
    }
}
