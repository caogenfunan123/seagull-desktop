package com.seagull.carlauncher;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 触摸转发（12.1~12.8 的执行侧）。
 *
 * 通道：root `input` 命令。滑动一条一条发（跟手 12.5），
 * 长按发一条「原地不动」的 swipe，时长就是长按判定（12.4）。
 * 一次只发一条（串行），所以手势不会交叉 —— 参考实现漏了这条，手指一快就乱。
 *
 * 坐标：VD 按 surface 尺寸 1:1 建，所以本地坐标直接用；带缩放时按 scale 换算。
 * 排障（12.8）：lastLines() 拿最近 12 条命令与返回，失败原因都在里面。
 */
public final class TouchForward {

    private static final String TAG = "SeagullTouch";
    private static final int LOG_MAX = 12;

    private final HandlerThread thread = new HandlerThread("seagull-touch");
    private final Handler h;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private final float threshold;      // 防误滑阈值（px）
    private final long longPressMs;
    private final boolean follow;       // 跟手：滑动手势边走边发

    private int displayId = -1;
    private float scale = 1f;
    private float downX, downY;
    private long downAt;
    private float lastX, lastY;
    private long lastAt;
    private boolean moved;

    public TouchForward(float threshold, long longPressMs, boolean follow) {
        this.threshold = Math.max(0, threshold);
        this.longPressMs = longPressMs > 0 ? longPressMs : 500L;
        this.follow = follow;
        thread.start();
        h = new Handler(thread.getLooper());
    }

    public void setDisplay(int displayId, float scale) {
        this.displayId = displayId;
        this.scale = scale <= 0 ? 1f : scale;
    }

    public boolean ready() { return displayId > 0; }

    /** 每一次 MotionEvent 都喂进来（12.1 点/滑/长按都从这里出）。 */
    public void feed(android.view.MotionEvent e) {
        if (!ready()) return;
        int a = e.getActionMasked();
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            downX = lastX = e.getX();
            downY = lastY = e.getY();
            downAt = lastAt = e.getEventTime();
            moved = false;
        } else if (a == android.view.MotionEvent.ACTION_MOVE) {
            if (!follow) return;                       // 不跟手：等抬起一次性发
            float dx = e.getX() - lastX, dy = e.getY() - lastY;
            if (Math.abs(dx) < 2 && Math.abs(dy) < 2) return;   // 抖一下不发，省命令
            long dt = Math.max(16, e.getEventTime() - lastAt);
            swipe(lastX, lastY, e.getX(), e.getY(), Math.min(dt, 120));
            lastX = e.getX(); lastY = e.getY(); lastAt = e.getEventTime();
            if (Math.abs(e.getX() - downX) >= threshold || Math.abs(e.getY() - downY) >= threshold) {
                moved = true;
            }
        } else if (a == android.view.MotionEvent.ACTION_UP) {
            if (!follow) {
                boolean isMove = Math.abs(e.getX() - downX) >= threshold
                        || Math.abs(e.getY() - downY) >= threshold;
                if (isMove) {
                    long dur = clamp(e.getEventTime() - downAt, 50, 2000);
                    swipe(downX, downY, e.getX(), e.getY(), dur);
                } else {
                    tap(e.getX(), e.getY());
                }
            }
            // 跟手模式下滑动已在 MOVE 里发完；没滑动的补一次点击
            if (follow && !moved) tap(e.getX(), e.getY());
        }
        // CANCEL 什么都不发（12.1：取消就丢掉，参考实现在这里漏过）
    }

    /** 供「长按」按钮用：原地不动按 longPressMs。 */
    public void longPress(float x, float y) {
        swipe(x, y, x, y, longPressMs);
    }

    private void tap(float x, float y) {
        final String cmd = "input -d " + displayId + " input tap "
                + px(x) + " " + px(y);
        run(cmd);
    }

    private void swipe(float x1, float y1, float x2, float y2, long dur) {
        final String cmd = "input -d " + displayId + " input swipe "
                + px(x1) + " " + px(y1) + " " + px(x2) + " " + px(y2) + " " + clamp(dur, 16, 2000);
        run(cmd);
    }

    private int px(float v) { return Math.round(v * scale); }

    private static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void run(String cmd) {
        h.post(() -> {
            String out = Caps.exec(cmd);
            boolean bad = out == null || out.toLowerCase().contains("permission denied")
                    || out.toLowerCase().contains("exception");
            push(cmd + " → " + (out == null ? "null" : out.trim().replace('\n', ' ')));
            if (bad) Log.w(TAG, "注入失败: " + cmd + " -> " + out);
        });
    }

    private void push(String line) {
        synchronized (LOG) {
            LOG.addFirst(line);
            while (LOG.size() > LOG_MAX) LOG.removeLast();
        }
    }

    /** 12.8 排障：最近几条命令与返回（同时只有一个窗口在转发，静态日志就够）。 */
    public static List<String> lastLines() {
        synchronized (LOG) { return new ArrayList<>(LOG); }
    }

    public void shutdown() {
        try { thread.quitSafely(); } catch (Throwable ignore) {}
    }

    /** 无 root 时给出可读的降级说明，别让用户对着没反应的界面猜。 */
    public static String degradeNote(Context c) {
        return Caps.hasRoot() ? "" : "这台机器没有 root，触摸注入走不了（12.7 已验证被拒）。"
                + "有 root 时用 input 命令，没有就只能开无障碍通道或直接操作窗口内的应用。";
    }
}
