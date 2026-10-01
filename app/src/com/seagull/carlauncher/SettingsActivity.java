package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 桌面设置：开机自启、Dock 槽位、窗口档位、默认桌面引导。 */
public class SettingsActivity extends Activity {

    private LinearLayout dockBox;
    private LinearLayout rootBox;
    private LauncherModel model;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        model = new LauncherModel(this);
        setContentView(build());
    }

    @Override protected void onResume() {
        super.onResume();
        model.loadApps();   // 刷新应用列表（可能有新装/卸载）
        fillDock();
        fillRootInfo();
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
        t.setText(R.string.settings_title);
        t.setTextColor(getColor(R.color.text));
        t.setTextSize(22);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(8); tp.bottomMargin = dp(14);
        col.addView(t, tp);

        /* 开机自启 */
        final android.content.SharedPreferences sp =
                getSharedPreferences(HomeActivity.PREFS, MODE_PRIVATE);
        Switch sw = new Switch(this);
        sw.setText("开机自动进入桌面");
        sw.setTextColor(getColor(R.color.text));
        sw.setTextSize(15);
        sw.setChecked(sp.getBoolean("autoHome", true));
        sw.setOnCheckedChangeListener((CompoundButton b2, boolean on) ->
                sp.edit().putBoolean("autoHome", on).apply());
        col.addView(sw, new LinearLayout.LayoutParams(-1, -2));

        col.addView(section("Dock（长按图标取消固定，长按应用列表条目固定）"));
        dockBox = new LinearLayout(this);
        dockBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(dockBox);

        col.addView(section("设为默认桌面"));
        col.addView(row("打开系统「默认应用」设置", "把默认桌面改成海鸥桌面", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (Throwable e) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Throwable ignore) {}
            }
        }));

        col.addView(section("窗口能力 / root"));
        rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(rootBox);

        sv.addView(col);
        return sv;
    }

    private TextView section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(getColor(R.color.leaf));
        tv.setTextSize(14);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(22); p.bottomMargin = dp(8);
        tv.setLayoutParams(p);
        return tv;
    }

    private View row(String title, String sub, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackgroundColor(getColor(R.color.card));

        TextView a = new TextView(this);
        a.setText(title);
        a.setTextColor(getColor(R.color.text));
        a.setTextSize(15);
        box.addView(a);

        TextView b = new TextView(this);
        b.setText(sub);
        b.setTextColor(getColor(R.color.text_dim));
        b.setTextSize(12);
        box.addView(b);

        if (l != null) box.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        box.setLayoutParams(p);
        return box;
    }

    private void fillDock() {
        if (dockBox == null || model == null) return;
        dockBox.removeAllViews();
        boolean any = false;
        for (int i = 0; i < model.dock.size(); i++) {
            String key = model.dock.get(i);
            LauncherModel.App a = model.find(key);
            String label = a != null ? a.label : key;
            final int slot = i;
            dockBox.addView(row("槽位 " + (i + 1) + "：" + label, "点击移除", v -> {
                model.toggleDock(key);
                fillDock();
            }));
            any = true;
        }
        if (!any) dockBox.addView(row("Dock 为空", "回桌面长按应用图标即可固定", null));
    }

    private void fillRootInfo() {
        if (rootBox == null) return;
        rootBox.removeAllViews();
        rootBox.addView(row("root 通道", Caps.rootWho(), v -> {
            new Thread(() -> {
                Caps.hasRoot();
                runOnUiThread(this::fillRootInfo);
            }).start();
        }));
        rootBox.addView(row("当前窗口档位", Caps.levelName(Caps.level(this)), null));
        rootBox.addView(row("虚拟屏（L3）",
                Caps.canVirtualDisplay(this) ? "可用"
                        : "不可用 —— 需 uid=1000。root 侧方案：把本应用装进 /system/priv-app",
                null));
        rootBox.addView(row("悬浮窗权限", Caps.canOverlay(this) ? "已授权" : "未授权（点这里去开）",
                v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Throwable ignore) {}
                }));
        rootBox.addView(row("打开窗口自检页", "能力探测与逐项验证", v ->
                startActivity(new Intent(this, WindowTestActivity.class))));
        rootBox.addView(row(getString(R.string.mirror_title), getString(R.string.mirror_sub),
                v -> startActivity(new Intent(this, MirrorActivity.class))));
        rootBox.addView(row(getString(R.string.root_panel_title),
                getString(R.string.root_panel_sub),
                v -> startActivity(new Intent(this, RootPanelActivity.class))));
    }
}
