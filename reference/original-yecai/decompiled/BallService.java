package cn.wayecai.launcher.system;

import a.AbstractC0010k;
import a.RunnableC0009j;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;
import android.widget.ImageView;
import cn.wayecai.launcher.HomeActivity;
import cn.wayecai.launcher.R;
import cn.wayecai.launcher.YecaiApp;
import d.g;
import i.ViewOnLayoutChangeListenerC0095w;
import j.ViewOnTouchListenerC0106h;
import k.e;
import k.f;
import k.j;

/* loaded from: classes.dex */
public final class BallService extends Service {

    /* renamed from: m, reason: collision with root package name */
    public static int f266m;

    /* renamed from: a, reason: collision with root package name */
    public WindowManager f267a;

    /* renamed from: b, reason: collision with root package name */
    public FrameLayout f268b;

    /* renamed from: c, reason: collision with root package name */
    public ImageView f269c;

    /* renamed from: d, reason: collision with root package name */
    public WindowManager.LayoutParams f270d;

    /* renamed from: f, reason: collision with root package name */
    public float f272f;

    /* renamed from: g, reason: collision with root package name */
    public float f273g;

    /* renamed from: h, reason: collision with root package name */
    public int f274h;

    /* renamed from: i, reason: collision with root package name */
    public int f275i;

    /* renamed from: j, reason: collision with root package name */
    public boolean f276j;

    /* renamed from: k, reason: collision with root package name */
    public int f277k;

    /* renamed from: e, reason: collision with root package name */
    public final Handler f271e = new Handler(Looper.getMainLooper());

    /* renamed from: l, reason: collision with root package name */
    public final RunnableC0009j f278l = new RunnableC0009j(this, 14);

    public static void a(Context context, g gVar) {
        Intent intent = new Intent(context, (Class<?>) BallService.class);
        if (!gVar.j0 || !Settings.canDrawOverlays(context)) {
            context.stopService(intent);
            return;
        }
        try {
            context.startService(intent);
        } catch (Throwable th) {
            AbstractC0010k.b("启动失败: ", th, "YecaiBall");
        }
    }

    public static void h(HomeActivity homeActivity, boolean z) {
        int i2 = f266m;
        boolean z2 = i2 > 0;
        int max = Math.max(0, i2 + (z ? 1 : -1));
        f266m = max;
        if (z2 != (max > 0) && YecaiApp.n.f253b.d().j0 && Settings.canDrawOverlays(homeActivity)) {
            homeActivity.startService(new Intent(homeActivity, (Class<?>) BallService.class));
        }
    }

    public final void b() {
        if (this.f268b == null) {
            return;
        }
        this.f268b.setAlpha(Math.max(0.15f, YecaiApp.n.f253b.d().m0 / 100.0f));
    }

    public final void c(g gVar) {
        if (this.f268b == null) {
            return;
        }
        boolean z = true;
        boolean z2 = f266m > 0;
        boolean z3 = getResources().getConfiguration().orientation == 1;
        if (z2 && (!gVar.k0 || "none".equals(gVar.g(z3)))) {
            z = false;
        }
        this.f268b.setVisibility(z ? 0 : 8);
    }

    public final void d(Point point) {
        WindowManager.LayoutParams layoutParams = this.f270d;
        layoutParams.x = Math.max(0, Math.min(point.x - layoutParams.width, layoutParams.x));
        WindowManager.LayoutParams layoutParams2 = this.f270d;
        layoutParams2.y = Math.max(0, Math.min(point.y - layoutParams2.height, layoutParams2.y));
    }

    public final void e(g gVar) {
        e e2 = e.e(gVar.w, j.n(this, gVar.v));
        this.f268b.setBackground(f.m(this, e2.f1078d, gVar.l0 / 2.0f, e2.f1081g, 1.0f));
        this.f269c.setImageTintList(ColorStateList.valueOf(e2.f1085k));
    }

    public final void f() {
        try {
            this.f267a.updateViewLayout(this.f268b, this.f270d);
        } catch (Throwable unused) {
        }
    }

    public final Point g() {
        WindowMetrics currentWindowMetrics;
        Rect bounds;
        Point point = new Point();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                currentWindowMetrics = this.f267a.getCurrentWindowMetrics();
                bounds = currentWindowMetrics.getBounds();
                point.set(bounds.width(), bounds.height());
                return point;
            } catch (RuntimeException unused) {
            }
        }
        point.set(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        return point;
    }

    @Override // android.app.Service
    public final IBinder onBind(Intent intent) {
        return null;
    }

    @Override // android.app.Service, android.content.ComponentCallbacks
    public final void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (this.f268b == null) {
            return;
        }
        d(g());
        f();
        c(YecaiApp.n.f253b.d());
    }

    @Override // android.app.Service
    public final void onDestroy() {
        this.f271e.removeCallbacks(this.f278l);
        FrameLayout frameLayout = this.f268b;
        if (frameLayout != null) {
            try {
                this.f267a.removeView(frameLayout);
            } catch (Throwable unused) {
            }
            this.f268b = null;
        }
        super.onDestroy();
    }

    @Override // android.app.Service
    public final int onStartCommand(Intent intent, int i2, int i3) {
        g d2 = YecaiApp.n.f253b.d();
        if (!d2.j0 || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return 2;
        }
        int f2 = f.f(this, d2.l0);
        if (this.f268b != null) {
            WindowManager.LayoutParams layoutParams = this.f270d;
            if (layoutParams.width != f2) {
                layoutParams.width = f2;
                layoutParams.height = f2;
                d(g());
                f();
            }
            e(d2);
            b();
        } else {
            this.f267a = (WindowManager) getSystemService(WindowManager.class);
            this.f277k = ViewConfiguration.get(this).getScaledTouchSlop();
            FrameLayout frameLayout = new FrameLayout(this);
            this.f268b = frameLayout;
            frameLayout.setElevation(f.f(this, 6.0f));
            ImageView imageView = new ImageView(this);
            this.f269c = imageView;
            imageView.setImageResource(R.drawable.ic_leaf);
            this.f269c.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int round = Math.round(f2 * 0.55f);
            this.f268b.addView(this.f269c, new FrameLayout.LayoutParams(round, round, 17));
            e(d2);
            this.f268b.addOnLayoutChangeListener(new ViewOnLayoutChangeListenerC0095w(1));
            this.f268b.setOnTouchListener(new ViewOnTouchListenerC0106h(this, 0));
            WindowManager.LayoutParams layoutParams2 = new WindowManager.LayoutParams(f2, f2, 2038, 552, -3);
            this.f270d = layoutParams2;
            layoutParams2.gravity = 8388659;
            SharedPreferences sharedPreferences = getSharedPreferences("ball", 0);
            this.f270d.x = sharedPreferences.getInt("x", 0);
            this.f270d.y = sharedPreferences.getInt("y", g().y / 3);
            d(g());
            try {
                this.f267a.addView(this.f268b, this.f270d);
                b();
            } catch (Throwable th) {
                AbstractC0010k.b("挂不上悬浮窗: ", th, "YecaiBall");
                this.f268b = null;
                stopSelf();
            }
        }
        c(d2);
        return 1;
    }
}
