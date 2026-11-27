package com.mjt.box.network.multiplexer;

import com.mjt.box.ui.OutputLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * Hỗ trợ giao thức Minecraft Bedrock NetherNet (WebRTC over HTTP Signaling + UDP).
 *
 * Khi MCPE 1.26+ kết nối qua NAT/Container (ví dụ node11.zampto.net:28029 → MJT → BDS 19132):
 *   1. BDS trả về SDP Answer cho POST /v1/join/{id} chứa IP nội bộ container (172.x.x.x / 127.0.0.1)
 *      và cổng UDP WebRTC ngẫu nhiên (hoặc 19132) mà client bên ngoài không thể chạm tới trực tiếp.
 *   2. NetherNetHelper đọc trọn gói phản hồi HTTP/SDP, học cổng UDP thực tế của BDS để chuyển
 *      luồng UDP đúng đích, đồng thời viết lại các dòng a=candidate / c=IN IP4 sang Public IP:Port
 *      và cập nhật lại Content-Length chuẩn HTTP/1.1.
 */
public class NetherNetHelper {

    public static class PublicEndpoint {
        public final String ip;
        public final int port;

        public PublicEndpoint(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    private static class DnsCacheEntry {
        final String ip;
        final long expiresAtMs;

        DnsCacheEntry(String ip, long expiresAtMs) {
            this.ip = ip;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static final Map<String, DnsCacheEntry> dnsCache = new ConcurrentHashMap<>();
    private static final int MAX_HTTP_MSG_BYTES = 131072; // 128KB cho phản hồi signaling

    /**
     * Xác định Public IP & Public Port dựa trên cấu hình (public_host/public_port)
     * hoặc trích xuất tự động từ HTTP Header "Host:" của client.
     */
    public static PublicEndpoint resolvePublicEndpoint(byte[] httpRequest,
                                                       String cfgHost, int cfgPort,
                                                       int fallbackBoundPort) {
        String rawHost = (cfgHost != null && !cfgHost.isEmpty() && !"auto".equalsIgnoreCase(cfgHost))
                ? cfgHost : null;
        int port = cfgPort > 0 ? cfgPort : -1;

        if ((rawHost == null || port <= 0) && httpRequest != null && httpRequest.length > 0) {
            String hostHeader = extractRawHostHeader(httpRequest);
            if (hostHeader != null && !hostHeader.isEmpty()) {
                int colonIdx = hostHeader.lastIndexOf(':');
                if (colonIdx > 0 && !hostHeader.contains("]")) {
                    if (rawHost == null) rawHost = hostHeader.substring(0, colonIdx).trim();
                    if (port <= 0) {
                        try {
                            port = Integer.parseInt(hostHeader.substring(colonIdx + 1).trim());
                        } catch (NumberFormatException ignored) {}
                    }
                } else if (rawHost == null) {
                    rawHost = hostHeader.trim();
                }
            }
        }

        if (port <= 0) port = fallbackBoundPort;
        if (rawHost == null || rawHost.isEmpty()) return null;

        String resolvedIp = resolveIpv4Cached(rawHost);
        return new PublicEndpoint(resolvedIp, port);
    }

    private static String extractRawHostHeader(byte[] data) {
        String text = new String(data, 0, Math.min(data.length, 4096), StandardCharsets.US_ASCII);
        for (String line : text.split("\r?\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("host:")) {
                return line.substring(5).trim();
            }
        }
        return null;
    }

    private static String resolveIpv4Cached(String host) {
        long now = System.currentTimeMillis();
        DnsCacheEntry cached = dnsCache.get(host);
        if (cached != null && now < cached.expiresAtMs) {
            return cached.ip;
        }
        String best = host;
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress addr : all) {
                if (addr instanceof Inet4Address) {
                    best = addr.getHostAddress();
                    break;
                }
            }
            if (best.equals(host) && all.length > 0) {
                best = all[0].getHostAddress();
            }
        } catch (Exception ignored) {}
        dnsCache.put(host, new DnsCacheEntry(best, now + 60_000L));
        return best;
    }

    /**
     * Hoàn tất đọc toàn bộ 1 HTTP response (nếu ban đầu mới đọc dở phần header/body)
     * và viết lại SDP ICE candidates nếu đây là SDP Answer của NetherNet.
     */
    public static byte[] finalizeAndRewriteHttpResponse(String sessionTag,
                                                        byte[] initialReply,
                                                        InputStream backendIn,
                                                        PublicEndpoint publicEp,
                                                        IntConsumer onDiscoveredBdsUdpPort) {
        if (initialReply == null || initialReply.length == 0) {
            return new byte[0];
        }

        byte[] full = readCompleteHttpResponse(initialReply, backendIn);
        return rewriteSdpInHttpResponse(sessionTag, full, publicEp, onDiscoveredBdsUdpPort);
    }

    /**
     * Đọc thêm dữ liệu từ backendIn cho tới khi nhận đủ body theo Content-Length hoặc Chunked end.
     */
    private static byte[] readCompleteHttpResponse(byte[] initial, InputStream in) {
        String text = new String(initial, StandardCharsets.ISO_8859_1);
        if (!text.startsWith("HTTP/") || in == null) {
            return initial;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(initial);
            byte[] buf = new byte[8192];

            // Đợi đủ header \r\n\r\n
            long deadline = System.currentTimeMillis() + 500;
            while (baos.size() < MAX_HTTP_MSG_BYTES && System.currentTimeMillis() < deadline) {
                String cur = new String(baos.toByteArray(), StandardCharsets.ISO_8859_1);
                if (cur.contains("\r\n\r\n")) break;
                int n = in.read(buf);
                if (n <= 0) break;
                baos.write(buf, 0, n);
            }

            byte[] withHdr = baos.toByteArray();
            String hdrText = new String(withHdr, StandardCharsets.ISO_8859_1);
            int hdrEnd = hdrText.indexOf("\r\n\r\n");
            if (hdrEnd < 0) return withHdr;

            String headersLower = hdrText.substring(0, hdrEnd).toLowerCase(Locale.ROOT);
            int cl = extractHeaderInt(headersLower, "content-length:");
            if (cl >= 0) {
                int targetTotal = hdrEnd + 4 + cl;
                long bodyDeadline = System.currentTimeMillis() + 800;
                while (baos.size() < targetTotal && baos.size() < MAX_HTTP_MSG_BYTES
                        && System.currentTimeMillis() < bodyDeadline) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    baos.write(buf, 0, n);
                }
            } else if (headersLower.contains("transfer-encoding: chunked")) {
                long chunkDeadline = System.currentTimeMillis() + 800;
                while (baos.size() < MAX_HTTP_MSG_BYTES && System.currentTimeMillis() < chunkDeadline) {
                    String cur = new String(baos.toByteArray(), StandardCharsets.ISO_8859_1);
                    if (cur.substring(hdrEnd + 4).contains("\r\n0\r\n")
                            || cur.substring(hdrEnd + 4).startsWith("0\r\n")) {
                        break;
                    }
                    int n = in.read(buf);
                    if (n <= 0) break;
                    baos.write(buf, 0, n);
                }
            }
        } catch (IOException ignored) {}

        return baos.toByteArray();
    }

    /**
     * Kiểm tra và viết lại SDP Answer nếu HTTP response chứa thông tin ICE candidates của NetherNet.
     */
    public static byte[] rewriteSdpInHttpResponse(String sessionTag,
                                                  byte[] rawResponse,
                                                  PublicEndpoint publicEp,
                                                  IntConsumer onDiscoveredBdsUdpPort) {
        if (rawResponse == null || rawResponse.length == 0) return rawResponse;

        String text = new String(rawResponse, StandardCharsets.ISO_8859_1);
        int hdrEnd = text.indexOf("\r\n\r\n");
        if (hdrEnd < 0 || !text.startsWith("HTTP/")) {
            return rawResponse;
        }

        String headers = text.substring(0, hdrEnd);
        boolean isChunked = headers.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked");

        byte[] rawBodyBytes = new byte[rawResponse.length - (hdrEnd + 4)];
        System.arraycopy(rawResponse, hdrEnd + 4, rawBodyBytes, 0, rawBodyBytes.length);

        String bodyStr;
        if (isChunked) {
            bodyStr = decodeChunkedBody(new String(rawBodyBytes, StandardCharsets.UTF_8));
        } else {
            bodyStr = new String(rawBodyBytes, StandardCharsets.UTF_8);
        }

        // Log gọn trạng thái /v1/join để dễ theo dõi
        String statusLine = headers.split("\r?\n", 2)[0];
        if (!bodyStr.contains("a=candidate:")) {
            OutputLogger.info(String.format("NetherNet [%s] ← %s (%d bytes JSON)",
                    sessionTag, statusLine, rawBodyBytes.length));
            return rawResponse;
        }

        // ===== XỬ LÝ SDP ANSWER CHỨA ICE CANDIDATES =====
        String lineSep = bodyStr.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = bodyStr.split("\r?\n", -1);
        StringBuilder rewritten = new StringBuilder(bodyStr.length() + 128);

        int discoveredPort = -1;
        String firstOldAddr = null;
        int rewrittenCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("c=IN IP4 ") && publicEp != null && !publicEp.ip.isEmpty()) {
                line = "c=IN IP4 " + publicEp.ip;
            } else if (line.startsWith("a=candidate:")) {
                String[] parts = line.trim().split("\\s+");
                // Định dạng ICE: a=candidate:<found> <comp> <proto> <prio> <ip> <port> typ <type> ...
                if (parts.length >= 8 && "udp".equalsIgnoreCase(parts[2])) {
                    String oldIp = parts[4];
                    int oldPort = -1;
                    try { oldPort = Integer.parseInt(parts[5]); } catch (NumberFormatException ignored) {}

                    if (oldPort > 0 && discoveredPort < 0) {
                        discoveredPort = oldPort;
                        firstOldAddr = oldIp + ":" + oldPort;
                    }

                    if (publicEp != null && !publicEp.ip.isEmpty() && publicEp.port > 0) {
                        parts[4] = publicEp.ip;
                        parts[5] = String.valueOf(publicEp.port);
                        // Thay raddr/rport nội bộ nếu là candidate srflx
                        for (int j = 8; j + 1 < parts.length; j += 2) {
                            if ("raddr".equals(parts[j])) parts[j + 1] = "0.0.0.0";
                            else if ("rport".equals(parts[j])) parts[j + 1] = "0";
                        }
                        line = String.join(" ", parts);
                        rewrittenCount++;
                    }
                }
            }
            rewritten.append(line);
            if (i < lines.length - 1) {
                rewritten.append(lineSep);
            }
        }

        if (discoveredPort > 0 && onDiscoveredBdsUdpPort != null) {
            onDiscoveredBdsUdpPort.accept(discoveredPort);
        }

        if (rewrittenCount == 0 || publicEp == null) {
            return rawResponse;
        }

        byte[] newBodyBytes = rewritten.toString().getBytes(StandardCharsets.UTF_8);
        String newHeaders = updateHttpHeaders(headers, newBodyBytes.length);

        OutputLogger.success(String.format(
                "NetherNet-SDP [%s] ← %s | ICE: %s → %s:%d (Chuyển UDP về cổng BDS: %d)",
                sessionTag, statusLine,
                firstOldAddr != null ? firstOldAddr : "internal",
                publicEp.ip, publicEp.port, discoveredPort));

        byte[] newHdrBytes = (newHeaders + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[newHdrBytes.length + newBodyBytes.length];
        System.arraycopy(newHdrBytes, 0, out, 0, newHdrBytes.length);
        System.arraycopy(newBodyBytes, 0, out, newHdrBytes.length, newBodyBytes.length);
        return out;
    }

    private static String updateHttpHeaders(String headers, int newContentLength) {
        String[] hLines = headers.split("\r\n");
        StringBuilder sb = new StringBuilder();
        boolean replacedCl = false;

        for (String hLine : hLines) {
            String low = hLine.toLowerCase(Locale.ROOT);
            if (low.startsWith("transfer-encoding:")) {
                continue; // Chuyển sang Content-Length cố định
            }
            if (low.startsWith("content-length:")) {
                if (sb.length() > 0) sb.append("\r\n");
                sb.append("Content-Length: ").append(newContentLength);
                replacedCl = true;
            } else {
                if (sb.length() > 0) sb.append("\r\n");
                sb.append(hLine);
            }
        }
        if (!replacedCl) {
            sb.append("\r\nContent-Length: ").append(newContentLength);
        }
        return sb.toString();
    }

    private static String decodeChunkedBody(String chunked) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (idx < chunked.length()) {
            int lineEnd = chunked.indexOf("\r\n", idx);
            if (lineEnd < 0) break;
            String sizeHex = chunked.substring(idx, lineEnd).split(";", 2)[0].trim();
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(sizeHex, 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (chunkSize == 0) break;
            int dataStart = lineEnd + 2;
            int dataEnd = Math.min(dataStart + chunkSize, chunked.length());
            sb.append(chunked, dataStart, dataEnd);
            idx = dataEnd + 2;
        }
        return sb.length() > 0 ? sb.toString() : chunked;
    }

    private static int extractHeaderInt(String headersLower, String headerPrefix) {
        int idx = headersLower.indexOf(headerPrefix);
        if (idx < 0) return -1;
        int end = headersLower.indexOf('\n', idx);
        String val = (end > idx ? headersLower.substring(idx + headerPrefix.length(), end)
                : headersLower.substring(idx + headerPrefix.length())).trim();
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
