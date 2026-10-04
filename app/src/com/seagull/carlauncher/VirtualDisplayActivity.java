package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * L3 虚拟屏窗口控制台 —— 走完「建虚拟屏 → 把应用启上去 → 验证 → 切焦点」全流程，
 * 每一步的真实结果都在这里显示，失败就显示失败，不假装成功。
 */
public class VirtualDisplayActivity extends BaseActivity {

    private final VirtualDisplayHost host = new VirtualDisplayHost();
    private final StringBuilder buf = new StringBuilder();
    private TextView log, state;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // RootOps.launchOnDisplay 需要 Context，不 attach 一点「root 代启」就抛
        // IllegalStateException 崩进程（VirtualDisplayHost.ctx() 直接抛）
        host.attach(this);
        setContentView(build());
        // su 探测是同步阻塞（ProcessBuilder + waitFor），放后台线程；
        // append 内部已 runOnUiThread，直接调即可
        new Thread(() -> append("uid=" + android.os.Process.myUid()
                + "  root=" + Caps.hasRoot()
                + "  自由窗口=不支持  虚拟屏=" + (Caps.canVirtualDisplay(this) ? "可用" : "待验证")),
                "vda-probe").start();
        append("点下面按钮逐条走，每步结果都打在这里，同时进 logcat（tag=SeagullVD）。");
    }

    @Override protected void onDestroy() {
        host.release();
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Skin.c(R.color.ground));
        col.setPadding(dp(20), dp(16), dp(20), dp(24));

        TextView back = new TextView(this);
        back.setText("← 返回桌面");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(16);
        back.setOnClickListener(v -> finish());
        col.addView(back);

        TextView t = new TextView(this);
        t.setText("L3 虚拟屏窗口");
        t.setTextColor(Skin.c(R.color.text));
        t.setTextSize(22);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(8); tp.bottomMargin = dp(6);
        col.addView(t, tp);

        TextView sub = new TextView(this);
        sub.setText("把应用真正启动到虚拟屏上。与 L1 镜像不同，这里目标应用是活的、可交互的。");
        sub.setTextColor(Skin.c(R.color.text_dim));
        sub.setTextSize(12);
        col.addView(sub);

        state = new TextView(this);
        state.setTextColor(Skin.c(R.color.leaf));
        state.setTextSize(12);
        state.setTypeface(Typeface.MONOSPACE);
        state.setBackground(Skin.round(Skin.c(R.color.panel), 12f));
        state.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(12);
        col.addView(state, sp);

        col.addView(section("① 建虚拟屏"));
        col.addView(btn("建一个 960x540 @160dpi 的虚拟屏", () -> {
            VirtualDisplayHost.R r = host.create(this, 960, 540, 160);
            append("create → " + r);
            refreshState();
        }));

        col.addView(section("② 把应用启上去（选一个）"));
        TextView hint = new TextView(this);
        hint.setText("下面列的是已安装应用，点一下就用「应用自身权限」尝试 launch 到虚拟屏。");
        hint.setTextColor(Skin.c(R.color.text_dim));
        hint.setTextSize(11);
        col.addView(hint);
        addAppButtons(col);

        col.addView(section("③ root 代启（shell 持 ADD_TRUSTED_DISPLAY）"));
        col.addView(btn("用 root 以 shell 身份把「设置」启到虚拟屏", () -> {
            VirtualDisplayHost.R r = host.launchViaRoot("com.android.settings");
            append("root launch → " + r);
            refreshState();
        }));
        col.addView(btn("用 root 以 shell 身份把「浏览器/文件管理」启到虚拟屏", () -> {
            String[] cands = {"com.android.documentsui", "com.android.browser",
                    "com.miui.notes", "com.android.chrome", "com.miui.video"};
            StringBuilder sb = new StringBuilder();
            for (String c : cands) {
                VirtualDisplayHost.R r = host.launchViaRoot(c);
                sb.append(c).append(r.ok ? " ok; " : " no; ");
                if (r.ok) break;
            }
            append("root launch → " + sb);
            refreshState();
        }));

        col.addView(section("④ 验证与焦点"));
        col.addView(btn("验证虚拟屏上是不是真有 task", () -> {
            VirtualDisplayHost.R r = host.verify(this, "com.android.settings");
            append("verify → " + r);
        }));
        col.addView(btn("把焦点切到虚拟屏上的 task（反射 setFocusedTask）", () -> {
            VirtualDisplayHost.R r = host.focusTask();
            append("focus → " + r);
        }));
        col.addView(btn("释放虚拟屏", () -> {
            host.release();
            append("已释放");
            refreshState();
        }));

        col.addView(section("说明：为什么普通应用做不了这一步"));
        TextView why = new TextView(this);
        why.setText("createVirtualDisplay 普通应用能过（上面①大概率就是 OK）。\n"
                + "真正卡住的是 ActivityOptions.setLaunchDisplayId()：系统要求调用方或目标持有\n"
                + "ADD_TRUSTED_DISPLAY / INTERNAL_SYSTEM_WINDOW，或在同一 task 内。\n"
                + "普通应用三条都不满足 → 抛 SecurityException。\n\n"
                + "本机 com.android.shell 持有 ADD_TRUSTED_DISPLAY（granted=true），\n"
                + "所以第三步「root 代启」是这台机器上唯一不依赖平台签名就能把应用搬进虚拟屏的路子。\n"
                + "要纯应用内实现，需要 uid=1000（装进 /system/priv-app）。");
        why.setTextColor(Skin.c(R.color.text_dim));
        why.setTextSize(11);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
        wp.topMargin = dp(8);
        col.addView(why, wp);

        log = new TextView(this);
        log.setTextColor(Skin.c(R.color.warn));
        log.setTextSize(11);
        log.setTypeface(Typeface.MONOSPACE);
        log.setPadding(0, dp(16), 0, 0);
        col.addView(log);

        sv.addView(col);
        return sv;
    }

    private void addAppButtons(LinearLayout col) {
        List<HomeActivity.AppEntry> apps = HomeActivity.loadApps(this);
        int n = Math.min(apps.size(), 10);
        for (int i = 0; i < n; i++) {
            final HomeActivity.AppEntry e = apps.get(i);
            col.addView(btn("→ " + e.label + "  (" + e.pkg + ")", () -> {
                VirtualDisplayHost.R r = host.launch(this, e.pkg);
                append("launch " + e.pkg + " → " + r);
                refreshState();
            }));
        }
    }

    private TextView section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Skin.c(R.color.leaf));
        tv.setTextSize(14);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(20); p.bottomMargin = dp(8);
        tv.setLayoutParams(p);
        return tv;
    }

    private View btn(String label, Runnable r) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Skin.c(R.color.text));
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setBackground(Skin.round(Skin.c(R.color.card), 12f));
        tv.setOnClickListener(v -> new Thread(r).start());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        tv.setLayoutParams(p);
        return tv;
    }

    private void refreshState() {
        final String s = "虚拟屏 displayId = " + (host.displayId() < 0 ? "未创建" : host.displayId())
                + "\nuid = " + android.os.Process.myUid()
                + "    root = " + Caps.hasRoot();
        runOnUiThread(() -> state.setText(s));
    }

    /** buf 会被多个按钮线程并发写，append 整体加锁；setText 回主线程。 */
    private synchronized void append(String s) {
        android.util.Log.i("SeagullVD", s);
        buf.insert(0, s + "\n");
        if (buf.length() > 6000) buf.setLength(6000);
        String shown = buf.toString();
        runOnUiThread(() -> { if (log != null) log.setText(shown); });
    }
}
