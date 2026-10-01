package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.Log;

/**
 * L3 完整链路自检 —— 走真实授权：MediaProjection 建屏 → root 搬应用 → 核对 → 触摸注入。
 *
 * 第一步必须用户在系统弹框点「立即开始」（录屏授权是 Android 硬规定，无法绕过）。
 * 之后全自动，逐条结果打 logcat（tag=SeagullSelfTest）。
 */
public final class SelfTestMirror {

    private static final String TAG = "SeagullSelfTest";
    private static final int REQ = 0x5EA9;
    private static final String TARGET = "com.android.deskclock";

    private static final String[] FLAG_CANDIDATES = {
            "0x18800000",   // NON_DOCUMENT(0x08000000) | NEW_TASK —— 参考实现实测用的
            "0x18000000",   // MULTIPLE_TASK | NEW_TASK
            "0x10104000",   // 报告 §6 提到的另一组
    };

    public static void begin(final Activity act) {
        line("===== L3 全链路自检 =====");
        line("uid=" + android.os.Process.myUid() + " root=" + Caps.hasRoot()
                + " 投影服务=" + PipProjectionService.running);
        // 前置：放开录屏 appops（部分 ROM 能省掉弹框）
        boolean pm = RootOps.allowProjectMedia(act);
        line("appops PROJECT_MEDIA allow → " + pm);
        MediaProjectionManager mpm =
                (MediaProjectionManager) act.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            act.startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
            line("已弹出录屏授权框，请在设备上点「立即开始」…");
        } catch (Throwable t) {
            line("拉起授权框失败: " + t);
        }
    }

    public static boolean isOurRequest(int req) { return req == REQ; }

    /** 授权回来后的全自动流程。 */
    public static void onConsent(final Activity act, int code, Intent data) {
        new Thread(() -> {
            try {
                line("✔ 录屏授权 OK，开始全自动流程");
                PipProjectionService.start(act);
                int waited = 0;
                while (!PipProjectionService.running && waited < 3000) {
                    Thread.sleep(150); waited += 150;
                }
                line("投影前台服务 running=" + PipProjectionService.running + "（等了 " + waited + "ms）");

                MediaProjectionManager mpm = (MediaProjectionManager)
                        act.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                MediaProjection mp = mpm.getMediaProjection(code, data);
                line("getMediaProjection → " + (mp == null ? "null" : "OK"));
                if (mp == null) return;

                int w = 960, h = 540, dpi = 160;
                var vd = mp.createVirtualDisplay("seagull-l3test", w, h, dpi,
                        android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                                | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                                | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                        null, null, null);
                int id = vd == null ? -1 : vd.getDisplay().getDisplayId();
                line("① MediaProjection 建屏 → " + (vd == null ? "null" : "displayId=" + id));
                if (id <= 0) return;

                String comp = RootOps.resolveLauncher(act, TARGET);
                line("② 目标组件: " + comp);

                // 每次启动前彻底停掉目标，避免 intent 被打到主屏已有实例上
                Caps.exec("am force-stop " + TARGET);
                Thread.sleep(800);

                for (String flags : FLAG_CANDIDATES) {
                    line("── 试 flags=" + flags + " ──");
                    // 先停目标再启，保证是"冷启到指定屏"
                    Caps.exec("am force-stop " + TARGET);
                    Thread.sleep(600);
                    String out = Caps.exec("am start --display " + id + " -f " + flags + " -n " + comp);
                    line("   am 输出: " + flat(out));
                    Thread.sleep(2500);
                    boolean ok = taskOnDisplay(id, TARGET);
                    line("   核对 → " + (ok ? "✔ 目标已在 display " + id : "✘ 没落上去"));
                    if (ok) {
                        String tap = Caps.exec("input -d " + id + " tap 480 270");
                        line("③ 触摸注入 input -d " + id + " tap 480 270 → "
                                + (tap == null || tap.trim().isEmpty() ? "已下发(静默成功)" : flat(tap)));
                        Thread.sleep(1000);
                        line("④ 注入后仍在屏上: " + taskOnDisplay(id, TARGET));
                        break;
                    }
                }

                line("⑤ 最终核对：目标的 task 落在哪个 display");
                line("   " + whereIsTask(TARGET));
                line("⑥ 系统是否认可这块屏: " + displayEvidence(id));
                line("⑦ 屏幕内 task 概览（按 Display 分节）:");
                String dd = Caps.exec("dumpsys activity activities");
                if (dd != null) {
                    String sec = null;
                    for (String l : dd.split("\n")) {
                        String t = l.trim();
                        if (t.startsWith("Display #")) { sec = t; line("   " + t); }
                        else if (sec != null && t.startsWith("* Task{") && t.contains("type=standard")) {
                            line("      " + t.substring(0, Math.min(95, t.length())));
                        }
                    }
                }
                try { vd.release(); } catch (Throwable ignore) {}
                line("已释放测试屏");
            } catch (Throwable t) {
                line("异常: " + t);
            }
            line("===== 自检结束 =====");
        }, "l3-selftest").start();
    }

    /**
     * 精确核对：目标 task 到底在哪个 display。
     * dumpsys activity activities 的真实结构是按 Display 分节：
     *     Display #0 (activities from top to bottom):
     *       * Task{24b3633 #27648 type=standard A=10238:com.android.deskclock ...}
     * 【坑】task 行本身不含 displayId，必须靠 Display #N 分节来归属。
     */
    private static boolean taskOnDisplay(int displayId, String pkg) {
        return whereIsTask(pkg).startsWith("display=" + displayId);
    }

    /** 返回 "display=N task=###" 或诊断串。 */
    private static String whereIsTask(String pkg) {
        String dump = Caps.exec("dumpsys activity activities");
        if (dump == null) return "dumpsys 无回显";
        int cur = -1;
        for (String l : dump.split("\n")) {
            String t = l.trim();
            if (t.startsWith("Display #")) {
                java.util.regex.Matcher dm =
                        java.util.regex.Pattern.compile("Display #(\\d+)").matcher(t);
                if (dm.find()) cur = Integer.parseInt(dm.group(1));
                continue;
            }
            if (t.startsWith("* Task{") && t.contains(pkg)) {
                java.util.regex.Matcher tm =
                        java.util.regex.Pattern.compile("#(\\d+)").matcher(t);
                String taskId = tm.find() ? tm.group(1) : "?";
                return "display=" + cur + " task=" + taskId;
            }
        }
        // 没找到 → 报出当前到底有几块 Display 分节，便于判断"屏建没建"
        StringBuilder sb = new StringBuilder("未找到该包 task；当前 Display 分节: ");
        for (String l : dump.split("\n")) {
            String t = l.trim();
            if (t.startsWith("Display #")) sb.append(t.replace(" (activities from top to bottom):", "")).append("  ");
        }
        return sb.toString().trim();
    }

    /** 独立证据：系统到底认不认这块屏。 */
    private static String displayEvidence(int id) {
        String d = Caps.exec("dumpsys display");
        if (d == null) return "dumpsys display 无回显";
        StringBuilder sb = new StringBuilder();
        for (String l : d.split("\n")) {
            if (l.contains("mDisplayId=" + id) || l.contains("seagull-l3test")) {
                sb.append(l.trim()).append(" | ");
            }
        }
        return sb.length() == 0 ? "系统里查不到 display " + id : sb.toString().trim();
    }

    private static String flat(String s) {
        if (s == null) return "null";
        String x = s.trim().replace("\n", " | ");
        return x.length() > 200 ? x.substring(0, 200) : x;
    }

    private static void line(String s) { Log.i(TAG, s); }
}
