package com.example.wangyihook;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* loaded from: classes.dex */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "WangyiHook";
    private static final String TARGET_PKG = "com.netease.cloudmusic";
    private static final String TARGET_PKG_IOT = "com.netease.cloudmusic.iot";

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isPortrait(int i4) {
        return i4 == 1 || i4 == 7 || i4 == 9 || i4 == 12 || i4 == 14;
    }

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        String pkg = loadPackageParam.packageName;
        if (!TARGET_PKG.equals(pkg) && !TARGET_PKG_IOT.equals(pkg)) {
            return;
        }
        XposedBridge.log("WangyiHook: hooked " + loadPackageParam.packageName + " process=" + loadPackageParam.processName);
        try {
            Class findClass = XposedHelpers.findClass("android.app.Activity", loadPackageParam.classLoader);
            XposedHelpers.findAndHookMethod(findClass, "isInMultiWindowMode", new Object[]{new XC_MethodHook() { // from class: com.example.wangyihook.HookEntry.1
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    methodHookParam.setResult(false);
                }
            }});
            XposedHelpers.findAndHookMethod(findClass, "onMultiWindowModeChanged", new Object[]{Boolean.TYPE, Configuration.class, new XC_MethodHook() { // from class: com.example.wangyihook.HookEntry.2
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    methodHookParam.args[0] = false;
                }
            }});
            XposedHelpers.findAndHookMethod(findClass, "setRequestedOrientation", new Object[]{Integer.TYPE, new XC_MethodHook() { // from class: com.example.wangyihook.HookEntry.3
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    int intValue = ((Integer) methodHookParam.args[0]).intValue();
                    if (HookEntry.isPortrait(intValue) || intValue == -1) {
                        XposedBridge.log("WangyiHook: force landscape from " + intValue + " for " + methodHookParam.thisObject.getClass().getName());
                        methodHookParam.args[0] = 0;
                    }
                }
            }});
            XposedHelpers.findAndHookMethod(findClass, "getRequestedOrientation", new Object[]{new XC_MethodHook() { // from class: com.example.wangyihook.HookEntry.4
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    methodHookParam.setResult(0);
                }
            }});
            XposedHelpers.findAndHookMethod(findClass, "onCreate", new Object[]{Bundle.class, new XC_MethodHook() { // from class: com.example.wangyihook.HookEntry.5
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    Activity activity = (Activity) methodHookParam.thisObject;
                    XposedBridge.log("WangyiHook: onCreate force landscape for " + activity.getClass().getName());
                    activity.setRequestedOrientation(0);
                }
            }});
            XposedHelpers.findAndHookMethod(findClass, "onResume", new Object[]{new XC_MethodHook() { // from class: com.example.wangyihook.HookEntry.6
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    ((Activity) methodHookParam.thisObject).setRequestedOrientation(0);
                }
            }});
            try {
                XposedHelpers.findAndHookMethod(Activity.class, "setPictureInPictureParams", new Object[]{XposedHelpers.findClass("android.app.PictureInPictureParams", loadPackageParam.classLoader), new XC_MethodHook() { // from class: com.example.wangyihook.HookEntry.7
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                        XposedBridge.log("WangyiHook: intercept setPictureInPictureParams for " + methodHookParam.thisObject.getClass().getName());
                    }
                }});
            } catch (Throwable th) {
                XposedBridge.log("WangyiHook: PIP hook skipped: " + th.getMessage());
            }
            XposedBridge.log("WangyiHook: all hooks installed");
        } catch (Throwable th2) {
            XposedBridge.log("WangyiHook: hook error: " + Log.getStackTraceString(th2));
        }
    }
}
