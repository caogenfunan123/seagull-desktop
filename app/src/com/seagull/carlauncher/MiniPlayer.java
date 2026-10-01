package com.seagull.carlauncher;

import android.content.Context;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 常驻媒体条（批次 N）—— 移植 CarPlay 的 MiniPlayer 纪律。
 *
 * 规矩（用户拍板）：
 *   · 36dp 一条，固定在画布与底栏之间；媒体会话不活跃时完全隐藏，不占位；
 *   · 只有上一首 / 播放暂停 / 下一首三个大按钮，没有进度条、没有封面缩略图；
 *   · 按钮热区向外扩 12dp（TouchDelegate 超出 36dp 条本身），盲触不弹跳；
 *   · 空白区点击 = 交给宿主导焦（媒体源在画布里才导，见 HomeActivity 接线）；
 *   · 三个按钮直接作用于媒体会话，不碰画布焦点。
 */
public final class MiniPlayer extends LinearLayout {

    private final TextView title;
    private final TextView btnPrev, btnPlay, btnNext;

    private Runnable onInfoClick;

    public MiniPlayer(Context ctx) {
        super(ctx);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(Skin.bar(R.color.panel));
        int pad = dp(ctx, 10);
        setPadding(pad, dp(ctx, 2), pad, dp(ctx, 2));
        setVisibility(View.GONE);

        title = new TextView(ctx);
        title.setTextColor(Skin.c(R.color.text));
        title.setTextSize(13);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(0, 0, dp(ctx, 10), 0);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -1, 1f);
        addView(title, tp);

        btnPrev = btn(ctx, "上一首", v -> transport(0));
        btnPlay = btn(ctx, "播放", v -> transport(1));
        btnNext = btn(ctx, "下一首", v -> transport(2));
        addView(btnPrev);
        addView(btnPlay);
        addView(btnNext);

        // 三个按钮共用一份热区委托（批次 P 修）：原生 TouchDelegate 一个 View
        // 只挂一个，逐个 post 会被最后一个覆盖——旧实现只有「下一首」生效。
        // 注意：热区外扩只能往行内借（按钮已是 -1 高，行外 1px 都属于画布/底栏，
        // 媒体条抢走画布边缘触摸比盲触难按更糟）。
        post(this::installHotZone);

        setOnClickListener(v -> { if (onInfoClick != null) onInfoClick.run(); });
    }

    private void installHotZone() {
        if (btnPrev == null || btnPlay == null || btnNext == null) return;
        MultiDelegate del = new MultiDelegate();
        int out = dp(getContext(), 12);
        for (TextView b : new TextView[]{btnPrev, btnPlay, btnNext}) {
            Rect r = new Rect();
            b.getHitRect(r);                 // 坐标空间 = MiniPlayer 自己
            r.inset(-out, -out);
            r.intersect(0, 0, getWidth() - 1, getHeight() - 1);   // 不出行边界
            if (!r.isEmpty()) del.add(r, b);
        }
        setTouchDelegate(del);
    }

    /**
     * 多目标 TouchDelegate：按命中矩形转发（语义同系统实现——
     * 事件落点置为目标中心再 dispatch，点按按钮不需要拖拽路径）。
     */
    private static final class MultiDelegate extends TouchDelegate {
        private static final class T { final Rect r; final View v;
            T(Rect r, View v) { this.r = r; this.v = v; } }
        private final java.util.List<T> ts = new java.util.ArrayList<>();
        MultiDelegate() { super(new Rect(), null); }
        void add(Rect r, View v) { ts.add(new T(r, v)); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            int x = (int) e.getX(), y = (int) e.getY();
            for (T t : ts) {
                if (t.r.contains(x, y)) {
                    e.setLocation(t.v.getWidth() / 2f, t.v.getHeight() / 2f);
                    return t.v.dispatchTouchEvent(e);
                }
            }
            return false;
        }
    }

    private TextView btn(Context ctx, String label, View.OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextColor(Skin.c(R.color.leaf));
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(Skin.c(R.color.card));
        int p = dp(ctx, 12);
        b.setPadding(p, p / 2, p, p / 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
        lp.leftMargin = dp(ctx, 6);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    /** 0=上一首 1=播放/暂停 2=下一首。都直接作用于媒体会话。 */
    private void transport(int which) {
        if (which == 0) MediaListenerService.prev();
        else if (which == 1) MediaListenerService.playPause();
        else MediaListenerService.next();
    }

    public void setOnInfoClick(Runnable r) { this.onInfoClick = r; }

    /** 媒体会话有内容才显形；没内容整条隐藏不占位。 */
    public void bind(MediaListenerService.Snap snap) {
        if (snap == null || snap.pkg == null || snap.pkg.isEmpty()) {
            setVisibility(View.GONE);
            return;
        }
        setVisibility(View.VISIBLE);
        String line = snap.title != null && !snap.title.isEmpty()
                ? snap.title + (snap.artist != null && !snap.artist.isEmpty()
                        ? " · " + snap.artist : "")
                : (snap.pkg);
        title.setText(line);
        btnPlay.setText(snap.playing ? "暂停" : "播放");
    }

    private static int dp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }
}
