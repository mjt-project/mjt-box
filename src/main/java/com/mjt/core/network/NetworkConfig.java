package com.mjt.core.network;

import com.mjt.core.ui.OutputLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quản lý cấu hình mạng ảo MJT v3.
 *
 * Cấu trúc config (network-config.json):
 *   - 3 nhóm cổng tách biệt: web_ports, shell_ports, other_ports
 *   - Thời gian xác minh: ssh_check_timeout_ms (2000), web_check_timeout_ms (2500)
 *   - Cấu hình AI mini (ai_assistant): Offline mặc định, có thể bật thêm Online fallback miễn phí
 *   - Hot-Reload: Tự động cập nhật config khi file thay đổi
 */
public class NetworkConfig {

    private static final String CONFIG_FILE = "./network-config.json";
    private static final String[] SECTION_KEYS = {
        "\"smart_multiplexer\"", "\"ai_assistant\"", "\"port_forwarding\"", "\"minecraft_router\""
    };

    // ===== Cấu hình chung =====
    private boolean enabled;
    private String bindAddress;
    private boolean autoReload;

    // ===== SMART MULTIPLEXER v3 =====
    private boolean smartMultiplexerEnabled;
    private int smartMultiplexerPort;
    private int sshCheckTimeoutMs;
    private int webCheckTimeoutMs;
    private List<Integer> webPorts;    // Tầng 2: HTTP, HTTPS, WebSocket
    private List<Integer> shellPorts;  // Tầng 1: SSH, SFTP, FTP
    private List<Integer> otherPorts;  // Tầng 3: Minecraft, game, dịch vụ khác

    // ===== MINI AI ASSISTANT (Offline mặc định + Optional Online) =====
    private boolean aiEnabled;
    private boolean aiOnlineEnabled;
    private String aiOnlineApiUrl;
    private String aiOnlineApiKey;
    private String aiOnlineModel;

    // ===== PORT FORWARDING =====
    private List<PortMapping> mappings;

    // ===== MINECRAFT ROUTER =====
    private boolean minecraftRouterEnabled;
    private int minecraftRouterPort;
    private Map<String, Integer> minecraftRoutes;
    private int minecraftDefaultPort;

    public NetworkConfig() {
        this.enabled = false;
        this.bindAddress = "0.0.0.0";
        this.autoReload = true;

        this.smartMultiplexerEnabled = true;
        this.smartMultiplexerPort = 25565;
        this.sshCheckTimeoutMs = 2000;
        this.webCheckTimeoutMs = 2500;
        this.webPorts = new ArrayList<>();
        this.shellPorts = new ArrayList<>();
        this.otherPorts = new ArrayList<>();

        this.aiEnabled = true;
        this.aiOnlineEnabled = false;
        this.aiOnlineApiUrl = "";
        this.aiOnlineApiKey = "";
        this.aiOnlineModel = "gpt-4o-mini";

        this.mappings = new ArrayList<>();

        this.minecraftRouterEnabled = false;
        this.minecraftRouterPort = 25000;
        this.minecraftRoutes = new LinkedHashMap<>();
        this.minecraftDefaultPort = 25565;
    }

    public static NetworkConfig load() {
        File configFile = new File(CONFIG_FILE);

        if (!configFile.exists()) {
            OutputLogger.warning("Không tìm thấy tệp cấu hình mạng. Đang khởi tạo " + CONFIG_FILE + "...");
            createDefaultConfig();
            return new NetworkConfig();
        }

        try {
            String json = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            return parse(json);
        } catch (Exception e) {
            OutputLogger.error("Lỗi đọc cấu hình mạng: " + e.getMessage());
            return new NetworkConfig();
        }
    }

    public static String getConfigFilePath() {
        return CONFIG_FILE;
    }

    // ==========================================
    // PHÂN TÍCH JSON BẰNG REGEX (Zero-Dependency)
    // ==========================================

    private static NetworkConfig parse(String rawJson) {
        String json = rawJson.replaceAll("(?m)^\\s*//.*$", "")
                .replaceAll("/\\*.*?\\*/", "");

        NetworkConfig config = new NetworkConfig();

        // Top-level trước khối đầu tiên
        int firstSection = json.length();
        for (String key : SECTION_KEYS) {
            int idx = json.indexOf(key);
            if (idx > 0) {
                firstSection = Math.min(firstSection, idx);
            }
        }
        String topSection = json.substring(0, firstSection);
        config.enabled = parseBoolean(topSection, "enabled", config.enabled);
        config.bindAddress = parseString(topSection, "bind_address", config.bindAddress);
        config.autoReload = parseBoolean(topSection, "auto_reload", config.autoReload);

        // ===== 1. Parse Smart Multiplexer =====
        String muxSection = extractSection(json, "\"smart_multiplexer\"");
        if (muxSection != null) {
            config.smartMultiplexerEnabled = parseBoolean(muxSection, "enabled", config.smartMultiplexerEnabled);
            config.smartMultiplexerPort = parseInt(muxSection, "listen_port", config.smartMultiplexerPort);
            config.sshCheckTimeoutMs = parseInt(muxSection, "ssh_check_timeout_ms", config.sshCheckTimeoutMs);
            config.webCheckTimeoutMs = parseInt(muxSection, "web_check_timeout_ms", config.webCheckTimeoutMs);

            config.webPorts = parseIntArray(muxSection, "web_ports");
            config.shellPorts = parseIntArray(muxSection, "shell_ports");
            config.otherPorts = parseIntArray(muxSection, "other_ports");

            if (config.webPorts.isEmpty() && config.shellPorts.isEmpty()
                    && config.otherPorts.isEmpty()) {
                parseLegacyRules(muxSection, config);
            }
        }

        // ===== 2. Parse AI Assistant =====
        String aiSection = extractSection(json, "\"ai_assistant\"");
        if (aiSection != null) {
            config.aiEnabled = parseBoolean(aiSection, "enabled", config.aiEnabled);
            config.aiOnlineEnabled = parseBoolean(aiSection, "online_enabled", config.aiOnlineEnabled);
            config.aiOnlineApiUrl = parseString(aiSection, "online_api_url", config.aiOnlineApiUrl);
            config.aiOnlineApiKey = parseString(aiSection, "online_api_key", config.aiOnlineApiKey);
            config.aiOnlineModel = parseString(aiSection, "online_model", config.aiOnlineModel);
        }

        // ===== 3. Parse Port Forwarding Mappings =====
        String pfSection = extractSection(json, "\"port_forwarding\"");
        if (pfSection != null) {
            String mappingRegex = "\"name\"\\s*:\\s*\"([^\"]+)\".*?" +
                    "\"public_port\"\\s*:\\s*(\\d+).*?" +
                    "\"internal_port\"\\s*:\\s*(\\d+).*?" +
                    "\"protocol\"\\s*:\\s*\"([^\"]+)\".*?" +
                    "\"enabled\"\\s*:\\s*(true|false)";

            Matcher mMatcher = Pattern.compile(mappingRegex, Pattern.DOTALL).matcher(pfSection);
            while (mMatcher.find()) {
                config.mappings.add(new PortMapping(
                        mMatcher.group(1),
                        Integer.parseInt(mMatcher.group(2)),
                        Integer.parseInt(mMatcher.group(3)),
                        mMatcher.group(4),
                        Boolean.parseBoolean(mMatcher.group(5))));
            }
        }

        // ===== 4. Parse Minecraft Router =====
        String mcSection = extractSection(json, "\"minecraft_router\"");
        if (mcSection != null) {
            config.minecraftRouterEnabled = parseBoolean(mcSection, "enabled", config.minecraftRouterEnabled);
            config.minecraftRouterPort = parseInt(mcSection, "listen_port", config.minecraftRouterPort);
            config.minecraftDefaultPort = parseInt(mcSection, "default_port", config.minecraftDefaultPort);

            String routeRegex = "\"hostname\"\\s*:\\s*\"([^\"]+)\".*?\"target_port\"\\s*:\\s*(\\d+)";
            Matcher rMatcher = Pattern.compile(routeRegex, Pattern.DOTALL).matcher(mcSection);
            while (rMatcher.find()) {
                config.minecraftRoutes.put(rMatcher.group(1), Integer.parseInt(rMatcher.group(2)));
            }
        }

        return config;
    }

    /**
     * Trích xuất đoạn JSON từ sectionKey cho tới section top-level kế tiếp (không phụ thuộc thứ tự key).
     */
    private static String extractSection(String json, String sectionKey) {
        int start = json.indexOf(sectionKey);
        if (start < 0) return null;

        int end = json.length();
        for (String otherKey : SECTION_KEYS) {
            if (otherKey.equals(sectionKey)) continue;
            int idx = json.indexOf(otherKey, start + sectionKey.length());
            if (idx > start) {
                end = Math.min(end, idx);
            }
        }
        return json.substring(start, end);
    }

    private static boolean parseBoolean(String section, String key, boolean fallback) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(section);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : fallback;
    }

    private static int parseInt(String section, String key, int fallback) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(section);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static String parseString(String section, String key, String fallback) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(section);
        return m.find() ? m.group(1) : fallback;
    }

    private static List<Integer> parseIntArray(String section, String fieldName) {
        List<Integer> result = new ArrayList<>();
        int fieldIdx = section.indexOf("\"" + fieldName + "\"");
        if (fieldIdx >= 0) {
            int arrOpen = section.indexOf('[', fieldIdx);
            int arrClose = section.indexOf(']', arrOpen);
            if (arrOpen >= 0 && arrClose > arrOpen) {
                String arrContent = section.substring(arrOpen + 1, arrClose);
                Matcher numMatcher = Pattern.compile("\\d+").matcher(arrContent);
                while (numMatcher.find()) {
                    result.add(Integer.parseInt(numMatcher.group()));
                }
            }
        }
        return result;
    }

    private static void parseLegacyRules(String muxSection, NetworkConfig config) {
        String ruleRegex = "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*?" +
                "\"protocol\"\\s*:\\s*\"([^\"]+)\"[^}]*?" +
                "\"target_port\"\\s*:\\s*(\\d+)[^}]*?" +
                "\"enabled\"\\s*:\\s*(true|false)[^}]*\\}";
        Matcher ruleMatcher = Pattern.compile(ruleRegex, Pattern.DOTALL).matcher(muxSection);

        while (ruleMatcher.find()) {
            String proto = ruleMatcher.group(2).trim().toUpperCase();
            int port = Integer.parseInt(ruleMatcher.group(3));
            boolean isRuleEnabled = Boolean.parseBoolean(ruleMatcher.group(4));

            if (!isRuleEnabled) continue;

            if (proto.contains("HTTP") || proto.contains("WEB") || proto.contains("TLS")
                    || proto.contains("HTTPS")) {
                if (!config.webPorts.contains(port)) config.webPorts.add(port);
            } else if (proto.contains("SSH") || proto.contains("SFTP") || proto.contains("FTP")) {
                if (!config.shellPorts.contains(port)) config.shellPorts.add(port);
            } else {
                if (!config.otherPorts.contains(port)) config.otherPorts.add(port);
            }
        }

        int fbStart = muxSection.indexOf("\"fallback_chain\"");
        if (fbStart >= 0) {
            List<Integer> fallbacks = parseIntArray(muxSection, "fallback_chain");
            for (int p : fallbacks) {
                if (!config.webPorts.contains(p) && !config.shellPorts.contains(p)
                        && !config.otherPorts.contains(p)) {
                    config.otherPorts.add(p);
                }
            }
        }

        if (!config.webPorts.isEmpty() || !config.shellPorts.isEmpty()
                || !config.otherPorts.isEmpty()) {
            OutputLogger.info("Tương thích ngược: Đã chuyển đổi priority_rules cũ → 3 nhóm cổng mới.");
        }
    }

    public static void createDefaultConfig() {
        String defaultJson = "{\n" +
                "  \"enabled\": true,\n" +
                "  \"bind_address\": \"0.0.0.0\",\n" +
                "  \"auto_reload\": true,\n" +
                "\n" +
                "  \"smart_multiplexer\": {\n" +
                "    \"enabled\": true,\n" +
                "    \"listen_port\": 25565,\n" +
                "    \"ssh_check_timeout_ms\": 2000,\n" +
                "    \"web_check_timeout_ms\": 2500,\n" +
                "\n" +
                "    \"web_ports\": [8080, 8081],\n" +
                "    \"shell_ports\": [2222],\n" +
                "    \"other_ports\": [25566, 25567]\n" +
                "  },\n" +
                "\n" +
                "  \"ai_assistant\": {\n" +
                "    \"enabled\": true,\n" +
                "    \"online_enabled\": false,\n" +
                "    \"online_api_url\": \"\",\n" +
                "    \"online_api_key\": \"\",\n" +
                "    \"online_model\": \"gpt-4o-mini\"\n" +
                "  },\n" +
                "\n" +
                "  \"port_forwarding\": [],\n" +
                "\n" +
                "  \"minecraft_router\": {\n" +
                "    \"enabled\": false,\n" +
                "    \"listen_port\": 25000,\n" +
                "    \"default_port\": 25565,\n" +
                "    \"routes\": []\n" +
                "  }\n" +
                "}\n";

        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            fos.write(defaultJson.getBytes(StandardCharsets.UTF_8));
            OutputLogger.success("Đã tạo tệp cấu hình mạng mẫu: " + CONFIG_FILE);
        } catch (IOException e) {
            OutputLogger.error("Không thể ghi tệp cấu hình mạng: " + e.getMessage());
        }
    }

    // ==========================================
    // GETTERS
    // ==========================================

    public boolean isEnabled() { return enabled; }
    public String getBindAddress() { return bindAddress; }
    public boolean isAutoReload() { return autoReload; }

    public boolean isSmartMultiplexerEnabled() { return smartMultiplexerEnabled; }
    public int getSmartMultiplexerPort() { return smartMultiplexerPort; }
    public int getSshCheckTimeoutMs() { return sshCheckTimeoutMs; }
    public int getWebCheckTimeoutMs() { return webCheckTimeoutMs; }
    public List<Integer> getWebPorts() { return webPorts; }
    public List<Integer> getShellPorts() { return shellPorts; }
    public List<Integer> getOtherPorts() { return otherPorts; }

    public boolean isAiEnabled() { return aiEnabled; }
    public boolean isAiOnlineEnabled() { return aiOnlineEnabled; }
    public String getAiOnlineApiUrl() { return aiOnlineApiUrl; }
    public String getAiOnlineApiKey() { return aiOnlineApiKey; }
    public String getAiOnlineModel() { return aiOnlineModel; }

    public List<PortMapping> getMappings() { return mappings; }

    public boolean isMinecraftRouterEnabled() { return minecraftRouterEnabled; }
    public int getMinecraftRouterPort() { return minecraftRouterPort; }
    public Map<String, Integer> getMinecraftRoutes() { return minecraftRoutes; }
    public int getMinecraftDefaultPort() { return minecraftDefaultPort; }
}
