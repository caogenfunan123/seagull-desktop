package com.seagull.carlauncher;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Size;
import android.util.Rational;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口：两职责合一。
 *
 * 1) SystemUI 进程：hook PipBoundsAlgorithm，把画中画窗口缩放到屏短边的 60%。
 *    这是从 CarPlay 原版（xphook）移植的核心逻辑——不建虚拟屏、不搬应用，
 *    直接控制系统 PiP 窗口尺寸。
 *
 * 2) 目标应用进程（高德/百度/腾讯/网易云/QQ音乐）：强制横屏 + 自动进 PiP。
 *    用户按 Home 离开应用时，onUserLeaveHint 里自动调 enterPictureInPictureMode。
 *
 * 本类自包含：跑在目标进程里，不引用主项目其他类。
 * de.robv.* 由 LSPosed 运行时提供。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "SeagullLsp";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /** ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE */
    private static final int SENSOR_LANDSCAPE = 6;

    /** PiP 窗口缩放比例：屏短边的 60% */
    private static final float TARGET_SIZE_PERCENT = 0.6f;

    /** 强制横屏 + 自动 PiP 的目标应用 */
    private static final String[] TARGETS = {
            "com.autonavi.minimap",    // 高德地图
            "com.baidu.BaiduMap",      // 百度地图
            "com.tencent.map",         // 腾讯地图
            "com.netease.cloudmusic",  // 网易云音乐
            "com.tencent.qqmusic",     // QQ 音乐
    };

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) {
        String pkg = lpp.packageName;

        // ---- SystemUI：PiP 尺寸缩放 ----
        if (SYSTEMUI_PACKAGE.equals(pkg)) {
            XposedBridge.log(TAG + ": 已挂载 SystemUI → PiP 尺寸缩放 60%");
            hookSystemUiPip(lpp.classLoader);
            return;
        }

        // ---- 目标应用：强制横屏 + 自动 PiP ----
        if (!hit(pkg)) return;
        XposedBridge.log(TAG + ": 已挂载 " + pkg + " → 强制横屏 + 自动 PiP");

        // 1) 运行时方向请求一律改横屏
        XposedHelpers.findAndHookMethod(Activity.class, "setRequestedOrientation",
                int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int want = (Integer) param.args[0];
                        if (want != SENSOR_LANDSCAPE) param.args[0] = SENSOR_LANDSCAPE;
                    }
                });

        // 2) Activity onCreate 后强拉横屏
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

        // 3) 用户按 Home 离开应用时自动进系统画中画
        //    这是从 VirtualDisplay 路线切回系统 PiP 路线的关键一环。
        //    应用清单需声明 supportsPictureInPicture=true 才会生效；
        //    不支持的会被 try/catch 吞掉，不影响正常退出。
        XposedHelpers.findAndHookMethod(Activity.class, "onUserLeaveHint",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Activity activity = (Activity) param.thisObject;
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
                            if (!activity.getPackageManager().hasSystemFeature(
                                    android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
                                return;
                            PictureInPictureParams.Builder b =
                                    new PictureInPictureParams.Builder();
                            b.setAspectRatio(new Rational(16, 9));
                            activity.enterPictureInPictureMode(b.build());
                            XposedBridge.log(TAG + ": 自动进 PiP " +
                                    activity.getClass().getName());
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": 进 PiP 失败（应用可能未声明 "
                                    + "supportsPictureInPicture）" + t);
                        }
                    }
                });
    }

    // ===================== SystemUI PiP 尺寸缩放 =====================
    // 以下 5 个 hook 从 CarPlay 原版 xphook 移植，控制系统的 PiP 窗口尺寸。
    // 原理：当任意应用进入 PiP 时，SystemUI 调 PipBoundsAlgorithm 算窗口大小；
    // 我们 hook 这些计算方法，把结果放大到屏短边的 60%。

    private void hookSystemUiPip(ClassLoader cl) {
        hookSetBoundsStateForEntry(cl);
        hookGetSizeForAspectRatioFloat(cl);
        hookGetSizeForAspectRatioSize(cl);
        hookAdjustSizeToAspectRatio(cl);
        hookTransformBoundsToAspectRatio(cl);
    }

    /** hook 1：记录当前进 PiP 的包名（用于后续判断是否要缩放）。 */
    private void hookSetBoundsStateForEntry(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.wm.shell.pip.PipBoundsState", cl,
                    "setBoundsStateForEntry",
                    ComponentName.class, ActivityInfo.class,
                    PictureInPictureParams.class,
                    "com.android.wm.shell.pip.PipBoundsAlgorithm",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            ComponentName cn = (ComponentName) param.args[0];
                            if (cn != null) {
                                CurrentPip.set(cn.getPackageName());
                                XposedBridge.log(TAG + ": PiP entry " +
                                        cn.getPackageName());
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hookSetBoundsStateForEntry OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookSetBoundsStateForEntry 失败 " + t);
        }
    }

    /** hook 2：getSizeForAspectRatio(float...) 重载，缩放结果。 */
    private void hookGetSizeForAspectRatioFloat(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.wm.shell.pip.PipBoundsAlgorithm", cl,
                    "getSizeForAspectRatio",
                    float.class, float.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!shouldScale(param.thisObject)) return;
                            Size orig = (Size) param.getResult();
                            if (orig == null) return;
                            int displayShort = Math.min(
                                    (Integer) param.args[2],
                                    (Integer) param.args[3]);
                            Size scaled = scaleToTarget(orig, displayShort);
                            if (scaled != null) {
                                param.setResult(scaled);
                                XposedBridge.log(TAG + ": getSizeForAspectRatio(f) " +
                                        orig.getWidth() + "x" + orig.getHeight() +
                                        " -> " + scaled.getWidth() + "x" +
                                        scaled.getHeight());
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hookGetSizeForAspectRatioFloat OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookGetSizeForAspectRatioFloat 失败 " + t);
        }
    }

    /** hook 3：getSizeForAspectRatio(Size...) 重载，缩放结果。 */
    private void hookGetSizeForAspectRatioSize(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.wm.shell.pip.PipBoundsAlgorithm", cl,
                    "getSizeForAspectRatio",
                    Size.class, float.class, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!shouldScale(param.thisObject)) return;
                            Size orig = (Size) param.getResult();
                            if (orig == null) return;
                            int displayShort = getDisplayShort(param.thisObject);
                            if (displayShort <= 0) return;
                            Size scaled = scaleToTarget(orig, displayShort);
                            if (scaled != null) {
                                param.setResult(scaled);
                                XposedBridge.log(TAG + ": getSizeForAspectRatio(S) " +
                                        orig.getWidth() + "x" + orig.getHeight() +
                                        " -> " + scaled.getWidth() + "x" +
                                        scaled.getHeight());
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hookGetSizeForAspectRatioSize OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookGetSizeForAspectRatioSize 失败 " + t);
        }
    }

    /** hook 4：adjustSizeToAspectRatio，缩放结果。 */
    private void hookAdjustSizeToAspectRatio(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.wm.shell.pip.PipBoundsAlgorithm", cl,
                    "adjustSizeToAspectRatio",
                    Size.class, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!shouldScale(param.thisObject)) return;
                            Size orig = (Size) param.getResult();
                            if (orig == null) return;
                            int displayShort = getDisplayShort(param.thisObject);
                            if (displayShort <= 0) return;
                            Size scaled = scaleToTarget(orig, displayShort);
                            if (scaled != null) {
                                param.setResult(scaled);
                                XposedBridge.log(TAG + ": adjustSizeToAspectRatio " +
                                        orig.getWidth() + "x" + orig.getHeight() +
                                        " -> " + scaled.getWidth() + "x" +
                                        scaled.getHeight());
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hookAdjustSizeToAspectRatio OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookAdjustSizeToAspectRatio 失败 " + t);
        }
    }

    /** hook 5：transformBoundsToAspectRatio，记录日志。 */
    private void hookTransformBoundsToAspectRatio(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.wm.shell.pip.PipBoundsAlgorithm", cl,
                    "transformBoundsToAspectRatio",
                    Rect.class, float.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (shouldScale(param.thisObject)) {
                                XposedBridge.log(TAG + ": transformBounds " +
                                        param.args[0]);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hookTransformBoundsToAspectRatio OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookTransformBoundsToAspectRatio 失败 " + t);
        }
    }

    // ===================== 辅助方法 =====================

    /** 判断当前 PiP 是否需要缩放（有包名就缩，不限定高德）。 */
    private static boolean shouldScale(Object obj) {
        if (CurrentPip.get() != null) return true;
        try {
            Object state = XposedHelpers.getObjectField(obj, "mPipBoundsState");
            if (state == null) return false;
            Object cn = XposedHelpers.callMethod(state,
                    "getLastPipComponentName");
            if (cn instanceof ComponentName) {
                String pkg = ((ComponentName) cn).getPackageName();
                CurrentPip.set(pkg);
                return true;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": shouldScale 失败 " + t);
        }
        return false;
    }

    /** 从 PipBoundsState 取屏短边。 */
    private static int getDisplayShort(Object obj) {
        try {
            Object state = XposedHelpers.getObjectField(obj, "mPipBoundsState");
            if (state == null) return 0;
            Object layout = XposedHelpers.callMethod(state, "getDisplayLayout");
            if (layout == null) return 0;
            int w = (Integer) XposedHelpers.callMethod(layout, "width");
            int h = (Integer) XposedHelpers.callMethod(layout, "height");
            return Math.min(w, h);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getDisplayShort 失败 " + t);
            return 0;
        }
    }

    /** 把系统算出的 PiP 尺寸缩放到屏短边的 60%。 */
    private static Size scaleToTarget(Size orig, int displayShort) {
        if (orig == null || displayShort <= 0) return null;
        int target = Math.round(displayShort * TARGET_SIZE_PERCENT);
        int minSide = Math.min(orig.getWidth(), orig.getHeight());
        if (minSide <= 0 || target <= minSide) return null;
        float ratio = (float) target / minSide;
        return new Size(
                Math.round(orig.getWidth() * ratio),
                Math.round(orig.getHeight() * ratio));
    }

    /** 当前 PiP 包名追踪（volatile 保证跨线程可见）。 */
    private static class CurrentPip {
        private static volatile String sPackageName;

        static void set(String pkg) { sPackageName = pkg; }
        static String get() { return sPackageName; }
    }

    private static boolean hit(String pkg) {
        for (String t : TARGETS) {
            if (t.equals(pkg)) return true;
        }
        return false;
    }
}
