package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 全部应用列表：点击启动，长按固定到 Dock。 */
public class AppListActivity extends BaseActivity {

    private LinearLayout list;
    private List<HomeActivity.AppEntry> apps = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        apps = HomeActivity.loadApps(this); // 复用加载逻辑（无状态）
        fill();
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

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("← 返回");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(16);
        back.setPadding(0, dp(6), dp(16), dp(6));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView title = new TextView(this);
        title.setText(R.string.all_apps);
        title.setTextColor(Skin.c(R.color.text));
        title.setTextSize(20);
        head.addView(title);

        TextView search = new TextView(this);
        search.setText("搜索");
        search.setTextColor(Skin.c(R.color.leaf));
        search.setTextSize(15);
        search.setPadding(dp(14), dp(6), 0, dp(6));
        search.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        head.addView(search);
        col.addView(head);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        return col;
    }

    private void fill() {
        list.removeAllViews();
        for (HomeActivity.AppEntry e : apps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(Skin.c(R.color.card));

            ImageView iv = new ImageView(this);
            iv.setImageDrawable(e.icon);
            row.addView(iv, new LinearLayout.LayoutParams(dp(38), dp(38)));

            TextView tv = new TextView(this);
            tv.setText(e.label);
            tv.setTextColor(Skin.c(R.color.text));
            tv.setTextSize(16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = dp(14);
            row.addView(tv, lp);

            TextView pkg = new TextView(this);
            pkg.setText(e.pkg);
            pkg.setTextColor(Skin.c(R.color.text_dim));
            pkg.setTextSize(10);
            row.addView(pkg);

            LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(-1, -2);
            wrap.bottomMargin = dp(8);
            row.setLayoutParams(wrap);

            row.setOnClickListener(v -> launch(e.pkg));
            row.setOnLongClickListener(v -> {
                HomeActivity.pinStatic(this, e.pkg);
                android.widget.Toast.makeText(this, "已固定到 Dock：" + e.label,
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            });
            list.addView(row);
        }
    }

    private void launch(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) startActivity(i);
        } catch (Throwable ignore) {}
    }
}
