package cn.wayecai.launcher.system;

import a.AbstractC0010k;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import cn.wayecai.launcher.HomeActivity;
import cn.wayecai.launcher.YecaiApp;
import cn.wayecai.launcher.system.BallService;
import cn.wayecai.launcher.system.BootReceiver;

/* loaded from: classes.dex */
public class BootReceiver extends BroadcastReceiver {

    /* renamed from: a, reason: collision with root package name */
    public static final /* synthetic */ int f279a = 0;

    public static void a(Context context, String str) {
        try {
            context.startActivity(new Intent(context, (Class<?>) HomeActivity.class).setAction("android.intent.action.MAIN").addCategory("android.intent.category.HOME").addFlags(268435456));
            Log.i("YecaiBoot", "开机自启：".concat(str));
        } catch (Throwable th) {
            Log.w("YecaiBoot", "开机自启没成功（" + str + "）：" + th);
        }
    }

    @Override // android.content.BroadcastReceiver
    public final void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if ("android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            try {
                final YecaiApp yecaiApp = (YecaiApp) context.getApplicationContext();
                final int i2 = 0;
                Runnable runnable = new Runnable() { // from class: j.i
                    @Override // java.lang.Runnable
                    public final void run() {
                        YecaiApp yecaiApp2 = yecaiApp;
                        switch (i2) {
                            case 0:
                                int i3 = BootReceiver.f279a;
                                if (HomeActivity.m0) {
                                    return;
                                }
                                if (yecaiApp2.f253b.d().i0 || AbstractC0108j.j(yecaiApp2)) {
                                    BootReceiver.a(yecaiApp2, "解锁");
                                    return;
                                }
                                return;
                            default:
                                int i4 = BootReceiver.f279a;
                                BallService.a(yecaiApp2, yecaiApp2.f253b.d());
                                I.a(yecaiApp2, "boot");
                                if ((yecaiApp2.f253b.d().i0 || AbstractC0108j.j(yecaiApp2)) && !HomeActivity.m0) {
                                    BootReceiver.a(yecaiApp2, "BOOT_COMPLETED");
                                    return;
                                }
                                return;
                        }
                    }
                };
                if (yecaiApp.f260i) {
                    runnable.run();
                } else {
                    yecaiApp.f261j.add(runnable);
                }
                return;
            } catch (Throwable th) {
                AbstractC0010k.b("开机自启没成功（LOCKED_BOOT_COMPLETED）：", th, "YecaiBoot");
                return;
            }
        }
        if ("android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
            try {
                BallService.a(context, ((YecaiApp) context.getApplicationContext()).f253b.d());
                context.startActivity(new Intent(context, (Class<?>) HomeActivity.class).setAction("android.intent.action.MAIN").addCategory("android.intent.category.HOME").addFlags(268435456));
                return;
            } catch (Throwable th2) {
                AbstractC0010k.b("更新后拉回桌面没成功：", th2, "YecaiBoot");
                return;
            }
        }
        if ("android.intent.action.BOOT_COMPLETED".equals(action)) {
            try {
                final YecaiApp yecaiApp2 = (YecaiApp) context.getApplicationContext();
                final int i3 = 1;
                Runnable runnable2 = new Runnable() { // from class: j.i
                    @Override // java.lang.Runnable
                    public final void run() {
                        YecaiApp yecaiApp22 = yecaiApp2;
                        switch (i3) {
                            case 0:
                                int i32 = BootReceiver.f279a;
                                if (HomeActivity.m0) {
                                    return;
                                }
                                if (yecaiApp22.f253b.d().i0 || AbstractC0108j.j(yecaiApp22)) {
                                    BootReceiver.a(yecaiApp22, "解锁");
                                    return;
                                }
                                return;
                            default:
                                int i4 = BootReceiver.f279a;
                                BallService.a(yecaiApp22, yecaiApp22.f253b.d());
                                I.a(yecaiApp22, "boot");
                                if ((yecaiApp22.f253b.d().i0 || AbstractC0108j.j(yecaiApp22)) && !HomeActivity.m0) {
                                    BootReceiver.a(yecaiApp22, "BOOT_COMPLETED");
                                    return;
                                }
                                return;
                        }
                    }
                };
                if (yecaiApp2.f260i) {
                    runnable2.run();
                } else {
                    yecaiApp2.f261j.add(runnable2);
                }
            } catch (Throwable th3) {
                AbstractC0010k.b("开机自启没成功：", th3, "YecaiBoot");
            }
        }
    }
}
