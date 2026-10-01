package com.carlink.desktop.svc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

/**
 * root 一键授权（0.2-m3）。
 *
 * 背景：桌面要用的权限里有两个是"普通应用申请不了、只能用户手动去系统页开"的
 * （悬浮窗 SYSTEM_ALERT_WINDOW、通知监听 NotificationListener）。root 环境下
 * 完全可以用 shell 直接授予，不该再让用户点四个按钮（0.2-m2 的真机批评）。
 *
 * 设计约束：
 * - 全部在后台线程调用；su 探测 3 秒超时，拒绝/超时按无 root 处理，界面回退手动按钮。
 * - 逐条命令独立记录成败：`pm grant` 在权限已授予时部分 ROM 返回非零，
 *   这种"非关键失败"不能把整次操作判成失败 —— 只有 appops 与监听写入两条是关键。
 * - 通知监听先 get 再拼接追加写回：直接覆盖会清掉其他 App 的监听。
 *   settings put 走 root 写入同样会触发系统侧 ContentObserver 自动重绑，无需额外广播。
 */
object RootAuth {

    private const val TAG = "CarLinkRoot"

    class Result(val keyOk: Boolean, val lines: List<String>)

    /** su 是否可用（Magisk / KernelSU / APatch 均支持 stdin 会话）。仅后台线程调用。 */
    fun suAvailable(): Boolean = runCmds(listOf("id"), 3_000).first

    /**
     * 一键授权。返回逐条结果行，供诊断区直接显示。仅后台线程调用（最长约 15s）。
     */
    fun grantAll(ctx: Context): Result {
        val pkg = ctx.packageName
        val listener = ComponentName(pkg, "com.carlink.desktop.media.CarMediaListenerService")
            .flattenToShortString()   // "com.carlink.desktop/.media.CarMediaListenerService"

        // 第一步：授予悬浮窗 + 读取现有通知监听列表（appops 无输出，get 的输出即列表）
        val (okA, outA) = runCmds(
            listOf(
                "appops set $pkg SYSTEM_ALERT_WINDOW allow",
                "settings get secure enabled_notification_listeners",
            ), 6_000
        )
        val existing = outA.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != "null" && it.contains('/') } ?: ""
        val merged = if (existing.contains(listener)) existing
                     else if (existing.isEmpty()) listener else "$existing:$listener"

        // 第二步：写回监听列表（关键）+ 兜底授权（非关键，失败只记录不判负）
        val (okB, outB) = runCmds(
            listOf(
                "settings put secure enabled_notification_listeners $merged",
                "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            ), 6_000
        )

        val lines = listOf(
            "悬浮窗 appops: ${if (okA) "OK" else "FAIL"}",
            "通知监听写入: ${if (okB) "OK" else "FAIL"}${if (outB.isNotBlank() && !okB) "（${outB.take(120)}）" else ""}",
            if (existing.contains(listener)) "监听列表: 已包含，跳过追加" else "监听列表: 已追加 $listener",
        )
        Log.i(TAG, "grantAll keyOk=${okA && okB}")
        return Result(okA && okB, lines)
    }

    /**
     * 放开录屏 appops，让 MediaProjection 授权页能出现/直接放行（镜像画中画的前置）。
     * Android 14 起 getMediaProjection 前必须有一次系统授权；先用 root 把
     * PROJECT_MEDIA 置 allow，减少弹窗被拒导致的"点了没画面"。仅后台线程调用。
     */
    fun allowProjectMedia(pkg: String): Boolean =
        runCmds(listOf("appops set $pkg PROJECT_MEDIA allow"), 4_000).first

    /**
     * 静默执行单条 root 命令（触摸注入、appops 之类，不关心输出，只要不抛异常）。
     * 触摸注入对时延敏感，这里超时给到 3s 且绝不阻塞调用线程太久。仅后台线程调用。
     */
    fun runQuiet(cmd: String) {
        runCmds(listOf(cmd), 3_000)
    }

    /**
     * 把目标 App 起进指定虚拟屏（复刻车联助手 c1/h 的完整三级链，0.2-m8 第 3 轮补全）。
     *
     * 参考版实测（c1/h.smali 行号可复查）有三条路，m7 我们只抄了第 2 条：
     *   ① API 主路（h.d 1191-1210）：ActivityOptions.makeBasic().setLaunchDisplayId(id)
     *      + context.startActivity(intent, bundle)，日志 "Deployed with Android API"。
     *      javap 核实 setLaunchDisplayId 在 android-34 是【public API】，我们完全能走，
     *      且零 su 开销。
     *   ② root 兜底（h.b）：am start --display <id> -f <flags> -n <comp>。
     *   ③ 锚点+move-task（h.d 1516+，第 4 轮做）。
     *
     * 【flags 解码 —— 本轮最重要的修正】参考版：
     *   目标 App 用 0x10104000 = NEW_TASK|LAUNCHED_FROM_HISTORY|TASK_ON_HOME
     *   锚点用     0x18800000 = NEW_TASK|MULTIPLE_TASK|EXCLUDE_FROM_RECENTS
     * m7 我们给目标也用了 0x10104000 —— 这正是真机"点网易云会跳转到网易云音乐（全屏）"
     * 的根因：网易云是 singleTask，主屏已有它的任务时，不带 MULTIPLE_TASK 的启动
     * 会把【主屏那个任务】拉到前台，而不是在虚拟屏新建。加 MULTIPLE_TASK 后，
     * 系统在虚拟屏新建独立任务（镜像实例），主屏任务不受影响。
     * 代价：目标 App 可能双实例占内存 —— 这正是参考版用锚点+move-task 想避免的，
     * 第 4 轮再评估。
     * 仅后台线程调用。
     */
    fun launchOnDisplay(ctx: Context, pkg: String, displayId: Int): Boolean {
        val comp = resolveLauncher(ctx, pkg)
            ?: run { Log.w(TAG, "launchOnDisplay: 找不到 $pkg 的启动组件"); return false }
        // ① API 主路：不依赖 root，成功即返回
        if (launchViaApi(ctx, comp, displayId)) {
            Log.i(TAG, "launchOnDisplay[$pkg] ok via Android API -> display $displayId")
            return true
        }
        Log.w(TAG, "launchOnDisplay[$pkg] API denied; trying root am start")
        // ② root 兜底：MULTIPLE_TASK 强制在虚拟屏新建任务（见上方 flags 解码）
        val (ok, out) = runCmds(
            listOf("am start --display $displayId -f 0x18800000 -n $comp"), 6_000)
        Log.i(TAG, "launchOnDisplay $comp -> display $displayId ok=$ok ${out.take(140)}")
        return ok
    }

    /**
     * 参考版 h.d 的 API 主路等价实现。SecurityException/ActivityNotFound 一律 false
     * 落 root 兜底（部分 ROM 限制跨屏 startActivity）。
     */
    private fun launchViaApi(ctx: Context, comp: String, displayId: Int): Boolean = try {
        val cn = ComponentName.unflattenFromString(comp)
        if (cn == null) {
            Log.w(TAG, "launchViaApi: 组件名解析失败 $comp")
            false
        } else {
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(displayId)
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(cn)
                    // NEW_TASK|MULTIPLE_TASK|EXCLUDE_FROM_RECENTS，与 root 路同一套 flags
                    .addFlags(0x18800000),
                opts.toBundle())
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "launchViaApi failed: ${t.message}")
        false
    }

    /** 解析包的主启动组件，返回 "pkg/Activity"；API resolve 优先，root resolve-activity 兜底。 */
    private fun resolveLauncher(ctx: Context, pkg: String): String? {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.let {
            return it.flattenToShortString()
        }
        val (ok, out) = runCmds(
            listOf("cmd package resolve-activity --brief $pkg"), 4_000)
        if (!ok) return null
        // 输出末两行形如 "priority=0 ... packageName/com.pkg.MainActivity"，取含 '/' 的最后一行
        return out.lineSequence().map { it.trim() }
            .lastOrNull { it.isNotEmpty() && it.contains('/') }
    }

    /** 一个 su 会话里顺序执行多条命令；返回 (退出码为0, 合并输出)。异常一律按失败。 */
    private fun runCmds(cmds: List<String>, timeoutMs: Long): Pair<Boolean, String> = try {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        DataOutputStream(p.outputStream).use { os ->
            cmds.forEach { os.writeBytes(it + "\n") }
            os.writeBytes("exit\n")
            os.flush()
        }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            false to "su 超时（授权弹窗可能被拒绝）"
        } else {
            (p.exitValue() == 0) to out
        }
    } catch (t: Throwable) {
        Log.w(TAG, "su failed: ${t.message}")
        false to (t.message ?: "su 不可用")
    }

    /**
     * 读当前系统里我们的虚拟屏是否真的存在（诊断用）。车联助手排查"点了没画面"的
     * 第一步就是数屏幕（b1/c 构造器日志 "Display id: … name: …"）。接管生效后应能
     * 看到 carlink-pip1 / carlink-pip2；少一块 → 对应槽部署失败（看 MirrorSlot 日志）。
     * 仅后台线程调用。
     */
    fun readDisplays(): String {
        val (ok, out) = runCmds(
            listOf("dumpsys display | grep -oE 'carlink-pip[0-9]+' | sort -u"), 3_000)
        if (!ok) return "读取失败（su 不可用/超时）"
        val names = out.trim().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return if (names.isEmpty()) "未见虚拟屏（未部署/已释放）" else names.joinToString(" ")
    }
}
