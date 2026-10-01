# NetEase Cloud Music PiP Adapter (Xposed Module)

## 问题
网易云音乐IoT版在CarWithX的PIP2区域运行时，点击封面、标题等UI元素会跳转到主车机屏，因为这些点击触发的 `startActivity()` 没有设置 `setLaunchDisplayId()`。

## 解决方案
Hook网易云音乐的 `startActivity()` 调用，在每次启动Activity前注入 `ActivityOptions.setLaunchDisplayId()`，强制Activity在当前VirtualDisplay上启动。

## 安装步骤

### 1. 编译APK
```bash
cd netease-pip-adapter
gradle assembleRelease
# 或使用 Android Studio 打开项目编译
```

### 2. 安装到设备
```bash
adb install app/build/outputs/apk/release/app-release.apk
```

### 3. 在LSPosed中启用模块
1. 打开 LSPosed Manager
2. 进入「模块」页面
3. 找到「NetEase PiP Adapter」并启用
4. 在作用域中勾选 `com.netease.cloudmusic.iot`（网易云音乐IoT版）
5. 强制停止网易云音乐并重新打开

## 验证
在LSPosed日志中应看到：
```
NetEasePiPAdapter: Loaded into com.netease.cloudmusic.iot
NetEasePiPAdapter: All hooks installed successfully
NetEasePiPAdapter [H2]: Injected displayId into startActivity(Intent, Bundle)
```

## Hook点说明

| Hook | 目标方法 | 作用 |
|------|---------|------|
| H1 | `Activity.startActivity(Intent)` | 拦截无Bundle版本，注入displayId |
| H2 | `Activity.startActivity(Intent, Bundle)` | 向已有Bundle注入displayId |
| H3 | `Context.startActivity(Intent)` | 非Activity上下文（Service等） |
| H4 | `Context.startActivity(Intent, Bundle)` | 非Activity上下文带Bundle |
| H5 | `Activity.startActivityForResult(Intent, int, Bundle)` | startActivityForResult带Bundle |
| H6 | `Activity.startActivityForResult(Intent, int)` | startActivityForResult无Bundle |

## 工作原理
1. 当用户在PIP2区域点击网易云音乐UI元素
2. 网易云内部调用 `startActivity(intent)` 启动播放器Activity
3. Hook拦截此调用，获取当前Activity所在Display ID
4. 创建 `ActivityOptions` 并设置 `setLaunchDisplayId(displayId)`
5. 使用带Bundle的 `startActivity(intent, options)` 重新发起调用
6. Activity在VirtualDisplay上启动，不会跳到主屏

## 需要的依赖
- LSPosed (或 EdXposed) 已安装并激活
- Xposed API 82+
- 设备已Root
