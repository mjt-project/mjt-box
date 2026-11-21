package com.mjt.core.network.multiplexer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Bộ phân tích và nhận diện giao thức (Zero-Dependency Protocol Sniffer).
 * Nhận diện giao thức từ các byte đầu tiên (Preambles / Handshakes) được gửi từ Client.
 */
public class ProtocolSniffer {

    // Danh sách các HTTP Methods tiêu chuẩn
    private static final String[] HTTP_METHODS = {
        "GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE "
    };

    /**
     * Nhận diện loại giao thức từ dữ liệu mồi (peek bytes)
     */
    public static ProtocolType detect(byte[] data, int length) {
        if (data == null || length <= 0) {
            return ProtocolType.UNKNOWN;
        }

        // 1. Kiểm tra HTTP / WebSocket
        if (isHttp(data, length)) {
            return ProtocolType.HTTP;
        }

        // 2. Kiểm tra TLS / HTTPS (Byte 0x16 là Handshake, 0x03 là SSL 3.0 / TLS 1.0+)
        if (isTls(data, length)) {
            return ProtocolType.HTTPS_TLS;
        }

        // 3. Kiểm tra SSH (Bắt đầu bằng "SSH-")
        if (isSsh(data, length)) {
            return ProtocolType.SSH;
        }

        // 4. Kiểm tra Minecraft (Handshake Packet 0x00 hoặc Legacy Ping 0xFE)
        if (isMinecraft(data, length)) {
            return ProtocolType.MINECRAFT;
        }

        return ProtocolType.UNKNOWN;
    }

    /**
     * Kiểm tra xem các byte có khớp với HTTP Request không
     */
    public static boolean isHttp(byte[] data, int length) {
        if (length < 3) return false;
        String prefix = new String(data, 0, Math.min(length, 12), StandardCharsets.US_ASCII);
        for (String method : HTTP_METHODS) {
            if (prefix.startsWith(method)) {
                return true;
            }
        }
        // Kiểm tra HTTP/2 Connection Preface: "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
        if (prefix.startsWith("PRI *")) {
            return true;
        }
        return false;
    }

    /**
     * Kiểm tra TLS ClientHello (HTTPS / WSS / TLS)
     */
    public static boolean isTls(byte[] data, int length) {
        if (length < 3) return false;
        // 0x16 = Handshake record, 0x03 = Major version TLS (3.0, 3.1/TLS 1.0, 3.3/TLS 1.2, etc.)
        return (data[0] & 0xFF) == 0x16 && (data[1] & 0xFF) == 0x03;
    }

    /**
     * Kiểm tra SSH Client Banner
     */
    public static boolean isSsh(byte[] data, int length) {
        if (length < 4) return false;
        String prefix = new String(data, 0, Math.min(length, 8), StandardCharsets.US_ASCII);
        return prefix.startsWith("SSH-");
    }

    /**
     * Kiểm tra Minecraft Java Handshake hoặc Legacy Ping
     */
    public static boolean isMinecraft(byte[] data, int length) {
        if (length < 2) return false;

        // Legacy Ping (MC 1.6 trở xuống): Bắt đầu bằng 0xFE 0x01
        if ((data[0] & 0xFF) == 0xFE) {
            return true;
        }

        // Modern Minecraft Handshake:
        // [Packet Length - VarInt] [Packet ID = 0x00 - VarInt] [Protocol Version - VarInt] ...
        try {
            int offset = 0;
            // Đọc packet length
            VarIntResult lenRes = readVarInt(data, offset, length);
            if (lenRes == null || lenRes.value <= 0 || lenRes.value > 1024) return false;
            offset = lenRes.newOffset;

            // Đọc packet ID (phải là 0x00 cho Handshake)
            VarIntResult idRes = readVarInt(data, offset, length);
            if (idRes == null || idRes.value != 0x00) return false;
            offset = idRes.newOffset;

            // Đọc protocol version
            VarIntResult protoRes = readVarInt(data, offset, length);
            if (protoRes == null) return false;
            offset = protoRes.newOffset;

            // Đọc độ dài server address
            VarIntResult addrLenRes = readVarInt(data, offset, length);
            if (addrLenRes == null || addrLenRes.value < 0 || addrLenRes.value > 255) return false;

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Trích xuất hostname từ gói Handshake Minecraft (nếu có)
     */
    public static String extractMinecraftHostname(byte[] data, int length) {
        try {
            int offset = 0;
            VarIntResult lenRes = readVarInt(data, offset, length);
            if (lenRes == null) return null;
            offset = lenRes.newOffset;

            VarIntResult idRes = readVarInt(data, offset, length);
            if (idRes == null || idRes.value != 0x00) return null;
            offset = idRes.newOffset;

            VarIntResult protoRes = readVarInt(data, offset, length);
            if (protoRes == null) return null;
            offset = protoRes.newOffset;

            VarIntResult addrLen = readVarInt(data, offset, length);
            if (addrLen == null || offset + addrLen.value > length) return null;
            offset = addrLen.newOffset;

            String addr = new String(data, offset, addrLen.value, StandardCharsets.UTF_8);
            return addr.split("\0")[0].toLowerCase(Locale.ROOT).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Trích xuất Header "Host:" từ HTTP Request
     */
    public static String extractHttpHost(byte[] data, int length) {
        try {
            String text = new String(data, 0, Math.min(length, 2048), StandardCharsets.US_ASCII);
            String[] lines = text.split("\r?\n");
            for (String line : lines) {
                if (line.toLowerCase(Locale.ROOT).startsWith("host:")) {
                    String hostVal = line.substring(5).trim();
                    // Bỏ cổng nếu có (vd: localhost:20002 -> localhost)
                    if (hostVal.contains(":")) {
                        hostVal = hostVal.substring(0, hostVal.indexOf(':'));
                    }
                    return hostVal.toLowerCase(Locale.ROOT).trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Trích xuất URL Path từ HTTP Request (dòng đầu tiên: GET /path HTTP/1.1)
     */
    public static String extractHttpPath(byte[] data, int length) {
        try {
            String text = new String(data, 0, Math.min(length, 1024), StandardCharsets.US_ASCII);
            int firstLineEnd = text.indexOf("\r\n");
            if (firstLineEnd == -1) firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                String firstLine = text.substring(0, firstLineEnd);
                String[] parts = firstLine.split(" ");
                if (parts.length >= 2) {
                    return parts[1]; // Request URI path
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==========================================
    // TIỆN ÍCH ĐỌC VARINT
    // ==========================================

    private static VarIntResult readVarInt(byte[] data, int offset, int maxLen) {
        int value = 0;
        int bitOffset = 0;

        for (int i = 0; i < 5; i++) {
            if (offset + i >= maxLen) return null;
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
