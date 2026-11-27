package com.mjt.box.network.multiplexer;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Bộ phân tích và nhận diện giao thức (Zero-Dependency Protocol Sniffer).
 * Hỗ trợ kiểm tra cả gói tin từ Client (Request/Handshake) và phản hồi từ
 * Backend (Response/Banner).
 */
public class ProtocolSniffer {

    // Danh sách các HTTP Methods tiêu chuẩn
    private static final String[] HTTP_METHODS = {
            "GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE "
    };

    /**
     * Thông tin chi tiết khi giải mã gói Handshake Minecraft Java
     */
    public static class MinecraftHandshakeInfo {
        public final int protocolVersion;
        public final String hostname;
        public final int nextState;
        public final boolean hasExtraPacketBytes;

        public MinecraftHandshakeInfo(int protocolVersion, String hostname, int nextState,
                boolean hasExtraPacketBytes) {
            this.protocolVersion = protocolVersion;
            this.hostname = hostname;
            this.nextState = nextState;
            this.hasExtraPacketBytes = hasExtraPacketBytes;
        }
    }

    /**
     * Nhận diện loại giao thức từ dữ liệu mồi của Client
     */
    public static ProtocolType detect(byte[] data, int length) {
        if (data == null || length <= 0) {
            return ProtocolType.UNKNOWN;
        }

        if (isHttp(data, length)) {
            return ProtocolType.HTTP;
        }

        if (isTls(data, length)) {
            return ProtocolType.HTTPS_TLS;
        }

        if (isSsh(data, length)) {
            return ProtocolType.SSH;
        }

        if (isMinecraft(data, length)) {
            return ProtocolType.MINECRAFT;
        }

        return ProtocolType.UNKNOWN;
    }

    // ==========================================
    // KIỂM TRA DỮ LIỆU CLIENT (INBOUND)
    // ==========================================

    public static boolean isHttp(byte[] data, int length) {
        if (length < 4)
            return false;
        String prefix = new String(data, 0, Math.min(length, 16), StandardCharsets.US_ASCII);
        for (String method : HTTP_METHODS) {
            if (prefix.startsWith(method)) {
                return true;
            }
        }
        return prefix.startsWith("PRI * HTTP/2");
    }

    /**
     * Kiểm tra xem dữ liệu HTTP đã chứa dấu kết thúc header (\r\n\r\n hoặc \n\n)
     * chưa
     */
    public static boolean isCompleteHttpHeader(byte[] data, int length) {
        if (data == null || length < 4)
            return false;
        String text = new String(data, 0, Math.min(length, 8192), StandardCharsets.ISO_8859_1);
        return text.contains("\r\n\r\n") || text.contains("\n\n");
    }

    public static boolean isTls(byte[] data, int length) {
        if (length < 3)
            return false;
        // 0x16 = TLS Handshake ClientHello, 0x03 = SSL 3.0 / TLS 1.x
        return (data[0] & 0xFF) == 0x16 && (data[1] & 0xFF) == 0x03;
    }

    public static boolean isSsh(byte[] data, int length) {
        if (length < 4)
            return false;
        String prefix = new String(data, 0, Math.min(length, 8), StandardCharsets.US_ASCII);
        return prefix.startsWith("SSH-");
    }

    public static boolean isMinecraft(byte[] data, int length) {
        if (length < 2)
            return false;
        if ((data[0] & 0xFF) == 0xFE) {
            return true;
        }
        return inspectMinecraftHandshake(data, length) != null;
    }

    /**
     * Phân tích đầy đủ gói Handshake Minecraft Java hiện đại.
     * Kiểm tra cấu trúc chặt chẽ để tránh nhận nhầm dữ liệu ngẫu nhiên.
     */
    public static MinecraftHandshakeInfo inspectMinecraftHandshake(byte[] data, int length) {
        try {
            int offset = 0;
            VarIntResult lenRes = readVarInt(data, offset, length);
            if (lenRes == null || lenRes.value < 5 || lenRes.value > 1024)
                return null;
            offset = lenRes.newOffset;
            int totalFirstPacketEnd = offset + lenRes.value;

            VarIntResult idRes = readVarInt(data, offset, length);
            if (idRes == null || idRes.value != 0x00)
                return null;
            offset = idRes.newOffset;

            VarIntResult protoRes = readVarInt(data, offset, length);
            if (protoRes == null)
                return null;
            offset = protoRes.newOffset;

            VarIntResult addrLenRes = readVarInt(data, offset, length);
            if (addrLenRes == null || addrLenRes.value < 1 || addrLenRes.value > 255)
                return null;
            offset = addrLenRes.newOffset;

            String hostname = "";
            if (offset + addrLenRes.value <= length) {
                hostname = new String(data, offset, addrLenRes.value, StandardCharsets.UTF_8)
                        .split("\0")[0].toLowerCase(Locale.ROOT).trim();
                offset += addrLenRes.value;
            } else {
                return null;
            }

            // Đọc 2 byte port + 1 byte nextState nếu có đủ dữ liệu
            int nextState = 0;
            if (offset + 3 <= length) {
                offset += 2; // port unsigned short
                VarIntResult stateRes = readVarInt(data, offset, length);
                if (stateRes == null || stateRes.value < 1 || stateRes.value > 3) {
                    return null;
                }
                nextState = stateRes.value;
            }

            boolean hasExtra = length > totalFirstPacketEnd;
            return new MinecraftHandshakeInfo(protoRes.value, hostname, nextState, hasExtra);
        } catch (Exception e) {
            return null;
        }
    }

    // ==========================================
    // XÁC MINH PHẢN HỒI TỪ BACKEND (OUTBOUND)
    // ==========================================

    /**
     * Xác minh phản hồi của backend có phải giao thức Shell (SSH/SFTP/FTP/Telnet)
     * hay không
     */
    public static boolean isShellResponse(byte[] data, int length) {
        if (data == null || length <= 0)
            return false;
        if (length >= 4) {
            String prefix = new String(data, 0, Math.min(length, 12), StandardCharsets.US_ASCII);
            if (prefix.startsWith("SSH-") || prefix.startsWith("220 ") || prefix.startsWith("220-")) {
                return true;
            }
        }
        // Telnet IAC negotiation (0xFF followed by WILL/WONT/DO/DONT)
        if (length >= 3 && (data[0] & 0xFF) == 0xFF) {
            int cmd = data[1] & 0xFF;
            return cmd >= 0xFB && cmd <= 0xFE;
        }
        return false;
    }

    /**
     * Kiểm tra xem HTTP Request có phải tín hiệu NetherNet của Minecraft Bedrock
     * (MCPE) không
     */
    public static boolean isMcpeNetherNetRequest(byte[] data, int length) {
        if (!isHttp(data, length))
            return false;
        String path = extractHttpPath(data, length);
        return path != null && path.toLowerCase(Locale.ROOT).startsWith("/v1/join");
    }

    /**
     * Trích xuất mã trạng thái HTTP từ phản hồi backend (ví dụ "HTTP/1.1 200 OK" ->
     * 200).
     * Trả về -1 nếu không phải HTTP response hợp lệ.
     */
    public static int parseHttpStatusCode(byte[] data, int length) {
        if (data == null || length < 10)
            return -1;
        String head = new String(data, 0, Math.min(length, 64), StandardCharsets.US_ASCII);
        if (!head.startsWith("HTTP/"))
            return -1;
        int sp1 = head.indexOf(' ');
        if (sp1 < 0 || sp1 + 4 > head.length())
            return -1;
        int end = sp1 + 4;
        String codeStr = head.substring(sp1 + 1, end);
        try {
            return Integer.parseInt(codeStr);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Xác minh phản hồi HTTP là THÀNH CÔNG thực sự (1xx, 2xx, 3xx, 401, 403, HTML
     * thô, HTTP/2).
     * Các mã lỗi 400, 404, 405, 501, 502, 503 KHÔNG được coi là khớp chắc chắn ngay
     * lập tức.
     */
    public static boolean isHttpConfirmedSuccess(byte[] data, int length) {
        if (data == null || length < 5)
            return false;
        int status = parseHttpStatusCode(data, length);
        if (status > 0) {
            return (status >= 100 && status < 400) || status == 401 || status == 403;
        }
        String prefix = new String(data, 0, Math.min(length, 32), StandardCharsets.US_ASCII);
        if (prefix.startsWith("<!DOCTYPE") || prefix.startsWith("<!doctype")
                || prefix.startsWith("<html") || prefix.startsWith("<HTML")) {
            return true;
        }
        return length >= 9 && (data[3] & 0xFF) == 0x04 && data[4] == 0x00;
    }

    /**
     * Kiểm tra phản hồi HTTP có phải mã 404 / không hỗ trợ đường dẫn hay không
     * (cổng web sống nhưng không có route này)
     */
    public static boolean isHttpSoftNotFound(byte[] data, int length) {
        int status = parseHttpStatusCode(data, length);
        return status == 404 || status == 405 || status == 410 || status == 501;
    }

    /**
     * Kiểm tra xem phản hồi HTTP có chứa trang HTML hay không (dùng để tránh SPA
     * catch-all nuốt nhầm /v1/join)
     */
    public static boolean isHtmlHttpResponse(byte[] data, int length) {
        if (data == null || length < 5)
            return false;
        String sample = new String(data, 0, Math.min(length, 512), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        return sample.contains("content-type: text/html")
                || sample.contains("<!doctype html")
                || sample.contains("<html");
    }

    /**
     * Xác minh phản hồi của backend có phải HTTP Response (bất kỳ mã trạng thái)
     * hay không
     */
    public static boolean isHttpResponse(byte[] data, int length) {
        if (data == null || length < 5)
            return false;
        String prefix = new String(data, 0, Math.min(length, 32), StandardCharsets.US_ASCII);
        if (prefix.startsWith("HTTP/") || prefix.startsWith("<!DOCTYPE")
                || prefix.startsWith("<!doctype") || prefix.startsWith("<html")
                || prefix.startsWith("<HTML")) {
            return true;
        }
        // HTTP/2 SETTINGS frame (9+ bytes, type == 0x04)
        return length >= 9 && (data[3] & 0xFF) == 0x04 && data[4] == 0x00;
    }

    /**
     * Xác minh phản hồi của backend có phải TLS ServerHello / TLS Record hợp lệ hay
     * không
     */
    public static boolean isTlsResponse(byte[] data, int length) {
        if (data == null || length < 3)
            return false;
        int recordType = data[0] & 0xFF;
        int majorVer = data[1] & 0xFF;
        // 0x16=Handshake, 0x15=Alert, 0x14=ChangeCipherSpec, Major=0x03
        return majorVer == 0x03 && (recordType == 0x16 || recordType == 0x15 || recordType == 0x14);
    }

    // ==========================================
    // TRÍCH XUẤT METADATA (HOSTNAME / PATH)
    // ==========================================

    public static String extractMinecraftHostname(byte[] data, int length) {
        MinecraftHandshakeInfo info = inspectMinecraftHandshake(data, length);
        return info != null ? info.hostname : null;
    }

    public static String extractHttpHost(byte[] data, int length) {
        try {
            String text = new String(data, 0, Math.min(length, 2048), StandardCharsets.US_ASCII);
            String[] lines = text.split("\r?\n");
            for (String line : lines) {
                if (line.toLowerCase(Locale.ROOT).startsWith("host:")) {
                    String hostVal = line.substring(5).trim();
                    if (hostVal.contains(":")) {
                        hostVal = hostVal.substring(0, hostVal.indexOf(':'));
                    }
                    return hostVal.toLowerCase(Locale.ROOT).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String extractHttpPath(byte[] data, int length) {
        try {
            String text = new String(data, 0, Math.min(length, 1024), StandardCharsets.US_ASCII);
            int firstLineEnd = text.indexOf("\r\n");
            if (firstLineEnd == -1)
                firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                String firstLine = text.substring(0, firstLineEnd);
                String[] parts = firstLine.split(" ");
                if (parts.length >= 2) {
                    return parts[1];
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static VarIntResult readVarInt(byte[] data, int offset, int maxLen) {
        int value = 0;
        int bitOffset = 0;
        for (int i = 0; i < 5; i++) {
            if (offset + i >= maxLen)
                return null;
            int b = data[offset + i] & 0xFF;
            value |= (b & 0x7F) << bitOffset;
            if ((b & 0x80) == 0) {
                return new VarIntResult(value, offset + i + 1);
            }
            bitOffset += 7;
        }
        return null;
    }

    private static class VarIntResult {
        final int value;
        final int newOffset;

        VarIntResult(int value, int newOffset) {
            this.value = value;
            this.newOffset = newOffset;
        }
    }
}
