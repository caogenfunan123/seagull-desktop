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
 *   · 把目标应用拉进虚拟屏走 root：守护进程进程内反射 startActivityAsUser（批次 J 新增），
 *     失败回退 root `am start --display <id> -f <flags> -n <comp>`
 *   · 触摸回注走 root：守护进程 injectInputEvent（真多指），失败回退 `input -d <id> ...`
 * 全程不依赖目标进程内的任何 hook。
 *
 * 降级链是硬规则：PrivClient 每一条失败都如实返回 false，命令照旧走 shell，
 * 调用方永远拿得到「成功」或「明确的失败」，不存在第三条路。
 */
public final class RootOps {

    private static final String TAG = "SeagullRootOps";

    /**
     * 拉起 flags（批次 K 修正，依据用户仓库 carlink-desktop-private m8 真机复盘）：
     *   0x10000000 NEW_TASK           —— 必须有，root 启动无 Activity 上下文
     *   0x08000000 MULTIPLE_TASK      —— 强制在虚拟屏新建独立任务（否则 singleTask 目标
     *                                  会把主屏已有任务拉到前台，"点了却跳全屏"就是它）
     *   0x00800000 EXCLUDE_FROM_RECENTS —— 镜像实例不进最近任务（参考版 0x18800000 同款）
     * 组合 = 0x18800000。LAUNCH_FLAGS（字符串）给 am 命令用，LAUNCH_FLAGS_INT 给守护进程用。
     */
    public static final String LAUNCH_FLAGS = "0x18800000";
    public static final int LAUNCH_FLAGS_INT = 0x18800000;

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
     * 三路启动，按可靠性排：
     *   ① root 守护进程进程内反射 startActivityAsUser（callingPackage=com.android.shell，
     *      ActivityOptions.setLaunchDisplayId）—— 与 am 同权，但没有进程启动开销，
     *      也不会被 am 的 argv 解析坑；
     *   ② 公开 API（部分 ROM 允许普通应用自己 startActivity 到虚拟屏）；
     *   ③ root `am start --display`（命令兜底，各 ROM 裁得最凶但普遍还在）。
     * flags 用 0x18800000（含 EXCLUDE_FROM_RECENTS）；--user 0 显式指定用户
     * （OneStep4 对标结论：工作资料/多用户下不带会落到错误用户）。
     * 返回 true 表示命令已下发成功（是否真的上去需另行 verify / ensureOnDisplay）。
     */
    public static boolean launchOnDisplay(Context ctx, String pkg, int displayId) {
        String comp = resolveLauncher(ctx, pkg);
        if (comp == null) {
            Log.w(TAG, "launchOnDisplay: 找不到 " + pkg + " 的启动组件");
            return false;
        }
        // 【坑 #5】am start 对已在运行的应用会静默投递到主屏实例：singleTask 复用
        // display 0 上的老栈，画中画黑屏、手机屏反被应用盖住（"有的软件跳转到
        // 应用界面，返回桌面也显示不了"）。先 force-stop 砍掉主屏实例再起。
        String killOut = Caps.exec("am force-stop " + pkg);
        Log.i(TAG, "部署前 force-stop " + pkg + " -> "
                + (killOut == null ? "无回显" : killOut.trim()));
        PrivClient.init(ctx);
        if (PrivClient.launch(displayId, comp, LAUNCH_FLAGS_INT)) {
            Log.i(TAG, "launchOnDisplay[" + pkg + "] 走守护进程成功 -> display " + displayId);
            relaxCompat(pkg);
            return true;
        }
        if (launchViaApi(ctx, comp, displayId)) {
            Log.i(TAG, "launchOnDisplay[" + pkg + "] 走 API 成功 -> display " + displayId);
            relaxCompat(pkg);
            return true;
        }
        String out = Caps.exec("am start --user 0 --display " + displayId
                + " -f " + LAUNCH_FLAGS + " -n " + comp);
        boolean ok = out != null && !out.contains("Error") && !out.contains("Exception");
        Log.i(TAG, "launchOnDisplay " + comp + " -> display " + displayId + " ok=" + ok
                + " " + (out == null ? "" : out.trim()));
        if (ok) relaxCompat(pkg);
        return ok;
    }

    /**
     * 让应用在虚拟屏里铺满画布（高德地图这类）：
     *  · FORCE_RESIZE_APP —— 不吃 letterbox 兼容模式（小屏/怪宽高比下应用
     *    渲染成手机尺寸居中留黑边，就是"没办法铺满全屏幕"的直接原因）；
     *  · NEVER_FIX_ORIENTATION —— 别锁死 manifest 里的 screenOrientation，
     *    跟着虚拟屏方向转，否则竖屏应用塞横屏画布左右留黑边。
     * 命令缺省（老 ROM 没有 am compat）时只记日志，不影响部署。
     * 仅后台线程调用。
     */
    public static void relaxCompat(String pkg) {
        for (String change : new String[] {"FORCE_RESIZE_APP", "NEVER_FIX_ORIENTATION"}) {
            String out = Caps.exec("am compat enable " + change + " " + pkg);
            boolean ok = out != null && !out.toLowerCase().contains("error")
                    && !out.toLowerCase().contains("exception")
                    && !out.toLowerCase().contains("unknown");
            Log.i(TAG, "am compat enable " + change + " " + pkg + " ok=" + ok
                    + " " + (out == null ? "无回显" : out.trim()));
        }
    }

    /** 清空某槽时还原兼容设置，别把主屏上这个应用的行为也永久改掉。 */
    public static void resetCompat(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        String out = Caps.exec("am compat reset " + pkg);
        Log.i(TAG, "am compat reset " + pkg + " -> "
                + (out == null ? "无回显" : out.trim()));
    }

    private static boolean launchViaApi(Context ctx, String comp, int displayId) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(android.content.ComponentName.unflattenFromString(comp));
            if (i.getComponent() == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
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

    /* ---------------- TRUSTED 虚拟屏（批次 K，carlink m13 同款） ---------------- */

    private static volatile Boolean trustedRoleGranted = null;

    /**
     * 给本包授 COMPANION_DEVICE_APP_STREAMING 角色（root `cmd role add-role-holder`）。
     * Android 14 的 RoleController 会给角色持有者授 ADD_TRUSTED_DISPLAY 等签名级权限，
     * 本进程才能用 VIRTUAL_DISPLAY_FLAG_TRUSTED 建屏 —— 受信屏才收留得住
     * home-affinity/TASK_ON_HOME 任务，这是"画中画任务被系统拉回主屏"的根治前提
     * （对标 OneStep4 RootVirtualDisplayHost.ensureTrustedDisplayRole）。
     * 幂等，进程内缓存；仅后台线程调用。
     */
    public static boolean grantTrustedDisplayRole(Context ctx) {
        Boolean cached = trustedRoleGranted;
        if (cached != null) return cached;
        String pkg = ctx.getPackageName();
        String out = Caps.exec("cmd role add-role-holder --user 0 "
                + "android.app.role.COMPANION_DEVICE_APP_STREAMING " + pkg + " 0");
        boolean ok = out != null && !out.toLowerCase().contains("error")
                && !out.toLowerCase().contains("exception");
        Log.i(TAG, "TRUSTED 角色授予(" + pkg + ") ok=" + ok
                + " " + (out == null ? "" : out.trim()));
        trustedRoleGranted = ok;
        return ok;
    }

    /** 角色授予状态（诊断用，不触发 su）。 */
    public static String roleState() {
        Boolean g = trustedRoleGranted;
        return g == null ? "未尝试" : (g ? "已授予 COMPANION_DEVICE_APP_STREAMING" : "授予失败");
    }

    /* ---------------- 目标自愈（批次 K，carlink m10/m12 同款） ---------------- */

    /**
     * singleTask 应用（网易云这类）在虚拟屏内部跳转时，新 activity 会落到默认屏，
     * 任务整体被拉走 —— 主屏冒出全屏应用、画中画黑屏。这里检测并搬回。
     *
     * 保守策略（m12 真机教训）：检测不出栈结构就放弃，绝不主动重拉 ——
     * 无脑重拉会在虚拟屏里叠出第二个目标任务，两个画中画一起坏。
     * 优先守护进程反射 moveRootTaskToDisplay（Android 14+，root uid 直接过检查），
     * 失败回退 `am task move-task <taskId> <displayId>`。
     * 仅后台线程调用。
     */
    public static String ensureOnDisplay(Context ctx, String pkg, int displayId) {
        if (pkg == null || pkg.isEmpty() || displayId <= 0) return "参数无效";
        String out = Caps.exec("am stack list");
        if (out == null) return "am stack list 无回显（su 不可用）";
        if (StackScan.segments(out).isEmpty()) return "am stack list 输出无法解析，跳过自愈";
        if (StackScan.isRunningOnDisplay(out, pkg, displayId)) return "目标已在虚拟屏";
        // 源 = 主屏(display 0)上的目标任务；搬到目标屏
        StackScan.TaskRef src = StackScan.findTaskOnDisplay(out, pkg, 0);
        if (src == null) return "目标任务不在主屏也不在虚拟屏（可能已退出）";
        PrivClient.init(ctx);
        if (PrivClient.move(src.taskId, displayId)) {
            return "已搬回虚拟屏（守护进程 moveRootTaskToDisplay " + src + "）";
        }
        String cmdOut = Caps.exec("am task move-task " + src.taskId + " " + displayId);
        boolean ok = cmdOut != null && !cmdOut.toLowerCase().contains("error")
                && !cmdOut.toLowerCase().contains("exception");
        return ok ? "已搬回虚拟屏（am task move-task " + src + "）"
                : "搬回失败：" + (cmdOut == null ? "无回显" : cmdOut.trim());
    }

    /* ---------------- 诊断 ---------------- */

    /** 当前存在的镜像虚拟屏（供诊断页显示"虚拟屏到底建没建"）。 */
    public static String readDisplays() {
        String out = Caps.exec("dumpsys display | grep -oE 'seagull-[a-zA-Z0-9]+'");
        if (out == null || out.trim().isEmpty()) return "未见虚拟屏";
        return out.trim().replace("\n", ", ");
    }

    /**
     * 确认目标是否真的落在虚拟屏上（TaskMover 同款判定：查 task 的 display 归属）。
     * 解析口径见 TaskScan（坑 #11：Task 行没有 displayId=，归属看段头）。
     * 返回 taskId，未找到 -1。
     */
    public static int findTaskId(String pkg, int displayId) {
        String out = Caps.exec("dumpsys activity activities");
        return out == null ? -1 : TaskScan.findTaskId(out, pkg, displayId);
    }

    /**
     * TaskMover：把任务迁移到目标 display。
     * 优先走守护进程的 moveRootTaskToDisplay 反射（Android 14+ 有这个隐藏方法，
     * root uid 调用直接过权限检查）；失败回退 `am task move-task` 命令链。
     * dumpsys 只拉一次：既定位 task，也确认它不在目标屏上（已在就是 no-op，
     * 报清楚，别让用户以为搬成功了）。
     */
    public static String moveTaskToDisplay(Context ctx, String pkg, int displayId, String anchorPkg) {
        if (!Caps.hasRoot()) return "无 root";
        String dump = Caps.exec("dumpsys activity activities");
        if (dump == null) return "dumpsys 无回显";
        int taskId = TaskScan.findTaskId(dump, pkg, -1);
        if (taskId < 0) return "找不到 " + pkg + " 的 task";
        if (TaskScan.findTaskId(dump, pkg, displayId) == taskId) {
            return "task=" + taskId + " 已在 display " + displayId + "，无需搬";
        }
        PrivClient.init(ctx);
        if (PrivClient.move(taskId, displayId)) {
            return "task=" + taskId + " -> display " + displayId + " : 守护进程反射成功";
        }
        String out = Caps.exec("am task move-task " + taskId + " " + displayId + " true");
        return "task=" + taskId + " -> display " + displayId + " : "
                + (out == null ? "无回显" : out.trim());
    }
}
