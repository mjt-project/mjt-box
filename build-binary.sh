#!/bin/sh
# ==============================================================================
# Tạo file thực thi Polyglot Standalone: target/mjt-box
# - Chạy trực tiếp như binary trên Linux: ./mjt-box
# - Vẫn gọi được bằng JVM chuẩn:        java -jar mjt-box
# - Tự tải Portable Temurin JRE (.mjt-jre/) nếu máy Linux không có sẵn Java
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ -n "$1" ]; then
    INPUT_JAR="$1"
elif [ -f "target/mjt-box.jar" ]; then
    INPUT_JAR="target/mjt-box.jar"
else
    INPUT_JAR="target/server.jar"
fi
OUTPUT_BIN="${2:-target/mjt-box}"

if [ ! -f "$INPUT_JAR" ]; then
    echo "[-] Không tìm thấy JAR nguồn: $INPUT_JAR (hãy chạy 'mvn package' trước)." >&2
    exit 1
fi

cat << 'EOF' > "$OUTPUT_BIN"
#!/bin/sh
SELF="$0"
case "$SELF" in
    /*) ;;
    *) SELF="$(pwd)/$SELF" ;;
esac
APP_DIR="$(cd "$(dirname "$SELF")" && pwd)"

find_java() {
    if [ -x "$APP_DIR/.mjt-jre/bin/java" ]; then
        echo "$APP_DIR/.mjt-jre/bin/java"
        return 0
    fi
    if command -v java >/dev/null 2>&1; then
        command -v java
        return 0
    fi
    return 1
}

JAVA_BIN="$(find_java || true)"
if [ -z "$JAVA_BIN" ]; then
    UNAME_ARCH="$(uname -m)"
    case "$UNAME_ARCH" in
        x86_64|amd64) JRE_ARCH="x64" ;;
        aarch64|arm64) JRE_ARCH="aarch64" ;;
        armv7*|armhf) JRE_ARCH="arm" ;;
        *) echo "[-] Chưa có Java và không hỗ trợ tải JRE cho $UNAME_ARCH" >&2; exit 1 ;;
    esac
    JRE_URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/${JRE_ARCH}/jre/hotspot/normal/eclipse"
    JRE_DIR="$APP_DIR/.mjt-jre"
    JRE_TMP="$APP_DIR/.mjt-jre-tmp.tar.gz"
    echo "[*] Chưa có Java trên hệ thống. Đang tải Temurin JRE 21 (${JRE_ARCH})..."
    if command -v curl >/dev/null 2>&1; then
        curl -fL "$JRE_URL" -o "$JRE_TMP"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$JRE_TMP" "$JRE_URL"
    else
        echo "[-] Cần curl hoặc wget để tải JRE thu gọn." >&2
        exit 1
    fi
    rm -rf "$JRE_DIR" && mkdir -p "$JRE_DIR"
    tar -xzf "$JRE_TMP" -C "$JRE_DIR" --strip-components=1
    rm -f "$JRE_TMP"
    chmod +x "$JRE_DIR/bin/java"
    JAVA_BIN="$JRE_DIR/bin/java"
fi

exec "$JAVA_BIN" -jar "$SELF" "$@"
exit 1
EOF

cat "$INPUT_JAR" >> "$OUTPUT_BIN"
chmod +x "$OUTPUT_BIN"
echo "[+] Đã tạo file thực thi Standalone: $OUTPUT_BIN"
