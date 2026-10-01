package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 菜园（TODO P1-1）：整屏只留大时钟 + 歌词 + 一片叶子。
 *
 * 长按叶子进，再长按一次出（4.1 / 4.2）；点叶子出全部应用（4.9）。
 * 三种摆法（4.13）在 gardenStyle 里；压暗（4.5）与遮罩颜色（4.6）都走设置。
 * 应用继续在后台跑，不做任何保活动作 —— 菜园只是个界面。
 */
public class GardenActivity extends BaseActivity {

    private LinearLayout col;
    private TextView leaf;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        applyWall(root);

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        switch (model.gardenStyle) {
            case CENTER_CLOCK:
                col.setGravity(Gravity.CENTER);
                col.addView(clock(46));
                col.addView(lyricLine(16));
                break;
            case LEFT_CLOCK_BOTTOM_LYRIC:
                col.setGravity(Gravity.TOP | Gravity.START);
                col.addView(spacer(dp(40)));
                col.addView(clock(30));
                col.addView(lyricLine(14));
                break;
            case BIG_CLOCK_DATE:
            default:
                col.setGravity(Gravity.CENTER);
                col.addView(clock(56));
                col.addView(sub(SysOps.dateCn(), 16));
                col.addView(lyricLine(15));
                break;
        }

        leaf = leafView();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(model.ballSize), dp(model.ballSize));
        if (model.ballX < 0) {
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            lp.leftMargin = dp(24);
            lp.bottomMargin = dp(32);
        } else {
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = dp(model.ballX);
            lp.topMargin = dp(model.ballY);
        }
        root.addView(leaf, lp);
        return root;
    }

    /** 菜园遮罩：壁纸在底下先铺一层，再按 gardenDim 压暗；浅色主题压黑。 */
    private void applyWall(FrameLayout root) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        String path = Skin.isLight(model) ? model.wallDay
                : (model.wallNight.isEmpty() ? model.wallDay : model.wallNight);
        android.graphics.Bitmap bmp = Wallpaper.loadForScreen(this, path, w, h);
        if (bmp == null) {
            root.setBackgroundColor(Skin.c(R.color.ground));
        } else {
            android.graphics.drawable.BitmapDrawable bd =
                    new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
            bd.setGravity(Gravity.FILL);
            root.setBackground(bd);
        }
        // 菜园遮罩与外观遮罩分开：这里只按 gardenDim 压，不动 model.wallDim
        int a = model.gardenDim * 255 / 100;
        View dim = new View(this);
        dim.setBackgroundColor(Color.argb(a, 0, 0, 0));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView clock(int sp) {
        TextView t = new TextView(this);
        t.setText(SysOps.clockHHmm());
        t.setTextSize(sp);
        t.setTextColor(Skin.c(R.color.text));
        t.setShadowLayer(dp(6), 0, 0, 0xAA000000);   // 4.11 字底：看不清时压一层影
        return t;
    }

    private TextView sub(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Skin.c(R.color.text_dim));
        return t;
    }

    /** 歌词行：批次 G 之前先占位，接上媒体会话后换真实歌词。 */
    private TextView lyricLine(int sp) {
        TextView t = new TextView(this);
        t.setText("（歌词在歌词模块落地后显示）");
        t.setTextSize(sp);
        t.setTextColor(Skin.c(R.color.text_dim));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView leafView() {
        TextView t = new TextView(this);
        t.setText("叶");
        t.setTextSize(model.ballSize / 3f);
        t.setTextColor(Skin.c(R.color.leaf));
        t.setGravity(Gravity.CENTER);
        t.setBackground(leafBg());
        // 点一下出全部应用，长按一次退出菜园
        t.setOnClickListener(v -> startActivity(new Intent(this, AppListActivity.class)));
        t.setOnLongClickListener(v -> { finish(); return true; });
        return t;
    }

    private android.graphics.drawable.Drawable leafBg() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor((0x66 << 24) | (Skin.c(R.color.leaf) & 0x00FFFFFF));
        g.setStroke(dp(1), Skin.c(R.color.leaf));
        return g;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() {
        finish();   // 4.8 返回键回桌面，不留在菜园
    }
}
