package com.seagull.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.List;

/**
 * 特权操作层（root）。
 *
 * 技术来源：用户自己的仓库 caogenfunan123/carlink-desktop 的 svc/RootAuth.kt（Kotlin 移植为 Java），
 * 以及 carplay-reverse-engineering 报告 §6/§11 的核验结论：
 *   · 建虚拟屏走【公开的 MediaProjection.createVirtualDisplay】，不需要 ADD_TRUSTED_DISPLAY
 *   · 把目标应用拉进虚拟屏走 root：am start --display <id> -f <flags> -n <comp>
 *   · 触摸回注走 root：input -d <id> tap|swipe ...
 * 全程不依赖目标进程内的任何 hook。
 */
public final class RootOps {

    private static final String TAG = "SeagullRootOps";

    /**
     * 拉起 flags（核验自 smali）：
     *   0x10000000 NEW_TASK   —— 必须有，root 启动无 Activity 上下文
     *   0x08000000 MULTIPLE_TASK —— 强制在虚拟屏新建独立任务（否则可能复用主屏已有任务）
     * 组合 = 0x18000000。加上 NEW_DOCUMENT(0x00080000) 得 0x18080000 亦可用。
     */
    public static final String LAUNCH_FLAGS = "0x18000000";

    private RootOps() {}

    /* ---------------- 权限预备 ---------------- */

    /** 开悬浮窗 + 追加通知监听 + 授通知权限。追加式写入，不覆盖别人的监听器。 */
    public static String grantAll(Context ctx) {
        String pkg = ctx.getPackageName();
        StringBuilder log = new StringBuilder();

        Caps.exec("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
        log.append("悬浮窗 appops: ok\n");

        String cur = Caps.exec("settings get secure enabled_notification_listeners");
        String mine = pkg + "/" + MediaListenerService.class.getName();
        if (cur != null) {
            cur = cur.trim();
            if (!cur.contains(pkg)) {
                String merged = cur.isEmpty() || "null".equals(cur) ? mine : cur + ":" + mine;
                Caps.exec("settings put secure enabled_notification_listeners " + merged);
                log.append("通知监听: 已追加\n");
            } else {
                log.append("通知监听: 已存在\n");
            }
        }
        Caps.exec("pm grant " + pkg + " android.permission.POST_NOTIFICATIONS");
        log.append("POST_NOTIFICATIONS: 已尝试\n");
        return log.toString();
    }

    /**
     * 放开录屏 appops（镜像前置）。
     * 注意：该 appop 是「一次性授权」，多数 ROM 仍会弹确认框，但置 allow 能减少被拒。
     */
    public static boolean allowProjectMedia(Context ctx) {
        return Caps.exec("appops set " + ctx.getPackageName() + " PROJECT_MEDIA allow") != null;
    }

    /* ---------------- 把应用拉进虚拟屏 ---------------- */

    /**
     * 双路启动：① 先试公开 API（部分 ROM 允许），失败落 ② root。
     * 返回 true 表示命令已下发成功（是否真的上去需另行 verify）。
     */
    public static boolean launchOnDisplay(Context ctx, String pkg, int displayId) {
        String comp = resolveLauncher(ctx, pkg);
        if (comp == null) {
            Log.w(TAG, "launchOnDisplay: 找不到 " + pkg + " 的启动组件");
            return false;
        }
        if (launchViaApi(ctx, comp, displayId)) {
            Log.i(TAG, "launchOnDisplay[" + pkg + "] 走 API 成功 -> display " + displayId);
            return true;
        }
        String out = Caps.exec("am start --display " + displayId
                + " -f " + LAUNCH_FLAGS + " -n " + comp);
        boolean ok = out != null && !out.contains("Error") && !out.contains("Exception");
        Log.i(TAG, "launchOnDisplay " + comp + " -> display " + displayId + " ok=" + ok
                + " " + (out == null ? "" : out.trim()));
        return ok;
    }

    private static boolean launchViaApi(Context ctx, String comp, int displayId) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(android.content.ComponentName.unflattenFromString(comp));
            if (i.getComponent() == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            ctx.startActivity(i, opts.toBundle());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "API 启动被拒，落 root: " + t);
            return false;
        }
    }

    /** 解析入口组件，失败再退 `cmd package resolve-activity --brief`。 */
    public static String resolveLauncher(Context ctx, String pkg) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null && i.getComponent() != null) {
                return i.getComponent().flattenToShortString();
            }
        } catch (Throwable ignore) {}
        String out = Caps.exec("cmd package resolve-activity --brief " + pkg);
        if (out != null) {
            for (String line : out.split("\n")) {
                String s = line.trim();
                if (s.contains("/") && !s.contains(" ")) return s;
            }
        }
        try {
            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = ctx.getPackageManager()
                    .queryIntentActivities(probe, PackageManager.MATCH_ALL);
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) {
                    return ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /* ---------------- 触摸回注 ---------------- */

    /** 单条静默命令（触摸注入用）。 */
    public static boolean injectTouch(int displayId, String cmd) {
        if (displayId <= 0) return false;
        String out = Caps.exec("input -d " + displayId + " " + cmd);
        return out != null;
    }

    /* ---------------- 诊断 ---------------- */

    /** 当前存在的镜像虚拟屏（供诊断页显示"虚拟屏到底建没建"）。 */
    public static String readDisplays() {
        String out = Caps.exec("dumpsys display | grep -oE 'seagull-[a-zA-Z0-9]+'");
        if (out == null || out.trim().isEmpty()) return "未见虚拟屏";
        return out.trim().replace("\n", ", ");
    }

    /**
     * 确认目标是否真的落在虚拟屏上（TaskMover 同款判定：查 task 的 displayId）。
     * 返回 taskId，未找到 -1。
     */
    public static int findTaskId(String pkg, int displayId) {
        String out = Caps.exec("dumpsys activity activities");
        if (out == null) return -1;
        int fallback = -1;
        for (String line : out.split("\n")) {
            if (!line.contains("Task{") || !line.contains(pkg)) continue;
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("taskId=(\\d+)|#(\\d+)").matcher(line);
            int id = -1;
            if (m.find()) id = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
            if (id < 0) continue;
            if (displayId > 0 && line.contains("displayId=" + displayId)) return id;
            if (fallback < 0) fallback = id;
        }
        return displayId > 0 ? -1 : fallback;
    }

    /**
     * TaskMover：把任务迁移到目标 display 的 root task（报告 §11.3 核验逻辑）。
     * 优先走 root 行命令，避免应用侧 MANAGE_ACTIVITY_TASKS 权限问题。
     */
    public static String moveTaskToDisplay(String pkg, int displayId, String anchorPkg) {
        if (!Caps.hasRoot()) return "无 root";
        int taskId = findTaskId(pkg, -1);
        if (taskId < 0) return "找不到 " + pkg + " 的 task";
        String out = Caps.exec("am task move-task " + taskId + " " + displayId + " true");
        return "task=" + taskId + " -> display " + displayId + " : "
                + (out == null ? "无回显" : out.trim());
    }
}
