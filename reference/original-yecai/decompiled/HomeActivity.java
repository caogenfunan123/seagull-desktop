package cn.wayecai.launcher;

import a.AbstractC0010k;
import a.C0008i;
import a.C0012m;
import a.C0013n;
import a.I;
import a.RunnableC0004e;
import a.RunnableC0005f;
import a.RunnableC0009j;
import a.RunnableC0011l;
import a.S;
import a.ViewOnClickListenerC0006g;
import a.ViewOnLongClickListenerC0007h;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import c.f;
import c.g;
import c.m;
import cn.wayecai.launcher.HomeActivity;
import cn.wayecai.launcher.system.BallService;
import d.a;
import d.b;
import f.j;
import h.C0065u0;
import i.C;
import i.C0087n;
import i.C0094v;
import i.K;
import i.N;
import i.O;
import i.P;
import i.T;
import i.ViewOnTouchListenerC0097y;
import i.Z;
import i.b0;
import i.e0;
import j.AbstractC0100b;
import j.AbstractC0108j;
import j.B;
import j.C0105g;
import j.G;
import j.H;
import j.InterfaceC0104f;
import j.J;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import k.e;
import l.r;
import l.s;
import l.v;
import l.w;
import m.i;
import m.k;

/* loaded from: classes.dex */
public class HomeActivity extends Activity implements a, InterfaceC0104f, f {
    public static boolean k0;
    public static boolean l0;
    public static boolean m0;
    public int A;
    public ViewOnTouchListenerC0097y B;
    public LinearLayout C;
    public boolean D;
    public int E;
    public ValueAnimator F;
    public boolean G;
    public boolean H;
    public boolean K;
    public int L;
    public boolean M;
    public String N;
    public int O;
    public int P;
    public boolean Q;
    public boolean R;
    public String S;
    public final RunnableC0011l Y;
    public final C0012m Z;
    public boolean a0;

    /* renamed from: b, reason: collision with root package name */
    public b f227b;

    /* renamed from: c, reason: collision with root package name */
    public i f228c;
    public int c0;

    /* renamed from: d, reason: collision with root package name */
    public C0105g f229d;
    public int d0;

    /* renamed from: e, reason: collision with root package name */
    public long f230e;

    /* renamed from: f, reason: collision with root package name */
    public G f231f;

    /* renamed from: g, reason: collision with root package name */
    public B f232g;

    /* renamed from: h, reason: collision with root package name */
    public g f233h;

    /* renamed from: i, reason: collision with root package name */
    public FrameLayout f234i;

    /* renamed from: j, reason: collision with root package name */
    public ImageView f235j;

    /* renamed from: k, reason: collision with root package name */
    public k.g f236k;

    /* renamed from: l, reason: collision with root package name */
    public boolean f237l;

    /* renamed from: m, reason: collision with root package name */
    public View f238m;
    public C0094v n;
    public O o;
    public j p;
    public C0087n r;
    public C s;
    public T t;
    public N u;
    public LinearLayout v;
    public FrameLayout w;
    public K x;
    public boolean y;
    public P z;

    /* renamed from: a, reason: collision with root package name */
    public long f226a = 20000;
    public final b0[] q = new b0[6];
    public int I = -1;
    public final long[] J = new long[6];
    public final Handler T = new Handler(Looper.getMainLooper());
    public final I U = new I(this);
    public final C0013n V = new C0013n(this);
    public final C0013n W = new C0013n(this);
    public final C0013n X = new C0013n(this);
    public final RunnableC0004e b0 = new RunnableC0004e(this, 2);
    public int[] e0 = new int[0];
    public final C0012m f0 = new C0012m(this, 1);
    public final C0013n g0 = new C0013n(this);
    public final C0013n h0 = new C0013n(this);
    public final C0013n i0 = new C0013n(this);
    public final C0013n j0 = new C0013n(this);

    public HomeActivity() {
        int i2 = 0;
        this.Y = new RunnableC0011l(this, i2);
        this.Z = new C0012m(this, i2);
    }

    public static boolean h(d.g gVar, int i2) {
        return (gVar.f310f[i2].h() || gVar.f310f[i2].f()) ? false : true;
    }

    public static void j(d.g gVar, String str, int i2) {
        int i3 = 0;
        while (true) {
            b[] bVarArr = gVar.f310f;
            if (i3 >= bVarArr.length) {
                return;
            }
            if (i3 != i2 && str.equals(z(bVarArr[i3]))) {
                b[] bVarArr2 = gVar.f310f;
                if (bVarArr2[i3].e()) {
                    bVarArr2[i3] = new b();
                } else {
                    ArrayList arrayList = new ArrayList((ArrayList) bVarArr2[i3].f285e);
                    arrayList.remove(w.d(str));
                    bVarArr2[i3] = arrayList.isEmpty() ? new b() : b.b(arrayList, null);
                }
            }
            i3++;
        }
    }

    public static int[] t(f.a aVar) {
        ArrayList j2 = aVar.j();
        int size = j2.size();
        int[] iArr = new int[size];
        for (int i2 = 0; i2 < size; i2++) {
            iArr[i2] = ((Integer) j2.get(i2)).intValue();
        }
        return iArr;
    }

    public static String z(b bVar) {
        return bVar.e() ? (String) bVar.f283c : bVar.c();
    }

    @Override // c.f
    public final void a() {
        f(this.f227b.d());
    }

    @Override // d.a
    public final void b(d.g gVar) {
        f(gVar);
        C0087n c0087n = this.r;
        if (c0087n.k() && c0087n.B == null) {
            c0087n.h(c0087n.f833d.getText().toString());
            if (c0087n.A == null || c0087n.f840k.getVisibility() != 0) {
                return;
            }
            c0087n.m(c0087n.A);
        }
    }

    @Override // j.InterfaceC0104f
    public final void c() {
        q();
        C0087n c0087n = this.r;
        if (c0087n.k() && c0087n.B == null) {
            c0087n.h(c0087n.f833d.getText().toString());
            if (c0087n.A == null || c0087n.f840k.getVisibility() != 0) {
                return;
            }
            c0087n.m(c0087n.A);
        }
    }

    public final int d(String str) {
        if (str == null) {
            return -1;
        }
        d.g d2 = this.f227b.d();
        int i2 = 0;
        while (true) {
            b[] bVarArr = d2.f310f;
            if (i2 >= bVarArr.length) {
                return -1;
            }
            if (bVarArr[i2].e() && str.equals((String) d2.f310f[i2].f283c)) {
                return i2;
            }
            i2++;
        }
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public final boolean dispatchTouchEvent(MotionEvent motionEvent) {
        View peekDecorView;
        int ime;
        boolean isVisible;
        if (this.a0) {
            return super.dispatchTouchEvent(motionEvent);
        }
        boolean z = motionEvent.getActionMasked() == 0;
        long j2 = this.f230e;
        boolean dispatchTouchEvent = super.dispatchTouchEvent(motionEvent);
        if (z && this.f230e == j2 && Build.VERSION.SDK_INT >= 30 && (peekDecorView = getWindow().peekDecorView()) != null) {
            try {
                WindowInsets rootWindowInsets = peekDecorView.getRootWindowInsets();
                if (rootWindowInsets != null) {
                    ime = WindowInsets.Type.ime();
                    isVisible = rootWindowInsets.isVisible(ime);
                    if (isVisible) {
                        long uptimeMillis = SystemClock.uptimeMillis();
                        KeyEvent keyEvent = new KeyEvent(uptimeMillis, uptimeMillis, 0, 4, 0, 0, -1, 0, 72, 257);
                        KeyEvent changeAction = KeyEvent.changeAction(keyEvent, 1);
                        e.a.e(this, keyEvent, 0);
                        e.a.e(this, changeAction, 0);
                    }
                }
            } catch (Throwable unused) {
            }
        }
        return dispatchTouchEvent;
    }

    public final void e(d.g gVar) {
        int i2 = "landscape".equals(gVar.z) ? 11 : "portrait".equals(gVar.z) ? 12 : 13;
        if (getRequestedOrientation() != i2) {
            setRequestedOrientation(i2);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:102:0x02ce  */
    /* JADX WARN: Removed duplicated region for block: B:117:0x06cd  */
    /* JADX WARN: Removed duplicated region for block: B:121:0x06cf  */
    /* JADX WARN: Removed duplicated region for block: B:124:0x032a  */
    /* JADX WARN: Removed duplicated region for block: B:127:0x0362  */
    /* JADX WARN: Removed duplicated region for block: B:141:0x069f  */
    /* JADX WARN: Removed duplicated region for block: B:144:0x06aa  */
    /* JADX WARN: Removed duplicated region for block: B:153:0x06ac  */
    /* JADX WARN: Removed duplicated region for block: B:154:0x06a1  */
    /* JADX WARN: Removed duplicated region for block: B:202:0x057b  */
    /* JADX WARN: Removed duplicated region for block: B:214:0x02db  */
    /* JADX WARN: Removed duplicated region for block: B:242:0x075e  */
    /* JADX WARN: Removed duplicated region for block: B:267:0x07ac  */
    /* JADX WARN: Removed duplicated region for block: B:277:0x07c6  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void f(d.g r38) {
        /*
            Method dump skipped, instructions count: 2013
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: cn.wayecai.launcher.HomeActivity.f(d.g):void");
    }

    public final HashSet g() {
        d.g d2 = this.f227b.d();
        HashSet hashSet = new HashSet();
        Iterator it = d2.q(this.M).j().iterator();
        while (it.hasNext()) {
            String z = z(d2.f310f[((Integer) it.next()).intValue()]);
            if (z != null) {
                hashSet.add(z);
            }
        }
        return hashSet;
    }

    public final boolean i() {
        d.g d2 = this.f227b.d();
        if (k.j.n(this, d2.v) == this.K && d2.w == this.L) {
            return false;
        }
        recreate();
        return true;
    }

    public final void k(boolean z) {
        for (b0 b0Var : this.q) {
            if (z) {
                if (!b0Var.E && b0Var.x.e() && b0Var.C) {
                    k b2 = b0Var.f779d.b((String) b0Var.x.f283c);
                    if (b2 != null && b2.p() == 2) {
                        b0Var.E = true;
                        b0Var.removeCallbacks(b0Var.H);
                        b2.a(new Z(b0Var, 1));
                    }
                }
            } else if (b0Var.E) {
                b0Var.E = false;
                if (b0Var.f787l.getVisibility() != 0) {
                    b0Var.f783h.setVisibility(8);
                }
            }
        }
    }

    public final void l() {
        ResolveInfo resolveInfo;
        Intent addCategory = new Intent("android.intent.action.MAIN").addCategory("android.intent.category.HOME");
        ArrayList arrayList = new ArrayList();
        for (ResolveInfo resolveInfo2 : getPackageManager().queryIntentActivities(addCategory, 0)) {
            ActivityInfo activityInfo = resolveInfo2.activityInfo;
            if (activityInfo != null) {
                if (!getPackageName().equals(activityInfo.packageName) && !resolveInfo2.activityInfo.name.endsWith("FallbackHome")) {
                    arrayList.add(resolveInfo2);
                }
            }
        }
        Iterator it = arrayList.iterator();
        while (true) {
            if (it.hasNext()) {
                resolveInfo = (ResolveInfo) it.next();
                ApplicationInfo applicationInfo = resolveInfo.activityInfo.applicationInfo;
                if (applicationInfo != null && (applicationInfo.flags & 129) != 0) {
                    break;
                }
            } else {
                resolveInfo = arrayList.isEmpty() ? null : (ResolveInfo) arrayList.get(0);
            }
        }
        if (resolveInfo == null) {
            u("这台机器上只有野菜桌面一个桌面");
            AbstractC0108j.q(this);
            return;
        }
        if (resolveInfo.activityInfo != null) {
            Intent addCategory2 = new Intent("android.intent.action.MAIN").addCategory("android.intent.category.HOME");
            ActivityInfo activityInfo2 = resolveInfo.activityInfo;
            try {
                startActivity(addCategory2.setClassName(activityInfo2.packageName, activityInfo2.name).addFlags(270532608));
                return;
            } catch (Throwable th) {
                AbstractC0010k.b("openHome: ", th, "YecaiLaunch");
            }
        }
        u("打不开系统桌面");
    }

    public final void m(String str) {
        k kVar;
        boolean z = this.f228c.f1265b.b() > 0 && (kVar = (k) this.f228c.f1266c.get(str)) != null && kVar.k();
        if (AbstractC0108j.r(this, str)) {
            return;
        }
        u("打不开 " + ((Object) this.f229d.e(str)));
        if (z) {
            this.f228c.e();
        }
    }

    public final void n(String str) {
        Intent intent = new Intent(this, (Class<?>) SettingsActivity.class);
        if (str != null) {
            intent.putExtra("category", str);
        }
        startActivity(intent, ActivityOptions.makeCustomAnimation(this, R.anim.yecai_fade_in, R.anim.yecai_hold).toBundle());
    }

    public final int o(d.g gVar) {
        Iterator it = gVar.q(this.M).j().iterator();
        int i2 = -1;
        long j2 = Long.MAX_VALUE;
        while (it.hasNext()) {
            int intValue = ((Integer) it.next()).intValue();
            if (h(gVar, intValue) && !gVar.f311g[intValue]) {
                if (gVar.f310f[intValue].g()) {
                    return intValue;
                }
                long j3 = this.J[intValue];
                if (j3 < j2) {
                    i2 = intValue;
                    j2 = j3;
                }
            }
        }
        return i2;
    }

    @Override // android.app.Activity
    public final void onBackPressed() {
        k b2;
        if (this.a0) {
            return;
        }
        if (this.t.getVisibility() == 0) {
            this.t.b();
            return;
        }
        if (this.r.k()) {
            this.r.c();
            return;
        }
        if (this.s.getVisibility() == 0) {
            this.s.a();
            return;
        }
        for (b0 b0Var : this.q) {
            if (b0Var != null && b0Var.D) {
                View view = b0Var.A;
                if ((view instanceof v) && ((v) view).f1235m) {
                    ((v) view).setEditing(false);
                    b0Var.D = false;
                    return;
                }
            }
        }
        if (this.y) {
            K k2 = this.x;
            if (k2.x) {
                k2.o();
                return;
            } else {
                this.W.g();
                return;
            }
        }
        if (this.p.o >= 0) {
            r(-1);
        } else {
            if (this.I < 0 || !this.f227b.d().f310f[this.I].e() || (b2 = this.f228c.b((String) this.f227b.d().f310f[this.I].f283c)) == null) {
                return;
            }
            b2.r();
        }
    }

    @Override // android.app.Activity, android.content.ComponentCallbacks
    public final void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (this.a0) {
            return;
        }
        boolean z = configuration.orientation == 1;
        boolean z2 = (configuration.smallestScreenWidthDp == this.c0 && configuration.densityDpi == this.d0) ? false : true;
        if (z != this.M || z2) {
            ArrayList arrayList = new ArrayList();
            if (!z2) {
                d.g d2 = this.f227b.d();
                Iterator it = d2.q(z).j().iterator();
                while (it.hasNext()) {
                    String z3 = z(d2.f310f[((Integer) it.next()).intValue()]);
                    if (z3 != null) {
                        arrayList.add(z3);
                    }
                }
            }
            i iVar = this.f228c;
            iVar.getClass();
            Iterator it2 = new ArrayList(iVar.f1266c.keySet()).iterator();
            while (it2.hasNext()) {
                String str = (String) it2.next();
                if (!arrayList.contains(str)) {
                    iVar.f(str);
                }
            }
            recreate();
        }
    }

    @Override // android.app.Activity
    public final void onCreate(Bundle bundle) {
        b0[] b0VarArr;
        boolean z;
        boolean z2;
        super.onCreate(bundle);
        m0 = true;
        k.f.i(this);
        this.c0 = getResources().getConfiguration().smallestScreenWidthDp;
        this.d0 = getResources().getConfiguration().densityDpi;
        YecaiApp yecaiApp = YecaiApp.n;
        if (!yecaiApp.f260i) {
            this.a0 = true;
            int i2 = createDeviceProtectedStorageContext().getSharedPreferences("boot", 0).getInt("ground", -15657712);
            boolean z3 = (((double) (i2 & 255)) * 0.114d) + ((((double) ((i2 >> 8) & 255)) * 0.587d) + (((double) ((i2 >> 16) & 255)) * 0.299d)) > 150.0d;
            getWindow().setBackgroundDrawable(new ColorDrawable(i2));
            getWindow().setNavigationBarColor(i2);
            FrameLayout frameLayout = new FrameLayout(this);
            frameLayout.setBackgroundColor(i2);
            ImageView imageView = new ImageView(this);
            imageView.setImageResource(R.drawable.ic_leaf);
            imageView.setImageTintList(ColorStateList.valueOf(z3 ? 771751936 : 956301311));
            int round = Math.round(getResources().getDisplayMetrics().density * 56.0f);
            frameLayout.addView(imageView, new FrameLayout.LayoutParams(round, round, 17));
            setContentView(frameLayout);
            k.j.b(this, createDeviceProtectedStorageContext().getSharedPreferences("boot", 0).getBoolean("fullscreen", true));
            RunnableC0004e runnableC0004e = new RunnableC0004e(this, 1);
            if (yecaiApp.f260i) {
                runnableC0004e.run();
                return;
            } else {
                yecaiApp.f261j.add(runnableC0004e);
                return;
            }
        }
        this.f227b = yecaiApp.f253b;
        this.f228c = yecaiApp.f254c;
        this.f229d = yecaiApp.f252a;
        G g2 = yecaiApp.f255d;
        this.f231f = g2;
        this.f232g = yecaiApp.f256e;
        this.f233h = yecaiApp.f257f;
        g2.o();
        d.g d2 = this.f227b.d();
        this.K = k.j.n(this, d2.v);
        k.f.a(e.e(this.f227b.d().w, this.K));
        this.L = this.f227b.d().w;
        int i3 = k.f.f1088a;
        boolean z4 = d2.F;
        SharedPreferences sharedPreferences = createDeviceProtectedStorageContext().getSharedPreferences("boot", 0);
        if (sharedPreferences.getInt("ground", 0) != i3 || !sharedPreferences.contains("fullscreen") || sharedPreferences.getBoolean("fullscreen", true) != z4) {
            sharedPreferences.edit().putInt("ground", i3).putBoolean("fullscreen", z4).apply();
        }
        boolean z5 = getResources().getConfiguration().orientation == 1;
        this.M = z5;
        if (z5) {
            getSharedPreferences("shell", 0).edit().putBoolean("seenPortrait", true).apply();
        }
        e(d2);
        this.N = d2.g(this.M);
        this.O = d2.C;
        this.P = d2.D;
        this.Q = d2.E;
        this.R = d2.F;
        getWindow().setNavigationBarColor(k.f.f1088a);
        FrameLayout frameLayout2 = new FrameLayout(this);
        this.f234i = frameLayout2;
        frameLayout2.setBackgroundColor(k.f.f1088a);
        ImageView imageView2 = new ImageView(this);
        this.f235j = imageView2;
        imageView2.setScaleType(ImageView.ScaleType.CENTER_CROP);
        this.f234i.addView(this.f235j, new FrameLayout.LayoutParams(-1, -1));
        View view = new View(this);
        this.f238m = view;
        this.f234i.addView(view, new FrameLayout.LayoutParams(-1, -1));
        int f2 = k.f.f(this, d2.D);
        int i4 = 0;
        while (true) {
            b0VarArr = this.q;
            if (i4 >= b0VarArr.length) {
                break;
            }
            b0VarArr[i4] = new b0(this, i4, this.f228c, this.f229d, this.i0);
            i4++;
        }
        this.p = new j(this, b0VarArr, f2, new C0013n(this));
        boolean equals = "bottom".equals(d2.g(this.M));
        boolean equals2 = "right".equals(d2.g(this.M));
        this.n = new C0094v(this, this.f229d, this.j0, equals, !d2.E, d2.C);
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        if (d2.E) {
            e0 e0Var = new e0(this, this.f231f, this.f232g);
            linearLayout.addView(e0Var, new LinearLayout.LayoutParams(-1, k.f.f(this, 42.0f)));
            this.o = e0Var.f803d;
            layoutParams.topMargin = k.f.f(this, 4.0f);
        } else {
            this.o = new O(this, this.f231f, this.f232g);
        }
        linearLayout.addView(this.p, layoutParams);
        this.C = linearLayout;
        LinearLayout linearLayout2 = new LinearLayout(this);
        this.v = linearLayout2;
        linearLayout2.setPadding(f2, f2, f2, f2);
        boolean equals3 = "none".equals(d2.g(this.M));
        if (equals3) {
            this.v.setOrientation(1);
            this.v.addView(linearLayout, new LinearLayout.LayoutParams(-1, -1));
        } else if (equals) {
            this.v.setOrientation(1);
            this.v.addView(linearLayout, new LinearLayout.LayoutParams(-1, 0, 1.0f));
            LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, k.f.f(this, d2.C));
            layoutParams2.topMargin = f2;
            this.v.addView(this.n, layoutParams2);
        } else {
            this.v.setOrientation(0);
            LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(k.f.f(this, d2.C), -1);
            LinearLayout.LayoutParams layoutParams4 = new LinearLayout.LayoutParams(0, -1, 1.0f);
            if (equals2) {
                layoutParams4.rightMargin = f2;
                this.v.addView(linearLayout, layoutParams4);
                this.v.addView(this.n, layoutParams3);
            } else {
                layoutParams4.leftMargin = f2;
                this.v.addView(this.n, layoutParams3);
                this.v.addView(linearLayout, layoutParams4);
            }
        }
        this.f234i.addView(this.v, new FrameLayout.LayoutParams(-1, -1));
        final int i5 = 0;
        this.n.addOnLayoutChangeListener(new View.OnLayoutChangeListener(this) { // from class: a.d

            /* renamed from: b, reason: collision with root package name */
            public final /* synthetic */ HomeActivity f88b;

            {
                this.f88b = this;
            }

            @Override // android.view.View.OnLayoutChangeListener
            public final void onLayoutChange(View view2, int i6, int i7, int i8, int i9, int i10, int i11, int i12, int i13) {
                HomeActivity homeActivity = this.f88b;
                switch (i5) {
                    case 0:
                        boolean z6 = HomeActivity.k0;
                        homeActivity.getClass();
                        if (i6 == i10 && i7 == i11 && i8 == i12 && i9 == i13) {
                            return;
                        }
                        homeActivity.X.y();
                        homeActivity.W.t();
                        return;
                    case 1:
                        homeActivity.X.z();
                        return;
                    default:
                        boolean z7 = HomeActivity.k0;
                        homeActivity.getClass();
                        if (i8 - i6 == i12 - i10 && i9 - i7 == i13 - i11) {
                            return;
                        }
                        homeActivity.X.b(homeActivity.f227b.d());
                        if (homeActivity.B != null) {
                            homeActivity.W.t();
                            return;
                        }
                        return;
                }
            }
        });
        final int i6 = 1;
        this.p.addOnLayoutChangeListener(new View.OnLayoutChangeListener(this) { // from class: a.d

            /* renamed from: b, reason: collision with root package name */
            public final /* synthetic */ HomeActivity f88b;

            {
                this.f88b = this;
            }

            @Override // android.view.View.OnLayoutChangeListener
            public final void onLayoutChange(View view2, int i62, int i7, int i8, int i9, int i10, int i11, int i12, int i13) {
                HomeActivity homeActivity = this.f88b;
                switch (i6) {
                    case 0:
                        boolean z6 = HomeActivity.k0;
                        homeActivity.getClass();
                        if (i62 == i10 && i7 == i11 && i8 == i12 && i9 == i13) {
                            return;
                        }
                        homeActivity.X.y();
                        homeActivity.W.t();
                        return;
                    case 1:
                        homeActivity.X.z();
                        return;
                    default:
                        boolean z7 = HomeActivity.k0;
                        homeActivity.getClass();
                        if (i8 - i62 == i12 - i10 && i9 - i7 == i13 - i11) {
                            return;
                        }
                        homeActivity.X.b(homeActivity.f227b.d());
                        if (homeActivity.B != null) {
                            homeActivity.W.t();
                            return;
                        }
                        return;
                }
            }
        });
        if (d2.E) {
            this.w = null;
        } else {
            FrameLayout.LayoutParams layoutParams5 = new FrameLayout.LayoutParams(-2, -2, 49);
            layoutParams5.topMargin = k.f.f(this, d2.x);
            FrameLayout frameLayout3 = new FrameLayout(this);
            this.w = frameLayout3;
            frameLayout3.addView(this.o, new FrameLayout.LayoutParams(-2, -2));
            this.f234i.addView(this.w, layoutParams5);
        }
        this.o.setMaxWidthDp(d2.y);
        this.o.setOnIslandClick(new RunnableC0004e(this, 0));
        K k2 = new K(this, this.f231f, this.f232g, YecaiApp.n.f258g, this.h0);
        this.x = k2;
        k2.setVisibility(8);
        this.f234i.addView(this.x, new FrameLayout.LayoutParams(-1, -1));
        float min = Math.min(60, d2.C);
        this.A = k.f.f(this, min);
        P p = new P(this, Math.round(min * 0.42f));
        this.z = p;
        p.setVisibility(4);
        C0013n c0013n = this.W;
        if (equals3) {
            this.z.setFloating(true);
            P p2 = this.z;
            FrameLayout frameLayout4 = this.f234i;
            SharedPreferences sharedPreferences2 = getSharedPreferences("shell", 0);
            boolean z6 = this.M;
            int f3 = k.f.f(this.f234i.getContext(), d2.D + 10);
            Objects.requireNonNull(c0013n);
            this.B = new ViewOnTouchListenerC0097y(p2, frameLayout4, sharedPreferences2, z6, f3, new RunnableC0005f(c0013n, 0), new RunnableC0005f(c0013n, 1));
        } else {
            this.B = null;
            int i7 = 0;
            this.z.setOnClickListener(new ViewOnClickListenerC0006g(this, i7));
            this.z.setOnLongClickListener(new ViewOnLongClickListenerC0007h(this, i7));
        }
        FrameLayout frameLayout5 = this.f234i;
        P p3 = this.z;
        int i8 = this.A;
        frameLayout5.addView(p3, new FrameLayout.LayoutParams(i8, i8));
        C0087n c0087n = new C0087n(this, this.f229d, this.f227b);
        this.r = c0087n;
        c0087n.setOnToggle(new C0008i(this, 0));
        this.f234i.addView(this.r, new FrameLayout.LayoutParams(-1, -1));
        C c2 = new C(this, this.f229d);
        this.s = c2;
        this.f234i.addView(c2, new FrameLayout.LayoutParams(-1, -1));
        T t = new T(this);
        this.t = t;
        t.setOnToggle(new C0008i(this, 1));
        this.f234i.addView(this.t, new FrameLayout.LayoutParams(-1, -1));
        N n = new N(this, new C0013n(this));
        this.u = n;
        n.setVisibility(8);
        this.f234i.addView(this.u, new FrameLayout.LayoutParams(-1, -1));
        setContentView(this.f234i);
        final int i9 = 2;
        this.f234i.addOnLayoutChangeListener(new View.OnLayoutChangeListener(this) { // from class: a.d

            /* renamed from: b, reason: collision with root package name */
            public final /* synthetic */ HomeActivity f88b;

            {
                this.f88b = this;
            }

            @Override // android.view.View.OnLayoutChangeListener
            public final void onLayoutChange(View view2, int i62, int i72, int i82, int i92, int i10, int i11, int i12, int i13) {
                HomeActivity homeActivity = this.f88b;
                switch (i9) {
                    case 0:
                        boolean z62 = HomeActivity.k0;
                        homeActivity.getClass();
                        if (i62 == i10 && i72 == i11 && i82 == i12 && i92 == i13) {
                            return;
                        }
                        homeActivity.X.y();
                        homeActivity.W.t();
                        return;
                    case 1:
                        homeActivity.X.z();
                        return;
                    default:
                        boolean z7 = HomeActivity.k0;
                        homeActivity.getClass();
                        if (i82 - i62 == i12 - i10 && i92 - i72 == i13 - i11) {
                            return;
                        }
                        homeActivity.X.b(homeActivity.f227b.d());
                        if (homeActivity.B != null) {
                            homeActivity.W.t();
                            return;
                        }
                        return;
                }
            }
        });
        k.j.b(this, d2.F);
        this.f228c.f1271h = this;
        ((CopyOnWriteArrayList) this.f227b.f285e).addIfAbsent(this);
        ((CopyOnWriteArrayList) this.f229d.f979g).addIfAbsent(this);
        ((CopyOnWriteArrayList) this.f233h.f197e).addIfAbsent(this);
        f(d2);
        if (YecaiApp.n.f263l) {
            c0013n.f(false);
        }
        C0008i c0008i = new C0008i(this, 2);
        j.I.f937b = c0008i;
        ArrayList arrayList = j.I.f938c;
        if (!arrayList.isEmpty()) {
            ArrayList arrayList2 = new ArrayList(arrayList);
            arrayList.clear();
            Iterator it = arrayList2.iterator();
            while (it.hasNext()) {
                c0008i.c((String) it.next());
            }
        }
        s.setHost(this.g0);
        IntentFilter intentFilter = new IntentFilter("android.intent.action.SCREEN_OFF");
        intentFilter.addAction("android.intent.action.SCREEN_ON");
        intentFilter.addAction("android.intent.action.USER_PRESENT");
        registerReceiver(this.f0, intentFilter);
        if (bundle == null || l0) {
            z = true;
            z2 = false;
        } else {
            z2 = false;
            z = false;
        }
        l0 = z2;
        if (!z || k0) {
            return;
        }
        k0 = true;
        j.I.a(this, "desktop_start");
    }

    @Override // android.app.Activity
    public final void onDestroy() {
        if (this.a0) {
            super.onDestroy();
            return;
        }
        k.g gVar = this.f236k;
        if (gVar != null) {
            gVar.a();
            gVar.f1104d = null;
            gVar.f1105e = false;
            gVar.f1107g = false;
            gVar.f1101a.setAlpha(0.0f);
        }
        try {
            unregisterReceiver(this.f0);
        } catch (IllegalArgumentException unused) {
        }
        j.I.f937b = null;
        if (s.f1203j == this.g0) {
            s.f1203j = null;
        }
        ViewOnTouchListenerC0097y viewOnTouchListenerC0097y = this.B;
        if (viewOnTouchListenerC0097y != null) {
            Handler handler = viewOnTouchListenerC0097y.f887h;
            handler.removeCallbacks(viewOnTouchListenerC0097y.s);
            handler.removeCallbacks(viewOnTouchListenerC0097y.r);
            viewOnTouchListenerC0097y.f880a.setOnTouchListener(null);
        }
        ((CopyOnWriteArrayList) this.f227b.f285e).remove(this);
        ((CopyOnWriteArrayList) this.f229d.f979g).remove(this);
        ((CopyOnWriteArrayList) this.f233h.f197e).remove(this);
        this.f228c.f1271h = null;
        super.onDestroy();
    }

    @Override // android.app.Activity
    public final void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!this.a0 && "android.intent.action.MAIN".equals(intent.getAction())) {
            this.t.b();
            this.r.f();
            this.s.a();
            for (b0 b0Var : this.q) {
                b0Var.f();
            }
            if (this.p.o >= 0) {
                r(-1);
            }
            if (this.G) {
                return;
            }
            this.W.g();
        }
    }

    @Override // android.app.Activity
    public final void onPause() {
        if (!this.a0) {
            this.f228c.h();
        }
        super.onPause();
    }

    @Override // android.app.Activity
    public final void onResume() {
        super.onResume();
        if (this.a0) {
            return;
        }
        this.G = false;
        k.j.b(this, this.f227b.d().F);
    }

    @Override // android.app.Activity
    public final void onStart() {
        int i2 = 0;
        int i3 = 3;
        int i4 = 1;
        super.onStart();
        if (this.a0) {
            return;
        }
        this.f228c.e();
        final i iVar = this.f228c;
        if (!iVar.f1272i) {
            iVar.f1272i = true;
            if (iVar.r == null) {
                try {
                    ActivityManager activityManager = (ActivityManager) iVar.f1264a.getSystemService(ActivityManager.class);
                    if (activityManager != null) {
                        Class<?> cls = Class.forName("android.app.ActivityManager$OnUidImportanceListener");
                        Object newProxyInstance = Proxy.newProxyInstance(i.class.getClassLoader(), new Class[]{cls}, new InvocationHandler() { // from class: m.h
                            @Override // java.lang.reflect.InvocationHandler
                            public final Object invoke(Object obj, Method method, Object[] objArr) {
                                i iVar2 = i.this;
                                iVar2.getClass();
                                if (!"onUidImportance".equals(method.getName()) || objArr == null || objArr.length != 2) {
                                    if ("hashCode".equals(method.getName())) {
                                        return Integer.valueOf(System.identityHashCode(obj));
                                    }
                                    if ("equals".equals(method.getName())) {
                                        return Boolean.valueOf(objArr != null && obj == objArr[0]);
                                    }
                                    if ("toString".equals(method.getName())) {
                                        return "YecaiUidWatch";
                                    }
                                    return null;
                                }
                                int intValue = ((Integer) objArr[0]).intValue();
                                if (((Integer) objArr[1]).intValue() < 1000 || !iVar2.f1272i) {
                                    return null;
                                }
                                for (String str : iVar2.f1266c.keySet()) {
                                    HashMap hashMap = iVar2.s;
                                    Integer num = (Integer) hashMap.get(str);
                                    if (num == null) {
                                        try {
                                            num = Integer.valueOf(iVar2.f1264a.getPackageManager().getApplicationInfo(str, 0).uid);
                                            hashMap.put(str, num);
                                        } catch (Throwable unused) {
                                            num = null;
                                        }
                                    }
                                    if (num != null && num.intValue() == intValue) {
                                        iVar2.f1269f.post(new RunnableC0009j(iVar2, 17));
                                        return null;
                                    }
                                }
                                return null;
                            }
                        });
                        ActivityManager.class.getMethod("addOnUidImportanceListener", cls, Integer.TYPE).invoke(activityManager, newProxyInstance, 1000);
                        iVar.r = newProxyInstance;
                        Log.i("YecaiEngine", "进程监听已注册：应用一死就立刻查，轮询退到 8000ms 兜底");
                    }
                } catch (Throwable th) {
                    AbstractC0010k.b("进程监听注册失败，退回纯轮询: ", th, "YecaiEngine");
                    iVar.r = null;
                }
            }
            iVar.f1269f.post(iVar.v);
        }
        BallService.a(this, this.f227b.d());
        BallService.h(this, true);
        Handler handler = this.T;
        RunnableC0011l runnableC0011l = this.Y;
        handler.removeCallbacks(runnableC0011l);
        handler.postDelayed(runnableC0011l, this.f226a);
        registerReceiver(this.Z, new IntentFilter("android.intent.action.TIME_TICK"));
        AbstractC0100b.f965a = new RunnableC0004e(this, i3);
        this.f233h.n(false);
        this.S = null;
        this.f237l = true;
        d.g d2 = this.f227b.d();
        if (d2.e0) {
            AbstractC0108j.a(this, true);
        }
        boolean i5 = i();
        C0013n c0013n = this.X;
        if (!i5) {
            c0013n.b(d2);
        }
        if (d2.d0) {
            C0008i c0008i = new C0008i(this, i3);
            if (!AbstractC0108j.f990f) {
                c.a aVar = new c.a(c0008i, 8);
                m.a(new C0065u0(getApplicationContext(), i4), new J(aVar), new J(aVar));
            }
        }
        YecaiApp yecaiApp = YecaiApp.n;
        boolean z = yecaiApp.f264m;
        yecaiApp.f264m = false;
        if (z) {
            this.W.f(true);
        }
        N n = this.u;
        if (n != null && n.getVisibility() != 0 && !this.y && this.f233h.i() && !getSharedPreferences("shell", 0).getBoolean("guideDone", false)) {
            k(true);
            N n2 = this.u;
            n2.f729c = 0;
            n2.d();
            n2.setAlpha(0.0f);
            n2.setVisibility(0);
            n2.animate().alpha(1.0f).setDuration(180L).start();
        }
        l.k kVar = new l.k(this, 2);
        H h2 = e.a.f324g;
        if (h2 != null && !e.a.f325h) {
            e.a.f324g = null;
            e.a.h(this, h2);
        } else if (!e.a.f323f) {
            e.a.f323f = true;
            new Handler(Looper.getMainLooper()).postDelayed(new S(this, kVar, i2), 20000L);
        }
        c0013n.z();
        boolean z2 = this.H;
        if (z2) {
            this.G = false;
            if (z2) {
                this.H = false;
                f(this.f227b.d());
            }
        }
    }

    @Override // android.app.Activity
    public final void onStop() {
        if (this.a0) {
            super.onStop();
            return;
        }
        this.G = true;
        this.f237l = false;
        this.X.z();
        BallService.h(this, false);
        i iVar = this.f228c;
        iVar.f1272i = false;
        iVar.f1269f.removeCallbacks(iVar.v);
        if (iVar.r != null) {
            try {
                ActivityManager activityManager = (ActivityManager) iVar.f1264a.getSystemService(ActivityManager.class);
                Class<?> cls = Class.forName("android.app.ActivityManager$OnUidImportanceListener");
                if (activityManager != null) {
                    ActivityManager.class.getMethod("removeOnUidImportanceListener", cls).invoke(activityManager, iVar.r);
                }
            } catch (Throwable unused) {
            }
            iVar.r = null;
        }
        this.T.removeCallbacks(this.Y);
        try {
            unregisterReceiver(this.Z);
        } catch (IllegalArgumentException unused2) {
        }
        AbstractC0100b.f965a = null;
        super.onStop();
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public final void onWindowFocusChanged(boolean z) {
        super.onWindowFocusChanged(z);
        if (this.a0) {
            if (z) {
                k.j.b(this, createDeviceProtectedStorageContext().getSharedPreferences("boot", 0).getBoolean("fullscreen", true));
            }
        } else if (z) {
            k.j.b(this, this.f227b.d().F);
        }
    }

    public final void p(int i2, String str) {
        this.J[i2] = SystemClock.uptimeMillis();
        s(i2, b.a(str));
        v(i2);
    }

    /* JADX WARN: Removed duplicated region for block: B:26:0x007f  */
    /* JADX WARN: Removed duplicated region for block: B:32:0x01d5  */
    /* JADX WARN: Removed duplicated region for block: B:35:0x01dd  */
    /* JADX WARN: Removed duplicated region for block: B:37:0x00c8  */
    /* JADX WARN: Type inference failed for: r8v0 */
    /* JADX WARN: Type inference failed for: r8v1, types: [int, boolean] */
    /* JADX WARN: Type inference failed for: r8v15 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void q() {
        /*
            Method dump skipped, instructions count: 498
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: cn.wayecai.launcher.HomeActivity.q():void");
    }

    public final void r(int i2) {
        this.p.setMaximized(i2);
        int i3 = 0;
        while (true) {
            b0[] b0VarArr = this.q;
            boolean z = true;
            if (i3 >= b0VarArr.length) {
                break;
            }
            b0 b0Var = b0VarArr[i3];
            if (i3 != i2) {
                z = false;
            }
            b0Var.setMaximized(z);
            i3++;
        }
        this.o.setMaximizedHint(i2 >= 0);
        w();
    }

    public final void s(int i2, b bVar) {
        String z = z(bVar);
        b bVar2 = this.f227b;
        d.g d2 = bVar2.d();
        if (z != null) {
            j(d2, z, i2);
        }
        d2.f310f[i2] = bVar;
        bVar2.j();
        bVar2.i();
    }

    public final void u(String str) {
        if (this.y) {
            this.x.s.c(str);
        } else {
            this.o.c(str);
        }
    }

    public final void v(int i2) {
        this.J[i2] = SystemClock.uptimeMillis();
        if (!this.f227b.d().f310f[i2].e()) {
            i2 = -1;
        }
        if (i2 == this.I) {
            return;
        }
        this.I = i2;
        int i3 = 0;
        while (true) {
            b0[] b0VarArr = this.q;
            if (i3 >= b0VarArr.length) {
                return;
            }
            b0VarArr[i3].setFocused(i3 == this.I);
            i3++;
        }
    }

    public final void w() {
        if (this.n == null || this.p == null) {
            return;
        }
        boolean[] y = y();
        C0094v c0094v = this.n;
        boolean z = y[0];
        boolean z2 = y[1];
        boolean z3 = y[2];
        c0094v.u = z;
        c0094v.v = z2;
        c0094v.w = z3;
        for (Map.Entry entry : c0094v.x.entrySet()) {
            c0094v.c((String) entry.getKey(), (View) entry.getValue());
        }
        r rVar = s.f1203j;
        Iterator it = new ArrayList(s.f1204k).iterator();
        while (it.hasNext()) {
            ((s) it.next()).g(false);
        }
    }

    public final int x(String str) {
        if (str == null) {
            return -1;
        }
        d.g d2 = this.f227b.d();
        Iterator it = d2.q(this.M).j().iterator();
        while (it.hasNext()) {
            int intValue = ((Integer) it.next()).intValue();
            b[] bVarArr = d2.f310f;
            if (bVarArr[intValue].e() && str.equals((String) bVarArr[intValue].f283c)) {
                return intValue;
            }
        }
        return -1;
    }

    public final boolean[] y() {
        f.a q = this.f227b.d().q(this.M);
        j jVar = this.p;
        f.k kVar = jVar.p;
        return new boolean[]{kVar == null ? j.f(jVar.n, null) != null : !this.f227b.d().f310f[kVar.f406d[0]].g(), q.b(0) && q.j().size() >= 2, this.p.o == 0};
    }
}
