package com.seagull.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Calendar;

/**
 * 任务引擎 —— 13.1~13.10 的执行侧。
 *
 * 触发：桌面启动（HomeActivity 首次 onResume，一个进程只跑一次）
 *       系统启动（BootReceiver）
 *       定时（arm() 每分钟看一眼，到点就跑）
 * 动作：延迟 N 秒 / 打开应用 / 让目标应用进画中画 / 从主屏移除该应用。
 *
 * 任务本体在 LauncherModel.Task 里，随桌面配置一起存盘（13.10）。
 */
public final class TaskEngine {

    private static final String TAG = "SeagullTask";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean desktopFired = false;
    private static boolean armed = false;

    private TaskEngine() {}

    /** 桌面启动触发。同一个进程里只跑一次（回到桌面不算重启）。 */
    public static void fireDesktop(Context c) {
        if (desktopFired) return;
        desktopFired = true;
        fire(c, LauncherModel.TaskTrigger.DESKTOP_START);
    }

    public static void fireBoot(Context c) {
        fire(c, LauncherModel.TaskTrigger.SYSTEM_BOOT);
    }

    private static void fire(Context c, LauncherModel.TaskTrigger tr) {
        appCtx = c.getApplicationContext();
        LauncherModel m = new LauncherModel(c);
        for (LauncherModel.Task t : m.tasks) {
            if (!t.enabled || t.trigger != tr) continue;
            if (tr == LauncherModel.TaskTrigger.TIMER && !isDue(t)) continue;
            schedule(c, t);
        }
    }

    /** 每分钟检查一次定时任务，到分钟就跑一次。 */
    public static void arm(Context c) {
        if (c != null) appCtx = c.getApplicationContext();
        if (appCtx == null || armed) return;
        armed = true;
        MAIN.postDelayed(TIMER_TICK, 60_000L);
    }

    public static void disarm() {
        armed = false;
        MAIN.removeCallbacks(TIMER_TICK);
    }

    private static final Runnable TIMER_TICK = new Runnable() {
        @Override public void run() {
            armed = false;
            Context ctx = appCtx;
            if (ctx != null) {
                LauncherModel m = new LauncherModel(ctx);
                for (LauncherModel.Task t : m.tasks) {
                    if (!t.enabled || t.trigger != LauncherModel.TaskTrigger.TIMER) continue;
                    if (isDue(t)) schedule(ctx, t);
                }
            }
            arm(ctx);
        }
    };

    private static Context appCtx;

    /** TIMER 是否到点：atMin 就是当前这分钟。每分钟只触发一次，所以不记上次时间。 */
    private static boolean isDue(LauncherModel.Task t) {
        if (t.atMin < 0) return false;
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) == t.atMin;
    }

    private static void schedule(final Context c, final LauncherModel.Task t) {
        Log.i(TAG, "任务 " + t.name + "：" + t.describe());
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                try { TaskEngine.run(c, t); } catch (Throwable e) { Log.w(TAG, "任务失败", e); }
            }
        }, Math.max(0, t.delayMs));
    }

    private static void run(Context c, LauncherModel.Task t) {
        LauncherModel.TaskAction a = t.action;
        if (a == LauncherModel.TaskAction.DELAY) return;    // 纯延迟：只把后面的动作拖后
        if (t.pkg.isEmpty()) return;
        if (a == LauncherModel.TaskAction.OPEN_PIP) {
            openPip(c, t.pkg);
        } else if (a == LauncherModel.TaskAction.OPEN_APP) {
            launch(c, t.pkg);
        } else if (a == LauncherModel.TaskAction.REMOVE_FROM_HOME) {
            LauncherModel m = new LauncherModel(c);
            m.pinned.removeIf(k -> k != null && (k.equals(t.pkg) || k.startsWith(t.pkg + "/")));
            m.save();
            Log.i(TAG, "已从主屏移除 " + t.pkg);
        }
    }

    /**
     * 13.6 在画中画打开应用 —— Android 没有「让别人强制进 PiP」的 API，
     * 只能用官方的 extra：目标应用自己认这两个 extra 才会进。
     * 所以这一条对「支持画中画的应用」有效，对不支持的应用等于普通打开（矩阵里已注明）。
     */
    private static void openPip(Context c, String pkg) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { launch(c, pkg); return; }
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            android.app.PictureInPictureParams.Builder b =
                    new android.app.PictureInPictureParams.Builder();
            b.setAspectRatio(new android.util.Rational(16, 9));
            i.putExtra("android.support.picture_in_picture", true);
            i.putExtra("android.support.picture_in_picture_params", b.build());
            c.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "画中画打开失败，退回普通打开", t);
            launch(c, pkg);
        }
    }

    private static void launch(Context c, String pkg) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "打开应用失败 " + pkg, t);
        }
    }
}
