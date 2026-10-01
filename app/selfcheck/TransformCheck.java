package com.seagull.carlauncher;

/**
 * 自检：TouchTransformer 的恒等 / 等比缩放 / 零保护 / 画布→源屏映射方向。
 * VD 模式（identity）scale 必须恒为 1 —— 这是批次 N"埋接口不改行为"的兜底。
 *
 * 跑法（纯 JVM，不需要 android.jar）：
 *   javac -d /tmp/trsc app/src/com/seagull/carlauncher/TouchTransformer.java \
 *       app/selfcheck/TransformCheck.java
 *   java -ea -cp /tmp/trsc com.seagull.carlauncher.TransformCheck
 */
public class TransformCheck {

    private static int n = 0;

    public static void main(String[] args) {
        // 1) 恒等：VD 模式 scale 恒 1（两轴都是）
        TouchTransformer id = TouchTransformer.identity();
        eq(id.scaleX(), 1f, "identity scaleX");
        eq(id.scaleY(), 1f, "identity scaleY");
        check(id.describe().contains("identity"), "identity describe: " + id.describe());

        // 2) 缩小映射：960x540 的推流进 480x270 的画布，画布点要 ×2 才是推流像素
        TouchTransformer sc = TouchTransformer.scaling(960, 540, 480, 270);
        eq(sc.scaleX(), 2f, "scaling scaleX 960→480");
        eq(sc.scaleY(), 2f, "scaling scaleY 540→270");

        // 3) 非等比：1920x720 的推流进 366x348 的画布（两轴系数必须不同）
        TouchTransformer wide = TouchTransformer.scaling(1920, 720, 366, 348);
        eq(wide.scaleX(), 1920f / 366f, "wide scaleX");
        eq(wide.scaleY(), 720f / 348f, "wide scaleY");
        check(Math.abs(wide.scaleX() - wide.scaleY()) > 0.1f, "wide 非等比：x/y 系数应有明显差");

        // 3b) 【批次 P 补】映射方向往返断言：画布中心按系数换算必须是源画面中心。
        // 这条直接钉死"画布→源"的方向，写反（dst/src）会在这里 X 差 5 倍。
        eq(wide.scaleX() * (366f / 2f), 960f, "画布中心 x → 推流像素 (960)");
        eq(wide.scaleY() * (348f / 2f), 360f, "画布中心 y → 推流像素 (360)");
        eq(sc.scaleX() * 240f, 480f, "画布中点 x → 推流像素 (480)");
        eq(sc.scaleY() * 135f, 270f, "画布中点 y → 推流像素 (270)");

        // 3c) 放大场景：画布比源大，系数 >1
        TouchTransformer up = TouchTransformer.scaling(480, 270, 960, 540);
        eq(up.scaleX(), 0.5f, "放大场景 scaleX 480→960");
        eq(up.scaleY(), 0.5f, "放大场景 scaleY 270→540");

        // 4) 零保护：任一边 ≤0 回落 1，配置错了也不能把触摸打偏
        TouchTransformer badW = TouchTransformer.scaling(0, 720, 366, 348);
        eq(badW.scaleX(), 1f, "srcW=0 保护 scaleX");
        eq(badW.scaleY(), 720f / 348f, "srcW=0 不影响 scaleY");
        TouchTransformer badH = TouchTransformer.scaling(960, 0, 480, 270);
        eq(badH.scaleX(), 960f / 480f, "srcH=0 不影响 scaleX");
        eq(badH.scaleY(), 1f, "srcH=0 保护 scaleY");
        TouchTransformer badDst = TouchTransformer.scaling(960, 540, 0, 0);
        eq(badDst.scaleX(), 1f, "dstW=0 保护 scaleX");
        eq(badDst.scaleY(), 1f, "dstH=0 保护 scaleY");
        // 负数 dst（画布量错的场景）同样回落
        TouchTransformer negDst = TouchTransformer.scaling(960, 540, -366, -348);
        eq(negDst.scaleX(), 1f, "dst 负数保护 scaleX");
        eq(negDst.scaleY(), 1f, "dst 负数保护 scaleY");

        // 5) 负数源（旋转量错的场景）同样回落
        TouchTransformer neg = TouchTransformer.scaling(-960, -540, 366, 348);
        eq(neg.scaleX(), 1f, "负数源保护 scaleX");
        eq(neg.scaleY(), 1f, "负数源保护 scaleY");

        // 6) src==dst 得到 1:1（含 describe）
        TouchTransformer same = TouchTransformer.scaling(366, 348, 366, 348);
        eq(same.scaleX(), 1f, "src==dst scaleX");
        eq(same.scaleY(), 1f, "src==dst scaleY");

        // 7) 极端尺寸不出 Infinity/NaN
        TouchTransformer tiny = TouchTransformer.scaling(1, 1, Integer.MAX_VALUE, Integer.MAX_VALUE);
        check(Float.isFinite(tiny.scaleX()) && Float.isFinite(tiny.scaleY()), "极端尺寸系数有限");
        TouchTransformer huge = TouchTransformer.scaling(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1);
        check(Float.isFinite(huge.scaleX()) && Float.isFinite(huge.scaleY()), "极端尺寸系数有限(放大)");

        // 8) describe 必须带尺寸与方向，排障时一眼看出映射是"画布→源"
        check(wide.describe().contains("1920x720") && wide.describe().contains("366x348"),
                "wide describe: " + wide.describe());
        check(badW.describe().contains("0x720"), "badW describe: " + badW.describe());

        System.out.println("TransformCheck 全部通过（" + n + " 项）");
    }

    /** 相对容差：期望值极小（1/Integer.MAX_VALUE 量级）时绝对容差会形同恒真。 */
    private static void eq(float got, float want, String what) {
        n++;
        float tol = 1e-4f * Math.max(1f, Math.abs(want));
        check(Math.abs(got - want) <= tol && Float.isFinite(got), what + " 应=" + want + " 实=" + got);
    }

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError("失败: " + what);
        System.out.println("  OK " + what);
    }
}
