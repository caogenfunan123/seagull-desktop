package com.seagull.carlauncher;

import android.view.Surface;

/**
 * 远端推流画面源占位（批次 N 预留，DiPlay/Carlink 下一批实现）。
 *
 * iPhone 侧渲染界面、H.264/H.265 推流到车机：本类的职责是把解码帧写进
 * Surface，并把触摸坐标经 {@link TouchTransformer#scaling} 缩放后回传给手机。
 * 现在全部方法抛未实现 —— 只占位，让 CanvasSource 的抽象在编译器里活着，
 * 不给未验证的代码留执行路径。
 */
public final class StreamCanvasSource implements CanvasSource {

    private final String name;

    public StreamCanvasSource(String name) { this.name = name; }

    @Override public int displayId() { throw new UnsupportedOperationException("StreamCanvasSource 未实现: " + name); }

    @Override public boolean ready() { throw new UnsupportedOperationException("StreamCanvasSource 未实现: " + name); }

    @Override public boolean attachSurface(Surface surface, int w, int h, int dpi) {
        throw new UnsupportedOperationException("StreamCanvasSource 未实现: " + name);
    }

    @Override public void detachSurface() { throw new UnsupportedOperationException("StreamCanvasSource 未实现: " + name); }
}
