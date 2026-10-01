package com.leting.neteaseadapter;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * NetEase Cloud Music IoT PiP Adapter
 *
 * Problem: When NetEase Cloud Music IoT runs inside a VirtualDisplay (e.g. PIP2 area
 * in CarWithX), clicking on UI elements like album cover or song title triggers
 * startActivity() WITHOUT setLaunchDisplayId(), causing the new Activity to launch
 * on the default display (Display 0 = main car screen) instead of staying on the
 * VirtualDisplay where the user touched.
 *
 * Solution: Hook Context.startActivity() and Activity.startActivityForResult() to
 * inject ActivityOptions.setLaunchDisplayId() with the current display ID before
 * the activity is launched. This forces all new activities to open on the same
 * display the calling Activity is currently on.
 *
 * Target package: com.netease.cloudmusic.iot
 *
 * Key classes analyzed:
 * - com.netease.cloudmusic.audio.player.p (line 596-634): startActivity calls
 * - com.netease.cloudmusic.audio.player.IotRadioPlayerActivity: companion.a() startActivity
 * - com.netease.cloudmusic.audio.launch.IotRedirectActivity: startActivity redirect
 * - com.netease.cloudmusic.audio.player.podcast.IotPodcastPlayerActivity
 *
 * Hook points:
 * 1. Activity.startActivity(Intent)          -> redirect to startActivity(Intent, Bundle)
 * 2. Activity.startActivity(Intent, Bundle)   -> inject setLaunchDisplayId into Bundle
 * 3. Context.startActivity(Intent)            -> wrap with ActivityOptions
 * 4. Context.startActivity(Intent, Bundle)    -> inject setLaunchDisplayId into Bundle
 * 5. Activity.startActivityForResult(Intent, int, Bundle) -> inject display ID
 */
public class NetEasePiPAdapter implements IXposedHookLoadPackage {

    private static final String TAG = "NetEasePiPAdapter";
    private static final String TARGET_PACKAGE = "com.netease.cloudmusic.iot";

    // Activity classes that should be pinned to current display
    private static final String[] PINNED_ACTIVITIES = {
        "com.netease.cloudmusic.audio.player.IotPlayerActivity",
        "com.netease.cloudmusic.audio.player.IotRadioPlayerActivity",
        "com.netease.cloudmusic.audio.player.IotSceneRadioPlayerActivity",
        "com.netease.cloudmusic.audio.player.podcast.IotPodcastPlayerActivity",
        "com.netease.cloudmusic.audio.launch.IotRedirectActivity",
        "com.netease.cloudmusic.audio.login.IotLoginActivity",
        "com.netease.cloudmusic.audio.setting.IotDeveloperActivity"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": Loaded into " + lpparam.packageName);

        ClassLoader cl = lpparam.classLoader;

        // Hook 1: Activity.startActivity(Intent)
        // This is the simplest form - redirect to the Bundle variant with our display ID
        hookActivityStartActivityIntent(cl);

        // Hook 2: Activity.startActivity(Intent, Bundle)
        // Inject setLaunchDisplayId into the options Bundle
        hookActivityStartActivityIntentBundle(cl);

        // Hook 3: Context.startActivity(Intent)
        // For non-Activity contexts (Service, Application, etc.)
        hookContextStartActivityIntent(cl);

        // Hook 4: Context.startActivity(Intent, Bundle)
        // For non-Activity contexts with options
        hookContextStartActivityIntentBundle(cl);

        // Hook 5: Activity.startActivityForResult(Intent, int, Bundle)
        // Some paths use startActivityForResult internally
        hookActivityStartActivityForResult(cl);

        // Hook 6: Activity.startActivityForResult(Intent, int)
        // Without Bundle variant
        hookActivityStartActivityForResultNoBundle(cl);

        XposedBridge.log(TAG + ": All hooks installed successfully");
    }

    /**
     * Get the display ID that the calling Activity is currently on.
     * Returns -1 if we can't determine it (fallback: don't modify).
     */
    private int getCurrentDisplayId(Object thisObject) {
        if (thisObject instanceof Activity) {
            Activity activity = (Activity) thisObject;
            try {
                // API 17+: getDisplay()
                Display display = activity.getDisplay();
                if (display != null) {
                    int displayId = display.getDisplayId();
                    // Only pin if we're on a non-default display (VirtualDisplay)
                    if (displayId > 0) {
                        return displayId;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": getDisplay() failed: " + t);
            }

            // Fallback: try WindowManager
            try {
                WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    Display wmDisplay = wm.getDefaultDisplay();
                    if (wmDisplay != null && wmDisplay.getDisplayId() > 0) {
                        return wmDisplay.getDisplayId();
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": WindowManager fallback failed: " + t);
            }
        }
        return -1;
    }

    /**
     * Check if the intent targets an Activity that should be pinned to current display.
     */
    private boolean shouldPinIntent(Intent intent) {
        if (intent == null) return false;

        ComponentName component = intent.getComponent();
        if (component == null) {
            // Try resolve
            return true; // Pin all if we can't resolve
        }

        String className = component.getClassName();
        if (className == null) return false;

        // Pin if it's an Iot activity or any activity in the player package
        if (className.contains("Iot") || className.contains("Player")) {
            return true;
        }

        // Pin activities that match our explicit list
        for (String pinned : PINNED_ACTIVITIES) {
            if (className.equals(pinned)) {
                return true;
            }
        }

        // Also pin the main activity and other internal activities
        if (className.startsWith("com.netease.cloudmusic.")) {
            return true;
        }

        return false;
    }

    /**
     * Create or modify an options Bundle to include setLaunchDisplayId.
     */
    private Bundle injectDisplayId(Object thisObject, Intent intent, Bundle existingOptions) {
        int displayId = getCurrentDisplayId(thisObject);
        if (displayId < 0) {
            // We're on the default display, no need to modify
            return existingOptions;
        }

        if (!shouldPinIntent(intent)) {
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

            // Set the launch display ID to the current display
            options.setLaunchDisplayId(displayId);

            Bundle result = options.toBundle();
            XposedBridge.log(TAG + ": Injected setLaunchDisplayId=" + displayId +
                    " for " + (intent.getComponent() != null ? intent.getComponent().getClassName() : "unknown"));
            return result;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": injectDisplayId failed: " + t);
            return existingOptions;
        }
    }

    // ==================== Hook implementations ====================

    /**
     * Hook: Activity.startActivity(Intent)
     * Redirect to startActivity(Intent, Bundle) with our injected options.
     */
    private void hookActivityStartActivityIntent(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity.class, "startActivity", Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        Intent intent = (Intent) param.args[0];

                        int displayId = getCurrentDisplayId(activity);
                        if (displayId < 0 || !shouldPinIntent(intent)) {
                            return; // Don't interfere
                        }

                        Bundle options = injectDisplayId(activity, intent, null);
                        if (options != null) {
                            // Call startActivity(Intent, Bundle) with our options
                            activity.startActivity(intent, options);
                            param.setResult(null); // Prevent original call
                            XposedBridge.log(TAG + " [H1]: Redirected startActivity(Intent) with displayId=" + displayId);
                        }
                    }
                }
            );
            XposedBridge.log(TAG + " [H1]: Hooked Activity.startActivity(Intent)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [H1]: Failed: " + t);
        }
    }

    /**
     * Hook: Activity.startActivity(Intent, Bundle)
     * Inject setLaunchDisplayId into the options Bundle.
     */
    private void hookActivityStartActivityIntentBundle(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity.class, "startActivity", Intent.class, Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        Intent intent = (Intent) param.args[0];
                        Bundle originalOptions = (Bundle) param.args[1];

                        Bundle newOptions = injectDisplayId(activity, intent, originalOptions);
                        if (newOptions != null && newOptions != originalOptions) {
                            param.args[1] = newOptions;
                            XposedBridge.log(TAG + " [H2]: Injected displayId into startActivity(Intent, Bundle)");
                        }
                    }
                }
            );
            XposedBridge.log(TAG + " [H2]: Hooked Activity.startActivity(Intent, Bundle)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [H2]: Failed: " + t);
        }
    }

    /**
     * Hook: Context.startActivity(Intent)
     * For non-Activity contexts, we need to find the current display differently.
     */
    private void hookContextStartActivityIntent(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                Context.class, "startActivity", Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) param.thisObject;
                        Intent intent = (Intent) param.args[0];

                        // If this is actually an Activity, the Activity hook will handle it
                        if (context instanceof Activity) {
                            return;
                        }

                        // For non-Activity context, try to find display from WindowManager
                        int displayId = getDisplayIdFromContext(context);
                        if (displayId < 0 || !shouldPinIntent(intent)) {
                            return;
                        }

                        try {
                            ActivityOptions options = ActivityOptions.makeBasic();
                            options.setLaunchDisplayId(displayId);
                            context.startActivity(intent, options.toBundle());
                            param.setResult(null);
                            XposedBridge.log(TAG + " [H3]: Redirected Context.startActivity with displayId=" + displayId);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " [H3]: Redirect failed: " + t);
                        }
                    }
                }
            );
            XposedBridge.log(TAG + " [H3]: Hooked Context.startActivity(Intent)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [H3]: Failed: " + t);
        }
    }

    /**
     * Hook: Context.startActivity(Intent, Bundle)
     */
    private void hookContextStartActivityIntentBundle(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                Context.class, "startActivity", Intent.class, Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) param.thisObject;
                        if (context instanceof Activity) {
                            return; // Activity hook handles it
                        }

                        Intent intent = (Intent) param.args[0];
                        Bundle originalOptions = (Bundle) param.args[1];

                        int displayId = getDisplayIdFromContext(context);
                        if (displayId < 0 || !shouldPinIntent(intent)) {
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
                            XposedBridge.log(TAG + " [H4]: Injected displayId into Context.startActivity(Intent, Bundle)");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " [H4]: Inject failed: " + t);
                        }
                    }
                }
            );
            XposedBridge.log(TAG + " [H4]: Hooked Context.startActivity(Intent, Bundle)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [H4]: Failed: " + t);
        }
    }

    /**
     * Hook: Activity.startActivityForResult(Intent, int, Bundle)
     */
    private void hookActivityStartActivityForResult(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity.class, "startActivityForResult", Intent.class, int.class, Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        Intent intent = (Intent) param.args[0];
                        Bundle originalOptions = (Bundle) param.args[2];

                        Bundle newOptions = injectDisplayId(activity, intent, originalOptions);
                        if (newOptions != null && newOptions != originalOptions) {
                            param.args[2] = newOptions;
                            XposedBridge.log(TAG + " [H5]: Injected displayId into startActivityForResult");
                        }
                    }
                }
            );
            XposedBridge.log(TAG + " [H5]: Hooked Activity.startActivityForResult(Intent, int, Bundle)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [H5]: Failed: " + t);
        }
    }

    /**
     * Hook: Activity.startActivityForResult(Intent, int) - no Bundle variant
     */
    private void hookActivityStartActivityForResultNoBundle(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity.class, "startActivityForResult", Intent.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        Intent intent = (Intent) param.args[0];

                        int displayId = getCurrentDisplayId(activity);
                        if (displayId < 0 || !shouldPinIntent(intent)) {
                            return;
                        }

                        // Redirect to the 3-arg variant with our options
                        Bundle options = injectDisplayId(activity, intent, null);
                        if (options != null) {
                            int requestCode = (Integer) param.args[1];
                            activity.startActivityForResult(intent, requestCode, options);
                            param.setResult(null);
                            XposedBridge.log(TAG + " [H6]: Redirected startActivityForResult with displayId=" + displayId);
                        }
                    }
                }
            );
            XposedBridge.log(TAG + " [H6]: Hooked Activity.startActivityForResult(Intent, int)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [H6]: Failed: " + t);
        }
    }

    /**
     * Get display ID from a non-Activity Context (Service, Application, etc.)
     */
    private int getDisplayIdFromContext(Context context) {
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
            XposedBridge.log(TAG + ": getDisplayIdFromContext failed: " + t);
        }
        return -1;
    }
}
