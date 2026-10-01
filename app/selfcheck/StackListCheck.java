package com.seagull.carlauncher;

/**
 * 自检：StackScan 的 `am stack list` 解析（目标自愈的唯一判据，错一格就会误重拉）。
 *
 * 跑法（不依赖设备，纯 JVM）：
 *   javac -d /tmp/stksc app/src/com/seagull/carlauncher/StackScan.java app/selfcheck/StackListCheck.java
 *   java  -ea -cp /tmp/stksc com.seagull.carlauncher.StackListCheck
 */
public class StackListCheck {

    // 现场取材：网易云在主屏（task 25）和虚拟屏（task 31）各有一个任务
    private static final String OUT =
            "Stack id=1\n" +
            " displayId=0\n" +                                  // 段头（独立成行）
            "  stackId=1\n" +
            "    taskId=25 mLastOrientation=0\n" +
            "      *TaskRecord{a01 #25 ... displayId=0}\n" +   // Task 行里也有 displayId= 字样，但不分段
            "      topActivity=ComponentInfo{com.netease.cloudmusic/.MainActivity}\n" +
            "  stackId=2\n" +
            "    taskId=26 mLastOrientation=0\n" +
            "      topActivity=ComponentInfo{com.android.launcher/com.android.launcher2.Launcher}\n" +
            " displayId=2\n" +
            "  stackId=7 mDisplayId=2 bounds=[0,0][1280,640]\n" +   // stack 行里也含 displayId= 字样
            "    taskId=31 mLastOrientation=2\n" +
            "      *TaskRecord{b02 #31 ... }\n" +
            "      topActivity=ComponentInfo{com.netease.cloudmusic/.MainActivity}\n";

    private static final String BARE =   // 没有 stackId 的极简输出
            "displayId=0\n" +
            "  taskId=8 com.foo.bar\n" +
            "displayId=2\n" +
            "  taskId=9 com.foo.bar\n";

    public static void main(String[] args) {
        // 1) 分段：两段，各含各的 task
        java.util.Map<Integer, String> seg = StackScan.segments(OUT);
        check(seg.size() == 2 && seg.containsKey(0) && seg.containsKey(2), "两段 0/2");
        check(StackScan.isRunningOnDisplay(OUT, "com.netease.cloudmusic", 0), "display0 有网易云");
        check(StackScan.isRunningOnDisplay(OUT, "com.netease.cloudmusic", 2), "display2 有网易云");
        check(!StackScan.isRunningOnDisplay(OUT, "com.android.launcher", 2), "display2 没有 launcher");
        check(!StackScan.isRunningOnDisplay(OUT, "com.not.here", 0), "没装的包判否");

        // 2) 找任务：主屏取 25/stack1，虚拟屏取 31/stack7
        StackScan.TaskRef a = StackScan.findTaskOnDisplay(OUT, "com.netease.cloudmusic", 0);
        StackScan.TaskRef b = StackScan.findTaskOnDisplay(OUT, "com.netease.cloudmusic", 2);
        check(a != null && a.taskId == 25 && a.stackId == 1, "主屏 task=25 stack=1");
        check(b != null && b.taskId == 31 && b.stackId == 7, "虚拟屏 task=31 stack=7");
        check(StackScan.findTaskOnDisplay(OUT, "com.not.here", 0) == null, "目标不在 -> null");

        // 3) 首任务：搬回目的 rootTask
        StackScan.TaskRef f0 = StackScan.firstTaskOnDisplay(OUT, 0);
        StackScan.TaskRef f2 = StackScan.firstTaskOnDisplay(OUT, 2);
        check(f0 != null && f0.taskId == 25, "display0 首任务 25");
        check(f2 != null && f2.taskId == 31, "display2 首任务 31");

        // 4) 没有 stackId 的极简输出也认
        check(StackScan.isRunningOnDisplay(BARE, "com.foo.bar", 2), "极简输出 display2");
        StackScan.TaskRef fb = StackScan.findTaskOnDisplay(BARE, "com.foo.bar", 0);
        check(fb != null && fb.taskId == 8 && fb.stackId == -1, "极简输出 task=8 stack=-1");

        // 5) 空输入的兜底
        check(StackScan.segments(null).isEmpty(), "null -> 空段");
        check(StackScan.segments("").isEmpty(), "空串 -> 空段");
        check(StackScan.segments("displayId=0\n  mSomething=1\n").containsKey(0), "只有段头也成段");
        check(!StackScan.isRunningOnDisplay("displayId=0\n", "com.foo", 0), "空段判否");
        check(StackScan.firstTaskOnDisplay("displayId=5\n  stackId=1\n  taskId=abc\n", 5) == null,
                "taskId= 后面不是数字 -> 跳过");

        System.out.println("StackListCheck OK (" + 12 + " 项)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("失败：" + what);
    }
}
