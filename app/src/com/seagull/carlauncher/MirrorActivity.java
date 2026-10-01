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
        // 回到前台时 surface 可能重建，重新挂载；再过一会跑目标任务自愈
        ui.postDelayed(this::redeployIfPossible, 400);
        ui.postDelayed(this::selfHeal, 1200);
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
        note.setText("机制说明（批次 K）\n"
                + "· 建屏：优先 TRUSTED 直建（root 授 COMPANION_DEVICE_APP_STREAMING 角色\n"
                + "  → DisplayManager.createVirtualDisplay + flag 候选降级 + 受信位校验），\n"
                + "  失败回落 MediaProjection（PUBLIC|OWN_CONTENT_ONLY|PRESENTATION）\n"
                + "· 尺寸：VD 严格按画布像素 1:1 建，画面不拉伸、触摸坐标零换算\n"
                + "· 搬应用：root `am start --user 0 --display <id> -f 0x18800000 -n <组件>`\n"
                + "  （守护进程反射优先，API 次之，命令兜底）\n"
                + "· 触摸：原始事件中继（多指）或 `input -d <id> tap|swipe` 兜底\n"
                + "· 自愈：回前台检测目标任务是否被拉回主屏（singleTask 常见），在则搬回\n"
                + "· 为什么非 TRUSTED 不可：Android 14 只允许受信屏收留 home-affinity 任务");
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
        buildEdgeTab(box, which);
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
            if (s == null || !s.ready()) return false;
            s.onTouch(e);   // 点/滑/长按都在 TouchForward 里按设置分流（12.1~12.5）
            return true;
        });
        view.setFocusable(true);
        view.setClickable(true);
        return box;
    }

    /** 11.14 边缘小标签：贴在槽位右边缘，点一下把窗口收回主屏（需 root）。 */
    private void buildEdgeTab(android.widget.FrameLayout box, int which) {
        if (!TaskMover.hasEdgeTab(this)) return;
        final MirrorSlot slot = which == 1 ? slotA : slotB;
        TextView tab = new TextView(this);
        tab.setText("收回");
        tab.setTextColor(Skin.c(R.color.text));
        tab.setTextSize(11);
        tab.setGravity(Gravity.CENTER);
        tab.setBackgroundColor(0xCC1B1D22);
        tab.setPadding(dp(4), dp(14), dp(4), dp(14));
        tab.setOnClickListener(v -> new Thread(() -> {
            int d = slot != null && slot.displayId() > 0 ? slot.displayId() : TaskMover.edgeDisplay();
            final String out = TaskMover.reclaim(this, d);
            ui.post(() -> appendDiag("边缘标签：" + out.replace('\n', ' ')));
        }).start());
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(dp(38), -2);
        lp.gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
        box.addView(tab, lp);
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
        // 不再强制录屏 token：TRUSTED 屏不需要，兜底路径由 deploy 内部判
        if (surface == null || !surface.isValid()) return;
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
            final boolean needProj = s.needsProjection();
            ui.post(() -> {
                View wait = s == slotA ? waitA : waitB;
                if (wait != null) wait.setVisibility(ok ? View.GONE : View.VISIBLE);
                appendDiag(s.describe());
                if (needProj) appendDiag("TRUSTED 屏不可用 → 先点「申请录屏授权」再选应用");
            });
        }, "deploy-" + pkg).start();
    }

    /**
     * 建屏 + 搬应用。Surface 可能还没就绪，照样先建屏（已验证：建屏不依赖 Surface，
     * root 也能把应用搬上去），Surface 一到再 attachSurface 出画面。
     *
     * 尺寸口径（抱怨修复"画中画要跟随画布大小"）：VD 严格按 SurfaceView 的实际
     * 像素尺寸建（1:1）—— 已量到用实测，没量到用槽位设计尺寸（全宽 - 左右留白
     * × 槽高），绝不用屏幕一半之类的估算值：VD 和画布差一个像素，
     * 画面就被作曲家拉伸、触摸坐标就偏。
     *
     * 授权口径：不强制录屏 token —— TRUSTED 直建（角色授予成功时）不需要它，
     * 只有兜底路径才需要；deploy 内部会判，缺了会提示。
     */
    private void deployNow() {
        // 用槽位设计尺寸建屏（没有 Surface 时以槽的期望尺寸为准）
        int w = canvasW();
        int h = canvasH();
        final int dpi = getResources().getDisplayMetrics().densityDpi;
        deploySlot(slotA, 1, w, h, dpi);
        deploySlot(slotB, 2, w, h, dpi);
    }

    /** 槽位画布设计宽度：全宽减去 rootCol 左右 padding。 */
    private int canvasW() {
        if (svA != null && svA.getWidth() > 0) return svA.getWidth();
        return Math.max(1, getResources().getDisplayMetrics().widthPixels - dp(32));
    }

    /** 槽位画布设计高度：slotBox 里写死的 dp(200)。 */
    private int canvasH() {
        if (svA != null && svA.getHeight() > 0) return svA.getHeight();
        return dp(200);
    }

    private void deploySlot(MirrorSlot slot, int which, int w, int h, int dpi) {
        if (slot == null) return;
        String pkg = loadPkg(which);
        if (pkg == null || pkg.isEmpty()) { appendDiag("槽 " + (which == 1 ? "A" : "B") + " 未选应用，跳过"); return; }
        SurfaceView svView = which == 1 ? svA : svB;
        Surface surf = (svView != null && svView.getHolder().getSurface() != null
                && svView.getHolder().getSurface().isValid()) ? svView.getHolder().getSurface() : null;
        // 已量到画布就用实测，没量到退回传入的设计尺寸 —— 保持 1:1
        final int fw = (svView != null && svView.getWidth() > 0) ? svView.getWidth() : w;
        final int fh = (svView != null && svView.getHeight() > 0) ? svView.getHeight() : h;
        new Thread(() -> {
            // deploy 内部先试 TRUSTED 直建（角色授予/su 都在这个后台线程里跑），
            // 失败且没有录屏 token 时返回 needsProjection —— 两条路都不通才提示授权
            boolean ok = slot.deploy(projection, pkg, surf, fw, fh, dpi);
            final boolean needProj = slot.needsProjection();
            ui.post(() -> {
                appendDiag("槽 " + (which == 1 ? "A" : "B") + " → " + slot.describe()
                        + (ok ? "" : "  [失败]"));
                if (needProj) appendDiag("TRUSTED 屏不可用 → 先点「申请录屏授权」再选应用");
                View wait = which == 1 ? waitA : waitB;
                if (wait != null) wait.setVisibility(ok && slot.ready() ? View.GONE : View.VISIBLE);
            });
        }, "deploy" + which).start();
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
     * 目标任务自愈（批次 K，carlink m10/m12 同款）：singleTask 应用在虚拟屏内部
     * 跳转会落到默认屏、任务被整体拉走（主屏冒出全屏应用、画中画黑屏）。
     * 回前台时检测一次，被拉走的搬回来。保守：解析不出栈结构就跳过，不重拉。
     */
    private void selfHeal() {
        new Thread(() -> {
            for (int i = 1; i <= 2; i++) {
                MirrorSlot s = i == 1 ? slotA : slotB;
                if (s == null || s.displayId() <= 0 || !s.ready()) continue;
                String pkg = loadPkg(i);
                if (pkg == null || pkg.isEmpty()) continue;
                String out = RootOps.ensureOnDisplay(this, pkg, s.displayId());
                if (!out.isEmpty()) {
                    final String line = "自愈 槽" + (i == 1 ? "A" : "B") + ": " + out;
                    ui.post(() -> appendDiag(line));
                }
            }
        }, "self-heal").start();
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
                // 不再强制录屏授权：TRUSTED 屏不需要，兜底路径缺 token 时
                // deploy 内部返回 needsProjection，UI 会提示
                final int slotNo = pickingSlot;
                deploySlot(slotNo == 1 ? slotA : slotB, slotNo, canvasW(), canvasH(),
                        getResources().getDisplayMetrics().densityDpi);
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
                + "  TRUSTED角色=" + RootOps.roleState()
                + "  投影服务=" + PipProjectionService.running
                + "  token=" + (projection != null)
                + "  活跃镜像屏=" + MirrorSlot.activeCount()
                + "\n槽A: " + (slotA == null ? "-" : slotA.describe())
                + "\n槽B: " + (slotB == null ? "-" : slotB.describe())
                + "\n系统虚拟屏: " + RootOps.readDisplays();
        if (diag != null) diag.setText(s + "\n──────────\n" + diagBuf);
    }
}
