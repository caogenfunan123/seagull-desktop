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

    protected LauncherModel model;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(scaled(base));
    }

    @Override protected void onCreate(Bundle b) {
        model = new LauncherModel(this);
        Skin.apply(model);
        super.onCreate(b);
        applyOrientation(model);
    }

    /** 屏幕方向（TODO P0-8 7.4~7.6）。AUTO = 跟随系统。 */
    private void applyOrientation(LauncherModel m) {
        switch (m.orientation) {
            case LANDSCAPE: setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); break;
            case PORTRAIT: setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT); break;
            default: setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    /** 当前是不是竖屏（决定用哪一套边距与缝）。 */
    protected boolean isPortrait() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    private static Context scaled(Context base) {
        int pct = LauncherModel.fontScaleOf(base);
        if (pct == 100) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.fontScale = base.getResources().getConfiguration().fontScale * pct / 100f;
        return base.createConfigurationContext(cfg);
    }
}
