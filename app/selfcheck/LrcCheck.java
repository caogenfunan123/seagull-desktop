package com.seagull.carlauncher;

/**
 * 自检：LRC 解析（Lrc.parse / Lrc.indexAt）。
 * 歌词时间戳是唯一「算错一行就整首跑偏」的地方，所以单测钉住。
 *
 * 跑法（不依赖设备，纯 JVM）：
 *   javac -d /tmp/lrcsc app/src/com/seagull/carlauncher/Lrc.java app/selfcheck/LrcCheck.java
 *   java  -ea -cp /tmp/lrcsc com.seagull.carlauncher.LrcCheck
 */
public class LrcCheck {

    public static void main(String[] args) {
        // 1) 常规：秒 + 两位毫
        Lrc a = Lrc.parse("[00:00.00]第一句\n[00:12.34]第二句\n");
        check(a.lines.length == 2, "两行");
        check(a.times[0] == 0 && a.times[1] == 12340, "时间戳 0 / 12340");

        // 2) 分号 + 一位毫 + 冒号小数都认
        Lrc b = Lrc.parse("[00:05;1]甲\n[00:06:250]乙\n[01:00]丙\n");
        check(b.times[0] == 5100, "分号小数 5100 -> " + b.times[0]);
        check(b.times[1] == 6250, "冒号三位毫 6250 -> " + b.times[1]);
        check(b.times[2] == 60000, "整分钟 60000");

        // 3) 元信息行丢掉，行内方括号留着
        Lrc c = Lrc.parse("[ti:歌名]\n[ar:歌手]\n[00:01.00]我 [ chorus ]\n");
        check(c.lines.length == 1 && c.lines[0].equals("我 [ chorus ]"), "元信息与行内方括号");

        // 4) 一行两个时间戳 = 副歌重复，两行同文
        Lrc d = Lrc.parse("[00:30.00][01:30.00]副歌\n");
        check(d.lines.length == 2 && d.lines[0].equals("副歌") && d.lines[1].equals("副歌"), "副歌重复");

        // 5) 乱序要按时间排
        Lrc e = Lrc.parse("[00:20.00]晚\n[00:10.00]早\n");
        check(e.lines[0].equals("早") && e.lines[1].equals("晚"), "乱序排序");

        // 6) 文本行（没有时间戳）不进歌词
        check(Lrc.parse("纯文本\n").lines.length == 0, "无时间戳丢弃");

        // 7) 播放位置落在两句之间
        Lrc f = Lrc.parse("[00:10.00]甲\n[00:20.00]乙\n[00:30.00]丙\n");
        check(Lrc.indexAt(f.times, 0) == -1, "开头前无行");
        check(Lrc.indexAt(f.times, 9999) == -1, "第一句前仍无行");
        check(Lrc.indexAt(f.times, 10000) == 0, "正好卡在第一句");
        check(Lrc.indexAt(f.times, 25000) == 1, "两句之间取上一句");
        check(Lrc.indexAt(f.times, 999999) == 2, "超出末尾取最后一句");

        System.out.println("LrcCheck OK (" + 15 + " 项)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("失败：" + what);
    }
}
