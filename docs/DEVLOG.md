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

---

## 批次 F — P1-1 菜园 + P1-2 小白点

**目标**：菜园整屏（大时钟 + 歌词 + 叶子），叶子长按进出；悬浮球可拖、贴边、点一下回桌面、长按进菜园。

**改动**

- 新增 `GardenActivity.java`：三种摆法（居中大钟 / 左上时钟+底部歌词 / 大字时间日期）；遮罩走 `gardenDim`，与外观的 `wallDim` 是两份；时钟带 `setShadowLayer` 当字底；叶子点=全部应用、长按=退出；返回键回桌面。
- 新增 `BallService.java`：`TYPE_APPLICATION_OVERLAY` 悬浮球，拖动时改 `WindowManager.LayoutParams.x/y`，松手贴最近的左右边并 `model.save()`；点=回桌面（Dock 关着时改成出全部应用）、长按=进菜园；长按判定用设置里的 `longPressMs`。
- `LauncherModel`：新增 `ballAlpha`（5.12）；`ballX/ballY/ballSize` 由菜园叶子与悬浮球共用（它们是同一个「野菜键」）。
- `HomeActivity` 底栏加「叶」：点=全部应用，长按=进菜园（`gardenEnabled` 关着时提示去设置开）。
- `BootReceiver` 开机后按 `ballEnabled` 拉起悬浮球。
- `SettingsSectionActivity`：菜园分区加「进一次菜园看看」；小白点分区接上 `BallService.setEnabled()`、透明度滑杆、回默认位置。
- Manifest：注册 `GardenActivity` 与 `BallService`。

**复盘**

- 做对：菜园和悬浮球共用一份位置/大小，模型没膨胀成两套字段；「野菜键」在两个场景是同一个东西，拆成两份迟早对不上。
- 做错：叶子背景色一开始写成 `Skin.c(R.color.leaf) & 0x66FFFFFF` —— 这是把 alpha 换成了不透明的白，等于白圆底。正确写法是 `(0x66 << 24) | (color & 0x00FFFFFF)`。这类「alpha 与 RGB 用同一个掩码混搭」的错，肉眼在编译期看不出来，只有 review 能抓到。
- 决策：菜园不给悬浮窗权限、不做成 Service —— 它就是一个 Activity，进菜园时别的应用本来就在后台跑，「画中画藏起来」不需要额外动作。
- 决策：长按用 `postDelayed` 而不是 `GestureDetector`，少一个类；代价是手指按住不动也会触发，符合「长按进菜园」的直觉。

**验证**：`typecheck.sh` 通过。

**遗留**：4.4 / 4.14 歌词与字号跟随等批次 G 的歌词模块；4.7 画中画联动未做（矩阵里标 🟡，需要窗口焦点控制）。

---

## 批次 G — P1-3 天气 + P1-4 歌词

**目标**：天气组件（当前 + 未来 3 天、20 分钟自动、点按刷新、手动/定位城市）；歌词组件（三种来源、行数字号对时、缓存清空、这首不显示、换版本、通知歌词），菜园歌词从占位换成真歌词。

**改动**

- 新增 `Weather.java`：Open-Meteo 公开接口（地名查询 + 预报，免 key 免注册 HTTPS）。`refresh()` 在单线程池里跑，回主线程回调；`arm()` 是 20 分钟单例定时器，桌面 onResume 起、数据过期时立刻取一次；`code()` 把 WMO 码翻成中文；`dayName()` 出今天/明天/后天/周几。
- 新增 `Lrc.java`：纯 Java 的 LRC 解析（行首连续时间戳、分号/冒号小数、一行多时间戳=副歌重复、乱序按时间排、元信息行剔除）+ `indexAt()` 找当前行。刻意不碰任何 Android 类，方便 JVM 自检。
- 新增 `Lyrics.java`：`MediaSessionManager.getActiveSessions()` 读当前歌（一秒一跳，`getPosition()` 是快照所以按墙钟自己累加）；三种来源（播放器自带 → 只扫 `description.extras`，拿不到走在线；本地 lrc → Music 目录两层深度；在线 → 酷狗搜候选 + 按 id 下 base64）；缓存 `filesDir/lyrics/<key>.lrc`；`nextVersion/clearCache`；`Sink` 回调把窗口推给组件条与菜园；同句不重复发通知。
- `LauncherModel`：天气加 `weatherAuto/Summary/Forecast/Error/Lat/Lon` + `setCity()`（改城市清缓存坐标）。
- `DesktopView`：组件 4 显示天气（点一下手动取）、组件 5 显示歌词两行；构造时注册 `Lyrics.Sink`，只在放了歌词组件时每秒重画组件条。
- `GardenActivity`：歌词行换成真歌词（只改 TextView 文字，不重建视图，10.15 与组件条同乘 `lyricSize`）。
- `SettingsSectionActivity`：天气分区接上取数/定位权限（root 走 `pm grant`，普通机走 `requestPermissions`）；歌词分区补状态栏/通知/蓝牙三个开关 + 换版本 + 这首不显示 + 清缓存。
- Manifest：加 `INTERNET/ACCESS_NETWORK_STATE/ACCESS_COARSE/FINE_LOCATION`。
- 新增 `app/selfcheck/LrcCheck.java`：15 项断言（时间戳换算、副歌重复、乱序排序、无时间戳丢弃、播放位置落在两句之间）。

**复盘**

- 做对：把 LRC 解析拆成零 Android 依赖的 `Lrc`，换来一个真能在 JVM 上跑的 15 项自检。时间戳是「算错一行就整首跑偏」的地方，这层测试值这 40 行。
- 做错（三个，写的时候都以为对了）：
  1. `PlaybackState.getDuration()` 编译不过 —— 它是 @hide。改用 `MediaMetadata.getLong(METADATA_KEY_DURATION)`。
  2. `getActiveSessions()` 返回的是 `List<MediaController>`（API23+），不是 `List<MediaSession>`，我按老印象写了 `new MediaController(ctx, token)`。直接遍历 controller 就行。
  3. `MediaMetadata.getBundle(String)` 也是 @hide，公开 API 读不到任意 metadata 键。10.2「播放器自带」只能退到 `getDescription().getExtras()`，扫不到就走在线 —— 这条在矩阵里标 🟡 并写清原因，不假装做到了。
- 决策：一秒一跳里只 `new LauncherModel` 一次（读存档要解 JSON，三次/秒太浪费），`advance/push/window` 都吃传进来的实例。
- 决策：隐藏的静态 Context 一开始留了 `sCtx`（缓存读写要用），看着像全局变量；改成所有方法显式带 `Context`，只留一个 `appCtx` 给定时器用。
- 决策：天气源选 Open-Meteo 而不是和风（9.7）。和风要 key、要注册，公开版没法开箱即用；换回来只需改 `Weather.FC_URL` 与地名接口两个常量。矩阵里 9.7 标 ⛔ 并写了这个替代路径。

**验证**：`typecheck.sh` 通过；`LrcCheck` 15 项通过。

**遗留**：9.4 按网络判断、10.2 播放器自带、10.11 状态栏真显示、10.14 卡片本体受平台/后续批次限制；真机联网与播放验证仍待做。

---

## 批次 H — P1-5 任务引擎 + P1-6 野菜岛 + P1-7 关于

**目标**：任务能真的自己跑；顶部胶囊能显示；关于页能查更新并自更新。

**改动**

- 新增 `TaskEngine.java`：`fireDesktop`（同进程只跑一次）/ `fireBoot` / `arm`（每分钟对表）三个触发，动作层实现 `OPEN_APP / OPEN_PIP / REMOVE_FROM_HOME / DELAY`。
- `LauncherModel.Task` 加 `atMin`（定时触发的「每天第几分钟」），`describe()` 会把时间说成人话（`每天 07:30`）；`appNameOf/shortName` 把包名换成应用名。
- 新增 `IslandView.java`：顶部居中胶囊，`onDraw` 里圆角矩形 + 主副两行文字，超宽尾部省略；`bind(model)` 按设置摆位置并取内容。
- `HomeActivity`：`root` 上挂 `IslandView`（不进 `mainCol`，免得被布局挤动），onResume 与 30 秒 tick 都 `bind`，另注册 `Lyrics.Sink` 让岛上歌词一秒一跳；点一下进野菜岛设置分区。
- `SettingsSectionActivity`：定时任务多问一句「每天几点几分」；关于页接上「检查更新」（GitHub releases/latest 对版本）与「自更新」（下 apk 到 filesDir → 系统安装器）；指纹改用 `GET_SIGNING_CERTIFICATES`。
- `BootReceiver`：开机跑 `TaskEngine.fireBoot`。

**复盘**

- 做对：定时任务用「每分钟对一次表」而不是 `AlarmManager`。`AlarmManager` 要权限、要 exact alarm 白名单（API 31+ 还收紧），一分钟精度对「每天 07:30 打开导航」完全够用；多出来的代价只是一分钟误差。
- 做错：`Task.describe()` 在 `static` 嵌套类里调实例方法 `appNameOf`，编译不过。拆成 `static shortName()`（截包名尾巴）与实例 `appNameOf()` 两份——任务列表里 `allApps` 已经加载过，用实例那份能显示真名，静态上下文才用短名。
- 决策：13.6「在画中画打开应用」用官方 `android.support.picture_in_picture` extra，Android 没有「强制别人进 PiP」的 API。矩阵里标 🟡 并写清原因，不假装 100% 生效。
- 决策：野菜岛浮在桌面自己这层（`FrameLayout` 顶层），不新开悬浮窗。跨应用浮起来要再造一个 Overlay Service；等 8.6 真被用到再上，ponytail 已写进类注释。
- 决策：8.7 挖孔/圆角检测没做，用手动偏移代替。自动检测要读 `DisplayCutout`，拿不到就得靠 `WindowInsets` 猜，收益不抵成本。

**验证**：`typecheck.sh` 通过；CI（`5197fce`，批次 G）`completed success`。

**遗留**：8.6 / 8.7 见上；13.6 受目标应用限制；开机动画（14.x）整体未做，要 Magisk 侧配合。

---

## 批次 I — P2 root 增强层（触摸转发 / 窗口搬运 / 系统监控）

**目标**：把三块 root 能力从「有开关没实现」变成真能跑：触摸转发、窗口搬运、系统监控。

**改动**

- 新增 `TouchForward.java`：整条触摸状态机。DOWN 记起点；MOVE 时按「跟手」开关分流（开=每 ≥16ms、位移 ≥2px 发一小段 `input -d <id> input swipe`；关=等抬手一次发完）；抬起时没滑动的补一次 `tap`；CANCEL 什么都不发。命令串行执行，最近 12 条命令与返回进静态环形日志（12.8 排障）。
- `MirrorSlot` 的 `onTouch` 改成把 `MotionEvent` 整个喂给 `TouchForward`，删掉自己那套 `downX/downAt` + `HandlerThread`。
- `LauncherModel` 加 `touchFollow`（12.5）。
- 新增 `TaskMover.java`：`dumpsys` 解析 `displayId=` 找 `taskId`，然后按顺序试三条搬运命令（`am task move-to-display` / `am stack move-task` / `cmd activity task move-to-display`），把每条的真实返回原样拼成一段文字给 UI 看。
- `MirrorActivity` 槽位右缘加「收回」小标签（11.14），点一下后台线程跑搬运，结果进诊断行。
- `SysOps` 加 `cpuPct()`（`/proc/stat` 两次采样差，120ms 间隔）与 `monitorLine()`（CPU/温度/内存一行）。
- 设置页：触摸分区补「注入方式」「跟手」「注入排障」「能力说明」；窗口分区补「收回窗口 / 拉回窗口 / 搬运排障」（都过 `rootOnly()`，没 root 就直说）；系统分区补「CPU / 温度 / 内存」。

**复盘**

- 做对：`TaskMover` 没有赌一个「通用写法」，而是按顺序试并把每条命令的返回原样摊在 UI 上。`am task` 的子命令各家 ROM 裁得不一致，容器里没法验证，能交付的最有价值形态就是「证据可见 + 一键试」。
- 做错：`SysOps.monitorLine` 第一版写的是 `getMemoryClass() - availMem/MB` —— 把「本应用堆上限」和「系统可用内存」相减，得到的数字没有任何意义。改成 `(totalMem - availMem) / totalMem`。这类「单位对不上但能编译」的错，只有把两个 API 的语义放一起看才抓得到。
- 做错：`TouchForward` 的 `lastAt` 一开始声明成 `float`（跟 `lastX/lastY` 写在一行），`e.getEventTime()` 是 `long`，编译期就报了。这个是纯手滑，编译器当场拦住。
- 决策：跟手靠「拆成多条短 swipe」实现，不用 `input motionevent`（API 30+ 才有，低配车机未必有）。代价是命令量变大，日志里能直接看到，够用。
- 决策：12.6 无障碍 `dispatchGesture` 只留开关没实现 —— 那要一整套无障碍服务 + 用户手动到系统里授权，公开版首发不值得。矩阵标 🟡 写明原因。

**验证**：`typecheck.sh` 通过；CI（`89908c3`，批次 H）`completed success`。

**遗留**：11.13/11.14/13.6/12.6 都要在真机（有 root）上验证命令可用性；容器里只能保证代码路径与降级提示正确。





