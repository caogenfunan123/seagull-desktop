package com.seagull.carlauncher;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * root 侧辅助通道骨架。
 *
 * 设计：本服务负责与 root 交互（su -c ...），
 * 用于 L3 的替代实现——由特权侧把应用启动进虚拟屏 / 调整任务窗口模式，
 * 以及静默安装自身更新等系统级操作。
 * 所有命令执行前都先过 Caps.hasRoot() 判定，失败即降级，不做任何盲试。
 */
public class RootShellService extends Service {

    private static final String TAG = "SeagullRoot";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "root 通道状态: " + Caps.rootWho());
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
