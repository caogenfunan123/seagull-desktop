package com.seagull.carlauncher;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 通知监听服务 —— 用于抓取媒体会话与导航通知（高德/音乐卡片的数据源）。
 * 需 root 侧把它写进 enabled_notification_listeners（RootOps.grantAll 已实现，追加式不覆盖别人）。
 */
public class MediaListenerService extends NotificationListenerService {

    private static final String TAG = "SeagullMediaListener";

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "通知监听已连接");
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        try {
            String pkg = sbn.getPackageName();
            android.os.Bundle ex = sbn.getNotification().extras;
            if (ex == null) return;
            CharSequence title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE);
            CharSequence text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT);
            Log.i(TAG, "通知 " + pkg + " | " + title + " | " + text);
            // 后续在这里解析导航/媒体信息 → 发布到状态单例
        } catch (Throwable t) {
            Log.w(TAG, "解析通知失败", t);
        }
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {}
}
