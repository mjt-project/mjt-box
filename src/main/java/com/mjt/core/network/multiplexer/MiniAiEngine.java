package com.mjt.core.network.multiplexer;

import com.mjt.core.ui.OutputLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mini AI Engine (100% Offline, Zero-Dependency + Optional Online Fallback).
 *
 * Chức năng cho nhóm Other:
 *   1. Trích xuất vector đặc trưng (độ dài, Entropy Shannon, tỷ lệ ASCII, VarInt,
 *      byte ma thuật RakNet/SOCKS/RFB/RDP/Redis/SQL...) từ gói tin đầu tiên.
 *   2. Phân loại giao thức bằng mô hình chấm điểm trọng số (Offline Perceptron/Bayesian).
 *   3. Kiểm chứng phản hồi từ backend trong vòng lặp Round-Robin để phát hiện sớm
 *      trường hợp nối nhầm dịch vụ (Cross-Protocol Mismatch / Instant Close).
 *   4. Tự học Q-Score (Reinforcement Learning cục bộ): Ghi nhớ điểm tin cậy theo
 *      từng cặp (Giao thức -> Cổng) với cơ chế tự phân rã (decay) theo thời gian.
 *   5. Tùy chọn Online AI Fallback (chỉ chạy khi người dùng bật và cấu hình API miễn phí).
 */
public class MiniAiEngine {

    /**
     * Kết quả phân loại gói tin của Mini AI
     */
    public static class AiClassification {
        public final String label;
        public final double confidence;
        public final boolean expectsImmediateReply;
        public final String source;
        public final String detail;

        public AiClassification(String label, double confidence,
                                boolean expectsImmediateReply, String source, String detail) {
            this.label = label;
            this.confidence = confidence;
            this.expectsImmediateReply = expectsImmediateReply;
            this.source = source;
            this.detail = detail;
        }
    }

    /**
     * Trạng thái đánh giá phản hồi từ backend trong nhóm Other
     */
    public enum BackendVerdict {
        CONFIRMED,        // Phản hồi hợp lệ, giữ kết nối ngay
        MISMATCH_REJECT,  // Phản hồi sai giao thức (ví dụ HTTP/SSH trả lời gói game) -> thử cổng tiếp
        PASSIVE_SILENT    // Backend giữ socket mở im lặng (có thể đang chờ gói tiếp theo)
    }

    private static class PortExperience {
        double qScore = 0.0;
        int consecutiveFailures = 0;
        long lastUpdatedMs = System.currentTimeMillis();
    }

    // Cấu hình
    private volatile boolean enabled;
    private volatile boolean onlineEnabled;
    private volatile String onlineApiUrl;
    private volatile String onlineApiKey;
    private volatile String onlineModel;

    // Bộ nhớ kinh nghiệm Q-Table: key = "LABEL@PORT"
    private final ConcurrentHashMap<String, PortExperience> qTable = new ConcurrentHashMap<>();
    // Cache phân loại cho chữ ký byte lạ (từ Offline / Online AI)
    private final ConcurrentHashMap<String, AiClassification> signatureCache = new ConcurrentHashMap<>();

    private static final double MIN_SCORE_CLAMP = -5.0;
    private static final double MAX_SCORE_CLAMP = 10.0;
    private static final double DEMOTE_THRESHOLD = -1.5;
    private static final long PENALTY_DECAY_MS = 60_000L; // Quên dần lỗi cũ sau 60 giây

    public MiniAiEngine(boolean enabled, boolean onlineEnabled,
                        String onlineApiUrl, String onlineApiKey, String onlineModel) {
        this.enabled = enabled;
        this.onlineEnabled = onlineEnabled;
        this.onlineApiUrl = onlineApiUrl != null ? onlineApiUrl.trim() : "";
        this.onlineApiKey = onlineApiKey != null ? onlineApiKey.trim() : "";
        this.onlineModel = (onlineModel != null && !onlineModel.trim().isEmpty())
                ? onlineModel.trim() : "gpt-4o-mini";
    }

    public void updateConfig(boolean enabled, boolean onlineEnabled,
                             String onlineApiUrl, String onlineApiKey, String onlineModel) {
        this.enabled = enabled;
        this.onlineEnabled = onlineEnabled;
        this.onlineApiUrl = onlineApiUrl != null ? onlineApiUrl.trim() : "";
        this.onlineApiKey = onlineApiKey != null ? onlineApiKey.trim() : "";
        if (onlineModel != null && !onlineModel.trim().isEmpty()) {
            this.onlineModel = onlineModel.trim();
        }
    }

    // ==========================================
    // 1. PHÂN LOẠI GÓI TIN (OFFLINE AI + OPTIONAL ONLINE)
    // ==========================================

    /**
     * Phân loại gói tin đầu tiên bằng AI mini offline (< 1ms).
     * Nếu độ tin cậy thấp và bật Online AI fallback, có thể tham khảo thêm API miễn phí.
     */
    public AiClassification classify(byte[] data) {
        if (!enabled || data == null || data.length == 0) {
            return new AiClassification("EMPTY_STREAM", 0.50, false, "OFFLINE_AI", "Không có dữ liệu mồi");
        }

        // Kiểm tra cache chữ ký trước
        String prefixSig = computePrefixSignature(data);
        AiClassification cached = signatureCache.get(prefixSig);
        if (cached != null) {
            return new AiClassification(cached.label, cached.confidence,
                    cached.expectsImmediateReply, "AI_MEMORY_CACHE", cached.detail);
        }

        AiClassification offlineResult = classifyOffline(data);

        // Nếu AI offline chưa chắc chắn và người dùng có cấu hình Online AI fallback miễn phí
        if (onlineEnabled && !onlineApiUrl.isEmpty() && offlineResult.confidence < 0.55) {
            AiClassification onlineResult = tryClassifyOnline(data);
            if (onlineResult != null) {
                signatureCache.put(prefixSig, onlineResult);
                return onlineResult;
            }
        }

        return offlineResult;
    }

    /**
     * Mô hình phân loại Offline sử dụng trích xuất đặc trưng & trọng số tuyến tính.
     */
    private AiClassification classifyOffline(byte[] data) {
        int len = data.length;

        // Trích xuất đặc trưng (Feature Extraction)
        double entropy = computeShannonEntropy(data);
        double asciiRatio = computeAsciiRatio(data);

        // Kiểm tra Minecraft Java (Modern VarInt + Legacy Ping)
        ProtocolSniffer.MinecraftHandshakeInfo mcInfo = ProtocolSniffer.inspectMinecraftHandshake(data, len);
        if (mcInfo != null) {
            boolean hasFollowUp = mcInfo.hasExtraPacketBytes;
            if (mcInfo.nextState == 1) {
                return new AiClassification("MINECRAFT_PING", 0.98, hasFollowUp,
                        "OFFLINE_AI", "MC Server List Ping (proto=" + mcInfo.protocolVersion + ")");
            } else if (mcInfo.nextState == 2 || mcInfo.nextState == 3) {
                return new AiClassification("MINECRAFT_LOGIN", 0.97, hasFollowUp,
                        "OFFLINE_AI", "MC Login/Transfer (host=" + mcInfo.hostname + ")");
            }
            return new AiClassification("MINECRAFT_JAVA", 0.88, hasFollowUp,
                    "OFFLINE_AI", "MC VarInt Handshake");
        }
        if ((data[0] & 0xFF) == 0xFE) {
            return new AiClassification("MINECRAFT_LEGACY", 0.94, true,
                    "OFFLINE_AI", "MC Legacy Ping 0xFE");
        }

        // Kiểm tra Minecraft RakNet (Bedrock over tunnel)
        if (len >= 17 && containsRakNetMagic(data)) {
            return new AiClassification("MINECRAFT_RAKNET", 0.95, true,
                    "OFFLINE_AI", "RakNet/Bedrock Magic");
        }

        // Kiểm tra SOCKS5 / SOCKS4 Proxy
        if (len >= 3 && data[0] == 0x05 && (data[1] & 0xFF) >= 1 && (data[1] & 0xFF) <= 10
                && len >= 2 + (data[1] & 0xFF)) {
            return new AiClassification("SOCKS5_PROXY", 0.93, true,
                    "OFFLINE_AI", "SOCKS5 Handshake");
        }
        if (len >= 8 && data[0] == 0x04 && (data[1] == 0x01 || data[1] == 0x02)) {
            return new AiClassification("SOCKS4_PROXY", 0.90, true,
                    "OFFLINE_AI", "SOCKS4 Request");
        }

        // Kiểm tra VNC (RFB) & RDP (TPKT)
        String asciiHead = new String(data, 0, Math.min(len, 16), StandardCharsets.US_ASCII);
        if (asciiHead.startsWith("RFB 003.")) {
            return new AiClassification("VNC_RFB", 0.96, true, "OFFLINE_AI", "VNC RFB Protocol");
        }
        if (len >= 7 && data[0] == 0x03 && data[1] == 0x00 && (data[5] & 0xFF) == 0xE0) {
            return new AiClassification("RDP_TPKT", 0.92, true, "OFFLINE_AI", "Microsoft RDP");
        }

        // Kiểm tra Redis RESP (*N\r\n$...)
        if (asciiHead.startsWith("*") && asciiHead.contains("\r\n$")) {
            return new AiClassification("REDIS_RESP", 0.94, true, "OFFLINE_AI", "Redis RESP Command");
        }

        // Kiểm tra PostgreSQL SSLRequest (00 00 00 08 04 d2 16 2f)
        if (len == 8 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 8
                && (data[4] & 0xFF) == 0x04 && (data[5] & 0xFF) == 0xD2
                && (data[6] & 0xFF) == 0x16 && (data[7] & 0xFF) == 0x2F) {
            return new AiClassification("POSTGRES_SSL", 0.95, true, "OFFLINE_AI", "PostgreSQL SSLRequest");
        }

        // Mô hình chấm điểm đặc trưng thống kê (Statistical Neural Scoring) cho giao thức lạ
        double plaintextScore = (asciiRatio * 0.85) + ((entropy < 5.2) ? 0.25 : -0.2);
        double encryptedScore = ((entropy > 6.8) ? 0.70 : 0.0) + ((asciiRatio < 0.25) ? 0.25 : 0.0);

        if (plaintextScore >= 0.75) {
            return new AiClassification("PLAINTEXT_CUSTOM", Math.min(0.85, plaintextScore),
                    false, "OFFLINE_AI",
                    String.format(Locale.ROOT, "ASCII=%.0f%% H=%.2f", asciiRatio * 100, entropy));
        }
        if (encryptedScore >= 0.70 && len >= 16) {
            return new AiClassification("ENCRYPTED_BINARY", Math.min(0.85, encryptedScore),
                    false, "OFFLINE_AI",
                    String.format(Locale.ROOT, "HighEntropy H=%.2f len=%d", entropy, len));
        }

        return new AiClassification("UNKNOWN_BINARY", 0.40, false, "OFFLINE_AI",
                String.format(Locale.ROOT, "len=%d ASCII=%.0f%% H=%.2f", len, asciiRatio * 100, entropy));
    }

    // ==========================================
    // 2. KIỂM CHỨNG PHẢN HỒI BACKEND TRONG OTHER
    // ==========================================

    /**
     * Phân tích dữ liệu backend gửi lại trong nhóm Other để phát hiện xem có bị nối nhầm
     * sang dịch vụ SSH/HTTP hay không, hoặc đã nhận đúng phản hồi.
     */
    public BackendVerdict verifyBackendResponse(AiClassification clientClass, byte[] response, int len) {
        if (response == null || len <= 0) {
            return BackendVerdict.PASSIVE_SILENT;
        }

        // Bắt lỗi nối nhầm nghiêm trọng: Dịch vụ ở Other lại trả lời bằng SSH hoặc HTTP!
        if (ProtocolSniffer.isShellResponse(response, len)) {
            return BackendVerdict.MISMATCH_REJECT;
        }
        if (ProtocolSniffer.isHttpResponse(response, len)) {
            // Ngoại lệ: nếu client thực chất là HTTP không nhận diện được trước đó thì vẫn nhận
            if (!"PLAINTEXT_CUSTOM".equals(clientClass.label)) {
                return BackendVerdict.MISMATCH_REJECT;
            }
        }

        return BackendVerdict.CONFIRMED;
    }

    // ==========================================
    // 3. REINFORCEMENT LEARNING (Q-SCORE MEMORY)
    // ==========================================

    /**
     * Sắp xếp lại danh sách Round-Robin một cách thích ứng:
     * Giữ nguyên thứ tự xoay vòng cho tất cả cổng khỏe/chưa rõ,
     * chỉ đẩy các cổng đã bị phạt nặng (Q <= -1.5 trong thời gian gần) xuống cuối danh sách thử.
     */
    public List<Integer> applyAdaptiveOrder(String protocolLabel, List<Integer> roundRobinPorts) {
        if (!enabled || roundRobinPorts == null || roundRobinPorts.size() <= 1) {
            return roundRobinPorts != null ? new ArrayList<>(roundRobinPorts) : Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        List<Integer> healthyOrNeutral = new ArrayList<>();
        List<Integer> penalized = new ArrayList<>();

        for (int port : roundRobinPorts) {
            PortExperience exp = getDecayedExperience(protocolLabel, port, now);
            if (exp != null && exp.qScore <= DEMOTE_THRESHOLD) {
                penalized.add(port);
            } else {
                healthyOrNeutral.add(port);
            }
        }

        // Nếu tất cả đều bị phạt, giữ nguyên vòng lặp Round-Robin gốc để không bị kẹt
        if (healthyOrNeutral.isEmpty()) {
            return new ArrayList<>(roundRobinPorts);
        }

        healthyOrNeutral.addAll(penalized);
        return healthyOrNeutral;
    }

    public void recordVerificationSuccess(String label, int port) {
        if (!enabled) return;
        PortExperience exp = getOrCreateExperience(label, port);
        synchronized (exp) {
            exp.qScore = Math.min(MAX_SCORE_CLAMP, exp.qScore + 1.0);
            exp.consecutiveFailures = 0;
            exp.lastUpdatedMs = System.currentTimeMillis();
        }
    }

    public void recordVerificationFailure(String label, int port) {
        if (!enabled) return;
        PortExperience exp = getOrCreateExperience(label, port);
        synchronized (exp) {
            exp.qScore = Math.max(MIN_SCORE_CLAMP, exp.qScore - 1.5);
            exp.consecutiveFailures++;
            exp.lastUpdatedMs = System.currentTimeMillis();
        }
    }

    public void recordSessionOutcome(String label, int port, long durationMs,
                                     long bytesFromBackend, long bytesToBackend) {
        if (!enabled) return;
        PortExperience exp = getOrCreateExperience(label, port);
        synchronized (exp) {
            if (bytesFromBackend > 0 && (durationMs >= 250 || bytesFromBackend >= 10)) {
                exp.qScore = Math.min(MAX_SCORE_CLAMP, exp.qScore + 0.5);
                exp.consecutiveFailures = 0;
            } else if (bytesFromBackend == 0 && durationMs < 300) {
                exp.qScore = Math.max(MIN_SCORE_CLAMP, exp.qScore - 0.8);
                exp.consecutiveFailures++;
            }
            exp.lastUpdatedMs = System.currentTimeMillis();
        }
    }

    public double getPortScore(String label, int port) {
        PortExperience exp = getDecayedExperience(label, port, System.currentTimeMillis());
        return exp != null ? exp.qScore : 0.0;
    }

    private PortExperience getOrCreateExperience(String label, int port) {
        return qTable.computeIfAbsent(label + "@" + port, k -> new PortExperience());
    }

    private PortExperience getDecayedExperience(String label, int port, long nowMs) {
        PortExperience exp = qTable.get(label + "@" + port);
        if (exp == null) return null;
        synchronized (exp) {
            long elapsed = nowMs - exp.lastUpdatedMs;
            if (elapsed > PENALTY_DECAY_MS && exp.qScore < 0) {
                // Tự phục hồi điểm phạt theo thời gian để cổng có cơ hội được thử lại
                exp.qScore = exp.qScore * 0.5;
                if (exp.qScore > -0.2) {
                    exp.qScore = 0.0;
                    exp.consecutiveFailures = 0;
                }
                exp.lastUpdatedMs = nowMs;
            }
        }
        return exp;
    }

    // ==========================================
    // 4. OPTIONAL ONLINE AI FALLBACK (NẾU BẬT)
    // ==========================================

    private AiClassification tryClassifyOnline(byte[] data) {
        HttpURLConnection conn = null;
        try {
            StringBuilder hex = new StringBuilder();
            int sampleLen = Math.min(data.length, 32);
            for (int i = 0; i < sampleLen; i++) {
                hex.append(String.format("%02x ", data[i] & 0xFF));
            }

            String prompt = "Classify TCP client first bytes (hex): " + hex.toString().trim()
                    + ". Reply ONLY one token from: MINECRAFT, GAME_TCP, PROXY, DATABASE, VNC_RDP, UNKNOWN.";

            String body = "{\"model\":\"" + escapeJson(onlineModel)
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
                    + escapeJson(prompt) + "\"}],\"max_tokens\":16,\"temperature\":0}";

            conn = (HttpURLConnection) new URL(onlineApiUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(2000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (!onlineApiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + onlineApiKey);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                StringBuilder respBody = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        respBody.append(line);
                    }
                }
                Matcher m = Pattern.compile("\"content\"\\s*:\\s*\"([^\"]+)\"").matcher(respBody.toString());
                if (m.find()) {
                    String tag = m.group(1).trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "");
                    if (!tag.isEmpty()) {
                        return new AiClassification("ONLINE_" + tag, 0.78, false,
                                "ONLINE_AI", "Model=" + onlineModel);
                    }
                }
            }
        } catch (Exception e) {
            OutputLogger.warning("Online AI fallback không khả dụng (" + e.getMessage() + "), dùng Offline AI.");
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    // ==========================================
    // HÀM TOÁN HỌC & XỬ LÝ BYTE
    // ==========================================

    private static double computeShannonEntropy(byte[] data) {
        int[] freq = new int[256];
        for (byte b : data) {
            freq[b & 0xFF]++;
        }
        double entropy = 0.0;
        double n = data.length;
        for (int count : freq) {
            if (count > 0) {
                double p = count / n;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy;
    }

    private static double computeAsciiRatio(byte[] data) {
        int printable = 0;
        for (byte b : data) {
            int v = b & 0xFF;
            if ((v >= 0x20 && v <= 0x7E) || v == '\r' || v == '\n' || v == '\t') {
                printable++;
            }
        }
        return (double) printable / data.length;
    }

    private static boolean containsRakNetMagic(byte[] data) {
        byte[] magic = {
            0x00, (byte) 0xFF, (byte) 0xFF, 0x00,
            (byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE,
            (byte) 0xFD, (byte) 0xFD, (byte) 0xFD, (byte) 0xFD,
            0x12, 0x34, 0x56, 0x78
        };
        outer:
        for (int i = 0; i <= data.length - magic.length; i++) {
            for (int j = 0; j < magic.length; j++) {
                if (data[i + j] != magic[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static String computePrefixSignature(byte[] data) {
        int len = Math.min(data.length, 6);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
