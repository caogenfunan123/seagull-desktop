# CarLink Desktop —— 手机端仿 CarPlay 车机桌面

在**已 Root + LSPosed** 的安卓手机上，复刻车联助手 X（com.leting 3.0.0）那套
CarPlay 风格车机桌面：导航卡 + 音乐卡 + **双画中画镜像**，可被选为默认桌面（HOME）。

> 当前版本：**0.2-m9**（versionCode 10）。本文档写的是**代码里实际存在的东西**，
> 不是计划。机制来源是对车联助手 APK 的**逐行 smali 核验**（不是报告转述），
> 完整取证与复盘见 [`docs/REVERSE-AND-PLAN.md`](docs/REVERSE-AND-PLAN.md)。
> 基础架构见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)，
> 0.2-m8~m9 的五轮逐条复盘（B1~B11 + 导航通知解析 + 抽屉投车机屏）见
> [`docs/RETRO.md`](docs/RETRO.md)。
>
> **m8 关键修复（真机 6 图反馈驱动）**：画中画"返回桌面失效"= resize 快路径漏置
> `ready=true`（B1）；点网易云跳全屏=目标 App 用了 `TASK_ON_HOME` 而非 `MULTIPLE_TASK`
> 拉走了主屏 singleTask（B11）；强制横屏、去留白、去"A·主内容"标题、独立设置图标（B3~B6）；
> 删 WAKE_LOCK 空权限、补 FLAG_KEEP_SCREEN_ON 常亮（B7/B8）；API `setLaunchDisplayId`
> 主路 + root 兜底（B10）。**m9 关键新增**：手机版高德不发车机版广播 → 改从**导航诱导通知**
> 解析卡片数据（`AmapNotificationParser`，零新增权限）；应用抽屉网格点图标**投到当前车机镜像屏**
> 而非手机默认屏（CarPlay 语义）。

---

## 0. 先说结论：画中画到底是怎么做到的

这是本项目反复返工的地方，0.2-m4~m6 全走错了路（setprop + 在目标进程内钩
`ActivityOptions.getBounds`），**根因是 ActivityOptions 由 Launcher 侧构建，钩目标进程内的它改不了真实尺寸**。
m7 按车联助手的真实机制重写，机制如下：

- **车联助手原版**：`DisplayManager.createVirtualDisplay("gdj", w, h, dpi, …, surface)`
  —— 走 **@SystemApi 无 MediaProjection token** 的重载，画面直接渲染进一个
  `ModSurfaveView`(SurfaceView)，所以能"悬浮在软件上面"。它能这么干是因为签名权限齐全
  （`CAPTURE_SECURE_VIDEO_OUTPUT` / `INJECT_EVENTS` / `WRITE_SECURE_SETTINGS`）+ LSPosed
  作用域含 `com.android.systemui`。
- **我们的合法等价物**（普通签名 App 拿不到那套权限）：
  `MediaProjection.createVirtualDisplay(name, w, h, dpi, PUBLIC|OWN_CONTENT_ONLY|PRESENTATION, surface, …)`
  —— 视觉结果一致：目标 App 起进这块虚拟屏后，画面渲染进我们 SurfaceView 的 Surface。
- **部署**（对齐原版 c1/h）：root `am start --display <id> -f 0x10104000 -n <pkg>/<launcher>`。
- **触摸回注**（对齐原版 b1/d）：`su -c "input -d <id> tap|swipe"`，位移 ≥10px 判滑动、
  时长夹 50~2000ms。坐标即 VD 像素（VD 按 surface 尺寸建，天然 1:1）。

原版 hook 高德强制比例（`TARGET_SIZE_PERCENT`）是**另一条正交的路**——那是真实系统
PiP 的尺寸固定，和"镜像画中画"无关；镜像方案落地后不再需要它。

> 一句话：**双画中画 = 虚拟屏镜像 + SurfaceView 上屏 + root 跨屏拉起 + root 触摸转发**，
> 全程不依赖目标进程内的任何 hook。

---

## 1. 数据链路总览

```
高德车机版 com.autonavi.amapauto
   └─ AUTONAVI_STANDARD_BROADCAST_SEND (普通广播) ──> navi/AmapNaviReceiver（动态注册）
                                                        └─> navi/AmapAutoParser（别名表+宽容转型）
任意音乐 App（网易云/QQ/本地…）
   └─ MediaSession（系统级）──> media/CarMediaListenerService（NotificationListener）
                                    ↓
                 state/CarLinkState（进程内 StateFlow 单例，唯一事实来源）
                                    ↓
        ui/MainActivity：导航卡 + 音乐卡 + 双镜像画中画（SurfaceView）+ 悬浮层
虚拟屏镜像（m7）：
  ui/MainActivity (SurfaceHolder.Callback)
     └─ svc/PipProjectionService（mediaProjection 前台服务，满足 Android 14 授权时序）
          └─ mirror/MirrorSlot：MediaProjection.createVirtualDisplay + root am start + su input
               └─ svc/RootAuth：su 会话（授权 / 跨屏拉起 / 触摸注入 / dumpsys 读屏诊断）
```

省掉的整条旧链路（架构重写时删除）：Frida 探测、dex 头解析、字段类型指纹、AIDL 跨进程、
Hook 后 View 跨进程渲染。代价：必须装**高德车机版**（公版 APK 可直接装手机）。

---

## 2. 目录结构（app/src/main/java/com/carlink/desktop/）

```
CarLinkApp.kt              Application：进程级初始化
apps/AppRepository.kt      已装应用查询（画中画/卡片可选目标列表）
model/                     NaviInfo / MusicInfo —— 全局数据契约
navi/                      AmapAutoParser（协议解析+别名表）· AmapNaviReceiver · UnitStrings
media/                     CarMediaListenerService（NotificationListener + MediaSession）
state/                     CarLinkState（StateFlow 单例，唯一事实来源）
overlay/                   PipWindow（可拖动悬浮窗基类）+ CarPipTheme（纯代码绘制）
                           NaviPipWindow / MusicPipWindow / NotifBannerWindow
settings/                  CarLinkSettings（单一存储文件 + slotForRegionA/B + 默认播种）
svc/
  DesktopService.kt        桌面前台服务（specialUse）：承载接收器 + 悬浮窗，可随开机自启
  PipProjectionService.kt  ★镜像画中画专用（mediaProjection）：满足 consent→FGS→token 时序
  RootAuth.kt              su 会话封装：grantAll / allowProjectMedia / launchOnDisplay /
                           runQuiet / readDisplays（dumpsys 数虚拟屏）
mirror/
  MirrorSlot.kt            ★一个镜像槽（幂等 deploy / resize 复用 / 触摸串行队列 / CANCEL 修复）
ui/
  MainActivity.kt          桌面：布局权重 + 双 SurfaceView 生命周期驱动 deploy/teardown
  DrawerActivity.kt        应用抽屉
  SettingsActivity.kt      设置宿主
  prefs/                   ScreenFragment · AppListPrefs · DevActions（诊断+一键授权+读屏）· …
lsp/
  WFreeHook.kt             LSPosed 模块入口（H1~H3）；★H4（getBounds 接管）已删——见 §0 根因
res/
  layout/activity_main.xml A 区/B 区各含 placeholder + *_pip_surface + *_pip_wait 遮罩
  xml/pref_*.xml           13 屏设置系统，key 逐字对齐逆向清单
  values/strings.xml       a_mirror_active / a_mirror_need_pkg / b_pip_need_pkg / notif_pip_running …
src/test/                  对齐测试（防"空壳设置"复发的机制性闸门）
windowfreer/               独立第二模块（carlink-windowfreer，窗口解锁）
```

★ = m7 新增/重写文件。

---

## 3. 装机与验证（App 会自证，无需你连 adb 敲命令）

1. **装高德车机版** `com.autonavi.amapauto`，打开走完初始化，确认能显示地图。
2. **装本 APK**（app-debug.apk），并授予 root（Magisk/KernelSU/APatch 弹窗允许）。
3. LSPosed 里**启用 CarLink 模块**，作用域建议对齐车联助手：
   `com.autonavi.minimap`、`com.luna.music`、`com.netease.cloudmusic.iot`、
   `com.android.systemui` 及各家车机互联包（`com.miui.carlink` / `com.baidu.carlife.xiaomi` /
   `com.samsung.android.carlink`）。注意：**镜像画中画本身不依赖该模块**（§0），
   模块只服务真实系统 PiP 的高德比例固定那条路。
4. 打开 CarLink → 「权限与自检」→ 点「root 一键授权」（自动开悬浮窗 + 写通知监听 +
   `appops … PROJECT_MEDIA allow`）。
5. **启用画中画**：在设置里把 A 区（或 B 区）选为「画中画（镜像到本区域）」并选一个目标应用。
   - 首次会弹一次**录屏授权**（已 appops 预授权，正常应秒过）。
   - 授权后区域里应直接出现目标 App 的镜像画面；诊断行显示 `槽1 镜像 <pkg> → displayId=N`。
   - 「链路自检」区每 ~4.8s 后台读一次 `dumpsys display`，出现 `carlink-pip1/pip2` 即虚拟屏已建。

### 不通时，App 自己告诉你的信息（截图给我即可）

- `镜像: 未见虚拟屏（未部署/已释放）` → 槽根本没部署：没选应用 / 录屏授权被拒 / su 不可用。
- `镜像: carlink-pip1`（VD 建了）但区域黑屏 → 卡在跨屏拉起：`am start --display` 失败，
  多半目标无可启动 Activity 或被 ROM 拦；诊断 `lastError` 会写原因。
- 有画面但点不动 / 偶发吞点击 → 触摸回注问题（已修 ACTION_CANCEL + 串行队列，若复现报我）。

---

## 4. 真机上仍未验证的点（诚实清单，详见 docs §11.8）

- 录屏授权在你的定制 ROM 上的实际行为（是否弹、能否记住）。
- 目标 App 在虚拟屏上的横竖屏方向。
- 触摸延迟实测（`su input -d` 约 45~150ms 量级）。
- OEM 省电策略是否杀 mediaProjection 前台服务。

---

## 5. 构建

```
gradle :app:assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
gradle :app:testDebugUnitTest    # 45 项单测，全绿为准（debug 变体为准，release XML 可能陈旧）
```

`versionCode` / `versionName` 在 `app/build.gradle.kts`。本 README 与
`docs/REVERSE-AND-PLAN.md` 第 11 部分随代码同步更新。

---

## 6. 仓库布局说明

GitHub 远端有两条分支，内容等价、布局不同：

- `main`：**扁平布局**（本文所述真实工程根，`app/` + `windowfreer/` + `docs/`）。
- `pushprep`：**嵌套布局**（源码在 `carlink-desktop/` 下，`release/` 放单个成品 APK），
  便于直接下载最新 APK。

每次发版：先在 main 提交，再把源码 `git archive` 移植进 pushprep 的 `carlink-desktop/`，
并把新 APK 放进 `release/`（删除旧版）。

> ⚠️ 安全提醒：仓库历史中曾明文提交过 GitHub Personal Access Token，
> **请立即到 GitHub Settings → Developer settings → Tokens 撤销该 token 并换新**。
