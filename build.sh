#!/usr/bin/env bash
# Lumiere Play build script with Clash/V2RayN/Trojan TUN proxy detection
set -e

echo "==> Lumiere Play 0.5.4 build"

# Clash TUN proxy auto-detect (CN/RU/IR users)
PROXY_PORTS="7890 7891 7892 8080 8888 3128 1080"
DETECTED=""
for p in $PROXY_PORTS; do
    if (echo > /dev/tcp/127.0.0.1/$p) 2>/dev/null; then
        DETECTED="http://127.0.0.1:$p"
        break
    fi
done

if [ -n "$DETECTED" ]; then
    echo "==> Detected TUN proxy at $DETECTED"
    export GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=${DETECTED##*:} -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=${DETECTED##*:}"
else
    echo "==> No TUN proxy detected, using direct connection"
fi

chmod +x gradlew
./gradlew clean build
echo "==> Build complete: build/libs/lumiereplay-0.5.4.jar"
