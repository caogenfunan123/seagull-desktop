package com.seagull.carlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 窗口卡片的前台服务（MediaProjection 要求有前台服务）。
 * 卡片的实际窗口挂在 {@link WindowCard} 里，由本服务持有生命周期。
 */
public class WindowService extends Service {

    public static final String ACTION_START_CARD = "com.seagull.carlauncher.START_CARD";
    private static final String TAG = "SeagullWindow";
    private static final String CH = "seagull_card";

    /** 授权结果只能由 Activity 拿到，交给服务后再由服务取投影。 */
    public static int sResultCode = 0;
    public static Intent sResultData;

    private WindowCard card;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        if (ACTION_START_CARD.equals(action(intent))) {
            MediaProjection mp = null;
            try {
                MediaProjectionManager mpm = (MediaProjectionManager)
                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (sResultData != null) mp = mpm.getMediaProjection(sResultCode, sResultData);
            } catch (Throwable t) {
                Log.e(TAG, "getMediaProjection 失败", t);
            }
            if (mp != null) {
                int w = getResources().getDisplayMetrics().widthPixels;
                int h = getResources().getDisplayMetrics().heightPixels;
                // 卡片按 16:9 左右的初始高度，虚拟屏尺寸跟随卡片布局
                card = new WindowCard(this, mp, w, h);
                card.show();
                Log.i(TAG, "卡片已启动");
            } else {
                Log.w(TAG, "没有 MediaProjection，无法建卡片");
            }
        }
        return START_NOT_STICKY;
    }

    private String action(Intent i) {
        return i == null ? null : i.getAction();
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "窗口卡片",
                        NotificationManager.IMPORTANCE_LOW));
            }
        }
        Notification n = null;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, CH)
                    .setContentTitle("海鸥桌面")
                    .setContentText("窗口卡片运行中")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build();
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(0x5EA2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(0x5EA2, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground 失败", t);
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        if (card != null) { card.close(); card = null; }
        super.onDestroy();
    }
}
