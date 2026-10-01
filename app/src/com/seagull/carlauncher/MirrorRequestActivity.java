package com.seagull.carlauncher;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * 镜像卡片启动器：申请截屏授权 → 拿到 MediaProjection → 交给 WindowService 挂卡片。
 *
 * 授权必须由 Activity 发起（Android 的硬性要求），所以这里是一个透明中转页。
 */
public class MirrorRequestActivity extends BaseActivity {

    private static final String TAG = "SeagullMirror";
    private static final int REQ = 0x5EA2;
    private static final String CH = "seagull_card";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Caps.canOverlay(this)) {
            Toast.makeText(this, "先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignore) {}
            finish();
            return;
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
        } catch (Throwable t) {
            Log.e(TAG, "申请截屏失败", t);
            Toast.makeText(this, "无法申请截屏授权：" + t, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) { finish(); return; }
        if (res != RESULT_OK || data == null) {
            Toast.makeText(this, "截屏授权被拒绝，L1 镜像卡片不可用", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        WindowService.sResultCode = res;
        WindowService.sResultData = data;
        Intent svc = new Intent(this, WindowService.class)
                .setAction(WindowService.ACTION_START_CARD);
        try {
            startForegroundService(svc);
        } catch (Throwable t) {
            Log.e(TAG, "startForegroundService 失败，退回 startService", t);
            startService(svc);
        }
        Toast.makeText(this, "镜像卡片已创建", Toast.LENGTH_SHORT).show();
        finish();
    }
}
