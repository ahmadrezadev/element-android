/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn.openvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tim.basevpn.singleProcess.ProtocolsVpnService
import com.tim.notification.DefaultVpnServiceNotification
import com.tim.notification.VpnServiceNotification
import im.vector.app.R
import im.vector.app.features.vpn.formatVpnSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ElementVpnServiceNotification(
        private val service: Service,
        private val notificationManager: NotificationManager,
) : VpnServiceNotification {

    private val appContext = service.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var speedUpdateJob: Job? = null
    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    @Volatile
    private var downloadBytesPerSecond: Long = 0L
    @Volatile
    private var uploadBytesPerSecond: Long = 0L

    override fun withTimer(): Boolean = false

    override fun start() {
        resetSpeedCounters()
        ServiceCompat.startForeground(
                service,
                NOTIFICATION_ID,
                createNotification(description = ""),
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        startSpeedUpdates()
    }

    override fun stop() {
        speedUpdateJob?.cancel()
        speedUpdateJob = null
        notificationManager.cancel(NOTIFICATION_ID)
    }

    override fun createNotification(description: String): Notification {
        ensureNotificationChannel()
        val speedSummary = appContext.getString(
                R.string.vpn_status_speed_value,
                formatVpnSpeed(downloadBytesPerSecond),
                formatVpnSpeed(uploadBytesPerSecond)
        )
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(appContext.getString(R.string.vpn_settings_title))
                .setContentText(speedSummary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(speedSummary))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                        R.drawable.ic_settings_x,
                        appContext.getString(R.string.vpn_status_action_stop),
                        createStopPendingIntent()
                )

        createLaunchPendingIntent()?.let {
            builder.setContentIntent(it)
        }

        return builder.build()
    }

    override fun updateNotification(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startSpeedUpdates() {
        speedUpdateJob?.cancel()
        speedUpdateJob = scope.launch {
            while (isActive) {
                delay(SPEED_UPDATE_INTERVAL_MS)
                updateSpeed()
                updateNotification(createNotification(description = ""))
            }
        }
    }

    private fun resetSpeedCounters() {
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        downloadBytesPerSecond = 0L
        uploadBytesPerSecond = 0L
    }

    private fun updateSpeed() {
        val currentRxBytes = TrafficStats.getTotalRxBytes()
        val currentTxBytes = TrafficStats.getTotalTxBytes()
        downloadBytesPerSecond = calculateBytesPerSecond(lastRxBytes, currentRxBytes)
        uploadBytesPerSecond = calculateBytesPerSecond(lastTxBytes, currentTxBytes)
        lastRxBytes = currentRxBytes
        lastTxBytes = currentTxBytes
    }

    private fun calculateBytesPerSecond(previous: Long, current: Long): Long {
        if (previous < 0L || current < 0L || current < previous) return 0L
        return current - previous
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.vpn_settings_title),
                NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createLaunchPendingIntent(): PendingIntent? {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName) ?: return null
        return PendingIntent.getActivity(
                appContext,
                REQUEST_CODE_OPEN_APP,
                launchIntent,
                PENDING_INTENT_FLAGS
        )
    }

    private fun createStopPendingIntent(): PendingIntent {
        val stopIntent = Intent(appContext, ElementOpenVpnService::class.java).apply {
            setPackage(appContext.packageName)
            putExtra(ProtocolsVpnService.ACTION_KEY, ProtocolsVpnService.ACTION_STOP_KEY)
        }
        return PendingIntent.getService(
                appContext,
                REQUEST_CODE_STOP_VPN,
                stopIntent,
                PENDING_INTENT_FLAGS
        )
    }

    private companion object {
        private const val NOTIFICATION_ID = DefaultVpnServiceNotification.NOTIFICATION_ID
        private const val CHANNEL_ID = DefaultVpnServiceNotification.CHANNEL_ID
        private const val FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 0x40000000
        private const val SPEED_UPDATE_INTERVAL_MS = 1_000L
        private const val REQUEST_CODE_OPEN_APP = 1001
        private const val REQUEST_CODE_STOP_VPN = 1002
        private const val PENDING_INTENT_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
