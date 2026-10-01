package com.carlink.desktop.lsp

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * 车机桌面的配套 LSPosed 模块 —— 只改「框架层窗口语义」，不碰任何第三方 App 的内部类。
 *
 * 为什么限定在框架层：导航类 App 的混淆方案每年都在变，一旦去 Hook 它自己的
 * 业务类/字段，版本一升级就全盘失效，而且失效是静默的（没报错，只是没数据）。
 * android.app.Activity 上这几个方法是公开 SDK，签名多年未变，所以这里只挂它们。
 *
 *   H1 如实上报多窗口状态：isInMultiWindowMode()
 *      不少 App 在非多窗口时拒绝按小尺寸重新布局。桌面把它缩成卡片后，
 *      这里按"窗口是否真的小于物理屏"返回，逼它走分屏重布局那条分支。
 *
 *   H2 方向劫持：setRequestedOrientation() / getRequestedOrientation()
 *      导航 App 常把自己锁成 landscape/portrait，一锁就把整个车机桌面的方向带跑。
 *      这里把它的锁屏请求换成桌面配置的方向。
 *
 *   H3 修正画中画参数：setPictureInPictureParams() / onPictureInPictureRequested()
 *      有些 App 传进极小的 sourceRectHint，小窗里只渲染出一角；统一换成整屏。
 *      另外把"请求进入 PiP"直接判定为允许，桌面才好把它收进角落。
 *
 * 明确不做：强制自由窗口（要改 WindowManagerService，不是 Activity 层能干净做到的）、
 * 目标 App 的业务数据提取（导航数据由主 App 走公开广播 / MediaSession 拿）、
 * 输入注入（真触摸镜像走 root `input -d <displayId>`，在主 App 侧）。
 *
 * 开关：persist.carlink.wf = 1(默认)/0；persist.carlink.wf.orientation = 0跟随/1横(默认)/2竖
 */
class WFreeHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpp: LoadPackageParam) {
        val pkg = lpp.packageName
        if (pkg == HOST_PACKAGE) return

        if (!enabled()) {
            Log.i(TAG, "disabled by $PROP_ENABLE, skip $pkg")
            return
        }

        try {
            // H1~H3 是"窗口语义配合"（多窗口谎报/方向劫持/PiP 参数修正），
            // 只在已知目标清单里装，避免波及用户没打算接管的 App。
            if (SCOPES.contains(pkg)) {
                Log.i(TAG, "installing window hooks into $pkg")
                hookMultiWindowPretence()
                hookOrientationOverride()
                hookPictureInPicture()
            }
            // 0.2-m7：画中画接管【不再靠 hook ActivityOptions.getBounds】。
            // 复盘结论（对齐车联助手 b1/b.smali 实测）：接管层是 MediaProjection
            // VirtualDisplay + SurfaceView 输出 + root am start --display + input -d，
            // 全部在主 App（com.carlink.desktop）侧完成，目标进程根本不需要被改窗口尺寸
            // ——目标在虚拟屏里就是"整屏"，画面被渲染进桌面区域，尺寸天然被 VD 锁死。
            // 之前 m4~m6 在目标进程 hook getBounds 之所以完全无效：ActivityOptions 是
            // 桌面（launcher）构造的，改目标进程里的 getter 改不动 launcher 已经定好的窗口。
        } catch (t: Throwable) {
            // 某个 ROM 上方法签名不同，也不该连带让目标 App 崩
            Log.e(TAG, "hook setup failed for $pkg: ${Log.getStackTraceString(t)}")
        }
    }

    // ---------------- H1 多窗口状态 ----------------

    private fun hookMultiWindowPretence() {
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "isInMultiWindowMode",
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any {
                    val a = param.thisObject as Activity
                    // 系统已判定为 PiP 时直接 true —— 不能再调 windowIsSmallerThanScreen，
                    // 它经由 decorView 度量可能再次触发本方法，形成 hook 递归
                    return if (a.isInPictureInPictureMode) true
                    else windowIsSmallerThanScreen(a)
                }
            }
        )
    }

    /**
     * 只有窗口确实被缩小时才谎报多窗口，全屏使用时保持 false ——
     * 否则会误伤目标 App 自己的全屏布局决策。
     * Display.getRealMetrics 拿物理屏尺寸，不受分屏影响；decorView 拿当前窗口实际尺寸。
     */
    private fun windowIsSmallerThanScreen(a: Activity): Boolean = try {
        val display = a.windowManager.defaultDisplay
        val real = DisplayMetrics()
        display.getRealMetrics(real)
        val view = a.window?.peekDecorView()
        if (view == null) {
            // 窗口尚未建立，此时不谎报，返回真实的"非多窗口"
            false
        } else {
            val margin = (24 * a.resources.displayMetrics.density).toInt()
            view.width < real.widthPixels - margin || view.height < real.heightPixels - margin
        }
    } catch (t: Throwable) {
        false
    }

    // ---------------- H2 方向劫持 ----------------

    private fun hookOrientationOverride() {
        val target = desiredOrientation()
        if (target == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return

        XposedHelpers.findAndHookMethod(
            Activity::class.java, "setRequestedOrientation", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args[0] as Int
                    if (req == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return
                    if (req == target) return
                    param.args[0] = target
                    Log.d(TAG, "orientation ${orName(req)} -> ${orName(target)}")
                }
            }
        )

        // 有的 App 先读回自己锁的方向再决定布局，让它读到修正后的值
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "getRequestedOrientation",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cur = param.result as? Int ?: return
                    if (cur != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) param.result = target
                }
            }
        )
    }

    // ---------------- H3 画中画 ----------------

    private fun hookPictureInPicture() {
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "setPictureInPictureParams", PictureInPictureParams::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val fixed = fullScreenParams(param.thisObject as Activity) ?: return
                    param.args[0] = fixed
                }
            }
        )

        // API 26+ 的 App 侧"是否允许进入 PiP"回调，直接放行
        if (Build.VERSION.SDK_INT >= 26) {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onPictureInPictureRequested",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val a = param.thisObject as Activity
                        // setPictureInPictureParams 的形参是【非空】的，null 不能直接传进去
                        val p = fullScreenParams(a) ?: return true
                        runCatching { a.setPictureInPictureParams(p) }
                        return true
                    }
                }
            )
        }
    }

    /** sourceRectHint 铺满整屏，避免小窗只裁出一角；比例交给系统按 16:9~2.39:1 合法区间收敛 */
    private fun fullScreenParams(a: Activity): PictureInPictureParams? = try {
        val dm = a.resources.displayMetrics
        PictureInPictureParams.Builder()
            .setSourceRectHint(Rect(0, 0, dm.widthPixels, dm.heightPixels))
            .setAspectRatio(Rational(dm.widthPixels, dm.heightPixels))
            .build()
    } catch (t: Throwable) {
        null
    }

    // ---------------- 配置 ----------------

    private fun enabled(): Boolean = prop(PROP_ENABLE, "1") == "1"

    private fun desiredOrientation(): Int = when (prop(PROP_ORIENTATION, "1")) {
        "2" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        "0" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    /** SystemProperties 不在公开 SDK 里，只能反射；读失败一律按默认值走 */
    private fun prop(key: String, def: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("get", String::class.java, String::class.java)
        m.invoke(null, key, def) as String
    } catch (t: Throwable) {
        def
    }

    private fun propInt(key: String, def: Int): Int = prop(key, def.toString()).toIntOrNull() ?: def

    private fun orName(v: Int) = when (v) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "landscape"
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "portrait"
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED -> "unspecified"
        else -> "0x" + v.toString(16)
    }

    companion object {
        private const val TAG = "CarLinkWF"
        private const val HOST_PACKAGE = "com.carlink.desktop"
        private const val PROP_ENABLE = "persist.carlink.wf"
        private const val PROP_ORIENTATION = "persist.carlink.wf.orientation"
        // 0.2-m7 删除 persist.carlink.pip.* / pip2.* 全部常量：那是 setprop+getBounds
        // 假方案的残留，接管已改为主 App 侧 VirtualDisplay 镜像，prop 链路整体退役。

        /**
         * 作用域包名。新增目标加一行即可 —— 这里刻意不写任何第三方内部类名，
         * 那是本模块能长期不坏的根本原因。实际生效范围仍以用户在 LSPosed 里的勾选为准。
         * 对齐车联助手双画中画：画中画1=高德、画中画2=网易云（NETEASE_PACKAGE）。
         */
        private val SCOPES = setOf(
            "com.autonavi.amapauto",
            "com.autonavi.minimap",
            "com.netease.cloudmusic",
            "com.netease.cloudmusic.iot",
        )
    }
}
