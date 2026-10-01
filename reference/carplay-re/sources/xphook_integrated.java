package com.leting.xposed;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Size;
import android.view.Display;
import android.view.WindowManager;
import com.baidu.mobstat.Config;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* loaded from: classes.dex */
public class xphook implements IXposedHookLoadPackage {
    public static final String AMAP_PACKAGE = "com.autonavi.minimap";
    public static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    public static final String NETEASE_PACKAGE = "com.netease.cloudmusic.iot";
    public static final String TAG = "AmapPipHook";
    public static final String NETEASE_TAG = "NetEasePipAdapter";
    public static final float TARGET_SIZE_PERCENT = 0.6f;

    // NetEase Cloud Music IoT activities that should be pinned to current display
    private static final String[] NETEASE_PINNED_ACTIVITIES = {
        "com.netease.cloudmusic.audio.player.IotPlayerActivity",
        "com.netease.cloudmusic.audio.player.IotRadioPlayerActivity",
        "com.netease.cloudmusic.audio.player.IotSceneRadioPlayerActivity",
        "com.netease.cloudmusic.audio.player.podcast.IotPodcastPlayerActivity",
        "com.netease.cloudmusic.audio.launch.IotRedirectActivity",
        "com.netease.cloudmusic.audio.login.IotLoginActivity",
        "com.netease.cloudmusic.audio.setting.IotDeveloperActivity"
    };

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        String str = loadPackageParam.packageName;
        if ("com.miui.carlink".equals(str) || "com.baidu.carlife.xiaomi".equals(str) || "com.samsung.android.carlink".equals(str)) {
            XposedBridge.log("CarWithX LSPosed API102 loaded: " + str);
        }
        if (SYSTEMUI_PACKAGE.equals(str)) {
            XposedBridge.log("AmapPipHook handleLoadPackage: " + str);
            hookAmapPip(loadPackageParam.classLoader);
            XposedBridge.log("AmapPipHook loaded in " + str);
        }
        // NetEase Cloud Music IoT: inject setLaunchDisplayId to prevent PIP touch jump
        if (NETEASE_PACKAGE.equals(str)) {
            XposedBridge.log(NETEASE_TAG + ": handleLoadPackage " + str);
            hookNeteasePip();
            XposedBridge.log(NETEASE_TAG + ": loaded in " + str);
        }
    }

    private void hookAmapPip(ClassLoader classLoader) {
        hookSetBoundsStateForEntry(classLoader);
        hookGetSizeForAspectRatioFloat(classLoader);
        hookGetSizeForAspectRatioSize(classLoader);
        hookAdjustSizeToAspectRatio(classLoader);
        hookTransformBoundsToAspectRatio(classLoader);
    }

    private void hookSetBoundsStateForEntry(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.android.wm.shell.pip.PipBoundsState", classLoader, "setBoundsStateForEntry", new Object[]{ComponentName.class, ActivityInfo.class, PictureInPictureParams.class, "com.android.wm.shell.pip.PipBoundsAlgorithm", new XC_MethodHook() { // from class: com.leting.xposed.xphook.1
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    ComponentName componentName = (ComponentName) methodHookParam.args[0];
                    if (componentName != null) {
                        CurrentPip.set(componentName.getPackageName());
                        XposedBridge.log("AmapPipHook PiP entry: " + componentName.getPackageName());
                        return;
                    }
                    XposedBridge.log("AmapPipHook PiP entry: null ComponentName");
                }
            }});
            XposedBridge.log("AmapPipHook hookSetBoundsStateForEntry OK");
        } catch (Throwable th) {
            XposedBridge.log("AmapPipHook hookSetBoundsStateForEntry failed: " + th);
        }
    }

    private void hookGetSizeForAspectRatioFloat(ClassLoader classLoader) {
        try {
            XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.leting.xposed.xphook.2
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    Size size;
                    Size scaleToTarget;
                    if (!xphook.isAmapPip(methodHookParam.thisObject) || (size = (Size) methodHookParam.getResult()) == null || (scaleToTarget = xphook.scaleToTarget(size, Math.min(((Integer) methodHookParam.args[2]).intValue(), ((Integer) methodHookParam.args[3]).intValue()))) == null) {
                        return;
                    }
                    methodHookParam.setResult(scaleToTarget);
                    XposedBridge.log("AmapPipHook getSizeForAspectRatio(float) " + size.getWidth() + Config.EVENT_HEAT_X + size.getHeight() + " -> " + scaleToTarget.getWidth() + Config.EVENT_HEAT_X + scaleToTarget.getHeight());
                }
            };
            Class cls = Float.TYPE;
            Class cls2 = Integer.TYPE;
            XposedHelpers.findAndHookMethod("com.android.wm.shell.pip.PipBoundsAlgorithm", classLoader, "getSizeForAspectRatio", new Object[]{cls, cls, cls2, cls2, xC_MethodHook});
            XposedBridge.log("AmapPipHook hookGetSizeForAspectRatioFloat OK");
        } catch (Throwable th) {
            XposedBridge.log("AmapPipHook hookGetSizeForAspectRatio(float...) failed: " + th);
        }
    }

    private void hookGetSizeForAspectRatioSize(ClassLoader classLoader) {
        try {
            XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.leting.xposed.xphook.3
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    Size size;
                    int displayShort;
                    Size scaleToTarget;
                    if (xphook.isAmapPip(methodHookParam.thisObject) && (size = (Size) methodHookParam.getResult()) != null && (displayShort = xphook.getDisplayShort(methodHookParam.thisObject)) > 0 && (scaleToTarget = xphook.scaleToTarget(size, displayShort)) != null) {
                        methodHookParam.setResult(scaleToTarget);
                        XposedBridge.log("AmapPipHook getSizeForAspectRatio(Size) " + size.getWidth() + Config.EVENT_HEAT_X + size.getHeight() + " -> " + scaleToTarget.getWidth() + Config.EVENT_HEAT_X + scaleToTarget.getHeight());
                    }
                }
            };
            Class cls = Float.TYPE;
            XposedHelpers.findAndHookMethod("com.android.wm.shell.pip.PipBoundsAlgorithm", classLoader, "getSizeForAspectRatio", new Object[]{Size.class, cls, cls, xC_MethodHook});
            XposedBridge.log("AmapPipHook hookGetSizeForAspectRatioSize OK");
        } catch (Throwable th) {
            XposedBridge.log("AmapPipHook hookGetSizeForAspectRatio(Size...) failed: " + th);
        }
    }

    private void hookAdjustSizeToAspectRatio(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.android.wm.shell.pip.PipBoundsAlgorithm", classLoader, "adjustSizeToAspectRatio", new Object[]{Size.class, Float.TYPE, new XC_MethodHook() { // from class: com.leting.xposed.xphook.4
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    Size size;
                    int displayShort;
                    Size scaleToTarget;
                    if (xphook.isAmapPip(methodHookParam.thisObject) && (size = (Size) methodHookParam.getResult()) != null && (displayShort = xphook.getDisplayShort(methodHookParam.thisObject)) > 0 && (scaleToTarget = xphook.scaleToTarget(size, displayShort)) != null) {
                        methodHookParam.setResult(scaleToTarget);
                        XposedBridge.log("AmapPipHook adjustSizeToAspectRatio " + size.getWidth() + Config.EVENT_HEAT_X + size.getHeight() + " -> " + scaleToTarget.getWidth() + Config.EVENT_HEAT_X + scaleToTarget.getHeight());
                    }
                }
            }});
            XposedBridge.log("AmapPipHook hookAdjustSizeToAspectRatio OK");
        } catch (Throwable th) {
            XposedBridge.log("AmapPipHook hookAdjustSizeToAspectRatio failed: " + th);
        }
    }

    private void hookTransformBoundsToAspectRatio(ClassLoader classLoader) {
        try {
            XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.leting.xposed.xphook.5
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    if (xphook.isAmapPip(methodHookParam.thisObject)) {
                        XposedBridge.log("AmapPipHook transformBoundsToAspectRatio input: " + ((Rect) methodHookParam.args[0]));
                    }
                }
            };
            Class cls = Boolean.TYPE;
            XposedHelpers.findAndHookMethod("com.android.wm.shell.pip.PipBoundsAlgorithm", classLoader, "transformBoundsToAspectRatio", new Object[]{Rect.class, Float.TYPE, cls, cls, xC_MethodHook});
            XposedBridge.log("AmapPipHook hookTransformBoundsToAspectRatio OK");
        } catch (Throwable th) {
            XposedBridge.log("AmapPipHook hookTransformBoundsToAspectRatio failed: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isAmapPip(Object obj) {
        Object objectField;
        if (CurrentPip.isAmap()) {
            return true;
        }
        try {
            objectField = XposedHelpers.getObjectField(obj, "mPipBoundsState");
        } catch (Throwable th) {
            XposedBridge.log("AmapPipHook isAmapPip failed: " + th);
        }
        if (objectField == null) {
            return false;
        }
        Object callMethod = XposedHelpers.callMethod(objectField, "getLastPipComponentName", new Object[0]);
        if (callMethod instanceof ComponentName) {
            String packageName = ((ComponentName) callMethod).getPackageName();
            CurrentPip.set(packageName);
            return AMAP_PACKAGE.equals(packageName);
        }
        return false;
    }

    static int getDisplayShort(Object obj) {
        Object callMethod;
        try {
            Object objectField = XposedHelpers.getObjectField(obj, "mPipBoundsState");
            if (objectField == null || (callMethod = XposedHelpers.callMethod(objectField, "getDisplayLayout", new Object[0])) == null) {
                return 0;
            }
            return Math.min(((Integer) XposedHelpers.callMethod(callMethod, "width", new Object[0])).intValue(), ((Integer) XposedHelpers.callMethod(callMethod, "height", new Object[0])).intValue());
        } catch (Throwable th) {
            XposedBridge.log("AmapPipHook getDisplayShort failed: " + th);
            return 0;
        }
    }

    static Size scaleToTarget(Size size, int i4) {
        if (size == null || i4 <= 0) {
            return null;
        }
        int round = Math.round(i4 * 0.6f);
        int min = Math.min(size.getWidth(), size.getHeight());
        if (min <= 0 || round <= min) {
            return null;
        }
        float f4 = round / min;
        return new Size(Math.round(size.getWidth() * f4), Math.round(size.getHeight() * f4));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class CurrentPip {
        private static volatile String sPackageName;

        CurrentPip() {
        }

        static void set(String str) {
            sPackageName = str;
        }

        static boolean isAmap() {
            return xphook.AMAP_PACKAGE.equals(sPackageName);
        }
    }

    // ==================== NetEase Cloud Music IoT PiP Adapter ====================
    // Problem: NetEase Cloud Music IoT calls startActivity() without setLaunchDisplayId(),
    // causing new Activities to launch on Display 0 (main screen) instead of the
    // VirtualDisplay where PIP2 is running. This makes PIP2 touch jump to the music app.
    // Solution: Hook all startActivity/startActivityForResult variants to inject
    // ActivityOptions.setLaunchDisplayId() with the current display ID.

    private void hookNeteasePip() {
        hookNeteaseActivityStartActivityIntent();
        hookNeteaseActivityStartActivityIntentBundle();
        hookNeteaseContextStartActivityIntent();
        hookNeteaseContextStartActivityIntentBundle();
        hookNeteaseActivityStartActivityForResult();
        hookNeteaseActivityStartActivityForResultNoBundle();
    }

    /**
     * Get the display ID that the calling Activity is currently on.
     * Returns -1 if on default display (no injection needed).
     */
    private int getNeteaseCurrentDisplayId(Object thisObject) {
        if (thisObject instanceof Activity) {
            Activity activity = (Activity) thisObject;
            try {
                Display display = activity.getDisplay();
                if (display != null) {
                    int displayId = display.getDisplayId();
                    if (displayId > 0) {
                        return displayId;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(NETEASE_TAG + ": getDisplay() failed: " + t);
            }
            // Fallback: WindowManager
            try {
                WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    Display wmDisplay = wm.getDefaultDisplay();
                    if (wmDisplay != null && wmDisplay.getDisplayId() > 0) {
                        return wmDisplay.getDisplayId();
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(NETEASE_TAG + ": WindowManager fallback failed: " + t);
            }
        }
        return -1;
    }

    /**
     * Check if the intent targets an Activity that should be pinned to current display.
     */
    private boolean shouldPinNeteaseIntent(Intent intent) {
        if (intent == null) return false;
        ComponentName component = intent.getComponent();
        if (component == null) {
            return true; // Pin all if we can't resolve
        }
        String className = component.getClassName();
        if (className == null) return false;
        // Pin Iot/Player activities
        if (className.contains("Iot") || className.contains("Player")) {
            return true;
        }
        // Pin explicit list
        for (String pinned : NETEASE_PINNED_ACTIVITIES) {
            if (className.equals(pinned)) {
                return true;
            }
        }
        // Pin all internal NetEase activities
        if (className.startsWith("com.netease.cloudmusic.")) {
            return true;
        }
        return false;
    }

    /**
     * Create or modify an options Bundle to include setLaunchDisplayId.
     */
    private Bundle injectNeteaseDisplayId(Object thisObject, Intent intent, Bundle existingOptions) {
        int displayId = getNeteaseCurrentDisplayId(thisObject);
        if (displayId < 0) {
            return existingOptions; // On default display, no need to modify
        }
        if (!shouldPinNeteaseIntent(intent)) {
            return existingOptions;
        }
        try {
            ActivityOptions options;
            if (existingOptions != null) {
                options = ActivityOptions.fromBundle(existingOptions);
                if (options == null) {
                    options = ActivityOptions.makeBasic();
                }
            } else {
                options = ActivityOptions.makeBasic();
            }
            options.setLaunchDisplayId(displayId);
            Bundle result = options.toBundle();
            XposedBridge.log(NETEASE_TAG + ": Injected setLaunchDisplayId=" + displayId +
                    " for " + (intent.getComponent() != null ? intent.getComponent().getClassName() : "unknown"));
            return result;
        } catch (Throwable t) {
            XposedBridge.log(NETEASE_TAG + ": injectDisplayId failed: " + t);
            return existingOptions;
        }
    }

    /**
     * Get display ID from a non-Activity Context (Service, Application, etc.)
     */
    private int getNeteaseDisplayIdFromContext(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                if (display != null) {
                    int displayId = display.getDisplayId();
                    if (displayId > 0) {
                        return displayId;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(NETEASE_TAG + ": getDisplayIdFromContext failed: " + t);
        }
        return -1;
    }

    /**
     * Hook 1: Activity.startActivity(Intent)
     * Redirect to startActivity(Intent, Bundle) with injected display ID.
     */
    private void hookNeteaseActivityStartActivityIntent() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity.class, "startActivity", Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        Intent intent = (Intent) param.args[0];
                        int displayId = getNeteaseCurrentDisplayId(activity);
                        if (displayId < 0 || !shouldPinNeteaseIntent(intent)) {
                            return;
                        }
                        Bundle options = injectNeteaseDisplayId(activity, intent, null);
                        if (options != null) {
                            activity.startActivity(intent, options);
                            param.setResult(null);
                            XposedBridge.log(NETEASE_TAG + " [H1]: Redirected startActivity(Intent) with displayId=" + displayId);
                        }
                    }
                }
            );
            XposedBridge.log(NETEASE_TAG + " [H1]: Hooked Activity.startActivity(Intent)");
        } catch (Throwable t) {
            XposedBridge.log(NETEASE_TAG + " [H1]: Failed: " + t);
        }
    }

    /**
     * Hook 2: Activity.startActivity(Intent, Bundle)
     * Inject setLaunchDisplayId into the options Bundle.
     */
    private void hookNeteaseActivityStartActivityIntentBundle() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity.class, "startActivity", Intent.class, Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        Intent intent = (Intent) param.args[0];
                        Bundle originalOptions = (Bundle) param.args[1];
                        Bundle newOptions = injectNeteaseDisplayId(activity, intent, originalOptions);
                        if (newOptions != null && newOptions != originalOptions) {
                            param.args[1] = newOptions;
                            XposedBridge.log(NETEASE_TAG + " [H2]: Injected displayId into startActivity(Intent, Bundle)");
                        }
                    }
                }
            );
            XposedBridge.log(NETEASE_TAG + " [H2]: Hooked Activity.startActivity(Intent, Bundle)");
        } catch (Throwable t) {
            XposedBridge.log(NETEASE_TAG + " [H2]: Failed: " + t);
        }
    }

    /**
     * Hook 3: Context.startActivity(Intent)
     * For non-Activity contexts (Service, Application, etc.)
     */
    private void hookNeteaseContextStartActivityIntent() {
        try {
            XposedHelpers.findAndHookMethod(
                Context.class, "startActivity", Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) param.thisObject;
                        if (context instanceof Activity) {
                            return; // Activity hook handles it
                        }
                        Intent intent = (Intent) param.args[0];
                        int displayId = getNeteaseDisplayIdFromContext(context);
                        if (displayId < 0 || !shouldPinNeteaseIntent(intent)) {
                            return;
                        }
                        try {
                            ActivityOptions options = ActivityOptions.makeBasic();
                            options.setLaunchDisplayId(displayId);
                            context.startActivity(intent, options.toBundle());
                            param.setResult(null);
                            XposedBridge.log(NETEASE_TAG + " [H3]: Redirected Context.startActivity with displayId=" + displayId);
                        } catch (Throwable t) {
                            XposedBridge.log(NETEASE_TAG + " [H3]: Redirect failed: " + t);
                        }
                    }
                }
            );
            XposedBridge.log(NETEASE_TAG + " [H3]: Hooked Context.startActivity(Intent)");
        } catch (Throwable t) {
            XposedBridge.log(NETEASE_TAG + " [H3]: Failed: " + t);
        }
    }

    /**
     * Hook 4: Context.startActivity(Intent, Bundle)
     */
    private void hookNeteaseContextStartActivityIntentBundle() {
        try {
            XposedHelpers.findAndHookMethod(
                Context.class, "startActivity", Intent.class, Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) param.thisObject;
                        if (context instanceof Activity) {
                            return;
                        }
                        Intent intent = (Intent) param.args[0];
                        Bundle originalOptions = (Bundle) param.args[1];
                        int displayId = getNeteaseDisplayIdFromContext(context);
                        if (displayId < 0 || !shouldPinNeteaseIntent(intent)) {
                            return;
                        }
                        try {
                            ActivityOptions options;
                            if (originalOptions != null) {
                                options = ActivityOptions.fromBundle(originalOptions);
                                if (options == null) {
                                    options = ActivityOptions.makeBasic();
                                }
                            } else {
                                options = ActivityOptions.makeBasic();
                            }
                            options.setLaunchDisplayId(displayId);
                            param.args[1] = options.toBundle();
                            XposedBridge.log(NETEASE_TAG + " [H4]: Injected displayId into Context.startActivity(Intent, Bundle)");
                        } catch (Throwable t) {
                            XposedBridge.log(NETEASE_TAG + " [H4]: Inject failed: " + t);
                        }
                    }
                }
            );
            XposedBridge.log(NETEASE_TAG + " [H4]: Hooked Context.startActivity(Intent, Bundle)");
        } catch (Throwable t) {
            XposedBridge.log(NETEASE_TAG + " [H4]: Failed: " + t);
        }
    }

    /**
     * Hook 5: Activity.startActivityForResult(Intent, int, Bundle)
     */
    private void hookNeteaseActivityStartActivityForResult() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity.class, "startActivityForResult", Intent.class, int.class, Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        Intent intent = (Intent) param.args[0];
                        Bundle originalOptions = (Bundle) param.args[2];
                        Bundle newOptions = injectNeteaseDisplayId(activity, intent, originalOptions);
                        if (newOptions != null && newOptions != originalOptions) {
                            param.args[2] = newOptions;
                            XposedBridge.log(NETEASE_TAG + " [H5]: Injected displayId into startActivityForResult");
                        }
                    }
                }
            );
            XposedBridge.log(NETEASE_TAG + " [H5]: Hooked Activity.startActivityForResult(Intent, int, Bundle)");
        } catch (Throwable t) {
            XposedBridge.log(NETEASE_TAG + " [H5]: Failed: " + t);
        }
    }

    /**
     * Hook 6: Activity.startActivityForResult(Intent, int) - no Bundle variant
     */
    private void hookNeteaseActivityStartActivityForResultNoBundle() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity.class, "startActivityForResult", Intent.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        Intent intent = (Intent) param.args[0];
                        int displayId = getNeteaseCurrentDisplayId(activity);
                        if (displayId < 0 || !shouldPinNeteaseIntent(intent)) {
                            return;
                        }
                        Bundle options = injectNeteaseDisplayId(activity, intent, null);
                        if (options != null) {
                            int requestCode = (Integer) param.args[1];
                            activity.startActivityForResult(intent, requestCode, options);
                            param.setResult(null);
                            XposedBridge.log(NETEASE_TAG + " [H6]: Redirected startActivityForResult with displayId=" + displayId);
                        }
                    }
                }
            );
            XposedBridge.log(NETEASE_TAG + " [H6]: Hooked Activity.startActivityForResult(Intent, int)");
        } catch (Throwable t) {
            XposedBridge.log(NETEASE_TAG + " [H6]: Failed: " + t);
        }
    }
}
