package com.seagull.carlauncher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自检：快捷栏「每个布局各有一份」的存取逻辑（LauncherModel.swapQuickbar）。
 *
 * 跑法（不依赖设备，android.jar 只在 classpath 上占位即可）：
 *   javac -cp app/libs/android.jar -sourcepath app/src -d /tmp/sc \
 *         app/selfcheck/QuickbarCheck.java
 *   java  -cp /tmp/sc:app/libs/android.jar com.seagull.carlauncher.QuickbarCheck
 */
public class QuickbarCheck {

    public static void main(String[] args) {
        List<LauncherModel.QuickSlot> cur = new ArrayList<>();
        Map<Integer, List<LauncherModel.QuickSlot>> store = new LinkedHashMap<>();

        // 布局 0 放两格
        cur.add(new LauncherModel.QuickSlot("a/1", "电话"));
        cur.add(new LauncherModel.QuickSlot("@fn:volume_up", "音量 +"));
        LauncherModel.swapQuickbar(cur, store, 0);
        check(store.get(0).size() == 2, "布局 0 存了两格");

        // 切到布局 1：先清空再读，读到的是空
        cur.clear();
        List<LauncherModel.QuickSlot> l1 = store.get(1);
        if (l1 != null) cur.addAll(l1);
        check(cur.isEmpty(), "布局 1 初始为空");

        // 布局 1 放一格后再切走
        cur.add(new LauncherModel.QuickSlot("b/1", "导航"));
        LauncherModel.swapQuickbar(cur, store, 1);

        // 切回布局 0：还是原来那两格，顺序不变
        cur.clear();
        cur.addAll(store.get(0));
        check(cur.size() == 2, "切回布局 0 有两格");
        check("a/1".equals(cur.get(0).key), "布局 0 第 1 格是电话");
        check("@fn:volume_up".equals(cur.get(1).key), "布局 0 第 2 格是音量 +");

        // 布局 1 独立保存
        check(store.get(1).size() == 1 && "b/1".equals(store.get(1).get(0).key),
                "布局 1 那份没被布局 0 覆盖");

        // 重复存同一布局不会叠加
        LauncherModel.swapQuickbar(cur, store, 0);
        check(store.get(0).size() == 2, "重复存布局 0 不叠加");

        System.out.println("QuickbarCheck OK (" + passed + " 项)");
    }

    private static int passed = 0;

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
        passed++;
        System.out.println("  ok - " + what);
    }
}
