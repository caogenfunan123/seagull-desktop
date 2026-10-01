package com.seagull.carlauncher;

import android.hardware.display.DisplayManager;

/**
 * 自检：TrustedFlags 的 flag 规范化、候选降级与 TRUSTED 位校验。
 * PUBLIC 强制带 OWN_CONTENT_ONLY、剥 INSECURE_KEYGUARD/SECURE 这几条是
 * "屏建得起来但任务被系统拉走"的根因，逐组钉死。
 *
 * 跑法（需要 android.jar，DisplayManager 常量来自 SDK）：
 *   javac -cp /tmp/opencode/android-sdk/platforms/android-33.jar -d /tmp/tfsc \
 *       app/src/com/seagull/carlauncher/TrustedFlags.java app/selfcheck/TrustedFlagsCheck.java
 *   java -ea -cp /tmp/tfsc com.seagull.carlauncher.TrustedFlagsCheck
 */
public class TrustedFlagsCheck {

    private static final int PUB = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static final int OWN = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
    private static final int PRESENT = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
    private static final int SECURE = DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
    private static final int TRUSTED = TrustedFlags.FLAG_TRUSTED;
    private static final int IME_KEYGUARD = TrustedFlags.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;

    public static void main(String[] args) {
        int sdk = 34;

        // 1) SDK≥30 一律带 TRUSTED；更低剥掉
        int n = TrustedFlags.normalize(PUB | OWN | PRESENT, sdk);
        check((n & TRUSTED) != 0, "A14 保留 TRUSTED");
        int old = TrustedFlags.normalize(PUB | OWN | PRESENT, 29);
        check((old & TRUSTED) == 0, "A29 剥 TRUSTED");
        check(!TrustedFlags.supportsTrusted(29) && TrustedFlags.supportsTrusted(30), "30 是分界");

        // 2) PUBLIC 强制 OWN_CONTENT_ONLY（防空屏镜像默认屏）；INSECURE_KEYGUARD/SECURE 必剥
        int pubOnly = TrustedFlags.normalize(PUB, sdk);
        check((pubOnly & OWN) != 0, "PUBLIC 强制带 OWN_CONTENT_ONLY");
        int messy = TrustedFlags.normalize(PUB | IME_KEYGUARD | SECURE, sdk);
        check((messy & IME_KEYGUARD) == 0, "剥 INSECURE_KEYGUARD（A14 拒组合）");
        check((messy & SECURE) == 0, "永远剥 SECURE");

        // 3) 5 组候选：全部含 PUBLIC|OWN_CONTENT_ONLY|PRESENTATION 且带 TRUSTED，从全到简递减
        int[] cands = TrustedFlags.candidateFlags();
        check(cands.length == 5, "5 组候选");
        int prevBits = Integer.MAX_VALUE;
        for (int raw : cands) {
            int f = TrustedFlags.normalize(raw, sdk);
            check((f & PUB) != 0 && (f & OWN) != 0 && (f & PRESENT) != 0, "候选含 PUBLIC|OWN|PRESENT");
            check((f & TRUSTED) != 0, "候选带 TRUSTED");
            check((f & SECURE) == 0, "候选剥 SECURE");
            int bits = Integer.bitCount(f & ~TRUSTED);
            check(bits <= prevBits, "候选位从多到少递减");
            prevBits = bits;
        }
        // 首组最全（8 个公开/隐藏位 + TRUSTED），末组最小（三个公开位 + TRUSTED）
        int first = TrustedFlags.normalize(cands[0], sdk);
        int last = TrustedFlags.normalize(cands[4], sdk);
        check((first & TrustedFlags.FLAG_SUPPORTS_TOUCH) != 0
                && (first & TrustedFlags.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS) != 0
                && (first & TrustedFlags.FLAG_OWN_FOCUS) != 0
                && (first & TrustedFlags.FLAG_ROTATES_WITH_CONTENT) != 0, "首组最全");
        check(last == (PUB | OWN | PRESENT | TRUSTED), "末组最小 = PUBLIC|OWN|PRESENT|TRUSTED");

        // 4) hasTrusted：Display 侧 TRUSTED=1<<7；=-1 视为失败
        check(TrustedFlags.hasTrusted(1 << 7), "带 1<<7 判受信");
        check(!TrustedFlags.hasTrusted(0), "flags=0 非受信");
        check(!TrustedFlags.hasTrusted(-1), "读不到 flags 判失败");

        // 5) 兜底组合 = PUBLIC|OWN_CONTENT_ONLY|PRESENTATION（无 TRUSTED）
        int fb = TrustedFlags.projectionFallbackFlags();
        check(fb == (PUB | OWN | PRESENT) && (fb & TRUSTED) == 0, "兜底组合不含 TRUSTED");

        System.out.println("TrustedFlagsCheck OK (" + 10 + " 项)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("失败：" + what);
    }
}
