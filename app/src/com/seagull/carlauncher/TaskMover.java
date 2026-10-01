package com.seagull.carlauncher;

import android.content.Context;
import android.util.Log;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 窗口搬运（11.13 收回窗口 / 11.14 从边缘小标签拉回窗口）—— 需 root。
 *
 * 收回：把虚拟屏上的任务搬回主屏，用 `am task` 的搬运子命令。
 * 拉回：同一套命令反向（把主屏上的任务搬进虚拟屏 displayId）。
 *
 * ponytail: 各家 ROM 对 `am task` 的子命令裁得不一致（有的只剩 lock/resizeable，
 * 有的还留 move-to-display），所以这里按顺序试，把每条的真实返回原样记下来，
 * 设置页里能直接看到是哪条命令被拒 —— 与其猜一个「通用写法」，不如把证据摊开。
 */
public final class TaskMover {

    private static final String TAG = "SeagullMover";

    /** 各 ROM 上试过的搬运命令，按成功率排。 */
    private static final String[] MOVE_TO_MAIN = {
            "am task move-to-display %1$d 0",
            "am stack move-task %1$d 0 0 true",
            "cmd activity task move-to-display %1$d 0",
    };
    private static final String[] MOVE_TO_DISPLAY = {
            "am task move-to-display %1$d %2$d",
            "am stack move-task %1$d 0 %2$d true",
            "cmd activity task move-to-display %1$d %2$d",
    };

    private TaskMover() {}

    /** 某块屏上最靠前的 taskId。找不到返回 -1。 */
    public static int frontTask(Context c, int displayId) {
        if (!Caps.hasRoot()) return -1;
        String out = Caps.exec("dumpsys activity activities | grep -B2 -A12 'displayId=" + displayId + "'");
        if (out == null) return -1;
        // 真实 dump 的任务行是 `Task{a1b2 #42 ...}`（Task{#N} 形式）；
        // taskId=42 形式也认（老 am stack list 口径）。只认前者会永远找不到任务。
        Matcher m = Pattern.compile("Task\\{[0-9a-fA-F]+ #(\\d+)").matcher(out);
        if (m.find()) return Integer.parseInt(m.group(1));
        Matcher m2 = Pattern.compile("taskId=(\\d+)").matcher(out);
        return m2.find() ? Integer.parseInt(m2.group(1)) : -1;
    }

    /** 11.13 收回窗口：把 displayId 这块屏上的任务搬回主屏。 */
    public static String reclaim(Context c, int displayId) {
        int id = frontTask(c, displayId);
        if (id < 0) return "屏 " + displayId + " 上没有任务可搬";
        return tryAll(c, MOVE_TO_MAIN, id, 0, "收回 task " + id);
    }

    /** 11.14 从边缘小标签拉回：把主屏上的任务搬进 displayId。 */
    public static String pullBack(Context c, int displayId) {
        int id = frontTask(c, 0);
        if (id < 0) return "主屏上没有任务可拉";
        return tryAll(c, MOVE_TO_DISPLAY, id, displayId, "拉回 task " + id + " → 屏 " + displayId);
    }

    private static String tryAll(Context c, String[] cmds, int taskId, int arg2, String what) {
        StringBuilder sb = new StringBuilder(what);
        for (String tpl : cmds) {
            String cmd = String.format(java.util.Locale.US, tpl, taskId, arg2);
            String out = Caps.exec(cmd);
            boolean bad = out == null
                    || out.toLowerCase().contains("unknown command")
                    || out.toLowerCase().contains("usage:")
                    || out.toLowerCase().contains("exception")
                    || out.toLowerCase().contains("permission denied");
            sb.append("\n").append(cmd).append(" → ")
                    .append(out == null ? "null" : out.trim().replace('\n', ' '));
            Log.i(TAG, cmd + " -> " + out);
            if (!bad) return sb.toString();
        }
        sb.append("\n这台机器的 am 不认这些搬运命令");
        return sb.toString();
    }

    /** 边缘小标签（11.14）：只在有活跃虚拟屏时出现。 */
    public static boolean hasEdgeTab(Context c) {
        return MirrorSlot.hasMirrorDisplay();
    }

    public static int edgeDisplay() { return MirrorSlot.newestDisplay(); }

    /** 排障用：dumpsys 片段（设置页原样展示）。 */
    public static String dump(Context c) {
        List<String> ids = new java.util.ArrayList<>();
        String out = Caps.exec("dumpsys activity activities | grep -E 'displayId=|taskId=' | head -40");
        if (out == null) return "拿不到 dumpsys（可能没有 root）";
        for (String s : out.split("\n")) ids.add(s.trim());
        return ids.isEmpty() ? "dumpsys 里没有任务" : android.text.TextUtils.join("\n", ids);
    }
}
