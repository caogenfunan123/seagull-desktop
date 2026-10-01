package com.carlink.desktop.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.carlink.desktop.svc.RootAuth

/**
 * 一个"镜像画中画"槽 —— 严格按车联助手挖实的真实机制复刻（逆向报告 §6/§11 +
 * 《画中画触摸行为分析报告》，并对齐反编译 smali b1/c(PipUI) + b1/b(SurfaceHolder.Callback) +
 * b1/d,b1/h(触摸) + c1/h(部署)），取代 m4~m6 错误的 setprop+getBounds 方案。
 *
 * 参考版事实（已核验 smali，不是报告转述）：
 *   - b1/b.surfaceCreated → DisplayManager.createVirtualDisplay("gdj", w, h, dpi, flags=0, surface,…)
 *     —— 它走的是【@SystemApi 版 createVirtualDisplay】，不传 MediaProjection token。
 *     它能这么干是因为签名权限齐全（manifest 里有 CAPTURE_SECURE_VIDEO_OUTPUT /
 *     INJECT_EVENTS / WRITE_SECURE_SETTINGS）+ LSPosed 作用域含 com.android.systemui。
 *   - 我们没有它的签名权限，改用【公开的 MediaProjection.createVirtualDisplay】——
 *     这是普通(含root)App 合法拿虚拟屏的唯一正道，效果一致：目标 App 起进这块
 *     虚拟屏后，画面渲染进我们 SurfaceView 的 Surface，天然浮在桌面区域之上。
 *   - 部署：root `am start --display <id> -f 0x10104000 -n <comp>`（对齐 c1/h）。
 *   - 触摸回注：su -c "input -d <id> tap|swipe"（位移≥10px 判滑动、时长夹 50~2000ms，对齐 b1/d）。
 *   - surface 尺寸变化：VirtualDisplay.resize + setSurface（对齐 b1/b.surfaceChanged）。
 *   - surfaceDestroyed：setSurface(null) 断输出、不销毁任务栈（对齐 b1/b.surfaceDestroyed）。
 *
 * 报告点破、这里按修正版实现的坑：
 *   - ACTION_CANCEL 处理（原版漏了 → 偶发吞点击）；
 *   - 触摸 exec 异步乱序 → 单线程串行队列；
 *   - 重复部署竞态 → (pkg,surface,尺寸) 未变即幂等返回，不重建 VD。
 */
class MirrorSlot(private val ctx: Context, val name: String) {

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    @Volatile var displayId: Int = -1; private set
    @Volatile var ready: Boolean = false; private set
    @Volatile var deploying: Boolean = false
    var lastError: String = ""; private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var touchThread: HandlerThread? = null
    private var touchHandler: Handler? = null

    private var downX = 0f; private var downY = 0f; private var downAt = 0L
    private var lastSig = ""

    /**
     * 部署（幂等）。sig = pkg + surface + 尺寸；没变直接返回 true。
     * 【必须在后台线程调用】：内部 RootAuth.launchOnDisplay 会跑 su，阻塞数秒。
     */
    fun deploy(mp: MediaProjection, pkg: String, surface: Surface, w: Int, h: Int, dpi: Int): Boolean {
        val sig = "$pkg|${System.identityHashCode(surface)}|${w}x$h"
        if (sig == lastSig && ready) return true
        lastError = ""
        if (pkg.isBlank()) { lastError = "未选择目标应用"; return false }
        if (w <= 0 || h <= 0) { lastError = "锚点尺寸未就绪"; return false }
        // 已有 VD、只是 surface/尺寸变了 → 走 resize+setSurface，不重建（避免目标重启）
        val existing = vd
        if (existing != null && pkg == lastPkg && displayId > 0) {
            runCatching { existing.resize(w, h, dpi); existing.setSurface(surface) }
                .onFailure { lastError = "resize 失败: ${it.message}"; return false }
            lastSig = sig
            // 【0.2-m8 真机 bug 修复】这里必须恢复 ready=true。
            // 离开桌面（HOME/切走）→ surfaceDestroyed → detachSurface() 把 ready 置 false，
            // 但 VD 和目标任务栈都保留着（这是刻意的：镜像不断）。回到桌面 surface 重建 →
            // 走这条 resize 快路径 → 以前只 resize 不置 ready，于是：
            //   ① aPipWait/bPipWait 判 slot.ready != true → 半透明提示层永久压在真实画面上
            //      （真机截图里"画中画镜像中…"盖住高德/网易云就是它）；
            //   ② wireMirrorSurface 的触摸回注判 s.ready → 永远 false → 点击彻底失效
            //      （用户反馈"点网易云跳转后返回桌面就完全失效"）。
            // resize+setSurface 成功即画面已恢复，ready 必须跟着恢复。
            ready = true
            Log.i(TAG, "[$name] resized/reattached ${w}x$h on displayId=$displayId")
            return true
        }
        teardownVd()
        lastPkg = pkg
        projection = mp
        vd = runCatching {
            mp.createVirtualDisplay(
                "carlink-$name", w, h, dpi,
                // PUBLIC 让目标 App 的窗口能被这块屏承载并对系统可见（等价参考版 flags=0 在其权限下的效果）
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                surface, null, mainHandler)
        }.getOrElse {
            lastError = "createVirtualDisplay 失败: ${it.message}"; return false
        } ?: run { lastError = "createVirtualDisplay 返回 null"; return false }
        displayId = vd?.display?.displayId ?: -1
        if (displayId < 0) { lastError = "拿不到虚拟屏 id"; teardownVd(); return false }
        val ok = RootAuth.launchOnDisplay(ctx, pkg, displayId)
        if (!ok) { lastError = "root am start --display $displayId 失败"; teardownVd(); return false }
        register(displayId)
        if (touchThread == null) {
            touchThread = HandlerThread("MirrorTouch-$name").also { it.start() }
            touchHandler = Handler(touchThread!!.looper)
        }
        lastSig = sig
        ready = true
        Log.i(TAG, "[$name] $pkg deployed on vd displayId=$displayId ${w}x$h")
        return true
    }

    /** SurfaceView 触摸转发（坐标即 VD 像素——VD 按 surface 尺寸建，1:1，报告"坐标偏移"坑天然不存在）。 */
    fun onTouch(e: MotionEvent) {
        if (!ready) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downAt = e.eventTime }
            MotionEvent.ACTION_UP -> {
                val id = displayId
                val h = touchHandler ?: return
                val cmd = if (kotlin.math.abs(e.x - downX) >= 10 || kotlin.math.abs(e.y - downY) >= 10) {
                    var dur = e.eventTime - downAt
                    if (dur < 50) dur = 50; if (dur > 2000) dur = 2000
                    "input -d $id swipe ${downX.toInt()} ${downY.toInt()} ${e.x.toInt()} ${e.y.toInt()} $dur"
                } else "input -d $id tap ${e.x.toInt()} ${e.y.toInt()}"
                h.post { RootAuth.runQuiet(cmd) }
            }
            MotionEvent.ACTION_CANCEL -> { /* 修正：丢弃本次，不注入也不残留（原版漏了这条） */ }
        }
    }

    /** surface 被销毁：只断输出（setSurface null），保留 VD 与目标任务栈，下次 surface 回来再挂上。 */
    fun detachSurface() {
        runCatching { vd?.setSurface(null) }
        ready = false
    }

    fun teardownVd() {
        ready = false
        unregister(displayId)
        runCatching { vd?.release() }
        vd = null; displayId = -1; lastSig = ""; lastPkg = ""
    }

    fun teardown() {
        teardownVd()
        touchThread?.quitSafely(); touchThread = null; touchHandler = null
    }

    private var lastPkg = ""

    companion object {
        private const val TAG = "MirrorSlot"

        /**
         * 当前【活跃的车机镜像屏】displayId 集合（0.2-m8 第 5 轮复盘新增）。
         *
         * 为什么需要：应用抽屉网格点图标时，CarPlay 风格的正确行为是"在车机屏里打开这个 App"，
         * 而不是像普通手机那样在默认屏（display 0）打开——后者会把 App 铺满整块手机屏、
         * 直接盖住桌面，正是用户说的"苹果互联风格也没做"。抽屉与 MirrorSlot 不在同一组件里，
         * 拿不到 slot1/slot2 的私有 displayId，所以在这里维护一份进程级注册表。
         *
         * 生命周期：deploy 成功（拿到 displayId 且 ready）→ add；teardownVd → remove。
         * 用 LinkedHashSet 保插入序，[newestDisplay] 返回最近部署的一块（通常是用户当前在看的那块）。
         */
        private val active = LinkedHashSet<Int>()

        @Synchronized
        internal fun register(dispId: Int) { if (dispId > 0) active.add(dispId) }

        @Synchronized
        internal fun unregister(dispId: Int) { active.remove(dispId) }

        /** 是否有一块可用的车机镜像屏；抽屉据此决定"投到车机屏"还是"退回默认屏"。 */
        @Synchronized
        fun hasCarDisplay(): Boolean = active.isNotEmpty()

        /** 最近部署的一块车机屏 displayId；无则 -1。 */
        @Synchronized
        fun newestDisplay(): Int = active.lastOrNull() ?: -1
    }
}
