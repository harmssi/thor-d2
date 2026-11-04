#!/bin/bash
# Runs inside the "thor-d2-build" image (thor/Dockerfile). /work = src/ (this repo), cache/, gradle/, keys/, out/.
set -euo pipefail
export ANDROID_HOME=/opt/android-sdk D2_CACHE_DIR=/work/cache GRADLE_USER_HOME=/work/gradle
VERSION=$(cat /work/src/thor/VERSION)
CODE=$(cat /work/src/thor/VERSION_CODE)
cd /work/src
bash tools/fetch-winlator101.sh >/dev/null
bash tools/collect-licenses.sh >/dev/null
bash tools/build-runtime.sh --public
bash tools/build-mod.sh >/dev/null
KEY=/work/keys/thord2.p12
if [ ! -f "$KEY" ]; then
    mkdir -p /work/keys && head -c 24 /dev/urandom | base64 | tr -d '/+=' > /work/keys/pass && chmod 600 /work/keys/pass
    keytool -genkeypair -storetype PKCS12 -keystore "$KEY" -alias d2android -keyalg RSA -keysize 4096 -validity 10000 \
        -storepass "$(cat /work/keys/pass)" -dname "CN=Thor D2" >/dev/null
fi
cd winlator
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew --no-daemon -q assembleRelease -Pd2VersionName="$VERSION" -Pd2VersionCode="$CODE" \
    -Pd2ReleaseKeystore="$KEY" -Pd2ReleaseKeystorePassword="$(cat /work/keys/pass)"
mkdir -p /work/out
cp app/build/outputs/apk/release/app-release.apk /work/out/ThorD2.apk
ls -l /work/out/ThorD2.apk
