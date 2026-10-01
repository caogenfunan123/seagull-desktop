package com.seagull.carlauncher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 `dumpsys activity activities` 文本里扫 task（找 taskId、判 display 归属）。
 * 纯字符串解析：零 android.* import，可脱离设备单测（app/selfcheck/DumpParseCheck.java）。
 *
 * 口径（TODO 已知坑 #11，批次 J 复核确认）：
 *   · display 归属来自 `Display #N` 段头，Task 行本身没有 displayId= 字段 ——
 *     按错口径去找「虚拟屏上的 task」，会永远找不到，或误搬主屏上的实例；
 *   · 少数 ROM 的 Task 行会带 displayId=（mDisplayId=），一并认；
 *   · `Task id #77` 详情行、`Hist #0: ActivityRecord{}` 都不是任务行，只认 `Task{`。
 */
public final class TaskScan {

    private static final Pattern HDR = Pattern.compile("Display\\s+#(\\d+)");
    private static final Pattern TID = Pattern.compile("taskId=(\\d+)|#(\\d+)");

    private TaskScan() {}

    /**
     * @param dump     `dumpsys activity activities` 全文
     * @param pkg      目标包名（Task 行包含即可命中）
     * @param displayId 目标 display；小于等于 0 表示「只要这个包的 task」，取文件中第一个
     * @return taskId；该包不在指定 display 上返回 -1（绝不回退到别的 display 的实例 ——
     *         同一个包可以同时活在两个屏上，搬错实例就是「看起来在动、实际没动」）
     */
    public static int findTaskId(String dump, String pkg, int displayId) {
        if (dump == null || pkg == null || pkg.isEmpty()) return -1;
        int fallback = -1;
        int curDisplay = 0;   // 当前 Display #N 段头声明的 display
        for (String line : dump.split("\n")) {
            if (line.contains("Task{")) {
                if (!line.contains(pkg)) continue;
                int id = taskIdOf(line);
                if (id < 0) continue;
                boolean inline = line.contains("displayId=" + displayId);
                if (displayId > 0 && (inline || curDisplay == displayId)) return id;
                if (fallback < 0) fallback = id;
                continue;
            }
            Matcher h = HDR.matcher(line);
            if (h.find()) {
                try {
                    curDisplay = Integer.parseInt(h.group(1));
                } catch (NumberFormatException ignore) {}
            }
        }
        return displayId > 0 ? -1 : fallback;
    }

    /** `Task{a1b2 #42 ...}` 取 42；`taskId=42` 形式也认。 */
    private static int taskIdOf(String line) {
        Matcher m = TID.matcher(line);
        if (!m.find()) return -1;
        String g = m.group(1) != null ? m.group(1) : m.group(2);
        try {
            return Integer.parseInt(g);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
