# 真机环境笔记

开发与验证使用的设备环境。换设备时先看这一页。

---

## 一、设备

| 项 | 值 |
|---|---|
| 机型 | Redmi K60（代号 `mondrian`） |
| 系统 | HyperOS / Android 14（SDK 34） |
| 屏幕 | 1080 x 2400，density 560（2.625x） |
| 构建标记 | `ro.build.tags=release-keys`，`ro.debuggable=0` |
| Bootloader | 已锁 |
| Root | KernelSU 3.2.5（`/system/bin/su`，SELinux context `u:r:ksu:s0`） |
| LSPosed | 运行中（本项目不使用） |

### 屏幕布局要点

```
y=0    .. ~90     状态栏 / 挖孔区（纯黑）
y≈98               顶栏开始渲染
y=2360 .. 2399     手势导航条（约 40px）
```

→ 布局必须留**底部 inset**，否则内容被手势条压住。

### 默认配色

| 用途 | 色值 |
|---|---|
| 地面 ground | `#0b100c` |
| 面板 panel | `#131a15` |
| 卡片 card | `#1b241d` |
| 叶绿 leaf | `#8cc26a` |
| 文字 text | `#e8efe9` |
| 次要文字 text_dim | 低于 text |

---

## 二、构建环境（Termux）

### 必需变量

```bash
export JAVA_TOOL_OPTIONS=-Duser.home=$HOME
```

沙箱环境下 `$HOME` 指向应用私有目录时才可写；不设这个变量 JDK 会往不可写的默认 home 写缓存然后失败。

### 不能用 `/tmp`

Termux 的 `/tmp` 不可写或不存在，构建脚本里所有临时路径都放在工作目录下。

### 工具链位置

| 工具 | 路径 |
|---|---|
| `aapt2` (arm64) | Termux `$PREFIX/bin/aapt2` |
| JDK | Termux openjdk-17 |
| `d8` (R8 9.5.20) | `~/carlauncher/libs/r8.jar` |
| `android.jar` | `~/carlauncher/libs/android.jar` (API 33) |
| `apksigner` | Termux `$PREFIX/bin/apksigner` |

**R8 版本坑**：8.2.2 的 `d8` 会抛 NPE，换 9.5.20 正常。

### 构建命令

```bash
cd ~/carlauncher
export PATH=$PREFIX/bin:$PATH
export JAVA_TOOL_OPTIONS=-Duser.home=$HOME
bash build.sh
```

构建脚本 7 步：`aapt2 link → R.java → javac --release 11 → d8 → zip 组包 → zipalign → apksigner`，
任一步出现 `error:` 立即退出（exit 1）。

---

## 三、真机操作注意事项

### 3.1 root shell 的挂载命名空间是过滤过的

```bash
# 从 root shell 看：
$ ls /data/data/ | wc -l
2                      # ← 只有 2 个！不是真实的
```

**不要把「看不到应用数据目录」当成「目录不存在」。**
要判断应用自己的持久化状态，让应用打日志上报：

```java
android.util.Log.i("SeagullDiag",
    "dataDir=" + dir.getAbsolutePath() + " xml=" + xml.getAbsolutePath()
    + " exists=" + xml.exists() + " size=" + xml.length());
```

```bash
logcat -d -s SeagullDiag
# I SeagullDiag: dataDir=/data/user/0/com.seagull.carlauncher
#   xml=/data/user/0/com.seagull.carlauncher/shared_prefs/seagull.xml
#   exists=true size=271 | mode=WIDGET_TOP dock=0 widgets=4 apps=156
```

### 3.2 `pm` / `am` 必须经 root

普通 shell 调用会报：

```
SecurityException: Permission Denial: runUninstall from pm command asks to run as user -1
You either need MANAGE_USERS or CREATE_USERS permission to: query users
```

### 3.3 安装前先落到 `/data/local/tmp`

直接 `pm install` 外部存储路径常被拒。流程：

```bash
cp out/SeagullLauncher.apk /data/local/tmp/seagull.apk
pm install -r /data/local/tmp/seagull.apk
```

### 3.4 `am force-stop` 一次只能带一个包名

```bash
am force-stop com.a com.b      # ✗ 无效
am force-stop com.a            # ✓
```

### 3.5 `am start` 会静默投递到已运行实例

```
Warning: Activity not started, intent has been delivered to currently running top-most instance
```

搬屏前必须先 `am force-stop`，否则目标应用仍在主屏。

### 3.6 `root_exec` 的调用约定（本工具环境）

- 多语句命令要包在 `sh -c '...'` 里
- 管道最后一条命令返回非 0 会导致整个调用报 `Command failed`
- 长命令拆分调用，避免 `kill EPERM`

### 3.7 不要频繁重启

重启会打断用户正在做的事。验证一律优先用
`am force-stop <目标>` + 重新拉起，而不是重启设备。

---

## 四、本机已验证通过的结论

| 项 | 结果 |
|---|---|
| `DisplayManager.createVirtualDisplay` | ✗ 被拒（需 `ADD_TRUSTED_DISPLAY`，`signature\|role`） |
| `MediaProjection.createVirtualDisplay` | ✓ 成功，owner 为本应用 |
| root `am start --display <id>` | ✓ 应用被搬到虚拟屏 |
| root `input -d <id> tap/swipe` | ✓ 注入成功，目标响应 |
| 公开 API `setLaunchDisplayId` | ✗ 对普通 app 无效 |
| `INJECT_EVENTS` | ✗ denied |
| `MediaSessionManager.getActiveSessions()` | ✗ 需 `MEDIA_CONTENT_CONTROL` 或通知使用权 |
| `isPerformGesturesEnabled` | ✓ true（无障碍手势可用） |
| SharedPreferences 落盘 | ✓ 修正 PREFS 名称 + `commit()` 后正常 |

### 虚拟屏验证证据

```
DisplayDeviceInfo{"seagull-l3test", 960 x 540, modeId 1, defaultModeId 1,
  state ON, type VIRTUAL, owner com.seagull.carlauncher (uid 10350)}

Display #11 (activities from top to bottom):
  * Task{6f01801 #27654 type=standard A=10344:com.android.deskclock ...}
```

---

## 五、目标应用装不上的原因（结论）

`cn.wayecai.launcher` 声明 `android:sharedUserId="android.uid.system"`，
但用泄漏的 AOSP testkey 签名。安装报：

```
INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: ... signing lineage that diverges
```

这是**签名链条校验**，不是配置问题，无法通过改配置绕过。
详见 `ORIGINAL-ANALYSIS.md`。
