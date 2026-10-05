package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.DataStoreManager
import com.example.data.PayoutStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object NotificationChannels {

    const val CHANNEL_TIMER_ID = "channel_watch_timer"
    const val CHANNEL_ALERT_ID = "channel_watch_alert"
    const val CHANNEL_COMPLETION_ID = "channel_watch_completion"
    const val CHANNEL_ADMIN_UPDATES_ID = "channel_kingo_admin_push_v2"

    const val NOTIFICATION_TIMER_ID = 1001
    const val NOTIFICATION_ALERT_ID = 1002
    const val NOTIFICATION_COMPLETION_ID = 1003

    private val notifyMutex = Mutex()
    private val inMemoryDispatchedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
                ?: return

            // 1. Silent Ongoing Timer Channel (updates every second)
            val timerChannel = NotificationChannel(
                CHANNEL_TIMER_ID,
                "Watch Progress Timer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live watch progress while watching YouTube video"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // 2. High-Importance Red Alert Channel (vibration + heads-up)
            val alertChannel = NotificationChannel(
                CHANNEL_ALERT_ID,
                "Watch Red Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts when wrong video is detected or task cancelled"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                lightColor = Color.RED
                enableLights(true)
            }

            // 3. Task Completion Channel
            val completionChannel = NotificationChannel(
                CHANNEL_COMPLETION_ID,
                "Task Rewards & Completion",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when task completes and coins are credited"
                enableVibration(true)
            }

            // 4. Instant Admin Push Notifications Channel (High Importance Heads-Up + Sound + Vibration)
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val adminUpdatesChannel = NotificationChannel(
                CHANNEL_ADMIN_UPDATES_ID,
                "Kingo King Live Push Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Instant push notifications for new tasks, posts, withdrawals, wallet updates & app updates"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
                lightColor = Color.parseColor("#F59E0B")
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                if (defaultSoundUri != null) {
                    setSound(defaultSoundUri, audioAttributes)
                }
            }

            notificationManager.createNotificationChannels(
                listOf(timerChannel, alertChannel, completionChannel, adminUpdatesChannel)
            )
        }
    }

    fun sendAdminUpdateNotification(
        context: Context,
        title: String,
        body: String,
        dedupKey: String? = null,
        targetScreen: String = "home",
        targetItemId: String? = null,
        allowOnAdminApp: Boolean = false,
        notificationId: Int = ((dedupKey?.hashCode()?.let { Math.abs(it) } ?: (System.currentTimeMillis() % 100000).toInt()) % 80000) + 2000
    ) {
        if (!allowOnAdminApp && com.example.BuildConfig.APP_ROLE == "ADMIN") return
        if (!dedupKey.isNullOrBlank()) {
            // Atomically check and register dedupKey
            if (!inMemoryDispatchedKeys.add(dedupKey)) {
                return
            }
        }
        try {
            createChannels(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("target_screen", targetScreen)
                if (!targetItemId.isNullOrBlank()) {
                    putExtra("target_item_id", targetItemId)
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ADMIN_UPDATES_ID)
                .setSmallIcon(R.drawable.ic_stat_kingo_notification)
                .setColor(Color.parseColor("#F59E0B"))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(notificationId, notification)
        } catch (_: Exception) {}
    }

    /**
     * Centralized real-time notification evaluator that checks DataStore for ANY new Admin action:
     * - New or Pinned Video Tasks (1 task = 1 notification)
     * - New or Pinned Admin Posts / Banners / Urgent Alerts
     * - Withdrawal Request Submitted / Approved / Payment Done / Rejected & Refunded
     * - Admin Wallet Balance Adjustment & 10% Referral Bonuses
     * - Support Chat Replies
     * - Mandatory App Update Available
     * Runs both when the app is in the foreground (MainViewModel) and in the background (YouTubeLiveSearchService).
     */
    suspend fun checkAndDispatchAdminNotifications(
        context: Context,
        dataStoreManager: DataStoreManager
    ) {
        notifyMutex.withLock {
            try {
                val isAdminApp = com.example.BuildConfig.APP_ROLE == "ADMIN"
                val notified = dataStoreManager.notifiedItemIdsFlow.first()
                inMemoryDispatchedKeys.addAll(notified)
                val newlyNotifiedKeys = mutableSetOf<String>()

                if (!isAdminApp) {
                    // 1. Check Video Tasks (New tasks + Pinned tasks) - Strictly 1 notification per task
                    val defaultTaskIds = setOf("default_rick", "default_android15", "default_kotlin_course", "default_lofi_live")
                    val tasks = dataStoreManager.videoTasksFlow.first()
                    for (t in tasks) {
                        if (defaultTaskIds.contains(t.id)) continue
                        val taskKey = "task_${t.id}"
                        if (!notified.contains(taskKey) && !notified.contains(t.id) && !inMemoryDispatchedKeys.contains(taskKey) && !inMemoryDispatchedKeys.contains(t.id)) {
                            newlyNotifiedKeys.add(taskKey)
                            newlyNotifiedKeys.add(t.id)
                            sendAdminUpdateNotification(
                                context = context,
                                title = "🎬 New Video Task (+${t.rewardCoins} Coins)",
                                body = t.title,
                                dedupKey = taskKey,
                                targetScreen = "tasks",
                                targetItemId = t.id
                            )
                        } else if (t.isPinned && t.pinnedAt > 1700000000000L) {
                            val pinKey = "task_pin_${t.id}_${t.pinnedAt}"
                            if (!notified.contains(pinKey) && !inMemoryDispatchedKeys.contains(pinKey)) {
                                newlyNotifiedKeys.add(pinKey)
                                sendAdminUpdateNotification(
                                    context = context,
                                    title = "⭐ Featured Task (+${t.rewardCoins} Coins)",
                                    body = t.title,
                                    dedupKey = pinKey,
                                    targetScreen = "tasks",
                                    targetItemId = t.id
                                )
                            }
                        }
                    }

                    // 2. Check Posts / Banners / Push Notifications - Strictly 1 notification per post
                    val defaultPostIds = setOf("default_welcome_banner")
                    val posts = dataStoreManager.adminPostsFlow.first()
                    for (p in posts) {
                        if (defaultPostIds.contains(p.id)) continue
                        val postKey = "post_${p.id}"
                        val targetTab = when (p.targetTab.trim().uppercase()) {
                            "TASKS" -> "tasks"
                            "WALLET" -> "wallet"
                            "ME" -> "me"
                            else -> "home"
                        }
                        val postNotificationTitle = if (p.title.isNotBlank()) p.title else "📢 New Update"
                        val postNotificationBody = if (p.message.isNotBlank()) p.message else "Tap to view in the app."

                        if (!notified.contains(postKey) && !notified.contains(p.id) && !inMemoryDispatchedKeys.contains(postKey) && !inMemoryDispatchedKeys.contains(p.id)) {
                            newlyNotifiedKeys.add(postKey)
                            newlyNotifiedKeys.add(p.id)
                            sendAdminUpdateNotification(
                                context = context,
                                title = postNotificationTitle,
                                body = postNotificationBody,
                                dedupKey = postKey,
                                targetScreen = targetTab,
                                targetItemId = p.id
                            )
                        } else if (p.isPinned && p.pinnedAt > 1700000000000L) {
                            val pinKey = "post_pin_${p.id}_${p.pinnedAt}"
                            if (!notified.contains(pinKey) && !inMemoryDispatchedKeys.contains(pinKey)) {
                                newlyNotifiedKeys.add(pinKey)
                                sendAdminUpdateNotification(
                                    context = context,
                                    title = if (p.title.isNotBlank()) "📢 ${p.title}" else "📢 Featured Update",
                                    body = postNotificationBody,
                                    dedupKey = pinKey,
                                    targetScreen = targetTab,
                                    targetItemId = p.id
                                )
                            }
                        }
                    }

                    // 3. Check Payout / Withdrawal Requests (APPROVED / COMPLETED / REJECTED)
                    val currentEmail = dataStoreManager.currentUserEmailFlow.first()?.trim()?.lowercase()
                    val payouts = dataStoreManager.payoutRequestsFlow.first()
                    for (req in payouts) {
                        if (!currentEmail.isNullOrBlank() && !req.userEmail.equals(currentEmail, ignoreCase = true)) {
                            continue
                        }
                        if (req.status != PayoutStatus.PENDING) {
                            val statusNotifyKey = "payout_${req.id}_${req.status.name}"
                            if (!notified.contains(statusNotifyKey) && !inMemoryDispatchedKeys.contains(statusNotifyKey)) {
                                newlyNotifiedKeys.add(statusNotifyKey)
                                val safeCoins = if (req.amountCoins > 0) req.amountCoins else (req.amountInr * 100).toInt()
                                val inrStr = String.format(Locale.US, "%.2f", req.amountInr)
                                when (req.status) {
                                    PayoutStatus.APPROVED -> {
                                        sendAdminUpdateNotification(
                                            context = context,
                                            title = "✅ Withdrawal Approved (₹$inrStr)",
                                            body = "Your ₹$inrStr payout via ${req.method} is approved and processing.",
                                            dedupKey = statusNotifyKey,
                                            targetScreen = "wallet",
                                            targetItemId = req.id
                                        )
                                    }
                                    PayoutStatus.COMPLETED -> {
                                        sendAdminUpdateNotification(
                                            context = context,
                                            title = "🎉 Payment Sent! ₹$inrStr",
                                            body = "₹$inrStr ($safeCoins Coins) has been sent to ${req.method} (${req.destination}).",
                                            dedupKey = statusNotifyKey,
                                            targetScreen = "wallet",
                                            targetItemId = req.id
                                        )
                                    }
                                    PayoutStatus.REJECTED -> {
                                        sendAdminUpdateNotification(
                                            context = context,
                                            title = "❌ Withdrawal Refunded (+$safeCoins Coins)",
                                            body = "$safeCoins Coins have been returned to your wallet.",
                                            dedupKey = statusNotifyKey,
                                            targetScreen = "wallet",
                                            targetItemId = req.id
                                        )
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }

                    // 4. Check Wallet Transactions for Coin Updates & Referral Bonuses
                    val txList = dataStoreManager.transactionsFlow.first()
                    for (tx in txList) {
                        val isAdminCoin = tx.id.startsWith("admin_coin_") || tx.title.contains("Balance Updated") || tx.title.contains("Admin Balance Update")
                        val isRefBonus = tx.id.startsWith("ref_withdraw_bonus_") || tx.title.contains("Referral Withdraw Bonus")
                        val txKey = "tx_${tx.id}"
                        if ((isAdminCoin || isRefBonus) && !notified.contains(txKey) && !notified.contains(tx.id) && !inMemoryDispatchedKeys.contains(txKey) && !inMemoryDispatchedKeys.contains(tx.id)) {
                            newlyNotifiedKeys.add(txKey)
                            newlyNotifiedKeys.add(tx.id)
                            val sign = if (tx.coins >= 0) "+${tx.coins}" else "${tx.coins}"
                            if (isRefBonus) {
                                sendAdminUpdateNotification(
                                    context = context,
                                    title = "🤝 Referral Bonus ($sign Coins)",
                                    body = tx.title,
                                    dedupKey = txKey,
                                    targetScreen = "wallet",
                                    targetItemId = tx.id
                                )
                            } else {
                                sendAdminUpdateNotification(
                                    context = context,
                                    title = "🪙 Wallet Updated ($sign Coins)",
                                    body = "Your wallet balance has been updated.",
                                    dedupKey = txKey,
                                    targetScreen = "wallet",
                                    targetItemId = tx.id
                                )
                            }
                        }
                    }

                    // 5. Check Mandatory App Update Availability
                    val appUpdate = dataStoreManager.remoteAppUpdateFlow.first()
                    val installedSig = dataStoreManager.installedUpdateSignatureFlow.first()
                    if (appUpdate != null && appUpdate.hasUpdate &&
                        (appUpdate.fileId.isNotBlank() || appUpdate.downloadUrl.isNotBlank()) &&
                        !com.example.util.ApkUpdateInstaller.isAppAlreadyUpToDate(context, appUpdate, installedSig)
                    ) {
                        val updKey = "app_update_${appUpdate.signature}"
                        if (!notified.contains(updKey) && !inMemoryDispatchedKeys.contains(updKey)) {
                            newlyNotifiedKeys.add(updKey)
                            sendAdminUpdateNotification(
                                context = context,
                                title = "🚀 Kingo King Update Available",
                                body = "Naya update aa gaya hai! Tap karke turant app update karein.",
                                dedupKey = updKey,
                                targetScreen = "home"
                            )
                        }
                    }
                }

                // 6. Check Support Chat Messages
                val msgs = dataStoreManager.supportMessagesFlow.first()
                if (isAdminApp) {
                    val newIncoming = msgs.filter { m ->
                        val msgKey = "msg_${m.id}"
                        m.senderRole == "USER" && !notified.contains(msgKey) && !notified.contains(m.id) && !inMemoryDispatchedKeys.contains(msgKey) && !inMemoryDispatchedKeys.contains(m.id)
                    }
                    if (newIncoming.isNotEmpty()) {
                        for (m in newIncoming) {
                            val msgKey = "msg_${m.id}"
                            newlyNotifiedKeys.add(msgKey)
                            newlyNotifiedKeys.add(m.id)
                            inMemoryDispatchedKeys.add(msgKey)
                            inMemoryDispatchedKeys.add(m.id)
                        }
                        val latest = newIncoming.last()
                        sendAdminUpdateNotification(
                            context = context,
                            title = "💬 Support Query from ${latest.userName} (${latest.userId})",
                            body = latest.message,
                            dedupKey = "msg_${latest.id}",
                            targetScreen = "chat",
                            allowOnAdminApp = true
                        )
                    }
                } else {
                    val myEmail = dataStoreManager.currentUserEmailFlow.first()?.trim()?.lowercase() ?: "guest@watchearn.com"
                    val newReplies = msgs.filter { m ->
                        val msgKey = "msg_${m.id}"
                        m.senderRole == "ADMIN" &&
                                m.userEmail.equals(myEmail, ignoreCase = true) &&
                                !notified.contains(msgKey) &&
                                !notified.contains(m.id) &&
                                !inMemoryDispatchedKeys.contains(msgKey) &&
                                !inMemoryDispatchedKeys.contains(m.id)
                    }
                    if (newReplies.isNotEmpty()) {
                        for (m in newReplies) {
                            val msgKey = "msg_${m.id}"
                            newlyNotifiedKeys.add(msgKey)
                            newlyNotifiedKeys.add(m.id)
                            inMemoryDispatchedKeys.add(msgKey)
                            inMemoryDispatchedKeys.add(m.id)
                        }
                        val latest = newReplies.last()
                        sendAdminUpdateNotification(
                            context = context,
                            title = "💬 Support Reply from Kingo Admin",
                            body = latest.message,
                            dedupKey = "msg_${latest.id}",
                            targetScreen = "chat",
                            targetItemId = latest.id
                        )
                    }
                }

                if (newlyNotifiedKeys.isNotEmpty()) {
                    dataStoreManager.markItemsNotified(newlyNotifiedKeys)
                }
            } catch (_: Exception) {}
        }
    }
}

