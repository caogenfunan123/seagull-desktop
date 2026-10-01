package cn.wayecai.launcher;

import a.AbstractC0010k;
import a.K;
import a.L;
import a.M;
import a.N;
import a.O;
import a.P;
import a.Q;
import android.app.Activity;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import c.f;
import c.g;
import d.a;
import d.b;
import h.AbstractC0042i0;
import h.C;
import h.C0;
import h.C0028b0;
import h.C0033e;
import h.C0038g0;
import h.C0060s;
import h.C0061s0;
import h.C0071x0;
import h.E;
import h.R0;
import h.U;
import h.ViewOnClickListenerC0035f;
import i.C0087n;
import j.C0105g;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import k.e;
import k.j;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes.dex */
public class SettingsActivity extends Activity implements a, f, U {
    public static final String[] E = {"account", "desktop", "appearance", "widgets", "advanced", "system"};
    public static final String[] F = {"账号与云端", "桌面", "外观", "天气与歌词", "高级功能", "系统与工具"};
    public static final int[] G = {R.drawable.ic_user, R.drawable.ic_layout, R.drawable.ic_image, R.drawable.ic_widget, R.drawable.ic_tune, R.drawable.ic_system};
    public static final String[][] H = {new String[]{"acct", "backup"}, new String[]{"layout", "dock", "quickbar", "garden", "ball"}, new String[]{"theme", "screen", "island"}, new String[]{"weather", "lyrics"}, new String[]{"window", "touch", "tasks", "bootanim"}, new String[]{"system", "market", "about"}};
    public static final String[][] I = {new String[]{"账号", "备份与恢复"}, new String[]{"布局", "Dock 栏", "快捷栏", "菜园", "小白点"}, new String[]{"主题与壁纸", "屏幕", "野菜岛"}, new String[]{"天气", "歌词"}, new String[]{"窗口", "触摸", "自动化任务", "开机动画"}, new String[]{"系统", "车机工具", "关于"}};
    public g A;
    public ArrayList B;

    /* renamed from: a, reason: collision with root package name */
    public b f239a;

    /* renamed from: b, reason: collision with root package name */
    public C0105g f240b;

    /* renamed from: c, reason: collision with root package name */
    public g f241c;

    /* renamed from: d, reason: collision with root package name */
    public String f242d;

    /* renamed from: e, reason: collision with root package name */
    public String f243e;

    /* renamed from: f, reason: collision with root package name */
    public boolean f244f;

    /* renamed from: g, reason: collision with root package name */
    public int f245g;

    /* renamed from: h, reason: collision with root package name */
    public int f246h;

    /* renamed from: i, reason: collision with root package name */
    public int f247i;

    /* renamed from: j, reason: collision with root package name */
    public boolean f248j;

    /* renamed from: k, reason: collision with root package name */
    public int f249k;

    /* renamed from: l, reason: collision with root package name */
    public ScrollView f250l;

    /* renamed from: m, reason: collision with root package name */
    public LinearLayout f251m;
    public ScrollView n;
    public LinearLayout o;
    public EditText p;
    public LinearLayout q;
    public String s;
    public ScrollView t;
    public LinearLayout u;
    public C0087n v;
    public boolean w;
    public boolean x;
    public C0061s0 z;
    public String r = "";
    public final Handler y = new Handler(Looper.getMainLooper());
    public final HashMap C = new HashMap();
    public final Q D = new Q(this, 0);

    public static int h(String str) {
        for (int i2 = 0; i2 < 6; i2++) {
            if (E[i2].equals(str)) {
                return i2;
            }
        }
        return 0;
    }

    public static String i(String str) {
        for (int i2 = 0; i2 < 6; i2++) {
            for (String str2 : H[i2]) {
                if (str2.equals(str)) {
                    return E[i2];
                }
            }
        }
        return null;
    }

    @Override // c.f
    public final void a() {
        if (this.f241c.i()) {
            g gVar = this.A;
            if (((JSONArray) gVar.f195c) == null) {
                gVar.h();
            }
        } else {
            g gVar2 = this.A;
            gVar2.f195c = null;
            gVar2.f196d = null;
        }
        l();
    }

    @Override // d.a
    public final void b(d.g gVar) {
        if (j.n(this, gVar.v) != this.w || gVar.w != this.f245g || gVar.h0 != this.f246h) {
            recreate();
        } else {
            j.b(this, gVar.F);
            l();
        }
    }

    public final void c() {
        if (this.f244f) {
            this.f250l.setVisibility(this.f247i == 0 ? 0 : 8);
            this.n.setVisibility(this.f247i == 1 ? 0 : 8);
            this.t.setVisibility(this.f247i == 2 ? 0 : 8);
        }
    }

    public final void d() {
        C0033e c0033e = (C0033e) k(C0033e.class);
        c0033e.p.removeCallbacks(c0033e.w);
        c0033e.v = null;
        this.u.removeAllViews();
        this.z.f641g.clear();
        HashMap hashMap = this.C;
        AbstractC0042i0 abstractC0042i0 = (AbstractC0042i0) hashMap.get(this.f243e);
        String y = abstractC0042i0 != null ? abstractC0042i0.y(this.f243e) : null;
        if (y == null) {
            int h2 = h(this.f242d);
            int i2 = 0;
            while (true) {
                String[] strArr = H[h2];
                if (i2 >= strArr.length) {
                    y = F[h2];
                    break;
                } else {
                    if (strArr[i2].equals(this.f243e)) {
                        y = I[h2][i2];
                        break;
                    }
                    i2++;
                }
            }
        }
        LinearLayout c2 = this.z.c();
        if (this.f244f) {
            c2.addView(k.f.h(this, R.drawable.ic_back, k.f.f1094g, 48, 24, new K(this, 2)), new LinearLayout.LayoutParams(k.f.f(this, 48.0f), k.f.f(this, 48.0f)));
            TextView b2 = k.f.b(this, y, 22.0f, k.f.f1094g);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-2, -2);
            layoutParams.leftMargin = k.f.f(this, 4.0f);
            c2.addView(b2, layoutParams);
        } else {
            c2.addView(k.f.b(this, y, 26.0f, k.f.f1094g));
        }
        this.u.addView(c2);
        C0061s0 c0061s0 = this.z;
        c0061s0.f643i = c2;
        c0061s0.f644j = y;
        if (!"account".equals(this.f242d) || this.f241c.i() || "backup".equals(this.f243e)) {
            AbstractC0042i0 abstractC0042i02 = (AbstractC0042i0) hashMap.get(this.f243e);
            if (abstractC0042i02 == null) {
                abstractC0042i02 = k(R0.class);
            }
            abstractC0042i02.a(this.f243e);
            String str = this.s;
            if (str == null) {
                return;
            }
            View view = (View) this.z.f641g.get(str);
            this.s = null;
            this.t.post(new O(this, view, k.f.f(this, 12.0f), 0));
            return;
        }
        this.s = null;
        final C0060s c0060s = (C0060s) k(C0060s.class);
        c0060s.t("登录", "登录后才能使用画中画。登录一次后离线也能用 10 天，联网时自动续期。");
        ViewOnClickListenerC0035f viewOnClickListenerC0035f = new ViewOnClickListenerC0035f(c0060s, 5);
        SettingsActivity settingsActivity = c0060s.f559c;
        View c3 = k.f.c(settingsActivity, "微信扫码登录 / 注册", true, viewOnClickListenerC0035f);
        C0061s0 c0061s02 = c0060s.f558b;
        ViewGroup.LayoutParams a2 = c0061s02.a(4);
        LinearLayout linearLayout = c0060s.f560d;
        linearLayout.addView(c3, a2);
        c0060s.m("拿手机微信扫一下就行，不用在车机上输手机号。第一次会在小程序里授权手机号并送 30 天 PRO。");
        c0060s.t("用手机号和密码登录", null);
        final EditText e2 = c0061s02.e("手机号（老账号填用户名）", false);
        String string = ((SharedPreferences) c0060s.f568l.f196d).getString("phone", null);
        if (string != null && string.matches("\\d{11}")) {
            e2.setText(string);
        }
        linearLayout.addView(e2, c0061s02.a(8));
        final EditText e3 = c0061s02.e("密码", true);
        linearLayout.addView(e3, c0061s02.a(12));
        final TextView o = k.f.o(settingsActivity, "", 14.0f, k.f.n);
        o.setVisibility(8);
        linearLayout.addView(o, c0061s02.a(12));
        final TextView c4 = k.f.c(settingsActivity, "登录", true, null);
        c4.setOnClickListener(new View.OnClickListener() { // from class: h.o
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                C0060s c0060s2 = C0060s.this;
                final String trim = e2.getText().toString().trim();
                final String obj = e3.getText().toString();
                boolean isEmpty = trim.isEmpty();
                TextView textView = o;
                final boolean z = false;
                if (isEmpty || obj.isEmpty()) {
                    textView.setText("请填写手机号和密码");
                    textView.setVisibility(0);
                    return;
                }
                TextView textView2 = c4;
                textView2.setEnabled(false);
                textView.setVisibility(8);
                a.H h3 = new a.H(c0060s2, textView, textView2, 4);
                final c.g gVar = c0060s2.f568l;
                gVar.getClass();
                c.m.a(new c.l() { // from class: c.d
                    @Override // c.l
                    public final Object run() {
                        g gVar2 = g.this;
                        gVar2.getClass();
                        JSONObject jSONObject = new JSONObject();
                        String str2 = trim;
                        jSONObject.put("account", str2.trim());
                        jSONObject.put("username", str2.trim());
                        jSONObject.put("password", obj);
                        jSONObject.put("device_id", ((SharedPreferences) gVar2.f196d).getString("deviceId", ""));
                        jSONObject.put("device_name", (Build.MANUFACTURER + " " + Build.MODEL).trim());
                        jSONObject.put("force", z);
                        jSONObject.put("apk_sha256", gVar2.a());
                        jSONObject.put("version_code", 38);
                        jSONObject.put("flavor", "platform");
                        return i.a("POST", "/auth/login", jSONObject, null);
                    }
                }, new a.H(gVar, trim, h3), new a.U(gVar, z, h3, trim, obj));
            }
        });
        linearLayout.addView(c4, c0061s02.a(16));
        c0060s.m("没有账号就用上面的微信扫码。忘记密码请联系作者重置（抖音 开tt挖野菜）。");
    }

    public final void e() {
        this.f251m.removeAllViews();
        LinearLayout c2 = this.z.c();
        c2.addView(k.f.h(this, R.drawable.ic_back, k.f.f1094g, 48, 24, new K(this, 0)), new LinearLayout.LayoutParams(k.f.f(this, 48.0f), k.f.f(this, 48.0f)));
        TextView b2 = k.f.b(this, "野菜设置", 20.0f, k.f.f1094g);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-2, -2);
        layoutParams.leftMargin = k.f.f(this, 6.0f);
        c2.addView(b2, layoutParams);
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams2.bottomMargin = k.f.f(this, 10.0f);
        this.f251m.addView(c2, layoutParams2);
        if (this.p == null) {
            EditText e2 = this.z.e("搜索设置", false);
            this.p = e2;
            e2.setTextSize(15.0f * k.f.s * k.f.r);
            this.p.setMinHeight(k.f.f(this, 44.0f));
            this.p.setImeOptions(3);
            this.p.addTextChangedListener(new P(this, 0));
        }
        if (this.p.getParent() instanceof ViewGroup) {
            ((ViewGroup) this.p.getParent()).removeView(this.p);
        }
        LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams3.bottomMargin = k.f.f(this, 10.0f);
        this.f251m.addView(this.p, layoutParams3);
        if (this.q == null) {
            LinearLayout linearLayout = new LinearLayout(this);
            this.q = linearLayout;
            linearLayout.setOrientation(1);
        }
        if (this.q.getParent() instanceof ViewGroup) {
            ((ViewGroup) this.q.getParent()).removeView(this.q);
        }
        this.f251m.addView(this.q, new LinearLayout.LayoutParams(-1, -2));
        f();
    }

    public final void f() {
        TextView o;
        String str;
        this.q.removeAllViews();
        boolean isEmpty = this.r.isEmpty();
        String[] strArr = F;
        float f2 = 12.0f;
        int i2 = 0;
        if (isEmpty) {
            while (i2 < 6) {
                String str2 = E[i2];
                boolean z = str2.equals(this.f242d) && !this.f244f;
                LinearLayout c2 = this.z.c();
                c2.setMinimumHeight(k.f.f(this, this.f244f ? 56.0f : 50.0f));
                c2.setPadding(k.f.f(this, 14.0f), 0, k.f.f(this, 12.0f), 0);
                c2.setBackground(k.f.j(this, z ? k.f.f1099l : 0, 12.0f));
                c2.addView(k.f.g(this, G[i2], z ? k.f.f1097j : k.f.f1095h), new LinearLayout.LayoutParams(k.f.f(this, 20.0f), k.f.f(this, 20.0f)));
                if (z) {
                    o = k.f.b(this, strArr[i2], 15.0f, k.f.f1097j);
                } else {
                    o = k.f.o(this, strArr[i2], this.f244f ? 16.0f : 15.0f, k.f.f1094g);
                }
                o.setSingleLine(true);
                o.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
                layoutParams.leftMargin = k.f.f(this, 12.0f);
                c2.addView(o, layoutParams);
                if ("account".equals(str2) && !this.f241c.m()) {
                    c2.addView(k.f.o(this, this.f241c.i() ? "已过期" : "未登录", 11.0f, k.f.n), this.z.b(6));
                }
                c2.setOnClickListener(new L(this, str2, 1));
                LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
                layoutParams2.bottomMargin = k.f.f(this, 2.0f);
                this.q.addView(c2, layoutParams2);
                i2++;
            }
            return;
        }
        String lowerCase = this.r.toLowerCase();
        ArrayList arrayList = new ArrayList();
        e.a.a(arrayList, C0060s.s);
        e.a.a(arrayList, C.s);
        e.a.a(arrayList, E.x);
        e.a.a(arrayList, E.u);
        e.a.a(arrayList, E.y);
        e.a.a(arrayList, E.v);
        e.a.a(arrayList, E.t);
        e.a.a(arrayList, R0.u);
        e.a.a(arrayList, E.z);
        e.a.a(arrayList, E.w);
        e.a.a(arrayList, E.D);
        e.a.a(arrayList, C0028b0.t);
        e.a.a(arrayList, E.E);
        e.a.a(arrayList, E.A);
        e.a.a(arrayList, C0.u);
        e.a.a(arrayList, h.L.E);
        e.a.a(arrayList, C0071x0.t);
        e.a.a(arrayList, C0038g0.x);
        e.a.a(arrayList, C0033e.A);
        Iterator it = arrayList.iterator();
        int i3 = 0;
        while (it.hasNext()) {
            String[] strArr2 = (String[]) it.next();
            String str3 = strArr2[i2];
            String str4 = strArr2[1];
            String str5 = strArr2[2];
            if (str3.toLowerCase().contains(lowerCase) || str5.toLowerCase().contains(lowerCase)) {
                String i4 = i(str4);
                if (i4 != null) {
                    int i5 = i3 + 1;
                    int h2 = h(i4);
                    LinearLayout linearLayout = new LinearLayout(this);
                    linearLayout.setOrientation(1);
                    linearLayout.setMinimumHeight(k.f.f(this, 52.0f));
                    linearLayout.setPadding(k.f.f(this, f2), k.f.f(this, 6.0f), k.f.f(this, 10.0f), k.f.f(this, 6.0f));
                    linearLayout.setBackground(k.f.j(this, i2, 10.0f));
                    TextView o2 = k.f.o(this, str3, 15.0f, k.f.f1094g);
                    o2.setSingleLine(true);
                    o2.setEllipsize(TextUtils.TruncateAt.END);
                    linearLayout.addView(o2, new LinearLayout.LayoutParams(-1, -2));
                    StringBuilder sb = new StringBuilder();
                    sb.append(strArr[h2]);
                    sb.append(" · ");
                    int h3 = h(i4);
                    int i6 = i2;
                    while (true) {
                        String[] strArr3 = H[h3];
                        if (i6 >= strArr3.length) {
                            str = "";
                            break;
                        } else {
                            if (strArr3[i6].equals(str4)) {
                                str = I[h3][i6];
                                break;
                            }
                            i6++;
                        }
                    }
                    sb.append(str);
                    TextView o3 = k.f.o(this, sb.toString(), 11.0f, k.f.f1096i);
                    o3.setSingleLine(true);
                    o3.setEllipsize(TextUtils.TruncateAt.END);
                    linearLayout.addView(o3, new LinearLayout.LayoutParams(-1, -2));
                    linearLayout.setOnClickListener(new N(this, i4, str4, str3, 0));
                    LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, -2);
                    layoutParams3.bottomMargin = k.f.f(this, 2.0f);
                    this.q.addView(linearLayout, layoutParams3);
                    i2 = i2;
                    lowerCase = lowerCase;
                    i3 = i5;
                    f2 = 12.0f;
                }
            }
        }
        int i7 = i2;
        if (i3 == 0) {
            TextView o4 = k.f.o(this, AbstractC0010k.a(new StringBuilder("没找到「"), this.r, "」"), 14.0f, k.f.f1096i);
            o4.setPadding(k.f.f(this, 12.0f), k.f.f(this, 12.0f), i7, i7);
            this.q.addView(o4, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    @Override // android.app.Activity
    public final void finish() {
        super.finish();
        if (Build.VERSION.SDK_INT < 34) {
            overridePendingTransition(R.anim.yecai_hold, R.anim.yecai_fade_out);
        }
    }

    public final void g() {
        TextView o;
        this.o.removeAllViews();
        int h2 = h(this.f242d);
        boolean z = this.f244f;
        String[] strArr = F;
        if (z) {
            LinearLayout c2 = this.z.c();
            c2.addView(k.f.h(this, R.drawable.ic_back, k.f.f1094g, 48, 24, new K(this, 1)), new LinearLayout.LayoutParams(k.f.f(this, 48.0f), k.f.f(this, 48.0f)));
            TextView b2 = k.f.b(this, strArr[h2], 20.0f, k.f.f1094g);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-2, -2);
            layoutParams.leftMargin = k.f.f(this, 4.0f);
            c2.addView(b2, layoutParams);
            LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
            layoutParams2.bottomMargin = k.f.f(this, 8.0f);
            this.o.addView(c2, layoutParams2);
        } else {
            TextView o2 = k.f.o(this, strArr[h2], 12.0f, k.f.f1096i);
            o2.setPadding(k.f.f(this, 12.0f), 0, 0, k.f.f(this, 6.0f));
            this.o.addView(o2, new LinearLayout.LayoutParams(-1, -2));
        }
        int i2 = 0;
        while (true) {
            String[] strArr2 = H[h2];
            if (i2 >= strArr2.length) {
                return;
            }
            String str = strArr2[i2];
            boolean z2 = str.equals(this.f243e) && !this.f244f;
            LinearLayout c3 = this.z.c();
            c3.setMinimumHeight(k.f.f(this, this.f244f ? 56.0f : 46.0f));
            c3.setPadding(k.f.f(this, 12.0f), 0, k.f.f(this, 10.0f), 0);
            c3.setBackground(k.f.j(this, z2 ? k.f.f1099l : 0, 10.0f));
            String[][] strArr3 = I;
            if (z2) {
                o = k.f.b(this, strArr3[h2][i2], 15.0f, k.f.f1097j);
            } else {
                o = k.f.o(this, strArr3[h2][i2], this.f244f ? 16.0f : 15.0f, k.f.f1094g);
            }
            c3.addView(o, new LinearLayout.LayoutParams(0, -2, 1.0f));
            c3.setOnClickListener(new L(this, str, 0));
            LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, -2);
            layoutParams3.bottomMargin = k.f.f(this, 2.0f);
            this.o.addView(c3, layoutParams3);
            i2++;
        }
    }

    public final boolean j() {
        return "account".equals(this.f242d) || "backup".equals(this.f243e);
    }

    public final AbstractC0042i0 k(Class cls) {
        Iterator it = this.B.iterator();
        while (it.hasNext()) {
            AbstractC0042i0 abstractC0042i0 = (AbstractC0042i0) it.next();
            if (cls.isInstance(abstractC0042i0)) {
                return (AbstractC0042i0) cls.cast(abstractC0042i0);
            }
        }
        throw new IllegalStateException("没有这一页：".concat(cls.getSimpleName()));
    }

    public final void l() {
        int scrollY = this.t.getScrollY();
        e();
        g();
        d();
        this.t.post(new M(this, scrollY, 0));
    }

    public final void m(String str) {
        this.f243e = str;
        if (this.f244f) {
            this.f247i = 2;
        }
        c();
        g();
        d();
        this.t.scrollTo(0, 0);
        if ("backup".equals(str)) {
            g gVar = this.A;
            if (((JSONArray) gVar.f195c) == null) {
                gVar.h();
            }
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:15:0x006d A[Catch: Exception -> 0x0072, TRY_LEAVE, TryCatch #5 {Exception -> 0x0072, blocks: (B:15:0x006d, B:97:0x0066, B:98:0x0069, B:92:0x0060), top: B:11:0x0042, inners: #3 }] */
    /* JADX WARN: Removed duplicated region for block: B:18:0x007a  */
    /* JADX WARN: Removed duplicated region for block: B:21:0x008f  */
    /* JADX WARN: Removed duplicated region for block: B:23:0x0099  */
    /* JADX WARN: Removed duplicated region for block: B:25:0x00a1  */
    /* JADX WARN: Removed duplicated region for block: B:75:0x0194  */
    /* JADX WARN: Removed duplicated region for block: B:78:0x0096  */
    /* JADX WARN: Type inference failed for: r0v42, types: [b.e, java.lang.Object] */
    /* JADX WARN: Type inference failed for: r3v36, types: [android.content.ContentResolver] */
    /* JADX WARN: Type inference failed for: r4v10, types: [android.net.Uri] */
    /* JADX WARN: Type inference failed for: r4v11 */
    /* JADX WARN: Type inference failed for: r4v12 */
    /* JADX WARN: Type inference failed for: r4v13 */
    /* JADX WARN: Type inference failed for: r4v15 */
    /* JADX WARN: Type inference failed for: r4v16 */
    /* JADX WARN: Type inference failed for: r4v17 */
    /* JADX WARN: Type inference failed for: r4v18 */
    /* JADX WARN: Type inference failed for: r4v19 */
    /* JADX WARN: Type inference failed for: r4v6 */
    /* JADX WARN: Type inference failed for: r4v7, types: [java.lang.String] */
    @Override // android.app.Activity
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void onActivityResult(int r22, int r23, android.content.Intent r24) {
        /*
            Method dump skipped, instructions count: 693
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: cn.wayecai.launcher.SettingsActivity.onActivityResult(int, int, android.content.Intent):void");
    }

    @Override // android.app.Activity
    public final void onBackPressed() {
        int i2;
        if (this.v.k()) {
            this.v.c();
            return;
        }
        R0 r0 = (R0) k(R0.class);
        String str = this.f243e;
        r0.getClass();
        if ("wall_lib".equals(str)) {
            r0.u("wall");
            return;
        }
        if ("wall".equals(str)) {
            r0.u("theme");
            return;
        }
        if (!this.f244f || (i2 = this.f247i) <= 0) {
            super.onBackPressed();
            return;
        }
        this.f247i = i2 - 1;
        c();
        e();
        g();
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Type inference failed for: r1v31, types: [java.lang.Object, h.R0, h.i0] */
    /* JADX WARN: Type inference failed for: r1v38, types: [java.lang.Object, h.C0, h.i0] */
    /* JADX WARN: Type inference failed for: r1v39, types: [h.L, java.lang.Object, h.i0] */
    @Override // android.app.Activity
    public final void onCreate(Bundle bundle) {
        boolean z;
        char c2;
        char c3;
        int i2;
        FrameLayout.LayoutParams layoutParams;
        int i3;
        String str;
        char c4;
        String str2;
        String str3 = "backup";
        super.onCreate(bundle);
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(0, R.anim.yecai_fade_in, R.anim.yecai_hold);
            overrideActivityTransition(1, R.anim.yecai_hold, R.anim.yecai_fade_out);
        }
        YecaiApp yecaiApp = YecaiApp.n;
        this.f239a = yecaiApp.f253b;
        this.f240b = yecaiApp.f252a;
        this.f241c = yecaiApp.f257f;
        k.f.i(this);
        this.w = j.n(this, this.f239a.d().v);
        k.f.a(e.e(this.f239a.d().w, this.w));
        this.f245g = this.f239a.d().w;
        this.f246h = this.f239a.d().h0;
        getWindow().setNavigationBarColor(k.f.f1088a);
        int round = Math.round(getResources().getConfiguration().screenWidthDp / k.f.r);
        this.f244f = round < 600 || k.f.n(this);
        this.f248j = getResources().getConfiguration().orientation == 1;
        float min = Math.min(1.35f, k.f.s);
        int round2 = Math.round((round < 900 ? 176 : 208) * min);
        int round3 = Math.round((round < 900 ? 148 : 172) * min);
        this.f249k = this.f244f ? 0 : Math.round((round < 900 ? 130 : 170) * min);
        String stringExtra = getIntent().getStringExtra("category");
        if (bundle != null) {
            this.f242d = bundle.getString("cat");
            this.f243e = bundle.getString("sub");
            this.f247i = bundle.getInt("level");
            z = bundle.getBoolean("diag");
        } else {
            z = false;
        }
        String str4 = "desktop";
        if (this.f242d == null) {
            if (stringExtra != null) {
                switch (stringExtra.hashCode()) {
                    case -934914674:
                        if (stringExtra.equals("recipe")) {
                            c4 = 0;
                            break;
                        }
                        c4 = 65535;
                        break;
                    case 94756405:
                        if (stringExtra.equals("cloud")) {
                            c4 = 1;
                            break;
                        }
                        c4 = 65535;
                        break;
                    case 1340337839:
                        if (stringExtra.equals("widgets")) {
                            c4 = 2;
                            break;
                        }
                        c4 = 65535;
                        break;
                    case 1559801053:
                        if (stringExtra.equals("devices")) {
                            c4 = 3;
                            break;
                        }
                        c4 = 65535;
                        break;
                    default:
                        c4 = 65535;
                        break;
                }
                switch (c4) {
                    case 0:
                    case 1:
                        str2 = "backup";
                        break;
                    case 2:
                        str2 = "weather";
                        break;
                    case 3:
                        str2 = "acct";
                        break;
                    default:
                        str2 = stringExtra;
                        break;
                }
                str = i(str2);
            } else {
                str = null;
            }
            if (str == null) {
                str = stringExtra != null ? stringExtra : (this.f241c.i() && this.f241c.m() && (!this.f241c.c() || this.f241c.k() > 7)) ? "desktop" : "account";
            }
            this.f242d = str;
            this.f247i = stringExtra != null ? 2 : 0;
        }
        String str5 = this.f242d;
        if (str5 != null) {
            switch (str5.hashCode()) {
                case -1396673086:
                    if (str5.equals("backup")) {
                        c2 = 0;
                        break;
                    }
                    c2 = 65535;
                    break;
                case -1253087691:
                    if (str5.equals("garden")) {
                        c2 = 1;
                        break;
                    }
                    c2 = 65535;
                    break;
                case -1109722326:
                    if (str5.equals("layout")) {
                        c2 = 2;
                        break;
                    }
                    c2 = 65535;
                    break;
                case -1081306052:
                    if (str5.equals("market")) {
                        c2 = 3;
                        break;
                    }
                    c2 = 65535;
                    break;
                case -934914674:
                    if (str5.equals("recipe")) {
                        c2 = 4;
                        break;
                    }
                    c2 = 65535;
                    break;
                case -787751952:
                    if (str5.equals("window")) {
                        c2 = 5;
                        break;
                    }
                    c2 = 65535;
                    break;
                case 3016191:
                    if (str5.equals("ball")) {
                        c2 = 6;
                        break;
                    }
                    c2 = 65535;
                    break;
                case 3088947:
                    if (str5.equals("dock")) {
                        c2 = 7;
                        break;
                    }
                    c2 = 65535;
                    break;
                case 92611469:
                    if (str5.equals("about")) {
                        c2 = '\b';
                        break;
                    }
                    c2 = 65535;
                    break;
                case 94756405:
                    if (str5.equals("cloud")) {
                        c2 = '\t';
                        break;
                    }
                    c2 = 65535;
                    break;
                case 110550847:
                    if (str5.equals("touch")) {
                        c2 = '\n';
                        break;
                    }
                    c2 = 65535;
                    break;
                default:
                    c2 = 65535;
                    break;
            }
            switch (c2) {
                case 0:
                case 4:
                case '\t':
                    str5 = "account";
                    break;
                case 1:
                case 2:
                case 6:
                case 7:
                    str5 = str4;
                    break;
                case 3:
                case '\b':
                    str5 = "system";
                    break;
                case 5:
                case '\n':
                    str4 = "advanced";
                    str5 = str4;
                    break;
                default:
                    String[] strArr = E;
                    int i4 = 0;
                    for (int i5 = 6; i4 < i5; i5 = 6) {
                        if (strArr[i4].equals(str5)) {
                            break;
                        } else {
                            i4++;
                        }
                    }
                    str5 = "account";
                    break;
            }
        } else {
            str5 = null;
        }
        this.f242d = str5;
        String str6 = this.f243e;
        if (str6 != null) {
            stringExtra = str6;
        }
        if (stringExtra != null) {
            switch (stringExtra.hashCode()) {
                case -934914674:
                    if (stringExtra.equals("recipe")) {
                        c3 = 0;
                        break;
                    }
                    c3 = 65535;
                    break;
                case 94756405:
                    if (stringExtra.equals("cloud")) {
                        c3 = 1;
                        break;
                    }
                    c3 = 65535;
                    break;
                case 1340337839:
                    if (stringExtra.equals("widgets")) {
                        c3 = 2;
                        break;
                    }
                    c3 = 65535;
                    break;
                case 1559801053:
                    if (stringExtra.equals("devices")) {
                        c3 = 3;
                        break;
                    }
                    c3 = 65535;
                    break;
                default:
                    c3 = 65535;
                    break;
            }
            switch (c3) {
                case 0:
                case 1:
                    break;
                case 2:
                    str3 = "weather";
                    break;
                case 3:
                    str3 = "acct";
                    break;
                default:
                    str3 = stringExtra;
                    break;
            }
        } else {
            str3 = null;
        }
        String[][] strArr2 = H;
        String[] strArr3 = strArr2[h(str5)];
        int length = strArr3.length;
        int i6 = 0;
        while (true) {
            if (i6 >= length) {
                String[] strArr4 = strArr2[h(str5)];
                i2 = 0;
                str3 = strArr4[0];
            } else if (strArr3[i6].equals(str3)) {
                i2 = 0;
            } else {
                i6++;
            }
        }
        this.f243e = str3;
        FrameLayout frameLayout = new FrameLayout(this);
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(i2);
        linearLayout.setBackgroundColor(k.f.f1088a);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(this.f244f ? k.f.f1088a : k.f.f1089b);
        scrollView.setVerticalScrollBarEnabled(false);
        LinearLayout linearLayout2 = new LinearLayout(this);
        this.f251m = linearLayout2;
        linearLayout2.setOrientation(1);
        int f2 = k.f.f(this, this.f244f ? 16.0f : 12.0f);
        this.f251m.setPadding(f2, k.f.f(this, 16.0f), f2, k.f.f(this, 24.0f));
        scrollView.addView(this.f251m, new FrameLayout.LayoutParams(-1, -2));
        this.f250l = scrollView;
        linearLayout.addView(scrollView, this.f244f ? new LinearLayout.LayoutParams(-1, -1) : new LinearLayout.LayoutParams(k.f.f(this, round2), -1));
        ScrollView scrollView2 = new ScrollView(this);
        scrollView2.setBackgroundColor(k.f.f1088a);
        scrollView2.setVerticalScrollBarEnabled(false);
        LinearLayout linearLayout3 = new LinearLayout(this);
        this.o = linearLayout3;
        linearLayout3.setOrientation(1);
        int f3 = k.f.f(this, this.f244f ? 16.0f : 10.0f);
        this.o.setPadding(f3, k.f.f(this, this.f244f ? 16.0f : 20.0f), f3, k.f.f(this, 24.0f));
        scrollView2.addView(this.o, new FrameLayout.LayoutParams(-1, -2));
        this.n = scrollView2;
        linearLayout.addView(scrollView2, this.f244f ? new LinearLayout.LayoutParams(-1, -1) : new LinearLayout.LayoutParams(k.f.f(this, round3), -1));
        this.t = new ScrollView(this);
        FrameLayout frameLayout2 = new FrameLayout(this);
        LinearLayout linearLayout4 = new LinearLayout(this);
        this.u = linearLayout4;
        linearLayout4.setOrientation(1);
        int f4 = k.f.f(this, this.f244f ? 16.0f : 28.0f);
        this.u.setPadding(f4, k.f.f(this, this.f244f ? 12.0f : 24.0f), f4, k.f.f(this, 56.0f));
        int i7 = round - (this.f244f ? 0 : round2 + round3);
        LinearLayout linearLayout5 = this.u;
        if (i7 > 980) {
            layoutParams = new FrameLayout.LayoutParams(k.f.f(this, 920.0f), -2, 1);
            i3 = -1;
        } else {
            i3 = -1;
            layoutParams = new FrameLayout.LayoutParams(-1, -2);
        }
        frameLayout2.addView(linearLayout5, layoutParams);
        this.t.addView(frameLayout2, new FrameLayout.LayoutParams(i3, -2));
        linearLayout.addView(this.t, this.f244f ? new LinearLayout.LayoutParams(i3, i3) : new LinearLayout.LayoutParams(0, i3, 1.0f));
        frameLayout.addView(linearLayout, new FrameLayout.LayoutParams(i3, i3));
        C0087n c0087n = new C0087n(this, this.f240b, this.f239a);
        this.v = c0087n;
        frameLayout.addView(c0087n, new FrameLayout.LayoutParams(i3, i3));
        setContentView(frameLayout);
        j.b(this, this.f239a.d().F);
        this.z = new C0061s0(this, this.u, this.f244f, this.f249k, this.w);
        this.A = new g(this, this.f241c);
        ArrayList arrayList = new ArrayList();
        arrayList.add(new AbstractC0042i0(this));
        arrayList.add(new AbstractC0042i0(this));
        arrayList.add(new E(this, 4));
        arrayList.add(new E(this, 1));
        arrayList.add(new E(this, 5));
        arrayList.add(new E(this, 2));
        arrayList.add(new E(this, 0));
        ?? abstractC0042i0 = new AbstractC0042i0(this);
        abstractC0042i0.t = "img";
        arrayList.add(abstractC0042i0);
        arrayList.add(new E(this, 6));
        arrayList.add(new E(this, 3));
        arrayList.add(new E(this, 8));
        arrayList.add(new AbstractC0042i0(this));
        arrayList.add(new E(this, 9));
        arrayList.add(new E(this, 7));
        ?? abstractC0042i02 = new AbstractC0042i0(this);
        abstractC0042i02.t = -1;
        arrayList.add(abstractC0042i02);
        ?? abstractC0042i03 = new AbstractC0042i0(this);
        abstractC0042i03.z = "zoom";
        arrayList.add(abstractC0042i03);
        arrayList.add(new AbstractC0042i0(this));
        arrayList.add(new C0038g0(this));
        arrayList.add(new C0033e(this));
        this.B = arrayList;
        Iterator it = arrayList.iterator();
        while (it.hasNext()) {
            AbstractC0042i0 abstractC0042i04 = (AbstractC0042i0) it.next();
            for (String str7 : abstractC0042i04.x()) {
                this.C.put(str7, abstractC0042i04);
            }
        }
        ((C0033e) k(C0033e.class)).s = z;
        ((CopyOnWriteArrayList) this.f239a.f285e).addIfAbsent(this);
        ((CopyOnWriteArrayList) this.f241c.f197e).addIfAbsent(this);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.PACKAGE_ADDED");
        intentFilter.addAction("android.intent.action.PACKAGE_REPLACED");
        intentFilter.addAction("android.intent.action.PACKAGE_REMOVED");
        intentFilter.addDataScheme("package");
        registerReceiver(this.D, intentFilter);
        c();
        e();
        g();
        d();
        if (j()) {
            this.A.h();
        }
    }

    @Override // android.app.Activity
    public final void onDestroy() {
        try {
            unregisterReceiver(this.D);
        } catch (IllegalArgumentException unused) {
        }
        ((CopyOnWriteArrayList) this.f239a.f285e).remove(this);
        ((CopyOnWriteArrayList) this.f241c.f197e).remove(this);
        Iterator it = this.B.iterator();
        while (it.hasNext()) {
            ((AbstractC0042i0) it.next()).n();
        }
        super.onDestroy();
    }

    @Override // android.app.Activity
    public final void onPause() {
        Iterator it = this.B.iterator();
        while (it.hasNext()) {
            ((AbstractC0042i0) it.next()).o();
        }
        super.onPause();
    }

    @Override // android.app.Activity
    public final void onResume() {
        super.onResume();
        if (this.x) {
            l();
        }
        this.x = true;
        Iterator it = this.B.iterator();
        while (it.hasNext()) {
            ((AbstractC0042i0) it.next()).p();
        }
    }

    @Override // android.app.Activity
    public final void onSaveInstanceState(Bundle bundle) {
        super.onSaveInstanceState(bundle);
        bundle.putString("cat", this.f242d);
        bundle.putString("sub", this.f243e);
        bundle.putInt("level", this.f247i);
        bundle.putBoolean("diag", ((C0033e) k(C0033e.class)).s);
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public final void onWindowFocusChanged(boolean z) {
        super.onWindowFocusChanged(z);
        if (z) {
            j.b(this, this.f239a.d().F);
        }
    }
}
