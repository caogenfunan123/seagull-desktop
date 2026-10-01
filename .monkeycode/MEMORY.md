# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[统一签名：以后都用一个签名文件]
- Date: 2026-10-01
- Context: 用户要求"弄个签名密钥把统一一下签名，以后都用一个签名文件，写md文档"
- Instructions:
  - 所有构建使用同一把钥匙 `app/keystore/seagull-release.keystore`（alias seagull-release，RSA 2048，10000 天）
  - 钥匙与密码只放两处：本地 `app/keystore/`（.gitignore）+ GitHub secrets（SEAGULL_KEYSTORE_B64 / SEAGULL_KS_PASS）；严禁进仓库、聊天、issue
  - CI 在编译前从 secret 还原钥匙；build.sh 找不到钥匙才现场生成并大声警告
  - 签名不一致 = 安装报 INSTALL_FAILED_UPDATE_INCOMPATIBLE，只能卸载重装；验证用 `apksigner verify --print-certs` 比对 SHA-256
  - 详见 docs/SIGNING.md

[画中画界面只要两个画布]
- Date: 2026-10-01
- Context: 用户对 L3 镜像页面的明确要求："界面只要两个画布！长按选择应用！不要多余的东西！"
- Instructions:
  - MirrorActivity 界面上只允许有两块 SurfaceView 画布（各占半屏），其余按钮/诊断/机制说明一律不放
  - 选应用交互 = 长按画布（长按被 GestureDetector 截走，不转发进画中画）；已选画布再长按可更换，对话框里有"清空该槽"
  - 诊断信息只进 logcat（tag=SeagullMirrorAct / SeagullMirror / SeagullRootOps），不在界面展示
  - 空画布中央只留一行提示文字（"长按选择应用"/"启动中…"），不算多余元素

[画中画必须是首屏 + 桌面默认横屏 + 界面精简]
- Date: 2026-10-01
- Context: 用户四条要求："画中画左右分割""桌面默认横屏""进入应用应该是画中画界面，不应该是设置里的子界面""界面太多不合理的地方精简一下"
- Instructions:
  - 打开应用（HomeActivity）默认落在画中画界面；画中画两块画布左右分割（各占一半宽）
  - 桌面默认横屏：LauncherModel.orientation 默认值用 LANDSCAPE
  - 桌面网格这些既有界面降为次级层（底栏按钮切换），不砍功能，但把只服务画中画的子页面入口（底栏"镜像小窗"）删掉，含义不明的按钮（"叶"键）也删
  - 以后新增"画中画相关"界面优先做成可复用 View（PipBoard）嵌进首屏，不要再开新 Activity 当子页面

[CarPlay 纪律移植：单焦点/卡片化/空态/MiniPlayer]
- Date: 2026-10-01
- Context: 用户拿来苹果 CarPlay 互联界面作参照，拍板四条移植（批次 N）
- Instructions:
  - 触摸只进焦点画布：非焦点画布的第一下触摸只切焦点不吃进 App；切换瞬间给失焦画布补 ACTION_CANCEL（多指鬼拖痕靠它）
  - 空画布例外：没有 App 可误触，第一下直接弹选择器（可发现性优先于焦点语义）
  - 空态大按钮 ≥80dp 高 + 16dp 圆角 + 半透明白描边；按下态 alpha 0.6 兜底（车机无震动马达）
  - SurfaceView 是窗外合成，clipToOutline 圆角裁剪部分设备失效——卡片化只做卡底+描边+缝，真圆角等 TextureView 实测
  - 非焦点画布黑遮罩跟环境光三档：TYPE_LIGHT 采样（公开 API 免权限）+ 1s 低通 + 20/2000lux 回差
  - MiniPlayer 双场景点击规则：媒体源在画布内→导焦，在后台→什么都不做（绝不挤占当前应用）
  - 分割权重锁 1:1 但配置化（pipWeightA/pipWeightB）；DiPlay 接入走"埋接口不接线"节奏

[Project Knowledge Summary]
- Date: 2026-10-01
- Context: Discovered by Agent while fixing "每次进入桌面还是应用界面"（批次 L）
- Category: Troubleshooting & Debugging
- Instructions:
  - 退出镜像页拆屏（vd.release）会把屏上任务倒回默认屏，桌面立刻冒出全屏应用——这是该抱怨的直接机制
  - 因此 VD 生命周期挂进程级 MirrorHost，退出只 detachSurface；只有"清空该槽"才拆屏+force-stop
  - 旧 CI 未配签名 secret 时每次构建随机生成密钥，两次产物证书 SHA-256 不同；用户每次装新包都要卸载

[Project Knowledge Summary]
- Date: 2026-10-01
- Context: Discovered by Agent while verifying cancelStroke in batch N
- Category: Troubleshooting & Debugging
- Instructions:
  - ACTION_CANCEL 精准送进画中画 App 只有一条路：守护中继通道（TouchForward DaemonSink）；root `input` 命令通道的 swipe 手势不可收回
  - MediaSessionManager.getActiveSessions 要求调用方是已启用的通知监听器——MediaListenerService 正合适，MiniPlayer 数据源复用它，不另起服务不加权限
  - SensorManager.TYPE_LIGHT 是公开 API 且不需要权限，环境光三档遮罩用它，不用 SensorPrivacyManager
