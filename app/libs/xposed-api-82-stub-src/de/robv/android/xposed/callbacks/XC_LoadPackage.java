package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/**
 * 签名桩：handleLoadPackage 的参数载体。
 */
public class XC_LoadPackage extends XCallback {

    public XC_LoadPackage() { }

    public static final class LoadPackageParam extends XCallback {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
