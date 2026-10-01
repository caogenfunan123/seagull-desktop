package com.seagull.carlauncher;

import android.view.Surface;

/**
 * 画布画面来源（批次 N 预留，给 DiPlay 铺路）。
 *
 * 界面层（PipBoard 的槽位）只认这个接口，不认画面从哪来：
 *   · VDDisplaySource —— 本地 VirtualDisplay 跑安卓应用（当前实现 = MirrorSlot）；
 *   · StreamDisplaySource —— iPhone 远端推流解码（DiPlay/Carlink，见 StreamCanvasSource）。
 * 这样以后「左 CarPlay 推流 + 右 高德 VD」的双画布混搭不用改布局和焦点逻辑。
 *
 * 只埋接口不接线：MirrorSlot 本来就实现了这四个方法，implements 上去零逻辑变化
 * （typecheck 兜底）。StreamCanvasSource 是空壳，调它就抛未实现。
 */
public interface CanvasSource {

    /** 虚拟屏 id；没建屏返回 -1。 */
    int displayId();

    /** 是否已就绪（建屏 + 搬应用成功；断 Surface 不影响）。 */
    boolean ready();

    /** 把 Surface 挂回画面源（VD 常驻、重进页面挂回同一块屏用）。 */
    boolean attachSurface(Surface surface, int w, int h, int dpi);

    /** 只断 Surface，不拆屏（拆屏 = 屏上任务倒回默认屏）。 */
    void detachSurface();
}
