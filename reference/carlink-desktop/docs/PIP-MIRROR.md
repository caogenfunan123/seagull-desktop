# 画中画镜像机制（0.2-m7 落地版）

本篇是**实现说明**，只讲代码里真实跑的东西。完整逆向取证、复盘、方法论教训在
[`REVERSE-AND-PLAN.md`](REVERSE-AND-PLAN.md) 第 9、11 部分。

---

## 1. 一句话机制

**双画中画 = 虚拟屏镜像 + SurfaceView 上屏 + root 跨屏拉起 + root 触摸转发。**
全程**不依赖目标进程内的任何 hook**（不做 setprop，不钩 `ActivityOptions`）。

对应车联助手 X 的 `b1/c`(PipUI) + `b1/b`(SurfaceHolder 建虚拟屏) + `c1/h`(root 部署) +
`b1/d`(root 触摸转发) 四段，逐行 smali 核验而来。

---

## 2. 为什么 m4~m6 全错（前车之鉴，别再走）

| 错误方案 | 为什么不通 |
|---|---|
| `setprop persist.carlink.pip.*` + 目标进程内读 prop | 属性读回来是对的，但没人拿它去建屏/改尺寸，画面自然不出 |
| 在目标进程内钩 `ActivityOptions.getBounds/setLaunchBounds` | **ActivityOptions 由 Launcher/SystemUI 侧构建并消费**，钩目标进程内新起 Activity 时用的那份 options 改不了真实落位尺寸 |

根因：**接管"屏幕"这件事必须发起方（我们）来建虚拟屏并把目标拉进去，
而不是跑到目标进程里去改它的窗口参数。**

---

## 3. 参考版 vs 我们的合法等价物

| 环节 | 车联助手（签名权限齐全） | CarLink（普通签名 + root） |
|---|---|---|
| 建虚拟屏 | `DisplayManager.createVirtualDisplay("gdj",w,h,dpi,0,surface,…)`（@SystemApi，**无 token**） | `MediaProjection.createVirtualDisplay("carlink-pipN",w,h,dpi, PUBLIC\|OWN_CONTENT_ONLY\|PRESENTATION, surface,…)` |
| 凭什么能这么干 | `CAPTURE_SECURE_VIDEO_OUTPUT`/`INJECT_EVENTS`/`WRITE_SECURE_SETTINGS` + LSPosed 作用域含 systemui | 一次系统录屏授权（`appops PROJECT_MEDIA allow` 预放行）+ root |
| 画面去处 | SurfaceView（`ModSurfaveView`）Surface | 我们 `activity_main.xml` 里 `a_pip_surface`/`b_pip_surface`（SurfaceView）的 Surface |
| 拉起目标 | `am start --display <id> -f 0x10104000 -n <comp>`（root） | 同：`RootAuth.launchOnDisplay` |
| 触摸回注 | `su -c "input -d <id> tap\|swipe"`（b1/d） | 同：`MirrorSlot.onTouch` → `RootAuth.runQuiet` |

> `MediaProjection.createVirtualDisplay` 是普通/root 应用合法拿虚拟屏的唯一正道；
> 对"把某个 App 起进虚拟屏、画面渲染进我们 Surface"这个目标，视觉效果与参考版一致。

---

## 4. Android 14 授权时序（最容易踩的坑）

Android 14 起，拿 `MediaProjection` token 前必须**严格满足**：

```
createScreenCaptureIntent() → RESULT_OK（用户同意录屏）
   → 启动一个 foregroundServiceType=mediaProjection 的前台服务并完成 startForeground
      → getMediaProjection(resultCode, data) → 拿到 token
         → createVirtualDisplay(...)   ← 早于上面任何一步调用都抛 SecurityException
```

因此本项目把录屏服务**单拆**成 `svc/PipProjectionService`（mediaProjection 型），
而**不能**挂到开机自启的 `DesktopService`（specialUse 型）上——一个服务只能声明一种类型组合，
且 mediaProjection 前台服务必须在"已同意"之后才起。`MainActivity` 负责把
「同意 → 起服务 → 轮询服务 running → 取 token → deploy」这条链串起来
（`awaitProjectionToken` 每 200ms 轮询，`tries > 10` 仍未起则放弃并提示"投屏前台服务未能启动"）。

---

## 5. 关键类职责

### `mirror/MirrorSlot`（一个镜像槽的全部生命周期）
- `deploy(mp, pkg, surface, w, h, dpi)`：**幂等**。签名 `sig = pkg|surface标识|WxH`；
  没变直接返回 true。已有 VD、只是 surface/尺寸变 → 走 `vd.resize + vd.setSurface`，**不重建**（目标不重启）。
- `onTouch(e)`：DOWN 记坐标/时间；UP 位移 ≥10px 判 `swipe`（时长夹 50~2000ms）否则 `tap`；
  投到**单线程 HandlerThread** 串行执行（修原版 exec 乱序）；`ACTION_CANCEL` 显式丢弃（修原版漏处理 → 偶发吞点击）。
- `detachSurface()`：surfaceDestroyed 只 `setSurface(null)`，**保留 VD 与目标任务栈**，下次 surface 回来再挂。
- `teardownVd()/teardown()`：真正释放（切走目标/退出时）。

### `svc/RootAuth`（su 会话，全部后台线程）
- `grantAll`：开悬浮窗 appops + 追加式写 `enabled_notification_listeners`（先 get 再拼，避免覆盖别人）+ `pm grant POST_NOTIFICATIONS`。
- `allowProjectMedia`：`appops set <pkg> PROJECT_MEDIA allow`（镜像前置）。
- `launchOnDisplay`：先 `getLaunchIntentForPackage` 解析组件，兜底 `cmd package resolve-activity --brief`，
  再 `am start --display <id> -f 0x10104000 -n <comp>`。
- `runQuiet`：单条静默命令（触摸注入用，3s 超时）。
- `readDisplays`：`dumpsys display | grep -oE 'carlink-pip[0-9]+'` → 诊断"虚拟屏到底建没建"。

### `svc/PipProjectionService`
mediaProjection 型前台服务，`@Volatile running` 供 `awaitProjectionToken` 轮询；START_NOT_STICKY。

### `ui/MainActivity`
- `wireMirrorSurface(sv, slot)`：SurfaceHolder.Callback 驱动 `onSurfaceReady → deploySlot` / `surfaceDestroyed → detachSurface`；
  `setOnTouchListener` 在 ready 时转 `slot.onTouch`。
- `updateMirrorSlots`：读设置决定 want1/want2；null 就 teardown 对应槽；两槽都关且有 projection 就
  `projection.stop()` + 停服务；surface 就绪且已有 token 才 deploy。
- `onActivityResult(REQ_PIP_CONSENT)`：存 code/data → 起服务 → 轮询 → `getMediaProjection` →
  注册 `Callback.onStop`（授权被系统撤销时清槽清服务）→ `updateMirrorSlots`。
- `onDestroy`：teardown 两槽 + `projection.stop()` + 停服务。

### `lsp/WFreeHook`（H4 已删）
保留 H1~H3（真实系统 PiP 相关，含高德强制比例那条路）；**删除** m4~m6 误加的
`getBounds` 接管与 `persist.carlink.pip.*` 常量。代码里留有注释记录"为什么删"。

---

## 6. 布局（`res/layout/activity_main.xml`）

A 区 / B 区各叠三层，z 序自下而上：
`placeholder` → `*_pip_surface`(SurfaceView) → `*_pip_wait`(#80000000 遮罩，未出画面时提示)。
`b_pip_placeholder` 老布局已删，提示文案改由 `b_pip_wait` 在
`b_pip_need_pkg` / `a_mirror_active` 间切换。

---

## 7. 诊断对照（用户零命令，全看 App）

| 现象 | 判读 |
|---|---|
| `镜像: 未见虚拟屏（未部署/已释放）` | 槽没部署：没选应用 / 录屏授权被拒 / 无 root |
| `镜像: carlink-pip1`（或 +pip2） | 虚拟屏已建；黑屏则查 `am start --display`（`lastError` 会写） |
| 有画面但点击不响应 / 偶发吞点击 | 触摸回注（已修 CANCEL+串行；复现再报） |
| `槽N 镜像 <pkg> → displayId=M WxH` | 部署成功回执（MainActivity 诊断行） |

---

## 8. 仍未在真机验证（诚实，勿当已确认）

录屏授权在定制 ROM 的行为、目标在虚拟屏的方向、触摸实测延迟、OEM 是否杀 mediaProjection 服务。
详见 [`REVERSE-AND-PLAN.md` §11.8](REVERSE-AND-PLAN.md)。
