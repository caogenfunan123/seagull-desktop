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
 * 双通道，按当前会话有没有 root 守护进程自动选：
 *   · 守护进程在位（PrivClient 可用）：原始事件中继 —— MotionEvent 连坐标带时间戳
 *     原样送到目标屏，多指手势天然成立，跟手零命令开销；
 *   · 不在位：root `input` 命令。滑动一条一条发（跟手 12.5），长按发一条
 *     「原地不动」的 swipe。一次只发一条（串行），手势不会交叉。
 *
 * 通道选择钉在每次手势的 DOWN 上，一次手势中途不换通道（换了事件流就断了）。
 *
 * 坐标：VD 按 surface 尺寸 1:1 建，所以本地坐标直接用；带缩放时按 scale 换算。
 * 排障（12.8）：lastLines() 拿最近 12 条命令与返回，失败原因都在里面。
 */
public final class TouchForward {

    private static final String TAG = "SeagullTouch";
    private static final int LOG_MAX = 12;
    private static final int MAX_POINTERS = 16;

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
    /** 当前手势走不走守护进程中继（每次 DOWN 时定，手势中途不换通道）。 */
    private boolean gestureOnDaemon;

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
            // 通道选择只在这里做：available() 是个廉价的同步查询，不会在 UI 线程拉起 su
            gestureOnDaemon = PrivClient.available();
        }
        if (gestureOnDaemon) {
            replay(e);
            return;
        }
        stroke(e, a);
    }

    /* ---------------- 守护进程通道：原始事件中继 ---------------- */

    /**
     * 把 MotionEvent 原样送到目标屏：全部触点 + 原始 action（POINTER 事件的 index 在高位）
     * + downTime/eventTime。目标应用收到的事件流与手指在 SurfaceView 上划的一模一样，
     * 所以点/滑/长按/多指缩放全都成立，不需要任何手势解释。
     *
     * 失败（连接断/注入被拒）处理：本手势余下事件退回命令通道，只在日志里记一笔 ——
     * 一个手势半路换通道事件流会断续，但比整条手势丢失好。
     */
    private void replay(android.view.MotionEvent e) {
        int n = Math.min(e.getPointerCount(), MAX_POINTERS);
        if (n <= 0) return;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = e.getX(i) * scale;
            ys[i] = e.getY(i) * scale;
        }
        boolean ok = PrivClient.motion(displayId, e.getAction(), e.getDownTime(),
                e.getEventTime(), xs, ys, n);
        if (ok) return;
        gestureOnDaemon = false;
        String what = PrivCodec.describeAction(e.getAction()) + " 中继失败，本手势余下事件改走命令通道";
        push(what);
        Log.w(TAG, what);
    }

    /* ---------------- input 命令通道：手势解释 ---------------- */

    private void stroke(android.view.MotionEvent e, int a) {
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
        if (PrivClient.available()) {
            // DOWN 与 UP 同点，中间隔 longPressMs —— 目标应用判定长按的就是这段时长
            long now = android.os.SystemClock.uptimeMillis();
            float[] px = {x * scale}, py = {y * scale};
            PrivClient.motion(displayId, android.view.MotionEvent.ACTION_DOWN, now, now, px, py, 1);
            PrivClient.motion(displayId, android.view.MotionEvent.ACTION_UP, now,
                    now + longPressMs, px, py, 1);
            return;
        }
        swipe(x, y, x, y, longPressMs);
    }

    private void tap(float x, float y) {
        final String cmd = "input -d " + displayId + " tap " + px(x) + " " + px(y);
        run(cmd);
    }

    private void swipe(float x1, float y1, float x2, float y2, long dur) {
        final String cmd = "input -d " + displayId + " swipe "
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
