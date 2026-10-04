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
        // Activity.getDisplay() 是 API 30+（minSdk 29）：29 上直接调用 = NoSuchMethodError
        // 锚点秒崩 → VD 上没有常驻 task → 空屏被系统清理，整套锚点机制失效。
        int d;
        if (android.os.Build.VERSION.SDK_INT >= 30 && getDisplay() != null) {
            d = getDisplay().getDisplayId();
        } else {
            d = -1;
        }
        Log.i(TAG, "锚点就位 task=" + getTaskId() + " displayId=" + d);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "锚点销毁 task=" + getTaskId());
    }
}
