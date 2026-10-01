package com.carlink.desktop.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * 已启动应用的枚举与缓存（0.2-m3 应用抽屉）。
 *
 * 设置页的"选应用"列表（AppListPrefs）和桌面应用抽屉都要这份数据，
 * 但两者对"系统应用是否显示"的口径不同：设置页只给用户可启动的、且系统项只显示桌面自身；
 * 抽屉则要显示全部可启动应用（这才是"抽屉"的意义）。所以这里只负责取原始列表，
 * 过滤口径留给调用方，避免一处妥协污染两处。
 *
 * 包可见性：本应用带 HOME 分类 + QUERY_ALL_PACKAGES（见 manifest 注释），
 * getInstalledApplications 能拿到全量；第三方不可见只会在缺这两个声明时发生。
 */
object AppRepository {

    class Entry(
        val label: String,
        val pkg: String,
        val activity: String,
        val icon: Drawable?,
        val isSystem: Boolean,
    )

    /** 取全部【有启动入口】的应用（含系统应用），按名称排序。耗时操作，须在后台线程调用。 */
    fun loadAll(ctx: Context): List<Entry> {
        val pm = ctx.packageManager
        val sysFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return pm.getInstalledApplications(0)
            .mapNotNull { ai ->
                val li = runCatching { pm.getLaunchIntentForPackage(ai.packageName) }.getOrNull()
                    ?: return@mapNotNull null   // 无可启动入口 = 抽屉里不该出现
                val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName)
                val icon = runCatching { ai.loadIcon(pm) }.getOrNull()
                Entry(
                    label = label.ifBlank { ai.packageName },
                    pkg = ai.packageName,
                    activity = li.component?.className ?: "",
                    icon = icon,
                    isSystem = (ai.flags and sysFlags) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** 按包名启动；返回 false 表示找不到入口（未安装/不可见）。 */
    fun launch(ctx: Context, pkg: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching {
            // 告诉前后台判定器"这是我们自己拉起的外部 App"（用户点抽屉图标不算退出桌面）
            com.carlink.desktop.ui.AppForeground.markSelfLaunch()
            ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    /**
     * 进程级缓存：抽屉加载与设置页选应用共用同一份枚举结果。
     * 首次 loadAll 后填充；安装/卸载由系统发 PACKAGE_ADDED/REMOVED，这里在
     * 每次进入抽屉/设置页时【主动失效重取】，所以不做 TTL，只做"取过没有"。
     */
    @Volatile
    private var cache: List<Entry>? = null

    fun loadAllCached(ctx: Context): List<Entry> = cache ?: loadAll(ctx).also { cache = it }

    fun invalidate() { cache = null }
}
