package com.carlink.desktop.svc

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.carlink.desktop.CarLinkApp
import com.carlink.desktop.R

/**
 * 镜像画中画的投屏前台服务（0.2-m7）。
 *
 * Android 14 的硬规定：MediaProjection.createVirtualDisplay 之前，应用必须已经
 * 有一个 foregroundServiceType=mediaProjection 的前台服务在跑，且该服务只能在
 * 用户授权【之后】启动（授权前启动会抛 SecurityException）。
 * 所以它和常驻的 DesktopService 分开：MainActivity 拿到录屏 token 后 start 这里，
 * 两个镜像槽都撤销后 stop。通知挂在服务同款低优先频道上，不打扰驾驶。
 */
class PipProjectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startedHere) return START_NOT_STICKY
        startedHere = true
        val n: Notification = NotificationCompat.Builder(this, CarLinkApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_pip_running))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        // type 参数只在 29+ 有意义；与 manifest 声明一致
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTI_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTI_ID, n)
        running = true   // MainActivity 轮询这个标志，true 后才允许 createVirtualDisplay
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        startedHere = false
        running = false
        super.onDestroy()
    }

    companion object {
        private const val NOTI_ID = 4712
        @Volatile private var startedHere = false
        /** startForeground(mediaProjection) 已完成的标志（Android 14 上取 token 的前置） */
        @Volatile var running = false; private set

        fun start(ctx: Context) {
            val i = Intent(ctx, PipProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PipProjectionService::class.java))
        }
    }
}
