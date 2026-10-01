package com.seagull.carlauncher;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

/**
 * 一个「应用真进小窗」的镜像槽 —— L3 核心。
 *
 * 移植自用户仓库 caogenfunan123/carlink-desktop 的 mirror/MirrorSlot.kt，
 * 机制核验自 carplay-reverse-engineering 报告 §6/§11 与《画中画触摸行为分析报告》。
 *
 * 与 L1（WindowCard）的本质区别：
 *   L1 = MediaProjection 镜像主屏，只读；
 *   L3 = createVirtualDisplay 建独立屏 → root `am start --display` 把目标应用搬进去
 *        → 画面渲染进我们的 SurfaceView → root `input -d` 把触摸回注进去。
 *        目标应用真的在这块屏上跑，不是镜像。
 *
 * 关键：建屏用【MediaProjection.createVirtualDisplay】而非 DisplayManager 版 ——
 * 后者会检查 ADD_TRUSTED_DISPLAY(signature|role)，前者是普通应用合法拿虚拟屏的正道。
 */
public final class MirrorSlot {

    private static final String TAG = "SeagullMirror";

    private final Context ctx;
    private final String name;

    private MediaProjection projection;
    private VirtualDisplay vd;
    private volatile int displayId = -1;
    private volatile boolean ready;
    private String lastError = "";
    private String lastPkg = "";
    private String lastSig = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 触摸转发交给 TouchForward（跟手/防误滑/长按/排障都在里面）。 */
    private TouchForward touch;

    private TouchForward touchFor() {
        if (touch == null) {
            LauncherModel m = new LauncherModel(ctx);
            touch = new TouchForward(m.touchThreshold, m.longPressMs, m.touchFollow);
        }
        return touch;
    }

    public MirrorSlot(Context ctx, String name) {
        this.ctx = ctx;
        this.name = name;
    }

    public int displayId() { return displayId; }
    public boolean ready() { return ready; }
    public String lastError() { return lastError; }

    /* ---------------- 全局活跃屏注册表（供抽屉"投到车机屏"用） ---------------- */

    private static final java.util.LinkedHashSet<Integer> ACTIVE = new java.util.LinkedHashSet<>();

    private static synchronized void register(int d) { if (d > 0) ACTIVE.add(d); }
    private static synchronized void unregister(int d) { ACTIVE.remove(d); }
    public static synchronized boolean hasMirrorDisplay() { return !ACTIVE.isEmpty(); }
    public static synchronized int newestDisplay() {
        Integer last = null;
        for (Integer i : ACTIVE) last = i;
        return last == null ? -1 : last;
    }
    public static synchronized int activeCount() { return ACTIVE.size(); }

    /* ---------------- 部署 ---------------- */

    /**
     * 部署（幂等）。sig = pkg + surface + 尺寸，没变直接返回 true。
     * 【必须在后台线程调用】：内部会跑 su，阻塞数秒。
     */
    public boolean deploy(MediaProjection mp, String pkg, Surface surface, int w, int h, int dpi) {
        String sig = pkg + "|" + (surface == null ? "nosurface" : System.identityHashCode(surface))
                + "|" + w + "x" + h;
        if (sig.equals(lastSig) && ready) return true;
        lastError = "";

        if (pkg == null || pkg.trim().isEmpty()) { lastError = "未选择目标应用"; return false; }
        if (w <= 0 || h <= 0) { lastError = "锚点尺寸未就绪"; return false; }

        // 已有 VD、只是 surface/尺寸变了 → resize + setSurface，不重建（避免目标重启）
        VirtualDisplay existing = vd;
        if (existing != null && pkg.equals(lastPkg) && displayId > 0 && surface != null) {
            try {
                existing.resize(w, h, dpi);
                existing.setSurface(surface);
                lastSig = sig;
                // 【关键】resize 成功即画面已恢复，ready 必须跟着恢复，
                // 否则：①提示层永久压在真画面上 ②触摸回注判 ready 永远 false → 点击失效
                ready = true;
                Log.i(TAG, "[" + name + "] resized/reattached " + w + "x" + h
                        + " on displayId=" + displayId);
                return true;
            } catch (Throwable t) {
                lastError = "resize 失败: " + t;
                return false;
            }
        }

        teardownVd();
        lastPkg = pkg;
        projection = mp;

        try {
            vd = mp.createVirtualDisplay(
                    "seagull-" + name, w, h, dpi,
                    // PUBLIC：目标 App 的窗口能被这块屏承载并对系统可见
                    // OWN_CONTENT_ONLY：不与主屏镜像耦合，屏上内容由我们启动的应用产生
                    // PRESENTATION：作为展示型输出屏
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                    surface, null, mainHandler);
        } catch (Throwable t) {
            lastError = "createVirtualDisplay 失败: " + t;
            Log.e(TAG, lastError, t);
            return false;
        }
        if (vd == null) { lastError = "createVirtualDisplay 返回 null"; return false; }

        displayId = vd.getDisplay() != null ? vd.getDisplay().getDisplayId() : -1;
        if (displayId < 0) { lastError = "拿不到虚拟屏 id"; teardownVd(); return false; }

        // 建屏成功即注册（触摸/抽屉据此找得到这块屏），不必等 Surface
        register(displayId);

        boolean ok = RootOps.launchOnDisplay(ctx, pkg, displayId);
        if (!ok) { lastError = "am start --display " + displayId + " 失败"; teardownVd(); return false; }

        touchFor().setDisplay(displayId, 1f);   // VD 与 surface 1:1，缩放固定 1
        lastSig = sig;
        // ready 表示"可接收触摸"：建屏+搬应用成功即视为就绪（画面由 attachSurface 负责）
        ready = (surface != null && surface.isValid());
        Log.i(TAG, "[" + name + "] " + pkg + " 已部署到 displayId=" + displayId + " " + w + "x" + h
                + " ready=" + ready);
        return true;
    }

    /**
     * Surface 后到（SurfaceView 回调晚于建屏）：把已有 VD 的输出挂到这块 Surface 上。
     * 产品主路径用它 —— 先建屏 + 搬应用（不依赖 Surface 就绪），Surface 一到就出画面。
     */
    public boolean attachSurface(Surface surface, int w, int h, int dpi) {
        if (vd == null || displayId <= 0) return false;
        if (surface == null || !surface.isValid()) return false;
        try {
            vd.resize(w, h, dpi);
            vd.setSurface(surface);
            ready = true;
            lastSig = lastPkg + "|" + System.identityHashCode(surface) + "|" + w + "x" + h;
            Log.i(TAG, "[" + name + "] surface 已挂载 " + w + "x" + h + " displayId=" + displayId);
            return true;
        } catch (Throwable t) {
            lastError = "attachSurface 失败: " + t;
            Log.w(TAG, lastError, t);
            return false;
        }
    }

    /* ---------------- 触摸回注 ---------------- */

    /** SurfaceView 触摸转发：点/滑/长按都交给 TouchForward（12.1）。 */
    public void onTouch(MotionEvent e) {
        if (displayId <= 0) return;
        touchFor().setDisplay(displayId, 1f);
        touchFor().feed(e);
    }

    /* ---------------- 生命周期 ---------------- */

    /** surface 被销毁：只断输出，保留 VD 与目标任务栈，下次 surface 回来再挂。 */
    public void detachSurface() {
        try { if (vd != null) vd.setSurface(null); } catch (Throwable ignore) {}
        ready = false;
    }

    public void teardownVd() {
        ready = false;
        unregister(displayId);
        try { if (vd != null) vd.release(); } catch (Throwable ignore) {}
        vd = null;
        displayId = -1;
        lastSig = "";
        lastPkg = "";
    }

    public void teardown() {
        teardownVd();
        if (touch != null) {
            touch.shutdown();
            touch = null;
        }
    }

    /** 诊断行：给 UI 显示"虚拟屏到底建没建"。 */
    public String describe() {
        if (displayId <= 0) {
            return "未部署" + (lastError.isEmpty() ? "" : "（" + lastError + "）");
        }
        return (ready ? "已就绪" : "已建屏未挂载") + " · displayId=" + displayId
                + (lastPkg.isEmpty() ? "" : " · " + lastPkg);
    }
}
