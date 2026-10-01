package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

/**
 * 所有页面的基类：全局字号缩放 + 主题换肤都挂在这里（TODO P0-7）。
 *
 * 字号走 Configuration.fontScale，只在这里做一次 —— 全部界面用的都是 sp，
 * 不用逐个 setTextSize 去乘系数。
 * 换肤在 onCreate 里算一次 Skin，页面用 Skin.c(R.color.x) 取色。
 */
public class BaseActivity extends Activity {

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(scaled(base));
    }

    @Override protected void onCreate(Bundle b) {
        LauncherModel m = new LauncherModel(this);
        Skin.apply(m);
        super.onCreate(b);
    }

    private static Context scaled(Context base) {
        int pct = LauncherModel.fontScaleOf(base);
        if (pct == 100) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.fontScale = base.getResources().getConfiguration().fontScale * pct / 100f;
        return base.createConfigurationContext(cfg);
    }
}
