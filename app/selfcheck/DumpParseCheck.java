package com.seagull.carlauncher;

/**
 * 自检：TaskScan.findTaskId 的 dumpsys 解析口径（坑 #11：Task 行没有 displayId=，
 * display 归属来自 Display #N 段头）。
 *
 * 跑法（不依赖设备，纯 JVM）：
 *   javac -d /tmp/dumpsc app/src/com/seagull/carlauncher/TaskScan.java app/selfcheck/DumpParseCheck.java
 *   java  -ea -cp /tmp/dumpsc com.seagull.carlauncher.DumpParseCheck
 */
public class DumpParseCheck {

    // 现场取材：同包活在主屏（#77）和虚拟屏（#88）两个 display 上，段头归属错一步就搬错实例
    private static final String DUMP =
            "ActivityTaskManager: task dump\n" +
            "  * Task{ffe2932 #41 type=standard A=com.android.launcher:Launcher Uid=u0a224}\n" +
            "Display #0 (default from 286ab9a)\n" +
            "  mFocusedApp=ActivityRecord{7c31b2 u0 com.android.launcher}\n" +
            "  * Task{fe8483e #77 type=standard A=com.seagull.carlauncher/.MainActivity Uid=u0a241}\n" +
            "  Hist  #0: ActivityRecord{a01 com.seagull.carlauncher/.MainActivity t77}}\n" +
            "  * Task{12ab901 #60 type=standard A=com.foo.player/.Play Uid=u0a100}\n" +
            "Display #2 (virtual 258b0a6)\n" +
            "    Task id #88\n" +
            "      mDisplayId=2\n" +
            "  * Task{b0d4c05 #88 type=standard A=com.seagull.carlauncher/.MainActivity Uid=u0a241}\n" +
            "  hist  #-1: ActivityRecord{b02 com.seagull.carlauncher/.MainActivity t88}\n";

    // 扁平输出：Task 行自带 displayId=，以及 taskId= 形式
    private static final String FLAT =
            "* Task{aa11bb #99 type=standard A=com.foo.bar/.Main Uid=u0a100 displayId=3}\n" +
            "* Task{cc22dd taskId=123 type=standard A=com.taskid.form/.Main Uid=u0a101}\n" +
            "Task id #77\n";

    public static void main(String[] args) {
        // 1) 段头归属：主屏实例 vs 虚拟屏实例分得清
        check(TaskScan.findTaskId(DUMP, "com.seagull.carlauncher", 0) == 77, "display0 取主屏实例 77");
        check(TaskScan.findTaskId(DUMP, "com.seagull.carlauncher", 2) == 88, "display2 取虚拟屏实例 88");

        // 2) 不指定 display 取文件中第一个（文件序）
        check(TaskScan.findTaskId(DUMP, "com.seagull.carlauncher", -1) == 77, "不指定 display 取第一个 77");

        // 3) 严格性：包在但不在目标屏上，绝不回退到别的屏的实例
        check(TaskScan.findTaskId(DUMP, "com.foo.player", 2) == -1, "foo.player 不在 display2 -> -1");
        check(TaskScan.findTaskId(DUMP, "com.foo.player", 0) == 60, "foo.player 在 display0 -> 60");

        // 4) 段头之前的 task（dump 目录块）归 display 0
        check(TaskScan.findTaskId(DUMP, "com.android.launcher", 0) == 41, "段头前的 task 归 display0");

        // 5) 包不存在 / 空输入
        check(TaskScan.findTaskId(DUMP, "com.not.installed", -1) == -1, "没装 -> -1");
        check(TaskScan.findTaskId(null, "com.foo.bar", -1) == -1, "dump 为 null -> -1");
        check(TaskScan.findTaskId("", "com.foo.bar", -1) == -1, "dump 为空 -> -1");
        check(TaskScan.findTaskId(DUMP, null, -1) == -1, "pkg 为 null -> -1");
        check(TaskScan.findTaskId(DUMP, "", -1) == -1, "pkg 为空 -> -1");

        // 6) `Task id #77` 详情行不是任务行（没有 Task{）
        check(TaskScan.findTaskId("Task id #77\n  mDisplayId=0\n", "com.foo", -1) == -1, "Task id 行不认");

        // 7) 扁平输出：行内 displayId= 认（少数 ROM 带这个字段）
        check(TaskScan.findTaskId(FLAT, "com.foo.bar", 3) == 99, "行内 displayId=3 -> 99");
        check(TaskScan.findTaskId(FLAT, "com.foo.bar", 1) == -1, "displayId=3 的包不属于 display1");

        // 8) taskId= 形式也认
        check(TaskScan.findTaskId(FLAT, "com.taskid.form", -1) == 123, "taskId=123");

        System.out.println("DumpParseCheck OK (" + 14 + " 项)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("失败：" + what);
    }
}
