package com.seagull.carlauncher;

import android.icu.text.Transliterator;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 应用名 → 拼音首字母（搜索用）。
 *
 * 走平台自带的 ICU 转换器（API 24+ 公开 API），不引第三方拼音库、不内置两万字词典。
 * ponytail: 首字母方案只做全拼首字母（"微信"→wx、"WiFi"→wifi），
 *           需要多音字分词排序时再换 Pinyin4j 之类的词典库。
 */
public final class Pinyin {

    private static final Map<String, String> CACHE = new HashMap<>();
    private static Transliterator tr;

    private Pinyin() {}

    /** 拼音首字母，小写。汉字走 Han-Latin 转换，英文字母直接取首字母。 */
    public static synchronized String initials(String label) {
        if (label == null || label.isEmpty()) return "";
        String hit = CACHE.get(label);
        if (hit != null) return hit;
        String py;
        try {
            if (tr == null) tr = Transliterator.getInstance("Han-Latin");
            py = tr.transliterate(label);
        } catch (Throwable t) {
            py = label;
        }
        StringBuilder sb = new StringBuilder();
        boolean prevLetter = false;
        for (int i = 0; i < py.length(); i++) {
            char c = py.charAt(i);
            if (Character.isLetter(c)) {
                if (!prevLetter) sb.append(Character.toLowerCase(c));
                prevLetter = true;
            } else {
                prevLetter = false;
            }
        }
        String out = sb.toString().toLowerCase(Locale.ROOT);
        if (CACHE.size() > 400) CACHE.clear();
        CACHE.put(label, out);
        return out;
    }

    /** 模糊匹配：原文包含查询，或拼音首字母包含查询。 */
    public static boolean match(String label, String pkg, String lowerQuery) {
        if (label != null && label.toLowerCase(Locale.ROOT).contains(lowerQuery)) return true;
        if (pkg != null && pkg.toLowerCase(Locale.ROOT).contains(lowerQuery)) return true;
        return initials(label).contains(lowerQuery);
    }
}
