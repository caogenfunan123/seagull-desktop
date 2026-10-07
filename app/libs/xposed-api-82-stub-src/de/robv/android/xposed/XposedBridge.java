package de.robv.android.xposed;

/**
 * 签名桩：日志与全局入口。
 */
public final class XposedBridge {

    private XposedBridge() { }

    public static void log(String text) { }

    public static void log(Throwable t) { }
}
