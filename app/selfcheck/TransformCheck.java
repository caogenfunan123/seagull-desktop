package com.seagull.carlauncher;

/**
 * 自检：TouchTransformer 的恒等 / 等比缩放 / 零保护。
 * VD 模式（identity）scale 必须恒为 1 —— 这是批次 N"埋接口不改行为"的兜底。
 *
 * 跑法（纯 JVM，不需要 android.jar）：
 *   javac -d /tmp/trsc app/src/com/seagull/carlauncher/TouchTransformer.java \
 *       app/selfcheck/TransformCheck.java
 *   java -ea -cp /tmp/trsc com.seagull.carlauncher.TransformCheck
 */
public class TransformCheck {

    public static void main(String[] args) {
        // 1) 恒等：VD 模式 scale 恒 1（两轴都是）
        TouchTransformer id = TouchTransformer.identity();
        eq(id.scaleX(), 1f, "identity scaleX");
        eq(id.scaleY(), 1f, "identity scaleY");
        check(id.describe().contains("identity"), "identity describe: " + id.describe());

        // 2) 等比缩放：960x540 的推流进 480x270 的画布 = 0.5
        TouchTransformer sc = TouchTransformer.scaling(960, 540, 480, 270);
        eq(sc.scaleX(), 0.5f, "scaling scaleX");
        eq(sc.scaleY(), 0.5f, "scaling scaleY");

        // 3) 非等比：1920x720 的推流进 366x348 的画布
        TouchTransformer wide = TouchTransformer.scaling(1920, 720, 366, 348);
        eq(wide.scaleX(), 366f / 1920f, "wide scaleX");
        eq(wide.scaleY(), 348f / 720f, "wide scaleY");

        // 4) 零保护：任一边 ≤0 回落 1，配置错了也不能把触摸打偏
        TouchTransformer badW = TouchTransformer.scaling(0, 720, 366, 348);
        eq(badW.scaleX(), 1f, "srcW=0 保护 scaleX");
        eq(badW.scaleY(), 348f / 720f, "srcW=0 不影响 scaleY");
        TouchTransformer badH = TouchTransformer.scaling(960, 0, 480, 270);
        eq(badH.scaleX(), 0.5f, "srcH=0 不影响 scaleX");
        eq(badH.scaleY(), 1f, "srcH=0 保护 scaleY");
        TouchTransformer badDst = TouchTransformer.scaling(960, 540, 0, 0);
        eq(badDst.scaleX(), 1f, "dstW=0 保护 scaleX");
        eq(badDst.scaleY(), 1f, "dstH=0 保护 scaleY");

        // 5) 负数边（旋转量错的场景）同样回落
        TouchTransformer neg = TouchTransformer.scaling(-960, -540, 366, 348);
        eq(neg.scaleX(), 1f, "负数源保护 scaleX");
        eq(neg.scaleY(), 1f, "负数源保护 scaleY");

        // 6) describe 必须带尺寸，排障时一眼看出映射方向
        check(wide.describe().contains("1920x720") && wide.describe().contains("366x348"),
                "wide describe: " + wide.describe());

        System.out.println("TransformCheck 全部通过");
    }

    private static void eq(float got, float want, String what) {
        check(Math.abs(got - want) < 1e-4f, what + " 应=" + want + " 实=" + got);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("失败: " + what);
        System.out.println("  OK " + what);
    }
}
