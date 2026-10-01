package com.seagull.carlauncher;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * root 功能面板 —— 每条操作都是真实执行（su -c），结果显示在下方日志里。
 * 没有 root 时全部显示为不可用并给出原因，不做假成功。
 */
public class RootPanelActivity extends Activity {

    private TextView env, log;
    private final StringBuilder buf = new StringBuilder();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        if (getIntent() != null && getIntent().getBooleanExtra("selftest", false)) SelfTest.run(this);
        if (getIntent() != null && getIntent().getBooleanExtra("l3test", false)) SelfTestL3.run(this);
    }

    @Override protected void onResume() {
        super.onResume();
        env.setText(SysOps.envInfo(this));
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(getColor(R.color.ground));
        col.setPadding(dp(20), dp(16), dp(20), dp(24));

        TextView back = new TextView(this);
        back.setText("← 返回桌面");
        back.setTextColor(getColor(R.color.leaf));
        back.setTextSize(16);
        back.setOnClickListener(v -> finish());
        col.addView(back);

        TextView t = new TextView(this);
        t.setText("root 功能");
        t.setTextColor(getColor(R.color.text));
        t.setTextSize(22);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(8); tp.bottomMargin = dp(10);
        col.addView(t, tp);

        env = new TextView(this);
        env.setTextColor(getColor(R.color.text_dim));
        env.setTextSize(12);
        env.setTypeface(Typeface.MONOSPACE);
        env.setBackgroundColor(getColor(R.color.panel));
        env.setPadding(dp(12), dp(10), dp(12), dp(10));
        col.addView(env);

        final boolean root = Caps.hasRoot();
        if (!root) {
            TextView warn = new TextView(this);
            warn.setText("⚠ 未检测到 root 通道：以下操作不会真的执行。");
            warn.setTextColor(getColor(R.color.bad));
            warn.setTextSize(13);
            warn.setPadding(0, dp(10), 0, 0);
            col.addView(warn);
        }

        /* 亮度 */
        col.addView(section("屏幕亮度（0-255）"));
        col.addView(slider(1, 255, Math.max(1, SysOps.getBrightness()), v ->
                op("亮度=" + v, () -> SysOps.setBrightness(v))));

        /* 音量 */
        col.addView(section("媒体音量（0-15）"));
        col.addView(slider(0, 15, 7, v -> op("音量=" + v, () -> SysOps.setVolume(v))));

        /* 开关类 */
        col.addView(section("开关"));
        col.addView(btn("WiFi 开", () -> op("WiFi 开", () -> SysOps.setWifi(true))));
        col.addView(btn("WiFi 关", () -> op("WiFi 关", () -> SysOps.setWifi(false))));
        col.addView(btn("蓝牙 开", () -> op("蓝牙 开", () -> SysOps.setBluetooth(true))));
        col.addView(btn("蓝牙 关", () -> op("蓝牙 关", () -> SysOps.setBluetooth(false))));
        col.addView(btn("屏幕常亮（研发调试用）", () -> op("屏幕常亮（研发调试用）", () -> SysOps.keepScreenOn(true))));
        col.addView(btn("自动时间开", () -> op("自动时间开", () -> SysOps.setAutoTime(true))));

        /* 任务类 */
        col.addView(section("进程 / 任务"));
        col.addView(btn("清掉后台所有第三方应用（保留系统）", () -> op("force-stop 全部第三方", () -> {
            StringBuilder sb = new StringBuilder();
            for (HomeActivity.AppEntry e : HomeActivity.loadApps(this)) {
                SysOps.R r = SysOps.forceStop(e.pkg);
                sb.append(e.label).append(':').append(r.ok ? "ok" : "no").append(' ');
            }
            return new SysOps.R(true, sb.toString());
        })));
        col.addView(btn("清空本应用缓存数据（慎用）", () -> op("清空本应用缓存数据（慎用）", () -> SysOps.clearCache(getPackageName()))));

        /* 权限 / 安装 */
        col.addView(section("权限 / 安装"));
        col.addView(btn("给自己授予全部申请过的权限", () -> op("批量 pm grant", () -> {
            StringBuilder sb = new StringBuilder();
            String[] perms = {
                    "android.permission.SYSTEM_ALERT_WINDOW",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.READ_MEDIA_AUDIO",
                    "android.permission.BLUETOOTH_CONNECT",
            };
            for (String p : perms) {
                SysOps.R r = SysOps.grant(getPackageName(), p);
                sb.append(p.substring(p.lastIndexOf('.') + 1)).append(':')
                        .append(r.ok ? "ok" : "no").append(' ');
            }
            return new SysOps.R(true, sb.toString());
        })));

        log = new TextView(this);
        log.setTextColor(getColor(R.color.warn));
        log.setTextSize(11);
        log.setTypeface(Typeface.MONOSPACE);
        log.setPadding(0, dp(16), 0, 0);
        col.addView(log);

        sv.addView(col);
        return sv;
    }

    private TextView section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(getColor(R.color.leaf));
        tv.setTextSize(14);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(20); p.bottomMargin = dp(8);
        tv.setLayoutParams(p);
        return tv;
    }

    interface Op { SysOps.R run(); }
    interface VoidOp { void run(); }

    private void doVoid(String label, VoidOp o) {
        op(label, () -> { o.run(); return new SysOps.R(true, "已下发"); });
    }

    private void op(String label, Op o) {
        new Thread(() -> {
            SysOps.R r;
            try { r = o.run(); } catch (Throwable t) { r = new SysOps.R(false, "异常: " + t); }
            final String line = label + " → " + r;
            runOnUiThread(() -> append(line));
        }).start();
    }

    private void append(String s) {
        buf.insert(0, s + "\n");
        if (buf.length() > 4000) buf.setLength(4000);
        if (log != null) log.setText(buf.toString());
    }

    private View btn(String label, VoidOp o) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(getColor(R.color.text));
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(14), dp(13), dp(14), dp(13));
        tv.setBackgroundColor(getColor(R.color.card));
        tv.setOnClickListener(v -> o.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        tv.setLayoutParams(p);
        return tv;
    }

    private View slider(int min, int max, int cur, final IntCb cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(getColor(R.color.card));
        box.setPadding(dp(14), dp(10), dp(14), dp(6));

        final TextView val = new TextView(this);
        val.setTextColor(getColor(R.color.text));
        val.setTextSize(14);
        val.setText("当前: " + cur);
        box.addView(val);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, cur - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText("当前: " + (p + min));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                cb.on(s.getProgress() + min);
            }
        });
        box.addView(sb);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        box.setLayoutParams(p);
        return box;
    }

    interface IntCb { void on(int v); }
}
