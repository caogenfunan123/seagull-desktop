# 车机互联软件逆向工程资料库

本仓库归档车机互联软件的**逆向分析报告、反编译源码、已构建 APK** 以及配套的源码工程，供独立软件重写与技术研究使用。

> 本仓库为私有仓库，内容仅用于技术研究与独立软件开发，请遵守所涉软件的使用协议与相关法律法规。

## 目录结构

| 目录 | 内容 |
| --- | --- |
| `docs/` | 逆向分析报告（HTML，含完整技术文档与字体/JS 资源） |
| `decompiled/` | 反编译源码完整打包（smali / res / AndroidManifest / apktool.yml，tar.gz） |
| `apks/` | 各版本已构建 APK 及相关参考 APK |
| `sources/` | 重写所需的 Java 源文件与 LSPosed 工程骨架 |

## docs 分析报告

- `docs/carplay-reverse-engineering.html` — **主分析报告**：覆盖高德导航 Hook 的 19+1 个挂载点、广播数据契约、画中画（PiP）、悬浮窗、构建部署与重写排期（共 31 章）。
- `docs/pip-touch-analysis.html` — 画中画 + 触摸链路的专项分析。
- `docs/_shared-report*` — 对应报告的字体 / 静态度量资源（报告离线下仍可完整渲染）。

## decompiled 反编译源码

- `decompiled/dec21-decompiled.tar.gz` — 反编译完整包，解压后为 apktool 标准结构：`smali/`、`res/`、`AndroidManifest.xml`、`apktool.yml`、`original/`。

## apks 已构建软件

| 文件 | 说明 |
| --- | --- |
| `carplay_v10` ~ `carplay_v21` | 车机互联主程序各迭代版本（`v20_signed`、`v21` 为较新版本） |
| `carplay_ucar_v1/v2/v3` | 接入 UCar 车机地图 AIDL 的版本演进而 |
| `carlink_carplay*` / `carlink_modified` | carlink 形态的打包 |
| `车联助手_网易云音乐PiP适配.apk` | 网易云音乐 PiP 适配打包 |
| `CarWith-4.0.4.apk` | 参照版 CarWith 原包（参考导航信息卡片实现） |
| `netease-original.apk` | 网易云原始包（PiP 适配的分析对象） |
| `latest.apk` | 最新参考包 |
| `base-referenced.apk` | 大体积基准参考包（经 Git LFS 管理） |

## sources 源码

- `xphook_integrated.java` — 整合后的 LSPosed 主入口分发逻辑。
- `wangyihook_HookEntry_integrated.java` — 网易云 Hook 入口整合。
- `netease-pip-adapter/` — 网易云音乐 PiP 适配的 LSPosed 工程骨架（Gradle 可构建）。

## 复刻步骤速览

1. 阅读 `docs/carplay-reverse-engineering.html` 第 30 章（高德 Hook 定位表）与第 25 章（数据契约）。
2. 解压 `decompiled/dec21-decompiled.tar.gz` 获取 smali 参照。
3. 按报告第 31 章里程碑 M1→M8 搭建新工程；LSPosed 作用域需勾选高德 / SystemUI 等目标包。