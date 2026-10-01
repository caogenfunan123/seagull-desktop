package com.seagull.carlauncher;

import android.content.Context;
import android.content.pm.PackageManager;

/** L3 + 画中画 的真机验证：每一步结果打 logcat（tag=SeagullSelfTest）。 */
public final class SelfTestL3 {

    public static void run(Context ctx) {
        new Thread(() -> {
            line("=== L3 / PiP 验证开始 ===");
            try {
                line("uid=" + android.os.Process.myUid() + " root=" + Caps.hasRoot());
                line("PiP 系统特性=" + ctx.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE));

                VirtualDisplayHost host = new VirtualDisplayHost();
                host.attach(ctx);
                line("① create → " + host.create(ctx, 960, 540, 160));
                line("② launch(com.android.settings) → " + host.launch(ctx, "com.android.settings"));
                Thread.sleep(1500);
                line("③ verify → " + host.verify(ctx, "com.android.settings"));
                line("④ launchViaRoot(com.android.settings) → " + host.launchViaRoot("com.android.settings"));
                line("   PrivClient: " + PrivClient.status());
                Thread.sleep(1500);
                line("⑤ verify2 → " + host.verify(ctx, "com.android.settings"));
                line("⑥ launchViaAm(裸 am 口径) → " + host.launchViaAm("com.android.settings"));
                Thread.sleep(1500);
                line("⑦ focusTask → " + host.focusTask());
                host.release();
                line("⑧ 已释放虚拟屏");
            } catch (Throwable t) {
                line("EXCEPTION " + t);
            }
            line("=== 验证结束 ===");
        }).start();
    }

    private static void line(String s) {
        android.util.Log.i("SeagullSelfTest", s);
    }
}
