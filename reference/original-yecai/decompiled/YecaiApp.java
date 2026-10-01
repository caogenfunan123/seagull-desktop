package cn.wayecai.launcher;

import a.Q;
import a.RunnableC0004e;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Process;
import android.os.UserManager;
import c.g;
import cn.wayecai.launcher.YecaiApp;
import cn.wayecai.launcher.system.BallService;
import cn.wayecai.launcher.system.LyricNotificationListener;
import d.a;
import d.b;
import j.AbstractC0100b;
import j.AbstractC0108j;
import j.B;
import j.C0105g;
import j.G;
import j.S;
import j.r;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import k.f;
import l.e;
import m.i;
import m.k;

/* loaded from: classes.dex */
public class YecaiApp extends Application {
    public static YecaiApp n;

    /* renamed from: a, reason: collision with root package name */
    public C0105g f252a;

    /* renamed from: b, reason: collision with root package name */
    public b f253b;

    /* renamed from: c, reason: collision with root package name */
    public i f254c;

    /* renamed from: d, reason: collision with root package name */
    public G f255d;

    /* renamed from: e, reason: collision with root package name */
    public B f256e;

    /* renamed from: f, reason: collision with root package name */
    public g f257f;

    /* renamed from: g, reason: collision with root package name */
    public S f258g;

    /* renamed from: h, reason: collision with root package name */
    public int f259h;

    /* renamed from: i, reason: collision with root package name */
    public boolean f260i;

    /* renamed from: j, reason: collision with root package name */
    public final ArrayList f261j = new ArrayList();

    /* renamed from: k, reason: collision with root package name */
    public Q f262k;

    /* renamed from: l, reason: collision with root package name */
    public boolean f263l;

    /* renamed from: m, reason: collision with root package name */
    public boolean f264m;

    public final void a(d.g gVar) {
        i iVar = this.f254c;
        int i2 = gVar.s;
        float f2 = gVar.t;
        long j2 = gVar.u;
        iVar.f1275l = i2;
        iVar.f1276m = f2;
        iVar.n = j2;
        Iterator it = iVar.f1266c.values().iterator();
        while (it.hasNext()) {
            ((k) it.next()).h(i2, f2, j2);
        }
        i iVar2 = this.f254c;
        LinkedHashMap linkedHashMap = gVar.p;
        int i3 = gVar.q;
        iVar2.getClass();
        iVar2.o = new HashMap(linkedHashMap);
        iVar2.p = i3;
        for (k kVar : iVar2.f1266c.values()) {
            Integer num = (Integer) iVar2.o.get(kVar.s());
            kVar.o(num != null ? num.intValue() : iVar2.p);
        }
        this.f254c.getClass();
        int i4 = f.f1088a;
        f.s = Math.max(0.85f, Math.min(1.6f, gVar.h0 / 100.0f));
        l.b.setGlobalScale(gVar.c0 / 100.0f);
        BallService.a(this, gVar);
        int i5 = gVar.p0;
        if (i5 != 1 && i5 != 2 && i5 != 3) {
            i5 = AbstractC0108j.f(this);
        }
        this.f259h = i5;
        e.setTickMs(i5 == 1 ? 1000L : 500L);
        l.b.setStepEnabled(this.f259h != 1);
    }

    public final void b() {
        if (this.f260i) {
            return;
        }
        Q q = this.f262k;
        if (q != null) {
            try {
                unregisterReceiver(q);
            } catch (IllegalArgumentException unused) {
            }
            this.f262k = null;
        }
        C0105g c0105g = new C0105g(this);
        this.f252a = c0105g;
        this.f253b = new b(this, c0105g);
        this.f254c = new i(this, this.f252a);
        this.f255d = new G(this);
        this.f256e = new B(this, this.f255d, new r(this));
        this.f257f = new g(this);
        this.f258g = new S(this);
        if (Process.myUid() == 1000) {
            LyricNotificationListener.a(this);
        }
        this.f254c.i(this.f257f.j());
        g gVar = this.f257f;
        ((CopyOnWriteArrayList) gVar.f197e).addIfAbsent(new c.f() { // from class: a.b0
            @Override // c.f
            public final void a() {
                YecaiApp yecaiApp = YecaiApp.this;
                yecaiApp.f254c.i(yecaiApp.f257f.j());
            }
        });
        a(this.f253b.d());
        b bVar = this.f253b;
        ((CopyOnWriteArrayList) bVar.f285e).addIfAbsent(new a() { // from class: a.c0
            @Override // d.a
            public final void b(d.g gVar2) {
                YecaiApp yecaiApp = YecaiApp.n;
                YecaiApp.this.a(gVar2);
            }
        });
        this.f260i = true;
        RunnableC0004e runnableC0004e = AbstractC0100b.f965a;
        registerReceiver(new BroadcastReceiver(), new IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND"));
        ArrayList arrayList = this.f261j;
        ArrayList arrayList2 = new ArrayList(arrayList);
        arrayList.clear();
        Iterator it = arrayList2.iterator();
        while (it.hasNext()) {
            ((Runnable) it.next()).run();
        }
    }

    @Override // android.app.Application
    public final void onCreate() {
        super.onCreate();
        n = this;
        f.i(this);
        UserManager userManager = (UserManager) getSystemService(UserManager.class);
        if (userManager == null || userManager.isUserUnlocked()) {
            b();
            return;
        }
        Q q = new Q(this, 1);
        this.f262k = q;
        registerReceiver(q, new IntentFilter("android.intent.action.USER_UNLOCKED"));
        if (userManager.isUserUnlocked()) {
            b();
        }
    }
}
