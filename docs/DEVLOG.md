# 开发日志（按批次）

每个批次 = 一个可交付的小主题。写法固定：**目标 → 改了什么 → 复盘（做对/做错/修正）→ 验证 → 遗留**。

构建与验证口径：

| 环节 | 方式 |
|---|---|
| 类型检查 | `bash /tmp/opencode/typecheck.sh`（只跑 javac，不出 APK） |
| APK 构建 | GitHub Actions：`.github/workflows/build-apk.yml`，复用 `app/build.sh` 七步链路 |
| 自检 | `app/selfcheck/*.java`，纯 java 直接跑，断言式 |

---

## 批次 A — P0-3 文件夹闭环 + P0-4 应用搜索

**目标**：长按 A 拖到 B 上生成文件夹；搜索按名称/包名/拼音首字母过滤。

**改动**

- `LauncherModel.homeKeys()`：pinned 为空时自动枚举，全在文件夹里也不会渲染成空桌面。
- `LauncherModel.CATEGORIES/categoryOf()/autoGroup()/dissolveFolderQuiet()/dissolveAllFolders()`：按包名前缀分 8 类，单应用分类自动解散。
- 新增 `Pinyin.java`：`android.icu.text.Transliterator("Han-Latin")` 取首字母，不引第三方词典。
- 新增 `SearchActivity.java`：结果点击启动，长按 → Dock / 文件夹 / 应用信息。
- `HomeActivity` 顶栏、`AppListActivity` 各加搜索入口；`LayoutModeActivity` 加自动归类与解散全部。

**复盘**

- 做对：拼音走平台 ICU，比塞一份词典小两个数量级，且 `android.jar` 里本来就有这个类。
- 做错：`homeKeys()` 原来只认 `pinned`，纯文件夹布局的桌面是空的 —— 这是个「首次启动就空白」的必现 bug，优先级高于搜索。
- 修正：搜索结果的长按菜单直接复用 `LauncherModel` 的 `toggleDock/addToFolder`，没有另建一套数据通路。

**验证**：`javac` 0 错误。

**遗留**：拼音首字母只覆盖 `Transliterator` 的通用规则，多音字不完美（中文桌面通病，够用）。

---

## 批次 B — P0-5 Dock 全配置

**目标**：Dock 位置/数量/粗细/图标大小/间距/透明度/自动收起/固定/时钟全部可配，长按图标选默认窗口。

**改动**

- `LauncherModel`：`dockWindow`（pkg → 1/2）+ `windowOf()/setDockWindow()`，save/load/reset 全链路。
- `DesktopView` 重写 Dock 部分：`FrameLayout` 根 + 主列侧边 margin + Dock 兄弟节点按 gravity 定位；`applyDockLayout()` 统一落布局参数；`applyCollapse()` 做贴边平移 + 把手展开；`dockMenu()` 长按菜单。
- `MirrorActivity`：`EXTRA_PKG`/`EXTRA_SLOT` + `intentFor(ctx,pkg,slot)`，onCreate 预写槽位并自动走录屏授权链。
- `SettingsSectionActivity.dockBody()`：9 项配置 + 每应用默认窗口（Dock 为空时用默认候选应用）。

**复盘**

- 做对：Dock 的位置/粗细/透明度/收起全部集中在 `applyDockLayout()`/`applyCollapse()` 两个方法里，其他代码不碰布局参数，改起来只有一处。
- 做错：默认 Dock 键位里写了 5 个包名硬编码；一旦桌面上一个都没有，底栏会空。改成「优先常见包名 → 都没有就取前 5 个」两级兜底。
- 修正：`dockWindow` 只存包名不存 key（同一包多入口时两个入口会互相覆盖），取舍上选了包名，因为「默认开在几号窗口」本来就是应用级属性。

**验证**：`javac` 0 错误；`build.sh` 出签名 APK（119345 bytes）。

**遗留**：自动收起只做了贴边平移，没做「侧栏塞满时自动变窄」。

---

## 批次 C — P0-6 快捷栏

**目标**：组件条里的一条小 Dock，每格放应用或功能按钮，每个布局各有一份。

**改动**

- 新增 `QuickBar.java`：`FN_KEYS/FN_LABELS`（7 个功能按钮）+ `runFn()` 执行 + `build()` 造视图。
- `SettingsSectionActivity` 的功能按钮表改为引用 `QuickBar.FN_KEYS`（原先两处各写一份 7 项清单）。
- `DesktopView`：组件条长按（或桌面空白处长按）出「加组件」菜单；长按单个组件可拿下/前后挪；组件 id=6 渲染 `QuickBar`。
- `LauncherModel.switchMode(Mode)` + `quickbarByMode`：每个桌面布局各存一份，存/取抽成纯静态 `swapQuickbar()`。
- `SysOps` 补 `isWifiOn/isBluetoothOn/isAirplaneOn`，开关按钮需要读当前态。
- 自检 `app/selfcheck/QuickbarCheck.java`：7 项断言。

**复盘**

- 做对：把「每布局一份」的存取逻辑抽成不依赖 Context 的纯静态方法，才有办法用普通 java 跑断言自检。
- 做错：自检第一版写 `cur.get(0).key()`，`QuickSlot.key` 是字段不是方法 —— 一编译就暴露，说明把自检放在同包下直接调包级静态方法是对的。
- 修正：`LayoutModeActivity` 的组件列表原本是 5 项、第 5 项叫「温度」，而桌面组件条里根本没有温度组件，两处对不上。统一到 `DesktopView.WIDGET_NAMES`。

**验证**：自检 7 项通过；`build.sh` 出 APK（123441 bytes）。

**遗留**：竖屏快捷栏（3.7）没做；功能按钮只支持 7 个，亮度/音量是相对调节，依赖 root 通道。

---

## 批次 D — P0-7 主题与壁纸

**目标**：切主题全局变色、壁纸白天/夜间两套、壁纸库、全局字号、文字颜色按壁纸明暗自动翻。

**改动**

- 新增 `Skin.java`：运行时配色层。`c(R.color.x)` 是全局唯一取色口，`apply(LauncherModel)` 按主题色 + 日夜算出 ground/panel/card/leaf/text 全套；颜色混合自己按通道插值（`androidx ColorUtils` 不在依赖里）。
- 新增 `BaseActivity.java`：所有页面改继承它。`attachBaseContext` 里把 `Configuration.fontScale` 乘上全局字号（一次生效，全部界面用的都是 sp）；`onCreate` 里 `Skin.apply()`。
- 新增 `Wallpaper.java`：`ACTION_OPEN_DOCUMENT` 选图 → 拷进 `filesDir/walls/` → 入壁纸库；`loadForScreen()` 抽样解码铺满；`isBright()` 抽样 16×16 算平均亮度。
- `HomeActivity`：`applyWallpaper()` 铺底 + 遮罩层；顶/底栏改用 `Skin.bar()`（亮壁纸上半透明 = 自动字底）；`skinSignature()` 只在主题/壁纸/字号/小时变化时整屏重画，避免每次回桌面都闪。
- `LauncherModel`：`load()` 开头补 `reset()`；`fontScaleOf(Context)` 静态只读字号（attachBaseContext 用，不跑应用枚举）；`pinnedIndex/movePinned/pinTop`（自己摆的布局）。
- `DesktopView`：非整理模式下长按图标 = 自己摆布局菜单（上移/下移/置顶/固定/拿下来/固定到 Dock/应用信息）；长按文件夹 = 打开/解散。
- 机械替换：15 个文件里 `getColor(R.color.x)` → `Skin.c(R.color.x)`，全部 `extends Activity` → `extends BaseActivity`。

**复盘**

- 做对：颜色不再从 `resources` 直接取，而是过 `Skin`。换主题只要 `Skin.apply()` 一次 + 重建视图，全屏生效，零散落点。
- 做错（3 个，都在替换阶段）：
  1. 正则 `getColor(R.color.x)` 命中了 `ctx.getColor(R.color.x)`，产出 `ctx.Skin.c(...)` 这种非法代码。已改回。
  2. `HomeActivity.barBtn(label, colorRes, ...)` 传的是变量，正则没命中，漏了一处 `getColor(colorRes)`。
  3. `android.graphics.Gravity` 不存在，`BitmapDrawable.setGravity` 要的是 `android.view.Gravity.FILL`。
- 修正：`LauncherModel.load()` 原本不清空列表字段，`widgets/folders/tasks/quickbar` 每次重读都会叠加 —— 这是首屏就可能出现的越读越胖 bug，现在 `load()` 开头先 `reset()`。
- 决策：壁纸亮度判定用「抽样 16×16 算平均亮度」，不引图像库；`isBright()` 的解码带 `inSampleSize=16`，一张 4K 图也就解出几百像素。

**验证**：`typecheck.sh` 通过。

**遗留**

- 毛玻璃（6.10）没做：`RenderEffect` 在 API 31+ 才有，minSdk 29 要自己降级，不划算。
- 视频壁纸（6.9）没做：解码开销与车机性能冲突。
- 日出日落精确切换（6.4 细分）用固定 06:00~19:00 代替，联网取日出日落等天气模块落地后再补。
- 主题色只做单色派生（accent → 一整套），没有做每套主题独立配 9 个色值。

---

## 批次 E — P0-8 屏幕设置

**目标**：外边距 / 缝真的作用到桌面；屏幕方向；横竖屏两套布局分开；信息栏关掉后时间挪到 Dock。

**改动**

- `LauncherModel`：`portMarginH/portMarginV/portGap` + `marginHOf(portrait)/marginVOf/gapOf` 三个取值口；save/load 补三个键。
- `BaseActivity.applyOrientation()`：`AUTO` → UNSPECIFIED，`LANDSCAPE` → SENSOR_LANDSCAPE，`PORTRAIT` → SENSOR_PORTRAIT；`isPortrait()` 供页面判断用。
- `BaseActivity` 持有 `protected LauncherModel model`，6 个页面删掉各自的 `private LauncherModel model` 与 `new LauncherModel(this)`（每个 Activity 少构造一次，省一次 `queryIntentActivities`）。
- `DesktopView.refresh()`：主列 padding 走 `marginHOf/marginVOf`，网格格子 margin 走 `gapOf`（原来写死 `dp(12)/dp(2,5)`）。
- `HomeActivity.buildTopBar()`：`topInfoBar` 关掉就整条隐藏，并强制 `dockShowClock = true`。
- `SettingsSectionActivity.screenBody()`：横屏 / 竖屏两组滑杆 + 「竖屏复制横屏的值」，方向选择，保持亮屏，信息栏，自动校时。

**复盘**

- 做对：外边距没有新增字段去覆盖旧字段，而是加三个 `…Of(portrait)` 取值口，页面上只判断一次方向。
- 做错：删各页面的 model 字段时用正则顺手把 `model = new LauncherModel(this);` 也删了，`HomeActivity.onResume` 里靠基类的 model 续命 —— 顺序上没问题，但 `HomeActivity` 原来是 `super.onCreate` 之后自己建 model，现在完全依赖基类，注释里要写清，否则以后有人插一行就炸。
- 决策：竖屏布局「分开存」只覆盖边距与缝这三项，没有把 Dock、桌面模式一起分。理由：桌面模式/Dock 是用户显式摆的，分成两套会让「我在竖屏调了下」和「横屏怎么变了」互相打架；边距是跟着屏幕物理尺寸走的东西，必须分开。

**验证**：`typecheck.sh` 通过。

**遗留**：横竖屏切换时 Activity 会重建（没加 `configChanges`），应用重启一次；7.8「横竖屏切换应用不重启」需要窗口控制，矩阵里本就是 ⛔。

