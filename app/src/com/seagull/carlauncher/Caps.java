package com.seagull.carlauncher;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 运行环境能力自检 —— 决定桌面能开到哪一档。
 *
 * L0 基础桌面 : 启动应用 / Dock / 应用列表       —— 无需任何权限
 * L1 悬浮卡片 : 悬浮窗 + 屏幕捕获镜像            —— 无需 root（需用户授权一次截屏）
 * L2 原生画中画: 系统 PictureInPicture           —— 无需 root，但全系统仅 1 个
 * L3 虚拟屏   : 把应用真正搬进虚拟屏             —— 需特权（system uid / hook 校验）
 */
public final class Caps {

    private static final String TAG = "SeagullCaps";

    public static final int L0_BASIC = 0;
    public static final int L1_OVERLAY = 1;
    public static final int L2_PIP = 2;
    public static final int L3_VIRTUAL_DISPLAY = 3;

    private static Boolean sRoot;
    private static Boolean sVirtualDisplay;
    private static String sRootWho = "未检测";

    private Caps() {}

    /* ---------------- root ---------------- */

    public static synchronized boolean hasRoot() {
        if (sRoot != null) return sRoot;
        boolean ok = false;
        try {
            String out = exec("su -c id");
            if (out != null) {
                sRootWho = out.trim();
                ok = out.contains("uid=0");
            } else {
                sRootWho = "su 无回显";
            }
        } catch (Throwable t) {
            sRootWho = "su 不可用: " + t;
            ok = false;
        }
        Log.i(TAG, "hasRoot=" + ok + " who=" + sRootWho);
        sRoot = ok;
        return ok;
    }

    public static String rootWho() {
        hasRoot();
        return sRootWho;
    }

    /** 以 root 执行一条命令，返回 stdout（失败返回 null）。 */
    public static String exec(String cmd) {
        try {
            java.lang.Process p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "exec 失败: " + cmd, t);
            return null;
        }
    }

    /* ---------------- 悬浮窗 ---------------- */

    public static boolean canOverlay(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    /* ---------------- 虚拟屏（特权档） ---------------- */

    /**
     * 虚拟屏能力判定：
     *   · uid == 1000（system 应用）→ 自己建屏自己搬，稳；
     *   · 有 root → 守护进程以 uid 0 进程内反射搬应用（批次 J 新增路径，
     *     Extendroid 同款机制）。🟡 待真机验证：SELinux 是否放行 abstract socket、
     *     ROM 是否裁剪 moveRootTaskToDisplay，容器里验不了，先如实标出来；
     *   · 都没有 → 不可用。
     */
    public static synchronized boolean canVirtualDisplay(Context ctx) {
        if (sVirtualDisplay != null) return sVirtualDisplay;
        boolean ok;
        if (Process.myUid() == Process.SYSTEM_UID) {
            ok = probeVirtualDisplay(ctx);
        } else if (hasRoot()) {
            ok = true;
        } else {
            ok = false;
        }
        sVirtualDisplay = ok;
        return ok;
    }

    /** 虚拟屏的落地路径，给体检报告如实写清楚用。 */
    public static synchronized String vdPath() {
        if (Process.myUid() == Process.SYSTEM_UID) return "system uid 直连";
        if (hasRoot()) {
            // 批次 K：root 在手的设备优先 TRUSTED 屏（角色授予 + 受信位校验），
            // 失败回落投影屏 + ensureOnDisplay 自愈
            return android.os.Build.VERSION.SDK_INT >= 33
                    ? "root TRUSTED 屏 / 投影兜底（待真机验证）"
                    : "root 守护进程反射 + 投影屏（Android 14 前不受信也拉不走）";
        }
        return "不可用";
    }

    private static boolean probeVirtualDisplay(Context ctx) {
        VirtualDisplay vd = null;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            vd = dm.createVirtualDisplay("seagull-probe", 16, 16, 160, null,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC);
            return vd != null;
        } catch (Throwable t) {
            Log.w(TAG, "probeVirtualDisplay", t);
            return false;
        } finally {
            if (vd != null) try { vd.release(); } catch (Throwable ignore) {}
        }
    }

    /* ---------------- 汇总 ---------------- */

    public static int level(Context ctx) {
        if (canVirtualDisplay(ctx)) return L3_VIRTUAL_DISPLAY;
        if (canOverlay(ctx)) return L1_OVERLAY;
        return L0_BASIC;
    }

    public static String levelName(int lv) {
        switch (lv) {
            case L3_VIRTUAL_DISPLAY: return "L3 虚拟屏（应用真搬进卡片）";
            case L2_PIP:             return "L2 系统原生画中画";
            case L1_OVERLAY:         return "L1 悬浮镜像卡片";
            default:                 return "L0 基础桌面";
        }
    }

    public static String report(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("设备      : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android   : ").append(Build.VERSION.RELEASE)
          .append("  (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("本应用 uid: ").append(Process.myUid())
          .append(Process.myUid() == Process.SYSTEM_UID ? "  ← system" : "  (普通应用)").append('\n');
        sb.append("root      : ").append(hasRoot() ? "有" : "无").append("  |  ").append(rootWho()).append('\n');
        sb.append("悬浮窗权限: ").append(canOverlay(ctx) ? "已授权" : "未授权").append('\n');
        sb.append("虚拟屏    : ").append(canVirtualDisplay(ctx) ? "可用（" + vdPath() + "）" : "不可用（需 system uid 或 root）").append('\n');
        sb.append("───────────────\n");
        sb.append("当前档位  : ").append(levelName(level(ctx)));
        return sb.toString();
    }
}
