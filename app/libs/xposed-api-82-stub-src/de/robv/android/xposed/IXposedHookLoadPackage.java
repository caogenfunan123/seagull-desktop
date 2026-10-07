package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 签名桩：应用进程加载入口。LSPosed 读 assets/xposed_init 实例化实现类。
 */
public interface IXposedHookLoadPackage extends IXposedMod {

    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
