package com.seagull.carlauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
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
 * 画中画面板（批次 M）—— 左右两块画布，长按选应用，别的一概没有。
 * 桌面（HomeActivity）与镜像页（MirrorActivity）共用同一块面板；
 * VD 归进程级 MirrorHost 常驻：切走只断 Surface，不拆屏。
 *
 * 交互约定（用户原话："界面只要两个画布！长按选择应用！不要多余的东西！"）：
 *   · 长按画布 → 宿主弹应用列表（长按被 GestureDetector 截走，先给 TouchForward 补 CANCEL）；
 *   · 点 / 滑 / 拖 → 照常转发进画中画里的应用；
 *   · 空画布只有一行居中提示，其余信息全进 logcat。
 */
public final class PipBoard extends LinearLayout {

    /** 宿主：长按画布时由它弹应用选择；回调用 showPicker 即可。 */
    public interface Host {
        void onPickApp(int slot);
    }

    private static final String TAG = "SeagullPipBoard";
    private static final int REQ_CONSENT = 0x5EA4;
    private static final String PREFS = "seagull";

    private final Activity act;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 录屏 token 只服务投影兜底路径；VD 常驻，退出页面不收。 */
    private MediaProjection projection;
    private MirrorSlot slotA, slotB;
    private SurfaceView svA, svB;
    private TextView hintA, hintB;

    public PipBoard(Activity act, Host host) {
        this(act, host, true);
    }

    /** autoDeploy=false 给自检页用：镜框照建，但不抢在自检前面部署。 */
    public PipBoard(Activity act, Host host, boolean autoDeploy) {
        super(act);
        this.act = act;
        this.host = host;
        setOrientation(HORIZONTAL);
        setBackgroundColor(Skin.c(R.color.ground));
        int pad = dp(6);
        setPadding(pad, pad, pad, pad);

        svA = new SurfaceView(act);
        svB = new SurfaceView(act);
        hintA = new TextView(act);
        hintB = new TextView(act);
        addView(canvasBox(1), new LinearLayout.LayoutParams(0, -1, 1f));
        addView(canvasBox(2), new LinearLayout.LayoutParams(0, -1, 1f));

        // 进程级常驻槽：上次退出没拆屏，直接拿回来接着用
        slotA = MirrorHost.slot(act, "pip1");
        slotB = MirrorHost.slot(act, "pip2");
        updateHints();
        if (autoDeploy) deployIfBound();
    }

    /* ------------------------- 生命周期（宿主转发） ------------------------- */

    public void onResume() {
        updateHints();
        ui.postDelayed(this::redeployIfPossible, 300);
        ui.postDelayed(this::selfHeal, 600);
    }

    /** 退出只断 Surface —— 屏与应用留给 MirrorHost 常驻；没有活跃屏才收 token。 */
    public void onDestroy() {
        if (slotA != null) slotA.detachSurface();
        if (slotB != null) slotB.detachSurface();
        if (MirrorSlot.activeCount() == 0) {
            if (projection != null) { try { projection.stop(); } catch (Throwable ignore) {} }
            PipProjectionService.stop(act);
        }
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

    /* ------------------------- 两块画布（左右分割） ------------------------- */

    private View canvasBox(int which) {
        FrameLayout box = new FrameLayout(act);
        box.setBackgroundColor(Color.BLACK);

        SurfaceView view = which == 1 ? svA : svB;
        box.addView(view, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = which == 1 ? hintA : hintB;
        hint.setText("长按选择应用");
        hint.setTextColor(Skin.c(R.color.text_dim));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0x80000000);
        box.addView(hint, new FrameLayout.LayoutParams(-1, -1));

        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {}
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
                MirrorSlot s = which == 1 ? slotA : slotB;
                if (s == null || w <= 0 || hh <= 0) return;
                attachTo(s, h.getSurface(), w, hh);
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                MirrorSlot s = which == 1 ? slotA : slotB;
                if (s != null) s.detachSurface();
                updateHints();
            }
        });

        // 长按 = 选应用（不转发进画中画）；其余手势全部转发
        final boolean[] longFired = {false};
        GestureDetector gd = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public void onLongPress(MotionEvent e) {
                longFired[0] = true;
                MirrorSlot s = which == 1 ? slotA : slotB;
                if (s != null && s.ready()) s.onTouch(cancelAt(e));
                if (host != null) host.onPickApp(which);
            }
        });
        view.setOnTouchListener((v, e) -> {
            MirrorSlot s = which == 1 ? slotA : slotB;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) longFired[0] = false;
            if (s != null && s.ready() && !longFired[0]) s.onTouch(e);
            gd.onTouchEvent(e);
            return true;
        });
        view.setFocusable(true);
        view.setClickable(true);
        return box;
    }

    private MotionEvent cancelAt(MotionEvent src) {
        MotionEvent c = MotionEvent.obtain(src);
        c.setAction(MotionEvent.ACTION_CANCEL);
        return c;
    }

    /* ------------------------- 提示文字 ------------------------- */

    private void updateHints() {
        setHint(1, slotA);
        setHint(2, slotB);
    }

    private void setHint(int which, MirrorSlot s) {
        TextView hint = which == 1 ? hintA : hintB;
        if (hint == null) return;
        String pkg = loadPkg(act, which);
        if (pkg == null || pkg.isEmpty()) {
            hint.setText("长按选择应用");
            hint.setVisibility(View.VISIBLE);
            return;
        }
        if (s != null && s.ready()) { hint.setVisibility(View.GONE); return; }
        if (s != null && s.needsProjection()) hint.setText("需要录屏授权…");
        else if (s != null && s.displayId() > 0) hint.setText("挂载中…");
        else hint.setText("启动中…");
        hint.setVisibility(View.VISIBLE);
    }

    /* ------------------------- 授权链 ------------------------- */

    public void requestConsent() {
        RootOps.allowProjectMedia(act);
        MediaProjectionManager mpm =
                (MediaProjectionManager) act.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            act.startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CONSENT);
        } catch (Throwable t) {
            Log.w(TAG, "申请录屏失败: " + t);
        }
    }

    private void pollToken(int code, Intent data, int tries) {
        if (PipProjectionService.running) {
            try {
                MediaProjectionManager mpm =
                        (MediaProjectionManager) act.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                projection = mpm.getMediaProjection(code, data);
                if (projection == null) { Log.w(TAG, "getMediaProjection 返回 null"); return; }
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() {
                        Log.w(TAG, "录屏被系统撤销");
                        MirrorHost.onProjectionStopped(act);
                    }
                }, ui);
                Log.i(TAG, "录屏 token 已取得，开始建屏并搬运应用");
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
        MirrorSlot slot = which == 1 ? slotA : slotB;
        if (slot == null) return;
        String pkg = loadPkg(act, which);
        if (pkg == null || pkg.isEmpty()) { setHint(which, slot); return; }
        SurfaceView svView = which == 1 ? svA : svB;
        Surface surf = (svView != null && svView.getHolder().getSurface() != null
                && svView.getHolder().getSurface().isValid()) ? svView.getHolder().getSurface() : null;
        // VD 严格按画布实际像素 1:1：量到用实测，没量到用左右分割后的单块设计尺寸
        int w = svView != null && svView.getWidth() > 0 ? svView.getWidth()
                : Math.max(1, act.getResources().getDisplayMetrics().widthPixels / 2 - dp(18));
        int h = svView != null && svView.getHeight() > 0 ? svView.getHeight()
                : Math.max(1, act.getResources().getDisplayMetrics().heightPixels - dp(150));
        final Surface f = surf;
        final int fw = w, fh = h;
        final MirrorSlot s = slot;
        setHint(which, slot);
        new Thread(() -> {
            // 同一槽的部署/挂面串行：并行会双建 VD（旧版 race 漏屏的坑）
            synchronized (s) {
                boolean ok = s.deploy(projection, loadPkg(act, which), f, fw, fh,
                        act.getResources().getDisplayMetrics().densityDpi);
                final boolean needProj = s.needsProjection();
                ui.post(() -> {
                    setHint(which, s);
                    Log.i(TAG, "槽 " + which + " → " + s.describe() + (ok ? "" : " [失败]"));
                    if (needProj && projection == null) requestConsent();
                });
            }
        }, "pip-deploy" + which).start();
    }

    /** 给外部（选了新应用后）调：部署指定槽。 */
    public void deploySlotPublic(int which) { deploySlot(which); }

    private void attachTo(MirrorSlot slot, Surface surface, int w, int h) {
        if (surface == null || !surface.isValid()) return;
        final int which = slot == slotA ? 1 : 2;
        final String pkg = loadPkg(act, which);
        if (pkg == null || pkg.isEmpty()) { setHint(which, slot); return; }
        final MirrorSlot s = slot;
        final int dpi = act.getResources().getDisplayMetrics().densityDpi;
        new Thread(() -> {
            // 同槽串行，理由见 deploySlot
            synchronized (s) {
                boolean ok = s.attachSurface(surface, w, h, dpi);
                if (!ok) ok = s.deploy(projection, pkg, surface, w, h, dpi);
                final boolean needProj = s.needsProjection();
                ui.post(() -> {
                    setHint(which, s);
                    Log.i(TAG, "槽 " + which + " → " + s.describe());
                    if (needProj && projection == null) requestConsent();
                });
            }
        }, "pip-attach" + which).start();
    }

    private void redeployIfPossible() {
        if (slotA != null && !slotA.ready() && svA != null && svA.getHolder().getSurface() != null
                && svA.getHolder().getSurface().isValid() && notEmpty(loadPkg(act, 1))) {
            attachTo(slotA, svA.getHolder().getSurface(), svA.getWidth(), svA.getHeight());
        }
        if (slotB != null && !slotB.ready() && svB != null && svB.getHolder().getSurface() != null
                && svB.getHolder().getSurface().isValid() && notEmpty(loadPkg(act, 2))) {
            attachTo(slotB, svB.getHolder().getSurface(), svB.getWidth(), svB.getHeight());
        }
    }

    /** 保守自愈：解析不出栈结构就跳过，绝不重拉（m12 教训）。 */
    private void selfHeal() {
        for (int i = 1; i <= 2; i++) {
            MirrorSlot s = i == 1 ? slotA : slotB;
            if (s == null || s.displayId() <= 0 || !s.ready()) continue;
            String pkg = loadPkg(act, i);
            if (pkg == null || pkg.isEmpty()) continue;
            final int slotNo = i;
            new Thread(() -> {
                String out = RootOps.ensureOnDisplay(act, pkg, s.displayId());
                if (out != null && !out.isEmpty()) Log.i(TAG, "自愈 槽 " + slotNo + ": " + out);
            }, "pip-heal" + i).start();
        }
    }

    private boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }

    /* ------------------------- 应用选择 ------------------------- */

    /** 长按画布后的宿主实现直接调它。选完即部署；「清空该槽」拆屏并停掉画中画里的应用。 */
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
            tv.setBackgroundColor(Skin.c(R.color.card));
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

    /** 供 showPicker 的「清空该槽」回调刷新提示。 */
    public void updateHintsPublic() { updateHints(); }

    public static String loadPkg(Context ctx, int which) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("mirror_pkg" + which, "");
    }

    public static void savePkg(Context ctx, int which, String pkg) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mirror_pkg" + which, pkg).apply();
    }
}
