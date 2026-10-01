package com.seagull.carlauncher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析 —— 纯 Java，不碰任何 Android 类，所以能直接在 JVM 上自检。
 * 时间戳与歌词行一一对应（times[i] 就是 lines[i] 的开始时间，单位毫秒）。
 */
public final class Lrc {

    private static final Pattern TIME =
            Pattern.compile("\\[(\\d+):(\\d{1,2})(?:[.:;](\\d{1,3}))?]");

    public final String[] lines;
    public final long[] times;

    /** 空歌词（「没在放歌 / 没查到」）共用一个，省得到处 new。 */
    public static final Lrc EMPTY = new Lrc(new String[0], new long[0]);

    public Lrc(String[] lines, long[] times) {
        this.lines = lines;
        this.times = times;
    }

    public static Lrc parse(String lrc) {
        List<String> out = new ArrayList<>();
        List<Long> ts = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[ti:") || line.startsWith("[ar:") || line.startsWith("[al:")
                    || line.startsWith("[by:") || line.startsWith("[offset:")) continue;
            List<Long> cur = new ArrayList<>();
            int end = 0;
            Matcher m = TIME.matcher(line);
            while (m.find() && m.start() == end) {      // 只认行首连续的时间戳
                long ms = Long.parseLong(m.group(1)) * 60000
                        + Long.parseLong(m.group(2)) * 1000
                        + frac(m.group(3));
                cur.add(ms);
                end = m.end();
            }
            if (cur.isEmpty()) continue;
            String text = line.substring(end).trim();
            if (text.isEmpty()) continue;
            for (long t : cur) { ts.add(t); out.add(text); }   // 一行多个时间戳 = 副歌重复
        }
        Integer[] idx = new Integer[out.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Long.compare(ts.get(a), ts.get(b)));
        String[] ls = new String[idx.length];
        long[] st = new long[idx.length];
        for (int i = 0; i < idx.length; i++) {
            ls[i] = out.get(idx[i]);
            st[i] = ts.get(idx[i]);
        }
        return new Lrc(ls, st);
    }

    /** [mm:ss.xx] 的小数部分：xx=厘、x=毫、xxx=毫。 */
    private static long frac(String f) {
        if (f == null || f.isEmpty()) return 0L;
        if (f.length() == 1) return Long.parseLong(f) * 100L;
        if (f.length() == 2) return Long.parseLong(f) * 10L;
        return Long.parseLong(f.substring(0, 3));
    }

    /** 当前该显示的行号（-1 = 还没到第一行）。 */
    public static int indexAt(long[] times, long posMs) {
        int i = -1;
        for (int k = 0; k < times.length; k++) {
            if (times[k] <= posMs) i = k; else break;
        }
        return i;
    }
}
