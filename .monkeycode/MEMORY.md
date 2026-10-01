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

[Project Knowledge Summary]
- Date: 2026-10-01
- Context: Discovered by Agent while fixing "每次进入桌面还是应用界面"（批次 L）
- Category: Troubleshooting & Debugging
- Instructions:
  - 退出镜像页拆屏（vd.release）会把屏上任务倒回默认屏，桌面立刻冒出全屏应用——这是该抱怨的直接机制
  - 因此 VD 生命周期挂进程级 MirrorHost，退出只 detachSurface；只有"清空该槽"才拆屏+force-stop
  - 旧 CI 未配签名 secret 时每次构建随机生成密钥，两次产物证书 SHA-256 不同；用户每次装新包都要卸载
