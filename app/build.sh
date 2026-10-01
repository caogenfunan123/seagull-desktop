#!/data/data/com.dsharnessmobile.shell/files/usr/bin/bash
# 海鸥桌面 — 纯命令行 APK 构建（无 Gradle），在 Termux 宿主侧运行
set -e

P="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/.dsh/ubuntu-rootfs/root/opt/android-sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$P/libs/android.jar"
JAVA_HOME=/data/data/com.dsharnessmobile.shell/files/usr/lib/jvm/java-21-openjdk
export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"
export PATH="/data/data/com.dsharnessmobile.shell/files/usr/bin:$JAVA_HOME/bin:$PATH"

MIN_API=29
TARGET_API=33
KS="$P/debug.keystore"
KS_PASS=android
KS_ALIAS=seagull

OUT="$P/out"
rm -rf "$OUT"; mkdir -p "$OUT/res-c" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1/7 编译资源 =="
aapt2 compile --dir "$P/res" -o "$OUT/res-c/res.zip"

echo "== 2/7 链接资源 + 生成 R.java =="
aapt2 link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$P/AndroidManifest.xml" \
  -R "$OUT/res-c/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version $MIN_API \
  --target-sdk-version $TARGET_API \
  --version-code 1 --version-name 1.0 \
  --auto-add-overlay

echo "== 3/7 javac =="
find "$P/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 11 -nowarn -Xlint:-options \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v 'bootstrap class path\|deprecat\|obsolete' > "$OUT/javac.log" || true
if grep -q 'error:' "$OUT/javac.log"; then echo "javac 失败："; cat "$OUT/javac.log"; exit 1; fi
grep -v '^Picked up' "$OUT/javac.log" | grep -v '^$' | head -5 || true

echo "== 4/7 d8 (dex) =="
"$JAVA_HOME/bin/java" -cp "$P/libs/r8.jar" com.android.tools.r8.D8 \
  --min-api $MIN_API --lib "$ANDROID_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "== 5/7 组装 apk =="
cd "$OUT/dex"
zip -q -X "$OUT/base.apk" classes*.dex
cd "$P"

echo "== 6/7 zipalign =="
if command -v zipalign >/dev/null 2>&1; then
  zipalign -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk" && mv "$OUT/aligned.apk" "$OUT/base.apk"
else
  echo "   (zipalign 缺失，跳过 — apksigner 输出本身已对齐)"
fi

echo "== 7/7 签名 =="
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=SeagullCarLauncher, O=Local, C=CN"
fi
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --out "$OUT/SeagullLauncher.apk" "$OUT/base.apk"

apksigner verify --print-certs "$OUT/SeagullLauncher.apk" | head -3
ls -l "$OUT/SeagullLauncher.apk"
echo "BUILD OK -> $OUT/SeagullLauncher.apk"
