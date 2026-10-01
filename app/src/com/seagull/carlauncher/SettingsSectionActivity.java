package com.seagull.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 设置分区页：按 EXTRA_SECTION 渲染 17 个分区之一（文案逐字对齐野菜桌面）。
 *
 * 职责：把 LauncherModel 已有的配置字段接上 UI 与持久化。
 * 「有则增强，无则隐藏」：需要 root / 特殊权限的操作只在能力可用时出现。
 * 各分区的完整功能落地节点：布局(已有) Dock(P0-5) 快捷栏(P0-6) 主题(P0-7) 菜园(P1-1)
 * 小白点(P1-2) 天气(P1-3) 歌词(P1-4) 任务引擎(P1-5) 野菜岛(P1-6) 触摸通道(P2-3)。
 */
public class SettingsSectionActivity extends BaseActivity {

    public static final String EXTRA_SECTION = "section";

    private static final String[] FN_KEYS = QuickBar.FN_KEYS;
    private static final String[] FN_LABELS = QuickBar.FN_LABELS;

    private String section;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null) section = "system";
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        model.loadApps();
        render();
    }

    private void render() {
        setContentView(build());
    }

    /* ==================== 页面骨架 ==================== */

    private LinearLayout col;

    private View build() {
        ScrollView sv = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Skin.c(R.color.ground));
        col.setPadding(dp(16), dp(12), dp(16), dp(24));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("← 返回设置");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(15);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView t = new TextView(this);
        t.setText("  " + titleOf(section));
        t.setTextColor(Skin.c(R.color.text));
        t.setTextSize(17);
        head.addView(t);
        col.addView(head);

        buildBody();

        sv.addView(col);
        return sv;
    }

    private static String titleOf(String id) {
        switch (id) {
            case "dock": return "Dock 栏";
            case "quickbar": return "快捷栏";
            case "garden": return "菜园";
            case "ball": return "小白点";
            case "theme": return "主题与壁纸";
            case "screen": return "屏幕";
            case "island": return "野菜岛";
            case "weather": return "天气";
            case "lyrics": return "歌词";
            case "window": return "窗口";
            case "touch": return "触摸";
            case "tasks": return "自动化任务";
            case "bootanim": return "开机动画";
            case "system": return "系统";
            case "market": return "车机工具";
            case "about": return "关于";
            default: return "布局";
        }
    }

    private void buildBody() {
        switch (section) {
            case "dock": dockBody(); break;
            case "quickbar": quickbarBody(); break;
            case "garden": gardenBody(); break;
            case "ball": ballBody(); break;
            case "theme": themeBody(); break;
            case "screen": screenBody(); break;
            case "island": islandBody(); break;
            case "weather": weatherBody(); break;
            case "lyrics": lyricsBody(); break;
            case "window": windowBody(); break;
            case "touch": touchBody(); break;
            case "tasks": tasksBody(); break;
            case "bootanim": bootanimBody(); break;
            case "system": systemBody(); break;
            case "market": marketBody(); break;
            case "about": aboutBody(); break;
            default: layoutBody(); break;
        }
    }

    /* ==================== 各分区 ==================== */

    private void layoutBody() {
        note("布局在「布局与组件」页配置。");
        actionRow("打开布局与组件", "桌面分区模式 · 组件条 · 显示应用名称",
                v -> startActivity(new Intent(this, LayoutModeActivity.class)));
    }

    private void dockBody() {
        sectionLabel("位置");
        choiceRow("Dock 位置", model.dockPos.label, () -> {
            String[] names = {"底部", "左侧", "右侧", "不显示"};
            choiceDialog("Dock 位置", names, model.dockPos.ordinal(), i -> {
                model.dockPos = LauncherModel.DockPos.values()[i];
                model.save(); render();
            });
        });

        sectionLabel("大小与透明度");
        sliderRow("放几个", 1, LauncherModel.DOCK_MAX, model.dockCount, " 个",
                v -> { model.dockCount = v; });
        sliderRow("宽度（底部时=高度）", 40, 140, model.dockWidth, " dp", v -> { model.dockWidth = v; });
        sliderRow("图标大小", 28, 72, model.dockIconSize, " dp", v -> { model.dockIconSize = v; });
        sliderRow("间距", 0, 24, model.dockGap, " dp", v -> { model.dockGap = v; });
        sliderRow("透明度", 0, 100, model.dockAlpha, " %", v -> { model.dockAlpha = v; });
        note("往右拉，Dock 越透明，背后的壁纸露得越多。");

        sectionLabel("行为");
        toggleRow("自动收起", model.dockAutoHide, () -> { model.dockAutoHide = !model.dockAutoHide; });
        toggleRow("固定（侧栏满了也不收起）", model.dockFixed, () -> { model.dockFixed = !model.dockFixed; });
        toggleRow("Dock 上的时间", model.dockShowClock, () -> { model.dockShowClock = !model.dockShowClock; });
        note("数量改小时，多出的应用会保留，改回来还在。");

        sectionLabel("Dock 应用（点击移除）");
        if (model.dock.isEmpty()) {
            note("Dock 为空 — 长按应用图标可固定");
        } else {
            for (int i = 0; i < model.dock.size(); i++) {
                final String key = model.dock.get(i);
                LauncherModel.App a = model.find(key);
                String label = a != null ? a.label : key;
                actionRow("槽位 " + (i + 1) + "：" + label, "点击移除", v -> {
                    model.toggleDock(key);
                    render();
                });
            }
        }

        sectionLabel("每个应用默认开在哪个窗口");
        note("设成 1 号 / 2 号后，点 Dock 图标直接进对应小窗；没设的应用点图标全屏打开。");
        List<String> dockKeys = new ArrayList<>(model.dock);
        if (dockKeys.isEmpty()) dockKeys.addAll(defaultDockKeys(model));
        for (int i = 0; i < dockKeys.size(); i++) {
            final LauncherModel.App a = model.find(dockKeys.get(i));
            if (a == null) continue;
            int w = model.windowOf(a.pkg);
            choiceRow(a.label, w == 0 ? "全屏" : (w == 1 ? "1 号窗口" : "2 号窗口"), () -> {
                String[] names = {"全屏", "1 号窗口", "2 号窗口"};
                choiceDialog(a.label, names, w, k -> {
                    model.setDockWindow(a.pkg, k);
                    render();
                });
            });
        }
    }

    /** Dock 为空时设置页也要能配窗口：沿用桌面那套默认候选。 */
    private List<String> defaultDockKeys(LauncherModel m) {
        String[] prefer = {"com.android.dialer", "com.android.mms", "com.android.chrome",
                "com.android.camera", "com.android.settings"};
        List<String> out = new ArrayList<>();
        for (String p : prefer) {
            for (LauncherModel.App a : m.allApps) if (a.pkg.equals(p)) { out.add(a.key()); break; }
        }
        if (out.isEmpty()) for (int i = 0; i < Math.min(5, m.allApps.size()); i++) out.add(m.allApps.get(i).key());
        return out;
    }

    private void quickbarBody() {
        note("放进组件条：长按组件条（或桌面空白处）→「+ 快捷栏」。"
                + "每个桌面布局各有一份快捷栏：在「布局与组件」切布局后回来改这里，改的是那一份。");
        actionRow("当前布局", model.mode.label, v ->
                startActivity(new Intent(this, LayoutModeActivity.class)));

        sectionLabel("快捷栏格子");
        for (int i = 0; i < LauncherModel.QUICKBAR_MAX; i++) {
            final int idx = i;
            String cur;
            if (i < model.quickbar.size()) {
                LauncherModel.QuickSlot q = model.quickbar.get(i);
                cur = q.key == null || q.key.isEmpty() ? "空"
                        : (q.isFunc() ? fnLabel(q.key.substring(4)) : q.label);
            } else {
                cur = "空";
            }
            choiceRow("快捷栏第 " + (i + 1) + " 格", cur, () -> slotDialog(idx));
        }

        sectionLabel("快捷开关");
        fnChoiceRow("快捷开关 1", model.quickBtn1, v -> { model.quickBtn1 = v; });
        fnChoiceRow("快捷开关 2", model.quickBtn2, v -> { model.quickBtn2 = v; });
    }

    private void slotDialog(final int idx) {
        String[] opts = {"清空", "选择应用…", "功能按钮…"};
        choiceDialog("快捷栏第 " + (idx + 1) + " 格", opts, -1, i -> {
            if (i == 0) {
                model.clearQuickSlot(idx);
                render();
            } else if (i == 1) {
                appPicker("选应用", a -> {
                    model.setQuickSlot(idx, a.key(), a.label);
                    render();
                });
            } else {
                choiceDialog("功能按钮", FN_LABELS, -1, j -> {
                    model.setQuickSlot(idx, "@fn:" + FN_KEYS[j], FN_LABELS[j]);
                    render();
                });
            }
        });
    }

    private void fnChoiceRow(String label, String cur, Consumer<String> set) {
        String shown = cur == null || cur.isEmpty() ? "无" : fnLabel(cur.startsWith("@fn:") ? cur.substring(4) : cur);
        choiceRow(label, shown, () -> {
            String[] opts = new String[FN_LABELS.length + 1];
            opts[0] = "无";
            System.arraycopy(FN_LABELS, 0, opts, 1, FN_LABELS.length);
            int curIdx = -1;
            if (cur != null && cur.startsWith("@fn:")) {
                for (int i = 0; i < FN_KEYS.length; i++) if (FN_KEYS[i].equals(cur.substring(4))) curIdx = i + 1;
            }
            final int def = curIdx;
            choiceDialog(label, opts, def, i -> {
                set.accept(i == 0 ? "" : "@fn:" + FN_KEYS[i - 1]);
                model.save(); render();
            });
        });
    }

    private static String fnLabel(String key) {
        for (int i = 0; i < FN_KEYS.length; i++) if (FN_KEYS[i].equals(key)) return FN_LABELS[i];
        return key;
    }

    private void gardenBody() {
        toggleRow("长按叶子进菜园", model.gardenEnabled, () -> { model.gardenEnabled = !model.gardenEnabled; });
        sliderRow("壁纸压暗", 0, 100, model.gardenDim, " %", v -> { model.gardenDim = v; });
        choiceRow("摆法", model.gardenStyle.label, () -> {
            LauncherModel.GardenStyle[] vals = LauncherModel.GardenStyle.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("摆法", names, model.gardenStyle.ordinal(), i -> {
                model.gardenStyle = vals[i];
                model.save(); render();
            });
        });
        actionRow("进一次菜园看看", "看看现在的样子", v ->
                startActivity(new Intent(this, GardenActivity.class)));
        note("桌面底栏的「叶」长按进菜园，菜园里再长按一次出来；点叶子出全部应用。"
                + "菜园的压暗跟外观里的背景遮罩是两份，互不影响。");
    }

    private void ballBody() {
        toggleRow("显示小白点", model.ballEnabled, () -> {
            model.ballEnabled = !model.ballEnabled;
            model.save();
            if (model.ballEnabled && !Caps.canOverlay(this)) {
                toast("还没给悬浮窗权限，先点下面那一行");
            } else {
                BallService.setEnabled(this, model.ballEnabled);
            }
            render();
        });
        actionRow("悬浮窗权限", Caps.canOverlay(this) ? "已授权" : "未授权（点这里去开）", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignore) {}
        });
        sliderRow("大小", 32, 96, model.ballSize, " dp", v -> { model.ballSize = v; });
        sliderRow("透明度", 20, 100, model.ballAlpha, " %", v -> { model.ballAlpha = v; });
        actionRow("回到默认位置", model.ballX < 0 ? "已在左下角" : "重新贴边", v -> {
            model.ballX = -1; model.ballY = -1;
            model.save();
            BallService.setEnabled(this, false);
            if (model.ballEnabled) BallService.setEnabled(this, true);
            render();
        });
        note("点一下回桌面，长按进菜园，直接拖能挪位置，松手自动贴边。"
                + "Dock 关着时，点一下改成出全部应用。");
        note("菜园里那片叶子跟悬浮球是同一个野菜键，位置大小共用。");
    }

    private void themeBody() {
        sectionLabel("主题色");
        for (int i = 0; i < Theme.IDS.length; i++) {
            final int idx = i;
            themeRow(Theme.NAMES[i], Theme.ACCENTS[i], Theme.IDS[i].equals(model.themeId), () -> {
                model.themeId = Theme.IDS[idx];
                model.customAccent = "";
                model.save(); render();
            });
        }
        actionRow("自定义主题色", model.customAccent.isEmpty() ? "十六进制，如 #8CC26A" : model.customAccent, v ->
                inputDialog("自定义主题色", model.customAccent.isEmpty() ? "#" : model.customAccent, hex -> {
                    String s = hex.trim().toUpperCase(Locale.ROOT);
                    if (s.length() == 7 && s.startsWith("#")) {
                        try {
                            Long.parseLong(s.substring(1), 16);
                            model.customAccent = s;
                            model.themeId = "custom";
                            model.save(); render();
                            return;
                        } catch (Throwable ignore) {}
                    }
                    toast("格式不对，要 #RRGGBB");
                }));

        sectionLabel("日夜与文字");
        choiceRow("日夜模式", model.dayNight.label, () -> {
            LauncherModel.DayNight[] vals = LauncherModel.DayNight.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("日夜模式", names, model.dayNight.ordinal(), i -> {
                model.dayNight = vals[i];
                model.save(); render();
            });
        });
        note("跟随系统 = 按本机时间切：06:00~19:00 浅色，其余深色。");
        sliderRow("全局字号", 50, 150, model.fontScale, " %", v -> { model.fontScale = v; });
        sliderRow("背景遮罩", 0, 100, model.wallDim, " %", v -> { model.wallDim = v; });
        note("壁纸太亮就拉遮罩，文字颜色会跟着壁纸明暗自动翻色。");

        sectionLabel("壁纸");
        actionRow("白天壁纸", model.wallDay.isEmpty() ? "未设置" : model.wallDay, v -> pickWall(true));
        actionRow("夜间壁纸", model.wallNight.isEmpty() ? "未设置" : model.wallNight, v -> pickWall(false));
        actionRow("清空壁纸", "回到纯色底", v -> {
            model.wallDay = ""; model.wallNight = "";
            for (LauncherModel.Wall w : new ArrayList<>(model.wallLib)) model.removeWall(w);
            model.save(); render();
        });
        sectionLabel("壁纸库（" + model.wallLib.size() + " / " + LauncherModel.WALL_LIB_MAX + "）");
        if (model.wallLib.isEmpty()) {
            note("还没有壁纸。点「白天壁纸」从相册选一张。");
        } else {
            for (LauncherModel.Wall w : new ArrayList<>(model.wallLib)) {
                wallRow(w);
            }
        }
        note("视频壁纸与毛玻璃（RenderEffect）依赖 API 29+ 特性与额外解码开销，"
                + "公开版先只支持静态图；等有实测收益再加。");
    }

    private static final int REQ_WALL = 0x5A11;

    private void pickWall(final boolean day) {
        try {
            startActivityForResult(Wallpaper.chooser(), REQ_WALL);
            wallPickIsDay = day;
        } catch (Throwable t) {
            toast("这台设备没有相册选择器");
        }
    }

    private boolean wallPickIsDay = true;

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_WALL || data == null || data.getData() == null) return;
        LauncherModel.Wall w = Wallpaper.onPicked(model, "", data.getData());
        if (w == null) { render(); return; }
        if (wallPickIsDay) model.useWall(w, false);
        else model.useWall(w, true);
        render();
    }

    private void wallRow(final LauncherModel.Wall w) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackgroundColor(Skin.c(R.color.card));

        TextView tv = new TextView(this);
        tv.setText(w.name + (w.used ? "  · 正在用" : ""));
        tv.setTextColor(Skin.c(R.color.text));
        tv.setTextSize(13);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView day = miniBtn("白天", v -> { model.useWall(w, false); render(); });
        row.addView(day);
        TextView night = miniBtn("夜间", v -> { model.useWall(w, true); render(); });
        row.addView(night);
        TextView del = miniBtn("删除", v -> {
            model.removeWall(w);
            render();
        });
        row.addView(del);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(6);
        row.setLayoutParams(p);
        col.addView(row);
    }

    private TextView miniBtn(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Skin.c(R.color.leaf));
        tv.setTextSize(12);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        tv.setOnClickListener(l);
        return tv;
    }

    private void themeRow(String name, int accent, boolean on, Runnable pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackgroundColor(on ? Skin.c(R.color.card) : Skin.c(R.color.panel));

        View sw = new View(this);
        sw.setBackgroundColor(accent);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(20), dp(20));
        row.addView(sw, sp);

        TextView tv = new TextView(this);
        tv.setText((on ? "  ● " : "  ○ ") + name);
        tv.setTextColor(on ? Skin.c(R.color.leaf) : Skin.c(R.color.text));
        tv.setTextSize(14);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = dp(6);
        row.addView(tv, tp);

        row.setOnClickListener(v -> pick.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(6);
        row.setLayoutParams(p);
        col.addView(row);
    }

    private void screenBody() {
        sectionLabel("横屏");
        sliderRow("屏幕外边距（左右）", 0, 48, model.marginH, " dp", v -> { model.marginH = v; });
        sliderRow("屏幕外边距（上下）", 0, 48, model.marginV, " dp", v -> { model.marginV = v; });
        sliderRow("窗口之间的缝", 0, 24, model.gap, " dp", v -> { model.gap = v; });

        sectionLabel("竖屏（单独一份）");
        sliderRow("屏幕外边距（左右）", 0, 48, model.portMarginH, " dp", v -> { model.portMarginH = v; });
        sliderRow("屏幕外边距（上下）", 0, 48, model.portMarginV, " dp", v -> { model.portMarginV = v; });
        sliderRow("窗口之间的缝", 0, 24, model.portGap, " dp", v -> { model.portGap = v; });
        actionRow("竖屏复制横屏的值", "把上面横屏的三项搬过来", v -> {
            model.portMarginH = model.marginH;
            model.portMarginV = model.marginV;
            model.portGap = model.gap;
            model.save(); render();
        });

        sectionLabel("方向与显示");
        choiceRow("屏幕方向", model.orientation.label, () -> {
            LauncherModel.Orientation[] vals = LauncherModel.Orientation.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("屏幕方向", names, model.orientation.ordinal(), i -> {
                model.orientation = vals[i];
                model.save(); render();
            });
        });
        toggleRow("保持亮屏", model.keepScreenOn, () -> {
            model.keepScreenOn = !model.keepScreenOn;
            applyKeepScreenOn();
        });
        toggleRow("顶部信息栏（日期 / Wi-Fi / 电量）", model.topInfoBar, () -> {
            model.topInfoBar = !model.topInfoBar;
            model.save(); render();
        });
        note("顶部信息栏关掉后，时间自动挪到 Dock 上。");
        toggleRow("自动同步网络时间", model.autoNetworkTime, () -> {
            model.autoNetworkTime = !model.autoNetworkTime;
            model.save();
            SysOps.R r = SysOps.setAutoTime(model.autoNetworkTime);
            if (!r.ok) toast("需要 root 通道：" + r.out);
        });
        note("隐藏系统栏需要平台签名，公开版不可达，对应入口不显示。");
    }

    private void applyKeepScreenOn() {
        model.save();
        if (model.keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        render();
    }

    private void islandBody() {
        toggleRow("显示野菜岛", model.islandEnabled, () -> { model.islandEnabled = !model.islandEnabled; });
        sliderRow("距顶部偏移（挖孔 / 圆角）", 0, 80, model.islandOffsetY, " dp", v -> { model.islandOffsetY = v; });
        sliderRow("宽度", 200, 480, model.islandWidth, " dp", v -> { model.islandWidth = v; });
        toggleRow("野菜岛显示歌词", model.islandLyric, () -> { model.islandLyric = !model.islandLyric; });
        note("歌词长时省略；状态栏隐藏时浮在窗口上。浮层在后续版本接入。");
    }

    private void weatherBody() {
        choiceRow("城市来源", model.citySource.label, () -> {
            LauncherModel.CitySource[] vals = LauncherModel.CitySource.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("城市来源", names, model.citySource.ordinal(), i -> {
                model.citySource = vals[i];
                model.save(); render();
            });
        });
        actionRow("当前城市", model.weatherCity.isEmpty() ? "未设置（例：杭州）" : model.weatherCity, v ->
                inputDialog("城市名", model.weatherCity, city -> {
                    model.weatherCity = city.trim();
                    model.save(); render();
                }));
        actionRow("更新于", model.weatherUpdatedAt == 0 ? "尚未更新" : new java.util.Date(model.weatherUpdatedAt).toString(), null);
        note("当前天气 + 未来 3 天，每 20 分钟更新，数据源和风天气。天气组件在后续版本接入。");
    }

    private void lyricsBody() {
        choiceRow("歌词来源", model.lyricSource.label, () -> {
            LauncherModel.LyricSource[] vals = LauncherModel.LyricSource.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("歌词来源", names, model.lyricSource.ordinal(), i -> {
                model.lyricSource = vals[i];
                model.save(); render();
            });
        });
        choiceRow("歌词行数", model.lyricLines == 0 ? "自动（横屏 5 / 竖屏 3）" : model.lyricLines + " 行", () -> {
            String[] names = {"自动（横屏 5 / 竖屏 3）", "1 行", "2 行", "3 行", "4 行", "5 行"};
            choiceDialog("歌词行数", names, model.lyricLines, i -> {
                model.lyricLines = i;
                model.save(); render();
            });
        });
        sliderRow("歌词字号", 50, 200, model.lyricSize, " %", v -> { model.lyricSize = v; });
        choiceRow("对时偏移", offsetLabel(model.lyricOffsetMs), () -> {
            String[] names = {"-2 秒", "-1.5 秒", "-1 秒", "-0.5 秒", "0", "+0.5 秒", "+1 秒", "+1.5 秒", "+2 秒"};
            int cur = 4;
            for (int i = 0; i < names.length; i++) {
                int v = -2000 + i * 500;
                if (v == model.lyricOffsetMs) { cur = i; break; }
            }
            final int def = cur;
            choiceDialog("对时偏移", names, def, i -> {
                model.lyricOffsetMs = -2000 + i * 500;
                model.save(); render();
            });
        });
        sliderRow("媒体卡片行数", 1, 6, model.mediaCardLines, " 行", v -> { model.mediaCardLines = v; });
        actionRow("通知使用权", notiListenerOn() ? "已授权" : "未授权（点这里去开）", v -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (Throwable ignore) {}
        });
        note("车载蓝牙歌词走媒体信息，不需通知权限。歌词解析与显示在后续版本接入。");
    }

    private static String offsetLabel(int ms) {
        if (ms == 0) return "0";
        return (ms > 0 ? "+" : "") + (ms / 1000f) + " 秒";
    }

    private boolean notiListenerOn() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return s != null && s.contains(getPackageName());
        } catch (Throwable t) { return false; }
    }

    private void windowBody() {
        choiceRow("性能档位", model.perf.label, () -> {
            LauncherModel.Perf[] vals = LauncherModel.Perf.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("性能档位", names, model.perf.ordinal(), i -> {
                model.perf = vals[i];
                model.save(); render();
            });
        });
        toggleRow("兼容模式（强制应用可调整大小）", model.compatMode, () -> { model.compatMode = !model.compatMode; });
        sliderRow("后台保留几个窗口", 0, 3, model.bgKeep, " 个", v -> { model.bgKeep = v; });
        toggleRow("锁屏后释放画中画", model.releaseOnLock, () -> { model.releaseOnLock = !model.releaseOnLock; });
        toggleRow("息屏暂停", model.pauseOnScreenOff, () -> { model.pauseOnScreenOff = !model.pauseOnScreenOff; });
        note("默认显示大小与每应用独立缩放需要虚拟屏（L3），普通应用被系统拒绝，公开版不提供。");

        sectionLabel("入口");
        actionRow("窗口能力自检页", "能力探测与逐项验证",
                v -> startActivity(new Intent(this, WindowTestActivity.class)));
        actionRow(getString(R.string.mirror_title), getString(R.string.mirror_sub),
                v -> startActivity(new Intent(this, MirrorActivity.class)));
    }

    private void touchBody() {
        sliderRow("防误滑阈值", 0, 50, model.touchThreshold, " px", v -> { model.touchThreshold = v; });
        choiceRow("长按判定", (model.longPressMs / 1000f) + " 秒", () -> {
            String[] names = {"0.3 秒", "0.5 秒"};
            int def = model.longPressMs == 300 ? 0 : 1;
            choiceDialog("长按判定", names, def, i -> {
                model.longPressMs = i == 0 ? 300 : 500;
                model.save(); render();
            });
        });
        toggleRow("无障碍通道（公开版主通道）", model.useAccessibility, () -> { model.useAccessibility = !model.useAccessibility; });
        toggleRow("root 注入通道", model.useRootInput, () -> { model.useRootInput = !model.useRootInput; });
        note("触摸方式选择与跟手体验在后续版本接入。");
    }

    private void tasksBody() {
        actionRow("新建任务", model.tasks.size() + " / " + LauncherModel.TASK_MAX + " 条",
                v -> taskCreateFlow());
        sectionLabel("任务列表（点击管理）");
        if (model.tasks.isEmpty()) {
            note("还没有任务。");
        } else {
            for (int i = 0; i < model.tasks.size(); i++) {
                final LauncherModel.Task t = model.tasks.get(i);
                actionRow((t.enabled ? "● " : "○ ") + t.name, t.describe(), v -> taskManage(t));
            }
        }
        note("任务随桌面配置一起导出。任务引擎（自动执行）在后续版本接入，本页先管理配置。");
    }

    private void taskCreateFlow() {
        if (model.tasks.size() >= LauncherModel.TASK_MAX) {
            toast("最多 " + LauncherModel.TASK_MAX + " 条");
            return;
        }
        inputDialog("任务名称", "", name -> {
            if (name.trim().isEmpty()) { toast("名字不能为空"); return; }
            LauncherModel.Task t = new LauncherModel.Task(name.trim());
            LauncherModel.TaskTrigger[] trs = LauncherModel.TaskTrigger.values();
            String[] trNames = new String[trs.length];
            for (int i = 0; i < trs.length; i++) trNames[i] = trs[i].label;
            choiceDialog("触发条件", trNames, t.trigger.ordinal(), ti -> {
                t.trigger = trs[ti];
                LauncherModel.TaskAction[] acts = LauncherModel.TaskAction.values();
                String[] acNames = new String[acts.length];
                for (int i = 0; i < acts.length; i++) acNames[i] = acts[i].label;
                choiceDialog("执行动作", acNames, t.action.ordinal(), ai -> {
                    t.action = acts[ai];
                    if (t.action == LauncherModel.TaskAction.OPEN_PIP
                            || t.action == LauncherModel.TaskAction.OPEN_APP) {
                        appPicker("选目标应用", a -> {
                            t.pkg = a.pkg;
                            askDelayThenAdd(t);
                        });
                    } else {
                        askDelayThenAdd(t);
                    }
                });
            });
        });
    }

    private void askDelayThenAdd(final LauncherModel.Task t) {
        inputDialog("延迟秒数（0 = 不延迟）", "0", s -> {
            try {
                t.delayMs = Math.max(0, Integer.parseInt(s.trim())) * 1000;
            } catch (Throwable e) { t.delayMs = 0; }
            if (model.addTask(t)) render();
        });
    }

    private void taskManage(final LauncherModel.Task t) {
        String[] opts = {t.enabled ? "停用" : "启用", "删除"};
        choiceDialog(t.name, opts, -1, i -> {
            if (i == 0) {
                t.enabled = !t.enabled;
                model.updateTask();
                render();
            } else if (i == 1) {
                model.removeTask(t);
                render();
            }
        });
    }

    private void bootanimBody() {
        note("公开版保留本地素材管理：生成、格式校验、成品文件。安装进系统需要 Magisk（root），没有 root 时对应入口自动隐藏。");
        actionRow("开机动画 · 生成一次", "本地素材合成（待开发）", null);
        if (Caps.hasRoot()) {
            actionRow("开机动画安装器", "把生成的动画装进系统（需 Magisk）", null);
        }
    }

    private void systemBody() {
        toggleRow("开机自启", model.autoHome, () -> { model.autoHome = !model.autoHome; });
        actionRow("设为默认桌面", "把默认桌面改成海鸥桌面", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (Throwable e) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Throwable ignore) {}
            }
        });
        actionRow("重启桌面", "关掉并重新打开海鸥桌面", v ->
                confirmDialog("重启桌面？", "桌面会立刻重启。", () -> {
                    Intent i = new Intent(this, HomeActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finishAffinity();
                }));
        actionRow("恢复出厂配置", "布局、Dock、外观、天气与歌词、自动化任务都会换成默认值。建议先导出备份。", v ->
                confirmDialog("恢复出厂配置？", "全部配置都会换成默认值。", () -> {
                    model.factoryReset();
                    toast("已恢复出厂配置");
                    render();
                }));

        sectionLabel("能力");
        actionRow("root 通道", Caps.rootWho(), v -> {
            new Thread(() -> {
                Caps.hasRoot();
                runOnUiThread(this::render);
            }).start();
        });
        actionRow("当前窗口档位", Caps.levelName(Caps.level(this)), null);
        actionRow("虚拟屏（L3）", Caps.canVirtualDisplay(this) ? "可用"
                : "不可用 —— 需 uid=1000。root 侧方案：把本应用装进 /system/priv-app", null);
        note("立即校时与系统自动时间需要平台签名，公开版不可达，对应入口不显示。");
    }

    private void marketBody() {
        actionRow(getString(R.string.root_panel_title), getString(R.string.root_panel_sub),
                v -> startActivity(new Intent(this, RootPanelActivity.class)));
        actionRow(getString(R.string.window_test_title), "能力探测与逐项验证",
                v -> startActivity(new Intent(this, WindowTestActivity.class)));
        actionRow(getString(R.string.mirror_title), getString(R.string.mirror_sub),
                v -> startActivity(new Intent(this, MirrorActivity.class)));
        actionRow("强制停止应用", "输入包名，root 通道执行", v ->
                inputDialog("包名", "", pkg -> {
                    if (pkg.trim().isEmpty()) return;
                    SysOps.R r = SysOps.forceStop(pkg.trim());
                    toast(r.toString());
                }));
        note("亮度 / 音量 / Wi-Fi / 蓝牙 / 飞行模式开关在 root 面板里。");
    }

    private void aboutBody() {
        String ver = "1.0";
        long code = 0;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
            code = pi.versionCode;
        } catch (Throwable ignore) {}
        actionRow("版本信息", "海鸥桌面 " + ver + " (" + code + ")", null);
        actionRow("作者信息", "公开版多窗口车机桌面 · 无账号 / 无会员 / 纯本地", null);
        actionRow("体检报告", "能力探测汇总", v ->
                msgDialog("体检报告", Caps.report(this)));
        actionRow("安装包指纹", "签名证书 SHA-256", v -> showFingerprint());
        actionRow("检查更新", "当前为最新版本（本地清单待接入）", null);
        note("不做登录、会员、云端存档、分享码。备份/恢复只走本地文件导入导出。");
    }

    private void showFingerprint() {
        new Thread(() -> {
            String fp;
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.GET_SIGNATURES);
                byte[] sig = pi.signatures[0].toByteArray();
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest(sig)) sb.append(String.format("%02X", b)).append(':');
                fp = sb.substring(0, sb.length() - 1);
            } catch (Throwable t) {
                fp = "读取失败：" + t;
            }
            final String out = fp;
            runOnUiThread(() -> msgDialog("安装包指纹", out));
        }).start();
    }

    /* ==================== 通用控件 ==================== */

    private void sectionLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Skin.c(R.color.leaf));
        tv.setTextSize(13);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(20); p.bottomMargin = dp(6);
        tv.setLayoutParams(p);
        col.addView(tv);
    }

    private void note(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Skin.c(R.color.text_dim));
        tv.setTextSize(12);
        tv.setPadding(dp(4), dp(10), dp(4), dp(4));
        col.addView(tv);
    }

    private void toggleRow(String label, boolean on, Runnable flip) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundColor(on ? Skin.c(R.color.card) : Skin.c(R.color.panel));
        TextView tv = new TextView(this);
        tv.setText((on ? "☑ " : "☐ ") + label);
        tv.setTextColor(on ? Skin.c(R.color.leaf) : Skin.c(R.color.text));
        tv.setTextSize(14);
        row.addView(tv);
        row.setOnClickListener(v -> { flip.run(); model.save(); render(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(6);
        row.setLayoutParams(p);
        col.addView(row);
    }

    private void choiceRow(String label, String current, Runnable open) {
        actionRow(label, current, open == null ? null : v -> open.run());
    }

    private void actionRow(String title, String sub, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(11), dp(14), dp(11));
        box.setBackgroundColor(l == null ? Skin.c(R.color.panel) : Skin.c(R.color.card));

        TextView a = new TextView(this);
        a.setText(title);
        a.setTextColor(Skin.c(R.color.text));
        a.setTextSize(14);
        box.addView(a);

        if (sub != null && !sub.isEmpty()) {
            TextView b = new TextView(this);
            b.setText(sub);
            b.setTextColor(Skin.c(R.color.text_dim));
            b.setTextSize(12);
            box.addView(b);
        }

        box.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(6);
        box.setLayoutParams(p);
        col.addView(box);
    }

    private void sliderRow(String label, int min, int max, int val, String unit, IntConsumer apply) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(12));
        box.setBackgroundColor(Skin.c(R.color.card));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Skin.c(R.color.text));
        name.setTextSize(14);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView value = new TextView(this);
        value.setText((val) + unit);
        value.setTextColor(Skin.c(R.color.leaf));
        value.setTextSize(14);
        top.addView(value);
        box.addView(top);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max, val) - min));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    int v = p + min;
                    value.setText(v + unit);
                    apply.accept(v);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { model.save(); }
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(6);
        box.setLayoutParams(p);
        col.addView(box);
    }

    /* ==================== 对话框 ==================== */

    private void choiceDialog(String title, String[] options, int cur, IntConsumer pick) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, cur, (DialogInterface d, int which) -> {
                    d.dismiss();
                    pick.accept(which);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void inputDialog(String title, String cur, Consumer<String> ok) {
        EditText et = new EditText(this);
        et.setText(cur);
        et.setSelection(et.getText().length());
        et.setTextColor(Skin.c(R.color.text));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", (DialogInterface d, int w) -> ok.accept(et.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDialog(String title, String msg, Runnable ok) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("确定", (DialogInterface d, int w) -> ok.run())
                .setNegativeButton("取消", null)
                .show();
    }

    private void msgDialog(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("好", null)
                .show();
    }

    private void appPicker(String title, Consumer<LauncherModel.App> pick) {
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < model.allApps.size(); i++) {
            final LauncherModel.App a = model.allApps.get(i);
            TextView tv = new TextView(this);
            tv.setText(a.label + "\n" + a.pkg);
            tv.setTextColor(Skin.c(R.color.text));
            tv.setTextSize(13);
            tv.setPadding(dp(16), dp(10), dp(16), dp(10));
            tv.setOnClickListener(v -> {
                if (dialog != null) dialog.dismiss();
                pick.accept(a);
            });
            list.addView(tv);
        }
        sv.addView(list);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setNegativeButton("取消", null)
                .show();
        dialog = dlg;
    }

    private AlertDialog dialog;

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
