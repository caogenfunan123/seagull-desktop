package com.seagull.carlauncher;

/**
 * 触摸坐标映射（批次 N 预留，给 DiPlay 铺路）。
 *
 * 为什么要抽出来：VD 画布按实际像素 1:1 建屏，画布坐标 = 应用坐标，缩放恒为 1；
 * DiPlay 推流模式画布物理像素和 iPhone 屏幕分辨率不一致（一块 366×348dp 的画布
 * 承载 1920×720 的推流），必须按比例缩放后再回传给手机。
 *
 * 抽象成纯函数接口，主链路（TouchForward.setDisplay 的 scale 形参）一行不动：
 *   · identity() —— VD 模式（当前默认），scale 恒 1；
 *   · scaling(srcW, srcH, dstW, dstH) —— 推流模式预留，dst/src 且带 0 保护。
 *
 * 自检在 app/selfcheck/TransformCheck.java（纯 JVM）。
 */
public interface TouchTransformer {

    /** 画布 x → 目标屏 x 的缩放系数。 */
    float scaleX();

    /** 画布 y → 目标屏 y 的缩放系数。 */
    float scaleY();

    /** 给人看的描述（进 logcat 排障）。 */
    String describe();

    static TouchTransformer identity() {
        return new TouchTransformer() {
            @Override public float scaleX() { return 1f; }
            @Override public float scaleY() { return 1f; }
            @Override public String describe() { return "identity(1:1)"; }
        };
    }

    /**
     * 等比缩放：画布像素 (dstW×dstH) 承载源画面 (srcW×srcH)。
     * 任一边 ≤0 回落 1（防除零，也防止配置错把触摸打偏）。
     */
    static TouchTransformer scaling(int srcW, int srcH, int dstW, int dstH) {
        final float sx = (srcW > 0 && dstW > 0) ? (float) dstW / srcW : 1f;
        final float sy = (srcH > 0 && dstH > 0) ? (float) dstH / srcH : 1f;
        return new TouchTransformer() {
            @Override public float scaleX() { return sx; }
            @Override public float scaleY() { return sy; }
            @Override public String describe() {
                return "scaling(" + srcW + "x" + srcH + " -> " + dstW + "x" + dstH
                        + " = " + sx + "/" + sy + ")";
            }
        };
    }
}
