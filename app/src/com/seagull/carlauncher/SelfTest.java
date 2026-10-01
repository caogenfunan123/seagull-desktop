package com.seagull.carlauncher;

import android.content.Context;
import java.io.File;

/**
 * 自检：在应用自身的 uid 下真实跑一遍 root 通道，结果落到 cacheDir/selftest.txt。
 * 用途是在没有 ADB/无 UI 自动化的情况下确认「应用内 su 通道」到底通不通。
 */
public final class SelfTest {

    public static void run(Context ctx) {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                sb.append("pkg=").append(ctx.getPackageName()).append('\n');
                sb.append("uid=").append(android.os.Process.myUid()).append('\n');
                sb.append("hasRoot=").append(Caps.hasRoot()).append('\n');
                sb.append("rootWho=").append(Caps.rootWho()).append('\n');
                sb.append("brightness(read)=").append(SysOps.getBrightness()).append('\n');
                sb.append("setBrightness=").append(SysOps.setBrightness(128)).append('\n');
                sb.append("brightness(after)=").append(SysOps.getBrightness()).append('\n');
                sb.append("setVolume=").append(SysOps.setVolume(7)).append('\n');
                sb.append("overlay=").append(Caps.canOverlay(ctx)).append('\n');
                sb.append("level=").append(Caps.levelName(Caps.level(ctx))).append('\n');
                sb.append("envInfo:\n").append(SysOps.envInfo(ctx)).append('\n');
            } catch (Throwable t) {
                sb.append("EXCEPTION ").append(t).append('\n');
            }
            // 写多个位置：/data/local/tmp 便于 root 侧取证；logcat 分块便于无文件读取
            String[] paths = {"/data/local/tmp/seagull_selftest.txt"};
            for (String p : paths) {
                try {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(p);
                    fos.write(sb.toString().getBytes("UTF-8"));
                    fos.close();
                } catch (Throwable t) {
                    android.util.Log.w("SeagullSelfTest", "写 " + p + " 失败: " + t);
                }
            }
            for (String line : sb.toString().split("\n")) {
                android.util.Log.i("SeagullSelfTest", line);
            }
        }).start();
    }
}
