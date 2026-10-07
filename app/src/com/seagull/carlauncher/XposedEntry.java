package com.seagull.carlauncher;

import android.app.Activity;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口（批次 W）：被 LSPosed 装进目标应用（高德/音乐类）进程里运行，
 * 把目标运行时的竖屏请求改写成横屏。
 *
 * 为什么必须有它：画中画链路里的 NEVER_FIX_ORIENTATION 只能解锁 manifest 里的
 * screenOrientation 声明，挡不住应用自己在运行时调 setRequestedOrientation(PORTRAIT)
 * —— 高德就是这么干的，结果画布（横形 VD）里塞进竖屏窗口，按比例居中，
 * 两侧全是黑边（真机截图坐实）。想强制横屏只能进应用进程改写调用，即 LSP 模块。
 *
 * 本类必须自包含：它跑在目标进程里，由 LSPosed 的 classloader 加载本 APK，
 * 主项目的其他类（Skin/LauncherModel 等）在目标进程里不存在，禁止引用。
 * de.robv.* 由 LSPosed 运行时提供，编译期用的是 app/libs 里的签名桩
 * （xposed-api-82-stub-src，签名与上游 api-82 一致）。
 *
 * 启用步骤：装本 APK → LSPosed 管理器里启用「海鸥桌面」模块（作用域已按
 * xposed_scope 预填）→ 强杀目标应用进程（高德等）让它重启加载 hook。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "SeagullLsp";

    /** ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE：横屏，且认左右两个方向。 */
    private static final int SENSOR_LANDSCAPE = 6;

    /** 默认作用域，与 res/values/arrays.xml 的 xposed_scope 保持一致。 */
    private static final String[] TARGETS = {
            "com.autonavi.minimap",    // 高德地图
            "com.baidu.BaiduMap",      // 百度地图
            "com.tencent.map",         // 腾讯地图
            "com.netease.cloudmusic",  // 网易云音乐
            "com.tencent.qqmusic",     // QQ 音乐
    };

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) {
        if (!hit(lpp.packageName)) return;
        XposedBridge.log(TAG + ": 已挂载 " + lpp.packageName + " → 强制横屏");

        // 1) 目标运行时发起的方向请求一律改写成横屏。
        //    高德 onCreate/onResume 里锁竖屏的调用都会经过这里。
        XposedHelpers.findAndHookMethod(Activity.class, "setRequestedOrientation",
                int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int want = (Integer) param.args[0];
                        if (want != SENSOR_LANDSCAPE) param.args[0] = SENSOR_LANDSCAPE;
                    }
                });

        // 2) 每个 Activity 创建完先强拉一次横屏，保证第一帧就是横的。
        //    这次调用会再进 hook 1，参数已是目标值，原样放行，无递归。
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate",
                Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            ((Activity) param.thisObject).setRequestedOrientation(
                                    SENSOR_LANDSCAPE);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": onCreate 拉横屏失败 " + t);
                        }
                    }
                });
    }

    private static boolean hit(String pkg) {
        for (String t : TARGETS) {
            if (t.equals(pkg)) return true;
        }
        return false;
    }
}
