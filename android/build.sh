#!/bin/sh
# Build the Charmera Card APK without Gradle, after running the checker's tests on the plain JVM.
#
#   SDK=/path/to/android-sdk ./build.sh [versionName] [versionCode]
#
# versionName defaults to the one in VERSION, versionCode to the current UTC date and hour.
# Needs the Android SDK with "platforms;android-35" and "build-tools;35.0.0", a JDK 17 or newer, and zip.
# Signing: KEYSTORE, KS_ALIAS and KS_PASS name your key. Without them a throwaway key is made in out/; an APK
# signed with a different key than the installed one cannot be installed over it (uninstall first).
set -eu
cd "$(dirname "$0")"

SDK=${SDK:-${ANDROID_HOME:-/opt/android/sdk}}
BT=$SDK/build-tools/35.0.0
AJ=$SDK/platforms/android-35/android.jar
VER=${1:-$(cat VERSION)}
CODE=${2:-$(date -u +%y%m%d%H)}
KEYSTORE=${KEYSTORE:-out/throwaway.keystore}
KS_ALIAS=${KS_ALIAS:-charmera}
KS_PASS=${KS_PASS:-throwaway-key}
APK=out/charmera-card-$VER.apk

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJ"; do
    [ -e "$f" ] || { echo "missing $f; install the SDK packages named at the top of this script" >&2; exit 2; }
done

rm -rf out/build out/test
mkdir -p out/build/classes out/build/gen out/test

echo "1/4 checker, write gate and card tests"
javac -encoding UTF-8 -d out/test src/io/github/daemonddev/charmera/UpdateCheck.java \
    src/io/github/daemonddev/charmera/CardState.java src/io/github/daemonddev/charmera/Candidates.java \
    src/io/github/daemonddev/charmera/WriteGate.java \
    test/UpdateCheckTest.java test/CardStateTest.java test/CandidatesTest.java test/WriteGateTest.java
java -cp out/test UpdateCheckTest
java -cp out/test io.github.daemonddev.charmera.CardStateTest
java -cp out/test CandidatesTest
java -cp out/test io.github.daemonddev.charmera.WriteGateTest

echo "2/4 resources"
"$BT/aapt2" compile --dir res -o out/build/res.zip
"$BT/aapt2" link -I "$AJ" --manifest AndroidManifest.xml --version-code "$CODE" --version-name "$VER" \
    --java out/build/gen -o out/build/base.apk out/build/res.zip

echo "3/4 code"
# Java 8 bytecode against the platform jar; d8 turns the lambdas into plain classes for Android 7.
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$AJ" -classpath "$BT/core-lambda-stubs.jar" \
    -d out/build/classes $(find src out/build/gen -name '*.java')
"$BT/d8" --min-api 24 --lib "$AJ" --output out/build $(find out/build/classes -name '*.class')
cp out/build/base.apk out/build/unsigned.apk
(cd out/build && zip -q -j unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 out/build/unsigned.apk out/build/aligned.apk

echo "4/4 signing"
if [ ! -f "$KEYSTORE" ]; then
    echo "no keystore at $KEYSTORE: making a throwaway one"
    keytool -genkeypair -keystore "$KEYSTORE" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=Charmera Card" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$APK" out/build/aligned.apk
"$BT/apksigner" verify "$APK"
echo "built $APK (versionName $VER, versionCode $CODE)"
