package com.seagull.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
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
import android.widget.Toast;

import java.util.List;

/**
 * 画中画界面（批次 L 重制）—— 界面上只有两块画布：
 *   · 长按画布 → 选应用（已选的画布再长按可更换 / 清空）；
 *   · 点 / 滑 / 拖 = 转发进画中画里的应用；
 *   · 没有按钮、没有诊断区、没有机制说明，全部沉到 logcat（tag=SeagullMirrorAct）。
 *
 * 常驻语义（治「每次进入桌面还是应用界面」）：
 * VD 归进程级 MirrorHost 所有，退出本页只断 Surface、不拆屏 —— 拆屏会把
 * 屏上的任务倒回默认屏，桌面立刻冒出全屏应用（旧版就是这个症状）。
 * 再进来时把 Surface 挂回同一块 VD，画面秒恢复。
 *
 * 授权链（Android 14 硬规定）：createScreenCaptureIntent → 用户点「立即开始」
 *   → PipProjectionService 前台服务起 → getMediaProjection 取 token。
 * TRUSTED 屏建屏不需要 token（角色授予走 root），只有投影兜底路径才需要，
 * 缺 token 时 deploy 内部返回 needsProjection，这里自动补弹授权框。
 */
public class MirrorActivity extends BaseActivity {

    private static final String TAG = "SeagullMirrorAct";
    private static final int REQ_CONSENT = 0x5EA4;
    private static final String PREFS = "seagull";

    /** 外部（桌面 Dock）指定「把这个应用搬进 slot 号槽位」。 */
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_SLOT = "slot";

    /** 供桌面调用：打开画中画页并指定目标应用与槽位（1/2）。 */
    public static Intent intentFor(Context ctx, String pkg, int slot) {
        Intent i = new Intent(ctx, MirrorActivity.class);
        i.putExtra(EXTRA_PKG, pkg);
        i.putExtra(EXTRA_SLOT, slot);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return i;
    }

    /** 本次会话的录屏 token（投影兜底路径用；进程级 VD 常驻，退出页面不收）。 */
    private MediaProjection projection;
    private MirrorSlot slotA, slotB;
    private SurfaceView svA, svB;
    /** 画布中央的唯一文字：未绑定时提示长按，部署中/失败时给原因。 */
    private TextView hintA, hintB;
    private int pickingSlot;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        // 进程级常驻槽：上次退出时没拆屏，这里直接拿回来接着用
        slotA = MirrorHost.slot(this, "pip1");
        slotB = MirrorHost.slot(this, "pip2");
        if (getIntent() != null && getIntent().getBooleanExtra("selftest", false)) {
            SelfTestMirror.begin(this);
            return;
        }
        maybeAutoDeploy();
        updateHints();
    }

    /** 桌面指定了目标应用：先写进槽位，再自动部署（要 token 会自动弹授权）。 */
    private void maybeAutoDeploy() {
        if (getIntent() != null) {
            String pkg = getIntent().getStringExtra(EXTRA_PKG);
            if (pkg != null && !pkg.isEmpty()) {
                int slot = getIntent().getIntExtra(EXTRA_SLOT, 1);
                if (slot < 1 || slot > 2) slot = 1;
                savePkg(slot, pkg);
                Log.i(TAG, "桌面指定：槽 " + slot + " → " + pkg);
            }
        }
        if (notEmpty(loadPkg(1)) || notEmpty(loadPkg(2))) deployNow();
    }

    @Override protected void onResume() {
        super.onResume();
        updateHints();
        // surface 回来就挂回同一块 VD；稍后再跑一次目标任务自愈
        ui.postDelayed(this::redeployIfPossible, 300);
        ui.postDelayed(this::selfHeal, 600);
    }

    /**
     * 退出只断 Surface —— VD 与目标任务都留给 MirrorHost 常驻，
     * 绝不在这里拆屏（拆屏 = 任务倒回默认屏 = 桌面冒出全屏应用）。
     * 一块活跃屏都不剩时才收录屏 token 与前台服务。
     */
    @Override protected void onDestroy() {
        if (slotA != null) slotA.detachSurface();
        if (slotB != null) slotB.detachSurface();
        if (MirrorSlot.activeCount() == 0) {
            if (projection != null) { try { projection.stop(); } catch (Throwable ignore) {} }
            PipProjectionService.stop(this);
        }
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /* ------------------------- 界面：只有两块画布 ------------------------- */

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.c(R.color.ground));
        root.setPadding(dp(10), dp(8), dp(10), dp(12));

        TextView back = new TextView(this);
        back.setText("← 画中画");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(13);
        back.setPadding(dp(2), dp(2), dp(2), dp(6));
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-2, -2));

        svA = new SurfaceView(this);
        svB = new SurfaceView(this);
        hintA = new TextView(this);
        hintB = new TextView(this);
        root.addView(canvasBox(1));
        root.addView(canvasBox(2));

        TextView tip = new TextView(this);
        tip.setText("长按画布选择应用；已选的画布再长按可更换 / 清空");
        tip.setTextColor(Skin.c(R.color.text_dim));
        tip.setTextSize(11);
        tip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(6);
        root.addView(tip, tp);
        return root;
    }

    /** 一块画布：黑底 SurfaceView + 居中提示，两槽各占一半高度。 */
    private View canvasBox(int which) {
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);

        SurfaceView view = which == 1 ? svA : svB;
        box.addView(view, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = which == 1 ? hintA : hintB;
        hint.setText("长按选择应用");
        hint.setTextColor(Skin.c(R.color.text_dim));
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0x80000000);
        box.addView(hint, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 0, 1f);
        p.topMargin = dp(6);
        box.setLayoutParams(p);

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
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public void onLongPress(MotionEvent e) {
                longFired[0] = true;
                MirrorSlot s = which == 1 ? slotA : slotB;
                if (s != null && s.ready()) {
                    // 把这轮手势掐掉：TouchForward 收到 CANCEL 会丢弃残留笔画
                    s.onTouch(cancelAt(e));
                }
                openPicker(which);
            }
        });
        view.setOnTouchListener((v, e) -> {
            MirrorSlot s = which == 1 ? slotA : slotB;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) longFired[0] = false;
            if (s != null && s.ready() && !longFired[0]) {
                s.onTouch(e);   // 点/滑/拖/长按进应用（长按已被上面截走）
            }
            gd.onTouchEvent(e);
            return true;
        });
        view.setFocusable(true);
        view.setClickable(true);
        return box;
    }

    /** 复制一个 CANCEL 事件，坐标沿用原手势落点。 */
    private MotionEvent cancelAt(MotionEvent src) {
        MotionEvent c = MotionEvent.obtain(src);
        c.setAction(MotionEvent.ACTION_CANCEL);
        return c;
    }

    /** 画布中央文字：未绑定 → 长按选择应用；成功 → 藏起；其余给原因。 */
    private void updateHints() {
        setHint(1, slotA);
        setHint(2, slotB);
    }

    private void setHint(int which, MirrorSlot s) {
        TextView hint = which == 1 ? hintA : hintB;
        if (hint == null) return;
        String pkg = loadPkg(which);
        if (pkg == null || pkg.isEmpty()) {
            hint.setText("长按选择应用");
            hint.setVisibility(View.VISIBLE);
            return;
        }
        if (s != null && s.ready()) {
            hint.setVisibility(View.GONE);
            return;
        }
        if (s != null && s.displayId() > 0) {
            hint.setText("挂载中…");
        } else if (s != null && s.needsProjection()) {
            hint.setText("需要录屏授权…");
        } else {
            hint.setText("启动中…");
        }
        hint.setVisibility(View.VISIBLE);
    }

    /* ------------------------- 授权链 ------------------------- */

    private void requestConsent() {
        RootOps.allowProjectMedia(this);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CONSENT);
        } catch (Throwable t) {
            Log.w(TAG, "申请录屏失败: " + t);
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (SelfTestMirror.isOurRequest(req)) {
            SelfTestMirror.onConsent(this, res, data);
            return;
        }
        if (req != REQ_CONSENT) return;
        if (res != RESULT_OK || data == null) {
            Log.w(TAG, "录屏授权被拒 → 投影兜底路径不可用");
            Toast.makeText(this, "没有录屏授权，TRUSTED 屏也失败了，画中画不可用", Toast.LENGTH_LONG).show();
            return;
        }
        final int code = res;
        final Intent d = data;
        PipProjectionService.start(this);
        pollToken(code, d, 0);
    }

    private void pollToken(int code, Intent data, int tries) {
        if (PipProjectionService.running) {
            try {
                MediaProjectionManager mpm =
                        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                projection = mpm.getMediaProjection(code, data);
                if (projection == null) { Log.w(TAG, "getMediaProjection 返回 null"); return; }
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() {
                        Log.w(TAG, "录屏被系统撤销");
                        // 受信屏不依赖投影，继续用；投影屏的 VD 已死，拆掉免得任务倒回主屏
                        MirrorHost.onProjectionStopped(MirrorActivity.this);
                    }
                }, ui);
                Log.i(TAG, "录屏 token 已取得，开始建屏并搬运应用");
                deployNow();
            } catch (Throwable t) {
                Log.w(TAG, "getMediaProjection 失败: " + t);
            }
            return;
        }
        if (tries > 10) { Log.w(TAG, "投屏前台服务未能启动（轮询超时）"); return; }
        ui.postDelayed(() -> pollToken(code, data, tries + 1), 200);
    }

    /* ------------------------- 部署 ------------------------- */

    private void attachTo(MirrorSlot slot, Surface surface, int w, int h) {
        if (surface == null || !surface.isValid()) return;
        final int which = slot == slotA ? 1 : 2;
        final String pkg = loadPkg(which);
        if (pkg == null || pkg.isEmpty()) { setHint(which, slot); return; }
        final MirrorSlot s = slot;
        final int dpi = getResources().getDisplayMetrics().densityDpi;
        new Thread(() -> {
            // VD 已建（常驻）→ 只挂 Surface 出画面；没建 → 完整部署
            boolean ok = s.attachSurface(surface, w, h, dpi);
            if (!ok) ok = s.deploy(projection, pkg, surface, w, h, dpi);
            final boolean needProj = s.needsProjection();
            ui.post(() -> {
                setHint(which, s);
                Log.i(TAG, "槽 " + which + " → " + s.describe());
                if (needProj && projection == null) {
                    Log.i(TAG, "TRUSTED 屏不可用，自动补录屏授权走投影兜底");
                    requestConsent();
                }
            });
        }, "deploy-pip" + which).start();
    }

    /**
     * 建屏 + 搬应用。VD 严格按画布实际像素 1:1 建（surfaceChanged 给的就是实测尺寸；
     * surface 还没量到时用「整宽 - 留白 × 屏高一半」兜底），画面不拉伸、触摸零换算。
     */
    private void deployNow() {
        final int dpi = getResources().getDisplayMetrics().densityDpi;
        deploySlot(slotA, 1, dpi);
        deploySlot(slotB, 2, dpi);
    }

    private void deploySlot(MirrorSlot slot, int which, int dpi) {
        if (slot == null) return;
        String pkg = loadPkg(which);
        if (pkg == null || pkg.isEmpty()) { setHint(which, slot); return; }
        SurfaceView svView = which == 1 ? svA : svB;
        Surface surf = (svView != null && svView.getHolder().getSurface() != null
                && svView.getHolder().getSurface().isValid()) ? svView.getHolder().getSurface() : null;
        int w = svView != null && svView.getWidth() > 0 ? svView.getWidth()
                : Math.max(1, getResources().getDisplayMetrics().widthPixels - dp(20));
        int h = svView != null && svView.getHeight() > 0 ? svView.getHeight()
                : Math.max(1, getResources().getDisplayMetrics().heightPixels / 2 - dp(40));
        final Surface f = surf;
        final int fw = w, fh = h;
        setHint(which, slot);
        new Thread(() -> {
            boolean ok = slot.deploy(projection, pkg, f, fw, fh, dpi);
            final boolean needProj = slot.needsProjection();
            ui.post(() -> {
                setHint(which, slot);
                Log.i(TAG, "槽 " + which + " → " + slot.describe() + (ok ? "" : " [失败]"));
                if (needProj && projection == null) requestConsent();
            });
        }, "deploy-pip" + which).start();
    }

    private void redeployIfPossible() {
        if (slotA != null && !slotA.ready() && svA != null && svA.getHolder().getSurface() != null
                && svA.getHolder().getSurface().isValid() && notEmpty(loadPkg(1))) {
            attachTo(slotA, svA.getHolder().getSurface(), svA.getWidth(), svA.getHeight());
        }
        if (slotB != null && !slotB.ready() && svB != null && svB.getHolder().getSurface() != null
                && svB.getHolder().getSurface().isValid() && notEmpty(loadPkg(2))) {
            attachTo(slotB, svB.getHolder().getSurface(), svB.getWidth(), svB.getHeight());
        }
    }

    /**
     * 目标任务自愈（carlink m10/m12 同款）：singleTask 应用在虚拟屏内跳转会落到
     * 默认屏、任务被整体拉走。解析不出栈结构就跳过，绝不重拉（m12 教训）。
     */
    private void selfHeal() {
        for (int i = 1; i <= 2; i++) {
            MirrorSlot s = i == 1 ? slotA : slotB;
            if (s == null || s.displayId() <= 0 || !s.ready()) continue;
            String pkg = loadPkg(i);
            if (pkg == null || pkg.isEmpty()) continue;
            final int slotNo = i;
            new Thread(() -> {
                String out = RootOps.ensureOnDisplay(this, pkg, s.displayId());
                if (out != null && !out.isEmpty()) {
                    Log.i(TAG, "自愈 槽 " + slotNo + ": " + out);
                }
            }, "self-heal" + i).start();
        }
    }

    private boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    /* ------------------------- 长按选应用 ------------------------- */

    private void openPicker(int which) {
        pickingSlot = which;
        List<HomeActivity.AppEntry> apps = HomeActivity.loadApps(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Skin.c(R.color.ground));
        col.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText("槽 " + (which == 1 ? "A" : "B") + " 选应用");
        title.setTextColor(Skin.c(R.color.text));
        title.setTextSize(16);
        col.addView(title);

        for (HomeActivity.AppEntry e : apps) {
            TextView tv = new TextView(this);
            tv.setText(e.label + "   " + e.pkg);
            tv.setTextColor(Skin.c(R.color.text));
            tv.setTextSize(13);
            tv.setPadding(dp(12), dp(9), dp(12), dp(9));
            tv.setBackgroundColor(Skin.c(R.color.card));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = dp(5);
            tv.setLayoutParams(p);
            tv.setOnClickListener(v -> {
                savePkg(which, e.pkg);
                Log.i(TAG, "槽 " + which + " → " + e.pkg);
                deploySlot(which == 1 ? slotA : slotB, which,
                        getResources().getDisplayMetrics().densityDpi);
                ((android.app.Dialog) v.getTag()).dismiss();
            });
            col.addView(tv);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        android.app.Dialog dlg = new android.app.AlertDialog.Builder(this)
                .setView(sv)
                .setNeutralButton("清空该槽", (d, w) -> {
                    MirrorHost.clear(this, which);
                    updateHints();
                })
                .setNegativeButton("关闭", null)
                .create();
        // 关掉对话框：把 dialog 挂到每行上，点完即关
        for (int i = 1; i < col.getChildCount(); i++) {
            col.getChildAt(i).setTag(dlg);
        }
        dlg.show();
    }

    private String loadPkg(int which) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("mirror_pkg" + which, "");
    }

    private void savePkg(int which, String pkg) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("mirror_pkg" + which, pkg).apply();
    }
}
