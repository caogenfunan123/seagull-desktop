package com.seagull.carlauncher;

import android.content.Context;
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
 * 移植自用户仓库 caogenfunan123/carlink-desktop 的 mirror/MirrorSlot.kt
 * （m13 版起含 TRUSTED 屏），机制核验自 carplay-reverse-engineering 报告
 * §6/§11、《画中画触摸行为分析报告》与 OneStep4 对标结论。
 *
 * 与 L1（WindowCard）的本质区别：
 *   L1 = MediaProjection 镜像主屏，只读；
 *   L3 = createVirtualDisplay 建独立屏 → root 把目标应用搬进去
 *        → 画面渲染进我们的 SurfaceView → 触摸回注进去。
 *        目标应用真的在这块屏上跑，不是镜像。
 *
 * 建屏两条路（批次 K 起，TRUSTED 优先）：
 *   · TRUSTED 直建：root 授角色后用 DisplayManager 公开 6 参 createVirtualDisplay
 *     + flag 候选降级 + TRUSTED 位校验 —— Android 14 上任务不被拉回主屏的关键；
 *   · MediaProjection 兜底：普通应用合法拿虚拟屏的正道，无 TRUSTED，
 *     Android 14 上 singleTask 目标可能被拉回主屏（有 ensureOnDisplay 自愈兜底）。
 */
public final class MirrorSlot implements CanvasSource {

    private static final String TAG = "SeagullMirror";

    private final Context ctx;
    private final String name;

    private MediaProjection projection;
    private VirtualDisplay vd;
    private volatile int displayId = -1;
    private volatile boolean ready;
    /** 批次 K：true = 本次 VD 是 TRUSTED 受信屏（任务才不会被系统拉回主屏）。 */
    private volatile boolean trusted;
    /** 批次 K：true = TRUSTED 直建失败且缺录屏 token，需要先走录屏授权再兜底。 */
    private volatile boolean needsProjection;
    private String lastError = "";
    private String lastPkg = "";
    private String lastSig = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 触摸转发交给 TouchForward（跟手/防误滑/长按/排障都在里面）。 */
    private TouchForward touch;

    /**
     * 懒初始化必须双检加锁：部署后台线程（deploy）与触摸主线程（onTouch）两条
     * 路径都会首触，无锁时两线程各 new 一个 TouchForward → 各起一个
     * HandlerThread，被覆盖的那个永远 shutdown 不到（线程泄漏）。
     */
    private TouchForward touchFor() {
        if (touch == null) {
            synchronized (this) {
                if (touch == null) {
                    LauncherModel m = new LauncherModel(ctx, false);
                    touch = new TouchForward(m.touchThreshold, m.longPressMs, m.touchFollow);
                }
            }
        }
        return touch;
    }

    public MirrorSlot(Context ctx, String name) {
        // 进程级常驻（MirrorHost 持有）：绝不能攥着 Activity 不放，存 app context
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.name = name;
        // 给 PrivClient 一个上下文：守护进程靠 base.apk 路径拉起，早给早生效
        PrivClient.init(this.ctx);
    }

    /** 批次 N：触摸坐标映射。VD 模式恒等（1:1，scale 恒 1）；推流模式由外部换实现。 */
    private volatile TouchTransformer transformer = TouchTransformer.identity();

    /** 换坐标映射（DiPlay 推流模式预留）。切回 VD 传 identity()。 */
    public void setTransformer(TouchTransformer t) {
        transformer = (t != null) ? t : TouchTransformer.identity();
        Log.i(TAG, "[" + name + "] 触摸映射 → " + transformer.describe());
    }

    public int displayId() { return displayId; }
    public boolean ready() { return ready; }
    public boolean trusted() { return trusted; }
    public boolean needsProjection() { return needsProjection; }
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
     *
     * 建屏策略（批次 K，对标 carlink-desktop m13 / OneStep4）：
     *   ① TRUSTED 直建：root 授 COMPANION_DEVICE_APP_STREAMING 角色 → 本进程
     *      DisplayManager.createVirtualDisplay(name,w,h,dpi,surface,flags)，5 组
     *      flag 候选逐个降级 + Display flags 校验 TRUSTED 位（1<<7）；
     *   ② TRUSTED 不可用（角色授予失败 / ROM 拒绝）→ MediaProjection 兜底，
     *      flags = PUBLIC|OWN_CONTENT_ONLY|PRESENTATION（参考实现实测组合）。
     * 为什么 TRUSTED 是关键：Android 14 只允许 home-affinity/TASK_ON_HOME 任务
     * （singleTask 应用）落在受信屏；非受信屏上即使注入了 launchDisplayId，
     * 任务也会被系统拉回默认屏 —— 用户实测"进去桌面还不是画中画界面"的根因。
     *
     * 尺寸口径：VD 严格按 SurfaceView 的实际 w/h/dpi 建（1:1）——
     * 触摸坐标零换算、应用窗口按画布尺寸布局（"画中画跟随画布大小"）。
     * 批次 J 的 PUBLIC|AUTO_MIRROR 已废弃：任务不在屏上时 AUTO_MIRROR 会把
     * 手机桌面镜像进画中画，正是"画中画里看到的是桌面而不是应用"的来源。
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
        needsProjection = false;
        trusted = false;

        VirtualDisplay made = createTrustedVd(w, h, dpi, surface);
        if (made != null) {
            vd = made;
            trusted = true;
            projection = null;   // TRUSTED 屏不靠 MediaProjection
            Log.i(TAG, "[" + name + "] TRUSTED 虚拟屏建成 " + w + "x" + h);
        } else {
            // 兜底：MediaProjection 路径（需要录屏授权 token）
            if (mp == null) {
                needsProjection = true;
                lastError = "TRUSTED 屏不可用且无录屏授权（兜底路径缺 token）";
                return false;
            }
            Log.w(TAG, "[" + name + "] TRUSTED 直建失败，回落 MediaProjection 路径");
            try {
                vd = mp.createVirtualDisplay(
                        "seagull-" + name, w, h, dpi,
                        TrustedFlags.projectionFallbackFlags(),
                        surface, null, mainHandler);
            } catch (Throwable t) {
                lastError = "createVirtualDisplay 失败: " + t;
                Log.e(TAG, lastError, t);
                return false;
            }
            if (vd == null) { lastError = "createVirtualDisplay 返回 null"; return false; }
        }

        displayId = vd.getDisplay() != null ? vd.getDisplay().getDisplayId() : -1;
        if (displayId < 0) { lastError = "拿不到虚拟屏 id"; teardownVd(); return false; }

        // 建屏成功即注册（触摸/抽屉据此找得到这块屏），不必等 Surface
        register(displayId);

        boolean ok = RootOps.launchOnDisplay(ctx, pkg, displayId);
        if (!ok) { lastError = "am start --display " + displayId + " 失败"; teardownVd(); return false; }

        // 部署即自愈一次（批次 L）：singleTask 目标落地瞬间就可能被系统拉回默认屏，
        // 不等用户切页面。保守：am stack list 解析不出就不动（见 ensureOnDisplay）。
        try {
            String heal = RootOps.ensureOnDisplay(ctx, pkg, displayId);
            Log.i(TAG, "[" + name + "] 部署后自愈: " + heal);
        } catch (Throwable t) {
            Log.w(TAG, "[" + name + "] 部署后自愈异常: " + t);
        }

        touchFor().setDisplay(displayId, transformer.scaleX());   // 批次 N：VD 恒等映射，scale 恒 1
        lastSig = sig;
        // ready 表示"可接收触摸"：建屏+搬应用成功即视为就绪（画面由 attachSurface 负责）
        ready = (surface == null) || surface.isValid();
        Log.i(TAG, "[" + name + "] " + pkg + " 已部署到 displayId=" + displayId + " " + w + "x" + h
                + " trusted=" + trusted + " ready=" + ready);
        return true;
    }

    /**
     * TRUSTED 虚拟屏直建（API 33+；角色授予失败或 ROM 拒绝时返回 null，
     * 调用方回落 MediaProjection）。flag 候选逐个降级，建后校验 Display flags
     * 是否真带 TRUSTED 位（1<<7）—— 不校验等于没建，有些 ROM 会静默吞掉 flag。
     */
    private VirtualDisplay createTrustedVd(int w, int h, int dpi, Surface surface) {
        if (android.os.Build.VERSION.SDK_INT < 33) return null;
        if (!RootOps.grantTrustedDisplayRole(ctx)) {
            Log.w(TAG, "[" + name + "] TRUSTED 角色授予失败，跳过直建");
            return null;
        }
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager)
                        ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return null;
        int sdk = android.os.Build.VERSION.SDK_INT;
        for (int raw : TrustedFlags.candidateFlags()) {
            int flags = TrustedFlags.normalize(raw, sdk);
            VirtualDisplay candidate;
            try {
                // 6 参公开重载（API 33 起在 SDK 里）；老设备 NoSuchMethodError 由 catch 兜
                candidate = dm.createVirtualDisplay("seagull-" + name, w, h, dpi, surface, flags);
            } catch (Throwable t) {
                Log.w(TAG, "[" + name + "] flags=0x" + Integer.toHexString(flags)
                        + " 建屏被拒: " + t);
                continue;
            }
            if (candidate == null) continue;
            int df = -1;
            try {
                df = candidate.getDisplay() != null ? candidate.getDisplay().getFlags() : -1;
            } catch (Throwable ignore) {}
            if (TrustedFlags.hasTrusted(df)) {
                Log.i(TAG, "[" + name + "] TRUSTED 屏建成 flags=0x" + Integer.toHexString(flags)
                        + " displayFlags=0x" + Integer.toHexString(df));
                return candidate;
            }
            Log.w(TAG, "[" + name + "] flags=0x" + Integer.toHexString(flags) + " 未获受信位"
                    + " (df=0x" + Integer.toHexString(df) + ")，降级下一候选");
            try { candidate.release(); } catch (Throwable ignore) {}
        }
        return null;
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
        touchFor().setDisplay(displayId, transformer.scaleX());
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

    /** 诊断行：给 UI 显示"虚拟屏到底建没建、是不是受信屏"。 */
    public String describe() {
        if (displayId <= 0) {
            return "未部署" + (lastError.isEmpty() ? "" : "（" + lastError + "）");
        }
        return (ready ? "已就绪" : "已建屏未挂载")
                + (trusted ? " · TRUSTED 受信屏" : " · 投影屏(非受信)")
                + " · displayId=" + displayId
                + (lastPkg.isEmpty() ? "" : " · " + lastPkg);
    }
}
