# 车联助手 X 深度逆向结论 + CarLink 重构规划

> 资料来源：`carplay-reverse-engineering` 私有仓库（dec21 = carplay_v21.apk 的 apktool 反编译产物，
> 4335 个 smali；主报告 31 章；PiP 触摸专项报告）。
> 本文档只写**有代码/报告证据**的结论，以及据此做出的独立设计。
> 状态：规划稿，未构建。

---

## 第一部分：车联助手 X 是怎么做到的

### 1.1 身份与模块声明

| 项 | 值 | 证据 |
|---|---|---|
| 包名 | `com.leting`，versionName 3.0.0 | aapt2 badging |
| 模块入口 | 3 个：`com.leting.xposed.xphook`、`com.example.gaodehook.HookEntry`、`com.example.wangyihook.HookEntry` | `assets/xposed_init` |
| **Xposed API** | **`xposedminversion = 102`** | `AndroidManifest.xml` meta-data |
| UI 框架 | Material Components（`mtrl_*`、`design_*` 资源大量存在） | `res/layout/` |

`xposedminversion` 就是用户说的「API 100/101/102」——那是 LSPosed/LibreXposed 的 API 版本号，
不是 Android SDK。我之前写的 82 是老 Xposed 的号，必须改。

### 1.2 【最重要的反证】它不用高德广播协议

> ⚠️ 本节结论已被第六部分修正：车联助手X 走 Hook 是因为**手机版高德根本不发这个协议**，
> 而不是协议不好用。真正的问题变成「我们手上没有车机版 APK」。

```
grep -rl "AUTONAVI\|amapauto" smali  →  0 命中
```

一个迭代到 v21、已经在真车上跑通的同类产品，导航数据**完全来自 Hook 手机版高德
`com.autonavi.minimap`**，没有使用 AmapAuto 标准广播协议。

这不证明广播路线错（它可能只是三星 CarLife 移植项目，历史包袱决定它不关心车机版），
但它证明了两件事：
- 手机版高德 Hook 是**已被验证可行**的路线；
- 我的「只押车机版广播」是**单点依赖**，必须补第二条腿。

### 1.3 高德侧只挂框架 API（7 个）

`gaodehook/HookEntry` 的 `handleLoadPackage` 里，目标只有 `android.app.Activity` 与
`android.app.PictureInPictureParams` 两个**框架类**，挂的方法全部是公开 SDK：

`isInMultiWindowMode`、`onMultiWindowModeChanged`(Z + Configuration)、
`setRequestedOrientation`(I)、`getRequestedOrientation`、`onCreate`(Bundle)、`onResume`、
`setPictureInPictureParams`(PictureInPictureParams)。

外加一个 `isPortrait(I)` 辅助判定（1/7/9/12/14 视为竖屏方向值）。

**结论**：它不改高德任何一个内部类。窗口化靠的是「骗过高德的多窗口/方向/PiP 决策」，
而不是抠它的 View。这正是我上一轮独立推导出的同一结论，现在得到双重印证。
框架 API 签名十年不变 —— 这是它能长期不坏的根本原因。

### 1.4 A 区真身：VirtualDisplay + SurfaceView + root 触摸转发

`res/layout/pip.xml` 只有两个子 View，结构极其干净：

```
ConstraintLayout
├── View            @id/touchpad     ← 覆盖在最上层，负责捕获手指
└── com.leting.auto.ModSurfaveView    ← 自定义 SurfaceView，显示副屏画面
```

`notouch/MirrorDisplay` 持有 `MediaProjection` / `MediaProjectionManager` / `SurfaceView`
三个静态字段，调用链：

```
MediaProjectionManager.getMediaProjection(resultCode, data)
  → MediaProjection.createVirtualDisplay(name, w, h, dpi, flags, surface, callback, handler)
  → DisplayManager.getDisplay(id) / getDisplays()
```

**把目标 App 拉到副屏**（`c1/h.smali`，日志 tag `CarWithX-PIP`）是**两级降级**：

1. 先试公开 API：`Context.startActivity(Intent, Bundle)`（配合 `ActivityOptions` 指定 display）；
2. 失败时打印 `"Android API denied; trying root am start"`，回退：
   `su -c "am start --display <displayId> -f 0x10104000 -n <component.flattenToShortString()>"`

`0x10104000` = `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME | FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`。
把新任务垫在 home 之上、并标记为历史启动，是为了让副屏上的 App 不会把主屏任务栈顶掉。

**触摸转发**（`b1/h.smali`）：

```
Runtime.exec(["su", "-c", "input -d <displayId> tap <x> <y>"])
Runtime.exec(["su", "-c", "input -d <displayId> swipe <x1> <y1> <x2> <y2> <duration>"])
```

判定规则：`|dx| < 10 && |dy| < 10` → tap，否则 swipe，duration 由手势计时得出。
`INJECT_EVENTS` 权限也声明了，但代码里没找到 `dispatchGesture`/`injectInputEvent` 调用，
说明**实际走的是 root shell，不是权限路径**。

无障碍服务（`res/xml/accessibilityservice.xml`）只监听 `com.baidu.carlife.xiaomi`，
`canPerformGestures=true`、`canRetrieveWindowContent=true` —— 它是给 CarLife 场景用的，
不是通用触摸方案。

### 1.5 导航卡片是代码构建的悬浮层

`carplay/CarPlayOverlayService`（3816 行）用 `TYPE_APPLICATION_OVERLAY`(0x7f6=2038) 与
`TYPE_PHONE`(0x7d6=2006) 两种窗口类型，`LinearLayout.addView` 纯代码拼装，
自带 `formatDistance(I)` / `formatTime(I)`。
**没有为转向图标准备任何 drawable 资源**（`res/drawable*` 里搜不到 navi/turn/maneuver 类图标），
说明图标也是代码画的 —— 与我 `CarPipTheme.turnArrow()` 自绘箭头的思路一致。

### 1.6 音乐侧与我完全同构

`d1/a.smali`、`d1/d.smali` 里有 `MediaSessionManager.getActiveSessions` 调用；
`NotificationListener` 是 `NotificationListenerService`（拿通知使用权）。
`com.example.wangyihook.HookEntry` 的目标包是 `com.netease.cloudmusic` 与
`com.netease.cloudmusic.iot`，配合 `sources/netease-pip-adapter` 的
`setLaunchDisplayId` 注入 —— 那是**网易云自己的 Activity 跳到主屏**的补丁，
属于 VirtualDisplay 路线的配套，不是数据源。

**结论**：音乐这块我的现有实现方向正确，不需要改。

### 1.7 设置体系（对应用户截图 4~6）

11 个 Fragment：`UI / Music / God / Boot / AppDrawer / Notification / Fs / ASS / EXP /
AdaptiveMode / Ta`。

`SettingsUIFragment` 里读到的分区键：
`pip`、`pip2`、`music`、`msgbox`、`widget`、`app`（区域功能枚举），
外观键 `cardopacity`、`cusbackground`、`radius`、`margin`、`layoutils`、`music_area_switch`。
侧边栏键：`sidebar`、`sidebar_position`、`sidebar_show_battery/time/signal/home`。
godmode 一族（`godmode_slot_1..5`、`godmode_opacity`、`godmode_rgb`、`godmode_iconsize`）
是悬浮球自定义，与 `res/values/arrays.xml` 的 `godmode_slot_entries`（关闭/助手主页/返回按钮）对应。

分区权重在截图里是「侧边栏/A 宽/BC 宽/B 高/C 高」五个整数百分比（60/40/40/60/0），
代码里对应的是 `LinearLayout` 的 `layout_weight`（`androidx.constraintlayout` 那个
`"weight"` 字符串是库内部用的，不是它的偏好键）。

### 1.8 它的权限清单（值得逐条对照）

```
SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION,
POST_NOTIFICATIONS, WRITE_SETTINGS, WRITE_SECURE_SETTINGS, STATUS_BAR_SERVICE,
BIND_ACCESSIBILITY_SERVICE, KILL_BACKGROUND_PROCESSES, FORCE_STOP_PACKAGES,
RECEIVE_BOOT_COMPLETED, PACKAGE_USAGE_STATS, QUERY_ALL_PACKAGES,
MANAGE_EXTERNAL_STORAGE, INJECT_EVENTS, CAPTURE_SECURE_VIDEO_OUTPUT, REORDER_TASKS
```

其中真正决定成败的是 `FOREGROUND_SERVICE_MEDIA_PROJECTION`（否则 Android 14 上
MediaProjection 起不来）和 `WRITE_SECURE_SETTINGS`（可 adb 授予，用来放开自由窗口等）。

---

## 第二部分：CarLink 与它的差距（对照用户截图）

| 维度 | 车联助手 X | 我现在的 0.1-probe | 差距性质 |
|---|---|---|---|
| 桌面骨架 | A/B/C + 侧边栏四区，权重可配 | 竖排两张卡 + 调试文字 | **架构缺失** |
| 视觉 | Material 组件、圆角卡片、毛玻璃、深浅两套 | 纯黑底 + 系统灰按钮 | 主题缺失 |
| 设置页 | 11 分组、可配包名/权重/外观 | 无 | **架构缺失** |
| A 区内容 | 真 App 跑在 VirtualDisplay 里，可触摸 | 无（只有自绘卡片） | **能力缺失** |
| 导航数据 | Hook 手机版高德 | 只押车机版广播 | 单点风险 |
| 音乐 | MediaSession | MediaSession | 持平 |
| LSP API | 102 | 82（写错） | 需改 |
| 调试能力 | 无 | 协议原始 key 回显面板 | **我更强** |

最后一行不是安慰自己：能在 App 里直接看到协议真实字段名，是它没有的能力，
也正是这一版探针最大的价值，必须保留。

---

## 第三部分：重构规划

### 3.1 设计原则

1. **框架层优先**：任何需要改第三方 App 内部类的功能，一律先找框架 API 的等价解法。
2. **两级降级**：每个特权操作都要有「公开 API 先试 → root 兜底」两条路，且失败可观测。
3. **单点依赖必须消除**：导航数据双源（车机版广播 + 手机版 Hook），运行时择优。
4. **可观测性优先于美观**：自检面板和协议回显保留，但移到设置页的「诊断」分组里。

### 3.2 目标架构

```
                    ┌──────────────────────────────────────┐
                    │  MainActivity（桌面，A/B/C/侧边栏）  │
                    │  ConstraintLayout + 权重来自设置      │
                    └──────────────────────────────────────┘
                       │ A区          │ B区        │ C区
                       ▼              ▼            ▼
              ┌────────────────┐  ┌─────────┐  ┌──────────
              │ SurfaceView    │  │ 导航卡  │  │ 音乐卡   │
              │  ↑ VirtualDisplay│ │(自绘)  │  │(MediaSess)│
              │  │ MediaProjection│└─────────┘  └──────────
              │  └ touchpad View │
              │     ↓ root input -d │
              └────────────────
                       ▲
   ───────────────────┴────────────────────────────────┐
   │ CarLinkState（StateFlow 单例，唯一事实来源）        │
   │   navi ← [AmapAutoReceiver | NaviHookClient] 择优   │
   │   music ← CarMediaListenerService(MediaSession)     │
   └─────────────────────────────────────────────────────┘
                       ▲
   ┌───────────────────┴──────────────┐
   │ 设置：分区权重/区域功能/包名/外观 │  诊断：协议回显/自检
   └──────────────────────────────────┘
```

### 3.3 模块划分

| 模块 | 内容 | 依赖 |
|---|---|---|
| `:app` | 桌面 UI、设置、诊断、悬浮层、服务 | Material Components |
| `:windowfreer` | LSPosed 模块（框架层窗口化 + 可选导航数据 Hook） | 依赖仍为 `de.robv.android.xposed:api:82`（该 artifact 最高就是 82，见 6.7）；manifest `xposedminversion` 82 → **102** |
| `:core`（可选） | 数据模型 + 状态中枢，两个模块共用 | — |

### 3.4 里程碑（重排，M1 不做窗口化）

- **M1 桌面与设置骨架**：Material 主题、A/B/C/侧边栏四区 ConstraintLayout、
  设置页（分区权重 + 区域功能枚举 + 画中画包名 + 外观），把现有调试面板收进「诊断」分组。
  验收：桌面观感对齐截图 1/2，设置改权重后布局实时变化。
- **M2 数据双源**：保留广播；新增 `NaviHookClient`（由 `:windowfreer` 通过 ContentProvider/广播
  回传），`CarLinkState` 按「谁先有数据用谁 + 心跳过期切换」。
  验收：只装车机版能出数据；只装手机版也能出数据；都不装时自检明确指认缺哪个。
- **M3 触摸镜像（A 区）**：MediaProjection + VirtualDisplay + SurfaceView + touchpad，
  `startActivity` 失败降级 `su -c am start --display`，触摸降级链
  `input -d tap/swipe`。
  验收：高德能在 A 区里操作（点搜索、点导航）。
- **M4 外观打磨**：毛玻璃/圆角/深浅主题、导航卡与音乐卡的视觉对齐截图。
- **M5 开机自启与保活**、**M6 方控/语音**（后置）。

### 3.5 明确不做

- 不做 godmode 悬浮球（与目标无关）。
- 不做 CarLife/三星移植相关分支。
- 不做百度统计（它的 `BaiduMobAd_*` 我们不需要）。
- 不抄它的混淆类名/字段表作为实现依据；M2 的 Hook 点位届时独立设计。

---

## 第四部分：复盘（三轮）

### 复盘一：技术可行性

**Q1：VirtualDisplay 路线在手机上真的可行吗？**
可行但有硬约束：MediaProjection 每次启动都要用户点授权弹窗（系统级，无法绕过）。
它的解法是 `SlaveActivity` 用 `startActivityForResult` 拿一次授权后
`getMediaProjection` 缓存到静态字段。我们必须照做，并且要处理
`MediaProjection.Callback.onStop()`（用户从通知栏点停投时，A 区要能优雅降级为占位图，
而不是黑屏）。

**Q2：root shell 触摸转发的延迟能接受吗？**
每次触摸 `fork+exec` 一个 `su`，报告实测 45–150ms，且 `ACTION_CANCEL` 未处理。
这是它的已知缺陷，不是我们的。改进方向：常驻一条 `su -c sh` 的 stdin 管道，
把 MotionEvent 批量写入，避免每次 fork。这条优化列为 M3 的可选项，不进主干。

**Q3：`input -d` 在低版本不存在怎么办？**
`input -d <displayId>` 是 Android 10+ 才有的参数。minSdk 24 上必须回退
（无 `-d` 只能操作主屏 → A 区不可交互）。处理：M3 里做能力探测，
Android 10 以下直接隐藏 A 区窗口化选项并在设置里说明原因，不做静默降级。

**Q4：0x10104000 这组 flag 能照抄吗？**
它是 `NEW_TASK|TASK_ON_HOME|LAUNCHED_FROM_HISTORY`。语义合理（垫在 home 上、不抢主屏），
但 `LAUNCHED_FROM_HISTORY` 会让部分 App 恢复旧实例而非新建。M3 要实测，
必要时只用 `NEW_TASK|TASK_ON_HOME`。

**结论**：路线可行，但 M3 的三处（授权弹窗、管道延迟、API 29 门槛）必须显式处理。

### 复盘二：范围与优先级

**Q1：M1 先做 UI 而不是窗口化，对吗？**
对。理由：(a) 用户当前最不满的是「简陋、没设置页」，M1 直接解决；
(b) 窗口化依赖 MediaProjection 授权流程，调试成本高，且它不改变数据链路——
先把四区骨架和设置打通，M3 只是往 A 区里填内容，不返工。

**Q2：数据双源（M2）会不会过度设计？**
不会，但要做减法。真正的风险是「车机版在手机上被环境检测限制」——
这一条至今未被用户的真机验证过（自检面板显示「高德车机版已安装 ✗」，
说明用户还没装车机版，广播路线**尚未取得任何实证**）。
所以 M2 不是锦上添花，而是**在广播路线被证伪时的唯一退路**。
但实现上克制：Hook 只回传 6 个字段（图标值、段距、全程距、剩余时间、路名、下一路名），
不追求全量。

**Q3：`:core` 模块要不要拆？**
暂不拆。目前只有 `:app` 需要状态中枢，`:windowfreer` 是独立进程注入方，
两者靠广播/Provider 通信而非共享类。拆了反而增加构建面。等 M2 真的出现
两边共用的数据契约类时再拆。

**Q4：Material 组件会不会重蹈 Compose 的构建风险？**
不会。`com.google.android.material:material` 是普通 AAR，无编译器插件，
与已验证可编译的 appcompat 同源。Compose 的风险来自 Kotlin 2.0 编译器插件链。
但要注意：Material 主题必须给 Activity 用（`Theme.Material3.*` 或 `Theme.MaterialComponents.*`），
**悬浮层仍走 Service context + 纯代码 Drawable**，这条已有教训不能破。

**结论**：里程碑顺序正确，M2 定性从「增强」改为「退路」，`:core` 不拆。

### 复盘三：风险与退路

**R1（高）：车机版高德在手机上根本不能正常导航。**
这是当前最大未知数，且已有间接信号（用户手机上未安装）。
退路：M2 的手机版 Hook；再退一步，纯手机版高德 + 前台服务通知解析（导航时高德自己会发
一条含「XX米后右转」的常驻通知，`NotificationListenerService` 直接读文字即可，
零 Hook 成本）。**这条最省，应该提到 M2 里先做**，作为广播失败后的第一兜底。

**R2（中）：LSPosed 102 API 与目标 ROM 不匹配。**
`xposedminversion` 只是提示，真正决定兼容的是 `IXposedHookLoadPackage` 接口签名（多年未变）。
风险低。做法：manifest 里 `xposedminversion=102`，同时代码不使用任何 102 独有 API，
保证在 93/100/101/102 上都能加载。

**R3（中）：root 不可用（用户换机/未授权）。**
所有 root 路径必须可探测、可关闭：A 区窗口化、触摸注入全部包在
`RootProbe.available()` 之后，不可用时设置页对应项置灰并说明，桌面退化为
B/C 双卡模式（即当前形态）。

**R4（低）：MediaProjection 在 Android 14 上被限制后台启动。**
需要 `FOREGROUND_SERVICE_MEDIA_PROJECTION` + 已声明类型的 FGS。
我们已有 specialUse 服务，M3 时新增 `mediaProjection` 类型服务，
并在 `AndroidManifest` 补权限。

**R5（低）：仓库里 153MB 的 `base-referenced.apk` 尚未取回。**
LFS 指针已确认，batch API 能拿到下载地址。它只是参照包，不阻塞任何里程碑。
> ✅ 已取回并校验（sha256 `db32ca1633ff0c01…`），身份见 6.1/6.5 —— 风险关闭，
> 但它带来的结论（手机版不发协议）比原风险本身重要得多。

**结论**：R1 需要立刻改变 M2 的实现顺序（通知解析先于 Hook）；
其余风险都有明确退路。

---

## 第五部分：据复盘修正后的最终执行顺序

1. **M1** 桌面四区骨架 + Material 主题 + 设置页 + 诊断分组（不动数据链路）
2. **M2a** 导航数据第三兜底：读高德前台服务通知文字（零 Hook、零 root，最快见效）
3. **M2b** 数据源择优调度（广播 / 通知解析 / 未来的 Hook，统一进 `CarLinkState`）
4. **M3** A 区 VirtualDisplay 窗口化 + root 触摸转发（含能力探测与降级）
5. **M4** 视觉打磨对齐截图
6. **M5** 自启保活
7. **M6**（可选）手机版高德 Hook 数据源
8. `:windowfreer` 的 `xposedminversion` 与依赖统一改 **102**

> 下一步等确认后再动代码。构建前会先跑一次全量编译验证。

---

## 第六部分：全量参照深挖结论（已把该拉的全部拉下来读完）

这一轮把三类参照物真正取回本地并逐条读了代码/字节码，结论**推翻了第五部分的两个前提**，
并暴露出我自己代码里两个会静默失效的 bug。所有证据都在下面标注了文件与行号，可复核。

### 6.1 参照物清单与取证方式

| 参照物 | 本地路径 | 体量/校验 | 取证手段 |
|---|---|---|---|
| 车联助手X（com.leting 3.0.0） | `/data/work/dec/dec21` | 4335 smali + 解码 manifest | baksmali + grep |
| `base-referenced.apk`（LFS） | `/data/work/base-referenced.apk` | 159,717,671 B，sha256 `db32ca1633ff0c01…` 已校验 | unzip + dex strings |
| ↳ 身份 | `/data/work/amapx/classes*.dex` | 8 个 dex | **= 高德手机版 `com.autonavi.minimap` 16.16.6.2013** |
| Nav-Link（广播协议消费端） | `/data/work/refs/Nav-Link-master` | 528 KB | 全量读 Java |
| YAMFsquared（freeform LSP 模块） | `/data/work/refs/YAMFsquared-main` | 980 KB | 读 manifest/build/HookSystem |
| 小米 CarWith 4.0.4 | `cre/apks/CarWith-4.0.4.apk` | 63.6 MB，`com.miui.carlink` | 抽 7 个 dex 做 strings 取证 |
| 其余 20 个 APK | `cre/apks/` | 19 个为 `com.leting` 历史版本（v10~v21、ucar、neteasePiP），1 个 `com.netease.cloudmusic.iot` | `aapt2 dump packagename` 全量识别 |

**关于 `cre/apks/` 的价值判定**：21 个包里有 19 个是同一个车联助手X 的迭代版本，
逆向第一个就够，其余是噪声（但 `carplay_v10→v21` 的演进顺序说明作者在触摸/窗口化上反复试错，
如果需要考古可以看 diff）。真正独立的第三方只有 CarWith 和网易云 IoT 版，CarWith 见 6.9（新反证）。

### 6.2 【致命 bug 1】`AmapAutoParser` 的距离字段类型判断错了

Nav-Link 是唯一一个真在车机版上跑通过的**协议消费端**代码，它读取距离/时间的方式：

```java
// AmapNaviReceiver.java:95-98
String segRemainDis   = intent.getStringExtra("SEG_REMAIN_DIS_AUTO");
String routeRemainDis = intent.getStringExtra("ROUTE_REMAIN_DIS_AUTO");
String routeRemainTime= intent.getStringExtra("ROUTE_REMAIN_TIME_AUTO");
String etaText        = intent.getStringExtra("ETA_TEXT");
```

**这三个字段是 String，而且带中文单位**（"55米" / "1.1公里" / "4分钟"），
Nav-Link 靠 `endsWith("公里")` 再 `replace` 来拆分数值与单位（AmapNaviReceiver.java:110-120）。

我现在的代码走的是 `firstInt(b, ALIAS_SEG_DIST)`，内部 `String` 分支只做
`toIntOrNull() ?: toDoubleOrNull()?.toInt()`（AmapAutoParser.kt:149）。
"1.1公里" 两条都失败 → 返回 null → `?: prev.xxx` 静默保留旧值。
**表现就是：卡片永远停在初始 0，没有任何报错。** 这就是我所谓"不硬信任 key 名"的
宽容策略反而掩盖了类型错误。必须重写为「先按 String+单位解析，失败再按 Number」。

**精确到每个字段（复盘三时发现，避免夸大）**：

| 字段 | 别名表（AmapAutoParser.kt:46-48） | 实际后果 |
|---|---|---|
| 转向距离 | `SEG_REMAIN_DIS_AUTO` → `SEG_REMAIN_DIST` → … | 别名里**没有可用的 Int 版本** → 只要车机版只发 `_AUTO` 字符串，**这一项必然恒为 0** |
| 剩余距离 | `ROUTE_REMAIN_DIS_AUTO` → **`ROUTE_REMAIN_DIST`** → … | 第一个（String 带单位）失败后，第二个正好是不带 `_AUTO` 的 **Int 米**，**意外能出值** → 所以真机上会看到"全程剩余有数、转向距离永远 0"这种半死不活的样子，最容易误判成"协议部分不支持" |
| 剩余时间 | `ROUTE_REMAIN_TIME_AUTO` → `ROUTE_REMAIN_TIME` → … | 同上一行，取决于该版本是否同时发不带 `_AUTO` 的 Int 版；不能赌，两个都要按类型读 |

这条差异很重要：它解释了为什么"看起来好像有一点数据"仍然不等于解析是对的。

### 6.3 【致命 bug 2】`TurnIcon` 常量表整体错位

FloatingWindowManager.java:803 的注释是**作者自己验过的**枚举表，是唯一可信来源：

```
已验证: 2=左转 3=右转 4=左前方 5=右前方 8=掉头 9=直行
        10=途经点 11=进入匝道 12=驶出匝道 15=终点；default=直行
```

对照我 `NaviInfo.kt:69-89` 的 `TurnIcon` 常量表——**全部错位**：

| 语义 | 正确值（Nav-Link 已验证） | 我当前的值（NaviInfo.kt） |
|---|---|---|
| 左转 | 2 | 1 |
| 右转 | 3 | 2 |
| 左前方 | 4 | 3 |
| 右前方 | 5 | 4 |
| 掉头 | 8 | 17 / 18 |
| 直行 | 9 | 9（巧合正确） |
| 途经点 | 10 | — （我把 10 当"靠左"） |
| 进入匝道 | 11 | — （我把 11 当"靠右"） |
| 驶出匝道 | 12 | — （我把 12 当"环岛"） |
| 终点 | 15 | — （我把 15 当"环岛直行出口"） |
| 未知 | → 一律按直行渲染 | — |

**根因**：我这套 1..18 的数值抄自**手机版高德导航 SDK 的 maneuver icon 枚举**
（那里确实是 1=左转、2=右转…还有环岛系列），而车机版广播里的 `NEW_ICON`/`ICON`
是**另一套命名空间**。两个枚举同名不同值，我把它们当成一个了。
13~18 在车机版协议里未验证 → 按 Nav-Link 处理，一并落 default（直行），
但**保留原始数值到诊断面板**，等真机数据回来再补表。

### 6.4 【协议缺失字段与语义】六项必补

1. **`NEW_ICON` 优先、`ICON` 兜底**：`int icon = getIntExtra("NEW_ICON",0); if(icon==0) icon=getIntExtra("ICON",0);`
   （AmapNaviReceiver.java:47-50）。我的别名表把 `ICON` 放首位、且没有 `NEW_ICON`，
   在支持新旧图标的版本上会拿到旧枚举。
2. **`ETA_TEXT`**：已是格式化好的中文串（例："18:06到"），直接显示，**不要自己用时间戳算到达时间**。
3. **进度条**：用 `ROUTE_REMAIN_DIS`(Int) 与 `ROUTE_ALL_DIS`(Int) 算
   `1 - remain/all`（AmapNaviReceiver.java:132-135）。注意这里 `ROUTE_REMAIN_DIS`（**不带 _AUTO**）
   才是 Int，和 6.2 里带 `_AUTO` 的 String 是两个不同的 key —— 我目前完全没读这两个。
4. **巡航红绿灯**：导航态是 `trafficLightStatus`/`dir`/`redLightCountDownSeconds` 三个独立 Int；
   巡航态是 **一个 JSONArray 字符串塞在 `lightsData` 里**（AmapNaviReceiver.java:84-88）。
   两套语义不同，不能共用解析：

   | | 导航态 | 巡航态 |
   |---|---|---|
   | 绿灯 | status == 4 | status == 1 |
   | 红灯 | status == 1 | status == 0 |
   | 黄灯 | 其它 | 其它 |
   | 方向 | 1左 2右 3掉头 4直行 | 1左 2直行 3右 |

   我现在的 `normalizeLight` 把 `0` 一律当红灯、且不知道巡航这套反转 —— **会把巡航绿灯当红灯显示**。

5. **`dir`（红绿灯方向）我完全没建模**：`NaviInfo` 里只有 `lightState`/`lightCountdownS`，
   没有方向字段（已 grep 全项目确认）。M4 要显示方向箭头时必须加字段，不能塞进字符串。
6. **红灯倒计时缺失就整包丢弃**：`AmapAutoParser.kt:117` 是
   `val sec = firstInt(...) ?: return null`。绿灯相位通常不带 `redLightCountDownSeconds`，
   于是**绿灯数据被直接扔掉**，用户看到的是"灯不显示"而不是"绿灯无倒计时"。
   这条是复盘一时才发现的，比类型 bug 更容易在真机上露馅。改为：状态与倒计时各自可选，
   只要任一命中就产出数据。

### 6.5 【前提被推翻】手机版高德 16.16.6 确实不发这个协议

对 `base-referenced.apk`（手机版高德）8 个 dex 做字符串取证：

```
AUTONAVI_STANDARD_BROADCAST        → 0 命中
SEND_STANDARD_BROADCAST            → 0 命中
PARA_FLP_AUTONAVI                  → 5 命中（只是缓存常量，与广播无关）
ExtraScreenNotifyService           → 26 命中
showNotificationForExtraScreen     → 3 命中
```

这条证据把第五部分 R1 的表述纠正了：**"车机版在手机上不能导航"不是最大风险，
真正的结构性事实是「手机版压根没有这个广播协议」**。
所以车联助手X 走 Hook 不是历史包袱，而是**被迫的**——我 1.2 节里那句"可能只是历史包袱"是错的。

推论出两个确定的行动：
- **拿到车机版（`com.autonavi.amapauto`）APK 变成前置决策点**，不再是"可选增强"。
  没有它，广播路线在这台手机上永远收不到任何数据。
- 手机版高德存在 `com.autonavi.bundle.routecommon.service.ExtraScreenNotifyService`，
  并有 `showNotificationForExtraScreen#startForeground`、`ExtraScreenIcon/Content/Template/DisContent`、
  `IRouteNaviInfoController`、`RouteNaviInfoConstant`。
  **说明手机端导航数据是通过"副屏通知"这条通道以 Notification 形式外发的。**
  这既验证了 M2a（读通知文字）不是权宜之计而是官方通路，也给出了 M6 Hook 的**精确落点**
  （Hook `ExtraScreenNotifyService` 拿 Builder，比 Hook 导航内部类稳定得多）。

### 6.6 【法律/授权约束】Nav-Link 不能直接抄

`refs/Nav-Link-master` 根目录只有 `README.md`，**没有任何 LICENSE/COPYING 文件**
（已实测 `ls | grep -i licen` → 无结果）。默认即"保留所有权利"。
因此：
- 那 12 个转向图标 + 3 个红绿灯颜色图标 + 4 个方向图标（140×140 PNG）**不能搬进我们仓库**；
- 代码片段也不能整段复制。
- 允许且应该做的是：把它当**协议说明书**（字段名、类型、枚举语义属于事实，不受版权保护），
  图标自己画（我用 VectorDrawable 重绘 12 个箭头 + 3 色圆形灯，成本可控），
  渲染参数（320dp 卡、#FF121212、12dp 圆角、`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE|FLAG_WATCH_OUTSIDE_TOUCH`、
  `format=-3`、TOP|START、minimal 样式与位置记忆）作为通用工程参数自研实现。

顺带：YAMFsquared 仓库里带着作者的 `releaseKey.jks` 签名私钥 —— **不下载、不使用、不复制**，
我们的 `:windowfreer` 继续用自己的 debug keystore。

### 6.7 YAMFsquared 对 `:windowfreer` 的两点校正

- 它的 `xposedminversion=93`，compileSdk 走 `compileOnly api:82` + `dev.rikka.hidden:stub:4.2.0`
  + 自建 `android-stub` 模块；车联助手X 是 `102`。
  用户要求的"支持 api 100/101/102"指的就是这个 **LSPosed API 版本号**，不是 Android SDK。
  → `:windowfreer` 的 manifest `xposedminversion` 从 82 改 **102**，
  依赖仍保持 `de.robv.android.xposed:api:82`（该 artifact 最新就是 82，102 只是声明下限要求，
  改依赖反而会引入不存在的版本）。
  **这条已实测**：`api.xposed.info/.../api-{82..103}.jar` 逐个探测，只有 `api-82` 返回 200，
  83 及以上全部 404。所以 `xposedminversion` 是**元数据里的一个数字**，与依赖版本无关，
  改成 102 零风险，改成"依赖 102"会直接构建失败。
- 它的 Hook 落点是 `android.os.ServiceManager` + `com.android.server.am.ActivityManagerService`，
  也就是**注入 system_server 做全局 freeform**；我目前只 Hook 目标 App 的框架方法（局部伪多窗口）。
  两条路线不是谁对谁错：system_server 路线能力更强（真 resize、真自由窗口），
  风险也更高（挂了是整个系统挂）。**保持现有局部方案，把 system_server 方案记为 M7 备选，
  不进 M1**，等 A 区 VirtualDisplay 方案（M3）验证不足时再启用。

### 6.8 据此修正的执行顺序（替换第五部分末尾那张表）

0. **【新增·阻塞项】确认能否取得车机版高德 APK**（用户提供 / 可下载源）。
   拿不到 → 广播路线在开发期只保留代码与原始数据面板，主验证路径改为 6.5 证实的通知通道。
1. **M1** 桌面四区骨架 + Material 主题 + 设置页 + 诊断分组
   - 与 M1 同批必做的**修 bug**（不改变架构，纯正确性）：
     `AmapAutoParser` 重写为「String-with-unit 优先」+ 补 `NEW_ICON`/`ETA_TEXT`/
     `ROUTE_REMAIN_DIS`/`ROUTE_ALL_DIS`/`lightsData`；`NaviInfo.TurnIcon` 按 6.3 表重映射；
     巡航/导航红绿灯状态语义分表；图标自绘。
2. **M2a** `NotificationListenerService` 读高德 `ExtraScreenNotifyService` 发出的导航通知
   （现在是**主数据源**，不再是兜底）
3. **M2b** 数据源择优调度（广播 / 通知 / Hook 三路统一进 `CarLinkState`，按新鲜度与优先级仲裁）
4. **M3** A 区 VirtualDisplay 窗口化 + root 触摸转发（含 `RootProbe` 能力探测与降级）
5. **M4** 视觉打磨（自绘图标 + 对齐截图）
6. **M5** 自启保活
7. **M6** Hook `ExtraScreenNotifyService` 取原始 `NotificationCompat.Builder`（比读文字更精确，
   可拿到 icon 与数值，不必正则猜中文）
8. **M7**（备选，默认不做）system_server freeform 路线
9. `:windowfreer` `xposedminversion` → 102

### 6.9 【新增反证】小米 CarWith 也不消费这个协议，且它没带导航 SDK

手上唯一一个**真正量产的车机互联产品**（`com.miui.carlink` 4.0.4，63.6 MB，7 个 dex）取证结果：

```
AUTONAVI_STANDARD_BROADCAST → 0
SEG_REMAIN / ROUTE_REMAIN / ROUTE_ALL_DIS / NEW_ICON / ETA_TEXT
NEXT_ROAD_NAME / CUR_ROAD_NAME / trafficLightStatus / lightsData
redLightCountDown / CUR_SPEED → 全部 0
com.autonavi.amapauto       → 出现，但只有 2 处：
                              "com.autonavi.amapauto" 和 "app_icon_com_autonavi_amapauto"
Lcom/amap/api/…             → 374 处（定位 AMapLocation、路径规划 Path/BusPath、地理围栏、POI/Tips）
Lcom/amap/api/navi          → 0 处   ← 关键
com.autonavi.map.activity.NewMapActivity、com.autonavi.minimap:id/atmapsView → 存在（资源 id 引用）
```

读法（严格区分事实与推断）：
- **事实**：CarWith 里 `com.autonavi.amapauto` 只以"包名字符串 + 图标资源别名"的形式出现，
  它没有 AmapAuto **协议** 的任何一个字段名；它带的是**高德开放平台 SDK 的搜索/定位/路线规划类**，
  **不含 `com.amap.api.navi` 导航 SDK**。
- **推断（标注为不确定）**：它大概率是靠"检测高德车机版是否安装 → 在手机上镜像车机版界面"
  这条路（`app_icon_com_autonavi_amapauto` 这种命名正是"外部应用图标占位"的写法），
  而不是靠解析广播拿导航数据。所以**不能**用它来论证"协议可用"，也**不能**用它论证"协议无人用"——
  它的场景是手机镜像车机，与我们要做的"手机自己出桌面"是两回事。

结论并到 6.5：**协议的唯一已验证消费端仍然是 Nav-Link 一个**。
这不再支持"广播路线是主流"的乐观假设。因此 6.8 里第 0 项（拿到车机版 APK）
和第 2 项（M2a 通知解析升为主源）都不是可选项，而是**必须同时成立才有确定性**。

### 6.10 顺带确认：我的注册与服务架构不用改

Nav-Link 的接收端与我完全一致：
动态 `registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)`、
单一 action 过滤器、`KEY_TYPE` 分流（10001 / 60073）、`foregroundServiceType="specialUse"`
承载常驻服务。
> ⚠️ 修正本节初稿的措辞：我原本写"已被第三个独立实现验证"，**这是错的**。
> 6.5 / 6.9 的取证说明车联助手X 与小米 CarWith **都不消费这个协议**，
> 已验证的协议消费端只有 Nav-Link 一家。架构一致只能证明我没写错基本盘，
> 不构成"广播路线有第二家背书"。超时参数也一致
（NAVI 6000ms ≈ 我的 `STALE_MS=6000`，
另有 LIGHT_HIDE 5000ms、WATCHDOG 5000ms、巡航宽限 cruise-grace 3000ms（FloatingWindowManager.java:31-34、238），
这三个值我缺，M1 补齐）。另外它导航↔巡航切换有 300ms 防抖（:374、:383），
我的状态机没有，会出现"卡片每 300ms 闪一次"，M1 一起补。
注意低版本分支：`registerReceiver` 的 `RECEIVER_EXPORTED` 带 SDK 判断，API<33 走两参版本 —— 这条我已实现正确。
所以 M1 不需要动 Service/Receiver 骨架，只动解析与 UI 层。

### 6.11 修复的正确落点：只改两处，不碰渲染架构

已确认 `TurnIcon` 的消费者只有 `CarPipTheme.kt:55,81-88`（一支箭头 Drawable 按角度旋转 + 环岛特判）。
这个"一支箭头旋转"的方案本身是**对的而且天然免疫版权问题**（6.6 要求自绘图标，这个方案正是自绘），
所以修 bug 不需要重做渲染，只改两张表：

```
新 TurnIcon（车机版协议命名空间）        箭头角度        文字兜底
LEFT            = 2                      -90°           左转
RIGHT           = 3                      +90°           右转
LEFT_FRONT      = 4                      -45°           左前方
RIGHT_FRONT     = 5                      +45°           右前方
UTURN           = 8                      180°           掉头
STRAIGHT        = 9                      0°             直行
WAY_POINT       = 10  ┐
RAMP_IN         = 11  ├ 0°（暂按直行渲染） 途经点/匝道   ← 保留原始 int 到诊断面板
RAMP_OUT        = 12  ┘
END             = 15                     0°             终点
未知 / 13..18                            0°             导航中
```
环岛特判（`RING`/`RING_EXIT_*`）在车机版协议里未验证 → **删除该分支**，
等真机原始数据里真出现环岛值再按实际语义补。删掉未验证代码比留着猜更安全。

`LightState` 保持 0..3 的内部枚举不变，只在 parser 出口做一次导航态/巡航态两套映射，
这样 UI 层（`CarPipTheme.lightColor`，93-98 行）零改动。

### 6.12 还没解决、需要用户拍板的一件事

车机版高德 APK 的获取渠道：用户手上是否有（或能从车机/其他来源导出）。
这决定 M1 之后主验证路径是「广播」还是「通知解析」。**其余全部已自主决定，不需要确认。**

---

## 第七部分：针对第六部分的三轮复盘（已完成）与代码落地记录

### 复盘一：证据自检（这一轮抓出 4 处我自己写错/写虚的地方）

| # | 初稿说法 | 复核结果 | 处理 |
|---|---|---|---|
| 1 | "架构已被第三个独立实现验证" | **错**。车联助手X（6.5）与小米 CarWith（6.9）都不消费该协议，已验证消费端只有 Nav-Link 一家 | 6.10 已改写为"只能证明我没写错基本盘"，不再当作路线背书 |
| 2 | "卡片永远停在初始 0" | **过头**。剩余距离/时间的别名表里正好有不带 `_AUTO` 的 Int key，会意外出值 | 6.2 补逐字段表，改为"转向距离恒 0、全程距离可能有值"这种半死不活形态 |
| 3 | "`xposed:api` 最新就是 82" | 已实测：`api-{82..103}.jar` 只有 82 返回 200 | 6.7 补实测数据 |
| 4 | "CarWith 靠镜像车机版" | dex 里只有包名字符串与图标别名，**无导航 SDK**，这是推断不是事实 | 6.9 明确分「事实 / 推断（不确定）」两栏 |

### 复盘二：设计自检（抓出 2 个会把修复做坏的地方）

1. **我差点把巡航红绿灯做成半成品**。第一版 `parseCruiseLights` 只返回数组长度，
   `lightState = prev.lightState` —— 等于解析了但没用，比不解析更糟（读者以为已支持）。
   已改为按巡航语义（1绿/0红，方向 1左/2直行/3右）真正映射出 state/dir/countdown。
2. **我差点把"字段缺席"当成"数据过期"**。第一版在 `trafficLightStatus` 缺席时清空灯，
   会在相邻两包之间闪一下。协议本来就常只发倒计时不发状态，正确做法是
   「字段缺席保留旧值 + 独立的灯寿命定时器」，与 Nav-Link 的 LIGHT_HIDE 一致。

### 复盘三：实现自检（抓出 1 个真 bug + 1 处顺序按猜测排）

1. **灯不消失（真 bug，已修）**：灯数据与诱导数据共用 `updatedAt` 作计时基准，
   而等红灯时诱导信息仍以约 1 次/秒刷新 `updatedAt` → `now - updatedAt` 永远 < 5s，
   **灯永远不会隐藏**。修法是给 `NaviInfo` 加独立的 `lightUpdatedAt` / `lightAgeMs`，
   灯只按自己的时间戳老化（`CarLinkState` 的 1s 轮询里判定）。
2. **读取顺序不该按我的猜测排（已修）**：第一版把裸 Int `ROUTE_REMAIN_DIS` 排在
   带单位的 `ROUTE_REMAIN_DIS_AUTO` 之前，理由是"Int 更精确"。但**已验证的消费端读的是后者**，
   前者只是我按命名规律加的未验证兜底。证据强度决定优先级，故改回 String 优先、Int 兜底。
3. **`NEW_ICON==0` 的语义（已修）**：0 是"本包无转向图标"，不是"新字段值为 0"，
   所以必须继续看 `ICON`。Kotlin 的 `?:` 只在 null 时兜底，写成
   `intOf(NEW_ICON) ?: intOf(ICON)` 会在 `NEW_ICON=0` 时错误地采用 0，
   已改为显式 `pickIcon()`，同时保证"两个 key 都没有"仍返回 null（不误判模式）。
4. **模式判定归位**：`isNavigating` 之前被红绿灯分支硬写成 `true`，巡航等灯时卡片会伪装成导航中。
   现在只有 10001 包通过 `modeOf(icon, all, prev)` 决定模式，灯数据不再参与模式判定。

### 7.4 本轮已落地并通过编译的代码改动

| 文件 | 改动 |
|---|---|
| `navi/AmapAutoParser.kt` | 全文重写：String-with-unit 解析、`NEW_ICON`/`ICON` 兜底、`ETA_TEXT`、`ROUTE_ALL_DIS` 进度、巡航 `lightsData` 真映射、灯字段不再整包丢弃、模式判定交 `modeOf` |
| `model/NaviInfo.kt` | `TurnIcon` 换成车机版协议枚举（删除未验证的 13~18）；新增 `routeAllDistM`/`etaText`/`lightDir`/`cruiseLightCount`/`lightUpdatedAt` + `progressPercent`/`lightAgeMs`；新增 `LightDir`；补 5 个超时常量 |
| `state/CarLinkState.kt` | 灯独立老化定时器（`LIGHT_HIDE_MS`） |
| `overlay/CarPipTheme.kt` | 删环岛未验证分支；`turnRotation` 按新枚举重写 |
| `overlay/NaviPipWindow.kt` | 签名加入新字段；绿灯无倒计时时显示"绿灯"文字而不是隐藏整块 |
| `windowfreer/…/AndroidManifest.xml` | `xposedminversion` 82 → **102**，并注明与依赖版本无关 |
| `windowfreer/…/WFreeHook.kt` | 修真实类型错误：`setPictureInPictureParams` 形参非空 |

**编译验证（本轮实测）**：`gradle assembleDebug` → BUILD SUCCESSFUL；
`aapt2 dump xmltree` 确认已装包 APK 内 `xposedminversion=102`。

### 7.5 M1 已落地内容（同一次构建内完成）

| 新增/改动 | 说明 |
|---|---|
| `settings/CarLinkSettings.kt` | 四区权重 + 区域内容 + 包名 + 外观 + 诊断开关；批量写入 + 内存快照 + 变更监听（避免读到半新半旧的权重） |
| `res/layout/activity_main.xml` | A / B / C / 侧边栏四区，权重由代码写回；侧边栏含时钟、两个悬浮开关、设置入口、链路指示 |
| `res/layout/card_navi.xml`、`card_music.xml` | 拆成可复用卡片；导航卡被 include 两份（A 区可放大），所以子 View 一律【从 include 根往下找】 |
| `res/layout/activity_settings.xml` | 5 组设置 + 诊断区（权限自检 + 协议原始回显 + 一键复制） |
| `ui/MainActivity.kt` | 重写为四区桌面；`applyLayout()` 做权重合法性兜底（四者全 0 时强制保留侧边栏，避免白屏） |
| `ui/SettingsActivity.kt` | 新建设置页 |
| `res/values/themes.xml` | 主题父级 `Theme.AppCompat.*` → `Theme.MaterialComponents.NoActionBar`（Material 组件必需），新增卡片/文字样式 |
| `navi/UnitStrings.kt` | 把「带中文单位的字符串 → 数值」抽成纯 Kotlin，**因而可跑 JVM 单测** |
| `app/src/test/.../UnitStringsTest.kt` | 18 个用例锁死 6.2/6.3/6.4 的修复：单位串、图标枚举、导航/巡航灯语义、进度百分比 |
| `AndroidManifest.xml` | 注册 SettingsActivity；`<queries>` 补 `com.netease.cloudmusic.iot`，并写明不用 `QUERY_ALL_PACKAGES` 的理由 |

**M1 自己抓到并修掉的一个布局错**：首版把内容列写成 `layout_weight="1"`，
侧边栏滑块最小档位 1 就变成 1:1 —— 侧栏直接吃掉半屏。
现改为内容列固定基准 10、侧边栏 0..8，占比上限 44%，并把这个比例写进设置页说明文字。
（这类问题单测抓不到，是复查 XML 时发现的。）

**验证结果**：`gradle :app:testDebugUnitTest` → tests=18 failed=0；
`gradle assembleDebug` → BUILD SUCCESSFUL，`app-debug.apk` 5,807,731 B（versionName 0.2-m1）。

**两处刻意的设计取舍**（避免以后被当成"随手就能改"的东西）：
- 诊断条默认【关】：普通用户不该在桌面看到 `icon=9 seg=55m` 这种字段串，
  要看得去设置页「诊断」分组开；协议回显面板本身完整保留。
- A 区占位文案不出现 "VirtualDisplay / M3" 这类里程碑黑话，
  改成用户能理解的一句话，同时不假装功能已可用。

### 7.6 M1 之后仍然存在的唯一硬阻塞

6.12 那条没变：**没有车机版高德 APK，广播链路在这台手机上收不到任何数据**。
所以 0.2-m1 装机的正确预期是：桌面四区与设置全部可用、音乐卡可用（走 MediaSession）、
导航卡在真机上是"等待高德车机版广播" —— 这不是 bug，是数据源缺位。
下一步真正能让导航卡出数据的只有 M2a（读高德手机版的前台导航通知）。

**（本条已被第八部分作废：车机版 APK 已直接拿到，决策 #0 关闭。）**

### 7.7 0.2-m2：设置项全接线轮（versionCode 3）

M1 遗留的"存了但没消费"的设置全部接上，外加一处语义修复：

| 项 | 落点 | 说明 |
|---|---|---|
| `musicPackage` | `media/CarMediaListenerService.rescan()` | 只接受目标包会话；目标已选但会话缺席时发布**空标题占位 MusicInfo** 而不是 `clearMusic()`，区分"没在播"和"链路断了"两种状态 |
| `minimalStyle` | `svc/DesktopService.ensureNavi/ensureMusic` → `PipWindow.show(startCollapsed)` | 最小化样式=卡片以折叠态出场；`show()` 加了带默认值的形参，旧调用零改动 |
| A 区"待实现"文案 | `ui/MainActivity` L207-209 | A 区选"镜像"与选"导航"给不同的等待文案，不再一句话糊两种状态 |
| `accent` | 常量表（CarLinkSettings）+ SettingsActivity `rg_accent` + MainActivity.applyLayout | 强调色存 **0xAARRGGBB 整数值本身而非序号**；applyLayout 里必须先 `CarPipTheme.accentColor = l.accent` 再 render（顺序敏感，有注释） |

**本轮抓到并修掉的一个语义 bug（子代理接线时引入）**：绿灯色被接到了用户强调色上
——选粉色主题就会得到"粉色绿灯"，交通信号语义色**绝不许**跟主题联动。
修复：`CarPipTheme.lightColor()` 恢复固定三色（绿 0xFF30D158 / 黄 0xFFFFD60A / 红 0xFFFF453A），
强调色只进非语义装饰（导航进度条 `progressDrawable.setTint`）。

**已知冗余**：`CarLinkSettings.mirrorImplemented()` 恒为 false 且零消费——M4 接 VirtualDisplay 时再消费或删。

**验证（本人重跑定论）**：`gradle :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL，
测试 **4 个类 / 18 个 @Test / 0 失败**（UnitStringsTest 9、TurnIconTableTest 3、
TrafficLightSemanticsTest 2、NaviProgressTest 4；4 个类同定义在 `navi/UnitStringsTest.kt` 一个文件里，
Kotlin 允许一文件多类）。`app-debug.apk` 5,806,739 B。

---

## 第八部分：官方发源端取证（车机版高德 V950 + AutoLite + 手机版 17.00 + CarWith 4.0.4）

这一轮的性质和前面所有部分都不同：此前所有协议结论的最强证据是
**第三方消费端**（Nav-Link）和文档转述，本轮第一次拿到**发源端本身的代码**——
从 auto.amap.com 官方直链下载的车机版高德，反汇编后逐字段核对。
之前所有"文档说 / README 说"的条目，凡是与发源端代码冲突的，一律以代码为准。

### 8.0 取证样本

| 样本 | 路径 / 标识 | 大小 | 结论用途 |
|---|---|---|---|
| 高德车机版 V950 | `/data/work/amapauto-V950.apk`，com.autonavi.amapauto **9.5.0.600013**，sha256 前缀 f2855369 | 154,947,836 B | 协议权威发源端 |
| 高德车机版 AutoLite | `/data/work/amapauto-lite560.apk`，com.autonavi.amapautolite 5.6.0.600021（armeabi-v7a） | 83,046,921 B | 协议版本对照（旧版无红绿灯字段） |
| 高德手机版 17.00 | `/data/work/amap-mobile-1700.apk`，com.autonavi.minimap 17.00.0.2005（targetSdk35，8 个 dex） | 195,437,758 B | 验证手机版是否发广播 |
| 小米 CarWith 4.0.4 | 仓库内 `com.miui.carlink 4.0.4-20260522`，classes8.dex 反汇编 | — | 导航卡片数据模型的第二个先例 |

V950 主 dex 反汇编语料 2,499,230 行（`/tmp/probe.dis`），本轮所有行号引用均指向该文件。

### 8.1 KEY_TYPE 权威表（发源端实测，替代此前所有转述）

| KEY_TYPE | 语义 | 证据位置（probe.dis） |
|---|---|---|
| **10001** | 诱导/导航数据包（GuideInfoProtocolData 全字段） | L2057618 |
| **60073** | 红绿灯倒计时（RspTrafficLightsCountdownInfoModel） | L2109498（见 8.2 陷阱） |
| **99999** | 进程数据（KEY_PROCESS_DATA / KEY_REQUEST_CODE） | 发源端字面量 |
| **10019** | 路口放大图（EXTRA_CROSS_MAP） | 发源端字面量 |
| **10119** | WIDGET_STATE | 发源端字面量 |
| **10008** | 定位回传 | 发源端字面量 |
| **10121 / 13034 / 20202** | 次要/测试通道 | 发源端字面量 |

此前文档只确认 10001/10019/10008；60073 与 99999 是本轮新增的权威确认。

### 8.2 dexdump 常量陷阱（本轮最重要的方法论教训）

**第一次搜 60073 得到"零命中"，是我的假阴性**。dexdump 把 32 位 `const`
指令按 float 渲染：

```
const v2, #float 8.41802e-41 // #0000eaa9
```

`0x0000EAA9` = 60073。用 `#int 60073` 正则永远搜不到。
**教训：在 dis 语料里找 KEY_TYPE 常量，要搜十六进制注释形式，不是十进制。**
（`const/16` 小常量走 `#int` 形式，`const` 大常量走 float 渲染——两种都要搜。）

### 8.3 60073 红绿灯字段（发源端逐字段确认）

来自 RspTrafficLightsCountdownInfoModel，全部 int：
`trafficLightStatus`、`dir`、`redLightCountDownSeconds`、`greenLightLastSecond`、**`waitRound`（新发现）**。

三个关键点：
1. **`trafficLightStatus` 是纯透传**（iget→return→putExtra，Java 层无任何映射代码）
   ——枚举语义定义在 native 层，**Java 反汇编无法获得，必须真机抓包**（未知项 #1）。
2. `greenLightLastSecond` 有版本门槛：仅当 `ib.t()`（解析版本号）≥ 810 才发送。
   V950=950 → 会发。这也解释了旧版 AutoLite 5.6 完全没有红绿灯字段。
3. `waitRound`（等待轮数，即"等几个红灯"）是 M1 卡片可以展示的增量信息，待真机确认语义。

### 8.4 巡航 lightsData：在本版发源端是死路

`lightsData`（Nav-Link 解析的巡航红绿灯 JSONArray 载体）在 V950 **和** AutoLite 中
全 APK 1733 个条目 **0 命中**。结论：巡航灯的数据载体在 9.5 时代已换或已下线，
**当前版本巡航灯走什么通道 = 未知项 #5（真机）**。M1 里 `parseCruiseLights` 保留
（对旧版/其他车机版仍可能有效），但对 V950 不要指望它出数据。

### 8.5 图标管线（convertIcon / switchIcon）

发源端 `GuideInfoProtocolData.convertIcon()`（L1705121）存在一张**Java 层重映射表**：
21/22/24→11，23→12，25/26/28→17，27→18（default 11）；
`switchIcon()`（L1708766）按配置开关 65→4，66→5；`NEW_ICON` 为 native 直传。
含义：我们枚举表（2/3/4/5/8/9/10/11/12/15）里的值只是重映射后的输出集，
**输入全集和 65/66 的触发条件在 native，属未知项 #2（真机）**。

### 8.6 `_AUTO` 字符串在 native 格式化 —— M1 设计被正名

发源端 Java 只是转发 native getter 返回的**已含中文单位的字符串**（如"500米后"），
拼接发生在 native 层。这从发源端确认了 M1 的两件事：
`_AUTO` 字段按 String-with-unit 解析是对的；`UnitStrings` 解析器是必需而非多虑。

### 8.7 RECV 控制通道：导出且无权限

`AmapAutoBroadcastReceiver` **exported=true 且无 permission** ——任何应用都能向
车机版高德发控制广播。分发主链 `se.onReceive → eq`（native/AIDL），
但 Java 层确有 6 处 `getIntExtra("KEY_TYPE")` 处理（此前子报告"Java 层无解析"的说法**过头了**，已纠正）：
`j10`（10042/10043/10057/12004/13005：家/公司卡、DEST、IS_START_NAVI、搜索参数）、
`md0`（10064/12006：设置项）、`hd.c`（10018）、CheryFX11 `shouldSendBroadcast` 门槛（10001/10002/10019）。
**完整 RECV 命令表 = 未知项 #3（真机 + 继续反汇编 j10/md0）**。
这同时是安全注记：CarLink 未来发控制广播没有任何权限障碍。

### 8.8 官方文档现状

`lbs.amap.com/api/amapauto` 与 `/api/amap-auto/guide/android/navi` 均已 **404（下线）**。
CSDN 上的官方 PDF 文库镜像仍可用，提供：10048 昼夜模式（EXTRA_DAY_NIGHT_MODE 0自动/1白天/2黑夜）、
10012 POI 上图（EXTRA_NAME String / EXTRA_LAT double / EXTRA_LON double / EXTRA_DEV int）、
10008 定位，以及关键操作细节：**向被强制停止的地图发广播必须带
`FLAG_INCLUDE_STOPPED_PACKAGES`**。gitcode 上的"AmapAuto协议.zip"仓库是空壳 bait，勿再引用。

### 8.9 手机版双版本确认：不发广播

最新官方手机版 17.00.0.2005（版本号取自官方 JSON
`mapdownload.autonavi.com/apps/apps/amap-official/amap-official/mobile_and.json`）
8 个 dex 全扫，协议字符串 **0 命中**；加上此前 16.16 的同样结论，**双版本确认手机版永不发
AmapAuto 广播**。但 `ExtraScreenNotifyService`（外屏通知通道）在 classes3/5/6.dex 仍在
——M2a（读手机版导航通知）路线依然成立且是手机端唯一原生数据通路。

### 8.10 CarWith 4.0.4：反射接管先例（用户指正后补上的分析）

此前我误判"CarWith 无导航卡片实现"（只 grep 了协议字符串），实际它带完整的
`net.easyconn.carman.bridge.*` 体系：
- **AMapReflectBridge**：单例，`Class.forName("%sImpl")` 反射装配；
  `AMapReflectBridgeImpl` **不随此 APK 出货**（也无 DexClassLoader）——
  即这是"接管高德进程内数据"的**框架先例，实现在别处**，不能说它是第二个协议消费端。
- **BMapBridge 卡片模型**：NavigationInfo{currentRoad, remainDistance:I, remainTime, speed,
  speedLimit, naviGuideInfo, enlargeMapInfo, highwayPanelModel}；
  BNaviGuideInfo{distance:I, roadName, **turnIcon:Bitmap**, turnIconName, exitRoad}；
  EnlargeMapInfo{turnIcon:Drawable, **remainDistance:String + remainUnit:String**, progress:I}；
  NaviDataInterface ~35 回调。传输走 UnixSocket/NetSocket（easyconn）。
- **对 CarLink 的两点直接价值**：① 进程内反射/接管路线有量产先例，佐证 M2a 备选路线的可行性；
  ② `remainDistance:String + remainUnit:String` 分离与 `progress:I` 的设计，
  与我们的 NaviInfo/UnitStrings/卡片模型**同构**，是第二个独立的设计验证。

### 8.11 V950 装车可行性初判（未验证）

arm64-v8a、minSdk21、**targetSdk24**、无 `android.car.*` 依赖，
导出 `MainMapActivity` + `androidauto://launch|poi|showTraffic` deeplink。
在 root 手机上安装运行的可能性高，但**没上真机之前只是初判**。

### 8.12 收口后剩余的纯真机未知项（6 条）

1. `trafficLightStatus` 枚举值（native 定义，Java 纯透传）
2. 转向图标全集 + 65/66 重映射触发条件
3. RECV 控制命令完整表
4. `waitRound` 的确切语义
5. V950 巡航灯的真实载体（lightsData 已死）
6. `_AUTO` 单位模板（native 拼接规则，影响解析兜底）

### 8.13 决策与路线更新

- **决策 #0（车机版 APK 从哪来）关闭**：官方直链可得，已入库两个版本。
- 广播路线（M2b）的前置条件从"找 APK"变为"真机装上 V950 验证可运行 + 抓 6 项未知"。
- M2a（手机版通知解析）优先级不变：它不依赖车机版能否安装，是确定性更高的通路。
- 两条线并行推进；广播链路在真机验证前，桌面导航卡的装机预期与 7.6 相同（占位等待态）。

---

## 第九部分：车联助手 X 全面反编译（com.leting 3.0.0，单 dex 4.4MB / 4264 类）

触发：用户真机打回 0.2-m2（"难看死了/两个软件/没参考界面/没参考设置功能"），
要求全面反编译车联助手、界面与设置全面复刻。本节是反编译的完整结论，
证据链：aapt2 xmltree（manifest/布局）、dexdump -d（类与方法签名）、aapt dump strings + 自写 arsc 解析（设置项标题）。

### 9.1 包体结构

- 单 classes.dex（4,426,680 B），无加固、无动态 dex（assets/dexopt 只是 ART baseline profile）。
- 自有代码 com.leting 41 类 + com.example.{gaodehook,wangyihook} 16 类，其余混淆进单字母包。
- **单包双身份实锤**：manifest 同时有 MAIN/LAUNCHER+HOME 的 MainActivity 与
  xposedmodule/xposeddescription/xposedminversion/xposedscope 四件套。
  我 0.2-m2 说"两个 APK 是 LSPosed 硬规则"是编造的约束，用户纠正正确。
- assets/xposed_init 三行入口：com.leting.xposed.xphook、com.example.gaodehook.HookEntry、
  com.example.wangyihook.HookEntry。

### 9.2 组件全表（manifest 实测）

- Activity 16 个：MainActivity[HOME+LAUNCHER]、OneUiHomeActivity（三星 OneUI 风格第二桌面）、
  SettingsActivity、welcome.{StartActivity,IntroducttoryActivity}、
  pip.PipAnchorActivity、pip.TaskMover、auto.{MainActivityTransTrans,TouchServiceLauncher,SystemUIPlugin}、
  notouch.{SlaveActivity,MirrorDisplay,RequestPermissionActivity}、deeplink 4 个、adapter.MyFragmentDisplayer。
- Service 6 个：MyAccessibilityService、NotificationListener、MediaPlaybackService、
  autostart.Detect、notouch.MediaService、auto.TouchService。
- Receiver 2 个：BootCompleteReceiver、MusicServiceNotificationReceiver。
- 组件数只有 25 个，但功能极密——大量能力靠 Xposed 注入与 root shell 实现，不靠组件。

### 9.3 权限表暴露的实现路线（29 条，关键 9 条）

| 权限 | 用途推断 |
|---|---|
| INJECT_EVENTS（签名级） | 触摸注入 → 非触屏车机操作手机 App（notouch 包） |
| CAPTURE_SECURE_VIDEO_OUTPUT（签名级） | 抓安全画面 → 镜像投屏不怕 FLAG_SECURE |
| FORCE_STOP_PACKAGES（签名级） | 清理后台（小白点"按钮-清理后台"） |
| WRITE_SECURE_SETTINGS（签名级） | 无障碍开关/通知监听直写系统表（RootSettings.shellPut） |
| STATUS_BAR_SERVICE（签名级） | 覆盖/隐藏车机状态栏（"覆盖车机导航栏/隐藏车机导航栏"） |
| KILL_BACKGROUND_PROCESSES | 同上清理后台 |
| FOREGROUND_SERVICE_MEDIA_PROJECTION | VirtualDisplay 镜像采集 |
| PACKAGE_USAGE_STATS | 应用使用统计（自动启动判定） |
| QUERY_ALL_PACKAGES | 应用抽屉/指定应用列表枚举 |

**结论：它常态以系统应用或 root 提权方式运行**，签名级权限不是普通安装能拿到的。
这解释了它的功能上限，也划定我们的复刻边界：root shell 能等效的（WRITE_SECURE_SETTINGS、
FORCE_STOP 类）我们跟；纯签名级的（STATUS_BAR、CAPTURE_SECURE）标记为"手机版暂未接管行为"。

### 9.4 xphook 36 方法——真实工作原理（本轮最重要发现）

方法名全暴露（dexdump 实测）：
- 尺寸接管：hookAdjustSizeToAspectRatio / hookTransformBoundsToAspectRatio /
  hookGetSizeForAspectRatioFloat / hookGetSizeForAspectRatioSize / hookSetBoundsStateForEntry /
  scaleToTarget / TARGET_SIZE_PERCENT
- 高德：hookAmapPip / isAmapPip（把高德塞进 PiP/自由窗）
- 网易云：NETEASE_PINNED_ACTIVITIES、hookNeteaseActivityStartActivity* 4 个、
  injectNeteaseDisplayId、getNeteaseCurrentDisplayId、shouldPinNeteaseIntent（跨屏投放）
- 常量：AMAP_PACKAGE / NETEASE_PACKAGE / SYSTEMUI_PACKAGE

**它不是"解析数据画假卡片"，是"Xposed 改窗口语义把真 App 缩成小窗 + SurfaceView 捕获真画面"。**
显示层：ModSurfaveView（pip.xml 全屏）+ MainSurfaceView。
我们的对应物：WFreeHook 已覆盖 H1-H3（多窗口谎报/方向/PiP 参数），
缺"自由窗尺寸接管"（9.4 第一组 hook）与"捕获显示"（VirtualDisplay/MediaProjection，M4 计划内）。
广播解析卡片路线降级为：轻量替代（不依赖抓屏权限），两条腿并存。

### 9.5 设置系统全量清单（13 屏 / 90+ 项，key 逐字记录）

res/xml 13 个 root_*_preferences.xml，全部 OneUI* 自定义 Preference。完整 key 表（复刻依据）：
- **Ui（root_ui_preferences）**：areaA/areaB/areaC(List)、mirror_app_package、mirror_app_package2、
  weightA/weightRight/weightB/weightC、ui_mode(List)、cusbackground、cardopacity、radius、margin
- **Boot（root_boot_preferences）**：exp_autostart、exp_package、jump、jump_pkg_lp、jump_always、
  fake_start、fake_start_configure
- **Music（root_music_preferences）**：音乐控制器、映射服务、switch_preference_1(兼容模式)、
  lock_music_player、lock_music_player_pkg、showalbum、album_musk_alpha
- **Notification（root_notification_preferences）**：notification_switch、notification_switch_auto、
  notification_display_seconds、notification_opacity、notification_whitelist(多选)、
  play_ringtone、play_ringtone_volume、msg_box_icon_size、msg_box_font_size
- **FloatMenu 小白点（root_floatmenu_preferences，21 项）**：touchassistant、touchassistant_auto、
  圆点模式、ta_dot_autohide_time、ta_reverse、ta_vertical、show_move、show_home、ta_drawer、
  ta_drawer_autohide、ta_drawer_autohide_time、show_back、show_app、ta_favo_app、show_kill、
  ta_icon_size、ta_opacity、ta_rgb
- **GodMode 导航栏助手（root_god_preferences，11 项）**：godmode、godmode_auto、godmode_slot_1..5、
  godmodeiconsize、godmodeautohide、godmodeautohidetime、godmode_opacity、godmode_rgb
- **Fs 全屏助手**：fs、fs_auto
- **Apps 应用抽屉**：launcher_addapp、launcher_col、launcher_icon_size、launcher_font_size、showlabel
- **Exp 实验室**：exp_autolock、exp_autoblackscreen、exp_autolockbyphone、exp_autolockbyphone_number
- **AdaptiveMode 应用兼容**：fake_start + 应用兼容设置入口
- **AssPatch 无障碍防脱**：ass_keeper
- **主菜单（root_preferences）**：18 个跳转项
- **Dev/Donate/Group**：fragment_dev/donate/group

对照我们 0.2-m2 的设置页：**5 组 14 项 vs 它的 13 屏 90+ 项**——"空壳子"的批评成立。

### 9.6 界面结构（复刻蓝本）

- activity_main：壁纸 ImageView 全屏 + 横排容器（A 区 FrameLayout | 右侧列 B/C 两个 FrameLayout）
  ——与我们"内容列 A + BC 行"权重骨架同构，我们的布局方向是对的，差的是皮和功能。
- 字符串里就叫"A区域/B区域/C区域"；musicplayer.xml：封面 FrameLayout(Album_musk 遮罩) +
  标题 18sp + 5 个 ImageButton 控制排；pip.xml：ConstraintLayout + ModSurfaveView 全屏。
- OneUI 设计语言实测值：底色 #F6F6F6、卡片 #FCFCFC、主文字 #010101、次文字 #8C8C8C、
  强调蓝 #0381FD、描边 #DEDEDE、卡片圆角 20dp、外边距 20dp、图标 30dp/圆角 12dp、
  抽屉格子 106x74dp、app_bar 180dp。
- 0.2-m3 已完成：colors/dimens/themes/card_navi/card_music/activity_main 全部换 OneUI 皮
  （色值尺寸全部取自上面实测，非目测）。

### 9.7 复刻范围裁定（哪些抄、哪些不抄、为什么）

| 车联助手功能 | 裁定 | 依据 |
|---|---|---|
| 桌面布局配置（区域/权重/圆角/透明度/间距/背景） | **全抄** | 纯客户端，已有数据通路 |
| 音乐控制器/锁定播放器/专辑封面 | **抄** | MediaSession 已具备 |
| 通知助手（白名单/时长/透明度/提示音） | **抄** | NotificationListener 已注册，缺 UI 与转发 |
| 启动设置（前置/后置启动、总是跳转） | **抄** | Activity 启动 + 无障碍/usagestats 可做 |
| 小白点/导航栏助手/全屏助手 | **设置照建、行为标注未接管** | 依赖 INJECT_EVENTS/STATUS_BAR 签名级权限 |
| 应用兼容模式（假启动） | 暂缓 | 针对 CarLife 生态，无用户场景 |
| 无障碍防脱/实验室（自动锁屏/自动拨号） | 设置照建标注未接管 | 依赖无障碍深度定制 |
| 消息盒子/黑屏按钮 | 二期 | 依赖签名级或投屏 |
| 镜像（画中画1/2） | M4 主线 | MediaProjection 可等效（root 下无 FLAG_SECURE 问题待验证） |

### 9.8 方法论记录（本轮踩的坑）

- aapt2 dump xmltree 的属性引用是 @0x7f11xxxx 资源 id，**低 16 位即字符串池索引**，
  配 aapt dump strings 的有序输出即可全部反查——不需要自写 arsc 解析器（我写了两次都错，
  UTF-8 flag 位是 0x100 不是 0x10，最终用索引对表法 5 分钟解决）。
- dexdump 的类块里 method name/type 成对相邻行，按行配对提取比正则跨块可靠。

---

## 第 10 部分：0.2-m3 构建落地（设置系统全量复刻，本轮我亲手做完）

> 子代理交付为零（无 preference 依赖、无 res/xml、SettingsActivity 未改造），
> 用户"又卡了吗"追问后我接手全部实现。以下为本轮真实产出，非计划。

### 10.1 单一存储文件（根治"改了读不到"空壳 bug）

CarLinkSettings 从自定义文件 `carlink_desktop` 切到
`PreferenceManager.getDefaultSharedPreferences`（即 `<包名>_preferences`）。
PreferenceFragment 默认就写这个文件，桌面读、设置写天然同一份。
`setDefaultSharedPreferencesName` 这个 API 不存在（我一度以为有，回退了 CarLinkApp 的引用）。

`load()` 读取端全部走容错扩展 `int()/str()/bool()`：
- ListPreference 存 String、EditTextPreference 存 String，但 Layout 字段是 Int →
  `int()` 先 getInt，ClassCastException 再 getString().toIntOrNull()。
- `str()` 也 try/catch，防止历史脏类型（比如 edit() 存进 Int）读崩桌面，宁可用默认值。

### 10.2 枚举语义变更（必须核对消费端）

A 区枚举位移：A_NONE=0 / A_MIRROR=1 / A_PIP2=2 / A_NAVI=3 / A_MUSIC=4（旧版 A_NAVI=2）。
MainActivity.applyLayout 只引用命名常量、不引用裸数字，故位移安全；已逐处核对。
候选项裁剪：B/C 只列当前渲染支持的组合（B=关闭/导航，C=关闭/音乐），
不给"设置生效但画面不换"的假选项——镜像/交叉组合留到 M4 有实现再放开。

### 10.3 13 屏设置系统（res/xml 11 个文件，key 逐字对齐 9.5 清单）

- pref_main：跳转式主菜单，nav_* key 由 ScreenFragment.NAV 分派到子屏（不存值）。
- pref_ui：areaA/B/C、weight*、mirror_app_package(2)、music_pkg、ui_mode、accent、
  radius、margin、cardopacity、cusbackground、diag_on_desktop。**桌面真正读的屏**。
- pref_boot/music/notification：功能键全建，已实现的接行为、未实现的标
  "（手机版暂未接管行为）"（裁定见 9.7）。
- pref_floatmenu(17)/godmode(13)/fs(2)/apps(5)/exp(5)：签名级权限依赖项，设置照建+标注，不做假开关。
- pref_dev：root 一键授权（RootAuth）+ 手动权限跳转 + 链路自检实时刷新 + 原始数据弹窗。

通用类 ScreenFragment 承载全部子屏（参数 xml 决定加载哪份），不写 13 个子类。
AppListPrefs 动态填"选已安装应用"的 ListPreference（getInstalledApplications +
getLaunchIntentForPackage 过滤，后台线程枚举回填主线程）。DevActions 迁移旧诊断逻辑。

### 10.4 默认值播种（两处默认必须同源）

多屏独立 xml，`setDefaultValues` 不跨文件递归 → CarLinkApp.onCreate 里逐屏播种。
否则首次进入 ListPreference 摘要空、桌面靠各自兜底，两个默认值会漂移。

### 10.5 极简态单一来源（消除重复控件）

`minimalStyle = (ui_mode==1) || minimal开关`，去掉 pref_ui 里重复的 minimal Switch；
ui_mode 成为唯一开关，保留 minimal 键只为兼容 0.2-m2 老用户已开的值（or 关系）。

### 10.6 QUERY_ALL_PACKAGES 的正当性

本应用带 HOME 分类、是真桌面，应用列表/抽屉/通知白名单必须枚举全部已安装应用——
Google 官方为启动器类保留的正当用途（车联助手同样声明）。加了 tools:ignore 与说明注释，
不推翻 m2"不滥用 QUERY_ALL_PACKAGES"的决定，而是给"桌面"这一具体身份划出豁免边界。

### 10.7 构建与验证事实

- `gradle :app:assembleDebug` 绿；踩一个真实编译错：Intent.loadLabel 解析不到
  （AppListPrefs 用 ApplicationInfo.loadLabel 修正）。
- `gradle :app:testDebugUnitTest` 18/18 全绿（4+2+3+9，与 7.7 复核一致）。
- aapt2 核验：packagename com.carlink.desktop、versionName 0.2-m3、
  xposedminversion value=93（不是 102）、xposedmodule/xposedscope 齐、
  11 份 res/xml/pref_*.xml + assets/xposed_init 已烘焙进 APK。
- 产物：/data/deliver/carlink-desktop-0.2-m3.apk（8.9MB）。

### 10.8 本轮【真实生效】vs【仅存配置待接线】诚实清单

真正改桌面行为的：areaA/B/C、weightA/B/C/Right、radius、margin、cardopacity、
ui_mode(→极简)、accent、diag_on_desktop、mirror_app_package、music_pkg、showalbum、
root 一键授权、诊断弹窗、启动跳转项（存值+读值通路在，桌面执行器 M4）。
仅存配置、行为待接线的：notification 转发本体（二期）、应用抽屉本体（二期）、
floatmenu/godmode/fs/exp（签名级权限，长期标注未接管）。
用户批评的"空壳子"针对的是【连配置都没有】；本轮把 90+ 项配置全建、并让 12+ 项真正驱动桌面。

---

## 第 11 部分：双画中画的真实机制（本轮挖实 + 等价复刻完成）

### 11.1 起因：我犯过的两个错，以及用户的真机证词

- 错误一：我曾断言"手机上做不了任意 App 的画中画接管"，并顺手把 SurfaceView
  删掉、判 MediaProjection 方案为"递归烂摊子"。
- 错误二：被推翻后第一版 hook 只做了"目标 bounds 为空时兜底"，仍然保守到不可用。
- 决定性证词：用户在真机上测过——车联助手能把【任意软件】hook 进画中画，
  而且有两个画中画、还能分别设置大小。这把"能不能"的问题直接关掉了，
  剩下的是"它是怎么做到的"，答案只能在 dex 里找。

### 11.2 dexdump + manifest 反解证据链（/tmp/clzs_dexdump.txt，33MB，逐条可复查）

| 证据 | 位置 | 含义 |
|---|---|---|
| `Lcom/leting/pip/PipAnchorActivity;` | dexdump Class #2809 | 透明锚点 Activity：onCreate 只有 8 个 code unit，纯占 task 槽 |
| manifest：taskAffinity=`com.leting.pip.anchor`、excludeFromRecents=true、exported=false、透明主题 | aapt2 反解 | 锚点不进最近任务、不可外部拉起 |
| `Lcom/leting/xposed/xphook;`：`hookAmapPip` / `hookNeteasePip` / `isAmapPip` / `injectNeteaseDisplayId` | 844371/844566/844669/844632 行 | 双 PiP 各自一条 hook 路径；网易云那条额外注入 displayId（跨屏） |
| `AMAP_PACKAGE` / `NETEASE_PACKAGE` / `NETEASE_PINNED_ACTIVITIES` 常量 | 844122/844127/844132 | 画中画1=高德车机版，画中画2=网易云，白名单式 Activity 收紧 |
| `TARGET_SIZE_PERCENT = 0.6f`、`scaleToTarget(Size,int)`、`hookAdjustSizeToAspectRatio` | 844152/844721/844349 | "可设置具体大小"的落点：按百分比把目标缩放到锚点 |
| 返回 `Landroid/app/ActivityOptions;` 的方法签名 `(Landroid/os/Bundle;)Landroid/app/ActivityOptions;` | 844249/844700 | hook 的框架层原语就是 ActivityOptions（其 getBounds 携带 task bounds） |
| 全 APK 字符串池零 `resizeableActivity` 声明；框架侧只碰 `android.app.ActivityOptions` 与 `android.util.Size` | pool.tsv 复查 | 证明它【不依赖】目标可调整大小——任意 App 都能被接管的原因 |

结论（0.2-m7 更正，见 11.8）：~~车联助手的画中画不是投屏（MediaProjection）~~ —— **这句是错的**。
反编译 smali `b1/b.smali`（`b1/c` PipUI 的 `SurfaceHolder.Callback`）实测：真正的双画中画走的是
`DisplayManager.createVirtualDisplay("gdj"/"gdj2", w, h, dpi, flags, surface, cb, handler)` ——
**VirtualDisplay 把 SurfaceView 的 Surface 当输出**，目标 App 起进这块虚拟屏后画面直接渲染进桌面区域。
`ActivityOptions.getBounds` 那条 hook 是【SystemUI 侧的真实 Android PiP 尺寸算法】
（AmapPipHook：hook `com.android.wm.shell.pip`，把高德强制成固定比例），
与"root 悬浮画中画"是两条独立路径，不是接管的主体。TaskMover/c1-h 负责把任务迁移/起进虚拟屏。

### 11.3 我们的等价实现（0.2-m7 全量重写：root 悬浮画中画）

> 修订（0.2-m7）：本节此前描述的方案（setprop + 目标进程 hook `ActivityOptions.getBounds`）
> 是【架构性错误】，m4~m6 三版真机全部"有占位无画面"。复盘见 11.8。现按参考版实测机制重写。

参考版机制（b1/c PipUI + b1/b Callback + b1/d 触摸 + c1/h 部署，全部核验 smali）：
1) 区域里一块 SurfaceView（res/layout/pip.xml 的 myscreen）；
2) surfaceCreated 里 `DisplayManager.createVirtualDisplay("gdj", w, h, densityDpi, 0, surface, …)`
   ——@SystemApi、无 token，靠签名权限（CAPTURE_SECURE_VIDEO_OUTPUT 等）合法；
3) 部署 `c1/h.d`（API setLaunchDisplayId 优先）→ 失败 `c1/h.b` 用
   `su -c "am start --display <id> -f 0x10104000 -n <comp>"`；
4) 触摸 `su -c "input -d <id> tap|swipe …"`（≥10px 判滑、时长夹 50~2000ms）。

我们的合法等价（没有它的签名权限，用公开 API 走同一条链路）：

```
MainActivity（A/B 区各一块 SurfaceView：a_pip_surface / b_pip_surface）
  slotForRegionA/B → 期望槽态（撤销 → MirrorSlot.teardown 释放 VD）
  surfaceCreated/Changed → updateMirrorSlots →（无 token 先发起 MediaProjection 授权）
  MirrorSlot.deploy（后台线程）：
     MediaProjection.createVirtualDisplay("carlink-pipN", w, h, dpi,
         PUBLIC|OWN_CONTENT_ONLY|PRESENTATION, surface)   ← 公开 API 版，与 @SystemApi 版同效
     → RootAuth.launchOnDisplay: su am start --display <id> -f 0x10104000 -n <comp>
  surfaceChanged（尺寸变）→ VirtualDisplay.resize + setSurface（对齐 b1/b）
  surfaceDestroyed → setSurface(null) 只断输出，目标任务留在虚拟屏（对齐 b1/b）
  onTouch → MirrorSlot 串行队列 → RootAuth.runQuiet("input -d <id> tap|swipe")
```

- 授权链（Android 14 死顺序）：createScreenCaptureIntent → 用户点"立即开始" →
  先启动 `PipProjectionService`（foregroundServiceType=mediaProjection，独立于常驻
  DesktopService，避免开机自启时未授权启动即崩）→ startForeground 完成后才
  getMediaProjection → 才允许 createVirtualDisplay。root 侧 `appops set <pkg> PROJECT_MEDIA allow`
  由 requestPipAuthIfNeeded 预先执行，减少弹窗被拒。
- 双槽并行模型不变：槽1 固定 A 区、槽2 固定 B 区，`slotForRegionA/B` 纯函数与
  `bothSlotsCanActiveAtOnce` 测试保留；撤销/重部署互不影响。
- 模块 WFreeHook 的 H4（getBounds 覆盖）与 persist.carlink.pip.* prop 链路【整体删除】；
  H1~H3 窗口语义 hook 保留（它们对真实 PiP/方向配合仍有用），高德强制比例那条
  （AmapPipHook → hook SystemUI `com.android.wm.shell.pip`）不在本轮范围：
  镜像方案里目标在虚拟屏中就是整屏，尺寸由 VD 决定，根本不需要真实 PiP。

### 11.4 本轮同时接线的"待接线清单"（10.8 里点名过的）

| 设置项 | 行为落地 |
|---|---|
| notification_switch/seconds/opacity/whitelist | CarMediaListenerService→NotifBannerWindow 桌面顶部横幅；白名单纯函数可测；媒体通知不转发防刷屏 |
| exp_autostart | BootReceiver 读它，关了不自启 |
| exp_package | 应用真·回前台时拉起（AppForeground 前后台计数+700ms 内部切换过滤） |
| jump / jump_pkg_lp / jump_always | 真·退出应用时跳转；自家拉起外部 App 后 3s 内的后台不算退出（markSelfLaunch），跳设置页不算 |
| launcher_col/icon_size/font_size/showlabel/launcher_addapp | DrawerActivity 全量网格抽屉（HOME 桌面的核心缺口），常驻应用置顶 |
| switch_preference_1（媒体兼容） | 播放器不发 MediaSession 时从媒体通知兜只读歌名 |
| lock_music_player(+pkg) | 选 session 时锁定优先 |

### 11.5 对齐测试（防"空壳设置"复发的机制性闸门）

`SettingsAlignmentTest`：
1) **每个 res/xml 的 app:key 必须二选一**：在 CarLinkSettings.Keys 常量表 / DevActions
   动作键里被消费，或其控件块自带 `not_takeover`"手机版暂未接管行为"诚实标注。
   ——首跑就抓出 2 个漏网空壳（album_musk_alpha、play_ringtone_volume），已补标注。
2) 反向诚实检查：标了"未接管"的键不许已经进 Keys 表（防止"生效了却谎称没做"）。
3) areaA/B/C 的 entries/values 长度与枚举语义钉死（上一版 mismatch 崩对话框的教训）。
4) 双槽判定（含 `bothSlotsCanActiveAtOnce` 并行前提、空包名不建槽、旧值归一化）、
   白名单匹配等纯逻辑直接单测。总计 30/30 通过（含 NaviProgress/TurnIcon 等其他类）。

### 11.6 顺带清理

- 删除随 MediaProjection 废案遗留的 FOREGROUND_SERVICE_MEDIA_PROJECTION 权限；
  KILL_BACKGROUND_PROCESSES 一并移除（抽屉未做"清理后台"按钮，不留无主权限）。
- 删除无人调用的 `mirrorImplemented()` 假接口；未接管项统一去掉
  `useSimpleSummaryProvider`（它会覆盖"未接管"摘要，两处互相打架）。

### 11.7 m6：真机反馈"画中画不显示 + 为什么上下分屏"的根因与修复

用户装了 m5 后真机反馈（截图：A 区"接管中"文案在、但目标画面没出现；整屏上下分栏）。
复查定位到两个**独立**根因，都已修：

1. **钩子装晚了（画中画不显示的主因）**。`WFreeHook.hookPipSizeTarget` 旧写法在
   `handleLoadPackage`（进程启动那一刻）比对 `*_pkg` 决定装不装 bounds 钩子。
   但真实时序是"目标 App 先运行 → 用户才在桌面点接管下发 setprop"，
   那个早已启动的进程里**根本没有钩子**，prop 写了也没人读。
   改为：bounds 钩子**无条件安装**到每个被勾选进程，"属于哪个槽 / 是否开着 / 矩形多大"
   全部在每次 `getBounds` 调用时现读现比（`slotForPkg` + `rectOf`）。

2. **拉起被 root 成功卡死（次因，且完全静默）**。`updatePipTakeover` 旧写法把
   `ensureLaunch` 放在 `applyPipSlots` 返回成功之后——su 弹窗没点/超时/被拒时目标
   压根没被启动，A 区只剩占位文案，用户看到"点了没画面"。改为拉起与 root **并行**：
   先按 `setLaunchBounds` 直接启动（多数 ROM 标准分屏即生效），root+hook 只兜"无视 bounds"，
   互不阻塞；root 失败在诊断条显性提示，不再静默。

3. **横屏应左右分栏**。桌面骨架此前写死竖向（A 上、B/C 下），车机横屏下 A 被压成顶部扁条，
   既难看又可能让目标 bounds 尺寸不合法。改为按 `configuration.orientation` 翻转
   `contentCol`/`bcRow` 方向（横屏 A 占左、B/C 右侧上下排），权重按父容器主轴重设，
   并补 `onConfigurationChanged`（Activity 声明了 configChanges，旋转不重建，必须手动重排）。
   诊断条移出会被翻转的容器，挂到恒为纵向的 root 层。

4. **新增可读回诊断**：`RootAuth.readPipProps()` 把 `persist.carlink.pip.*` 当前值
   显示在"系统与诊断 → 链路自检"，用于区分"setprop 没写进去（su 问题）"与
   "写进去了但画面不动（模块/作用域问题）"——下次真机反馈可直接定位分叉。

（以上 1~4 的修复对 m6 本身是真实的，但 m6 真机仍无画面——因为整个方案是错的，见 11.8。）

### 11.8 m7 总复盘：m4~m6 为什么全错，以及这次是怎么找对的

用户证词："车联助手我已经验证过完全跑得通，我给你一仓库东西你都研究不透吗？"——
事实成立，且暴露我三个方法论错误：

1. **拿静态字符串池当全部真相**。11.2 的"不是 MediaProjection"结论只查了 dexdump 字符串，
   没读反编译 smali。而仓库里 `/data/work/dec/dec21` 是完整 apktool 反解（5451 文件），
   b1/b.smali 里 `createVirtualDisplay` 调用就在眼前。正确姿势：**报告 + smali 交叉核验，
   谁有可执行细节信谁**。
2. **没吃透"谁构造 ActivityOptions"**。getBounds 是桌面（launcher）进程构造、随
   startActivity 传给 AMS 的；在目标进程 hook 它的 getter 改不动 launcher 已定好的窗口。
   这一个常识性错误让整个 m4~m6 方向作废——m6 真机"prop 读回一切正常但无画面"就是这个信号。
3. **验证外包给用户**。多次让用户跑 adb 命令、看现象回报，而不是先把手里的 APK、
   报告、反解仓库翻透。用户提供的每份材料（pip-touch-analysis.html、carplay-reverse-engineering.html §6/§11、
   dec21）都早于我的猜测，应该先穷尽它们。

参考版机制全貌（本轮逐文件核验）：
- `b1/c`(PipUI)/`b1/e`(PipUI2)：pip.xml  inflate → ModSurfaveView(myscreen) + touchpad，
  densityDpi 取主屏，`b1/d`/`b1/h` 挂 OnTouchListener。
- `b1/b`(Callback)：surfaceCreated → `DisplayManager.createVirtualDisplay("gdj",…,flags=0,surface,…)`
  → 记下 displayId → `c1/h.d`（API move/launch）失败再 `c1/h.b`（`su -c "am start --display …"`）；
  surfaceChanged → `vd.resize + setSurface`；surfaceDestroyed → `setSurface(null)`（不断任务）。
- `b1/d`：DOWN 记点，UP 拼 `input -d <id> tap|swipe`（Δ≥10px 判滑、时长夹 50~2000），
  `Runtime.exec("su","-c",cmd)` 即发即忘（乱序风险实测存在，我们改串行队列）。
- AmapPipHook（hook SystemUI wm.shell.pip，TARGET_SIZE_PERCENT=0.6）服务的是【真实 Android PiP】
  那条路（高德必须强制比例才能固定尺寸），与 root 悬浮画中画互不相干。
- 它合法持有 @SystemApi VD 的资本 = 签名级权限（CAPTURE_SECURE_VIDEO_OUTPUT/INJECT_EVENTS/
  WRITE_SECURE_SETTINGS…manifest 全在）+ LSPosed 作用域含 systemui。我们没有它的签名，
  所以用公开 MediaProjection 通道拿等价的虚拟屏（Android 14 的 FGS+授权死顺序见 11.3）。

本轮改动清单（0.2-m7）：
- 新增 `mirror/MirrorSlot.kt`（VD 生命周期 + 触摸注入）、`svc/PipProjectionService.kt`
  （mediaProjection 类型 FGS）、RootAuth 增加 allowProjectMedia/runQuiet/launchOnDisplay，
  删除 applyPipSlots/readPipProps（prop 链路退役）；诊断改为 `readDisplays()`（数虚拟屏）。
- 布局：a_body/b_body 各加 SurfaceView（+半透明等待层），manifest 恢复
  FOREGROUND_SERVICE_MEDIA_PROJECTION + 新服务声明。
- WFreeHook：删 H4 与全部 pip prop 常量（注释写明为什么删，防回退）。
- 测试 30/30 绿（unit test 单变体合计：SettingsAlignment 12 + Navi 三类 4/2/3 + UnitStrings 9；
  `bothSlotsCanActiveAtOnce` 等并行前提用例不变），aapt2 核验
  PipProjectionService foregroundServiceType=mediaProjection(0x20) 已烘焙。

仍须真机验证的点（诚实清单）：
- 录屏授权弹窗是否出现/是否被 ROM 拦截（appops PROJECT_MEDIA 预授权后多数 Magisk 配置直接放行）；
- `am start --display` 在目标 ROM 上是否成功（失败时诊断条会给出色退出信息）；
- 触摸延迟（su exec 45~150ms，与车联助手同级）；虚拟屏里目标 App 的方向表现；
- 国产 ROM 对 MediaProjection 后台限制（需在电池优化白名单，DesktopService 已有自启链路）。
