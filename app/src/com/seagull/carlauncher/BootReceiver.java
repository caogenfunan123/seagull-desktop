package com.seagull.carlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机 / 更新后把桌面拉回前台（车机场景常用）。 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "SeagullBoot";

    @Override public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        Log.i(TAG, "onReceive " + a);
        if ("android.intent.action.MY_PACKAGE_REPLACED".equals(a)) {
            pull(ctx);
            return;
        }
        // autoHome 存在布局存档的 JSON 里（LauncherModel.save 的 K_LAYOUT），
        // 旧实现读顶层 SP 的 "autoHome" 键——模型从不写这个键，
        // 「开机回桌面」开关永远是默认 true（批次 T 复盘坐实）
        boolean auto = new LauncherModel(ctx, false).autoHome;
        if (auto) pull(ctx);
    }

    private void pull(Context ctx) {
        try {
            Intent i = new Intent(ctx, HomeActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "拉回桌面失败", t);
        }
        // 13.3 系统启动触发的任务
        try {
            TaskEngine.fireBoot(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "开机任务失败", t);
        }
        // 悬浮球跟着开机自启（依赖 LauncherModel 里的 ballEnabled）
        try {
            LauncherModel m = new LauncherModel(ctx, false);
            if (m.ballEnabled) BallService.setEnabled(ctx, true);
        } catch (Throwable t) {
            Log.w(TAG, "拉起悬浮球失败", t);
        }
    }
}
