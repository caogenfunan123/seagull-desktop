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

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        model = new LauncherModel(this);
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
        lp.x = model.ballX < 0 ? dp(16) : model.ballX;
        lp.y = model.ballY < 0 ? screenH() - size - dp(32) : model.ballY;
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
                        if (!moved) tap();
                        else snap(lp);
                        return true;
                }
                return false;
            }
        });
        return v;
    }

    private final Runnable longPressRun = new Runnable() {
        @Override public void run() { startActivity(new Intent(BallService.this, GardenActivity.class)); }
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

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override public void onDestroy() {
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
