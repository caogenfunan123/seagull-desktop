package cn.wayecai.launcher.system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;
import j.AbstractC0101c;

/* loaded from: classes.dex */
public class InstallResultReceiver extends BroadcastReceiver {
    public static void a(Context context, String str) {
        try {
            Toast.makeText(context.getApplicationContext(), str, 1).show();
        } catch (Throwable unused) {
        }
    }

    @Override // android.content.BroadcastReceiver
    public final void onReceive(Context context, Intent intent) {
        if (intent == null || !"cn.wayecai.launcher.INSTALL_RESULT".equals(intent.getAction())) {
            return;
        }
        int intExtra = intent.getIntExtra("android.content.pm.extra.STATUS", Integer.MIN_VALUE);
        String stringExtra = intent.getStringExtra("android.content.pm.extra.STATUS_MESSAGE");
        String stringExtra2 = intent.getStringExtra("android.content.pm.extra.PACKAGE_NAME");
        Log.i("YecaiInstall", "安装结果 status=" + intExtra + " pkg=" + stringExtra2 + " msg=" + stringExtra);
        if (intExtra == -1) {
            Intent intent2 = (Intent) intent.getParcelableExtra("android.intent.extra.INTENT");
            if (intent2 == null) {
                Log.w("YecaiInstall", "系统要确认，却没给 EXTRA_INTENT");
                a(context, "系统要求手动确认安装，但没给出确认界面");
                return;
            }
            try {
                context.startActivity(intent2.addFlags(268435456));
                return;
            } catch (Throwable th) {
                Log.w("YecaiInstall", "拉不起系统安装界面: " + th);
                a(context, "打不开系统安装界面：" + th.getMessage());
                return;
            }
        }
        if (intExtra == 0) {
            Log.i("YecaiInstall", "装好了: " + stringExtra2);
            return;
        }
        Log.w("YecaiInstall", "安装失败 status=" + intExtra + " msg=" + stringExtra);
        if (intent.getBooleanExtra("cn.wayecai.launcher.CONFIRM", false) && intExtra == 3) {
            Log.i("YecaiInstall", "车友取消了安装: " + stringExtra2);
            return;
        }
        String stringExtra3 = intent.getStringExtra("cn.wayecai.launcher.FALLBACK_URL");
        if (stringExtra3 != null && !stringExtra3.isEmpty() && AbstractC0101c.k(context, stringExtra3)) {
            a(context, "这台机器不允许后台静默安装，已交给系统安装界面，按提示装就行");
            return;
        }
        StringBuilder sb = new StringBuilder("安装失败");
        sb.append((stringExtra == null || stringExtra.isEmpty()) ? "（系统未说明原因）" : "：".concat(stringExtra));
        a(context, sb.toString());
    }
}
