package com.carlink.desktop.state

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.carlink.desktop.model.LightDir
import com.carlink.desktop.model.LightState
import com.carlink.desktop.model.MusicInfo
import com.carlink.desktop.model.NaviInfo
import com.carlink.desktop.navi.AmapAutoParser
import com.carlink.desktop.settings.CarLinkSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 单一事实来源。所有生产者（广播、MediaSession）写这里，所有消费者（桌面 UI、悬浮层）读这里。
 */
object CarLinkState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _navi = MutableStateFlow(NaviInfo.EMPTY)
    val navi: StateFlow<NaviInfo> = _navi.asStateFlow()

    private val _music = MutableStateFlow(MusicInfo.EMPTY)
    val music: StateFlow<MusicInfo> = _music.asStateFlow()

    /** 协议原始数据，最近 60 条，供"调试面板"回溯；不做环形缓冲会内存泄漏 */
    private val _rawTaps = MutableStateFlow<List<AmapAutoParser.RawTap>>(emptyList())
    val rawTaps: StateFlow<List<AmapAutoParser.RawTap>> = _rawTaps.asStateFlow()

    private val _amapAutoInstalled = MutableStateFlow(false)
    val amapAutoInstalled: StateFlow<Boolean> = _amapAutoInstalled.asStateFlow()

    private var appContext: Context? = null

    /** 是否曾经收到过任意一条高德广播 —— 用来区分"没装车机版"和"装了但没开导航" */
    private val _everReceivedBroadcast = MutableStateFlow(false)
    val everReceivedBroadcast: StateFlow<Boolean> = _everReceivedBroadcast.asStateFlow()

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        refreshAmapAutoPresence()
        // 独立的过期计时器：高德协议没有"导航结束"事件，只能靠超时把 isNavigating 归位，
        // 否则退出导航后卡片会永久停在最后一个路口。
        scope.launch {
            while (true) {
                delay(1_000)
                val n = _navi.value

                // 灯有独立且更短的寿命，并且计时基准是【最后一次收到灯数据】而不是
                // updatedAt —— 等红灯时诱导信息仍在持续刷新 updatedAt，
                // 若共用基准，灯永远不会消失。这是复盘时抓出来的第二个真 bug。
                if (n.lightState != LightState.NONE && n.lightAgeMs > NaviInfo.LIGHT_HIDE_MS) {
                    _navi.value = _navi.value.copy(
                        lightState = LightState.NONE,
                        lightCountdownS = 0,
                        lightDir = LightDir.NONE,
                        cruiseLightCount = 0,
                        lightUpdatedAt = 0L,
                    )
                }

                if (n.isNavigating && n.isStale) {
                    _navi.value = _navi.value.copy(isNavigating = false)
                }
            }
        }
    }

    fun refreshAmapAutoPresence() {
        val ctx = appContext ?: return
        _amapAutoInstalled.value = AmapPresence.isInstalled(ctx)
    }

    /** 由广播接收器调用。整个函数不许抛 —— 抛了会打断系统广播分发 */
    fun onBroadcast(intent: Intent?) {
        if (intent == null) return
        val kt = AmapAutoParser.keyTypeOf(intent)
        AmapAutoParser.captureRaw(intent)?.let { raw ->
            _everReceivedBroadcast.value = true
            _rawTaps.value = (_rawTaps.value + raw).takeLast(60)
        }
        // 不硬依赖 keyType：部分版本全程只发 10001 且把红绿灯塞在同一包里，
        // 所以先按语义字段试诱导信息，再试红绿灯，谁命中算谁。
        val parsed = if (kt == AmapAutoParser.KEY_TYPE_TRAFFIC_LIGHT) {
            AmapAutoParser.parseTrafficLight(intent, _navi.value)
                ?: AmapAutoParser.parseNavi(intent, _navi.value)
        } else {
            AmapAutoParser.parseNavi(intent, _navi.value)
                ?: AmapAutoParser.parseTrafficLight(intent, _navi.value)
        }
        if (parsed != null) _navi.value = parsed
        else if (kt == null) AmapAutoParser.logUnknown(intent)
    }

    fun onMusic(info: MusicInfo) { _music.value = info }

    /**
     * 发布一条【通知链路】解析出的导航信息（手机版高德无广播，走这里）。
     * 与 onBroadcast 分开是因为通知只带 到下一机动点距离/道路名，
     * 由解析器基于 prev 做 copy，这里只做整体替换和"曾收到"标记。
     */
    fun onNavi(info: NaviInfo) {
        _everReceivedBroadcast.value = true
        _navi.value = info
    }

    fun clearMusic() { _music.value = MusicInfo.EMPTY }

    fun canDrawOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun debugDump(): String = buildString {
        appendLine("navi        = ${_navi.value}")
        appendLine("music       = ${_music.value.copy(artwork = null)}")
        appendLine("amapauto    = ${_amapAutoInstalled.value}")
        appendLine("everRx      = ${_everReceivedBroadcast.value}")
        appendLine("overlayPerm = ${appContext?.let { canDrawOverlay(it) }}")
        appendLine("--- last 12 raw taps ---")
        _rawTaps.value.takeLast(12).forEach { t ->
            appendLine("kt=${t.keyType} ${t.entries.entries.joinToString("; ") { "${it.key}=${it.value}" }}")
        }
    }
}

object AmapPresence {
    /**
     * 包可见性受 <queries> 控制，manifest 里已声明 com.autonavi.amapauto。
     * 查的是【设置页里指定的那个包】——用户换成别的车机版包名时，自检结论才跟着变。
     */
    fun isInstalled(ctx: Context): Boolean = installed(ctx, CarLinkSettings.current(ctx).pipPackage)

    fun installed(ctx: Context, pkg: String): Boolean = try {
        if (pkg.isBlank()) false else {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        }
    } catch (_: Throwable) {
        false
    }
}

