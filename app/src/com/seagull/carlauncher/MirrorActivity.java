package com.seagull.carlauncher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * L3 镜像小窗宿主 —— 把目标应用真正搬进虚拟屏并显示在一块 SurfaceView 上，触摸可回注。
 *
 * 授权时序（Android 14 硬规定，照参考实现逐条对齐）：
 *   createScreenCaptureIntent → RESULT_OK（用户同意）
 *      → 启动 foregroundServiceType=mediaProjection 的前台服务并完成 startForeground
 *         → getMediaProjection(resultCode, data)
 *            → createVirtualDisplay(...)   ← 早于任何一步都会抛 SecurityException
 */
public class MirrorActivity extends BaseActivity {

    private static final String TAG = "SeagullMirrorAct";
    private static final int REQ_CONSENT = 0x5EA4;
    private static final String PREFS = "seagull";

    /** 外部（桌面 Dock）指定「把这个应用搬进 slot 号槽位」。 */
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_SLOT = "slot";

    /** 供桌面调用：打开镜像页并指定目标应用与槽位（1/2）。 */
    public static Intent intentFor(android.content.Context ctx, String pkg, int slot) {
        Intent i = new Intent(ctx, MirrorActivity.class);
        i.putExtra(EXTRA_PKG, pkg);
        i.putExtra(EXTRA_SLOT, slot);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return i;
    }

    private MediaProjection projection;
    private MirrorSlot slotA, slotB;

    private LinearLayout rootCol, pickerBox;
    private TextView diag;
    private SurfaceView svA, svB;
    private View waitA, waitB;
    private TextView pickerTitle;
    private int pickingSlot = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        if (getIntent() != null && getIntent().getBooleanExtra("selftest", false)) {
            SelfTestMirror.begin(this);
        }
        slotA = new MirrorSlot(this, "pip1");
        slotB = new MirrorSlot(this, "pip2");
        maybeAutoDeploy();
    }

    /** 桌面 Dock 指定了目标应用：先写进槽位，再自动走录屏授权链。 */
    private void maybeAutoDeploy() {
        if (getIntent() == null) return;
        String pkg = getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null || pkg.isEmpty()) return;
        int slot = getIntent().getIntExtra(EXTRA_SLOT, 1);
        if (slot < 1 || slot > 2) slot = 1;
        savePkg(slot, pkg);
        appendDiag("桌面指定：槽 " + (slot == 1 ? "A" : "B") + " → " + pkg);
        ui.postDelayed(this::requestConsent, 500);
    }

    @Override protected void onResume() {
        super.onResume();
        updateDiag();
        // 回到前台时 surface 可能重建，重新挂载
        ui.postDelayed(this::redeployIfPossible, 400);
    }

    @Override protected void onDestroy() {
        if (slotA != null) slotA.teardown();
        if (slotB != null) slotB.teardown();
        if (projection != null) { try { projection.stop(); } catch (Throwable ignore) {} }
        PipProjectionService.stop(this);
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /* ------------------------- UI ------------------------- */

    private View build() {
        ScrollView sv = new ScrollView(this);
        rootCol = new LinearLayout(this);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.setBackgroundColor(Skin.c(R.color.ground));
        rootCol.setPadding(dp(16), dp(12), dp(16), dp(16));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("← 返回桌面");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(15);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView t = new TextView(this);
        t.setText("  L3 镜像小窗（应用真进虚拟屏）");
        t.setTextColor(Skin.c(R.color.text));
        t.setTextSize(17);
        head.addView(t);
        rootCol.addView(head);

        diag = new TextView(this);
        diag.setTextColor(Skin.c(R.color.text_dim));
        diag.setTextSize(11);
        diag.setTypeface(Typeface.MONOSPACE);
        diag.setBackgroundColor(Skin.c(R.color.panel));
        diag.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams dpt = new LinearLayout.LayoutParams(-1, -2);
        dpt.topMargin = dp(8);
        rootCol.addView(diag, dpt);

        // 授权按钮
        rootCol.addView(btn("① 申请录屏授权（必须先做这一步）", v -> requestConsent()));

        // 两个镜像槽
        rootCol.addView(section("槽 A"));
        svA = new SurfaceView(this);
        waitA = waitLabel("槽 A：未部署 —— 先授权，再选应用");
        rootCol.addView(slotBox(svA, waitA, 1));
        rootCol.addView(btn("槽 A 选应用", v -> openPicker(1)));
        rootCol.addView(btn("槽 A 清除", v -> { if (slotA != null) { slotA.teardown(); savePkg(1, ""); updateDiag(); } }));

        rootCol.addView(section("槽 B"));
        svB = new SurfaceView(this);
        waitB = waitLabel("槽 B：未部署");
        rootCol.addView(slotBox(svB, waitB, 2));
        rootCol.addView(btn("槽 B 选应用", v -> openPicker(2)));
        rootCol.addView(btn("槽 B 清除", v -> { if (slotB != null) { slotB.teardown(); savePkg(2, ""); updateDiag(); } }));

        rootCol.addView(section("诊断"));
        rootCol.addView(btn("刷新诊断", v -> updateDiag()));
        rootCol.addView(btn("看系统里有哪些虚拟屏", v -> {
            String s = RootOps.readDisplays();
            Toast.makeText(this, s, Toast.LENGTH_LONG).show();
            appendDiag("虚拟屏: " + s);
        }));
        rootCol.addView(btn("给应用列表刷新媒体/通知权限（root）", v -> {
            String s = RootOps.grantAll(this);
            appendDiag(s);
        }));

        TextView note = new TextView(this);
        note.setText("机制说明\n"
                + "· 建屏：MediaProjection.createVirtualDisplay（普通应用合法路径，不查 ADD_TRUSTED_DISPLAY）\n"
                + "· 搬应用：root `am start --display <id> -f 0x18000000 -n <组件>`\n"
                + "· 触摸：root `input -d <id> tap|swipe`（位移≥10px 判滑动，时长夹 50~2000ms）\n"
                + "· 已知限制：被镜像应用自身若调用 startActivity 跳主屏，会跳回 display 0（音乐类应用常见）");
        note.setTextColor(Skin.c(R.color.text_dim));
        note.setTextSize(10);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(14);
        rootCol.addView(note, np);

        sv.addView(rootCol);
        return sv;
    }

    private View slotBox(SurfaceView view, View wait, int which) {
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        box.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
        box.addView(wait, new android.widget.FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(200));
        p.topMargin = dp(8);
        box.setLayoutParams(p);

        // Surface 生命周期 + 触摸转发
        final MirrorSlot slot = which == 1 ? slotA : slotB;
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
                ui.post(() -> wait.setVisibility(View.VISIBLE));
            }
        });
        view.setOnTouchListener((v, e) -> {
            MirrorSlot s = which == 1 ? slotA : slotB;
            if (s != null && s.ready()) { s.onTouch(e); return true; }
            return false;
        });
        view.setFocusable(true);
        view.setClickable(true);
        return box;
    }

    private TextView waitLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Skin.c(R.color.text_dim));
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(0x80000000);
        return tv;
    }

    private TextView section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Skin.c(R.color.leaf));
        tv.setTextSize(13);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(18); p.bottomMargin = dp(4);
        tv.setLayoutParams(p);
        return tv;
    }

    private View btn(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Skin.c(R.color.text));
        tv.setTextSize(13);
        tv.setPadding(dp(12), dp(11), dp(12), dp(11));
        tv.setBackgroundColor(Skin.c(R.color.card));
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(7);
        tv.setLayoutParams(p);
        return tv;
    }

    /* ------------------------- 授权链 ------------------------- */

    private void requestConsent() {
        RootOps.allowProjectMedia(this);   // 前置：放开录屏 appops
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CONSENT);
        } catch (Throwable t) {
            appendDiag("申请录屏失败: " + t);
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
            appendDiag("录屏授权被拒 → 镜像不可用");
            return;
        }
        final int code = res;
        final Intent d = data;

        // ② 先起 mediaProjection 前台服务，轮询到 running 才允许取 token
        PipProjectionService.start(this);
        pollToken(code, d, 0);
    }

    private void pollToken(int code, Intent data, int tries) {
        if (PipProjectionService.running) {
            try {
                MediaProjectionManager mpm =
                        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                projection = mpm.getMediaProjection(code, data);
                if (projection == null) { appendDiag("getMediaProjection 返回 null"); return; }
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() {
                        ui.post(() -> {
                            if (slotA != null) slotA.teardown();
                            if (slotB != null) slotB.teardown();
                            PipProjectionService.stop(MirrorActivity.this);
                            appendDiag("录屏授权被系统撤销，镜像槽已清");
                        });
                    }
                }, ui);
                appendDiag("✔ 录屏 token 已取得；开始建屏并搬运应用（不等 Surface）");
                deployNow();
            } catch (Throwable t) {
                appendDiag("getMediaProjection 失败: " + t);
            }
            return;
        }
        if (tries > 10) { appendDiag("投屏前台服务未能启动（轮询超时）"); return; }
        ui.postDelayed(() -> pollToken(code, data, tries + 1), 200);
    }

    /* ------------------------- 部署 ------------------------- */

    private void attachTo(MirrorSlot slot, Surface surface, int w, int h) {
        if (projection == null || surface == null || !surface.isValid()) return;
        final int which = slot == slotA ? 1 : 2;
        final String pkg = loadPkg(which);
        if (pkg == null || pkg.isEmpty()) return;
        final MirrorSlot s = slot;
        final int dpi = getResources().getDisplayMetrics().densityDpi;
        new Thread(() -> {
            // VD 已建 → 只挂 Surface 出画面；未建 → 完整部署
            boolean attached = s.attachSurface(surface, w, h, dpi);
            if (!attached) attached = s.deploy(projection, pkg, surface, w, h, dpi);
            final boolean ok = attached;   // 供内层 lambda 捕获
            ui.post(() -> {
                View wait = s == slotA ? waitA : waitB;
                if (wait != null) wait.setVisibility(ok ? View.GONE : View.VISIBLE);
                appendDiag(s.describe());
            });
        }, "deploy-" + pkg).start();
    }

    /**
     * 建屏 + 搬应用。Surface 可能还没就绪，照样先建屏（已验证：MediaProjection 建屏
     * 不依赖 Surface，root 也能把应用搬上去），Surface 一到再 attachSurface 出画面。
     */
    private void deployNow() {
        if (projection == null) { appendDiag("先申请录屏授权"); return; }
        // 用屏幕尺寸建屏（没有 Surface 时以槽的期望尺寸为准）
        int w = getResources().getDisplayMetrics().widthPixels / 2;
        int h = Math.max(540, getResources().getDisplayMetrics().heightPixels / 3);
        final int dpi = getResources().getDisplayMetrics().densityDpi;
        deploySlot(slotA, 1, w, h, dpi);
        deploySlot(slotB, 2, w, h, dpi);
    }

    private void deploySlot(MirrorSlot slot, int which, int w, int h, int dpi) {
        if (slot == null || projection == null) return;
        String pkg = loadPkg(which);
        if (pkg == null || pkg.isEmpty()) { appendDiag("槽 " + (which == 1 ? "A" : "B") + " 未选应用，跳过"); return; }
        SurfaceView svView = which == 1 ? svA : svB;
        Surface surf = (svView != null && svView.getHolder().getSurface() != null
                && svView.getHolder().getSurface().isValid()) ? svView.getHolder().getSurface() : null;
        final int fw = (svView != null && svView.getWidth() > 0) ? svView.getWidth() : w;
        final int fh = (svView != null && svView.getHeight() > 0) ? svView.getHeight() : h;
        new Thread(() -> {
            boolean ok = slot.deploy(projection, pkg, surf, fw, fh, dpi);
            ui.post(() -> {
                appendDiag("槽 " + (which == 1 ? "A" : "B") + " → " + slot.describe()
                        + (ok ? "" : "  [失败]"));
                View wait = which == 1 ? waitA : waitB;
                if (wait != null) wait.setVisibility(ok && slot.ready() ? View.GONE : View.VISIBLE);
            });
        }, "deploy" + which).start();
    }

    private void redeployIfPossible() {
        if (projection == null) return;
        if (slotA != null && !slotA.ready() && svA != null && svA.getHolder().getSurface() != null
                && svA.getHolder().getSurface().isValid() && notEmpty(loadPkg(1))) {
            attachTo(slotA, svA.getHolder().getSurface(), svA.getWidth(), svA.getHeight());
        }
        if (slotB != null && !slotB.ready() && svB != null && svB.getHolder().getSurface() != null
                && svB.getHolder().getSurface().isValid() && notEmpty(loadPkg(2))) {
            attachTo(slotB, svB.getHolder().getSurface(), svB.getWidth(), svB.getHeight());
        }
    }

    private boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    /* ------------------------- 应用选择 ------------------------- */

    private void openPicker(int which) {
        pickingSlot = which;
        List<HomeActivity.AppEntry> apps = HomeActivity.loadApps(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Skin.c(R.color.ground));
        col.setPadding(dp(16), dp(14), dp(16), dp(16));

        pickerTitle = new TextView(this);
        pickerTitle.setText("给槽 " + (which == 1 ? "A" : "B") + " 选一个应用（共 " + apps.size() + " 个）");
        pickerTitle.setTextColor(Skin.c(R.color.text));
        pickerTitle.setTextSize(16);
        col.addView(pickerTitle);
        pickerBox = col;

        for (HomeActivity.AppEntry e : apps) {
            TextView tv = new TextView(this);
            tv.setText(e.label + "   " + e.pkg);
            tv.setTextColor(Skin.c(R.color.text));
            tv.setTextSize(13);
            tv.setPadding(dp(12), dp(10), dp(12), dp(10));
            tv.setBackgroundColor(Skin.c(R.color.card));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = dp(6);
            tv.setLayoutParams(p);
            tv.setOnClickListener(v -> {
                savePkg(pickingSlot, e.pkg);
                appendDiag("槽 " + (pickingSlot == 1 ? "A" : "B") + " → " + e.pkg);
                if (projection != null) {
                    final int slotNo = pickingSlot;
                    deploySlot(slotNo == 1 ? slotA : slotB, slotNo,
                            getResources().getDisplayMetrics().widthPixels / 2,
                            Math.max(540, getResources().getDisplayMetrics().heightPixels / 3),
                            getResources().getDisplayMetrics().densityDpi);
                } else {
                    appendDiag("还没授权录屏，先点「申请录屏授权」");
                }
            });
            col.addView(tv);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setView(sv).setNegativeButton("关闭", null).create();
        dlg.show();
    }

    private String loadPkg(int which) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("mirror_pkg" + which, "");
    }

    private void savePkg(int which, String pkg) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("mirror_pkg" + which, pkg).apply();
    }

    /* ------------------------- 诊断 ------------------------- */

    private final StringBuilder diagBuf = new StringBuilder();

    private void appendDiag(String s) {
        Log.i(TAG, s);
        ui.post(() -> {
            diagBuf.insert(0, s + "\n");
            if (diagBuf.length() > 3000) diagBuf.setLength(3000);
            if (diag != null) diag.setText(diagBuf.toString());
        });
    }

    private void updateDiag() {
        String s = "uid=" + android.os.Process.myUid()
                + "  root=" + Caps.hasRoot()
                + "  投影服务=" + PipProjectionService.running
                + "  token=" + (projection != null)
                + "  活跃镜像屏=" + MirrorSlot.activeCount()
                + "\n槽A: " + (slotA == null ? "-" : slotA.describe())
                + "\n槽B: " + (slotB == null ? "-" : slotB.describe())
                + "\n系统虚拟屏: " + RootOps.readDisplays();
        if (diag != null) diag.setText(s + "\n──────────\n" + diagBuf);
    }
}
