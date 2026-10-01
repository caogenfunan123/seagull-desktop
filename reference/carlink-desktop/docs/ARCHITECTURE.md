# CarLink Desktop 基础架构（0.2-m8）

> 本文只描述**代码里真实存在**的结构。与 `docs/PIP-MIRROR.md`（画中画专档）、
> `docs/REVERSE-AND-PLAN.md`（逆向取证 + 逐轮复盘）配套阅读。

## 1. 一句话定位

在**已 Root + LSPosed** 的安卓手机上，做一个 CarPlay 风格的横屏车机桌面：
导航卡 + 音乐卡 + 双"镜像画中画"，可注册为默认 HOME。

## 2. 分层

```
┌──────────────────────────────────────────────────────────┐
│  UI 层  ui/                                                │
│   MainActivity  桌面（A/B/C + 侧边栏，权重来自设置）        │
│   SettingsActivity + prefs/*  13 屏设置（androidx.preference）│
│   DrawerActivity  应用抽屉                                 │
├──────────────────────────────────────────────────────────┤
│  状态层  state/CarLinkState（进程内 StateFlow 单例，唯一事实源）│
│     navi: StateFlow<NaviInfo>   music: StateFlow<MusicInfo> │
│     rawTaps / amapAutoInstalled / everReceivedBroadcast      │
├──────────────────────────────────────────────────────────┤
│  数据源层（两条独立腿，都写进 CarLinkState）                 │
│   navi/   AmapNaviReceiver → AmapAutoParser（高德车机版广播） │
│   media/  CarMediaListenerService（NotificationListener +   │
│           MediaSessionManager.getActiveSessions）           │
├──────────────────────────────────────────────────────────┤
│  呈现增强层 overlay/ PipWindow + NaviPipWindow/MusicPipWindow│
│           （TYPE_APPLICATION_OVERLAY 悬浮层，纯代码绘制）     │
├──────────────────────────────────────────────────────────┤
│  镜像层  mirror/MirrorSlot + svc/PipProjectionService        │
│           + svc/RootAuth（详见 PIP-MIRROR.md）              │
├──────────────────────────────────────────────────────────┤
│  常驻层  svc/DesktopService（specialUse FGS：承载接收器+悬浮层）│
│           svc/BootReceiver（BOOT_COMPLETED 自启）            │
├──────────────────────────────────────────────────────────┤
│  模块层  lsp/WFreeHook（LSPosed 入口，真实系统 PiP 比例固定） │
└──────────────────────────────────────────────────────────┘
```

## 3. 数据流（单向）

```
高德车机版 ──AUTONAVI_STANDARD_BROADCAST_SEND──▶ AmapNaviReceiver
                                                    │ AmapAutoParser（别名表 + 宽容转型 + KEY_TYPE 分派）
                                                    ▼
音乐 App ──MediaSession──▶ CarMediaListenerService   CarLinkState.onBroadcast()
                                │ rescan()/publish()         │
                                └────▶ CarLinkState.onMusic()┘
                                                    ▼
                                    StateFlow（navi / music / rawTaps）
                                                    ▼
                    ┌───────────────────────────────┴──────────────────────┐
        MainActivity.collect → renderNavi/renderMusic        DesktopService.collect → 悬浮层刷新
                    ▼
        MirrorSlot（虚拟屏镜像，独立于卡片数据流）
```

**关键约束**：`CarLinkState` 是唯一事实源；UI 不反向写数据，只写"设置"和"控制指令"。

## 4. 配置层（设置系统）

- 存储：`PreferenceManager.getDefaultSharedPreferences`（即 `<包名>_preferences`）。
  桌面侧读、设置侧写，天然同一份文件 —— 这是 m3 根治"改了读不到"空壳 bug 的关键。
- 单一入口 `CarLinkSettings.current(ctx)` 返回 `Layout` 数据类（缓存 + `invalidate()` 失效）。
- **两处默认必须同源**：`pref_ui.xml` 的 `app:defaultValue` 与 `CarLinkSettings.load()` 的
  `p.int(KEY, 默认)` 必须一致，否则"没进过设置页的新用户"和"进过设置页的用户"观感不同。
- 纯逻辑函数（`slotForRegionA/B`、`notifWhitelisted`、`effectiveMusicPkg`）不依赖 Android，
  便于 `src/test` 的对齐测试直接断言。

## 5. 屏幕方向与形态

- 桌面/设置/抽屉三个 Activity 全部 `android:screenOrientation="landscape"`
  （m8 起，对齐车联助手 manifest 实测 `screenOrientation=0` + `launchMode=2`）。
- 三 Activity 都声明 `configChanges`（orientation|screenSize|…）→ 旋转/分屏不重建，
  `onConfigurationChanged` 里手动重排布局。

## 6. 权限清单与实际用途（逐条，防"声明未用"）

| 权限 | 用途 | 实际使用点 |
|---|---|---|
| SYSTEM_ALERT_WINDOW | 悬浮层 | overlay/PipWindow、NotifBannerWindow |
| FOREGROUND_SERVICE + SPECIAL_USE | 常驻服务 | DesktopService |
| FOREGROUND_SERVICE_MEDIA_PROJECTION | 镜像投屏 FGS | PipProjectionService |
| POST_NOTIFICATIONS | 服务通知可见 | requestNotifPermissionIfNeeded |
| RECEIVE_BOOT_COMPLETED | 开机自启 | BootReceiver |
| QUERY_ALL_PACKAGES | 桌面枚举应用（HOME 类正当用途） | AppRepository、选应用列表 |
| WAKE_LOCK | ⚠️ 第 2 轮处理：需核实是否真用到 | 见 RETRO.md |

## 7. 测试闸门

`src/test` 三组：`SettingsAlignmentTest`（设置 key/默认与消费端对齐）、
`UnitStringsTest`（距离/时间格式化）、`TurnIconTableTest`/`TrafficLightSemanticsTest`/`NaviProgressTest`
（导航语义）。**以 debug 变体结果为准**（release XML 可能是陈旧构建残留）。

## 8. 已知未完成 / 待办（诚实清单）

- 导航卡数据依赖高德车机版广播；手机版不发协议（docs §6.5 已证），故车机版是硬前提。
- "苹果互联风格"（CarPlay 视觉）目前只有浅色 OneUI 卡片，未做 CarPlay 深色网格/圆角图标墙。
- 车联助手有独立设置图标入口 —— m8 已在侧边栏加齿轮图标（`ic_gear_fill`）。
- 详见 `docs/RETRO.md` 逐轮记录。
