#!/usr/bin/env bash
# 海鸥桌面 — 纯命令行 APK 构建（无 Gradle）
# 默认按 Termux 宿主运行；CI / 桌面环境用环境变量覆盖：
#   SEAGULL_SDK         build-tools 所在目录的父目录（需含 build-tools/<ver>/）
#   SEAGULL_ANDROID_JAR android.jar 路径
#   SEAGULL_JAVA_HOME   JDK 路径
set -e

P="$(cd "$(dirname "$0")" && pwd)"
SDK="${SEAGULL_SDK:-$HOME/.dsh/ubuntu-rootfs/root/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
[ -x "$BT/aapt2" ] || BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | tail -1)"
ANDROID_JAR="${SEAGULL_ANDROID_JAR:-$P/libs/android.jar}"
JAVA_HOME="${SEAGULL_JAVA_HOME:-/data/data/com.dsharnessmobile.shell/files/usr/lib/jvm/java-21-openjdk}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Duser.home=$HOME}"
export PATH="$BT:$JAVA_HOME/bin:$PATH"

MIN_API=29
TARGET_API=33

# 版本号必须每次构建都不同（批次 Y）。之前写死 --version-code 1 --version-name 1.0：
#   · GitHub release tag 恒为 v1.0 → 「检查更新」拿 1.0 跟本机 1.0 比，永远回「已是最新」
#   · 用户因此长期停在旧包上，之后每批 LSPosed 模块改动都"像没生效一样"
# 现在：versionName = 1.0.<git 短哈希>（人眼可核对是不是当前提交），
#       versionCode  = unix 秒（单调递增，2038 年前不溢出 int）。
# 不在 git 仓库里时退回 1.0 / 1，别让构建挂掉。
VERSION_NAME="1.0"
if git -C "$P" rev-parse --short HEAD >/dev/null 2>&1; then
  VERSION_NAME="1.0.$(git -C "$P" rev-parse --short HEAD)"
fi
VERSION_CODE="$(date +%s)"
echo "版本：$VERSION_NAME ($VERSION_CODE)"

# 统一签名密钥（批次 L 起，详见 docs/SIGNING.md）：
#   keystore/seagull-release.keystore   唯一签名文件，本地与 CI 同一把
#   密码优先级：SEAGULL_KS_PASS 环境变量 > keystore/seagull-release.properties > 兜底默认值
# keystore/ 与 *.properties 都在 .gitignore 里，密钥本身不进仓库；
# CI 从 secret SEAGULL_KEYSTORE_B64 还原同一把钥匙，保证每个包签名一致。
KS_DIR="$P/keystore"
KS="$KS_DIR/seagull-release.keystore"
KS_ALIAS=seagull-release
KS_PASS="${SEAGULL_KS_PASS:-}"
if [ -z "$KS_PASS" ] && [ -f "$KS_DIR/seagull-release.properties" ]; then
  KS_PASS="$(grep '^ksPass=' "$KS_DIR/seagull-release.properties" | head -1 | cut -d= -f2-)"
fi
[ -n "$KS_PASS" ] || KS_PASS=seagull-release

# LSPosed 模块（批次 W）：XposedBridge 签名桩只进编译期，运行时由 LSPosed 提供，
# 见 app/libs/xposed-api-82-stub-src/。缺了它 XposedEntry 编不过。
XSTUB="$P/libs/xposed-api-82.jar"
[ -f "$XSTUB" ] || { echo "缺 $XSTUB（Xposed 签名桩，用 app/libs/xposed-api-82-stub-src 重新打）"; exit 1; }
[ -d "$P/assets" ] || { echo "缺 $P/assets（xposed_init 所在目录）"; exit 1; }

OUT="$P/out"
# 每次构建用独立子目录，避免清空历史产物（构建机不删文件）
STAMP="$(date +%Y%m%d-%H%M%S)"
RESC="$OUT/res-$STAMP"
GEN="$OUT/gen-$STAMP"
CLASSES="$OUT/classes-$STAMP"
DEX="$OUT/dex-$STAMP"
mkdir -p "$RESC" "$GEN" "$CLASSES" "$DEX"

echo "== 1/7 编译资源 =="
aapt2 compile --dir "$P/res" -o "$RESC/res.zip"

echo "== 2/7 链接资源 + 生成 R.java =="
aapt2 link -o "$OUT/base-$STAMP.apk" \
  -I "$ANDROID_JAR" \
  -A "$P/assets" \
  --manifest "$P/AndroidManifest.xml" \
  -R "$RESC/res.zip" \
  --java "$GEN" \
  --min-sdk-version $MIN_API \
  --target-sdk-version $TARGET_API \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "== 3/7 javac =="
find "$P/src" "$GEN" -name '*.java' > "$OUT/sources-$STAMP.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 11 -nowarn -Xlint:-options \
  -classpath "$ANDROID_JAR:$XSTUB" \
  -d "$CLASSES" @"$OUT/sources-$STAMP.txt" 2>&1 | grep -v 'bootstrap class path\|deprecat\|obsolete' > "$OUT/javac-$STAMP.log" || true
if grep -q 'error:' "$OUT/javac-$STAMP.log"; then echo "javac 失败："; cat "$OUT/javac-$STAMP.log"; exit 1; fi
grep -v '^Picked up' "$OUT/javac-$STAMP.log" | grep -v '^$' | head -5 || true

echo "== 4/7 d8 (dex) =="
if [ -f "$P/libs/r8.jar" ]; then
  "$JAVA_HOME/bin/java" -cp "$P/libs/r8.jar" com.android.tools.r8.D8 \
    --min-api $MIN_API --lib "$ANDROID_JAR" --lib "$XSTUB" --output "$DEX" \
    $(find "$CLASSES" -name '*.class')
else
  d8 --min-api $MIN_API --lib "$ANDROID_JAR" --lib "$XSTUB" --output "$DEX" \
    $(find "$CLASSES" -name '*.class')
fi

echo "== 5/7 组装 apk =="
cd "$DEX"
zip -q -X "$OUT/base-$STAMP.apk" classes*.dex
cd "$P"

echo "== 6/7 zipalign =="
if command -v zipalign >/dev/null 2>&1; then
  zipalign -f -p 4 "$OUT/base-$STAMP.apk" "$OUT/aligned-$STAMP.apk" && mv "$OUT/aligned-$STAMP.apk" "$OUT/base-$STAMP.apk"
else
  echo "   (zipalign 缺失，跳过 — apksigner 输出本身已对齐)"
fi

echo "== 7/7 签名 =="
if [ ! -f "$KS" ]; then
  echo "   未找到统一签名密钥，现场生成一把（仅本次有效！签名会和以前的包不一致）"
  echo "   要一劳永逸：把 keystore/seagull-release.keystore 配成 CI secret，见 docs/SIGNING.md"
  mkdir -p "$KS_DIR"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=SeagullCarLauncher, O=Local, C=CN"
fi
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --out "$OUT/SeagullLauncher.apk" "$OUT/base-$STAMP.apk"

apksigner verify --print-certs "$OUT/SeagullLauncher.apk" | head -3
ls -l "$OUT/SeagullLauncher.apk"
echo "BUILD OK -> $OUT/SeagullLauncher.apk"
