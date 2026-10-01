# CarLink Desktop 逐轮复盘（0.2-m8，五轮制）

> 用户要求：全面复盘 5 次，每一次抓 bug、每一次自问自答、每一次修完验证。
> 本文按轮记录：**现象 → 自问 → 证据 → 结论 → 修复 → 验证**。
> 轮次之间互相独立，后一轮会复核前一轮的修复有没有引入新问题。

---

## 第 1 轮：真机截图反馈的直接根因

### 起点：用户 6 张截图给出的硬事实

| 截图 | 事实 | 推论 |
|---|---|---|
| 图6（车联助手） | 高德+网易云**铺满整屏、无留白、无标题** | 参考版是全出血布局 |
| 图4（我们） | 左高德右网易云**同时出画面** | ★ m7 镜像机制本身已经通了 |
| 图2/图5（我们） | 半透明"画中画镜像中…"压在真实画面上 | 遮罩没随画面恢复而收起 |
| 图1/图3（我们） | 诊断 `镜像: carlink-pip1 carlink-pip2` | 两块虚拟屏都建成功了 |
| 用户描述 | "点网易云会跳转，**再返回桌面就完全失效**" | 生命周期回归 bug |
| 用户描述 | "车机助手是强制横屏的，不会有这么多留白" | 缺 screenOrientation + 装饰过多 |
| 用户描述 | "车机助手有一个单独的设置图标" | 设置入口不对 |

### 自问 1：机制通了，那"返回后失效"到底断在哪一环？

顺着"离开桌面 → 回来"逐环排查：离开 → SurfaceView 的 surface 被销毁 →
`surfaceDestroyed` → `slot.detachSurface()` → **`ready = false`**（VD 和目标任务栈保留，这是刻意的）。
回来 → surface 重建 → `onSurfaceReady` → `updateMirrorSlots` → `deploySlot` →
`MirrorSlot.deploy` 走 resize 快路径（VD 还在、pkg 没变）→ `resize + setSurface` 成功 →
**`return true`，但没有把 `ready` 重新置回 true！**

于是回来后：
- `aPipWait.visibility = if (slot1?.ready != true) VISIBLE` → **遮罩永久压在画面上**（图2/图5 的直接解释）；
- 触摸回注判断 `if (s != null && s.ready)` → **永远 false，点击彻底失效**（"返回桌面就完全失效"的直接解释）。

**这就是第 1 号 bug：resize 快路径漏设 `ready = true`。** 一个赋值语句的缺失，
同时解释了用户的两个症状。修复：`MirrorSlot.kt` resize 分支 `lastSig = sig` 后补 `ready = true`。

### 自问 2：为什么 m7 的测试没抓到它？

因为它是**状态机时序 bug**，需要"部署→销毁 surface→重建 surface"这个序列才触发，
而单测没有 Robolectric/仪器化环境。**教训：这类 bug 只能靠"把生命周期序列在脑子里跑一遍"来防，
我 m7 复盘时只核对了"首次部署"路径，没核对"二次进入"路径。** 后续每轮复盘必须显式走查
"离开→返回"序列。

### 自问 3：遮罩还有第二个来源？

查 `deploySlot`：每次 surfaceChanged（包括 resize 快路径）都会先把等待层设 VISIBLE，
再等后台线程回来。resize 是毫秒级的，遮罩会闪一下——但配合 bug#1 就是"闪了再也不消失"。
修复：只有 `!s.ready`（本来就没就绪）才显示等待层。

### 自问 4：留白和"A·主内容"是谁造成的？

对照参考版 `res/layout/activity_main.xml`（逐行读）：
`sidebar w=1 | containerA w=6 | right w=4 { containerB w=4, containerC w=6 }`，
**根无 padding、容器无内边距、无标题、卡片间无 margin**。
我们的版本：根 padding 20dp + A 卡内 padding 14dp + "A · 主内容"标题 + 卡片描边 + 默认权重 3:2:2:1。
每一项都是参考版没有的自作装饰。修复：删标题、删两层 padding、卡片 strokeWidth/elevation=0、
默认权重改 6:4:6:1（pref_ui.xml 与 CarLinkSettings.load() 两处同源改）。

### 自问 5：强制横屏为什么漏了？

参考版 manifest 实测 `screenOrientation=0(landscape) + launchMode=2`，我们三个 Activity 都没写。
修复：MainActivity/SettingsActivity/DrawerActivity 全部 `android:screenOrientation="landscape"`。

### 第 1 轮抓到的 bug 清单（已修）

| # | 位置 | 症状 | 修复 |
|---|---|---|---|
| B1 | `MirrorSlot.deploy` resize 分支 | 返回桌面后遮罩压画面 + 触摸失效 | 补 `ready = true` |
| B2 | `MainActivity.deploySlot` | resize 时遮罩闪烁 | 仅 `!s.ready` 才显示等待层 |
| B3 | 布局 | "A·主内容"标题、20dp+14dp 双层 padding、描边 | 全删，对齐参考全出血 |
| B4 | 默认权重 3:2:2:1 | A 区只占 3/7，大片留白 | 6:4:6:1，两处同源 |
| B5 | manifest | 未强制横屏 | 三 Activity `screenOrientation=landscape` |
| B6 | 侧边栏 | 设置无独立图标 | 加 `ic_gear_fill`（从参考版复制） |

### 第 1 轮附带修复（开源对照发现）

对照开源车机桌面 **dw2lam/openlauncher**（GitHub，MIT，专为后装车机做的离线优先桌面）
的 `MediaListenerService.kt`，作者留了一条注释：*getActiveSessions 每次返回新
MediaController 实例，必须比 sessionToken 不能比引用，否则回调抖动*。
检查我们 `CarMediaListenerService.kt:196` —— 用的正是 `active !== chosen` 引用比较，
**同一个坑**。修复为 `active?.sessionToken != chosen.sessionToken`。

---

## 第 2 轮：权限与设置的"空壳"审计

### 自问 1：manifest 里每条权限都有代码真的在用吗？

逐条 grep：`SYSTEM_ALERT_WINDOW`✓（overlay）、`FOREGROUND_SERVICE*`✓、
`POST_NOTIFICATIONS`✓、`RECEIVE_BOOT_COMPLETED`✓、`QUERY_ALL_PACKAGES`✓、
**`WAKE_LOCK` ✗ —— 全代码零引用**。这是"声明未用"的空壳权限（对齐测试只查设置键，
不查权限，所以没被抓到）。

### 自问 2：那"屏幕常亮"这个车机刚需谁在做？

没人做。桌面 Activity 没加 `FLAG_KEEP_SCREEN_ON`，服务也不持锁 —— 意味着**用这个桌面
导航，几分钟屏幕就熄灭**，这在车里不可接受。而设置页 `exp_autoblackscreen` 标着
"手机版暂未接管行为"，也是空壳。

### 自问 3：修法选哪个？为什么不用 WAKE_LOCK？

- 服务持 `PARTIAL_WAKE_LOCK`：CPU 一直醒，后台费电，且要保留权限；
- 窗口 `FLAG_KEEP_SCREEN_ON`：**不需要任何权限**，桌面可见即常亮、离开自动交回系统策略，
  语义正好是"桌面在前台时屏幕不熄"。选后者。
- 不复用 `exp_autoblackscreen`（"离开后自动黑屏"）：那是车机语义（车机离开桌面没东西可看），
  手机上离开桌面是去看目标 App，黑屏反而错。新增独立开关 `screen_awake`（默认开），
  进 `pref_ui.xml` + `Keys` + `load()` + `applyLayout`，被对齐测试闸门覆盖。
- `WAKE_LOCK` 从 manifest 删除（将来做参考版 `BlackScreenActivity` 那种黑屏保 CPU 模式再加回）。

### 第 2 轮抓到的 bug 清单（已修）

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| B7 | manifest | WAKE_LOCK 声明零使用（空壳权限） | 删除 |
| B8 | MainActivity | 桌面常亮完全没人实现（导航会息屏） | `FLAG_KEEP_SCREEN_ON` + `screen_awake` 开关 |

### 第 2 轮我自己引入并当场抓回的错

- **改完没立刻编译**：B2 修复里写了 `active.sessionToken`，Kotlin 对可变属性不能智能转换，
  编译失败（`Smart cast impossible`）。教训：**每轮修复后第一件事是跑编译，日志为空≠成功，
  要看到 BUILD 结果或产物时间戳变化**。已改为 `active?.sessionToken` 并重建通过。
- **注释与实际不符**：初版注释写"加在 decorView 上"，实际代码是 `window.addFlags`
  （窗口级，效果相同但表述必须准确）。已改。

### 第 2 轮验证

- `gradle :app:assembleDebug :app:testDebugUnitTest` 全绿：30/30（debug 变体，XML 时间戳新鲜）；
- aapt2：`versionCode=9 versionName=0.2-m8`；manifest 中 WAKE_LOCK 0 处、
  `screenOrientation` 3 处（三 Activity 全强制横屏）。

---

## 第 3 轮：部署链与参考版逐条对照（进行中）

### 自问 1：参考版把 App 弄进虚拟屏用了几条路？我们实现了几条？

重读 `c1/h.smali`（行号可复查）：

1. **API 主路**（h.d 前半段，1191-1210）：`ActivityOptions.makeBasic().setLaunchDisplayId(id)`
   + `context.startActivity(intent, bundle)`，成功日志 `"Deployed with Android API"`；
2. **root am start 兜底**（1219-1290）：`su -c "am start --display N -f 0x10104000 -n comp"`，
   日志 `"Deployed with root am start"`；
3. **锚点 + move-task**（h.d 1516-1568）：先在虚拟屏起自家 `PipAnchorActivity`
   （flags 0x18800000 = NEW_TASK|TASK_ON_SCREEN），再 `am stack list` + awk 找
   "含 PipAnchorActivity 的 Stack id"，`am stack move-task <目标taskId> <锚点栈id> true`。

我们只实现了第 2 条。用 `javap` 核实：**`setLaunchDisplayId` 在 android-34 SDK 里是 public API**
（不是 hiddenapi）——第 1 条路我们完全可以走，而且它**不依赖 su、没有进程 fork 延迟**。

### 自问 2：为什么这很重要？

- root `am start` 每次 fork `su` + sh，实测数百 ms 到数秒，且su 授权弹窗/SELinux 都可能卡；
- API 路成功时零延迟；失败（部分 ROM 限制跨屏 startActivity）再落 root，行为严格不劣于现状。

### 自问 3：锚点路要不要抄？

要理解它解决什么：**目标 App 是 singleTask（如网易云）且已在主屏有任务时，
`am start --display` 可能只是把主屏已有任务拉到前台，并不会挪到虚拟屏**。
参考版的解法：把目标任务 move 到"锚点 Activity 所在的栈"，强制落屏。
Android 12+ 该命令改名（`am task move-task`），需要两条命令都试。
—— 本轮先落地 API 主路 + root 兜底 + move-task 补救链，锚点 Activity 作为栈锚的完整复刻放第 4 轮
（它需要新增 Activity 与 taskAffinity 设计，改动面大，单独做避免一锅炖）。

### 第 3 轮抓到的 bug 清单（修复中）

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| B9 | `deploySlot` | `surf1!!` 非空断言竞态：检查后、后台线程读之前 surface 被销毁置 null → 后台线程 NPE 崩溃（进程不崩但槽永远卡死） | 进入线程前捕获局部变量，线程内校验 |
| B10 | `RootAuth.launchOnDisplay` | 只走 root am start（参考版第 2 路），缺 public API 主路（第 1 路）与 move-task 补救（第 3 路） | 三级链：API setLaunchDisplayId → root am start → am stack/task move-task |

（验证结果见本轮末尾）

### 第 3 轮验证

- 编译两次：第一次因 `launchViaApi` 表达式体内 `return` 失败（`Returns are not allowed
  for functions with expression body`）——又是"改完先编译"教训，改块体后通过；
- APK 14:09:44 新鲜，`tests=30 failures=0 errors=0`（XML 14:09:45 新鲜）。

### 第 3 轮抓到的 bug 清单（已修）

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| B9 | `deploySlot` | `surf1!!` 竞态 NPE，槽卡死 deploying | 主线程捕获局部变量后校验 |
| B10 | `RootAuth.launchOnDisplay` | 缺参考版 API 主路（setLaunchDisplayId 是 public，javap 已证） | 加 `launchViaApi` 为第①路，失败落 root |
| B11 | 同上，flags | 目标 App 用 `0x10104000`（TASK_ON_HOME）→ singleTask 应用（网易云）被拉回主屏全屏，正是"点网易云跳转到网易云音乐"根因 | 改 `0x18800000`（MULTIPLE_TASK）强制虚拟屏新建任务 |

### 第 3 轮自问 4：MULTIPLE_TASK 会不会任务堆积？

不会。`launchOnDisplay` 只在全新部署路径调用（teardownVd→建新VD→launch）；resize 快路径
不调它。每次新 VD = 新 displayId，旧 VD release 时其上任务随屏销毁，无堆积。

---

## 第 4 轮：手机版高德的导航卡片数据源（导航通知解析）

### 现象
截图图 4 里用户跑的是【手机版高德】（底部 tabs 首页/探索/打车/我的），桌面导航卡
长期停在"无广播·待机"，即使真实在导航也不更新。用户明确点名"那个导航卡片也没实现"。

### 自问
1. 卡片真的"没实现"吗？——不是。渲染逻辑（paintNavi）、解析器（AmapAutoParser）、
   状态流（CarLinkState.navi）都在，且第 2/3 轮刚修过。
2. 那为什么不动？——因为**唯一的数据源是高德车机版广播
   `AUTONAVI_STANDARD_BROADCAST_SEND`**，而手机版高德根本不发这条广播
   （docs 第 6.5 节已记：手机版与车机版是两套进程，只有车机版/带 AmapAuto SDK 的才发）。
   没有数据进来，渲染再对也是空转。
3. 参照 车联助手 是怎么让手机版也有导航信息的？——它不靠广播。
   `com.leting.carplay.AmapNaviHook` 里除了 hook 高德 SDK，还有一条
   `extractFromNotification(Notification)` 兜底：手机版高德导航时会在通知栏挂一条
   **持续更新的诱导通知**，正文带"剩余XXX米/公里""沿XX路""前方左转"等文案。

### 证据（反编译核实，非猜测）
`/tmp/dec21/.../AmapNaviHook.smali`：
- `extractFromNotification` @642：读 `Notification.extras` 的 `android.text`（副标题，主）
  与 `android.title`（标题，备）；任一 `contains("米"/"公里"/"km")` 即视为导航活跃。
- `parseDistance` @3856：去掉"剩余/约/全程/预计/还有/，/,"后按 `公里`×1000、`米` 取整。
- `extractRoadName` @1088：依次 `replaceAll` 掉"剩余\d+[单位]"、"\d+[单位]"、导航动作词、
  逗号及之后、"\d+分钟/\d+分"，最后**长度>1 且不以数字开头**才认作道路名。
命中后 `sNaviActive=true` 并 `broadcastNaviUpdate()`。

### 结论
手机版没广播 ≠ 没导航卡。正确做法是**复用通知栏这条数据源**，而不是再写一个 Xposed hook
（我们已经是 NotificationListenerService，`onNotificationPosted` 天然能拿到每条通知，
**零新增权限、零新进程**）。

### 修复
新增 `navi/AmapNotificationParser.kt`：
- 纯字符串逻辑（`extractDistanceMeters` / `extractRoadName` / `parse`）可 JVM 单测；
  `fromNotification` 只是把 `Notification.extras` 取值这层壳剥掉。
- 比参照多一道**防误判双条件**：既要含距离单位，又要命中导航动作词
  （外卖/物流通知也带"300米"，参照是在限定高德进程的 hook 里所以不必防，
  我们在通用 NotificationListenerService 上必须防）。
- `parse` 基于 `prev` 做 copy，通知链路只更新 `segRemainDistM` / `curRoadName` /
  `isNavigating` / `updatedAt`，**不覆盖**广播链路带来的全程距离、速度、红绿灯等。
- `CarLinkState.onNavi()` 新入口；`CarMediaListenerService.onNotificationPosted`
  命中高德包名时先尝试解析更新导航卡，成功即 `return`（不再把诱导通知当横幅转发，避免刷屏）。

### 验证
`AmapNotificationParserTest` 15 例全过（距离米/公里/km、混合双数字取单位邻接值、
道路名清洗与数字开头拒绝、text→title 兜底、无距离/无动作词返回 null、字段不被覆盖、
包名白名单）；`:app:testDebugUnitTest :app:compileDebugKotlin` BUILD SUCCESSFUL，全套 45 例绿。
真机验证项：手机版高德导航时通知文案是否确实带"剩余…米/公里"格式（不同版本措辞可能变，
届时按诊断面板 rawTap 补 hint 词表）。

---

## 第 5 轮："苹果互联风格（CarPlay style）也没做"——抽屉网格开在错误的屏

### 现象
用户点名"苹果互联风格也没做"。看应用抽屉代码，网格点图标走
`AppRepository.launch → ctx.startActivity(NEW_TASK)`（`apps/AppRepository.kt:51`），
**永远把 App 开在默认屏（display 0）**。桌面上真实在导航/放歌时点抽屉图标，
App 会铺满整块手机屏、盖住 A/B/C 卡片——和 CarPlay「在车机屏里打开」完全相反。

### 自问
1. "苹果互联风格"到底指什么？——不是配色，是**交互语义**：在 CarPlay 上点网格图标，
   App 是在**车机那块屏**里打开，手机默认屏不受影响。我们的抽屉却把它开回手机屏。
2. 为什么之前没发现？——镜像部署（`RootAuth.launchOnDisplay`）早就支持投到虚拟屏了，
   但只有 A/B/C 卡片用得上（它们绑死了 slot1/slot2）。抽屉网格这个"任意 App"入口，
   拿不到一个"当前可用的车机屏"句柄，就退回了裸 startActivity。
3. 参照怎么解决"任意 App 投到车机屏"？——它用 `PipAnchorActivity` 做任务锚点 + 常驻目标屏。
   我们没有锚点权限，但我们有【活跃的虚拟屏 displayId】，用 `setLaunchDisplayId` 主路即可等价。

### 证据
- `apps/AppRepository.kt:51` 裸 `NEW_TASK`，无任何 display 参数。
- `svc/RootAuth.kt:109` `launchOnDisplay` 已实现 API（`setLaunchDisplayId`）主路 + root 兜底，
  只是没有一处能拿到"当前该投到哪块屏"的 displayId。
- `mirror/MirrorSlot.kt` 的 `displayId` 是 private 实例字段，MainActivity 的 slot1/slot2
  对外不可见，抽屉自然取不到。

### 结论
不是"没做 CarPlay 风格"，是**抽屉少了一条"投到当前车机屏"的数据通路**。
最小正确修复：在 MirrorSlot 里维护一份进程级「活跃车机屏 displayId 注册表」，
抽屉据此优先投屏，无屏可投时才退回默认屏。

### 修复
- `mirror/MirrorSlot.kt` companion 新增 `active: LinkedHashSet<Int>` 注册表 +
  `register/unregister`（`@Synchronized`）、`hasCarDisplay()`、`newestDisplay()`。
  部署成功 `register(displayId)`，`teardownVd` 里 displayId 归 -1 前先 `unregister`。
- `ui/DrawerActivity.kt` 网格点击：`newestDisplay() > 0` 时后台线程调
  `RootAuth.launchOnDisplay(app, pkg, dispId)`（su 不能卡 UI 线程），否则 `AppRepository.launch`。
  两条分支都先 `AppForeground.markSelfLaunch()`，避免"我们自己拉起"被误判成退出桌面。

### 验证
`:app:testDebugUnitTest :app:compileDebugKotlin` BUILD SUCCESSFUL（编译期类型/引用全过；
注册表是纯状态逻辑，随部署真机验证）。

### 复盘遗留（诚实记录，未在本轮修）
- 抽屉投屏目前投到 `newestDisplay()`（最近部署那块）。A/B 双屏时用户可能更希望
  "投到当前正看着的那块"或"弹选择"。这属于交互设计取舍，不是 bug，留待真机反馈再定。
- `launchOnDisplay` 每次点击起一个临时线程 + 一次 su，无节流；快速连点会并发多个 su 会话。
  与镜像部署同源问题（第 3 轮已记），本轮未一并处理。
