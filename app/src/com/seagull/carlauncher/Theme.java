package com.seagull.carlauncher;

/**
 * 配色表：14 套内置主题 + 自定义主题色。
 * 对齐野菜桌面原文案（逐字）：叶影 / 叶绿 / 林间 / 晨雾 / 田垄 / 极简白 / 樱花粉 /
 * 天空蓝 / 活力橙 / 珊瑚红 / 薄荷青 / 薰衣紫 / 香槟金 / 高级灰。
 *
 * P0-2 阶段只提供「选择 + 预览色块 + 持久化」，全局换肤在 P0-7 接入。
 */
public final class Theme {

    public static final String DEFAULT_ID = "leaf_shadow";

    public static final String[] IDS = {
            "leaf_shadow", "leaf_green", "forest", "morning_mist", "field_ridge",
            "minimal_white", "sakura_pink", "sky_blue", "vivid_orange", "coral_red",
            "mint_green", "lavender_purple", "champagne_gold", "premium_gray"
    };

    public static final String[] NAMES = {
            "叶影", "叶绿", "林间", "晨雾", "田垄",
            "极简白", "樱花粉", "天空蓝", "活力橙", "珊瑚红",
            "薄荷青", "薰衣紫", "香槟金", "高级灰"
    };

    /** 每套主题的强调色（深色底上的主色调）。 */
    public static final int[] ACCENTS = {
            0xFF8CC26A, 0xFF4CAF50, 0xFF388E3C, 0xFF90A4AE, 0xFFA1887F,
            0xFFE0E0E0, 0xFFF48FB1, 0xFF64B5F6, 0xFFFFA726, 0xFFFF7043,
            0xFF4DB6AC, 0xFFB39DDB, 0xFFD4B483, 0xFF9E9E9E
    };

    private Theme() {}

    public static String nameOf(String id) {
        if (id == null || id.isEmpty()) return NAMES[0];
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return NAMES[i];
        return "自定义";
    }

    public static int accentOf(String id) {
        if (id != null) for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return ACCENTS[i];
        return ACCENTS[0];
    }

    /** 当前生效的强调色：自定义色合法就用自定义色，否则用主题色，再兜底默认。 */
    public static int accent(LauncherModel m) {
        if (m == null) return ACCENTS[0];
        if (m.customAccent != null && m.customAccent.length() == 7 && m.customAccent.startsWith("#")) {
            try { return (0xFF000000 | (int) (Long.parseLong(m.customAccent.substring(1), 16))); }
            catch (Throwable ignore) {}
        }
        return accentOf(m.themeId);
    }
}
