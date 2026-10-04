package com.seagull.carlauncher;

import android.content.Context;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 常驻媒体条（批次 N）—— 移植 CarPlay 的 MiniPlayer 纪律。
 *
 * 规矩（用户拍板）：
 *   · 36dp 一条，固定在画布与底栏之间；媒体会话不活跃时完全隐藏，不占位；
 *   · 只有上一首 / 播放暂停 / 下一首三个大按钮，没有进度条、没有封面缩略图；
 *   · 按钮热区向外扩 12dp（自绘热区转发，不用 TouchDelegate），盲触不弹跳；
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

        // 三个按钮的热区转发（批次 P 修）：TouchDelegate 一个 View 只能挂一个，
        // 逐个 post 会被最后一个覆盖（旧实现只有「下一首」生效）；而自己继承
        // TouchDelegate 又必须传非 null View，构造期传 null 直接 NPE
        // （`TouchDelegate.<init>` 里 `delegateView.getContext()`，真机已崩）。
        // 改为 OnTouchListener 自己转发：事件落点先落哪个按钮热区（外扩 12dp，
        // 不出行边界）就送给哪个按钮，其余返回 false 让行自身的"导焦"点击继续。
        setOnTouchListener(this::onZoneTouch);
    }

    /** 热区命中判定：按三个按钮各自的 Rect 外扩后求交，空矩形不参与。 */
    private TextView hotButton(float x, float y) {
        if (getWidth() <= 0 || getHeight() <= 0) return null;
        int out = dp(getContext(), 12);
        Rect r = new Rect();
        for (TextView b : new TextView[]{btnPrev, btnPlay, btnNext}) {
            b.getHitRect(r);                 // 坐标空间 = MiniPlayer 自己
            r.inset(-out, -out);
            r.intersect(0, 0, getWidth() - 1, getHeight() - 1);   // 不出行边界
            if (!r.isEmpty() && r.contains((int) x, (int) y)) return b;
        }
        return null;
    }

    private boolean onZoneTouch(View row, MotionEvent e) {
        TextView hit = hotButton(e.getX(), e.getY());
        if (hit == null) return false;       // 空白区 → 行的 click（导焦）照旧
        MotionEvent m = MotionEvent.obtain(e);
        m.setLocation(e.getX() - hit.getX(), e.getY() - hit.getY());   // 换算到按钮坐标系
        boolean consumed = hit.dispatchTouchEvent(m);
        m.recycle();
        return true;
    }

    /** 空白区点击 = 宿主导焦（媒体源在画布里才导，见 HomeActivity 接线）。 */
    public void setOnInfoClick(Runnable r) {
        this.onInfoClick = r;
        setOnClickListener(v -> { if (onInfoClick != null) onInfoClick.run(); });
    }

    private TextView btn(Context ctx, String label, View.OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextColor(Skin.c(R.color.leaf));
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Skin.pill(Skin.c(R.color.card)));
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
