package com.seagull.carlauncher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `am stack list` 输出解析（批次 K，目标自愈 ensureOnDisplay 的纯逻辑部分）。
 *
 * 移植自用户仓库 carlink-desktop-private 的 svc/StackListParser.kt（其上核验
 * 参考版 TaskMover.smali）：车联助手在 app_process 里用反射搬任务，我们在 root
 * shell 下用 `am stack list` 找目标任务是否还在虚拟屏 —— singleTask 应用
 * （网易云这类）在虚拟屏内部跳转会把任务拉回主屏，自愈必须靠这个判据。
 *
 * 零 android.* import，可纯 JVM 单测（app/selfcheck/StackListCheck.java）——
 * 这是"目标任务是否还在虚拟屏"的唯一判据，解析错一格，自愈就会误重拉。
 *
 * 输出结构（AOSP 各版本大体一致）：
 *   displayId=0
 *     stackId=1 ...
 *       taskId=25 ...
 *         *TaskRecord{...} ... topActivity=ComponentInfo{com.netease.cloudmusic/...}
 *   displayId=2
 *     stackId=7 ...
 *       taskId=31 ...
 */
public final class StackScan {

    public static final class TaskRef {
        public final int stackId;
        public final int taskId;
        public TaskRef(int stackId, int taskId) { this.stackId = stackId; this.taskId = taskId; }
        @Override public String toString() { return "stack=" + stackId + "/task=" + taskId; }
    }

    private StackScan() {}

    /** 按 displayId 分段；displayId 行独立成行（stack 行里也含 displayId= 字样，勿误分）。 */
    public static Map<Integer, String> segments(String out) {
        Map<Integer, String> map = new LinkedHashMap<>();
        if (out == null) return map;
        int cur = -1;
        StringBuilder sb = new StringBuilder();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("displayId=") && t.length() > 10 && isDigits(t.substring(10))) {
                if (cur >= 0) map.put(cur, sb.toString());
                cur = Integer.parseInt(t.substring(10));
                sb.setLength(0);
            } else if (cur >= 0) {
                sb.append(line).append('\n');
            }
        }
        if (cur >= 0) map.put(cur, sb.toString());
        return map;
    }

    /** 目标包是否在指定屏上（任务描述/topActivity 任意位置含包名）。 */
    public static boolean isRunningOnDisplay(String out, String pkg, int displayId) {
        String seg = segments(out).get(displayId);
        return seg != null && pkg != null && !pkg.isEmpty() && seg.contains(pkg);
    }

    /**
     * 指定屏上包含目标包的最近一个任务（搬回的源）。
     * 任务块 = taskId= 行（含）到下一个 taskId= 行（不含）；包名出现在块内即命中。
     */
    public static TaskRef findTaskOnDisplay(String out, String pkg, int displayId) {
        String seg = segments(out).get(displayId);
        if (seg == null || pkg == null || pkg.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        for (String l : seg.split("\n")) lines.add(l);
        int[] stackOf = new int[lines.size()];
        int lastStack = -1;
        for (int i = 0; i < lines.size(); i++) {
            lastStack = pickStack(lines.get(i), lastStack);
            stackOf[i] = lastStack;
        }
        // 收集任务块起点
        List<int[]> starts = new ArrayList<>();   // {taskId, stackId, lineIdx}
        for (int i = 0; i < lines.size(); i++) {
            int tid = pickInt(lines.get(i), "taskId=");
            if (tid >= 0) starts.add(new int[] {tid, stackOf[i], i});
        }
        TaskRef hit = null;
        for (int s = 0; s < starts.size(); s++) {
            int from = starts.get(s)[2];
            int to = (s + 1 < starts.size()) ? starts.get(s + 1)[2] : lines.size();
            for (int i = from; i < to; i++) {
                if (lines.get(i).contains(pkg)) {
                    hit = new TaskRef(starts.get(s)[1], starts.get(s)[0]);
                    break;
                }
            }
        }
        return hit;
    }

    /** 指定屏上的第一个任务（move-task 的目的 rootTask；找不到锚点时的兜底）。 */
    public static TaskRef firstTaskOnDisplay(String out, int displayId) {
        String seg = segments(out).get(displayId);
        if (seg == null) return null;
        int lastStack = -1;
        for (String line : seg.split("\n")) {
            lastStack = pickStack(line, lastStack);
            int tid = pickInt(line, "taskId=");
            if (tid >= 0) return new TaskRef(lastStack, tid);
        }
        return null;
    }

    /* ---------------- 内部小工具 ---------------- */

    private static int pickStack(String line, int last) {
        int v = pickInt(line, "stackId=");
        return v >= 0 ? v : last;
    }

    /** 行内找 key=数字，取第一个；找不到 -1。 */
    private static int pickInt(String line, String key) {
        int p = line.indexOf(key);
        if (p < 0) return -1;
        p += key.length();
        int q = p;
        while (q < line.length() && line.charAt(q) >= '0' && line.charAt(q) <= '9') q++;
        if (q == p) return -1;
        try {
            return Integer.parseInt(line.substring(p, q));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
