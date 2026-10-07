package de.robv.android.xposed;

/**
 * 签名桩：反射 hook 工具。
 */
public final class XposedHelpers {

    private XposedHelpers() { }

    public static XC_MethodHook findAndHookMethod(Class<?> clazz, String methodName,
            Object... parameterTypesAndCallback) {
        return null;
    }
}
