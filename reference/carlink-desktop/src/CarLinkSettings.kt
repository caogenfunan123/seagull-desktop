package com.carlink.desktop.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.carlink.desktop.overlay.CarPipTheme

/**
 * 桌面布局与外观的用户可配置项（0.2-m3 升级为 v2，对齐车联助手 X 的设置项命名）。
 *
 * 关键改动：存储文件从自定义 "carlink_desktop" 改为
 * PreferenceManager.getDefaultSharedPreferences（即 "<包名>_preferences"）。
 * 原因：设置页 0.2-m3 起改用 androidx.preference 多屏，PreferenceFragment 默认写这个文件；
 * 若两边文件不一致，就会出现"设置页改了、桌面读不到"的空壳 bug —— 这正是用户批评的根因之一。
 * 统一到默认文件后，桌面侧读、设置侧写，天然同一份。
 *
 * 权重是【相对值】，不做归一化存储；理由见旧版注释（避免 33/33/34 抖动）。
 * 键名对齐车联助手实测提取的 key（areaA/weightA/mirror_app_package…），
 * 便于将来直接读它的配置习惯，也方便用户从车联助手迁移心智。
 */
object CarLinkSettings {

    // ---- 区域内容枚举（对齐车联助手"A/B/C 区域内容"列表）----
    const val A_NONE = 0
    const val A_MIRROR = 1        // 接管槽1：A 区锚点显示画中画1（pipPackage）
    const val A_PIP2 = 2          // 已废弃旧值：曾把画中画2也塞 A 区。读到时按关闭处理，不再使用（归一化见 normalizeRegion）
    const val A_NAVI = 3          // 导航卡放大到 A 区
    const val A_MUSIC = 4         // 音乐卡放大到 A 区
    const val B_NONE = 0
    const val B_NAVI = 1
    const val B_MUSIC = 2
    const val B_MIRROR = 3        // 接管槽2：B 区锚点显示画中画2（pipPackage2）
    const val C_NONE = 0
    const val C_NAVI = 1
    const val C_MUSIC = 2
    const val C_MIRROR = 3

    const val DEFAULT_PIPIP_PKG = "com.autonavi.amapauto"
    const val DEFAULT_MUSIC_PKG = ""

    /** 全部字符串键，供其它文件引用，避免散落字面量。 */
    object Keys {
        const val WEIGHT_A = "weightA"
        const val WEIGHT_B = "weightB"
        const val WEIGHT_C = "weightC"
        const val WEIGHT_RIGHT = "weightRight"
        const val AREA_A = "areaA"
        const val AREA_B = "areaB"
        const val AREA_C = "areaC"
        const val MIRROR_PKG = "mirror_app_package"
        const val MIRROR_PKG2 = "mirror_app_package2"
        const val MUSIC_PKG = "music_pkg"
        const val UI_MODE = "ui_mode"
        const val CUSTOM_BG = "cusbackground"
        const val CARD_OPACITY = "cardopacity"
        const val RADIUS = "radius"
        const val MARGIN = "margin"
        const val ACCENT = "accent"
        const val MINIMAL = "minimal"
        const val DIAG = "diag_on_desktop"
        const val LOCK_MUSIC = "lock_music_player"
        const val LOCK_MUSIC_PKG = "lock_music_player_pkg"
        const val SHOW_ALBUM = "showalbum"
        const val MUSIC_COMPAT = "switch_preference_1"
        const val NOTIF_SECONDS = "notification_display_seconds"
        const val NOTIF_WHITELIST = "notification_whitelist"
        const val NOTIF_SWITCH = "notification_switch"
        const val NOTIF_OPACITY = "notification_opacity"
        const val LAUNCHER_ADD = "launcher_addapp"
        const val LAUNCHER_COL = "launcher_col"
        const val LAUNCHER_ICON = "launcher_icon_size"
        const val LAUNCHER_FONT = "launcher_font_size"
        const val SHOW_LABEL = "showlabel"
        const val EXP_AUTOSTART = "exp_autostart"
        const val EXP_PACKAGE = "exp_package"
        // 【第 2 轮复盘】桌面常亮（FLAG_KEEP_SCREEN_ON）。
        // 说明为什么不复用 exp_autoblackscreen（"离开后自动黑屏"）：那是车机语义 ——
        // 车机上离开桌面后没有可见 UI 才需要黑屏省电；手机上离开桌面是去看目标 App，
        // 黑屏反而错。所以新增独立开关，实验室那条保持"未接管"并写明原因。
        const val SCREEN_AWAKE = "screen_awake"
        const val JUMP = "jump"
        const val JUMP_PKG = "jump_pkg_lp"
        const val JUMP_ALWAYS = "jump_always"
    }

    data class Layout(
        val weightA: Int,
        val weightB: Int,
        val weightC: Int,
        val weightSidebar: Int,
        val regionA: Int,
        val regionB: Int,
        val regionC: Int,
        val pipPackage: String,
        val pipPackage2: String,
        val musicPackage: String,
        val uiMode: Int,
        val accent: Int,
        val cornerDp: Int,
        val cardMarginDp: Int,
        val cardOpacity: Int,
        val customBackground: Boolean,
        val minimalStyle: Boolean,
        val showDiagnosticsOnDesktop: Boolean,
        val lockMusicPlayer: Boolean,
        val lockMusicPkg: String,        // lock_music_player_pkg：锁定后只接受该播放器
        val musicCompat: Boolean,        // switch_preference_1：媒体兼容模式（见 CarMediaListenerService 注释）
        val showAlbum: Boolean,
        // ---- 0.2-m3 收尾：此前只落了键、没进 Layout 的一组行为开关 ----
        // 通知助手（转发到桌面横幅）
        val notificationEnabled: Boolean,
        val notificationSeconds: Int,
        val notificationOpacity: Int,      // 0..100
        val notificationWhitelist: String, // 逗号分隔包名，空 = 全部
        // 启动与跳转
        val autoStart: Boolean,            // exp_autostart：开机自启（BootReceiver 读）
        val launchOnEnter: String,         // exp_package：进入桌面后拉起的应用
        val jumpOnExit: Boolean,           // jump：离开桌面时跳转目标
        val jumpPackage: String,           // jump_pkg_lp：跳转的目标应用
        val jumpAlways: Boolean,           // jump_always：不询问直接跳
        // 应用抽屉参数
        val launcherAddApp: String,       // launcher_addapp：常驻/置顶应用
        val launcherColumn: Int,
        val launcherIconDp: Int,
        val launcherFontSp: Int,
        val showAppLabel: Boolean,
        // 【第 2 轮复盘】桌面在前台时保持屏幕常亮（FLAG_KEEP_SCREEN_ON）
        val screenAwake: Boolean,
    )

    @Volatile
    private var cached: Layout? = null
    private var prefs: SharedPreferences? = null

    private val listeners = mutableListOf<(Layout) -> Unit>()

    fun current(ctx: Context): Layout = cached ?: load(ctx)

    /** 与 PreferenceFragment 同一个文件：默认 SharedPreferences。 */
    fun prefsOf(ctx: Context): SharedPreferences =
        prefs ?: PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)
            .also { prefs = it }

    private fun SharedPreferences.int(k: String, def: Int) =
        runCatching { getInt(k, def) }.getOrElse {
            // 兼容：老版本可能把数字存成了 String（ListPreference 写入）
            getString(k, null)?.toIntOrNull() ?: def
        }

    private fun SharedPreferences.str(k: String, def: String) = runCatching {
        getString(k, def)
    }.getOrNull() ?: def
        // 容错：ListPreference 存 String、代码侧 edit() 可能存成别的类型；
        // getString 对非 String 值会抛 ClassCastException，宁可用默认值也不崩桌面。

    private fun SharedPreferences.bool(k: String, def: Boolean) =
        runCatching { getBoolean(k, def) }.getOrElse {
            getString(k, null)?.toBoolean() ?: def
        }

    private fun load(ctx: Context): Layout {
        val p = prefsOf(ctx)
        val l = Layout(
            // 0.2-m8：默认与 pref_ui.xml 同步改为参考版实测权重/外观（两处必须同源，见 10.4）
            weightA = p.int(Keys.WEIGHT_A, 6),
            weightB = p.int(Keys.WEIGHT_B, 4),
            weightC = p.int(Keys.WEIGHT_C, 6),
            weightSidebar = p.int(Keys.WEIGHT_RIGHT, 1),
            regionA = normalizeRegion(p.int(Keys.AREA_A, A_NAVI)),
            regionB = p.int(Keys.AREA_B, B_NAVI),
            regionC = p.int(Keys.AREA_C, C_MUSIC),
            pipPackage = p.str(Keys.MIRROR_PKG, DEFAULT_PIPIP_PKG),
            pipPackage2 = p.str(Keys.MIRROR_PKG2, ""),
            musicPackage = p.str(Keys.MUSIC_PKG, DEFAULT_MUSIC_PKG),
            uiMode = p.int(Keys.UI_MODE, 0),
            accent = p.int(Keys.ACCENT, CarPipTheme.ACCENT),
            cornerDp = p.int(Keys.RADIUS, 20),
            cardMarginDp = p.int(Keys.MARGIN, 12),
            cardOpacity = p.int(Keys.CARD_OPACITY, 100),
            customBackground = p.bool(Keys.CUSTOM_BG, false),
            // 极简态由 ui_mode 单一决定（0 标准 / 1 极简），不再另设 minimal 开关——
            // 两个控件控同一行为是"空壳设置"的典型症状，用户批评过。Keys.MINIMAL 保留读，
            // 兼容 0.2-m2 老用户已经打开过的开关值（or 关系，二者任一为真即极简）。
            minimalStyle = p.int(Keys.UI_MODE, 0) == 1 || p.bool(Keys.MINIMAL, false),
            showDiagnosticsOnDesktop = p.bool(Keys.DIAG, false),
            lockMusicPlayer = p.bool(Keys.LOCK_MUSIC, false),
            lockMusicPkg = p.str(Keys.LOCK_MUSIC_PKG, ""),
            musicCompat = p.bool(Keys.MUSIC_COMPAT, false),
            showAlbum = p.bool(Keys.SHOW_ALBUM, true),
            notificationEnabled = p.bool(Keys.NOTIF_SWITCH, false),
            notificationSeconds = p.int(Keys.NOTIF_SECONDS, 5),
            notificationOpacity = p.int(Keys.NOTIF_OPACITY, 90),
            notificationWhitelist = p.str(Keys.NOTIF_WHITELIST, ""),
            autoStart = p.bool(Keys.EXP_AUTOSTART, true),
            launchOnEnter = p.str(Keys.EXP_PACKAGE, ""),
            jumpOnExit = p.bool(Keys.JUMP, false),
            jumpPackage = p.str(Keys.JUMP_PKG, ""),
            jumpAlways = p.bool(Keys.JUMP_ALWAYS, false),
            launcherAddApp = p.str(Keys.LAUNCHER_ADD, ""),
            launcherColumn = p.int(Keys.LAUNCHER_COL, 6),
            launcherIconDp = p.int(Keys.LAUNCHER_ICON, 60),
            launcherFontSp = p.int(Keys.LAUNCHER_FONT, 12),
            showAppLabel = p.bool(Keys.SHOW_LABEL, true),
            screenAwake = p.bool(Keys.SCREEN_AWAKE, true),
        )
        cached = l
        return l
    }

    /**
     * 批量写入。必须一次提交：权重是 4 个关联值，
     * 分 4 次 put 会让中间态被监听方读到（表现为布局跳一下）。
     */
    fun edit(ctx: Context, block: (MutableMap<String, Any?>) -> Unit) {
        val p = prefsOf(ctx)
        val pending = HashMap<String, Any?>()
        block(pending)
        val e = p.edit()
        for ((k, v) in pending) {
            when (v) {
                is Int -> e.putInt(k, v)
                is Boolean -> e.putBoolean(k, v)
                is String -> e.putString(k, v)
                null -> e.remove(k)
            }
        }
        e.apply()
        cached = null
        val fresh = load(ctx)
        for (l in listeners.toList()) runCatching { l(fresh) }
    }

    fun set(ctx: Context, key: String, value: Any?) = edit(ctx) { it[key] = value }

    /** PreferenceFragment 直接写默认文件后，用它通知桌面侧缓存失效并重算。 */
    fun invalidate(ctx: Context) {
        cached = null
        val fresh = load(ctx)
        for (l in listeners.toList()) runCatching { l(fresh) }
    }

    fun addListener(l: (Layout) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Layout) -> Unit) { listeners.remove(l) }

    /** 生效的音乐来源包名：锁定播放器优先，否则用通用「音乐来源包名」。 */
    fun effectiveMusicPkg(l: Layout): String =
        if (l.lockMusicPlayer && l.lockMusicPkg.isNotBlank()) l.lockMusicPkg else l.musicPackage

    /** 通知白名单匹配：空列表 = 全部放行；否则包名需命中其一。 */
    fun notifWhitelisted(l: Layout, pkg: String): Boolean =
        notifWhitelisted(l.notificationWhitelist, pkg)

    /** 纯字符串版，无 Android 依赖，便于单元测试（对齐测试用）。 */
    fun notifWhitelisted(whitelist: String, pkg: String): Boolean {
        val wl = whitelist.trim()
        if (wl.isEmpty()) return true
        return wl.split(',', '，', ' ', '\n').any { it.trim() == pkg }
    }

    /**
     * 双画中画【同时】接管（对齐车联助手：两个画中画并排、各显示不同 App）。
     * 与上一版"A 区一个锚点轮流显示"的关键区别：槽 1 固定绑 A 区、槽 2 固定绑 B 区，
     * 两者互不排斥，可在同一次布局里一起下发 → 屏幕上真的同时出现两个被接管的应用。
     *
     * 纯逻辑、可测：region 处于该槽的接管模式且目标包非空时返回 (槽号, 包名)，否则 null。
     */
    fun slotForRegionA(regionA: Int, pipPackage: String): Pair<Int, String>? =
        if (regionA == A_MIRROR && pipPackage.isNotBlank()) 1 to pipPackage else null

    fun slotForRegionB(regionB: Int, pipPackage2: String): Pair<Int, String>? =
        if (regionB == B_MIRROR && pipPackage2.isNotBlank()) 2 to pipPackage2 else null

    /**
     * 旧值归一化：0.2-m4 之前"画中画2"曾作为 A 区选项（regionA=2），与 B 区方案不能共存。
     * 升级到双画中画并行模型时统一按 A 区关闭处理；只改内存副本（prefs 不改写，
     * 用户在设置页重新选择后自然覆盖），避免测试/复用 load() 产生写盘副作用。
     */
    fun normalizeRegion(regionA: Int): Int = if (regionA == A_PIP2) A_NONE else regionA

    // 强调色预设：存 0xAARRGGBB 整数值本身，而不是存"序号"。
    const val ACCENT_GREEN = 0xFF1ED760.toInt()
    const val ACCENT_BLUE = 0xFF0A84FF.toInt()
    const val ACCENT_ORANGE = 0xFFFF9F0A.toInt()
    const val ACCENT_PINK = 0xFFFF375F.toInt()
    val ACCENT_PRESETS = listOf(ACCENT_GREEN, ACCENT_BLUE, ACCENT_ORANGE, ACCENT_PINK)
}
