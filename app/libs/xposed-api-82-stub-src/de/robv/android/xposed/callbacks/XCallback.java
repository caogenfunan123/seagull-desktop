package de.robv.android.xposed.callbacks;

/**
 * XposedBridge API 签名桩（仅编译期用，运行时由 LSPosed 提供真实实现）。
 * 与上游 XposedBridge api-82 的公开签名保持一致，只保留本模块用到的面。
 */
public abstract class XCallback {

    public XCallback() { }

    public XCallback(int priority) { }
}
