package com.seagull.carlauncher;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

/**
 * L3 虚拟屏窗口 —— 把应用真正启动到虚拟屏上，卡片内可交互。
 *
 * 与 L1 镜像卡片的本质区别：
 *   L1 = MediaProjection 镜像，原应用仍在主屏，卡片只读；
 *   L3 = 目标应用被 launch 到虚拟屏 displayId 上，主屏腾出来，卡片里是活的窗口。
 *
 * 权限现实（本机实测）：
 *   · 应用可以自己 createVirtualDisplay（PUBLIC flag），这一步普通应用就能过；
 *   · 真正卡人的是 ActivityOptions.setLaunchDisplayId()：它要求
 *     caller 或 target 持有 ADD_TRUSTED_DISPLAY / INTERNAL_SYSTEM_WINDOW，
 *     或在同一 task 内。普通应用三者皆无 → 抛 SecurityException。
 *   · 本机 com.android.shell **有** ADD_TRUSTED_DISPLAY（granted=true），
 *     所以有一条不依赖签名的通路：由 root 侧以 shell 身份 `am start --display N` 代启。
 *
 * 因此本类做两件事：
 *   1) 自己尝试 launch（能过就过，过不了记下确切异常）；
 *   2) 失败时回退到 root 代启通道（{@link RootShellService}），并把结果如实报告。
 */
public final class VirtualDisplayHost {

    private static final String TAG = "SeagullVD";

    public static final class R {
        public final boolean ok;
        public final String msg;
        public R(boolean ok, String msg) { this.ok = ok; this.msg = msg; }
        @Override public String toString() { return (ok ? "OK  " : "FAIL ") + msg; }
    }

    private VirtualDisplay vd;
    private int displayId = -1;
    private Context appCtx;

    public int displayId() { return displayId; }

    /** 第一步：建虚拟屏。返回 displayId，失败 -1。 */
    public R create(Context ctx, int w, int h, int dpi) {
        release();
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            // WITHOUT surface：虚拟屏自己持有内容，这样启动进去的应用才画得出来。
            // flags 与 MirrorSlot 现策略对齐：不加减 AUTO_MIRROR —— 任务没落屏时
            // AUTO_MIRROR 会把手机桌面镜像进画中画，排障结论被带偏（批次 J 实测坑）。
            // OWN_CONTENT_ONLY 也不行：第三方应用 task 上来了画面也是黑的。
            vd = dm.createVirtualDisplay("seagull-vd", w, h, dpi, (Surface) null,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC);
            if (vd == null) {
                return new R(false, "createVirtualDisplay 返回 null（权限或资源被拒）");
            }
            displayId = vd.getDisplay() != null ? vd.getDisplay().getDisplayId() : -1;
            if (displayId < 0) {
                vd.release();
                vd = null;
                return new R(false, "createVirtualDisplay 后拿不到 display（设备未就绪）");
            }
            Log.i(TAG, "虚拟屏已建 id=" + displayId + " " + w + "x" + h + "@" + dpi);
            return new R(true, "虚拟屏 id=" + displayId + "  " + w + "x" + h + "@" + dpi);
        } catch (Throwable t) {
            Log.e(TAG, "create", t);
            return new R(false, "createVirtualDisplay 抛异常：" + t);
        }
    }

    /** 第二步：把目标应用启动到虚拟屏（应用自身权限路径）。 */
    public R launch(Context ctx, String pkg) {
        if (displayId < 0) return new R(false, "还没建虚拟屏");
        try {
            android.content.Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return new R(false, pkg + " 没有可启动的入口");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            Bundle b = opts.toBundle();
            ctx.startActivity(i, b);
            Log.i(TAG, "launch 已下发 " + pkg + " -> display " + displayId);
            return new R(true, "已下发 " + pkg + " → display " + displayId + "（是否真的上去要看下一句）");
        } catch (SecurityException se) {
            Log.w(TAG, "launch 被安全策略拒绝", se);
            return new R(false, "SecurityException：" + se.getMessage()
                    + "\n  → 需要 ADD_TRUSTED_DISPLAY / uid=1000，走 root 代启");
        } catch (Throwable t) {
            Log.e(TAG, "launch", t);
            return new R(false, "异常：" + t);
        }
    }

    /**
     * 回退通道：root 代启。现在统一走 RootOps.launchOnDisplay ——
     * 守护进程进程内反射 → 公开 API → am 命令，三级降级都在里面。
     * 以前只有裸 `am start`，命令解析与输出都要猜，先留着这条对比口径。
     */
    public R launchViaRoot(String pkg) {
        if (displayId < 0) return new R(false, "还没建虚拟屏");
        if (!Caps.hasRoot()) return new R(false, "无 root 通道");
        boolean ok = RootOps.launchOnDisplay(ctx(), pkg, displayId);
        return new R(ok, ok
                ? "RootOps.launchOnDisplay 已下发 " + pkg + " → display " + displayId
                : "RootOps.launchOnDisplay 三级通道全部失败");
    }

    /** 老 am 直启口径也留着（排障时对比守护进程与裸命令的差异）。 */
    public R launchViaAm(String pkg) {
        if (displayId < 0) return new R(false, "还没建虚拟屏");
        if (!Caps.hasRoot()) return new R(false, "无 root 通道");
        String cmd = "am start --display " + displayId
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + pkg
                + " -f 0x10000000";
        String out = Caps.exec(cmd);
        if (out == null) return new R(false, "root 命令无回显");
        boolean ok = out.contains("Starting") || out.contains("Status: ok");
        String err = null;
        for (String line : out.split("\n")) {
            if (line.contains("Error") || line.contains("Exception")
                    || line.contains("Warning") || line.contains("Permission")) err = line.trim();
        }
        return new R(ok && err == null, out.trim() + (err != null ? "\n  ⚠ " + err : ""));
    }

    private Context ctx() {
        if (appCtx == null) {
            throw new IllegalStateException("VirtualDisplayHost 未 attach Context");
        }
        return appCtx;
    }

    /** create/verify 之前 attach 一次（RootOps.launchOnDisplay 需要 Context）。 */
    public void attach(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        PrivClient.init(ctx);
    }

    /** 第三步：确认目标是否真的落在虚拟屏上（解析 dumpsys 的 Task 段，不是 DisplayContent 段）。 */
    public R verify(Context ctx, String pkg) {
        if (displayId < 0) return new R(false, "还没建虚拟屏");
        String out = Caps.exec("dumpsys activity activities");
        if (out == null) return new R(false, "dumpsys 无回显");
        StringBuilder found = new StringBuilder();
        int n = 0;
        for (String line : out.split("\n")) {
            if (line.trim().startsWith("* Task{") || line.contains("Task{")) {
                // 只认带 displayId 的 Task 行
                if (line.contains("displayId=" + displayId)) {
                    found.append(line.trim()).append('\n');
                    n++;
                }
            }
        }
        if (n == 0) {
            return new R(false, "display " + displayId + " 上没有任何 task");
        }
        boolean has = found.toString().contains(pkg);
        return new R(has, (has ? "✔ " + pkg + " 已在 display " + displayId
                : "✘ display " + displayId + " 上有 " + n + " 个 task，但没有 " + pkg)
                + "\n" + found);
    }

    /**
     * 焦点切到虚拟屏上的 task。
     * 普通应用反射调 ActivityTaskManager.setFocusedTask 需要 MANAGE_ACTIVITY_TASKS，必被拒，
     * 所以这里优先走 root：`am task focus <id>`（shell 身份）；失败才退回反射并如实报告异常。
     */
    public R focusTask() {
        if (displayId < 0) return new R(false, "还没建虚拟屏");
        String out = Caps.exec("dumpsys activity activities");
        if (out == null) return new R(false, "dumpsys 无回显");
        String taskId = null;
        for (String line : out.split("\n")) {
            if (line.contains("Task{") && line.contains("displayId=" + displayId)) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("#(\\d+)").matcher(line);
                if (m.find()) { taskId = m.group(1); break; }
            }
        }
        if (taskId == null) return new R(false, "display " + displayId + " 上没有可切焦点的 task");
        String rootOut = Caps.exec("am task focus " + taskId);
        boolean rootOk = rootOut != null && !rootOut.toLowerCase().contains("exception")
                && !rootOut.toLowerCase().contains("error");
        if (rootOk) return new R(true, "am task focus " + taskId + "（root 通道） | " + rootOut.trim());
        try {
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Object svc = atm.getMethod("getService").invoke(null);
            atm.getMethod("setFocusedTask", int.class).invoke(svc, Integer.parseInt(taskId));
            return new R(true, "setFocusedTask(" + taskId + ") 反射成功");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return new R(false, "root 通道：" + (rootOut == null ? "无回显" : rootOut.trim())
                    + "\n  反射：" + cause);
        }
    }

    public void release() {
        if (vd != null) {
            try { vd.release(); } catch (Throwable ignore) {}
            vd = null;
        }
        displayId = -1;
    }
}
