# 统一签名密钥（唯一签名文件）

## 一句话

以后所有构建都用同一把钥匙：`app/keystore/seagull-release.keystore`（别名 `seagull-release`）。
配好之后每个 APK 签名完全一致，覆盖安装不用卸载、不丢桌面配置。

## 为什么要有这份文档

旧版 `app/build.sh` 的逻辑是「找不到 `app/debug.keystore` 就现场生成一把」。
CI 上没配 secret 时，**每次构建都会生成一把新钥匙** —— 签名每次都不一样：

| 构建 | 证书 SHA-256 |
|---|---|
| run #8（批次 J） | `5e1bc20eb50f31c0a1f2519340e969ff4c31d834ca230d196f31be8b61107e20` |
| run #10（批次 K） | `eb720411e7dead190b9b8d787bea3e3ee4e27591963aa86a4f55f2ba6db53446` |

后果：往已装过的机器上装新包必报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`，
只能先卸载（桌面图标位置、菜园、镜像绑定全部清空）。

**注意**：第一次换到统一签名的包时，设备上已有的旧版仍是随机签名，
所以**这一个版本需要手动卸载一次**；此后永久不再需要卸载。

## 钥匙与密码放在哪

| 内容 | 位置 | 进仓库？ |
|---|---|---|
| 私钥文件 `seagull-release.keystore` | 本仓库 `app/keystore/`（构建机/CI 用） | 否（`.gitignore` 有 `keystore/`） |
| 私钥文件同一把 | GitHub secret `SEAGULL_KEYSTORE_B64`（base64） | — |
| 密码 | `app/keystore/seagull-release.properties` | 否（同上被 ignore） |
| 密码 | GitHub secret `SEAGULL_KS_PASS` | — |

规范：**密钥文件和密码一律不进仓库、不进聊天记录、不进 issue**。
仓库里只放文档和「从哪拿」的说明。

## 构建时怎么用

`app/build.sh` 第 7 步签名，取钥匙的优先级：

1. `app/keystore/seagull-release.keystore` 存在 → 直接用它签；
2. 不存在 → 现场生成一把并在日志里大声警告「仅本次有效」。

密码读取优先级：`SEAGULL_KS_PASS` 环境变量 →
`app/keystore/seagull-release.properties` 的 `ksPass` → 兜底默认值。

CI（`.github/workflows/build-apk.yml`）在编译前一步「还原统一签名密钥」：
把 secret 里的 base64 解回 `app/keystore/seagull-release.keystore`
并写好 properties，再以 `SEAGULL_KS_PASS` 环境变量传给 build.sh。
两个 secret 都配了，日志会打 `用固定签名密钥（SEAGULL_KEYSTORE_B64 已还原）`；
缺任何一个只会 warning 并退回临时密钥，不阻断构建。

## 本地验证签名一致

```bash
# 看一个包的签名证书指纹
apksigner verify --print-certs SeagullLauncher.apk | grep "SHA-256 digest"
```

新旧两个包打出同一行指纹，才叫签名统一。

## 备份与轮换

- **备份**：`app/keystore/` 整个目录另外拷一份到只有你能拿到的地方（本地加密盘 /
  密码管理器附件）。secret 只能写不能读，丢了就再从备份恢复。
- **恢复**：把 keystore 重新 `gh secret set SEAGULL_KEYSTORE_B64 < base64 文件`，
  密码同步进 `SEAGULL_KS_PASS`，本地 properties 同名写回。
- **轮换**（换一把新钥匙）＝ 所有已装设备必须卸载重装一次，且旧包再也不能覆盖升级。
  没有充分理由不要轮换；真要轮换，先按上面流程生成新钥匙、传好 secret 再发版。

## 排障

| 现象 | 原因 | 处理 |
|---|---|---|
| 安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 新旧包签名不一致 | 换统一签名后的第一个包：卸载旧版再装；之后不会再出现 |
| CI 日志出现 `为临时密钥` warning | secret 没配/被清 | 按「备份与恢复」重设 `SEAGULL_KEYSTORE_B64`、`SEAGULL_KS_PASS` |
| `apksigner` 报密码错误 | properties / env / secret 三处密码不一致 | 三处统一成同一密码 |
