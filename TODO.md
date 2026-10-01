# 后续开发清单

> 基线：`FEATURE-MATRIX.md`（205 条，5 组 / 17 分区）
> 顺序原则：先让「桌面」真的能用（纯公开 API），再加特色功能，最后做 root 增强层。
>
> **状态**：P0~P2 的代码全部落地（批次 A~I，见 `docs/DEVLOG.md`）。
> 标 🟡 的是「代码已写、未在真机验收」——容器里做不了 root 命令、悬浮窗授权、录屏授权与车机布局验证。
> 接手前先读 `docs/AI-GUIDE.md`（怎么改、怎么验证、怎么排障）。

---

## P0 —— 让桌面可用（全部公开 API，无权限依赖）

### P0-1 持久化与数据层 ✅ 已完成
- [x] 统一 `PREFS`：`LauncherModel` 曾用 `"seagull_launcher"`，与 `HomeActivity.PREFS = "seagull"` 不一致，
      导致写入与读取是两个文件 → 已改为引用 `HomeActivity.PREFS`
- [x] `apply()` → `commit()`，保证首次启动立即落盘
- [x] `LauncherModel` 扩展为全量配置单一事实源（布局/Dock/快捷栏/菜园/小白点/外观/屏幕/岛屿/天气/歌词/窗口/触摸/任务）
- [x] 存档加版本号 `v`，枚举用 `optEnum` 容错（旧存档不认识的值回落默认，不抛异常）
- **验收**：`logcat -s SeagullDiag` 显示 `xml=.../seagull.xml exists=true`

### P0-2 设置骨架 🟡 代码已写，未验收
- [x] `SettingsHubActivity`：5 组 / 17 分区入口，逐字对齐原文案
      - 桌面 → 布局 | Dock 栏 | 快捷栏 | 菜园 | 小白点
      - 外观 → 主题与壁纸 | 屏幕 | 野菜岛
      - 天气与歌词 → 天气 | 歌词
      - 高级功能 → 窗口 | 触摸 | 自动化任务 | 开机动画
      - 系统与工具 → 系统 | 车机工具 | 关于
- [x] 设置项搜索（全局搜设置项名 + 应用名，结果可直接启动应用）
- [x] `SettingsSectionActivity`：17 个分区页，接上 `LauncherModel` 全部已有配置字段的
      UI 与持久化（选择 / 开关 / 滑杆 / 输入对话框 / 应用选择器）
- [x] `Theme`：14 套内置配色表 + 自定义主题色解析（选择与预览已落，全局换肤在 P0-7）
- [x] 已接的最小生效项：Dock 位置=不显示时隐藏、Dock 数量截断、组件条透明度、保持亮屏（公开 API 路径）
- [x] 旧 `SettingsActivity` 改为兼容跳转；桌面「设置」按钮指向 Hub
- [ ] 真机验收：从桌面进设置，能点进每个分区并返回，返回后配置不丢
- **验收**：从桌面进设置，能点进每个分区并返回，返回后配置不丢

### P0-3 文件夹闭环 🟡 代码已写，未验收
- [x] `LauncherModel.mergeInto / renameFolder / dissolveFolder / removeFromFolder / addToFolder`
- [x] `FolderActivity`（打开 / 改名 / 解散 / 移出）
- [x] `DesktopView` 拖拽压合（`onInterceptTouchEvent` + `hitTest`）
- [x] 拖到文件夹图标上 → 并入（`@folder:` tag 分支已写）
- [x] 自动归类：`LauncherModel.CATEGORIES/categoryOf/autoGroup`
- [x] 长按图标 → 上移/下移/置顶/固定/拿下/固定到 Dock/应用信息（批次 D）
- [ ] 真机拖拽验收
- **验收**：长按 A 拖到 B 上松手 → 生成文件夹；点进去能改名/移出/解散；杀进程重进仍在

### P0-4 应用搜索 ✅
- [x] `SearchActivity`：输入即时过滤（应用名 + 包名）
- [x] 拼音首字母匹配（`Pinyin.java`，ICU `Transliterator "Han-Latin"`）
- [x] 搜索结果可直接启动 / 固定到 Dock
- **验收**：输入 2 个字符出结果，点结果能启动

### P0-5 Dock 全配置 ✅
- [x] 位置（底部/左侧/右侧/不显示）
- [x] 数量（1~8）
- [x] 宽度（底部时=高度）
- [x] 图标大小 / 间距
- [x] 透明度（实时预览）
- [x] 自动收起（把手）/ 固定
- [x] Dock 时钟开关；长按 Dock 应用 → 默认开在几号窗口（`MirrorActivity` 双槽）
- [x] 数量改小时保留多出的应用
- **验收**：改任一项立即生效且重启后保持

### P0-6 快捷栏 ✅
- [x] `QuickBar` 组件：组件条里的一条小 Dock
- [x] 每格放应用或功能按钮（7 个功能：亮度/音量 ±、静音、Wi-Fi、蓝牙、飞行模式）
- [x] 每个桌面布局各有一份（按 `LauncherModel.Mode` 分组存 `quickbars`）
- [x] 长按组件条 →「+」→ 选组件 / 长按组件 → 管理
- [x] 自检 `app/selfcheck/QuickbarCheck.java`
- **验收**：快捷栏能放 3 个应用 + 2 个功能按钮并点击生效

### P0-7 主题与壁纸 ✅（毛玻璃 6.10 暂缓）
- [x] `Theme` + `Skin`：14 套内置配色 + 自定义十六进制主题色
- [x] 日夜模式（跟随系统 / 深色 / 浅色）
- [x] 壁纸：白天 / 夜间两套，从系统相册选
- [x] 壁纸库（上限 24，满了给提示）
- [x] 背景遮罩 `wallDim`
- [x] 文字颜色自动（壁纸亮度抽样 16×16）+ 自动字底
- [x] 全局字号缩放（`BaseActivity.attachBaseContext` 改 `fontScale`，全用 sp）
- [ ] 毛玻璃（6.10）：要 RenderEffect，暂缓
- **验收**：切主题全局变色，换壁纸立即生效，重启保持

### P0-8 屏幕设置 ✅
- [x] 屏幕外边距（左右 / 上下）
- [x] 窗口之间的缝
- [x] 屏幕方向（跟随系统 / 固定横屏 / 固定竖屏）
- [x] 横屏 / 竖屏两套边距与缝（`portMarginH/V`、`portGap`）
- [ ] 保持亮屏（`SysOps.keepScreenOn` 已实现，接 UI）
- [ ] 顶部信息栏（日期 / Wi-Fi / 电量）开关
- [ ] 自动同步网络时间（接 UI）

---

## P1 —— 特色功能

### P1-1 菜园 🟡 代码已写，未验收
- [x] 长按叶子进菜园，再长按退出
- [x] 大时钟 + 歌词（歌词接批次 G 的 `Lyrics`）
- [x] 壁纸压暗 `gardenDim`（浅色主题下遮罩换黑色）
- [x] 返回键回桌面
- [x] 菜园里点叶子出应用列表
- [x] 3 种摆法（居中大钟 / 左上时钟+底部歌词 / 大字时间日期）
- [ ] 进菜园时画中画窗口隐藏（4.7，需要窗口焦点控制）

### P1-2 小白点 🟡 代码已写，未验收
- [x] `BallService` 悬浮球（`TYPE_APPLICATION_OVERLAY`）
- [x] 可拖到任意位置，松手贴最近左右边
- [x] 位置记在本机
- [x] 点一下回桌面，长按进出菜园
- [x] Dock 关着时点悬浮球改为出全部应用
- [x] 大小 / 透明度；开机自启

### P1-3 天气组件 ✅
- [x] `Weather.java`：Open-Meteo 公开接口（免 key；和风要 key，见矩阵 9.7）
- [x] 城市来源（自动定位 ✅ / 手动指定 ✅ / 按网络判断 🟡 退化为沿用缓存城市）
- [x] 当前天气 + 未来 3 天
- [x] 每 20 分钟刷新 + 点按手动取
- [x] 组件条天气格

### P1-4 歌词组件 ✅
- [x] 媒体会话（`MediaSessionManager`，一秒一跳）
- [x] 来源：播放器自带 🟡（公开 API 读不到任意 metadata 键）/ 本地 .lrc ✅ / 在线酷狗 ✅
- [x] 行数（自动 横屏 5 / 竖屏 3）、字号
- [x] 对时偏移（±0.5s / 1s）
- [x] 歌词缓存 + 清空（`app/selfcheck/LrcCheck.java` 15 项自检）
- [x] 这首不显示歌词 / 这首歌词不对（换版本）
- [x] 状态栏 / 通知文字歌词
- [x] 媒体卡片行数（设置项已接，卡片本体未做）

### P1-5 自动化任务 ✅
- [x] `TaskEngine`：触发器 + 动作
- [x] 触发：桌面启动 / 系统启动 / 定时（每分钟对表）
- [x] 动作：延迟 / 在画中画打开应用 🟡 / 移除主屏任务 / 打开应用
- [x] 新建 / 删除 / 启停
- [x] 上限 20 条
- [x] 随桌面配置一起导出

### P1-6 野菜岛 🟡 代码已写，未验收
- [x] 顶部胶囊浮层（`IslandView`，浮在桌面这层）
- [x] 距顶部偏移（挖孔 / 圆角）
- [x] 宽度 + 歌词省略
- [ ] 状态栏隐藏时浮在窗口上（8.6，要再开一个悬浮窗）
- [ ] 挖孔/圆角自动检测（8.7，用手动偏移代替）

### P1-7 关于页 ✅
- [x] 版本信息
- [x] 检查更新（GitHub `releases/latest`）
- [x] 自更新（下载 apk 交系统安装器）
- [x] 体检报告（能力探测汇总）
- [x] 安装包指纹（签名证书 SHA-256）

---

## P2 —— root 增强层（自动隐藏，无 root 不影响安装）

### P2-1 窗口镜像 🟡 代码已写，未在车机复验
- [x] `MirrorSlot`：`MediaProjection.createVirtualDisplay` + root 搬应用 + 触摸注入
- [x] 正式 UI（`MirrorActivity`）：授权 → 选应用 → 建屏 → 搬应用 → 渲染到 `SurfaceView`
- [x] 双槽位 A/B
- [ ] 亮度/缩放/裁剪

### P2-2 窗口搬运与保活 🟡 代码已写，root 命令待真机验证
- [x] `dumpsys activity activities` 解析 `displayId=` 找 `taskId`
- [x] 收回窗口 / 从边缘小标签拉回（`TaskMover`，按序试三条 `am` 命令并回传真实输出）
- [ ] 窗口丢失自动重建（需 L3）
- [ ] 重启后恢复窗口（需 L3）

### P2-3 触摸转发调优 ✅（无障碍通道只留开关）
- [x] 防误滑阈值、长按判定（300/500ms）
- [x] 跟手（`touchFollow`，每 ≥16ms 一小段）
- [x] 触摸方式选择（root input / 无障碍）、注入排障日志
- [x] 修 `input -d N input tap` 双写 input（批次 J）
- [ ] 无障碍 `dispatchGesture` 通道：整套无障碍服务授权成本高，暂缓

### P2-4 系统监控增强 ✅
- [x] CPU（`/proc/stat` 两次采样差）/ 温度 / 内存一行读数
- [x] 一键清理后台（`SysOps.killBackground`）
- [x] 息屏暂停 + 亮屏恢复（开关已接）

### P2-5 root 守护进程与 L3 修复 🟡 代码已写，真机待验（批次 J）
- [x] `PrivCodec` 线协议 + `PrivCodecCheck` 30 项自检（纯 JVM）
- [x] `PrivClient`：`su -c setsid app_process` 拉起守护进程、断线重连、会话级禁用
- [x] `RootMain`：反射 `startActivityAsUser`（setLaunchDisplayId）/ `injectInputEvent`（多指）/ `moveRootTaskToDisplay` / `removeTask`
- [x] VD flags 修正 `PUBLIC|AUTO_MIRROR`（去掉 `OWN_CONTENT_ONLY`=黑屏根因）
- [x] 守护进程在位时触摸改原始事件中继（多指、零命令开销）
- [ ] 真机验证：SELinux 是否放行 abstract socket、KernelSU su 域、ROM 是否裁剪 `moveRootTaskToDisplay`
- [ ] 复核 `findTaskId` 的 dumpsys 口径（已知坑 #11）

---

## 明确不做

| 项目 | 原因 |
|---|---|
| 账号 / 登录 / 注册 | 需求明确砍掉 |
| 会员 / PRO / 兑换码 / 支付 | 同上 |
| 云端存档 / 分享码 | 同上 |
| 开机动画云端生成 | 依赖账号体系，可保留纯本地版 |
| 平台签名才能做的系统改写 | 公开版不可达（见 FEATURE-MATRIX 第六节 D 类） |
| 破解 / 绕过原版激活 | 不做 |

---

## 已知坑（写代码时注意）

1. **`PREFS` 必须只有一处定义** —— 曾经两个文件各用各的名字，数据互相看不见。
2. **`commit()` 优先于 `apply()`** —— 首次启动立即需要落盘时，`apply()` 可能还没写完进程就被回收。
3. **root shell 看到的 `/data/data` 是过滤后的挂载命名空间** —— 从外部 `ls` 判断应用数据是否存在会误判。
   要判断持久化状态，让应用自己打日志（`SeagullDiag`）。
4. **`am force-stop` 一次只能带一个包名**。
5. **`am start` 对已在运行的应用会静默投递到主屏实例** —— 搬屏前必须先 `am force-stop`。
6. **`DesktopView` 只能有 `(Context, Host)` 一个构造函数** —— 多个重载会导致 `reference to DesktopView is ambiguous`。
7. **lambda 捕获的局部变量必须 effectively final** —— 需要重赋值时先算好再赋给 `final` 变量。
8. **取色一律用 `Skin.c(R.color.x)`** —— `getColor()` 拿到的是资源固定值，主题切换后不变；
   自定义 View 里也不能用 Activity 的 `getColor`。
9. **`LauncherModel.load()` 开头必须有 `reset()`** —— 少了它 `widgets/folders/tasks/pinned`
   每次重读都追加一遍，越用越胖。
10. **手势导航条占屏幕底部约 40px** —— 布局要留底部 inset。
11. **`dumpsys activity activities` 的 display 归属**：按 `Display #N` 段头切分，
    后续 `* Task{...}` 行归当前 N；Task 行本身**没有** `displayId=` 字段。
10. **手势导航条占屏幕底部约 40px** —— 布局要留底部 inset。
11. **`dumpsys activity activities` 的 display 归属**：按 `Display #N` 段头切分，
    后续 `* Task{...}` 行归当前 N；Task 行本身**没有** `displayId=` 字段。

---

## 环境依赖清单

| 依赖 | 用途 | 备注 |
|---|---|---|
| `aapt2` (arm64) | 资源链接 + 生成 R.java | Termux 需手动放 |
| JDK 17+ | javac | `JAVA_TOOL_OPTIONS=-Duser.home=$HOME` |
| `d8` (R8 9.5.20) | dex 转换 | 旧版 R8 8.2.2 有 NPE |
| `apksigner` | 签名 | 自动生成 debug keystore |
| `android.jar` (API 33) | 编译引导类路径 | 放 `libs/` |
| `zipalign` | 对齐（可选） | 缺失时跳过，apksigner 输出本身已对齐 |
