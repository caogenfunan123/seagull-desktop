package de.robv.android.xposed;

import java.lang.reflect.Member;

import de.robv.android.xposed.callbacks.XCallback;

/**
 * 签名桩：方法 hook 回调。真实类由 LSPosed 运行时提供。
 */
public abstract class XC_MethodHook extends XCallback {

    public XC_MethodHook() { }

    public XC_MethodHook(int priority) { }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable { }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable { }

    public static final class MethodHookParam extends XCallback {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public boolean returnEarly;
    }

    public class Unhook {
        public void unhook() { }
    }
}
