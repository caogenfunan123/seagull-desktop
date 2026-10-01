package com.carlink.desktop.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.carlink.desktop.model.MusicInfo
import com.carlink.desktop.navi.AmapNotificationParser
import com.carlink.desktop.overlay.NotifBannerWindow
import com.carlink.desktop.settings.CarLinkSettings
import com.carlink.desktop.state.CarLinkState

/**
 * 音乐侧唯一数据源：NotificationListenerService + MediaSessionManager。
 *
 * 四条实现约束（都是容易踩的坑）：
 *  1) 不存在 ACCESS_MEDIA_SESSION 权限。getActiveSessions() 要求用户手动授予
 *     「通知使用权」，未授权时抛 SecurityException（部分 ROM 直接返回空数组）。
 *  2) getActiveSessions() 返回 List<MediaController>，是调用瞬间的快照，不会持续推送。
 *     播放中的更新必须靠 MediaController.registerCallback，或退化为轮询。
 *  3) PlaybackState.position 是快照值，不会自己前进。进度显示按
 *     (now - updatedAt) 插值，见 MusicInfo.livePositionMs()。
 *  4) 本服务由系统绑定，进程被杀后系统自动重绑，不需要额外保活。
 */
class CarMediaListenerService : NotificationListenerService() {

    private var smm: MediaSessionManager? = null
    private var active: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    /** 通知转发横幅（懒建，随服务销毁一起消失） */
    private var banner: NotifBannerWindow? = null
    /** 最近一次兼容模式用通知兜底发布的时间戳，防止轮询立刻把它清空 */
    @Volatile private var lastCompatAt = 0L

    /** 回调是主通道；轮询只为让进度条平滑走（有些 App 不按固定频率推 position） */
    private val pollMs = 1_000L
    private val poller = object : Runnable {
        override fun run() {
            if (active != null) publish() else rescan()
            handler.postDelayed(this, pollMs)
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            dropController()
            rescan()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        smm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        rescan()
        handler.postDelayed(poller, pollMs)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        handler.removeCallbacks(poller)
        dropController()
        CarLinkState.clearMusic()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        if (instance === this) instance = null
        dropController()
        banner?.hide(); banner = null
        super.onDestroy()
    }

    /**
     * 两条链路共用这一个回调：
     *  1) 媒体：新通知带 media session token 说明有 App 开始接管媒体，立刻重扫。
     *  2) 通知转发（对齐车联助手"通知助手"）：notification_switch 开、包名过白名单，
     *     且【不是媒体控制类通知】时，在桌面顶部弹一条横幅。排除媒体通知是因为
     *     播放器本来就有自己的常驻通知，再转发一遍只会刷屏。
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val isMedia = try {
            sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        } catch (_: Throwable) { false }
        if (isMedia) {
            rescan()
            // 媒体兼容模式：有些国产播放器只发一条媒体通知、不暴露可控制的
            // MediaSession（active 仍为空）。这时直接从通知的标题/副标题兜一份
            // 只读信息出来，至少桌面能显示"在放什么"，比空着强。
            if (active == null) compatPublish(sbn)
            return
        }
        // 导航链路（第 4 轮复盘）：手机版高德不发车机版广播，只在通知栏挂诱导通知。
        // 命中高德包名时先尝试解析诱导文案更新导航卡片，成功就不再把这条通知当普通横幅转发
        // （否则导航过程会被自己的文案刷屏）。参照 com.leting.carplay.AmapNaviHook。
        if (AmapNotificationParser.isAmap(sbn.packageName)) {
            val parsed = AmapNotificationParser.fromNotification(
                sbn.notification, CarLinkState.navi.value
            )
            if (parsed != null) {
                CarLinkState.onNavi(parsed)
                return
            }
        }
        forwardAsBanner(sbn)
    }

    /** 仅在 musicCompat 打开且没有可用 session 时，用通知字段填一份只读 MusicInfo。 */
    private fun compatPublish(sbn: StatusBarNotification) {
        val l = try { CarLinkSettings.current(applicationContext) } catch (_: Throwable) { return }
        if (!l.musicCompat) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        if (title.isBlank()) return
        val artist = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        // 通知大图标取法各 ROM 差异大（Bitmap vs 资源 id），稳妥起见兼容模式只补文字，不取封面。
        lastCompatAt = System.currentTimeMillis()
        CarLinkState.onMusic(MusicInfo(
            title = title, artist = artist, artwork = null,
            isPlaying = true, packageName = sbn.packageName ?: "",
            updatedAt = lastCompatAt,
        ))
    }

    private fun forwardAsBanner(sbn: StatusBarNotification) {
        val l = try { CarLinkSettings.current(applicationContext) } catch (_: Throwable) { return }
        if (!l.notificationEnabled) return
        if (!CarLinkSettings.notifWhitelisted(l, sbn.packageName ?: "")) return
        val ex = sbn.notification?.extras
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val stayMs = l.notificationSeconds.coerceIn(1, 60) * 1_000L
        handler.post {
            if (!CarLinkState.canDrawOverlay(this)) return@post
            try {
                if (banner == null) banner = NotifBannerWindow(applicationContext)
                banner?.show(title, text, stayMs, l.notificationOpacity)
            } catch (_: Throwable) { }   // 悬浮窗授权竞态：静默跳过这条
        }
    }

    private fun rescan() {
        val mgr = smm ?: return
        val sessions = try {
            mgr.getActiveSessions(ComponentName(this, CarMediaListenerService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "getActiveSessions denied (通知使用权未授予?): ${t.message}")
            emptyList()
        }

        // 用户在设置页指定的音乐包名（锁定播放器优先）：非空时只接受该包的 session，
        // 避免多个播放器抢状态。服务不是 Activity，拿不到 Activity context，
        // 必须用 applicationContext 读设置。
        val targetPkg = try {
            val ls = com.carlink.desktop.settings.CarLinkSettings.current(applicationContext)
            com.carlink.desktop.settings.CarLinkSettings.effectiveMusicPkg(ls)
        } catch (_: Throwable) { "" }

        var chosen: MediaController? = null
        for (s in sessions) {
            val pkg = s.packageName ?: ""
            // 指定了目标包名时，跳过所有不匹配的 session
            if (targetPkg.isNotBlank() && pkg != targetPkg) continue

            val st = try { s.playbackState?.state } catch (_: Throwable) { null }
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                chosen = s; break
            }
            if (chosen == null && try {
                    s.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null
                } catch (_: Throwable) { false }
            ) chosen = s
        }

        if (chosen == null) {
            dropController()
            // 指定了目标包名但它当前不在 sessions 里（App 未启动/未播放）时，
            // 不要 clearMusic() 把界面清空成"未在播放"——那会让用户以为链路断了，
            // 实际上只是目标 App 暂时没播放。改为发布一个 packageName=targetPkg 的空 MusicInfo，
            // 桌面卡片会显示"未在播放"+目标包名，比通用的"需要通知使用权"更不容易让人误解。
            // 未指定目标包名时保持原有"任意来源"行为：找不到就清空。
            if (targetPkg.isNotBlank()) {
                CarLinkState.onMusic(com.carlink.desktop.model.MusicInfo(
                    title = "",
                    packageName = targetPkg,
                    updatedAt = System.currentTimeMillis(),
                ))
                Log.d(TAG, "目标音乐 App $targetPkg 当前无播放会话，保留占位状态")
            } else if (System.currentTimeMillis() - lastCompatAt > 6_000) {
                // 兼容模式刚用通知兜过底，6 秒内不清空（通知不像 session 会持续推 position）
                CarLinkState.clearMusic()
            }
            return
        }
        // 【第 1 轮复盘修复】getActiveSessions() 每次调用都返回【新的 MediaController 实例】，
        // 即使底层是同一个会话。用引用比较（active !== chosen）会在每次 rescan 都
        // 判定"会话变了"→ unregister + register 抖动，并把 album-art bitmap 反复重发布。
        // 正确做法是比较 sessionToken（开源车机桌面 openlauncher 的 MediaListenerService
        // 里作者踩过同一个坑并留了同样的注释，这里对齐其做法）。
        if (active?.sessionToken != chosen.sessionToken) {
            dropController()
            active = chosen
            try { chosen.registerCallback(callback, handler) } catch (_: Throwable) {}
        }
        publish()
    }

    private fun publish() {
        val c = active ?: return
        try {
            val md = c.metadata
            val ps = c.playbackState
            val acts = ps?.actions ?: 0L
            val info = MusicInfo(
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
                album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                artwork = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                isPlaying = ps?.state == PlaybackState.STATE_PLAYING ||
                            ps?.state == PlaybackState.STATE_BUFFERING,
                positionMs = ps?.position ?: 0L,
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                canSkipNext = acts and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                canSkipPrev = acts and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                packageName = c.packageName ?: "",
                updatedAt = System.currentTimeMillis(),
            )
            if (info.isActive) CarLinkState.onMusic(info)
        } catch (t: Throwable) {
            Log.w(TAG, "publish failed: ${t.message}")
        }
    }

    private fun dropController() {
        try { active?.unregisterCallback(callback) } catch (_: Throwable) {}
        active = null
    }

    /** 桌面/悬浮层的控制动作。音乐 App 可能已经退出，所以全部吞异常 */
    fun transport(action: TransportAction) {
        val c = active ?: run { rescan(); return }
        try {
            // MediaController 上没有 play()/pause() 便捷方法，那是某些文档里的假 API。
            // 控制指令只能走 getTransportControls()。
            val tc = c.transportControls
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            when (action) {
                TransportAction.PLAY_PAUSE -> if (playing) tc.pause() else tc.play()
                TransportAction.NEXT -> tc.skipToNext()
                TransportAction.PREV -> tc.skipToPrevious()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "transport failed: ${t.message}")
            dropController()
        }
    }

    companion object {
        private const val TAG = "CarMedia"

        /** 服务由系统创建，外部拿不到实例，用静态注册表桥接控制指令 */
        @Volatile
        var instance: CarMediaListenerService? = null
            private set

        val isConnected: Boolean get() = instance != null

        fun send(action: TransportAction) { instance?.transport(action) }
    }
}

enum class TransportAction { PLAY_PAUSE, NEXT, PREV }
