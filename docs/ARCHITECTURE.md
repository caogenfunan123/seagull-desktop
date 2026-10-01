# 架构

## 分层

```
┌─────────────────────────────────────────────────────────┐
│  UI 层（Activity / View）                                │
│  HomeActivity · SettingsHubActivity · 各分区设置页         │
│  DesktopView · FolderActivity · SearchActivity           │
│  GardenActivity · ThemeActivity · LayoutModeActivity ...  │
└────────────────────────┬────────────────────────────────┘
                         │ 只读写 LauncherModel
┌────────────────────────▼────────────────────────────────┐
│  数据层                                                   │
│  LauncherModel —— 全量配置 + 应用清单的单一事实源            │
│  持久化：SharedPreferences(HomeActivity.PREFS, json_layout)│
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  能力层                                                   │
│  Caps      能力探测与分档（L0~L3）                         │
│  SysOps    系统操作（亮度/音量/网络/时间/强停）              │
│  RootOps   root 通道（搬屏 / 触摸注入 / 授予权限）           │
│  Theme     配色表                                          │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  增强服务层（可选，缺权限自动降级）                          │
│  MirrorSlot          虚拟屏镜像槽（L3）                    │
│  PipProjectionService 投屏前台服务（mediaProjection 类型）  │
│  BallService         小白点悬浮球（L1）                    │
│  MediaListenerService 通知监听（歌词）                     │
│  TaskEngine          自动化任务引擎                        │
└─────────────────────────────────────────────────────────┘
```

## 模块职责

### 数据层

| 文件 | 职责 |
|---|---|
| `LauncherModel.java` | 应用清单枚举、布局/Dock/快捷栏/菜园/小白点/外观/屏幕/岛屿/天气/歌词/窗口/触摸/任务全部配置的读写与持久化 |
| `Theme.java` | 14 套内置配色 + 自定义主题色解析 |

**约定**：任何 UI 想读或改配置，都必须经 `LauncherModel`。
不要新建 SharedPreferences 文件，不要在自己的类里缓存配置副本。

### 能力层

| 文件 | 职责 |
|---|---|
| `Caps.java` | 探测 root / 悬浮窗 / 虚拟屏 / 通知使用权，输出档位与体检报告 |
| `SysOps.java` | 系统操作统一入口，每项返回 `R{ok,msg}`，失败不抛异常 |
| `RootOps.java` | `su` 通道：`grantAll` / `allowProjectMedia` / `launchOnDisplay` / `injectTouch` / `readDisplays` / `findTaskId` / `moveTaskToDisplay` |

`SysOps` 的返回约定：

```java
public static final class R {
    public final boolean ok;
    public final String msg;
}
```

调用方必须检查 `ok`，失败时给用户可读提示，不能静默吞掉。

### 增强服务层

全部遵循「**有则增强，无则隐藏**」：

| 服务 | 缺权限时的行为 |
|---|---|
| `MirrorSlot` | 无 root → 建屏成功但搬不动应用 → 退回悬浮镜像卡片（L1） |
| `PipProjectionService` | 无截屏授权 → 不启动，UI 隐藏相关入口 |
| `BallService` | 无悬浮窗权限 → 不启动，设置页显示「去授权」 |
| `MediaListenerService` | 无通知使用权 → 不启动，歌词格显示提示 |
| `TaskEngine` | 任务动作不可用时，该项在编辑界面置灰并说明原因 |

## 关键流程

### 启动

```
BootReceiver (BOOT_COMPLETED)
  └─ 读 autoHome → startActivity(HomeActivity)

HomeActivity.onCreate
  ├─ prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
  ├─ model = new LauncherModel(this)      ← loadApps() + load()
  ├─ selfReport()                          ← 打 SeagullDiag 日志
  ├─ setContentView(buildUi())             ← 顶栏 + DesktopView + 底栏
  └─ desktop.refresh()
```

### 桌面渲染

```
DesktopView.refresh()
  ├─ renderWidgets(m)    组件条（按 m.widgets 与 m.mode 决定可见性）
  ├─ 重建 grid           m.mode == DENSE ? 6 列 : 5 列
  │   └─ homeKeys() → folderOf(k) → appView / folderView
  └─ 重建 dockBar        按 m.dock 列表
```

### 拖拽压合

```
长按图标（需 editMode）
  └─ startDrag(view, key)
       └─ onTouchEvent(ACTION_MOVE) → hitTest(rawX, rawY) 找落点
            └─ ACTION_UP
                 ├─ 落点是普通图标 → model.mergeInto(dst, src)
                 └─ 落点是文件夹 → 把 src 加进该文件夹
```

### L3 镜像（root 增强）

```
MirrorActivity
  ├─ 请求截屏授权（REQ_CONSENT）
  ├─ onActivityResult RESULT_OK
  │    └─ 启动 PipProjectionService（前台服务，mediaProjection 类型）
  ├─ getMediaProjection → MirrorSlot.deploy(mp, pkg, surface, w, h, dpi)
  │    ├─ mp.createVirtualDisplay(...)      ← 建屏
  │    └─ RootOps.launchOnDisplay(displayId, pkgString)
  │         ├─ am force-stop <pkg>          ← 必须先杀，否则投递到主屏旧实例
  │         └─ am start --display <id> -f 0x18800000 -n <comp>
  └─ 触摸：MirrorSlot.onTouch(e) → RootOps.injectTouch(displayId, ...)
```

## 持久化格式

单个 SharedPreferences 文件，主键 `json_layout`，值为 JSON：

```json
{
  "v": 2,
  "mode": "WIDGET_TOP", "labels": true,
  "pinned": ["com.x/.Main"], "dock": ["com.y/.Main"],
  "widgets": [0,1,2,3],
  "folders": [{"name":"工具","keys":["a","b"]}],
  "layoutPreset": 0, "stripAlpha": 100,
  "dockPos": "BOTTOM", "dockCount": 5, "dockWidth": 76, ...
  "quickbar": [{"k":"@fn:brightness_up","l":"亮度+"}],
  "garden": true, "gardenDim": 60, "gardenStyle": "CENTER_CLOCK",
  "ball": false, "ballX": 40, "ballY": -1,
  "theme": "leaf_shadow", "dayNight": "DARK", "fontScale": 100,
  "wallLib": [{"n":"壁纸1","p":"/sdcard/...","u":true}],
  "marginH": 12, "gap": 6, "orientation": "AUTO",
  "island": false, "islandY": 0, "islandW": 320,
  "citySource": "MANUAL", "city": "杭州",
  "lyricSource": "PLAYER", "lyricLines": 0, "lyricOffset": 0,
  "perf": "MID", "compat": false, "bgKeep": 1, "appScale": [{"p":"com.x","s":80}],
  "touchTh": 10, "longPress": 500,
  "tasks": [{"n":"开机开导航","tr":"SYSTEM_BOOT","ac":"OPEN_PIP","p":"com.autonavi.minimap","d":5000,"e":true}],
  "autoHome": true
}
```

**兼容策略**：新增字段一律带默认值；枚举用 `optEnum()` 读取，值不认识就回落默认，
旧存档永远不会因为缺字段或脏数据而抛异常。
