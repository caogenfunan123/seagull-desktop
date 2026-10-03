package com.seagull.carlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * 画中画锚点（批次 R，复刻 CarWithX 的 PipAnchorActivity）。
 *
 * 作用：部署时先把它起在虚拟屏上，让每块 VD 常驻一个我们自己的 root task。
 *   1. 重钉落点：目标任务被系统拉回主屏后，重钉有稳定的 stack 可落；
 *   2. 保屏：VD 上永远有活任务，系统不会把它当空屏清理；
 *   3. 判据：`am stack list` 里锚点行 = 这块屏还活着的直接证据。
 *
 * 形态与复刻源一致：空主题 + excludeFromRecents + 独立 taskAffinity
 * （不与任何应用共栈，move-task 才不会把别人的任务捎进来）。
 * 用户永远看不见它：透明背景、无界面、不进最近任务。
 */
public class PipAnchorActivity extends Activity {

    private static final String TAG = "SeagullAnchor";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "锚点就位 task=" + getTaskId() + " displayId="
                + (getDisplay() != null ? getDisplay().getDisplayId() : -1));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "锚点销毁 task=" + getTaskId());
    }
}
