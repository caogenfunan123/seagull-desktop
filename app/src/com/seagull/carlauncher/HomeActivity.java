package com.seagull.carlauncher;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 桌面主界面 —— 海鸥桌面（公开版 HOME）。
 *
 * 结构（批次 M 起，画中画优先）：顶栏（时钟/整理/搜索/设置）
 *   + 内容层（PipBoard 画中画 ⇄ DesktopView 桌面网格，二选一）
 *   + 底栏（画中画/桌面切换、全部应用、布局组件、工具）。
 *
 * 进应用默认就是画中画界面（用户原话："进入应用应该是画中画界面，
 * 不应该是设置里的子界面、界面太多不合理"）：桌面网格降为可切换的次级层，
 * 底栏第一个按钮切回去。
 *
 * 全部公开 API：不需要 root、不需要任何签名权限，装到 Android 29+ 设备即可当桌面使用。
 * 静态 API（PREFS / AppEntry / loadApps / pinStatic）被另外 5 个文件依赖，保持兼容不变。
 */
public class HomeActivity extends BaseActivity implements DesktopView.Host {

    public static final String PREFS = "seagull";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private DesktopView desktop;
    private PipBoard pip;
    private MiniPlayer mini;
    /** true = 首屏画中画（默认）；false = 桌面网格。底栏按钮切换。 */
    private boolean pipMode = true;
    private LinearLayout topBar, bottomBar, mainCol;
    private FrameLayout content;
    private TextView pipBox;
    private IslandView island;
    private String lastSkin = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lastSkin = skinSignature();
        selfReport();
        setContentView(buildUi());
        buildTopBar();
        buildBottomBar();
        // 首屏是画中画：桌面网格等切过去再刷（toggleMode 里会刷）
        if (!pipMode) desktop.refresh();
        refreshMini();
        ui.postDelayed(tick, 30_000L);
    }

    /**
     * 自检上报：把持久化真实状态打到 logcat。
     * 用途：root shell 看到的是过滤后的挂载命名空间（/data/data 只有 2 项），
     * 无法从外部判断 SharedPreferences 是否落盘，只能让应用自己报。
     */
    private void selfReport() {
        try {
            java.io.File dir = getFilesDir().getParentFile();
            java.io.File xml = new java.io.File(dir, "shared_prefs/" + PREFS + ".xml");
            android.util.Log.i("SeagullDiag",
                    "dataDir=" + dir.getAbsolutePath()
                    + " | xml=" + xml.getAbsolutePath()
                    + " exists=" + xml.exists()
                    + (xml.exists() ? " size=" + xml.length() : "")
                    + " | mode=" + model.mode
                    + " dock=" + model.dock.size()
                    + " pinned=" + model.pinned.size()
                    + " widgets=" + model.widgets.size()
                    + " folders=" + model.folders.size()
                    + " apps=" + model.allApps.size());
        } catch (Throwable t) {
            android.util.Log.w("SeagullDiag", "自检上报失败", t);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // 从设置页 / 壁纸选择回来：配置变了就整屏重画一次
        model.load();
        String sig = skinSignature();
        if (!sig.equals(lastSkin)) {
            lastSkin = sig;
            Skin.apply(model);
            model.loadApps();
            // 旧 pip 的待跑 redeploy/selfHeal 摘掉：setContentView 会造新的
            // PipBoard，旧的没人 onDestroy，回调会对已废弃的树自愈（批次 T 坐实）
            if (pip != null) pip.cancelPending();
            setContentView(buildUi());
            buildTopBar();
            buildBottomBar();
            if (desktop != null) desktop.refresh();
        } else {
            // 画中画是首屏：回来就重新挂 Surface / 自愈；桌面模式才刷网格
            if (pipMode) {
                if (pip != null) pip.onResume();
            } else if (desktop != null) {
                desktop.refresh();
            }
            buildTopBar();
        }
        if (island != null) {
            island.bind(model);
            island.setVisibility(model.islandEnabled ? View.VISIBLE : View.GONE);
        }
        if (model != null && model.keepScreenOn) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        Weather.arm(this);          // 20 分钟一次；数据过期时立刻取
        Lyrics.start(this);         // 一秒一跳，组件条/菜园的歌词都从它出
        Lyrics.removeSink(islandSink);
        Lyrics.addSink(islandSink); // sink 恒为同一实例：CopyOnWrite 集合会永久持死
        // 整个 HomeActivity，复用而非追加（旧实现无 remove，每次 onResume
        // push 一次 = 每回一次桌面多跑一遍 bind + 保活一整个 Activity）
        refreshMini();              // 媒体条跟着会话状态走（没会话整条隐藏）
        // 常驻画中画被系统拉回主屏时搬回去（批次 L；节流在 MirrorHost 里）
        MirrorHost.healHome(this);
        TaskEngine.fireDesktop(this);   // 13.2 桌面启动触发（同进程只跑一次）
        TaskEngine.arm(this);           // 13.4 定时任务每分钟看一眼
    }

    /** 岛上的歌词一秒一跳。 */
    private final Lyrics.Sink islandSink = new Lyrics.Sink() {
        @Override public void onLyric(String[] w, String t, String a) {
            // 展示方（HomeActivity/岛）可能已被销毁：拿不住就别硬塞
            if (isFinishing() || isDestroyed()) return;
            if (model != null && model.islandLyric && island != null) island.bind(model);
        }
    };

    /** 主题 / 壁纸 / 字号 / 画布权重变了才重画，避免每次回桌面都闪一下。 */
    private String skinSignature() {
        if (model == null) return "";
        return model.themeId + "|" + model.customAccent + "|" + model.dayNight
                + "|" + model.wallDay + "|" + model.wallNight + "|" + model.wallDim
                + "|" + model.fontScale + "|" + model.pipWeightA + ":" + model.pipWeightB
                + "|" + SysOps.clockHHmm().substring(0, 2);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (pip != null) pip.onDestroy();   // 只断 Surface，VD 留给 MirrorHost 常驻
        Lyrics.removeSink(islandSink);      // sink 保活纪律：Activity 走了一定摘掉
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (topBar != null) buildTopBar();
            if (island != null && model != null) island.bind(model);
            if (pipMode) {
                if (pip != null) pip.updateHintsPublic();
            } else if (desktop != null) {
                desktop.refresh();
            }
            refreshMini();   // 30s 一次兜底扫描；播放态变化有 MediaController 回调即时刷
            // 周期自愈：应用中途把新 activity 落到默认屏（盖住桌面=返回也没用）时，
            // 30s 内搬回虚拟屏。onResume 也会立即跑一次。
            if (pip != null) pip.selfHealPublic();
            ui.postDelayed(this, 30_000L);
        }
    };

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (pip != null) pip.onActivityResult(req, res, data);   // 录屏授权只在投影兜底路径才弹
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /* ------------------------- UI 构建 ------------------------- */

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        applyWallpaper(root);

        mainCol = new LinearLayout(this);
        mainCol.setOrientation(LinearLayout.VERTICAL);
        root.addView(mainCol, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Skin.bar(R.color.panel));
        topBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        mainCol.addView(topBar, new LinearLayout.LayoutParams(-1, dp(58)));

        desktop = new DesktopView(this, this);
        pip = new PipBoard(this, slot -> PipBoard.showPicker(this, slot, pip), true, model);
        content = new FrameLayout(this);
        content.addView(desktop, new FrameLayout.LayoutParams(-1, -1));
        content.addView(pip, new FrameLayout.LayoutParams(-1, -1));
        mainCol.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackgroundColor(Skin.bar(R.color.panel));
        bottomBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        mainCol.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(62)));

        // 常驻媒体条（批次 N）：层级在画布之上、底栏之下，没媒体会话时整条隐藏
        mini = new MiniPlayer(this);
        mini.setOnInfoClick(this::focusMediaCanvas);
        mainCol.addView(mini, new LinearLayout.LayoutParams(-1, dp(36)));

        // 野菜岛：顶部居中的胶囊，浮在桌面上（不进 mainCol，免得被布局挤动）
        island = new IslandView(this);
        island.setOnClickListener(v -> startActivity(new Intent(this, SettingsSectionActivity.class)
                .putExtra(SettingsSectionActivity.EXTRA_SECTION, "island")));
        island.setVisibility(model != null && model.islandEnabled ? View.VISIBLE : View.GONE);
        root.addView(island, new FrameLayout.LayoutParams(dp(model == null ? 320 : model.islandWidth),
                dp(40), android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL));

        // 画中画形态下的精简视图（系统 PiP 里只显示这一层）
        pipBox = new TextView(this);
        pipBox.setText("海鸥桌面 · 画中画");
        pipBox.setTextColor(Skin.c(R.color.leaf));
        pipBox.setTextSize(14);
        pipBox.setGravity(Gravity.CENTER);
        pipBox.setBackgroundColor(Skin.c(R.color.ground));
        pipBox.setVisibility(View.GONE);
        root.addView(pipBox, new FrameLayout.LayoutParams(-1, -1));

        applyMode();   // 首屏 = 画中画（用户：进应用就是画中画界面）
        return root;
    }

    /**
     * 内容层二选一：画中画（默认首屏）⇄ 桌面网格。
     * 画中画是用户要的主界面，桌面网格是次级层，底栏第一个按钮来回切。
     */
    private void applyMode() {
        if (content == null || desktop == null || pip == null) return;
        desktop.setVisibility(pipMode ? View.GONE : View.VISIBLE);
        pip.setVisibility(pipMode ? View.VISIBLE : View.GONE);
        if (bottomBar != null) buildBottomBar();
        if (topBar != null) buildTopBar();
    }

    private void toggleMode() {
        pipMode = !pipMode;
        applyMode();
        if (!pipMode && desktop != null) desktop.refresh();
    }

    /**
     * MiniPlayer 空白区点击（批次 N 拍板的双场景规则）：
     *   · 场景 A：媒体源正在画布里 → 导焦到那块画布（不新开页面）；
     *   · 场景 B：媒体源在后台（没进画中画）→ 什么都不做，不挤占当前应用。
     * 播放控制键不经过这里（MiniPlayer 直接对媒体会话下发 transport control）。
     */
    private void focusMediaCanvas() {
        MediaListenerService.Snap s = MediaListenerService.snap();
        if (s == null || s.pkg == null || s.pkg.isEmpty()) return;
        if (pip == null) return;
        if (s.pkg.equals(PipBoard.loadPkg(this, 1))) pip.focusSlot(1);
        else if (s.pkg.equals(PipBoard.loadPkg(this, 2))) pip.focusSlot(2);
    }

    /** 刷媒体条：没会话 → 整条隐藏不占位。 */
    private void refreshMini() {
        if (mini == null) return;
        MediaListenerService.refresh(this);
        mini.bind(MediaListenerService.snap());
    }

    /**
     * 壁纸铺底 + 背景遮罩（TODO P0-7）。
     * 遮罩层加在 root 的最底层，mainCol 透明，就能透出壁纸。
     */
    private void applyWallpaper(FrameLayout root) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        String path = currentWallPath();
        android.graphics.Bitmap bmp = Wallpaper.loadForScreen(this, path, w, h);
        if (bmp == null) {
            root.setBackgroundColor(Skin.c(R.color.ground));
            return;
        }
        android.graphics.drawable.BitmapDrawable bd =
                new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
        bd.setGravity(Gravity.FILL);
        root.setBackground(bd);
        if (model.wallDim > 0) {
            View dim = new View(this);
            dim.setBackgroundColor((model.wallDim * 255 / 100) << 24);
            dim.setClickable(true);      // 挡住壁纸，防止穿透点击
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    /** 当前该用哪张：浅色用白天图，深色优先夜间图。 */
    private String currentWallPath() {
        if (model == null) return "";
        if (Skin.isLight(model)) return model.wallDay;
        return model.wallNight.isEmpty() ? model.wallDay : model.wallNight;
    }

    private void buildTopBar() {
        if (topBar == null) return;
        // 批次 N：画中画模式顶栏压到 40dp，垂直空间让给画布（CarPlay 纪律：chrome 不挤内容）
        topBar.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(pipMode ? 40 : 58)));
        int pv = dp(pipMode ? 6 : 10);
        topBar.setPadding(dp(14), pv, dp(14), pv);
        // 顶部信息栏关掉时，时间挪到 Dock（TODO P0-8 7.11 / 7.12）
        topBar.setVisibility(model.topInfoBar ? View.VISIBLE : View.GONE);
        if (model.topInfoBar) buildInfoBar();
        else model.dockShowClock = true;
    }

    /**
     * 信息栏（批次 N）：画中画模式只留 时间 + 设置（用户：主驾场景优先，管理功能下沉）；
     * 桌面模式才给全量（日期/整理/搜索/设置）。
     */
    private void buildInfoBar() {
        topBar.removeAllViews();

        TextView clock = new TextView(this);
        clock.setText(SysOps.clockHHmm());
        clock.setTextColor(Skin.c(R.color.text));
        clock.setTextSize(pipMode ? 17 : 20);
        topBar.addView(clock);

        if (pipMode) {
            View spacer = new View(this);
            topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            topBar.addView(chipBtn("设置", v ->
                    startActivity(new Intent(this, SettingsHubActivity.class))));
            return;
        }

        TextView date = new TextView(this);
        date.setText("   " + SysOps.dateCn());
        date.setTextColor(Skin.c(R.color.text_dim));
        date.setTextSize(12);
        topBar.addView(date);

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        boolean editing = desktop != null && desktop.editMode;
        TextView edit = new TextView(this);
        edit.setText(editing ? "✓ 完成" : "整理");
        edit.setTextColor(editing ? Skin.c(R.color.leaf) : Skin.c(R.color.text));
        edit.setTextSize(13);
        edit.setPadding(dp(12), dp(7), dp(12), dp(7));
        edit.setBackground(Skin.pill(Skin.c(R.color.card)));
        edit.setOnClickListener(v -> {
            desktop.editMode = !desktop.editMode;
            buildTopBar();
            toast(desktop.editMode
                    ? "整理模式：长按一个图标，拖到另一个图标上松手 → 合并成文件夹"
                    : "已退出整理模式");
        });
        topBar.addView(edit);

        topBar.addView(chipBtn("搜索", v -> startActivity(new Intent(this, SearchActivity.class))));
        topBar.addView(chipBtn("设置", v ->
                startActivity(new Intent(this, SettingsHubActivity.class))));
    }

    /** 顶栏胶囊按钮（搜索/设置）。 */
    private View chipBtn(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Skin.c(R.color.text));
        tv.setTextSize(13);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        tv.setBackground(Skin.pill(Skin.c(R.color.card)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(l);
        return tv;
    }

    /**
     * 底栏（批次 M 精简）：画中画 ⇄ 桌面 的切换 + 全部应用 / 布局组件 / 工具。
     * 砍掉「镜像小窗」（画中画已是首屏，那个子页面没意义）和含义不明的「叶」键
     * （菜园改从 设置 → 桌面 → 菜园 进）。
     */
    private void buildBottomBar() {
        if (bottomBar == null) return;
        bottomBar.removeAllViews();
        bottomBar.addView(barBtn(pipMode ? "桌面" : "画中画", R.color.leaf,
                v -> toggleMode()), weight());
        bottomBar.addView(gap(), new LinearLayout.LayoutParams(dp(8), 1));
        bottomBar.addView(barBtn("全部应用", R.color.text,
                v -> startActivity(new Intent(this, AppListActivity.class))), weight());
        bottomBar.addView(gap(), new LinearLayout.LayoutParams(dp(8), 1));
        bottomBar.addView(barBtn("布局组件", R.color.text,
                v -> startActivity(new Intent(this, LayoutModeActivity.class))), weight());
        bottomBar.addView(gap(), new LinearLayout.LayoutParams(dp(8), 1));
        bottomBar.addView(barBtn("工具", R.color.warn,
                v -> startActivity(new Intent(this, RootPanelActivity.class))), weight());
    }

    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -1, 1f); }

    private View gap() { return new View(this); }

    private View barBtn(String label, int colorRes, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Skin.c(colorRes));
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(Skin.round(Skin.c(R.color.card), 12f));
        tv.setOnClickListener(l);
        return tv;
    }

    /* ------------------------- DesktopView.Host ------------------------- */

    @Override public LauncherModel model() { return model; }

    @Override public void onOpenFolder(LauncherModel.Folder f) {
        Intent i = new Intent(this, FolderActivity.class);
        i.putExtra(FolderActivity.EXTRA_NAME, f.name);
        startActivity(i);
    }

    @Override public void onToast(String msg) { toast(msg); }

    @Override public void onRequestLayoutMode() {
        startActivity(new Intent(this, LayoutModeActivity.class));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /* ------------------------- 系统画中画（L2） ------------------------- */

    /** 进入系统原生画中画。全系统同时只允许一个 PiP 窗口。 */
    public void enterPip(View source) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            if (!getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
            if (Build.VERSION.SDK_INT >= 26) {
                b.setAspectRatio(new android.util.Rational(16, 9));
            }
            if (source != null) {
                Rect r = new Rect();
                source.getGlobalVisibleRect(r);
                b.setSourceRectHint(r);
            }
            enterPictureInPictureMode(b.build());
        } catch (Throwable t) {
            android.util.Log.w("SeagullPip", "进画中画失败", t);
            toast("进画中画失败：" + t);
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean inPip, Configuration cfg) {
        super.onPictureInPictureModeChanged(inPip, cfg);
        if (mainCol != null) mainCol.setVisibility(inPip ? View.GONE : View.VISIBLE);
        if (pipBox != null) pipBox.setVisibility(inPip ? View.VISIBLE : View.GONE);
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        // 旧实现读 prefs 里一个从未写入的 "autoPip"（永远 false），死代码。
        // 画中画切换走 pipMode + PipBoard，不借系统画中画。
    }

    /* ------------------------- 兼容静态 API ------------------------- */

    /** 应用条目（被 AppListActivity / MirrorActivity / RootPanelActivity / VirtualDisplayActivity 复用）。 */
    public static class AppEntry {
        public final String label;
        public final String pkg;
        public final Drawable icon;
        AppEntry(String label, String pkg, Drawable icon) {
            this.label = label; this.pkg = pkg; this.icon = icon;
        }
    }

    /** 静态加载器：调用方必须传 Context（未 attach 的 Activity 实例会 NPE 炸进程）。 */
    public static List<AppEntry> loadApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(main, 0);
        } catch (Throwable t) {
            android.util.Log.w("SeagullApps", "queryIntentActivities 失败", t);
            return new ArrayList<>();
        }
        List<AppEntry> list = new ArrayList<>();
        if (ris == null) return list;
        String self = ctx.getPackageName();
        for (android.content.pm.ResolveInfo ri : ris) {
            if (ri == null || ri.activityInfo == null) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (ai == null || self.equals(ai.packageName)) continue;
            try {
                list.add(new AppEntry(String.valueOf(ai.loadLabel(pm)), ai.packageName,
                        ai.loadIcon(pm)));
            } catch (Throwable ignore) {}
        }
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    public void launch(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable ignore) {}
    }

    /** 静态入口：供其他 Activity 把应用固定到 Dock（走新的 LauncherModel，key = pkg/cls）。 */
    public static void pinStatic(Context ctx, String pkg) {
        try {
            LauncherModel m = new LauncherModel(ctx);
            for (LauncherModel.App a : m.allApps) {
                if (a.pkg.equals(pkg)) {
                    if (!m.dock.contains(a.key())) {
                        m.dock.add(a.key());
                        m.save();
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("SeagullHome", "pinStatic 失败", t);
        }
    }
}
