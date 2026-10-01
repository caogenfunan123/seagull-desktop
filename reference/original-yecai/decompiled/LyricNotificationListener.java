package cn.wayecai.launcher.system;

import a.AbstractC0010k;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import cn.wayecai.launcher.SettingsActivity;
import cn.wayecai.launcher.YecaiApp;
import j.B;
import j.z;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/* loaded from: classes.dex */
public class LyricNotificationListener extends NotificationListenerService {

    /* renamed from: a, reason: collision with root package name */
    public static volatile boolean f280a;

    public static boolean a(ContextWrapper contextWrapper) {
        if (b(contextWrapper)) {
            if (f280a) {
                return true;
            }
            c(contextWrapper);
            return true;
        }
        ComponentName componentName = new ComponentName(contextWrapper, (Class<?>) LyricNotificationListener.class);
        try {
            NotificationManager.class.getMethod("setNotificationListenerAccessGranted", ComponentName.class, Boolean.TYPE).invoke((NotificationManager) contextWrapper.getSystemService(NotificationManager.class), componentName, Boolean.TRUE);
        } catch (Throwable th) {
            AbstractC0010k.b("setNotificationListenerAccessGranted: ", th, "YecaiLyricNL");
            try {
                ContentResolver contentResolver = contextWrapper.getContentResolver();
                String string = Settings.Secure.getString(contentResolver, "enabled_notification_listeners");
                String flattenToString = componentName.flattenToString();
                if (string != null) {
                    if (!string.contains(flattenToString)) {
                    }
                }
                if (string != null && !string.isEmpty()) {
                    flattenToString = string + ":" + flattenToString;
                }
                Settings.Secure.putString(contentResolver, "enabled_notification_listeners", flattenToString);
            } catch (Throwable th2) {
                AbstractC0010k.b("enabled_notification_listeners: ", th2, "YecaiLyricNL");
            }
        }
        boolean b2 = b(contextWrapper);
        if (b2) {
            c(contextWrapper);
        }
        return b2;
    }

    public static boolean b(ContextWrapper contextWrapper) {
        try {
            return ((NotificationManager) contextWrapper.getSystemService(NotificationManager.class)).isNotificationListenerAccessGranted(new ComponentName(contextWrapper, (Class<?>) LyricNotificationListener.class));
        } catch (Throwable unused) {
            return false;
        }
    }

    public static void c(ContextWrapper contextWrapper) {
        try {
            NotificationListenerService.requestRebind(new ComponentName(contextWrapper, (Class<?>) LyricNotificationListener.class));
        } catch (Throwable th) {
            AbstractC0010k.b("requestRebind: ", th, "YecaiLyricNL");
        }
    }

    public static int d(SettingsActivity settingsActivity) {
        if (b(settingsActivity)) {
            return f280a ? 2 : 1;
        }
        return 0;
    }

    @Override // android.service.notification.NotificationListenerService
    public final void onListenerConnected() {
        f280a = true;
    }

    @Override // android.service.notification.NotificationListenerService
    public final void onListenerDisconnected() {
        f280a = false;
    }

    @Override // android.service.notification.NotificationListenerService
    public final void onNotificationPosted(StatusBarNotification statusBarNotification) {
        LinkedHashMap linkedHashMap;
        String str;
        YecaiApp yecaiApp = YecaiApp.n;
        if (yecaiApp == null || statusBarNotification == null) {
            return;
        }
        B b2 = yecaiApp.f256e;
        String packageName = statusBarNotification.getPackageName();
        Notification notification = statusBarNotification.getNotification();
        b2.getClass();
        if (notification == null || packageName == null || !packageName.equals(b2.f901b.i())) {
            return;
        }
        b2.f905f = packageName;
        LinkedHashMap linkedHashMap2 = new LinkedHashMap();
        CharSequence charSequence = notification.tickerText;
        if (charSequence != null) {
            linkedHashMap2.put("tickerText", charSequence.toString().trim());
        }
        Bundle bundle = notification.extras;
        if (bundle != null) {
            try {
                String[] strArr = B.G;
                for (int i2 = 0; i2 < 6; i2++) {
                    String str2 = strArr[i2];
                    CharSequence charSequence2 = bundle.getCharSequence(str2);
                    if (charSequence2 != null) {
                        linkedHashMap2.put(str2, charSequence2.toString().trim());
                    }
                }
                for (String str3 : bundle.keySet()) {
                    if (!linkedHashMap2.containsKey(str3)) {
                        B.e(str3, bundle.get(str3), linkedHashMap2, 0);
                    }
                }
            } catch (Throwable unused) {
            }
        }
        Iterator it = linkedHashMap2.entrySet().iterator();
        while (true) {
            boolean hasNext = it.hasNext();
            linkedHashMap = b2.E;
            if (!hasNext) {
                break;
            }
            Map.Entry entry = (Map.Entry) it.next();
            B.v(linkedHashMap, (String) entry.getKey(), (String) entry.getValue());
        }
        if (!b2.l() || b2.w || b2.f912m) {
            return;
        }
        String str4 = (String) linkedHashMap2.get("tickerText");
        boolean z = (notification.flags & 50331648) != 0;
        z zVar = (z) linkedHashMap.get("tickerText");
        boolean z2 = zVar != null && zVar.f1052b >= 3;
        if (b2.b("meizu_ticker") && !TextUtils.isEmpty(str4) && (z || (z2 && ((str = b2.f908i) == null || str.isEmpty() || !str4.contains(b2.f908i))))) {
            b2.y(str4, "状态栏歌词");
            return;
        }
        if (b2.b("notification_text")) {
            String[] strArr2 = B.H;
            for (int i3 = 0; i3 < 6; i3++) {
                String str5 = strArr2[i3];
                z zVar2 = (z) linkedHashMap.get(str5);
                String str6 = (String) linkedHashMap2.get(str5);
                if (zVar2 != null && !TextUtils.isEmpty(str6) && zVar2.f1052b >= 3 && !b2.i(str6)) {
                    b2.y(str6, "通知文字");
                    return;
                }
            }
        }
    }
}
