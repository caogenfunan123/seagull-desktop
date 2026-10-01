# 海鸥桌面 · Seagull Desktop

> 公开版多窗口车机桌面。对标 **野菜桌面**（`cn.wayecai.launcher` v1.7.3）的功能结构，
> **去掉账号 / 会员 / 云端体系**，做成一个装上就能用的普通桌面。

- 包名：`com.seagull.carlauncher`
- 签名：`CN=SeagullCarLauncher`（自签名，非平台签名）
- 最低版本：Android 10 (API 29)，targetSdk 33
- **不需要 root 即可安装使用**；root / 无障碍 / 截屏授权是**可选增强层**，缺失时自动隐藏对应入口

---

## 目录结构

```
seagull-desktop/
├── README.md                  # 本文件
├── FEATURE-MATRIX.md          # 功能对齐清单（205 条，逐条状态 + 可行性分类）
├── TODO.md                    # 后续开发清单（P0/P1/P2 + 完成定义）
├── app/                       # 应用源码（Java，无 Gradle，自建构建脚本）
│   ├── src/com/seagull/carlauncher/
│   ├── res/
│   ├── AndroidManifest.xml
│   └── build.sh               # 7 步无 Gradle 构建流水线
├── docs/
│   ├── ARCHITECTURE.md        # 架构与模块职责
│   ├── TECHNIQUES.md          # 关键技术：L0~L3 窗口档位、MediaProjection、触摸注入
│   ├── ORIGINAL-ANALYSIS.md   # 野菜桌面逆向分析结论
│   ├── REFERENCE-REPOS.md     # 参考仓库索引与可复用点
│   └── DEVICE-NOTES.md        # 真机环境笔记（Redmi K60 / HyperOS / KernelSU）
└── reference/                 # 参考资料（原样留档，便于溯源）
    ├── carlink-desktop/       # 参考仓库 1：关键 Kotlin 源码 + 全部文档
    ├── carplay-re/            # 参考仓库 2：分析报告 + PiP 适配器源码
    └── original-yecai/        # 目标应用：1173 条 UI 文案 + 9 个业务类反编译源码
```

---

## 快速开始

### 构建

```bash
cd app
bash build.sh
# 产物：out/SeagullLauncher.apk
```

构建链路（无 Gradle）：`aapt2 链接资源 → 生成 R.java → javac → d8 → 组包 → zipalign → apksigner`

依赖（Termux / 类 Unix 环境）：`aapt2`(arm64)、JDK 17+、`d8`(R8)、`apksigner`、`android.jar`(API 33)

> **本机环境注意**：`JAVA_TOOL_OPTIONS=-Duser.home=$HOME`，且不能用 `/tmp`。
> 详见 `docs/DEVICE-NOTES.md`。

### 安装

```bash
# 先落到 /data/local/tmp 再装（直接装外部存储的路径常被拒）
cp out/SeagullLauncher.apk /data/local/tmp/
pm install -r /data/local/tmp/SeagullLauncher.apk
```

### 设为默认桌面

设置 → 应用 → 默认应用 → 桌面 → 选「海鸥桌面」
（或桌面内「设置 → 设为默认桌面」跳转）

---

## 设计原则

1. **公开优先**：核心功能只用标准公开 API。任何需要 root / 平台签名 / 特殊权限的能力都做成
   「有则增强，无则隐藏」，绝不因为缺权限而崩溃或拒绝启动。
2. **单一事实源**：所有配置（布局 / Dock / 快捷栏 / 菜园 / 外观 / 天气 / 歌词 / 任务）
   收在 `LauncherModel` 一处，落在同一个 SharedPreferences 文件（`HomeActivity.PREFS`，主键 `json_layout`）。
   UI 只读写这一处，禁止各自开 prefs 文件。
3. **无账号**：不做登录、不做会员、不做云端存档、不做分享码。
   备份/恢复只走本地文件导入导出。
4. **可逆**：每个副作用（服务、监听、悬浮窗、定时器）都要能随模块关闭而撤销。

---

## 当前进度

| 指标 | 数值 |
|---|---|
| 源码规模 | 26 个 Java 文件 / 约 4600 行 |
| 功能清单条目 | 205 条（5 组 / 17 分区） |
| 已完成 ✅ | 18 条（8%） |
| 部分完成 🟡 | 9 条 |
| 未做 ⬜ | 161 条 |
| 平台不可达 ⛔ | 17 条 |

逐条状态见 **`FEATURE-MATRIX.md`**，开发顺序见 **`TODO.md`**。

### 已经跑通的

- **L3 虚拟屏窗口**（root 增强层）：`MediaProjection.createVirtualDisplay` 建屏 +
  root `am start --display <id>` 把任意第三方应用搬上去 + root `input -d <id>` 注入触摸。
  真机验证通过（`DisplayDeviceInfo{"seagull-l3test", 960x540, type VIRTUAL, owner com.seagull.carlauncher}`）。
- **L2 系统画中画**：标准 PiP API，无 root 可用。
- **应用网格 / Dock / 文件夹 / 启动应用**：公开 API。
- **系统操作层**：亮度、音量、Wi-Fi、蓝牙、飞行模式、自动校时、强停、清理缓存。

### 关键结论

`ADD_TRUSTED_DISPLAY` 是 `signature|role` 级权限，**普通 app 无法调 `DisplayManager.createVirtualDisplay`**。
但 `MediaProjection.createVirtualDisplay` 走的是另一条路，**公开 app 也能建虚拟屏**——
这是本项目多窗口方案的基础。详见 `docs/TECHNIQUES.md`。

---

## 相关仓库

| 仓库 | 用途 |
|---|---|
| [caogenfunan123/carlink-desktop](https://github.com/caogenfunan123/carlink-desktop) | 核心窗口/镜像/root 技术来源（Kotlin） |
| [caogenfunan123/carplay-reverse-engineering](https://github.com/caogenfunan123/carplay-reverse-engineering) | 车机 PiP / 触摸转发逆向分析 |

两个仓库的关键源码与分析报告已留档在 `reference/`，便于离线溯源。

---

## 许可

本项目源码用于学习与自有设备使用。`reference/` 下材料版权归各自作者。
目标应用「野菜桌面」为第三方商业软件，本项目**不包含**其任何代码，
仅依据公开可见的 UI 文案与设置结构做功能对齐。
