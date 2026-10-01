package com.carlink.desktop.svc

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.carlink.desktop.CarLinkApp
import com.carlink.desktop.R
import com.carlink.desktop.model.MusicInfo
import com.carlink.desktop.model.NaviInfo
import com.carlink.desktop.model.TurnIcon
import com.carlink.desktop.navi.AmapNaviReceiver
import com.carlink.desktop.overlay.CarPipTheme
import com.carlink.desktop.overlay.MusicPipWindow
import com.carlink.desktop.overlay.NaviPipWindow
import com.carlink.desktop.settings.CarLinkSettings
import com.carlink.desktop.state.CarLinkState
import com.carlink.desktop.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 常驻前台服务，承载三件事：
 *  1. 高德车机版广播接收器（必须动态注册，见 AmapNaviReceiver）
 *  2. 两个画中画悬浮窗（导航 / 音乐）
 *  3. 与桌面 UI 共享的状态订阅
 *
 * 悬浮窗只在"导航中"或"播放中"时存在，空闲即销毁 ——
 * 永久挂着的悬浮层会遮挡高德自己的 UI，那是不可用的产品。
 */
class DesktopService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var receiver: AmapNaviReceiver? = null
    private var naviPip: NaviPipWindow? = null
    private var musicPip: MusicPipWindow? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTI_ID, buildNotification(getString(R.string.notif_idle)))
        receiver = AmapNaviReceiver.register(this)
        CarLinkState.refreshAmapAutoPresence()
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        handleCommand(intent)
        // START_STICKY 挡不住国产 ROM 的"清后台"，所以另外挂了 BOOT_COMPLETED 自启
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 转屏后原 x/y 可能落在屏幕外，直接重建窗口
        handler.post { rebuildOverlays() }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        AmapNaviReceiver.unregister(this, receiver)
        receiver = null
        naviPip?.hide(); musicPip?.hide()
        naviPip = null; musicPip = null
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        scope.launch {
            combine(CarLinkState.navi, CarLinkState.music) { n, m -> n to m }
                .distinctUntilChanged()
                .collect { (n, m) -> applyState(n, m) }
        }
    }

    private fun applyState(n: NaviInfo, m: MusicInfo) {
        refreshNotificationText(n, m)

        if (!CarLinkState.canDrawOverlay(this)) return

        // 两个窗口独立处理：导航窗口创建失败不该连带影响音乐窗口
        if (n.isNavigating) ensureNavi()?.update(n)
        else { naviPip?.hide(); naviPip = null }

        if (m.isActive) ensureMusic()?.update(m)
        else { musicPip?.hide(); musicPip = null }
    }

    private fun ensureNavi(): NaviPipWindow? {
        naviPip?.let { if (it.isShowing) return it }
        return try {
            val minimal = CarLinkSettings.current(this).minimalStyle
            NaviPipWindow(this).also { it.show(startCollapsed = minimal); naviPip = it }
        } catch (t: Throwable) {
            Log.w(TAG, "navi overlay failed: ${t.message}"); naviPip = null; null
        }
    }

    private fun ensureMusic(): MusicPipWindow? {
        musicPip?.let { if (it.isShowing) return it }
        return try {
            val minimal = CarLinkSettings.current(this).minimalStyle
            MusicPipWindow(this).also { it.show(startCollapsed = minimal); musicPip = it }
        } catch (t: Throwable) {
            Log.w(TAG, "music overlay failed: ${t.message}"); musicPip = null; null
        }
    }

    private fun rebuildOverlays() {
        val hadNavi = naviPip?.isShowing == true
        val hadMusic = musicPip?.isShowing == true
        naviPip?.hide(); musicPip?.hide()
        naviPip = null; musicPip = null
        if (hadNavi) ensureNavi()?.update(CarLinkState.navi.value)
        if (hadMusic) ensureMusic()?.update(CarLinkState.music.value)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CarLinkApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 前台服务通知同时充当"链路指示灯"：
     * 用户不用连 adb，下拉通知栏就能看出数据到底通没通。
     */
    private fun refreshNotificationText(n: NaviInfo, m: MusicInfo) {
        if (n.isNavigating) {
            val road = n.curRoadName.ifBlank { TurnIcon.label(n.turnIcon) }
            updateNotification("${n.remainDistText} 后 $road · 剩余 ${n.totalRemainDistText}")
        } else if (m.isActive) {
            updateNotification("${m.title} - ${m.artist}")
        } else {
            updateNotification(getString(R.string.notif_idle))
        }
    }

    // ---------- 供桌面 UI 直接调用（同进程，不需要 bindService） ----------

    fun toggleNaviPip() {
        if (naviPip?.isShowing == true) { naviPip?.hide(); naviPip = null }
        else ensureNavi()?.update(CarLinkState.navi.value)
    }

    fun toggleMusicPip() {
        if (musicPip?.isShowing == true) { musicPip?.hide(); musicPip = null }
        else ensureMusic()?.update(CarLinkState.music.value)
    }

    fun naviPipVisible(): Boolean = naviPip?.isShowing == true
    fun musicPipVisible(): Boolean = musicPip?.isShowing == true

    private fun handleCommand(intent: Intent?) {
        when (intent?.action) {
            ACTION_TOGGLE_NAVI -> toggleNaviPip()
            ACTION_TOGGLE_MUSIC -> toggleMusicPip()
        }
    }

    companion object {
        private const val TAG = "DesktopService"
        private const val NOTI_ID = 4711

        const val ACTION_TOGGLE_NAVI = "com.carlink.desktop.TOGGLE_NAVI"
        const val ACTION_TOGGLE_MUSIC = "com.carlink.desktop.TOGGLE_MUSIC"

        @Volatile
        var instance: DesktopService? = null
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, DesktopService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** 带命令启动：桌面 UI 的开关按钮走这条路，避免直接持有服务实例 */
        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, DesktopService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun updateNotification(text: String) {
            val s = instance ?: return
            s.getSystemService(NotificationManager::class.java)
                ?.notify(NOTI_ID, s.buildNotification(text))
        }
    }
}
