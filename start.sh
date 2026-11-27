#!/bin/sh
# ==============================================================================
# MJT Box Standalone Launcher
# Tự động phát hiện Java, hoặc tải JRE Temurin thu gọn cục bộ vào .mjt-jre/
# khi hệ thống Linux chưa cài đặt sẵn Java.
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ -f "mjt-box.jar" ]; then
    JAR_FILE="mjt-box.jar"
elif [ -f "server.jar" ]; then
    JAR_FILE="server.jar"
elif [ -f "target/mjt-box.jar" ]; then
    JAR_FILE="target/mjt-box.jar"
elif [ -f "target/server.jar" ]; then
    JAR_FILE="target/server.jar"
else
    echo "[!] Không tìm thấy mjt-box.jar hoặc server.jar trong $SCRIPT_DIR" >&2
    exit 1
fi

find_java() {
    if [ -x "$SCRIPT_DIR/.mjt-jre/bin/java" ]; then
        echo "$SCRIPT_DIR/.mjt-jre/bin/java"
        return 0
    fi
    if command -v java >/dev/null 2>&1; then
        command -v java
        return 0
    fi
    return 1
}

bootstrap_portable_jre() {
    UNAME_ARCH="$(uname -m)"
    case "$UNAME_ARCH" in
        x86_64|amd64) JRE_ARCH="x64" ;;
        aarch64|arm64) JRE_ARCH="aarch64" ;;
        armv7*|armhf) JRE_ARCH="arm" ;;
        i*86) JRE_ARCH="x86" ;;
        *)
            echo "[-] Kiến trúc CPU '$UNAME_ARCH' chưa hỗ trợ tự tải JRE." >&2
            exit 1
            ;;
    esac

    JRE_URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/${JRE_ARCH}/jre/hotspot/normal/eclipse"
    JRE_DIR="$SCRIPT_DIR/.mjt-jre"
    JRE_TMP="$SCRIPT_DIR/.mjt-jre-tmp.tar.gz"

    echo "[*] Hệ thống chưa có sẵn Java. Đang tải Temurin JRE 21 (${JRE_ARCH})..."
    if command -v curl >/dev/null 2>&1; then
        curl -fL "$JRE_URL" -o "$JRE_TMP"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$JRE_TMP" "$JRE_URL"
    else
        echo "[-] Thiếu cả 'curl' lẫn 'wget' để tự tải JRE." >&2
        exit 1
    fi

    rm -rf "$JRE_DIR"
    mkdir -p "$JRE_DIR"
    tar -xzf "$JRE_TMP" -C "$JRE_DIR" --strip-components=1
    rm -f "$JRE_TMP"
    chmod +x "$JRE_DIR/bin/java"
    echo "[+] Đã cài đặt Portable JRE tại $JRE_DIR"
}

JAVA_BIN="$(find_java || true)"
if [ -z "$JAVA_BIN" ]; then
    bootstrap_portable_jre
    JAVA_BIN="$SCRIPT_DIR/.mjt-jre/bin/java"
fi

exec "$JAVA_BIN" -jar "$JAR_FILE" "$@"
