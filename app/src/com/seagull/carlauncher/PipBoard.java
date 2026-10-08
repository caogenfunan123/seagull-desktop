package com.seagull.carlauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 画中画面板（批次 N 重写）—— 左右两块画布，单焦点，卡片化，空态大按钮。
 *
 * 参照 CarPlay Dashboard 的三条纪律移植到 VD 双画中画：
 *   ① 多卡片并排，但只有一个主焦点 —— 触摸只进焦点画布；
 *   ② 扫视优先 —— 空态是「＋ 选择应用」大按钮，不是一行小字；
 *   ③ 常驻 chrome 不被 App 覆盖 —— 卡片是普通 View 层，SurfaceView 在卡内。
 *
 * 单焦点规则（车机安全红线）：
 *   · 非焦点画布的第一下触摸 = 只切焦点，不吃进 App；给刚失焦的画布补 CANCEL
 *     （掐掉残留笔画，多指场景下的鬼拖痕全靠这个）；
 *   · 空画布没有 App 可误触，第一下触摸直接弹选择器（可发现性优先）。
 *
 * 卡片化：SurfaceView 保持方角不裁剪（clipToOutline 在部分设备对 SurfaceView
 * 失效，圆角等真机验证）。卡底比画布大出 {@link #CARD_PAD}，将来开真圆角
 * 不用改布局。
 *
 * 遮罩亮度：非焦点画布盖一层黑，透明度跟环境光三档（SensorManager.TYPE_LIGHT，
 * 公开 API 不要权限）：夜间 0.45 / 常态 0.35 / 白天强光 0.28。
 *
 * 常驻语义（批次 L）：VD 归进程级 MirrorHost，切走只断 Surface 不拆屏。
 */
public final class PipBoard extends LinearLayout {

    /** 宿主：长按画布 / 点空态按钮时由它弹应用选择。 */
    public interface Host {
        void onPickApp(int slot);
    }

    private static final String TAG = "SeagullPipBoard";
    private static final int REQ_CONSENT = 0x5EA4;
    private static final String PREFS = "seagull";

    private static final int CARD_PAD = 12;       // 卡底比画布大出的余量（将来真圆角用）
    private static final int CARD_GAP = 8;        // 两卡之间的缝
    private static final int BORDER_FOCUS_DP = 2; // 焦点描边宽
    private static final int FADE_MS = 150;       // 焦点/遮罩淡入，不做缩放弹跳（防分心）
    private static final float MASK_LOW = 0.28f;  // 白天强光：遮罩最薄，画面要看得清
    private static final float MASK_MID = 0.35f;  // 常态
    private static final float MASK_HI = 0.45f;   // 夜间：遮罩最厚
    private static final long LUX_INTERVAL_MS = 1000;
    private static final float LUX_NIGHT = 20f;   // 低于它算夜间（带回差）
    private static final float LUX_DAY = 2000f;   // 高于它算白天强光

    private final Activity act;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LauncherModel model;

    /**
     * 录屏会话：两个槽各持一份独立 MediaProjection（批次 Q 修"第二个画布里是
     * 第一个画布"）。一个 MediaProjection 只能建一块虚拟屏；两块画布共用一个
     * 会话时，第二块 createVirtualDisplay 出来的屏会把第一块画面直接复印过来
     * （用户实测的"递归套娃"）。用同一次授权结果分别取两份会话，各建各的 VD。
     * VD 归进程级 MirrorHost 常驻，退出页面只断 Surface 不收。
     */
    private final MediaProjection[] proj = new MediaProjection[3];
    private MediaProjection projection;   // 兼容旧字段：proj[1] 的别名，勿单独赋值

    private MediaProjection projFor(int which) {
        MediaProjection p = which >= 1 && which <= 2 ? proj[which] : null;
        return p != null ? p : projection;
    }
    private MirrorSlot slotA, slotB;
    private SurfaceView svA, svB;
    private TextView hintA, hintB;        // 画布中央状态行（启动中…/需要录屏授权…）
    private View emptyA, emptyB;          // 空态大按钮（空画布才有）
    private View maskA, maskB;            // 非焦点遮罩
    private View borderA, borderB;        // 焦点高亮描边
    private final boolean[] longFired = {false, false, false};
    /** armed[i] = 本手势（从 DOWN 起）发生在这块画布已是焦点的时候，才允许外注入。 */
    private final boolean[] armed = {false, false, false};
    /** 录屏授权去重标志（两个槽同时需要投影时只弹一次）。 */
    private boolean consentRequesting = false;

    /** 单焦点：只有它能收触摸。初始槽 1。 */
    private int focusSlot = 1;

    /** 环境光三档得出的遮罩透明度。 */
    private float maskAlpha = MASK_MID;

    public PipBoard(Activity act, Host host) {
        this(act, host, true);
    }

    /** autoDeploy=false 给自检页用：镜框照建，但不抢在自检前面部署。 */
    public PipBoard(Activity act, Host host, boolean autoDeploy) {
        this(act, host, autoDeploy, new LauncherModel(act));
    }

    public PipBoard(Activity act, Host host, boolean autoDeploy, LauncherModel model) {
        super(act);
        this.act = act;
        this.host = host;
        this.model = model != null ? model : new LauncherModel(act);
        setOrientation(HORIZONTAL);
        setBackgroundColor(Skin.c(R.color.ground));
        int pad = dp(4);
        setPadding(pad, pad, pad, pad);

        svA = new SurfaceView(act);
        svB = new SurfaceView(act);
        hintA = new TextView(act);
        hintB = new TextView(act);

        int wa = Math.max(1, this.model.pipWeightA);
        int wb = Math.max(1, this.model.pipWeightB);
        addView(column(1), new LinearLayout.LayoutParams(0, -1, wa));
        addView(column(2), new LinearLayout.LayoutParams(0, -1, wb));

        // 进程级常驻槽：上次退出没拆屏，直接拿回来接着用
        slotA = MirrorHost.slot(act, "pip1");
        slotB = MirrorHost.slot(act, "pip2");
        updateHints();
        applyFocusVisuals(false);
        if (autoDeploy) deployIfBound();
    }

    /* ------------------------- 生命周期（宿主转发） ------------------------- */

    public void onResume() {
        updateHints();
        applyFocusVisuals(false);
        ui.postDelayed(this::redeployIfPossible, 300);
        ui.postDelayed(this::selfHeal, 600);
    }

    /**
     * 宿主重建 UI（换肤 / 换壁纸等）时调：只摘待跑的 redeploy/selfHeal 回调。
     * Surface 不在这里处理——SurfaceView 随旧树 detach 时 surfaceDestroyed
     * 会自然走到 slot.detachSurface（见建卡处的 Callback）。
     */
    public void cancelPending() { ui.removeCallbacksAndMessages(null); }

    /** 退出只断 Surface —— 屏与应用留给 MirrorHost 常驻；没有活跃屏才收 token。 */
    public void onDestroy() {
        // postDelayed(redeployIfPossible/selfHeal) 必须摘：300ms 内 finish 时它们
        // 仍会对已销毁 Activity 的 SurfaceView 起后台线程
        ui.removeCallbacksAndMessages(null);
        if (slotA != null) slotA.detachSurface();
        if (slotB != null) slotB.detachSurface();
        if (MirrorSlot.activeCount() == 0) {
            stopProjections();
            PipProjectionService.stop(act);
        }
    }

    private void stopProjections() {
        for (int i = 1; i <= 2; i++) {
            if (proj[i] != null) {
                try { proj[i].stop(); } catch (Throwable ignore) {}
                proj[i] = null;
            }
        }
        projection = null;
    }

    /** 录屏授权结果。返回 true 表示本面板消费了这次回调。 */
    public boolean onActivityResult(int req, int res, Intent data) {
        if (req != REQ_CONSENT) return false;
        if (res != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "录屏授权被拒 → 投影兜底路径不可用");
            return true;
        }
        final int code = res;
        final Intent d = data;
        PipProjectionService.start(act);
        pollToken(code, d, 0);
        return true;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SensorManager sm = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        Sensor light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (light == null) return;   // 车机没环境光：遮罩固定在常态档
        try { sm.registerListener(lightListener, light, SensorManager.SENSOR_DELAY_NORMAL); }
        catch (Throwable t) { Log.w(TAG, "环境光注册失败: " + t); }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        SensorManager sm = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) { try { sm.unregisterListener(lightListener); } catch (Throwable ignore) {} }
    }

    /** 环境光 → 遮罩透明度。1s 采样 + 低通 + 三档带回差，别每帧动 alpha。 */
    private final SensorEventListener lightListener = new SensorEventListener() {
        private float avg = -1f;
        private long lastAt;

        @Override public void onSensorChanged(SensorEvent e) {
            if (e == null || e.values == null || e.values.length == 0) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastAt < LUX_INTERVAL_MS) return;
            lastAt = now;
            float lux = e.values[0];
            avg = (avg < 0f) ? lux : avg * 0.8f + lux * 0.2f;
            float target;
            if (avg < LUX_NIGHT) target = MASK_HI;
            else if (avg > LUX_DAY) target = MASK_LOW;
            else target = MASK_MID;
            if (Math.abs(target - maskAlpha) > 0.01f) {
                maskAlpha = target;
                applyMaskAlpha();
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int a) {}
    };

    /* ------------------------- 两张卡片（左右分割） ------------------------- */

    /**
     * 一块画布 = 一张卡：深色卡底 + 1px 半透明白描边（方角，SurfaceView 裁不圆），
     * 卡内 padding {@link #CARD_PAD} 比画布大一圈，将来开真圆角不露黑边。
     * 画布上方依次叠：状态行 / 空态大按钮 / 非焦点遮罩 / 点击接管 / 焦点描边。
     * 触摸统一走卡容器的 OnTouchListener（覆盖层都不是点击目标）。
     */
    private View column(int which) {
        FrameLayout card = new FrameLayout(act);
        card.setBackground(cardBg());
        int pad = dp(CARD_PAD);
        card.setPadding(pad, pad, pad, pad);
        card.setClipChildren(false);

        SurfaceView view = which == 1 ? svA : svB;
        card.addView(view, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = which == 1 ? hintA : hintB;
        hint.setText("长按选择应用");
        hint.setTextColor(Skin.c(R.color.text_dim));
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0x80000000);
        hint.setVisibility(View.GONE);
        card.addView(hint, new FrameLayout.LayoutParams(-1, -1));

        View empty = which == 1 ? (emptyA = emptyButton()) : (emptyB = emptyButton());
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-2, -2);
        ep.gravity = Gravity.CENTER;
        card.addView(empty, ep);
        // 空态大按钮自己收点击（它是可点击的 child，触摸不经卡容器）。
        // 【坑】卡内 SurfaceView 绝不能 setClickable(true)：一旦可点击，它会吞掉
        // 整块画布的触摸（批次 N 因此两块画布都弹不出选择器），卡容器的长按/转发
        // 全死。SurfaceView 保持不可点击，触摸统一走卡容器 OnTouchListener。

        View mask = new View(act);
        mask.setBackgroundColor(Color.BLACK);
        mask.setAlpha(0f);
        mask.setVisibility(View.GONE);
        if (which == 1) maskA = mask; else maskB = mask;
        card.addView(mask, new FrameLayout.LayoutParams(-1, -1));

        View border = new View(act);
        border.setBackground(borderBg(Skin.c(R.color.leaf), BORDER_FOCUS_DP));
        border.setAlpha(0f);
        if (which == 1) borderA = border; else borderB = border;
        card.addView(border, new FrameLayout.LayoutParams(-1, -1));

        // 单一触摸入口：焦点拦截 / 空态选择 / 手势转发全在这判
        final int w = which;
        empty.setOnClickListener(v -> openPick(w));   // 空态按钮是卡内可点击 child，自己收点击
        final GestureDetector gd = new GestureDetector(act,
                new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public void onLongPress(MotionEvent e) {
                longFired[w] = true;
                MirrorSlot s = slotOf(w);
                if (s != null && s.ready()) cancelStroke(s);   // 掐掉残留笔画再弹选择器
                openPick(w);
            }
        });
        card.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                if (w != focusSlot) {
                    // 安全红线：第一下只切焦点，不吃进 App。armed=false 让本手势
                    // 后续的 MOVE/UP 也不外泄：否则失焦瞬间的 UP 会被注进旧 App
                    // （地图里凭空多点一下），空画布上还会顺带弹选择器。
                    armed[w] = false;
                    switchFocus(w);
                    return true;
                }
                armed[w] = true;
                longFired[w] = false;
            }
            MirrorSlot s = slotOf(w);
            if (armed[w] && s != null && s.ready() && !longFired[w]) s.onTouch(e);
            if (armed[w]) gd.onTouchEvent(e);
            if (armed[w] && (s == null || !s.ready())) {
                // 没 App 可误触：焦点态下点一下直接选应用（可发现性优先）
                if (a == MotionEvent.ACTION_UP && !longFired[w]) openPick(w);
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                boolean tapped = armed[w] && a == MotionEvent.ACTION_UP && !longFired[w];
                armed[w] = false;
                if (tapped) healSlotSoon(w);
            }
            return true;
        });

        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {}
            @Override public void surfaceChanged(SurfaceHolder h, int f, int wd, int ht) {
                MirrorSlot s = slotOf(w);
                if (s == null || wd <= 0 || ht <= 0) return;
                attachTo(s, h.getSurface(), wd, ht);
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                MirrorSlot s = slotOf(w);
                if (s != null) s.detachSurface();
                updateHints();
            }
        });
        // 注意：这里绝不能 view.setClickable(true) —— 见上面 openPick 处的说明。
        return card;
    }

    private MirrorSlot slotOf(int which) { return which == 1 ? slotA : slotB; }

    /** 深色卡底 + 1px 半透明白描边。方角：真要圆角得先把 SurfaceView 换 TextureView。 */
    private GradientDrawable cardBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Skin.c(R.color.card));
        g.setStroke(dp(1), 0x40FFFFFF);
        g.setCornerRadius(0);
        return g;
    }

    private GradientDrawable borderBg(int color, int widthDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setStroke(dp(widthDp), color);
        return g;
    }

    /** 空态大按钮：≥80dp 高、16dp 圆角、半透明白描边、图标+文字上下排布。 */
    private View emptyButton() {
        TextView b = new TextView(act);
        b.setText("＋\n选择应用");
        b.setGravity(Gravity.CENTER);
        b.setTextColor(0xF0FFFFFF);
        b.setTextSize(18);
        b.setMinHeight(dp(80));
        b.setMinWidth(dp(160));
        b.setPadding(dp(24), dp(16), dp(24), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setStroke(dp(1), 0x80FFFFFF);
        g.setCornerRadius(dp(16));
        b.setBackground(g);
        return b;
    }

    /* ------------------------- 单焦点 ------------------------- */

    /**
     * 切焦点：给刚失焦的画布补一个 CANCEL —— 多指场景（一指在左画布拖着、
     * 二指点右画布）下，左 App 留着一道鬼拖痕全靠这个掐掉。守护中继通道
     * 会把 CANCEL 真的送进 App；命令通道本来就丢弃 CANCEL。
     */
    private void switchFocus(int to) {
        if (to == focusSlot) return;
        int from = focusSlot;
        focusSlot = to;
        Log.i(TAG, "焦点 槽" + from + " → 槽" + to);
        // 撤掉旧画布的在途手势武装：多指时一指正拖着旧画布，此后它的 MOVE/UP
        // 必须全部作废（只补 CANCEL 不够，armed 不撤事件照样注进旧 App）。
        armed[from] = false;
        longFired[from] = false;
        cancelStroke(slotOf(from));
        applyFocusVisuals(true);
    }

    /** 外部导焦（MiniPlayer 空白区点击用）。 */
    public void focusSlot(int which) {
        if (which == 1 || which == 2) switchFocus(which);
    }

    private void cancelStroke(MirrorSlot s) {
        if (s == null || !s.ready()) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        try { s.onTouch(c); } catch (Throwable t) { Log.w(TAG, "失焦 CANCEL 失败: " + t); }
        c.recycle();
    }

    /** 焦点描边淡入；非焦点画布只盖一层环境光遮罩（不要多余控件）。 */
    private void applyFocusVisuals(boolean animate) {
        for (int i = 1; i <= 2; i++) {
            View border = i == 1 ? borderA : borderB;
            View mask = i == 1 ? maskA : maskB;
            boolean focus = (i == focusSlot);
            if (border != null) {
                if (animate) border.animate().alpha(focus ? 1f : 0f).setDuration(FADE_MS).start();
                else border.setAlpha(focus ? 1f : 0f);
            }
            boolean bound = !pkgOf(i).isEmpty();
            if (mask != null) {
                mask.setVisibility(!focus && bound ? View.VISIBLE : View.GONE);
                if (!focus && bound) {
                    if (animate) mask.animate().alpha(maskAlpha).setDuration(FADE_MS).start();
                    else mask.setAlpha(maskAlpha);
                }
            }
        }
    }

    private void applyMaskAlpha() {
        for (int i = 1; i <= 2; i++) {
            View mask = i == 1 ? maskA : maskB;
            if (mask != null && mask.getVisibility() == View.VISIBLE) {
                mask.animate().alpha(maskAlpha).setDuration(FADE_MS).start();
            }
        }
    }

    /* ------------------------- 提示 / 空态显隐 ------------------------- */

    private void updateHints() {
        setHint(1, slotA);
        setHint(2, slotB);
        applyFocusVisuals(false);
    }

    private void setHint(int which, MirrorSlot s) {
        TextView hint = which == 1 ? hintA : hintB;
        View empty = which == 1 ? emptyA : emptyB;
        if (hint == null) return;
        String pkg = pkgOf(which);
        if (pkg == null || pkg.isEmpty()) {
            // 空态：大按钮占位，状态行藏起
            hint.setVisibility(View.GONE);
            if (empty != null) empty.setVisibility(View.VISIBLE);
            return;
        }
        if (empty != null) empty.setVisibility(View.GONE);
        if (s != null && s.ready()) { hint.setVisibility(View.GONE); return; }
        if (s != null && s.needsProjection()) hint.setText("需要录屏授权…");
        else if (s != null && s.displayId() > 0) hint.setText("挂载中…");
        else hint.setText("启动中…");
        hint.setVisibility(View.VISIBLE);
    }

    /* ------------------------- 授权链 ------------------------- */

    public void requestConsent() {
        // 去重：两个槽同时要投影时会连弹两次授权，旧 MediaProjection 被直接覆盖
        // （不 stop 不反注册回调，录屏会话泄漏到进程结束）
        if (consentRequesting) { Log.i(TAG, "录屏授权进行中，跳过重复申请"); return; }
        consentRequesting = true;
        // allowProjectMedia 是 su 命令（数百 ms），丢后台；startActivityForResult 回主线程
        new Thread(() -> {
            RootOps.allowProjectMedia(act);
            ui.post(() -> {
                try {
                    MediaProjectionManager mpm = (MediaProjectionManager)
                            act.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                    act.startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CONSENT);
                } catch (Throwable t) {
                    Log.w(TAG, "申请录屏失败: " + t);
                } finally {
                    consentRequesting = false;
                }
            });
        }, "pip-consent").start();
    }

    private void pollToken(int code, Intent data, int tries) {
        if (PipProjectionService.running) {
            try {
                MediaProjectionManager mpm =
                        (MediaProjectionManager) act.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                MediaProjection fresh = mpm.getMediaProjection(code, data);
                if (fresh == null) { Log.w(TAG, "getMediaProjection 返回 null"); return; }
                // 覆盖前先停旧的：否则旧录屏会话 + 回调泄漏到进程结束
                stopProjections();
                proj[1] = fresh;
                // 第二份会话（同一次授权结果再取一次）。拿不到就退而共用一份：
                // 那样槽 2 的 VD 会复印槽 1 的画面，日志留证，至少能定位。
                MediaProjection second = null;
                try {
                    second = mpm.getMediaProjection(code, data);
                } catch (Throwable t) {
                    Log.w(TAG, "第二份投影会话取得失败: " + t);
                }
                proj[2] = second;
                if (second == null) {
                    proj[2] = fresh;
                    Log.w(TAG, "拿不到第二份投影会话：槽 2 将复用槽 1 的（画面可能嵌套，"
                            + "看 dumpsys display | grep -i virtual 只有一块屏）");
                } else {
                    Log.i(TAG, "两份投影会话就绪：两槽各自建各自的虚拟屏");
                }
                projection = fresh;
                MediaProjection.Callback cb = new MediaProjection.Callback() {
                    @Override public void onStop() {
                        Log.w(TAG, "录屏被系统撤销");
                        MirrorHost.onProjectionStopped(act);
                    }
                };
                fresh.registerCallback(cb, ui);
                if (second != null) second.registerCallback(cb, ui);
                deployIfBound();
            } catch (Throwable t) {
                Log.w(TAG, "getMediaProjection 失败: " + t);
            }
            return;
        }
        if (tries > 10) { Log.w(TAG, "投屏前台服务未能启动（轮询超时）"); return; }
        ui.postDelayed(() -> pollToken(code, data, tries + 1), 200);
    }

    /* ------------------------- 部署 ------------------------- */

    /** 有绑定的画布就自动部署（进页面即画中画，不需要用户点任何按钮）。 */
    public void deployIfBound() {
        deploySlot(1);
        deploySlot(2);
    }

    private void deploySlot(int which) {
        MirrorSlot slot = slotOf(which);
        if (slot == null) return;
        String pkg = pkgOf(which);
        if (pkg == null || pkg.isEmpty()) { setHint(which, slot); return; }
        SurfaceView svView = which == 1 ? svA : svB;
        Surface surf = (svView != null && svView.getHolder().getSurface() != null
                && svView.getHolder().getSurface().isValid()) ? svView.getHolder().getSurface() : null;
        // VD 严格按画布实际像素 1:1：量到用实测，没量到按权重比例算单块设计尺寸
        int w = svView != null && svView.getWidth() > 0 ? svView.getWidth() : designW(which);
        int h = svView != null && svView.getHeight() > 0 ? svView.getHeight()
                : Math.max(1, act.getResources().getDisplayMetrics().heightPixels - dp(150));
        final Surface f = surf;
        final int fw = w, fh = h;
        final MirrorSlot s = slot;
        final MediaProjection mp = projFor(which);
        setHint(which, slot);
        new Thread(() -> {
            // 同一槽的部署/挂面串行：并行会双建 VD
            synchronized (s) {
                boolean ok = s.deploy(mp, pkg, f, fw, fh, dpiFit(fw, fh));
                final boolean needProj = s.needsProjection();
                ui.post(() -> {
                    setHint(which, s);
                    // 两块画布抢同一块 VD 的检测网：哪块屏被两个槽同时握着，
                    // 槽 2 直接拆掉并报错。静默共屏的后果就是"画布里套着画布"。
                    MirrorSlot other = slotOf(which == 1 ? 2 : 1);
                    if (s.displayId() > 0 && other != null && other.displayId() == s.displayId()) {
                        Log.e(TAG, "槽 " + which + " 与对槽共用 displayId=" + s.displayId()
                                + "（VD 独立失败，画面会嵌套）→ 拆掉本槽");
                        s.teardown();
                    }
                    Log.i(TAG, "槽 " + which + " → " + s.describe() + (ok ? "" : " [失败]"));
                    if (needProj && mp == null) requestConsent();
                });
            }
        }, "pip-deploy" + which).start();
    }

    /**
     * 画布像素 → VD 密度（批次 Q 修"高德四周黑边"）。
     *
     * 黑边的主因不是 VD 尺寸，是密度：直接把手机 densityDpi（440）传给一块
     * 366px 宽的小屏，应用看到的是 ~85dp 视口，多数 App（高德）把它当小屏，
     * 按自己声明的最小宽度渲染后居中留黑边（size compat 表现）。
     * 折成 280dp 基准再反算 dpi：画布 366px @ 209dpi ≈ 280dp 宽，与一台紧凑
     * 手机视口同量级，应用按正常手机版式铺满。触摸坐标不受 dpi 影响（仍 1:1）。
     */
    private int dpiFit(int w, int h) {
        int def = act.getResources().getDisplayMetrics().densityDpi;
        int targetDp = 280;                       // 紧凑手机视口宽
        int byW = Math.round(w * 160f / targetDp);
        int byH = Math.round(h * 160f / targetDp);
        int v = Math.max(byW, byH);               // 取大：宽高两侧视口都不超过目标 dp
        if (v < 160) v = 160;                      // 再小就 mdpi，低于它应用会按低密度锯齿渲染
        if (v > def) v = def;                      // 不超过设备默认，别让字大过设计稿
        return v;
    }

    /** 没量到画布时的兜底宽度：整屏扣掉卡片留白，按权重比例切。 */
    private int designW(int which) {
        int avail = act.getResources().getDisplayMetrics().widthPixels
                - dp(CARD_PAD * 4) - dp(CARD_GAP) - dp(8);
        int wa = Math.max(1, model.pipWeightA);
        int wb = Math.max(1, model.pipWeightB);
        int w = which == 1 ? wa : wb;
        return Math.max(1, (int) ((long) avail * w / (wa + wb)));
    }

    /** 给外部（选了新应用后）调：部署指定槽。 */
    public void deploySlotPublic(int which) { deploySlot(which); }

    private void attachTo(MirrorSlot slot, Surface surface, int w, int h) {
        if (surface == null || !surface.isValid()) return;
        final int which = slot == slotA ? 1 : 2;
        final String pkg = pkgOf(which);
        if (pkg == null || pkg.isEmpty()) { setHint(which, slot); return; }
        final MirrorSlot s = slot;
        final MediaProjection mp = projFor(which);
        final int dpi = dpiFit(w, h);
        new Thread(() -> {
            // 同槽串行，理由见 deploySlot
            synchronized (s) {
                boolean ok = s.attachSurface(surface, w, h, dpi);
                if (!ok) ok = s.deploy(mp, pkg, surface, w, h, dpi);
                final boolean needProj = s.needsProjection();
                ui.post(() -> {
                    setHint(which, s);
                    Log.i(TAG, "槽 " + which + " → " + s.describe()
                            + " 画布 " + w + "x" + h + "@" + dpi + "dpi");
                    if (needProj && mp == null) requestConsent();
                });
            }
        }, "pip-attach" + which).start();
    }

    private void redeployIfPossible() {
        if (slotA != null && !slotA.ready() && svA != null && svA.getHolder().getSurface() != null
                && svA.getHolder().getSurface().isValid() && notEmpty(pkgOf(1))) {
            attachTo(slotA, svA.getHolder().getSurface(), svA.getWidth(), svA.getHeight());
        }
        if (slotB != null && !slotB.ready() && svB != null && svB.getHolder().getSurface() != null
                && svB.getHolder().getSurface().isValid() && notEmpty(pkgOf(2))) {
            attachTo(slotB, svB.getHolder().getSurface(), svB.getWidth(), svB.getHeight());
        }
    }

    /** 保守自愈：解析不出栈结构就跳过，绝不重拉（m12 教训）。应用真缺席（画中画里按了返回）才节流重拉。 */
    private void selfHeal() {
        for (int i = 1; i <= 2; i++) {
            MirrorSlot s = slotOf(i);
            if (s == null || s.displayId() <= 0 || !s.ready()) continue;
            String pkg = pkgOf(i);
            if (pkg == null || pkg.isEmpty()) continue;
            final int slotNo = i;
            new Thread(() -> {
                String out = RootOps.ensureOnDisplay(act, pkg, s.displayId());
                if (out != null && out.startsWith(RootOps.ABSENT)) {
                    MirrorHost.relaunchIfAbsent(act, slotNo, pkg, s.displayId());
                }
                if (out != null && !out.isEmpty()) Log.i(TAG, "自愈 槽 " + slotNo + ": " + out);
            }, "pip-heal" + i).start();
        }
    }

    /**
     * 点一下之后的兜底自愈（批次 Z）：网易云这类 singleTask 应用在虚拟屏里跳页时，
     * 新 Activity 常被系统按「默认屏」拉起，整块任务被拉回主屏 —— 画布黑掉、主屏
     * 反被它全屏盖住，用户描述就是「点一下跳主屏」。
     *
     * 为什么要有这条：模块的 setLaunchDisplayId 注入只在模块真被注进目标进程时才生效
     * （框架/作用域任一环没到位就静默失效）；这条走 root 的 am task move-task，不依赖
     * 任何框架，是模块路线的兜底。补两次延时（600ms / 1600ms）：跳页的开窗有几帧延迟，
     * 一次可能扑空。
     */
    private void healSlotSoon(final int which) {
        ui.postDelayed(() -> healSlotNow(which), 600);
        ui.postDelayed(() -> healSlotNow(which), 1600);
    }

    private void healSlotNow(int which) {
        MirrorSlot s = slotOf(which);
        if (s == null || s.displayId() <= 0 || !s.ready()) return;
        final String pkg = pkgOf(which);
        if (pkg == null || pkg.isEmpty()) return;
        final int d = s.displayId();
        final int slotNo = which;
        new Thread(() -> {
            String out = RootOps.ensureOnDisplay(act, pkg, d);
            if (out != null && !out.isEmpty()) Log.i(TAG, "点后自愈 槽 " + slotNo + ": " + out);
        }, "pip-tap-heal" + which).start();
    }

    private boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    private String pkgOf(int which) { return loadPkg(act, which); }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }

    /* ------------------------- 应用选择 ------------------------- */

    /** 宿主长按/点空态后调它。选完即部署；「清空该槽」拆屏并停掉画中画里的应用。 */
    public static void showPicker(Activity act, int which, PipBoard board) {
        List<HomeActivity.AppEntry> apps = HomeActivity.loadApps(act);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Skin.c(R.color.ground));
        int p = (int) (14 * act.getResources().getDisplayMetrics().density);
        col.setPadding(p, p - 2, p, p - 2);

        TextView title = new TextView(act);
        title.setText("槽 " + (which == 1 ? "A" : "B") + " 选应用");
        title.setTextColor(Skin.c(R.color.text));
        title.setTextSize(16);
        col.addView(title);

        for (HomeActivity.AppEntry e : apps) {
            TextView tv = new TextView(act);
            tv.setText(e.label + "   " + e.pkg);
            tv.setTextColor(Skin.c(R.color.text));
            tv.setTextSize(13);
            tv.setPadding(p, p - 4, p, p - 4);
            tv.setBackground(Skin.round(Skin.c(R.color.card), 12f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = p / 2;
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                savePkg(act, which, e.pkg);
                Log.i(TAG, "槽 " + which + " → " + e.pkg);
                if (board != null) board.deploySlotPublic(which);
                ((Dialog) v.getTag()).dismiss();
            });
            col.addView(tv);
        }

        ScrollView sv = new ScrollView(act);
        sv.addView(col);
        Dialog dlg = new android.app.AlertDialog.Builder(act)
                .setView(sv)
                .setNeutralButton("清空该槽", (d, w) -> {
                    MirrorHost.clear(act, which);
                    if (board != null) board.updateHintsPublic();
                })
                .setNegativeButton("关闭", null)
                .create();
        // dialog 建好后挂到每一行上，点完即关
        for (int i = 1; i < col.getChildCount(); i++) col.getChildAt(i).setTag(dlg);
        dlg.show();
    }

    private void openPick(int which) {
        if (host != null) host.onPickApp(which);
    }

    /** 供 showPicker 的「清空该槽」回调刷新提示。 */
    public void updateHintsPublic() { updateHints(); }

    /** 周期自愈入口（HomeActivity tick 调）。 */
    public void selfHealPublic() { selfHeal(); }

    public static String loadPkg(Context ctx, int which) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("mirror_pkg" + which, "");
    }

    public static void savePkg(Context ctx, int which, String pkg) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mirror_pkg" + which, pkg).apply();
    }
}
