package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 窗口能力自检 & 各项能力的手动验证入口。
 * 这一页是排障用的：能不能开悬浮窗、能不能建虚拟屏、root 在哪一档，全在这里看。
 */
public class WindowTestActivity extends BaseActivity {

    private static final int REQ_PROJECTION = 0x5EA1;

    private TextView report;
    private TextView log;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private View build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Skin.c(R.color.ground));
        col.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView back = new TextView(this);
        back.setText("← 返回桌面");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(16);
        back.setOnClickListener(v -> finish());
        col.addView(back);

        TextView t = new TextView(this);
        t.setText(R.string.window_test_title);
        t.setTextColor(Skin.c(R.color.text));
        t.setTextSize(22);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(8); tp.bottomMargin = dp(12);
        col.addView(t, tp);

        report = new TextView(this);
        report.setTextColor(Skin.c(R.color.text));
        report.setTextSize(13);
        report.setTypeface(android.graphics.Typeface.MONOSPACE);
        report.setBackground(Skin.round(Skin.c(R.color.panel), 12f));
        report.setPadding(dp(14), dp(12), dp(14), dp(12));
        col.addView(report);

        col.addView(btn("① 申请悬浮窗权限（L1 镜像卡片用）", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable e) { say("打开悬浮窗设置失败: " + e); }
        }));
        col.addView(btn("② 申请截屏授权（MediaProjection，L1 用）", v -> {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        }));
        col.addView(btn("③ 测试创建虚拟屏（探测特权档）", v -> testVirtualDisplay()));
        col.addView(btn("④ 测试 root 通道", v -> {
            new Thread(() -> {
                final boolean r = Caps.hasRoot();
                final String who = Caps.rootWho();
                runOnUiThread(() -> {
                    append("root = " + r + "  |  " + who);
                    refresh();
                });
            }).start();
        }));
        col.addView(btn("⑤ 刷新自检", v -> refresh()));

        TextView note = new TextView(this);
        note.setText("说明\n"
                + "· L0 基础桌面：无需任何权限，本页全部项目失败也能用。\n"
                + "· L1 悬浮镜像卡片：需要①悬浮窗 + ②截屏授权，不需要 root。\n"
                + "· L3 虚拟屏：目标应用被真正搬进虚拟屏，需要 uid=1000（系统应用）。\n"
                + "  普通应用即使建成虚拟屏，setLaunchDisplayId 也会被系统拒绝 —— 这是本机验证过的结论。\n"
                + "· 想上 L3，路径是：root 把本应用装进 /system/priv-app（或 LSPosed hook 掉校验）。");
        note.setTextColor(Skin.c(R.color.text_dim));
        note.setTextSize(12);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = dp(16);
        col.addView(note, np);

        log = new TextView(this);
        log.setTextColor(Skin.c(R.color.warn));
        log.setTextSize(12);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(12);
        col.addView(log, lp);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        return sv;
    }

    private View btn(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Skin.c(R.color.leaf));
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(14), dp(14), dp(14), dp(14));
        tv.setBackground(Skin.round(Skin.c(R.color.card), 12f));
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(10);
        tv.setLayoutParams(p);
        return tv;
    }

    private void refresh() {
        if (report != null) report.setText(Caps.report(this));
    }

    private void append(String s) {
        if (log != null) log.append("\n" + s);
    }

    private void say(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        append(s);
    }

    /* ------------------------- 虚拟屏探测 ------------------------- */

    private void testVirtualDisplay() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            VirtualDisplay vd = dm.createVirtualDisplay("seagull-test", 640, 360, 160, null,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC);
            if (vd == null) {
                say("createVirtualDisplay 返回 null —— 不可用");
                return;
            }
            int id = vd.getDisplay().getDisplayId();
            say("虚拟屏创建成功 displayId=" + id + "（能否把应用启动进去还需 uid=1000）");
            vd.release();
        } catch (Throwable e) {
            say("createVirtualDisplay 抛异常：" + e);
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PROJECTION) {
            if (res == RESULT_OK && data != null) {
                say("截屏授权 OK —— L1 镜像卡片可用");
            } else {
                say("截屏授权被拒绝 —— L1 不可用");
            }
        }
    }
}
