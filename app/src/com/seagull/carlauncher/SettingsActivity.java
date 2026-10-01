package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 兼容入口：设置功能已迁移到 SettingsHubActivity（5 组 / 17 分区 + 搜索）。
 * 本类只做跳转，保留包名与清单注册，外部引用不断。
 */
public class SettingsActivity extends BaseActivity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startActivity(new Intent(this, SettingsHubActivity.class));
        finish();
    }
}
