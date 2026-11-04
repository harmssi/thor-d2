#!/bin/bash
# Thor D2 bauen: Quellen zum Build-Host syncen, im Docker-Image "thor-d2-build" bauen (wird bei Bedarf aus
# thor/Dockerfile erzeugt), APK nach dist/ThorD2.apk holen. Optional: --install (adb install -r).
set -euo pipefail
HOST=${THOR_BUILD_HOST:?set THOR_BUILD_HOST=user@linux-host-with-docker}
REMOTE=${THOR_D2_REMOTE:-/mnt/cache/thor-d2}
REPO="$(cd "$(dirname "$0")/.." && pwd)"

rsync -a --delete --exclude .git --exclude /dist --exclude winlator/.gradle --exclude winlator/app/build \
    --exclude winlator/build --exclude winlator/local.properties --exclude winlator/app/.cxx \
    --exclude 'winlator/app/src/*/assets/d2/' \
    "$REPO/" "$HOST:$REMOTE/src/"
ssh "$HOST" "docker image inspect thor-d2-build >/dev/null 2>&1 || docker build -t thor-d2-build $REMOTE/src/thor"
ssh "$HOST" "docker run --rm -v $REMOTE:/work thor-d2-build bash /work/src/thor/docker-build.sh"

mkdir -p "$REPO/dist"
scp -q "$HOST:$REMOTE/out/ThorD2.apk" "$REPO/dist/ThorD2.apk"
echo "APK: dist/ThorD2.apk"
[ "${1:-}" = "--install" ] && adb install -r "$REPO/dist/ThorD2.apk"
exit 0
