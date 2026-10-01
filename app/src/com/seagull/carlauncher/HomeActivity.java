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
public class HomeActivity extends Activity implements DesktopView.Host {

    public static final String PREFS = "seagull";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private LauncherModel model;
    private DesktopView desktop;
    private LinearLayout topBar, bottomBar, mainCol;
    private TextView pipBox;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        model = new LauncherModel(this);
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
        if (desktop != null) {
            desktop.refresh();
            buildTopBar();
        }
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (desktop != null) desktop.refresh();
            if (topBar != null) buildTopBar();
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
        root.setBackgroundColor(getColor(R.color.ground));

        mainCol = new LinearLayout(this);
        mainCol.setOrientation(LinearLayout.VERTICAL);
        root.addView(mainCol, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(getColor(R.color.panel));
        topBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        mainCol.addView(topBar, new LinearLayout.LayoutParams(-1, dp(58)));

        desktop = new DesktopView(this, this);
        mainCol.addView(desktop, new LinearLayout.LayoutParams(-1, 0, 1f));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackgroundColor(getColor(R.color.panel));
        bottomBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        mainCol.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(62)));

        // 画中画形态下的精简视图（系统 PiP 里只显示这一层）
        pipBox = new TextView(this);
        pipBox.setText("海鸥桌面 · 画中画");
        pipBox.setTextColor(getColor(R.color.leaf));
        pipBox.setTextSize(14);
        pipBox.setGravity(Gravity.CENTER);
        pipBox.setBackgroundColor(getColor(R.color.ground));
        pipBox.setVisibility(View.GONE);
        root.addView(pipBox, new FrameLayout.LayoutParams(-1, -1));

        return root;
    }

    private void buildTopBar() {
        if (topBar == null) return;
        topBar.removeAllViews();

        TextView clock = new TextView(this);
        clock.setText(SysOps.clockHHmm());
        clock.setTextColor(getColor(R.color.text));
        clock.setTextSize(20);
        topBar.addView(clock);

        TextView date = new TextView(this);
        date.setText("   " + SysOps.dateCn());
        date.setTextColor(getColor(R.color.text_dim));
        date.setTextSize(12);
        topBar.addView(date);

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        boolean editing = desktop != null && desktop.editMode;
        TextView edit = new TextView(this);
        edit.setText(editing ? "✓ 完成" : "整理");
        edit.setTextColor(editing ? getColor(R.color.leaf) : getColor(R.color.text));
        edit.setTextSize(13);
        edit.setPadding(dp(12), dp(7), dp(12), dp(7));
        edit.setBackgroundColor(getColor(R.color.card));
        edit.setOnClickListener(v -> {
            desktop.editMode = !desktop.editMode;
            buildTopBar();
            toast(desktop.editMode
                    ? "整理模式：长按一个图标，拖到另一个图标上松手 → 合并成文件夹"
                    : "已退出整理模式");
        });
        topBar.addView(edit);

        TextView set = new TextView(this);
        set.setText("设置");
        set.setTextColor(getColor(R.color.text));
        set.setTextSize(13);
        set.setPadding(dp(12), dp(7), dp(12), dp(7));
        set.setBackgroundColor(getColor(R.color.card));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = dp(8);
        set.setLayoutParams(sp);
        set.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
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
    }

    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -1, 1f); }

    private View gap() { return new View(this); }

    private View barBtn(String label, int colorRes, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(getColor(colorRes));
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(getColor(R.color.card));
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
