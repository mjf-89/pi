package dev.pi.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** User-started foreground work keeps model networking available while another app is visible. */
class DeviceTaskService : Service() {
    private val automation get() = (application as PiApplication).automation
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { automation.end(true); return START_NOT_STICKY }
        val id = intent?.getStringExtra("session")
        if (id == null || !automation.session.allows(id)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("device-task", "Device tasks", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 20, Intent(this, DeviceTaskService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 21, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "device-task").setSmallIcon(R.drawable.ic_pi)
            .setContentTitle("Pi device task is active").setContentText("Tap to return to Pi. Stop ends screen access.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(20, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(20, notification)
            automation.ready(id)
        } catch (_: Exception) {
            (application as PiApplication).runtime.deviceFailure("Android could not start the device task service. Return to Pi and try again.")
            automation.end(true); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (automation.running) automation.end(true)
        super.onDestroy()
    }
}
