package com.seagull.carlauncher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面本体：组件条 + 应用网格 + Dock，支持长按拖拽压合成文件夹。
 *
 * Dock 完整配置（TODO P0-5）：位置（底部/左/右/不显示）、数量、粗细、图标大小、
 * 间距、透明度、自动收起（带把手）、固定、Dock 时钟、每应用默认开在几号窗口。
 *
 * 全部纯 UI + 本地存储，不需要任何权限 —— 这是公开版的地基。
 */
public class DesktopView extends FrameLayout {

    private static final String TAG = "SeagullDesktop";

    public interface Host {
        LauncherModel model();
        void onOpenFolder(LauncherModel.Folder f);
        void onToast(String msg);
        void onRequestLayoutMode();
    }

    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout mainCol, widgetStrip;
    private GridLayout grid;
    private LinearLayout dockBar;
    private TextView dockHandle;
    private FrameLayout dragLayer;

    /** 编辑模式：开着才能拖拽（避免误触），由外部按钮切换。 */
    public boolean editMode = false;

    /** Dock 收起态（自动收起时点把手展开） */
    private boolean dockCollapsed = false;

    /** 拖拽状态 */
    private View dragView;
    private float dragDx, dragDy;
    private String dragKey;
    private View dropTarget;
    private final Paint hiPaint = new Paint();

    /** 组件条槽位名（下标即 model 里的组件 id）。 */
    private static final String[] WIDGET_NAMES =
            {"时钟", "日期", "电量", "内存", "天气", "歌词", "快捷栏"};

    public DesktopView(Context c, Host host) {
        super(c);
        this.host = host;
        hiPaint.setStyle(Paint.Style.STROKE);
        hiPaint.setStrokeWidth(dp(2));
        hiPaint.setColor(0xFF8CC26A);
        build();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void build() {
        setBackgroundColor(getResources().getColor(R.color.ground, null));

        mainCol = new LinearLayout(getContext());
        mainCol.setOrientation(LinearLayout.VERTICAL);
        mainCol.setPadding(dp(12), dp(10), dp(12), dp(6));
        addView(mainCol, new FrameLayout.LayoutParams(-1, -1));

        widgetStrip = new LinearLayout(getContext());
        widgetStrip.setOrientation(LinearLayout.HORIZONTAL);
        widgetStrip.setVisibility(GONE);
        mainCol.addView(widgetStrip, new LinearLayout.LayoutParams(-1, dp(96)));

        grid = new GridLayout(getContext());
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        mainCol.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        dockBar = new LinearLayout(getContext());
        dockBar.setOrientation(LinearLayout.HORIZONTAL);
        dockBar.setGravity(Gravity.CENTER);
        dockBar.setBackgroundColor(getResources().getColor(R.color.panel, null));
        dockBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        addView(dockBar, new FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM));

        // 自动收起的把手：贴在 Dock 所在的那条边
        dockHandle = new TextView(getContext());
        dockHandle.setTextSize(13);
        dockHandle.setTextColor(getResources().getColor(R.color.text_dim, null));
        dockHandle.setGravity(Gravity.CENTER);
        dockHandle.setBackgroundColor(getResources().getColor(R.color.panel, null));
        dockHandle.setVisibility(GONE);
        dockHandle.setOnClickListener(v -> {
            dockCollapsed = false;
            applyCollapse(model());
        });
        addView(dockHandle, new FrameLayout.LayoutParams(dp(30), dp(76), Gravity.LEFT));

        // 拖拽层：浮在最上面画拖拽态与高亮
        dragLayer = new FrameLayout(getContext()) {
            @Override protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (dropTarget != null && dropTarget != dragView) {
                    Rect r = new Rect();
                    dropTarget.getGlobalVisibleRect(r);
                    int[] me = new int[2]; getLocationOnScreen(me);
                    r.offset(-me[0], -me[1]);
                    r.inset(dp(3), dp(3));
                    canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, dp(10), dp(10), hiPaint);
                }
            }
        };
        dragLayer.setVisibility(GONE);
        addView(dragLayer, new FrameLayout.LayoutParams(-1, -1));

        setClipChildren(false);
    }

    /* ==================== 渲染 ==================== */

    public void refresh() {
        LauncherModel m = model();
        if (m == null) return;

        renderWidgets(m);
        widgetStrip.setAlpha(m.widgetStripAlpha / 100f);

        grid.removeAllViews();
        int cols = m.mode == LauncherModel.Mode.DENSE ? LauncherModel.COLS_DENSE
                : LauncherModel.COLS_DEFAULT;
        grid.setColumnCount(cols);
        boolean showGrid = m.mode != LauncherModel.Mode.DOCK_ONLY;
        grid.setVisibility(showGrid ? VISIBLE : GONE);

        if (showGrid) {
            List<String> keys = m.homeKeys();
            List<String> shown = new ArrayList<>();   // 文件夹占位只出现一次
            for (String k : keys) {
                LauncherModel.Folder f = m.folderOf(k);
                if (f != null) {
                    String fk = "@folder:" + f.name + ":" + f.keys.get(0);
                    if (shown.contains(fk)) continue;
                    shown.add(fk);
                    grid.addView(folderView(f), cellParams(cols));
                } else {
                    LauncherModel.App a = m.find(k);
                    if (a == null) continue;
                    grid.addView(appView(a, dp(44), m.showLabels, false), cellParams(cols));
                }
            }
        }

        applyDockLayout(m);
        renderDock(m);
    }

    /* ==================== Dock（TODO P0-5） ==================== */

    /** 位置 / 粗细 / 透明度 / 自动收起，都在这一处落到布局参数上。 */
    private void applyDockLayout(LauncherModel m) {
        boolean vertical = m.dockPos == LauncherModel.DockPos.LEFT
                || m.dockPos == LauncherModel.DockPos.RIGHT;
        dockBar.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        dockBar.setVisibility(m.dockPos == LauncherModel.DockPos.HIDDEN ? GONE : VISIBLE);
        dockBar.setAlpha(Math.max(0.15f, m.dockAlpha / 100f));

        int thick = dp(m.dockWidth);
        FrameLayout.LayoutParams dock = (FrameLayout.LayoutParams) dockBar.getLayoutParams();
        FrameLayout.LayoutParams main = (FrameLayout.LayoutParams) mainCol.getLayoutParams();
        main.leftMargin = 0; main.rightMargin = 0; main.topMargin = 0; main.bottomMargin = 0;
        dock.leftMargin = 0; dock.rightMargin = 0; dock.topMargin = 0; dock.bottomMargin = 0;
        if (m.dockPos == LauncherModel.DockPos.LEFT) {
            dock.width = thick; dock.height = -1; dock.gravity = Gravity.LEFT;
            main.leftMargin = thick;
        } else if (m.dockPos == LauncherModel.DockPos.RIGHT) {
            dock.width = thick; dock.height = -1; dock.gravity = Gravity.RIGHT;
            main.rightMargin = thick;
        } else {
            dock.width = -1; dock.height = thick; dock.gravity = Gravity.BOTTOM;
            main.bottomMargin = thick;
        }

        FrameLayout.LayoutParams handle = (FrameLayout.LayoutParams) dockHandle.getLayoutParams();
        if (m.dockPos == LauncherModel.DockPos.LEFT) {
            handle.width = dp(30); handle.height = -1; handle.gravity = Gravity.LEFT;
        } else if (m.dockPos == LauncherModel.DockPos.RIGHT) {
            handle.width = dp(30); handle.height = -1; handle.gravity = Gravity.RIGHT;
        } else {
            handle.width = -1; handle.height = dp(24); handle.gravity = Gravity.BOTTOM;
        }
        if (!m.dockAutoHide || m.dockFixed) dockCollapsed = false;
        applyCollapse(m);
    }

    private void applyCollapse(LauncherModel m) {
        if (m == null) return;
        boolean collapsible = m.dockAutoHide && !m.dockFixed
                && m.dockPos != LauncherModel.DockPos.HIDDEN;
        if (!collapsible) {
            dockBar.setTranslationX(0f);
            dockBar.setTranslationY(0f);
            dockHandle.setVisibility(GONE);
            return;
        }
        if (!dockCollapsed) {
            dockBar.setTranslationX(0f);
            dockBar.setTranslationY(0f);
            dockHandle.setVisibility(GONE);
            return;
        }
        int thick = dp(m.dockWidth);
        dockBar.setTranslationY(m.dockPos == LauncherModel.DockPos.BOTTOM ? thick - dp(10) : 0f);
        dockBar.setTranslationX(m.dockPos == LauncherModel.DockPos.LEFT ? -(thick - dp(10))
                : (m.dockPos == LauncherModel.DockPos.RIGHT ? thick - dp(10) : 0f));
        dockHandle.setText(m.dockPos == LauncherModel.DockPos.BOTTOM ? "≡"
                : (m.dockPos == LauncherModel.DockPos.LEFT ? "›" : "‹"));
        dockHandle.setVisibility(VISIBLE);
    }

    private void renderDock(LauncherModel m) {
        if (m.dockPos == LauncherModel.DockPos.HIDDEN) return;
        dockBar.removeAllViews();
        boolean vertical = m.dockPos == LauncherModel.DockPos.LEFT
                || m.dockPos == LauncherModel.DockPos.RIGHT;
        List<String> keys = m.dock.isEmpty() ? defaultDockKeys(m) : m.dock;
        // 数量改小时只显示前 N 个，多出的应用仍留在 model.dock 里
        int n = Math.min(keys.size(), Math.max(1, m.dockCount));
        for (int i = 0; i < n; i++) {
            LauncherModel.App a = m.find(keys.get(i));
            if (a == null) continue;
            dockBar.addView(appView(a, dp(m.dockIconSize), false, true), dockParams(m, vertical));
        }
        if (m.dockShowClock) {
            TextView clk = new TextView(getContext());
            clk.setText(SysOps.clockHHmm());
            clk.setTextColor(getResources().getColor(R.color.text, null));
            clk.setTextSize(12);
            clk.setGravity(Gravity.CENTER);
            clk.setOnClickListener(v -> {
                dockCollapsed = !dockCollapsed;
                applyCollapse(m);
            });
            dockBar.addView(clk, dockParams(m, vertical));
        }
    }

    private List<String> defaultDockKeys(LauncherModel m) {
        // 默认 Dock 放最常见的电话/信息/浏览器/相机，找不到就取前 5 个
        String[] prefer = {"com.android.dialer", "com.android.mms", "com.android.chrome",
                "com.android.camera", "com.android.settings"};
        List<String> out = new ArrayList<>();
        for (String p : prefer) {
            for (LauncherModel.App a : m.allApps) {
                if (a.pkg.equals(p)) { out.add(a.key()); break; }
            }
        }
        if (out.isEmpty()) for (int i = 0; i < Math.min(5, m.allApps.size()); i++) out.add(m.allApps.get(i).key());
        return out;
    }

    private GridLayout.LayoutParams cellParams(int cols) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0; p.height = dp(84);
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        p.setMargins(dp(2), dp(5), dp(2), dp(5));
        return p;
    }

    private LinearLayout.LayoutParams dockParams(LauncherModel m, boolean vertical) {
        int g = dp(m.dockGap);
        LinearLayout.LayoutParams p;
        if (vertical) {
            p = new LinearLayout.LayoutParams(-1, 0, 1f);
        } else {
            p = new LinearLayout.LayoutParams(0, -1, 1f);
        }
        p.setMargins(g, g, g, g);
        return p;
    }

    /* ==================== 组件条 ==================== */

    private void renderWidgets(LauncherModel m) {
        widgetStrip.removeAllViews();
        if (m.widgets.isEmpty() || m.mode == LauncherModel.Mode.DOCK_ONLY) {
            widgetStrip.setVisibility(GONE);
            return;
        }
        widgetStrip.setVisibility(VISIBLE);
        for (int id : m.widgets) {
            widgetStrip.addView(makeWidget(id, m), new LinearLayout.LayoutParams(0, -1, 1f));
        }
    }

    private View makeWidget(int id, LauncherModel m) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(getResources().getColor(R.color.panel, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        box.setLayoutParams(lp);

        TextView big = new TextView(getContext());
        big.setTextColor(getResources().getColor(R.color.text, null));
        big.setTextSize(id == 0 ? 30 : 17);
        big.setGravity(Gravity.CENTER);

        TextView small = new TextView(getContext());
        small.setTextColor(getResources().getColor(R.color.text_dim, null));
        small.setTextSize(10);
        small.setGravity(Gravity.CENTER);

        switch (id) {
            case 0: big.setText(SysOps.clockHHmm()); small.setText("时钟"); break;
            case 1: big.setText(SysOps.dateCn());     small.setText("日期"); break;
            case 2: big.setText(SysOps.batteryPct(getContext())); small.setText("电量"); break;
            case 3: big.setText(SysOps.memFree(getContext()));    small.setText("内存"); break;
            // ponytail: 4/5/6 是天气、歌词、快捷栏，等各自模块落地后接真实取值
            case 4: case 5: case 6: big.setText("待接入"); small.setText(WIDGET_NAMES[id]); break;
            default: big.setText("—"); small.setText("空"); break;
        }
        box.addView(big);
        box.addView(small);
        return box;
    }

    /* ==================== 图标 ==================== */

    private View appView(final LauncherModel.App a, int iconPx, boolean label, final boolean isDock) {
        final LauncherModel m = model();
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setTag(a.key());

        ImageView iv = new ImageView(getContext());
        Drawable d = m == null ? null : m.icon(a);
        if (d != null) iv.setImageDrawable(d);
        box.addView(iv, new LinearLayout.LayoutParams(iconPx, iconPx));

        if (label) {
            TextView tv = new TextView(getContext());
            tv.setText(a.label);
            tv.setTextColor(getResources().getColor(R.color.text_dim, null));
            tv.setTextSize(10);
            tv.setMaxLines(1);
            tv.setGravity(Gravity.CENTER);
            box.addView(tv);
        }

        box.setOnClickListener(v -> onAppTap(a, isDock));
        box.setOnLongClickListener(v -> {
            if (isDock) { dockMenu(a); return true; }
            if (!editMode) { host.onToast("点右上角「编辑」后可拖动整理"); return true; }
            startDrag(box, a.key());
            return true;
        });
        return box;
    }

    /** 点图标：Dock 上按「默认开在几号窗口」路由，桌面图标直接启动。 */
    private void onAppTap(LauncherModel.App a, boolean isDock) {
        LauncherModel m = model();
        if (m == null) return;
        if (editMode && !isDock) { host.onToast("编辑模式：长按拖动，拖到另一个图标上合并成文件夹"); return; }
        if (isDock) {
            int slot = m.windowOf(a.pkg);
            if (slot > 0 && a.pkg != null) {
                try { getContext().startActivity(MirrorActivity.intentFor(getContext(), a.pkg, slot)); }
                catch (Throwable t) { host.onToast("打不开窗口页：" + t); }
                return;
            }
        }
        m.launch(a, false);
    }

    /** 长按 Dock 图标：换默认窗口 / 全屏打开 / 拿下来（TODO 2.10 / 2.11 / 2.13）。 */
    private void dockMenu(final LauncherModel.App a) {
        final LauncherModel m = model();
        if (m == null) return;
        int cur = m.windowOf(a.pkg);
        String[] opts = {
                (cur == 1 ? "● " : "○ ") + "默认开在 1 号窗口",
                (cur == 2 ? "● " : "○ ") + "默认开在 2 号窗口",
                "全屏打开（当前窗口留着）",
                "从 Dock 拿下来"
        };
        new AlertDialog.Builder(getContext())
                .setTitle(a.label)
                .setItems(opts, (d, which) -> {
                    switch (which) {
                        case 0: m.setDockWindow(a.pkg, cur == 1 ? 0 : 1); break;
                        case 1: m.setDockWindow(a.pkg, cur == 2 ? 0 : 2); break;
                        case 2: m.setDockWindow(a.pkg, 0); m.launch(a, false); break;
                        default: m.toggleDock(a.key()); host.onToast("已从 Dock 拿下来"); break;
                    }
                    refresh();
                })
                .show();
    }

    private View folderView(final LauncherModel.Folder f) {
        LauncherModel m = model();
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setTag("@folder:" + f.name + ":" + f.keys.get(0));

        // 2x2 迷你预览
        GridLayout pre = new GridLayout(getContext());
        pre.setColumnCount(2);
        int n = Math.min(4, f.keys.size());
        for (int i = 0; i < n; i++) {
            LauncherModel.App a = m.find(f.keys.get(i));
            ImageView iv = new ImageView(getContext());
            Drawable d = a == null ? null : m.icon(a);
            if (d != null) iv.setImageDrawable(d);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = dp(20); p.height = dp(20);
            p.setMargins(dp(1), dp(1), dp(1), dp(1));
            pre.addView(iv, p);
        }
        box.addView(pre, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView tv = new TextView(getContext());
        tv.setText(f.name + " (" + f.keys.size() + ")");
        tv.setTextColor(getResources().getColor(R.color.text_dim, null));
        tv.setTextSize(10);
        tv.setMaxLines(1);
        box.addView(tv);

        box.setOnClickListener(v -> host.onOpenFolder(f));
        box.setOnLongClickListener(v -> {
            if (!editMode) { host.onToast("点右上角「编辑」后可拖动整理"); return true; }
            startDrag(box, box.getTag().toString());
            return true;
        });
        return box;
    }

    /* ==================== 拖拽压合 ==================== */

    private void startDrag(View v, String key) {
        dragKey = key;
        dragView = v;
        dragLayer.setVisibility(VISIBLE);
        dragView.setAlpha(0.35f);
        host.onToast("拖到目标图标上松手即合并成文件夹");
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (dragView == null) return super.onInterceptTouchEvent(e);
        handleDrag(e);
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (dragView != null) { handleDrag(e); return true; }
        return super.onTouchEvent(e);
    }

    private void handleDrag(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                dropTarget = hitTest(e.getRawX(), e.getRawY());
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                View t = dropTarget;
                String src = dragKey;
                endDrag();
                if (t != null && src != null) {
                    Object tag = t.getTag();
                    if (tag != null && !tag.equals(src)) {
                        String dst = tag.toString();
                        if (dst.startsWith("@folder:")) {
                            // 落到文件夹上 → 并入
                            String[] parts = dst.split(":");
                            LauncherModel m = model();
                            LauncherModel.Folder f = null;
                            for (LauncherModel.Folder x : m.folders) if (x.name.equals(parts[1])) { f = x; break; }
                            if (f != null && !f.keys.contains(src)) {
                                f.keys.add(src);
                                m.pinned.remove(src);
                                m.save();
                                host.onToast("已加入「" + f.name + "」");
                                refresh();
                            }
                        } else {
                            LauncherModel.Folder f = model().mergeInto(dst, src);
                            if (f != null) {
                                host.onToast("已合并为文件夹，可点进去改名");
                                refresh();
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    private void endDrag() {
        if (dragView != null) dragView.setAlpha(1f);
        dragView = null; dragKey = null; dropTarget = null;
        dragLayer.setVisibility(GONE);
        invalidate();
    }

    /** 用全局坐标找落在哪个图标上。 */
    private View hitTest(float rawX, float rawY) {
        View found = null;
        found = scan(grid, rawX, rawY, found);
        found = scan(dockBar, rawX, rawY, found);
        return found;
    }

    private View scan(ViewGroup g, float rawX, float rawY, View cur) {
        if (g == null || g.getVisibility() != VISIBLE) return cur;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == dragView || c.getTag() == null) continue;
            int[] loc = new int[2];
            c.getLocationOnScreen(loc);
            if (rawX >= loc[0] && rawX <= loc[0] + c.getWidth()
                    && rawY >= loc[1] && rawY <= loc[1] + c.getHeight()) {
                return c;
            }
        }
        return cur;
    }

    private LauncherModel model() { return host == null ? null : host.model(); }
}
