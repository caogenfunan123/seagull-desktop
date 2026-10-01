package com.seagull.carlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 画中画槽的进程级持有者（批次 L）。
 *
 * 为什么需要它：VD 一旦释放，系统会把屏上的任务倒回默认屏 —— 桌面立刻冒出
 * 全屏应用，这正是用户说的「每次进入桌面还是应用界面」。所以 VD 的生命周期
 * 挂在进程上而不是 MirrorActivity 上：退出画中画页只断 Surface，屏与目标
 * 应用都留着，再进来把 Surface 挂回同一块 VD，画面秒恢复。
 *
 * 清空某槽（长按 → 清空该槽）走 {@link #clear}：拆屏 + 停掉画中画里的应用
 * + 忘掉绑定，这是唯一的拆除入口。
 */
public final class MirrorHost {

    private static final String TAG = "SeagullMirrorHost";
    private static final String PREFS = "seagull";
    private static final long HEAL_MIN_INTERVAL_MS = 3000;

    private static final Map<String, MirrorSlot> SLOTS = new HashMap<>();
    private static long lastHeal;

    private MirrorHost() {}

    /** 取（或建）进程级槽位；ctx 存 applicationContext，不泄漏 Activity。 */
    public static synchronized MirrorSlot slot(Context ctx, String name) {
        MirrorSlot s = SLOTS.get(name);
        if (s == null) {
            s = new MirrorSlot(ctx.getApplicationContext(), name);
            SLOTS.put(name, s);
        }
        return s;
    }

    static synchronized MirrorSlot slot(int which) {
        return SLOTS.get(which == 1 ? "pip1" : "pip2");
    }

    /** 清空某槽：拆屏 + 停掉画中画里的应用 + 忘掉绑定。 */
    public static void clear(Context ctx, int which) {
        if (which < 1 || which > 2) return;
        MirrorSlot s = slot(which);
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pkg = sp.getString("mirror_pkg" + which, "");
        if (s != null) s.teardown();
        sp.edit().putString("mirror_pkg" + which, "").apply();
        if (pkg != null && !pkg.isEmpty()) {
            final String p = pkg;
            new Thread(() -> Caps.exec("am force-stop " + p), "pip-clear" + which).start();
        }
        Log.i(TAG, "清空槽 " + which + "（" + pkg + "）");
    }

    /**
     * 录屏被系统撤销：受信屏不依赖投影，继续用；投影屏的 VD 已死，
     * 拆掉免得屏上任务倒回主屏冒全屏。
     */
    public static synchronized void onProjectionStopped(Context ctx) {
        for (int i = 1; i <= 2; i++) {
            MirrorSlot s = slot(i);
            if (s == null || s.trusted()) continue;
            if (s.displayId() > 0) {
                Log.w(TAG, "槽 " + i + " 是投影屏且投影已停，拆屏");
                s.teardown();
            }
        }
        PipProjectionService.stop(ctx);
    }

    /**
     * 桌面 onResume 自愈（批次 L）：常驻画中画被系统拉回主屏时搬回去。
     * 保守：解析不出栈结构就不动（见 RootOps.ensureOnDisplay）。带节流 ——
     * 桌面 resume 很频繁，别把 su 打爆。
     */
    public static void healHome(Context ctx) {
        if (MirrorSlot.activeCount() == 0) return;
        long now = System.currentTimeMillis();
        if (now - lastHeal < HEAL_MIN_INTERVAL_MS) return;
        lastHeal = now;
        for (int i = 1; i <= 2; i++) {
            final MirrorSlot s = slot(i);
            if (s == null || s.displayId() <= 0) continue;
            SharedPreferences sp =
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final String pkg = sp.getString("mirror_pkg" + i, "");
            if (pkg == null || pkg.isEmpty()) continue;
            final int slotNo = i;
            new Thread(() -> {
                String out = RootOps.ensureOnDisplay(ctx.getApplicationContext(), pkg, s.displayId());
                Log.i(TAG, "桌面自愈 槽 " + slotNo + ": " + out);
            }, "pip-heal" + i).start();
        }
    }
}
