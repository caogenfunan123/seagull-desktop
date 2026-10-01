package com.carlink.desktop.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.carlink.desktop.R
import com.carlink.desktop.apps.AppRepository
import com.google.android.material.card.MaterialCardView
import com.carlink.desktop.media.CarMediaListenerService
import com.carlink.desktop.media.TransportAction
import com.carlink.desktop.mirror.MirrorSlot
import com.carlink.desktop.model.LightDir
import com.carlink.desktop.model.LightState
import com.carlink.desktop.model.NaviInfo
import com.carlink.desktop.model.TurnIcon
import com.carlink.desktop.navi.AmapAutoParser
import com.carlink.desktop.overlay.CarPipTheme
import com.carlink.desktop.settings.CarLinkSettings
import com.carlink.desktop.svc.DesktopService
import com.carlink.desktop.svc.PipProjectionService
import com.carlink.desktop.svc.RootAuth
import com.carlink.desktop.state.CarLinkState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * CarPlay 风格桌面：A / B / C + 侧边栏四区，权重与内容全部来自设置页。
 *
 * 三条实现约束（都是这一版踩到的）：
 *  1) 导航卡被 include 两次（A 区放大 / B 区默认），两份内部 id 同名。
 *     所以取子 View 必须从【所属 include 根】往下找；从 Activity 找只会拿到
 *     文档顺序里的第一份，另一份永远不动。
 *  2) 权重写回集中在 applyLayout()，并做合法性兜底：某区被关时权重清 0 + GONE，
 *     否则留下 0 权重但带 margin 的缝；四区全 0 时强制保留侧边栏，避免白屏。
 *  3) 悬浮层不在本 Activity 里，仍由 DesktopService 持有（Service context 不能用
 *     Material 主题 inflate，见 CarPipTheme 注释）。
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private companion object {
        /** 内容列的基准权重，侧边栏按 0..8 与它比 */
        const val CONTENT_WEIGHT = 10
        /** MediaProjection 授权请求码（对齐车联助手 SlaveActivity 的 requestCode=1，我们用独立值） */
        const val REQ_PIP_CONSENT = 41
    }

    private lateinit var regionA: View
    private lateinit var regionB: View
    private lateinit var regionC: View
    private lateinit var regionSide: View
    private lateinit var bcRow: LinearLayout
    private lateinit var contentCol: LinearLayout
    private lateinit var aPlaceholder: View
    private lateinit var aNaviSlot: View
    private lateinit var aMusicSlot: View
    private lateinit var aHint: TextView
    private lateinit var sideLink: TextView
    private lateinit var sideClock: TextView
    private lateinit var sideDate: TextView
    private lateinit var diagStrip: TextView

    /** 画中画（0.2-m7 镜像方案）：两块 SurfaceView 是槽 1 / 槽 2 的真实画面出口 */
    private lateinit var aSurface: SurfaceView
    private lateinit var bSurface: SurfaceView
    private lateinit var aPipWait: TextView
    private lateinit var bPipWait: TextView
    private lateinit var bNaviSlot: View
    /**
     * 双镜像画中画【同时】生效（复刻车联助手 b1/c+b1/b 的 VirtualDisplay 机制，
     * 取代 m4~m6 的 setprop+getBounds 假方案）：
     * 槽1=pipPackage→A 区 surface，槽2=pipPackage2→B 区 surface；
     * 每槽一块 VirtualDisplay（MediaProjection 授权 token + surface 作输出），
     * 目标 App 用 root `am start --display <虚拟屏>` 起进去，触摸用 `input -d` 回注。
     */
    private var slot1: MirrorSlot? = null
    private var slot2: MirrorSlot? = null
    /** SurfaceHolder 回调写入的 surface 状态（deploy 决策读）；0 = 未就绪 */
    private var surf1: Surface? = null; private var surf1W = 0; private var surf1H = 0
    private var surf2: Surface? = null; private var surf2W = 0; private var surf2H = 0
    /** MediaProjection token：进程内缓存（对齐车联助手 MirrorDisplay 的静态 s），一次授权两个槽共用 */
    private var projection: MediaProjection? = null
    private var requestingPipAuth = false

    /** 上一次主动离开桌面的时间戳（保留：诊断用，未来"离开自动收起接管"也挂这里） */
    private var leavingAtMs = 0L
    /** jump_always=关 时，退出跳转每进程只执行一次 */
    private var jumpedOnce = false

    /** 从 include 根往下找，规避两份同名 id */
    private class NaviViews(root: View) {
        val turn: ImageView = root.findViewById(R.id.navi_turn)
        val dist: TextView = root.findViewById(R.id.navi_dist)
        val road: TextView = root.findViewById(R.id.navi_road)
        val sub: TextView = root.findViewById(R.id.navi_sub)
        val progress: ProgressBar = root.findViewById(R.id.navi_progress)
        val lightRow: View = root.findViewById(R.id.navi_light_row)
        val lightDot: View = root.findViewById(R.id.navi_light_dot)
        val lightText: TextView = root.findViewById(R.id.navi_light_text)
    }

    private class MusicViews(root: View) {
        val cover: ImageView = root.findViewById(R.id.music_cover)
        val title: TextView = root.findViewById(R.id.music_title)
        val artist: TextView = root.findViewById(R.id.music_artist)
        val bar: ProgressBar = root.findViewById(R.id.music_progress)
        val play: ImageView = root.findViewById(R.id.music_play)
    }

    private var naviB: NaviViews? = null
    private var naviA: NaviViews? = null
    private var musicC: MusicViews? = null
    private var musicA: MusicViews? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 【第 2 轮复盘修复】车机桌面语义 = 桌面在前台时屏幕常亮。
        // 此前 manifest 声明了 WAKE_LOCK 但全代码零使用（空壳权限）。正确做法不需要
        // WAKE_LOCK：FLAG_KEEP_SCREEN_ON 挂在窗口上，桌面可见即常亮、离开自动释放。
        // 实际生效点在 applyLayout 里随开关 screen_awake 走（默认开），单一来源。

        regionA = findViewById(R.id.region_a)
        regionB = findViewById(R.id.region_b)
        regionC = findViewById(R.id.region_c)
        regionSide = findViewById(R.id.region_side)
        bcRow = findViewById(R.id.bc_row)
        contentCol = findViewById(R.id.content_col)
        aPlaceholder = findViewById(R.id.a_placeholder)
        aSurface = findViewById(R.id.a_pip_surface)
        bSurface = findViewById(R.id.b_pip_surface)
        aPipWait = findViewById(R.id.a_pip_wait)
        bPipWait = findViewById(R.id.b_pip_wait)
        bNaviSlot = findViewById(R.id.b_navi_slot)
        // surface 生命周期回调：车联助手同款（b1/b SurfaceHolder.Callback）。
        // surfaceChanged 时（尺寸就绪且有效）尝试部署；surfaceDestroyed 时释放该槽 VD，
        // 避免向已销毁的 surface 渲染。坐标天然 1:1（VD 按 surface 尺寸建）。
        wireMirrorSurface(aSurface, slot = 1)
        wireMirrorSurface(bSurface, slot = 2)
        aNaviSlot = findViewById(R.id.a_navi_slot)
        aMusicSlot = findViewById(R.id.a_music_slot)
        aHint = findViewById(R.id.a_hint)
        sideLink = findViewById(R.id.side_link)
        sideClock = findViewById(R.id.side_clock)
        sideDate = findViewById(R.id.side_date)
        diagStrip = findViewById(R.id.diag_strip)

        findViewById<View>(R.id.b_navi_slot)?.let { naviB = NaviViews(it) }
        naviA = NaviViews(aNaviSlot)
        findViewById<View>(R.id.c_music_slot)?.let { musicC = bindMusic(it) }
        findViewById<View>(R.id.a_music_slot)?.let { musicA = bindMusic(it) }

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_side_navi_pip).setOnClickListener {
            DesktopService.send(this, DesktopService.ACTION_TOGGLE_NAVI)
        }
        findViewById<View>(R.id.btn_side_music_pip).setOnClickListener {
            DesktopService.send(this, DesktopService.ACTION_TOGGLE_MUSIC)
        }
        findViewById<View>(R.id.btn_side_drawer).setOnClickListener {
            AppRepository.invalidate()   // 抽屉每次打开都重取，覆盖安装/卸载
            startActivity(Intent(this, DrawerActivity::class.java))
        }
        findViewById<View>(R.id.btn_launch_amap).setOnClickListener { launchTarget() }

        DesktopService.start(this)
        requestNotifPermissionIfNeeded()
        applyLayout(CarLinkSettings.current(this), silentFirst = true)
        CarLinkSettings.addListener(layoutListener)

        lifecycleScope.launch {
            combine(CarLinkState.navi, CarLinkState.music) { n, m -> n to m }
                .collect { (n, m) -> renderNavi(n); renderMusic(m) }
        }
        lifecycleScope.launch { CarLinkState.rawTaps.collect { renderSideLink(it.lastOrNull()) } }

        handler.postDelayed(clockTicker, 0)
        // 音乐进度靠插值往前走，MediaSession 不会自己推 position
        handler.postDelayed(progressTicker, 500)

        // 应用级前后台联动（AppForeground 在 CarLinkApp.onCreate 已 init）：
        // 真·回到前台 → 进入桌面拉起；用户·退出桌面 → 退出跳转。跳设置页属应用内切换，两者都不触发。
        AppForeground.onForeground = { maybeLaunchOnEnter() }
        AppForeground.onUserExit = { maybeJumpOnExit() }
    }

    override fun onResume() {
        super.onResume()
        // singleTask 桌面从设置页返回不会走 onCreate，必须在这里拉一次最新配置。
        // invalidate 触发监听器→applyLayout；再显式 current() 兜底（幂等，防监听器被移除的极端态）。
        // 【第 1 轮复盘修复】silentFirst 必须是 false：silentFirst=true 会跳过
        // renderNavi/renderMusic，导致"改了设置返回桌面"或"从目标 App 切回桌面"时，
        // 导航卡/音乐卡停留在旧值，直到下一次状态推送才刷新（真机上表现为卡片不动）。
        // surface 驱动的镜像重部署不受此影响（它在 updateMirrorSlots 里，两条路径都会跑）。
        CarLinkSettings.invalidate(this)
        applyLayout(CarLinkSettings.current(this), silentFirst = false)
    }

    /**
     * 「进入桌面后拉起的应用」（exp_package，对齐车联助手 Boot 屏）。
     * 判定标准：整个应用从后台回到前台（按 HOME 回来、从别的 App 切回来）。
     * 从设置页返回【不算】——那是应用内切换，AppForeground 会把这种空档过滤掉。
     */    private fun maybeLaunchOnEnter() {
        val pkg = CarLinkSettings.current(this).launchOnEnter
        if (pkg.isBlank() || pkg == packageName) return
        AppForeground.markSelfLaunch()
        runCatching {
            packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { startActivity(it) }
        }
    }

    /**
     * 桌面声明了 configChanges（旋转/分屏不重建），所以方向变化不会走 onCreate/onResume，
     * 必须在这里手动重排：横屏左右分栏、竖屏上下分栏的切换全靠重跑 applyLayout。
     * 镜像槽会自动跟随：surface 尺寸变化 → surfaceChanged → deploy 内 resize+setSurface，
     * 目标 App 在虚拟屏里随新分辨率重布局，不需要任何手动重发。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 等一帧让系统把新尺寸应用到根 View，再量权重和锚点
        window.decorView.post {
            applyLayout(CarLinkSettings.current(this), silentFirst = true)
        }
    }

    override fun onPause() {
        super.onPause()
        leavingAtMs = System.currentTimeMillis()
    }

    /**
     * 「退出时跳转目标」（jump + jump_pkg_lp + jump_always）。
     * 手机上的语义：真正离开本应用（按 HOME / 切到别的 App）时把用户送到指定应用。
     * 由 AppForeground 在确认整个应用进入后台后回调触发（跳设置页不算离开）。
     */
    private fun maybeJumpOnExit() {
        val l = CarLinkSettings.current(this)
        if (!l.jumpOnExit || l.jumpPackage.isBlank()) return
        if (!l.jumpAlways && jumpedOnce) return
        jumpedOnce = true
        runCatching {
            packageManager.getLaunchIntentForPackage(l.jumpPackage)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { startActivity(it) }
        }
    }

    override fun onDestroy() {
        CarLinkSettings.removeListener(layoutListener)
        handler.removeCallbacks(clockTicker)
        handler.removeCallbacks(progressTicker)
        AppForeground.onForeground = null
        AppForeground.onUserExit = null
        // 桌面销毁（被卸载/系统回收）才彻底收镜像；home 离开桌面不走这里，
        // 目标 App 留在虚拟屏里继续运行——正是车联助手"离开桌面镜像不断"的表现。
        slot1?.teardown(); slot2?.teardown(); slot1 = null; slot2 = null
        projection?.stop(); projection = null
        PipProjectionService.stop(this)
        super.onDestroy()
    }

    private val layoutListener: (CarLinkSettings.Layout) -> Unit = { applyLayout(it, false) }

    /** 从一个音乐卡 include 根建视图并挂控制键（A 区放大版与 C 区版共用）。 */
    private fun bindMusic(root: View): MusicViews {
        root.findViewById<View>(R.id.music_play).setOnClickListener {
            CarMediaListenerService.send(TransportAction.PLAY_PAUSE)
        }
        root.findViewById<View>(R.id.music_next).setOnClickListener {
            CarMediaListenerService.send(TransportAction.NEXT)
        }
        root.findViewById<View>(R.id.music_prev).setOnClickListener {
            CarMediaListenerService.send(TransportAction.PREV)
        }
        return MusicViews(root)
    }

    // ---------- 布局 ----------

    private fun applyLayout(l: CarLinkSettings.Layout, silentFirst: Boolean = false) {
        // 强调色先落地：它是 CarPipTheme 这个 object 的运行时状态，
        // 桌面所有取色（灯点/进度条/侧栏）都在本方法之后的 render 里读，顺序不能反
        CarPipTheme.accentColor = l.accent
        val aOn = l.regionA != CarLinkSettings.A_NONE
        val bOn = l.regionB != CarLinkSettings.B_NONE
        val cOn = l.regionC != CarLinkSettings.C_NONE

        val wa = if (aOn) l.weightA.coerceAtLeast(1) else 0
        val wb = if (bOn) l.weightB.coerceAtLeast(1) else 0
        val wc = if (cOn) l.weightC.coerceAtLeast(1) else 0
        var ws = l.weightSidebar.coerceAtLeast(0)
        // 四者全 0 会白屏，这里强制留一条侧边栏而不是让用户对着黑屏猜原因
        if (wa == 0 && wb == 0 && wc == 0 && ws == 0) ws = 1

        val row = wb + wc
        // 横屏=左右分栏（A 占左侧大块，B/C 在右侧上下排），竖屏=上下分栏（A 在上，B/C 在下）。
        // 车机桌面绝大多数时候是横屏，写死竖向会让 A 被压成顶部扁条——画中画目标比例怪异、
        // 甚至被 ROM 以"尺寸不合法"忽略 bounds。这里按当前方向翻转两个容器的 orientation。
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        contentCol.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        bcRow.orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        // 内容列固定 10，侧边栏 0..8 → 侧栏占比 0%~44%，
        // 若两边都按 1:1 起算，滑块 1 就会吃掉半屏（M1 首版实测到的错法）
        // 权重必须落在【父容器主轴】上：父横向→子宽 0dp，父纵向→子高 0dp。
        // contentCol/regionSide 挂在恒为横向的 root 上；regionA/bcRow 挂在 contentCol（方向=landscape）；
        // regionB/regionC 挂在 bcRow（方向与 contentCol 相反=!landscape）。
        weight(contentCol, CONTENT_WEIGHT, parentHorizontal = true)
        weight(regionSide, ws, parentHorizontal = true)
        weight(regionA, wa, parentHorizontal = landscape)
        weight(bcRow, row, parentHorizontal = landscape)
        weight(regionB, wb, parentHorizontal = !landscape)
        weight(regionC, wc, parentHorizontal = !landscape)
        // A 与 bcRow 之间的间距方向随容器方向翻转：横屏在 A 右侧、竖屏在 A 下方
        (regionA.layoutParams as? LinearLayout.LayoutParams)?.let {
            val gap = if (wa > 0 && row > 0) dp(l.cardMarginDp) else 0
            it.bottomMargin = if (landscape) 0 else gap
            it.marginEnd = if (landscape) gap else 0
            regionA.layoutParams = it
        }
        (regionB.layoutParams as? LinearLayout.LayoutParams)?.let {
            // B 与 C 之间：横屏是上下排（间距在 B 下方），竖屏是左右排（间距在 B 右侧）
            val gap = dp(l.cardMarginDp)
            it.marginEnd = if (landscape) 0 else gap
            it.bottomMargin = if (landscape) gap else 0
            regionB.layoutParams = it
        }
        regionA.visibility = if (wa > 0) View.VISIBLE else View.GONE
        regionB.visibility = if (wb > 0) View.VISIBLE else View.GONE
        regionC.visibility = if (wc > 0) View.VISIBLE else View.GONE
        regionSide.visibility = if (ws > 0) View.VISIBLE else View.GONE
        bcRow.visibility = if (row > 0) View.VISIBLE else View.GONE

        // A 区内容：镜像画中画1 / 导航 / 音乐 / 占位。regionA 已在设置层归一化
        // （旧值 A_PIP2=2 读到时按 A_NONE 处理），这里不再特判。
        val wantPip1 = CarLinkSettings.slotForRegionA(l.regionA, l.pipPackage) != null
        val aShowsNavi = l.regionA == CarLinkSettings.A_NAVI
        val aShowsMusic = l.regionA == CarLinkSettings.A_MUSIC
        aNaviSlot.visibility = if (aShowsNavi) View.VISIBLE else View.GONE
        aMusicSlot.visibility = if (aShowsMusic) View.VISIBLE else View.GONE
        // 镜像态：占位与等待层让位给 surface；非镜像态 surface 直接隐藏
        val aMirrorNoPkg = l.regionA == CarLinkSettings.A_MIRROR && !wantPip1
        aPlaceholder.visibility = when {
            aShowsNavi || aShowsMusic -> View.GONE
            wantPip1 -> View.GONE
            else -> View.VISIBLE
        }
        aSurface.visibility = if (wantPip1) View.VISIBLE else View.GONE
        aPipWait.visibility = if (wantPip1 && slot1?.ready != true) View.VISIBLE else View.GONE
        aHint.text = getString(
            when {
                aMirrorNoPkg -> R.string.a_mirror_need_pkg
                wantPip1 -> R.string.a_mirror_active
                else -> R.string.a_mirror_pending_navi
            }
        )

        // B 区内容：镜像画中画2（b_pip_surface）与导航卡（b_navi_slot）二选一。
        // 与 A 区同构，所以槽1、槽2 能同时各自镜像一个 App。
        // "选了镜像但没挑应用"时 b_pip_wait 显示选应用提示（surface 需要包名才能部署）。
        val wantPip2 = CarLinkSettings.slotForRegionB(l.regionB, l.pipPackage2) != null
        val bShowsNavi = l.regionB == CarLinkSettings.B_NAVI
        val bMirrorNoPkg = l.regionB == CarLinkSettings.B_MIRROR && !wantPip2
        bNaviSlot.visibility = if (bShowsNavi) View.VISIBLE else View.GONE
        bSurface.visibility = if (wantPip2) View.VISIBLE else View.GONE
        bPipWait.visibility = if (bMirrorNoPkg || (wantPip2 && slot2?.ready != true))
            View.VISIBLE else View.GONE
        if (bMirrorNoPkg) bPipWait.text = getString(R.string.b_pip_need_pkg)
        else bPipWait.text = getString(R.string.a_mirror_active)

        // 圆角来自设置页，主题的默认值只当初始值用
        val r = l.cornerDp.toFloat() * resources.displayMetrics.density
        (regionA as? MaterialCardView)?.radius = r
        (regionB as? MaterialCardView)?.radius = r
        (regionC as? MaterialCardView)?.radius = r
        (regionSide as? MaterialCardView)?.radius = r

        // 卡片不透明度（0..100）：车联助手是整卡 alpha，不是背景色 alpha，
        // 因为 alpha 作用到 MaterialCardView 上，文字/图标会随卡片一起半透明——这正是它的效果。
        val a = l.cardOpacity.coerceIn(10, 100) / 100f
        regionA.alpha = a; regionB.alpha = a; regionC.alpha = a; regionSide.alpha = a

        applyKeepScreenOn(l.screenAwake)

        diagStrip.visibility = if (l.showDiagnosticsOnDesktop) View.VISIBLE else View.GONE
        if (!silentFirst) {
            renderNavi(CarLinkState.navi.value)
            renderMusic(CarLinkState.music.value)
        }
        // surface 可见性刚被改动 → 下一帧 surfaceChanged 会回调；这里主动对一次
        // 期望态（撤销中的槽要立刻释放 VD，部署中的槽等 surface 就绪后自动触发）
        updateMirrorSlots(l)
    }

    // ---------- 镜像画中画（0.2-m7，复刻车联助手 VirtualDisplay 机制） ----------

    /** SurfaceView → 槽号映射（wireMirrorSurface 时写死，回调里不再判断来源）。 */
    private fun wireMirrorSurface(sv: SurfaceView, slot: Int) {
        sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                onSurfaceReady(slot, h.surface, sv.width, sv.height)
            }
            override fun surfaceChanged(h: android.view.SurfaceHolder, fmt: Int, w: Int, ht: Int) {
                onSurfaceReady(slot, h.surface, w, ht)
            }
            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                // 对齐车联助手 b1/b.surfaceDestroyed：只断输出，不销毁任务栈——
                // 目标 App 留在虚拟屏上，surface 回来（切回镜像）时重新 setSurface 即恢复画面。
                val s = if (slot == 1) slot1 else slot2
                s?.detachSurface()
                if (slot == 1) { surf1 = null } else { surf2 = null }
            }
        })
        // 触摸回注（对齐 b1/d、b1/h 的 OnTouchListener → "su -c input -d <id> …"）
        sv.setOnTouchListener { v, e ->
            val s = if (sv.id == R.id.a_pip_surface) slot1 else slot2
            if (s != null && s.ready) { s.onTouch(e); v.performClick(); true } else false
        }
    }

    private fun onSurfaceReady(slot: Int, surface: Surface, w: Int, h: Int) {
        if (slot == 1) { surf1 = surface; surf1W = w; surf1H = h }
        else { surf2 = surface; surf2W = w; surf2H = h }
        updateMirrorSlots(CarLinkSettings.current(this))
    }

    /**
     * 期望态 → 实际部署的唯一入口（applyLayout / surfaceChanged / 授权返回 都会调）。
     * 幂等规则都在 MirrorSlot.deploy 里（sig 不变不重建）；这里只管三件事：
     *  撤销的槽 → teardown 释放 VD；
     *  要部署且 surface/包名就绪 → 确保有 MediaProjection token（没有就先发起授权，
     *    授权回来后本函数会被再次调用）→ 后台线程 deploy（su 阻塞，绝不占主线程）；
     *  部署结果回主线程刷新等待层/诊断。
     */
    private fun updateMirrorSlots(l: CarLinkSettings.Layout) {
        val want1 = CarLinkSettings.slotForRegionA(l.regionA, l.pipPackage)
        val want2 = CarLinkSettings.slotForRegionB(l.regionB, l.pipPackage2)

        if (want1 == null) { slot1?.teardown(); slot1 = null; aPipWait.visibility = View.GONE }
        if (want2 == null) { slot2?.teardown(); slot2 = null; bPipWait.visibility = View.GONE }
        // 两槽都撤销 → 投屏前台服务停止，token 一并释放（不留一个"正在录屏"的通知）
        if (want1 == null && want2 == null && projection != null) {
            projection?.stop(); projection = null
            PipProjectionService.stop(this)
        }

        val need1 = want1 != null && surf1 != null
        val need2 = want2 != null && surf2 != null
        if (!need1 && !need2) return
        val mp = projection
        if (mp == null) { requestPipAuthIfNeeded(); return }

        if (want1 != null && need1) deploySlot(mp, want1.second, 1)
        if (want2 != null && need2) deploySlot(mp, want2.second, 2)
    }

    private fun deploySlot(mp: MediaProjection, pkg: String, slot: Int) {
        val s = (if (slot == 1) slot1 else slot2)
            ?: MirrorSlot(this, "pip$slot").also { if (slot == 1) slot1 = it else slot2 = it }
        // 只挡"正在部署中"；ready 也放行——MirrorSlot.deploy 自身幂等，
        // surface 尺寸变化时正是靠这次调用走 resize+setSurface（对齐 b1/b.surfaceChanged）
        if (s.deploying) return
        s.deploying = true
        // 【第 3 轮复盘修复 B9】surf1!! 非空断言有竞态：updateMirrorSlots 在主线程判过
        // surf1 != null，但 Thread{}.start 前/刚起时 surfaceDestroyed 可能把它置回 null
        // （离开桌面的瞬间），后台线程一读 !! 就 NPE，槽卡死在 deploying=true。
        // 正确做法：主线程先把值捕获进局部 val（捕获后不再依赖字段），线程内校验。
        val surface = if (slot == 1) surf1 else surf2
        val w = if (slot == 1) surf1W else surf2W
        val h = if (slot == 1) surf1H else surf2H
        if (surface == null || w <= 0 || h <= 0) { s.deploying = false; return }
        val dpi = resources.displayMetrics.densityDpi
        // 只有本来就没就绪的槽才需要"镜像中"等待层；已 ready 的 resize 重部署
        // 再闪一次遮罩就是真机截图里"画面被半透明文字压住"的观感来源之一。
        if (!s.ready) {
            aPipWait.visibility = if (slot == 1) View.VISIBLE else aPipWait.visibility
            bPipWait.visibility = if (slot == 2) View.VISIBLE else bPipWait.visibility
        }
        Thread {
            val ok = s.deploy(mp, pkg, surface, w, h, dpi)
            runOnUiThread {
                s.deploying = false
                val wait = if (slot == 1) aPipWait else bPipWait
                wait.visibility = if (s.ready) View.GONE else View.VISIBLE
                if (!ok) setPipDiag("槽$slot 镜像失败：${s.lastError}")
                else {
                    // 部署成功后目标 App 在虚拟屏里自己会转，等待层收起即露出画面
                    if (CarLinkSettings.current(this).showDiagnosticsOnDesktop)
                        setPipDiag("槽$slot 镜像 $pkg → displayId=${s.displayId} ${w}x$h")
                }
            }
        }.start()
    }

    /**
     * MediaProjection 授权（对齐车联助手 notouch/SlaveActivity 的做法）：
     * createScreenCaptureIntent → 系统录屏授权弹窗 → onActivityResult getMediaProjection。
     * root 环境先把 appops PROJECT_MEDIA 置 allow，多数 Magisk 配置下弹窗自动通过。
     * 同一进程只请求一次；授权成功后 updateMirrorSlots 会重跑部署。
     */
    private fun requestPipAuthIfNeeded() {
        if (requestingPipAuth) return
        requestingPipAuth = true
        Thread {
            RootAuth.allowProjectMedia(packageName)
            runOnUiThread {
                runCatching {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                    @Suppress("DEPRECATION")
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PIP_CONSENT)
                }.onFailure {
                    requestingPipAuth = false
                    setPipDiag("发起录屏授权失败：${it.message}")
                }
            }
        }.start()
    }

    @Deprecated("MediaProjection 授权回调，桌面为 singleTask 不会被重建")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PIP_CONSENT) return
        requestingPipAuth = false
        if (resultCode != Activity.RESULT_OK || data == null) {
            setPipDiag("录屏授权被拒绝 → 画中画无法取屏，请在弹窗里点\"立即开始\"")
            return
        }
        // Android 14 的死顺序（13 及以下无所谓，这里统一走新序）：
        //   授权已拿到 → 启动 mediaProjection 类型 FGS → 等 startForeground 完成
        //   → 才能 getMediaProjection → 才能 createVirtualDisplay。
        // 提前 getMediaProjection 会抛 SecurityException（实测教训，写进注释防回退）。
        pipConsentResult = resultCode to data
        PipProjectionService.start(this)
        awaitProjectionToken(0)
    }

    private var pipConsentResult: Pair<Int, Intent>? = null

    private fun awaitProjectionToken(tries: Int) {
        val (code, data) = pipConsentResult ?: return
        if (!PipProjectionService.running) {
            if (tries > 10) {
                setPipDiag("投屏前台服务未能启动（Android 14 限制），镜像不可用")
                return
            }
            window.decorView.postDelayed({ awaitProjectionToken(tries + 1) }, 200)
            return
        }
        runCatching {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data).also { p ->
                // token 被系统收回（用户在通知栏停止录屏）时清缓存并撤掉镜像
                p?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        runOnUiThread {
                            projection = null
                            slot1?.teardown(); slot1 = null
                            slot2?.teardown(); slot2 = null
                            aPipWait.visibility = View.GONE; bPipWait.visibility = View.GONE
                            PipProjectionService.stop(this@MainActivity)
                            setPipDiag("录屏会话已被系统结束，画中画收起；再次触发接管会重新授权")
                        }
                    }
                }, null)
            }
        }.onFailure { setPipDiag("getMediaProjection 失败：${it.message}") }
        if (projection != null) {
            pipConsentResult = null
            updateMirrorSlots(CarLinkSettings.current(this))
        }
    }

    /** 诊断行：只有开了诊断条才写，避免平时污染界面。 */
    private fun setPipDiag(msg: String) {
        if (!CarLinkSettings.current(this).showDiagnosticsOnDesktop) return
        diagStrip.visibility = View.VISIBLE
        diagStrip.text = msg
    }

    /** 占位按钮兜底：surface 还没就绪/授权未完成时，用户可手动先拉起目标 App。 */
    private fun launchTarget() {
        val l = CarLinkSettings.current(this)
        val pkg = CarLinkSettings.slotForRegionA(l.regionA, l.pipPackage)?.second ?: l.pipPackage
        if (pkg.isBlank()) return
        AppForeground.markSelfLaunch()
        runCatching {
            packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { startActivity(it) }
        }.onFailure {
            diagStrip.visibility = View.VISIBLE
            diagStrip.text = "拉起 $pkg 失败：${it.message}（未安装或包不可见）"
        }
    }

    /**
     * 写权重。父容器方向决定权重落在哪个轴上：
     * 父横向 → 子宽 0dp+weight、高 match_parent；父纵向 → 子高 0dp+weight、宽 match_parent。
     * 横竖屏切换时两个轴都要重设，否则另一轴残留的 0dp 会让子 View 塌成一条线。
     */
    private fun weight(v: View, w: Int, parentHorizontal: Boolean) {
        val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.weight = w.toFloat()
        if (parentHorizontal) {
            lp.width = 0
            lp.height = LinearLayout.LayoutParams.MATCH_PARENT
        } else {
            lp.width = LinearLayout.LayoutParams.MATCH_PARENT
            lp.height = 0
        }
        v.layoutParams = lp
    }

    // ---------- 渲染 ----------

    private fun renderNavi(n: NaviInfo) {
        naviB?.let { if (bNaviSlot.visibility == View.VISIBLE) paintNavi(it, n) }
        naviA?.let { if (aNaviSlot.visibility == View.VISIBLE) paintNavi(it, n) }
        if (diagStrip.visibility == View.VISIBLE) renderDiagStrip(n)
    }

    private fun paintNavi(v: NaviViews, n: NaviInfo) {
        v.turn.setImageDrawable(CarPipTheme.turnArrow(this, n.turnIcon))
        v.turn.rotation = CarPipTheme.turnRotation(n.turnIcon)
        v.dist.text = if (n.isNavigating) n.remainDistText else "未导航"
        v.road.text = when {
            n.isNavigating && n.nextRoadName.isNotBlank() ->
                "${TurnIcon.label(n.turnIcon)} · ${n.nextRoadName}"
            n.curRoadName.isNotBlank() -> n.curRoadName
            n.isNavigating -> TurnIcon.label(n.turnIcon)
            else -> "等待高德车机版广播"
        }
        v.sub.text = if (n.isNavigating) {
            buildString {
                append(n.totalRemainDistText); append(" · ")
                // ETA_TEXT 是车机版已格式化好的串（"18:06到"），有就用，不自己算
                if (n.etaText.isNotBlank()) append(n.etaText) else append(n.remainTimeText)
                if (n.curSpeed > 0) { append(" · "); append(n.curSpeed); append("km/h") }
                if (n.limitSpeed > 0) { append(" · 限速"); append(n.limitSpeed) }
            }
        } else "巡航 / 待机"

        val p = n.progressPercent
        if (p >= 0 && n.isNavigating) {
            v.progress.visibility = View.VISIBLE
            v.progress.progress = p
            // 进度条是非语义装饰，交给用户强调色；灯色保持固定交通色（见 CarPipTheme.lightColor）
            v.progress.progressDrawable?.setTint(CarPipTheme.accentColor)
        } else v.progress.visibility = View.GONE

        if (n.lightState == LightState.NONE) {
            v.lightRow.visibility = View.GONE
        } else {
            v.lightRow.visibility = View.VISIBLE
            v.lightDot.background = CarPipTheme.dotDrawable(CarPipTheme.lightColor(n.lightState))
            val dir = LightDir.label(n.lightDir)
            v.lightText.text = buildString {
                append(LightState.label(n.lightState))
                if (dir.isNotEmpty()) { append(" · "); append(dir) }
                if (n.lightCountdownS > 0) { append(" "); append(n.lightCountdownS); append("s") }
                if (n.cruiseLightCount > 1) { append(" · 共"); append(n.cruiseLightCount); append("组") }
            }
        }
    }

    private fun renderMusic(m: com.carlink.desktop.model.MusicInfo) {
        musicC?.let { if (regionC.visibility == View.VISIBLE) paintMusic(it, m) }
        musicA?.let { if (aMusicSlot.visibility == View.VISIBLE) paintMusic(it, m) }
    }

    private fun paintMusic(v: MusicViews, m: com.carlink.desktop.model.MusicInfo) {
        // "显示专辑封面"关了就收起封面块，卡片变纯文字控制条（对齐车联助手 showalbum）。
        // 只 gate 封面：标题/艺术家/进度/播放键在两种模式下都要照常刷新。
        if (!CarLinkSettings.current(this).showAlbum) {
            v.cover.visibility = View.GONE
        } else {
            v.cover.visibility = View.VISIBLE
            val art = m.artwork
            if (art != null && !art.isRecycled) v.cover.setImageBitmap(art)
            else v.cover.setImageDrawable(CarPipTheme.placeholderArtwork(this))
        }
        v.title.text = m.title.ifBlank { "未在播放" }
        v.artist.text = when {
            m.artist.isNotBlank() -> m.artist
            m.packageName.isNotBlank() -> m.packageName
            else -> "需要「通知使用权」才能读取播放状态"
        }
        v.bar.progress = (m.progressFraction() * 1000).toInt()
        v.play.setImageResource(if (m.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun renderSideLink(last: AmapAutoParser.RawTap?) {
        val st = CarLinkState.navi.value
        sideLink.text = buildString {
            append(if (CarLinkState.amapAutoInstalled.value) "目标已装" else "目标未装")
            append(" · ")
            append(if (last != null) "广播✓ kt=${last.keyType}" else "无广播")
            append("\n")
            append(if (st.isNavigating) "导航中"
            else if (CarMediaListenerService.isConnected) "音乐就绪" else "待机")
        }
    }

    private fun renderDiagStrip(n: NaviInfo) {
        val last = CarLinkState.rawTaps.value.lastOrNull()
        diagStrip.text = "icon=${n.turnIcon} seg=${n.segRemainDistM}m all=${n.routeAllDistM}m " +
            "灯=${LightState.label(n.lightState)} kt=${last?.keyType ?: "-"} " +
            "rawKeys=${last?.entries?.size ?: 0}"
    }

    private val clockTicker = object : Runnable {
        override fun run() {
            val c = Calendar.getInstance()
            sideClock.text = String.format(
                "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            sideDate.text = String.format(
                "%d/%02d/%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
            CarLinkState.refreshAmapAutoPresence()
            handler.postDelayed(this, 20_000)
        }
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            val m = CarLinkState.music.value
            if (m.isActive && m.isPlaying) renderMusic(m)
            handler.postDelayed(this, 500)
        }
    }

    /**
     * API 33+ 前台服务通知需要 POST_NOTIFICATIONS 运行时授权。
     * 未授予时服务仍能运行（只是通知不可见），所以只请求一次、不阻塞启动。
     */
    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 桌面常亮（第 2 轮复盘）。FLAG_KEEP_SCREEN_ON 是【窗口级】标志：
     * 加在 window 上，只要桌面窗口可见，屏幕就不灭；桌面被其它 App 覆盖或退出，
     * 窗口不再可见，系统自动停止续亮 —— 不需要 WAKE_LOCK 权限，也不会像
     * 服务长期持锁那样在后台费电。开关关掉时清标志，交回系统超时策略。
     */
    private fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
