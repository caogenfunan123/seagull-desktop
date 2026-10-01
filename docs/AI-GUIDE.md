# AI 开发指南

给「接手这个仓库的 AI / 新人」看的操作手册。读完这一篇就能独立改功能、验证、交付。

---

## 1. 这是什么

海鸥桌面：一个**无 Gradle** 的 Android 桌面启动器（Java + 手写资源 + 自建 `app/build.sh`）。
目标设备是车机 / 平板：横屏为主、可能没有 Google 服务、常有 root。
功能对标参照物「野菜桌面」，逐条对照表在 `FEATURE-MATRIX.md`（205 条，含状态与原因）。

| 事实 | 值 |
|---|---|
| 包名 | `com.seagull.carlauncher` |
| 语言 / UI | Java + 纯代码建视图（无 XML 布局、无 AppCompat） |
| minSdk / targetSdk | 29 / 33 |
| 构建 | `app/build.sh`（aapt2 → javac → d8 → zipalign → apksigner，7 步） |
| 依赖 | **零第三方库**，只用 Android SDK |
| APK 产物 | GitHub Actions（`build-apk.yml`）出 artifact，本地不出包 |

---

## 2. 动手之前必读的四条规矩

1. **不改存档 key 名**。用户设备上已有数据；删/改 key 必须留回落读取，否则升级后桌面空白。
2. **配置只走 `LauncherModel`**。不许新建 SharedPreferences，不许在自己类里缓存配置副本。
3. **取色只走 `Skin.c()`**。别用 `getColor(R.color.x)` —— 那样拿不到运行时主题（见 §4）。
4. **Activity 一律继承 `BaseActivity`**。继承 `Activity` 会丢掉字号缩放与换肤。

---

## 3. 目录地图

```
app/
  AndroidManifest.xml          权限 + 组件注册（新增 Activity/Service 必须在这里登记）
  build.sh                     7 步 APK 构建，环境变量可覆盖（CI 用）
  selfcheck/                   纯 JVM 自检（QuickbarCheck / LrcCheck / PrivCodecCheck / DumpParseCheck / TrustedFlagsCheck / StackListCheck）
  src/com/seagull/carlauncher/
    BaseActivity.java          公共基类：fontScale + Skin + 方向 + model
    LauncherModel.java         全量配置单一事实源（最大的文件，改它要小心）
    HomeActivity.java          桌面宿主：顶/底栏、壁纸、野蛮岛、常亮
    DesktopView.java           桌面网格 + 组件条 + Dock + 拖拽压合 + 长按菜单
    Skin.java / Theme.java     运行时配色 / 内置 14 套主题
    Wallpaper.java             壁纸导入 + 亮度抽样
    GardenActivity.java        菜园（三种摆法）
    IslandView.java            顶部胶囊
    BallService.java           小白点悬浮球
    Weather.java               天气（Open-Meteo）
    Lyrics.java / Lrc.java     歌词一跳一拍 / 纯 Java 解析
    TaskEngine.java            自动化任务
    TaskMover.java             窗口搬运（root）
    TouchForward.java          触摸转发（双通道：守护中继 / input 命令）
    MirrorSlot.java            虚拟屏镜像槽（两块；TRUSTED 优先 + 投影兜底 + 1:1）
    MirrorHost.java            画中画槽进程级持有者：退出页面不断屏、桌面自愈、清空入口
    MirrorActivity.java        画中画界面：只有两块画布，长按选应用，零按钮
    TrustedFlags.java          TRUSTED 屏 flag 候选/规范化/受信位校验
    StackScan.java             am stack list 解析：任务在哪个屏（纯 JVM，可自检）
    PrivCodec.java             守护进程线协议（纯 JVM，可自检）
    TaskScan.java              dumpsys 扫 task：display 归属看段头（纯 JVM，可自检）
    SelfTestL3.java / SelfTestMirror.java   L3 真机自检（⓪ TRUSTED 探测 + ①~⑩ 建屏/搬应用/触摸）
    PrivClient.java            守护进程客户端（拉起/重连/降级）
    RootMain.java              app_process 守护进程入口（反射隐藏 API）
    Caps.java / SysOps.java / RootOps.java   能力探测 / 系统操作 / root 通道
docs/
  ARCHITECTURE.md              分层、模块职责、关键流程、存档 JSON（先读这个）
  DEVLOG.md                    每个批次的目标 / 改动 / 复盘（做错了也写进去）
  DEVICE-NOTES.md              车机与模拟器上的实测记录
  SIGNING.md                   统一签名密钥：唯一钥匙位置、CI secret、备份/轮换/排障
  TECHNIQUES.md                平台限制与绕法
FEATURE-MATRIX.md             205 条功能状态
TODO.md                        P0~P2 顺序与验收口径
```

---

## 4. 三个最容易踩的坑

### 4.1 颜色：拿到的不是主题色

```java
getColor(R.color.text)                 // 拿到资源里的固定值，主题切换后不变
Skin.c(R.color.text)                   // 拿到当前主题下的实际色值
```

`Skin.apply(model)` 在 `BaseActivity.onCreate` 里跑，把整张 `R.color` 表按主题重解析一遍。
取色时机晚于 `apply` 的写法一律无效。

### 4.2 字号：只在 `attachBaseContext` 生效一次

```java
@Override protected void attachBaseContext(Context base) {
    super.attachBaseContext(base);            // BaseActivity 已改 fontScale
}
// 之后所有 setTextSize 用 sp —— 不要再手动乘 model.fontScale
```

### 4.3 横竖屏：Activity 会重建

清单里除 `HomeActivity` 外都没写 `configChanges`，所以旋转 = 重建 = 应用重启一次。
所有「当前状态」必须能从 `LauncherModel` 还原，不要只放在内存字段里。

### 4.4 画中画：退出页面绝不能拆屏

VD 一旦 `release()`，系统会把屏上的任务倒回默认屏 —— 桌面立刻冒出全屏应用
（用户原话："每次进入桌面还是应用界面"）。VD 归进程级 `MirrorHost`，
`MirrorActivity.onDestroy` 只 `detachSurface()`；拆屏的唯一入口是
`MirrorHost.clear()`（长按 → 清空该槽，会 force-stop 画中画里的应用）。

### 4.5 签名：每个包必须是同一把钥匙

build.sh 找不到 `keystore/seagull-release.keystore` 会现场生成临时密钥，
签名每次都不同 → 覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
改动构建链路前先读 `docs/SIGNING.md`；本地比对签名用
`apksigner verify --print-certs` 看 SHA-256 是否一致。

---

## 5. 加一个功能的固定动作

```
1. FEATURE-MATRIX.md 里找到那条，先看备注里的平台限制
2. LauncherModel 加字段 + save/load（带默认值，枚举走 optEnum）
3. 写功能类（取数/状态机放静态类，展示放 Activity/View）
4. DesktopView / 各分区设置页接上入口
5. AndroidManifest.xml 登记新组件
6. 非平凡逻辑留一个自检：app/selfcheck/XxxCheck.java（纯 JVM、无框架）
7. 跑类型检查 → 写 DEVLOG → 提交
```

### 自检怎么写

```java
public class LrcCheck {
    public static void main(String[] args) {
        Lrc a = Lrc.parse("[00:12.34]第二句\n");
        if (a.times[0] != 12340) throw new AssertionError("时间戳");
        System.out.println("LrcCheck OK");
    }
}
```

跑法：`javac -d /tmp/sc <纯Java类> app/selfcheck/XxxCheck.java && java -ea -cp /tmp/sc <类名>`。
**要能脱离 Android 跑**，所以算法部分别 import android.\* —— 这也是 `Lrc` 被拆出来的原因。
例外：`TrustedFlagsCheck` 校验的 flag 常量来自 DisplayManager，需
`-cp /tmp/opencode/android-sdk/platforms/android-33.jar`（自检本身仍不跑 android 代码）。

---

## 6. 验证

### 6.1 类型检查（本地唯一允许的编译动作）

```bash
# 需要 android-33.jar 与一份 R.java stub，脚本已封装
bash /tmp/opencode/typecheck.sh
# 输出 TYPECHECK OK 才算过
```

### 6.2 APK 构建（只走 CI）

本地**不**构建 APK。推 main 之后：

```bash
/tmp/opencode/g push origin main
# 查 Actions（匿名 API 会 rate limit，需要 token）
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/caogenfunan123/seagull-desktop/actions/runs?per_page=2"
```

`conclusion == success` 才算构建通过；失败就读 job log 里的 `== N/7 ==` 定位步骤。
若本机有完整 SDK，也可以 `SEAGULL_SDK=... SEAGULL_ANDROID_JAR=... bash app/build.sh` 自出包（产物在 `app/out/`）。

### 6.3 真机

容器里做不了的事：root 命令是否被这台 ROM 接受、悬浮窗授权、录屏授权、车机上的实际布局与性能。
这类功能在矩阵里标 🟡 并写清原因，不要写成 ✅。

---

## 7. 代码风格

- 注释写**为什么**，不写是什么。中文注释。
- 每个类头部一句话说明职责边界。
- 平台限制相关代码标 `ponytail:` 注释，写清天花板与升级路径。
- 不新增第三方依赖（无 Gradle，加依赖等于改构建链路）。
- 不做「以后可能用得上」的抽象：一个实现的接口、只配置一次的值，都不要。
- 保留清单权限里的「无害申请」（root/特权机才有意义，普通机申请也不报错），但**运行时**要过 `Caps.hasRoot()` 判定。

---

## 8. 提交与文档

- commit message：`feat: P1-3 天气 + P1-4 歌词 — 一句话说清做了什么`。
- 每个批次在 `docs/DEVLOG.md` 追加：目标 / 改动 / **复盘（做对、做错、决策）** / 验证 / 遗留。
  做错的部分照实写，这篇文档的价值一半在这里。
- 功能状态变化同步回 `FEATURE-MATRIX.md`，把 ⬜ 改成 ✅ / 🟡 / ⛔ 并写原因。
- `TODO.md` 勾掉已完成项。

---

## 9. 排障入口

| 现象 | 先看 |
|---|---|
| 桌面空白 / 图标不显示 | `LauncherModel.load()` 开头有没有 `reset()`；`homeKeys()` 返回是否为空 |
| 主题切换后颜色没变 | 取色是不是用了 `getColor` 而不是 `Skin.c` |
| 字体没跟着设置走 | Activity 是不是继承了 `Activity` 而不是 `BaseActivity` |
| 镜像窗口不显示 | `WindowTestActivity`（能力探测逐项验证）+ `MirrorSlot.describe()` |
| 触摸没反应 | 设置 → 触摸 → 注入排障（最近命令与返回）+ `Caps.rootWho()` + `PrivClient.status()` |
| 任务不执行 | `TaskEngine` 的 `trigger` / `atMin` / `enabled`；日志 tag `SeagullTask` |
| 歌词不动 | 媒体会话是否在播；`SeagullLyric` 日志；歌词来源是否选了「只用本地」 |
| 天气取不到 | 城市是否填了；`weatherError`；`SeagullWeather` 日志 |
| 构建挂在某一步 | CI job log 里 `== N/7 ==`；先在本地跑类型检查 |

日志 tag 一览：`SeagullDiag` / `SeagullCaps` / `SeagullMirror` / `SeagullTouch` /
`SeagullTask` / `SeagullLyric` / `SeagullWeather` / `SeagullMover` / `SeagullBoot`。
