package com.seagull.carlauncher;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 小白点 / 野菜悬浮键（TODO P1-2）。
 *
 * 一个 SYSTEM_ALERT_WINDOW 悬浮球：
 *   点一下   → 回桌面（Dock 关着时改成出全部应用）
 *   长按     → 进菜园
 *   拖       → 松手贴最近的左右边，位置记在本机
 *
 * 位置与大小走 LauncherModel（ballX/ballY/ballSize/ballAlpha），
 * 和菜园里的叶子共用同一个位置 —— 它们本质是同一个「野菜键」。
 */
public class BallService extends Service {

    private static final String TAG = "SeagullBall";

    private WindowManager wm;
    private View ball;
    private LauncherModel model;
    private int size;
    private float downX, downY;
    private boolean moved;
    private boolean longFired;

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        model = new LauncherModel(this, false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Caps.canOverlay(this)) {
            Log.w(TAG, "没有悬浮窗权限，不启动");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ball == null) show();
        return START_STICKY;
    }

    private void show() {
        size = dp(model.ballSize);
        ball = buildBall();

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // 上次的位置可能是换机前/横竖屏切换前/改字号后写下的：FLAG_LAYOUT_NO_LIMITS
        // 会把"越界的 x/y"直接渲染到屏幕外（用户看不到球，以为服务挂了）。
        // 旧实现只在 <0 时给默认值，>屏幕宽/高的脏值原样用。
        int x = model.ballX < 0 ? dp(16) : model.ballX;
        int y = model.ballY < 0 ? screenH() - size - dp(32) : model.ballY;
        int sw = screenW(), sh = screenH();
        x = Math.max(0, Math.min(x, Math.max(0, sw - size)));
        y = Math.max(0, Math.min(y, Math.max(0, sh - size)));
        lp.x = x; lp.y = y;
        ball.setTag(lp);
        try {
            wm.addView(ball, lp);
        } catch (Throwable t) {
            Log.w(TAG, "加悬浮球失败", t);
            ball = null;
        }
    }

    private View buildBall() {
        TextView v = new TextView(this);
        v.setText("叶");
        v.setTextSize(model.ballSize / 3f);
        v.setTextColor(Skin.c(R.color.leaf));
        v.setGravity(Gravity.CENTER);

        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        int a = (int) (Math.max(0.2f, Math.min(1f, model.ballAlpha / 100f)) * 255);
        g.setColor((a << 24) | (Skin.c(R.color.leaf) & 0x00FFFFFF));
        g.setStroke(dp(1), Skin.c(R.color.leaf));
        v.setBackground(g);

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final long longPress = model.longPressMs > 0 ? model.longPressMs : 500;
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getTag();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        moved = false;
                        longFired = false;
                        view.postDelayed(longPressRun, longPress);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (Math.abs(e.getRawX() - downX) > slop
                                || Math.abs(e.getRawY() - downY) > slop) moved = true;
                        lp.x += e.getRawX() - downX;
                        lp.y += e.getRawY() - downY;
                        downX = e.getRawX(); downY = e.getRawY();
                        clamp(lp);
                        try { wm.updateViewLayout(view, lp); } catch (Throwable ignore) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.removeCallbacks(longPressRun);
                        // 长按已弹菜园时松手不能再来一次 tap()，否则桌面又盖上来
                        if (!moved && !longFired) tap();
                        else if (moved) snap(lp);
                        return true;
                }
                return false;
            }
        });
        return v;
    }

    private final Runnable longPressRun = new Runnable() {
        @Override public void run() {
            longFired = true;
            // 长按服务上下文起 Activity 必须带 NEW_TASK，否则 AndroidRuntimeException
            // （Service 不是 Activity context）。tap() 那条路径带过，这条漏了。
            startActivity(new Intent(BallService.this, GardenActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    };

    private void tap() {
        Intent i;
        // Dock 关着时，悬浮键点一下直接出全部应用（5.7 / 5.8）
        if (model.dockPos == LauncherModel.DockPos.HIDDEN) {
            i = new Intent(this, AppListActivity.class);
        } else {
            i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** 松手贴最近的边，顺手把位置存下来。 */
    private void snap(WindowManager.LayoutParams lp) {
        lp.x = lp.x + size / 2 < getResources().getDisplayMetrics().widthPixels / 2
                ? dp(4) : getResources().getDisplayMetrics().widthPixels - size - dp(4);
        lp.y = Math.max(0, Math.min(lp.y, screenH() - size));
        try { wm.updateViewLayout(ball, lp); } catch (Throwable ignore) {}
        // model 是 onCreate 时的快照，直接 save 会把用户此后在设置页改的主题/Dock
        // 全部静默回滚。存之前先重读档，只动球坐标这两个字段。
        model.load();
        model.ballX = lp.x;
        model.ballY = lp.y;
        model.save();
    }

    private void clamp(WindowManager.LayoutParams lp) {
        int w = getResources().getDisplayMetrics().widthPixels;
        lp.x = Math.max(0, Math.min(lp.x, w - size));
        lp.y = Math.max(0, Math.min(lp.y, screenH() - size));
    }

    private int screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override public void onDestroy() {
        // 长按 Runnable 只在 UP/CANCEL 时摘；销毁时补一次，否则球已 remove 还会弹菜园
        if (ball != null) ball.removeCallbacks(longPressRun);
        if (ball != null) {
            try { wm.removeView(ball); } catch (Throwable ignore) {}
            ball = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /* ---------------- 开关 ---------------- */

    /** 开/关悬浮键。设置页与开机自启都走这里。 */
    public static void setEnabled(Context ctx, boolean on) {
        Intent i = new Intent(ctx, BallService.class);
        if (on) ctx.startService(i);
        else ctx.stopService(i);
    }
}
