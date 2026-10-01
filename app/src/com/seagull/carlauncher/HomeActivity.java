package com.seagull.carlauncher;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
 * 结构：顶栏（时钟/日期/整理/设置） + DesktopView（组件条 + 应用网格 + Dock） + 底栏（全部应用/布局/镜像/工具）。
 *
 * 全部公开 API：不需要 root、不需要任何签名权限，装到 Android 29+ 设备即可当桌面使用。
 * 静态 API（PREFS / AppEntry / loadApps / pinStatic）被另外 5 个文件依赖，保持兼容不变。
 */
public class HomeActivity extends BaseActivity implements DesktopView.Host {

    public static final String PREFS = "seagull";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private DesktopView desktop;
    private LinearLayout topBar, bottomBar, mainCol;
    private TextView pipBox;
    private IslandView island;
    private String lastSkin = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        lastSkin = skinSignature();
        selfReport();
        setContentView(buildUi());
        buildTopBar();
        buildBottomBar();
        desktop.refresh();
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
            setContentView(buildUi());
            buildTopBar();
            buildBottomBar();
            if (desktop != null) desktop.refresh();
        } else if (desktop != null) {
            desktop.refresh();
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
        Lyrics.addSink(islandSink);
        // 常驻画中画被系统拉回主屏时搬回去（批次 L；节流在 MirrorHost 里）
        MirrorHost.healHome(this);
        TaskEngine.fireDesktop(this);   // 13.2 桌面启动触发（同进程只跑一次）
        TaskEngine.arm(this);           // 13.4 定时任务每分钟看一眼
    }

    /** 岛上的歌词一秒一跳。 */
    private final Lyrics.Sink islandSink = new Lyrics.Sink() {
        @Override public void onLyric(String[] w, String t, String a) {
            if (model != null && model.islandLyric && island != null) island.bind(model);
        }
    };

    /** 主题 / 壁纸 / 字号变了才重画，避免每次回桌面都闪一下。 */
    private String skinSignature() {
        if (model == null) return "";
        return model.themeId + "|" + model.customAccent + "|" + model.dayNight
                + "|" + model.wallDay + "|" + model.wallNight + "|" + model.wallDim
                + "|" + model.fontScale + "|" + SysOps.clockHHmm().substring(0, 2);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (desktop != null) desktop.refresh();
            if (topBar != null) buildTopBar();
            if (island != null && model != null) island.bind(model);
            ui.postDelayed(this, 30_000L);
        }
    };

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
        mainCol.addView(desktop, new LinearLayout.LayoutParams(-1, 0, 1f));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackgroundColor(Skin.bar(R.color.panel));
        bottomBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        mainCol.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(62)));

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

        return root;
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
        // 顶部信息栏关掉时，时间挪到 Dock（TODO P0-8 7.11 / 7.12）
        topBar.setVisibility(model.topInfoBar ? View.VISIBLE : View.GONE);
        if (model.topInfoBar) buildInfoBar();
        else model.dockShowClock = true;
    }

    private void buildInfoBar() {
        topBar.removeAllViews();

        TextView clock = new TextView(this);
        clock.setText(SysOps.clockHHmm());
        clock.setTextColor(Skin.c(R.color.text));
        clock.setTextSize(20);
        topBar.addView(clock);

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
        edit.setBackgroundColor(Skin.c(R.color.card));
        edit.setOnClickListener(v -> {
            desktop.editMode = !desktop.editMode;
            buildTopBar();
            toast(desktop.editMode
                    ? "整理模式：长按一个图标，拖到另一个图标上松手 → 合并成文件夹"
                    : "已退出整理模式");
        });
        topBar.addView(edit);

        TextView search = new TextView(this);
        search.setText("搜索");
        search.setTextColor(Skin.c(R.color.text));
        search.setTextSize(13);
        search.setPadding(dp(12), dp(7), dp(12), dp(7));
        search.setBackgroundColor(Skin.c(R.color.card));
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(-2, -2);
        scp.leftMargin = dp(8);
        search.setLayoutParams(scp);
        search.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        topBar.addView(search);

        TextView set = new TextView(this);
        set.setText("设置");
        set.setTextColor(Skin.c(R.color.text));
        set.setTextSize(13);
        set.setPadding(dp(12), dp(7), dp(12), dp(7));
        set.setBackgroundColor(Skin.c(R.color.card));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = dp(8);
        set.setLayoutParams(sp);
        set.setOnClickListener(v -> startActivity(new Intent(this, SettingsHubActivity.class)));
        topBar.addView(set);
    }

    private void buildBottomBar() {
        if (bottomBar == null) return;
        bottomBar.removeAllViews();
        bottomBar.addView(barBtn("全部应用", R.color.leaf,
                v -> startActivity(new Intent(this, AppListActivity.class))), weight());
        bottomBar.addView(gap(), new LinearLayout.LayoutParams(dp(8), 1));
        bottomBar.addView(barBtn("布局组件", R.color.text,
                v -> startActivity(new Intent(this, LayoutModeActivity.class))), weight());
        bottomBar.addView(gap(), new LinearLayout.LayoutParams(dp(8), 1));
        bottomBar.addView(barBtn("镜像小窗", R.color.text,
                v -> startActivity(new Intent(this, MirrorActivity.class))), weight());
        bottomBar.addView(gap(), new LinearLayout.LayoutParams(dp(8), 1));
        bottomBar.addView(barBtn("工具", R.color.warn,
                v -> startActivity(new Intent(this, RootPanelActivity.class))), weight());
        bottomBar.addView(gap(), new LinearLayout.LayoutParams(dp(8), 1));
        // 野菜键：点=全部应用，长按=进菜园（长按超过 longPressMs 才算）
        View leaf = barBtn("叶", R.color.leaf, v -> startActivity(new Intent(this, AppListActivity.class)));
        leaf.setOnLongClickListener(v -> {
            if (model != null && !model.gardenEnabled) {
                toast("菜园已关：设置 → 桌面 → 菜园");
                return true;
            }
            startActivity(new Intent(this, GardenActivity.class));
            return true;
        });
        bottomBar.addView(leaf, weight());
    }

    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -1, 1f); }

    private View gap() { return new View(this); }

    private View barBtn(String label, int colorRes, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Skin.c(colorRes));
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(Skin.c(R.color.card));
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
        if (prefs != null && prefs.getBoolean("autoPip", false)) enterPip(null);
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
