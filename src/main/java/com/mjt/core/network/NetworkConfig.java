package com.mjt.core.network;

import com.mjt.core.network.multiplexer.MultiplexRule;
import com.mjt.core.network.multiplexer.ProtocolType;
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
 * Quản lý cấu hình mạng ảo (Virtual Network Configuration).
 * Đọc/ghi tệp network-config.json bằng Regex (Zero-Dependency).
 */
public class NetworkConfig {

    private static final String CONFIG_FILE = "./network-config.json";

    // ===== Cấu hình chung =====
    private boolean enabled;
    private String bindAddress;

    // ===== CHẾ ĐỘ 1: SMART MULTIPLEXER (Dùng chung 1 Port duy nhất) =====
    private boolean smartMultiplexerEnabled;
    private int smartMultiplexerPort;
    private List<MultiplexRule> multiplexRules;
    private List<Integer> fallbackChain;

    // ===== CHẾ ĐỘ 2: PORT FORWARDING (Chuyển tiếp nhiều cổng riêng biệt) =====
    private List<PortMapping> mappings;

    // ===== CHẾ ĐỘ 3: MINECRAFT ROUTER RIÊNG BIỆT =====
    private boolean minecraftRouterEnabled;
    private int minecraftRouterPort;
    private Map<String, Integer> minecraftRoutes; // hostname → target_port
    private int minecraftDefaultPort;

    public NetworkConfig() {
        this.enabled = false;
        this.bindAddress = "0.0.0.0";

        // Mặc định Smart Multiplexer
        this.smartMultiplexerEnabled = true;
        this.smartMultiplexerPort = 20002;
        this.multiplexRules = new ArrayList<>();
        this.fallbackChain = new ArrayList<>();

        // Mặc định Port Forwarding
        this.mappings = new ArrayList<>();

        // Mặc định Minecraft Router
        this.minecraftRouterEnabled = false;
        this.minecraftRouterPort = 25000;
        this.minecraftRoutes = new LinkedHashMap<>();
        this.minecraftDefaultPort = 25565;
    }

    /**
     * Nạp cấu hình từ tệp. Nếu chưa tồn tại, tự động tạo mẫu mặc định.
     */
    public static NetworkConfig load() {
        File configFile = new File(CONFIG_FILE);

        if (!configFile.exists()) {
            OutputLogger.warning("Không tìm thấy tệp cấu hình mạng. Đang khởi tạo " + CONFIG_FILE + "...");
            createDefaultConfig();
            return new NetworkConfig();
        }

        try {
            String json = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            return parse(json);
        } catch (Exception e) {
            OutputLogger.error("Lỗi đọc cấu hình mạng: " + e.getMessage());
            return new NetworkConfig();
        }
    }

    // ==========================================
    // PHÂN TÍCH JSON BẰNG REGEX (Zero-Dependency)
    // ==========================================

    private static NetworkConfig parse(String rawJson) {
        // Loại bỏ comment // hoặc /* */ nếu người dùng có gõ vào
        String json = rawJson.replaceAll("(?m)^\\s*//.*$", "")
                .replaceAll("/\\*.*?\\*/", "");

        NetworkConfig config = new NetworkConfig();

        int muxIndex = json.indexOf("\"smart_multiplexer\"");
        int pfIndex = json.indexOf("\"port_forwarding\"");
        int mcIndex = json.indexOf("\"minecraft_router\"");

        // Tìm enabled và bind_address ở đầu file (top-level)
        int firstSection = json.length();
        if (muxIndex > 0)
            firstSection = Math.min(firstSection, muxIndex);
        if (pfIndex > 0)
            firstSection = Math.min(firstSection, pfIndex);
        if (mcIndex > 0)
            firstSection = Math.min(firstSection, mcIndex);

        String topSection = json.substring(0, firstSection);
        Matcher enabledMatcher = Pattern.compile("\"enabled\"\\s*:\\s*(true|false)").matcher(topSection);
        if (enabledMatcher.find()) {
            config.enabled = Boolean.parseBoolean(enabledMatcher.group(1));
        }

        Matcher bindMatcher = Pattern.compile("\"bind_address\"\\s*:\\s*\"([^\"]+)\"").matcher(topSection);
        if (bindMatcher.find()) {
            config.bindAddress = bindMatcher.group(1);
        }

        // ===== 1. Parse Smart Multiplexer =====
        if (muxIndex >= 0) {
            int muxEnd = json.length();
            if (pfIndex > muxIndex)
                muxEnd = Math.min(muxEnd, pfIndex);
            if (mcIndex > muxIndex)
                muxEnd = Math.min(muxEnd, mcIndex);
            String muxSection = json.substring(muxIndex, muxEnd);

            Matcher muxEn = Pattern.compile("\"enabled\"\\s*:\\s*(true|false)").matcher(muxSection);
            if (muxEn.find()) {
                config.smartMultiplexerEnabled = Boolean.parseBoolean(muxEn.group(1));
            }

            Matcher muxPort = Pattern.compile("\"listen_port\"\\s*:\\s*(\\d+)").matcher(muxSection);
            if (muxPort.find()) {
                config.smartMultiplexerPort = Integer.parseInt(muxPort.group(1));
            }

            // Parse fallback_chain
            int fbStart = muxSection.indexOf("\"fallback_chain\"");
            if (fbStart >= 0) {
                int arrOpen = muxSection.indexOf('[', fbStart);
                int arrClose = muxSection.indexOf(']', arrOpen);
                if (arrOpen >= 0 && arrClose > arrOpen) {
                    String fbArr = muxSection.substring(arrOpen + 1, arrClose);
                    Matcher numMatcher = Pattern.compile("\\d+").matcher(fbArr);
                    while (numMatcher.find()) {
                        config.fallbackChain.add(Integer.parseInt(numMatcher.group()));
                    }
                }
            }

            // Parse priority_rules
            String ruleBlockRegex = "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"protocol\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"target_port\"\\s*:\\s*(\\d+)[^}]*?\"enabled\"\\s*:\\s*(true|false)([^}]*)\\}";
            Matcher ruleMatcher = Pattern.compile(ruleBlockRegex, Pattern.DOTALL).matcher(muxSection);

            while (ruleMatcher.find()) {
                String rName = ruleMatcher.group(1);
                String rProtoStr = ruleMatcher.group(2).trim().toUpperCase();
                int rPort = Integer.parseInt(ruleMatcher.group(3));
                boolean rEnabled = Boolean.parseBoolean(ruleMatcher.group(4));
                String rExtra = ruleMatcher.group(5);

                ProtocolType pType = ProtocolType.UNKNOWN;
                try {
                    pType = ProtocolType.valueOf(rProtoStr);
                } catch (IllegalArgumentException e) {
                    if (rProtoStr.contains("MINECRAFT") || rProtoStr.contains("MC"))
                        pType = ProtocolType.MINECRAFT;
                    else if (rProtoStr.contains("HTTP") || rProtoStr.contains("WEB"))
                        pType = ProtocolType.HTTP;
                    else if (rProtoStr.contains("SSH"))
                        pType = ProtocolType.SSH;
                    else if (rProtoStr.contains("TLS") || rProtoStr.contains("HTTPS"))
                        pType = ProtocolType.HTTPS_TLS;
                }

                MultiplexRule rule = new MultiplexRule(rName, pType, rPort, rEnabled);

                // Parse host_routes (nếu có)
                if (rExtra != null && rExtra.contains("\"host_routes\"")) {
                    Matcher hrMatcher = Pattern
                            .compile("\"host\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"target_port\"\\s*:\\s*(\\d+)")
                            .matcher(rExtra);
                    while (hrMatcher.find()) {
                        rule.addHostRoute(hrMatcher.group(1), Integer.parseInt(hrMatcher.group(2)));
                    }
                }

                // Parse path_routes (nếu có)
                if (rExtra != null && rExtra.contains("\"path_routes\"")) {
                    Matcher prMatcher = Pattern
                            .compile("\"path\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"target_port\"\\s*:\\s*(\\d+)")
                            .matcher(rExtra);
                    while (prMatcher.find()) {
                        rule.addPathRoute(prMatcher.group(1), Integer.parseInt(prMatcher.group(2)));
                    }
                }

                config.multiplexRules.add(rule);
            }
        }

        // ===== 2. Parse Port Forwarding Mappings =====
        if (pfIndex >= 0) {
            int pfEnd = json.length();
            if (mcIndex > pfIndex)
                pfEnd = Math.min(pfEnd, mcIndex);
            String pfSection = json.substring(pfIndex, pfEnd);

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

        // ===== 3. Parse Minecraft Router =====
        if (mcIndex >= 0) {
            String mcSection = json.substring(mcIndex);

            Matcher mcEn = Pattern.compile("\"enabled\"\\s*:\\s*(true|false)").matcher(mcSection);
            if (mcEn.find()) {
                config.minecraftRouterEnabled = Boolean.parseBoolean(mcEn.group(1));
            }

            Matcher mcPort = Pattern.compile("\"listen_port\"\\s*:\\s*(\\d+)").matcher(mcSection);
            if (mcPort.find()) {
                config.minecraftRouterPort = Integer.parseInt(mcPort.group(1));
            }

            Matcher mcDef = Pattern.compile("\"default_port\"\\s*:\\s*(\\d+)").matcher(mcSection);
            if (mcDef.find()) {
                config.minecraftDefaultPort = Integer.parseInt(mcDef.group(1));
            }

            String routeRegex = "\"hostname\"\\s*:\\s*\"([^\"]+)\".*?\"target_port\"\\s*:\\s*(\\d+)";
            Matcher rMatcher = Pattern.compile(routeRegex, Pattern.DOTALL).matcher(mcSection);
            while (rMatcher.find()) {
                config.minecraftRoutes.put(rMatcher.group(1), Integer.parseInt(rMatcher.group(2)));
            }
        }

        return config;
    }

    /**
     * Tạo tệp cấu hình mẫu chuẩn JSON (RFC 8259)
     */
    public static void createDefaultConfig() {
        String defaultJson = "{\n" +
                "  \"enabled\": true,\n" +
                "  \"bind_address\": \"0.0.0.0\",\n" +
                "  \"smart_multiplexer\": {\n" +
                "    \"enabled\": true,\n" +
                "    \"listen_port\": 20002,\n" +
                "    \"priority_rules\": [\n" +
                "      {\n" +
                "        \"name\": \"minecraft-server\",\n" +
                "        \"protocol\": \"MINECRAFT\",\n" +
                "        \"target_port\": 25565,\n" +
                "        \"enabled\": true,\n" +
                "        \"host_routes\": [\n" +
                "          { \"host\": \"mc1\", \"target_port\": 25565 },\n" +
                "          { \"host\": \"mc2\", \"target_port\": 25566 }\n" +
                "        ]\n" +
                "      },\n" +
                "      {\n" +
                "        \"name\": \"web-and-vscode\",\n" +
                "        \"protocol\": \"HTTP\",\n" +
                "        \"target_port\": 8080,\n" +
                "        \"enabled\": true,\n" +
                "        \"host_routes\": [\n" +
                "          { \"host\": \"web\", \"target_port\": 8080 },\n" +
                "          { \"host\": \"code\", \"target_port\": 8443 }\n" +
                "        ],\n" +
                "        \"path_routes\": [\n" +
                "          { \"path\": \"/vscode\", \"target_port\": 8443 }\n" +
                "        ]\n" +
                "      },\n" +
                "      {\n" +
                "        \"name\": \"ssh-service\",\n" +
                "        \"protocol\": \"SSH\",\n" +
                "        \"target_port\": 2222,\n" +
                "        \"enabled\": false\n" +
                "      }\n" +
                "    ],\n" +
                "    \"fallback_chain\": [25565, 8080, 8443, 2222]\n" +
                "  },\n" +
                "  \"port_forwarding\": [\n" +
                "    {\n" +
                "      \"name\": \"minecraft-direct\",\n" +
                "      \"public_port\": 20002,\n" +
                "      \"internal_port\": 25565,\n" +
                "      \"protocol\": \"tcp\",\n" +
                "      \"enabled\": false\n" +
                "    },\n" +
                "    {\n" +
                "      \"name\": \"web-direct\",\n" +
                "      \"public_port\": 20003,\n" +
                "      \"internal_port\": 8080,\n" +
                "      \"protocol\": \"tcp\",\n" +
                "      \"enabled\": false\n" +
                "    }\n" +
                "  ],\n" +
                "  \"minecraft_router\": {\n" +
                "    \"enabled\": false,\n" +
                "    \"listen_port\": 25000,\n" +
                "    \"default_port\": 25565,\n" +
                "    \"routes\": [\n" +
                "      { \"hostname\": \"mc1\", \"target_port\": 25565 },\n" +
                "      { \"hostname\": \"mc2\", \"target_port\": 25566 }\n" +
                "    ]\n" +
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

    public boolean isEnabled() {
        return enabled;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public boolean isSmartMultiplexerEnabled() {
        return smartMultiplexerEnabled;
    }

    public int getSmartMultiplexerPort() {
        return smartMultiplexerPort;
    }

    public List<MultiplexRule> getMultiplexRules() {
        return multiplexRules;
    }

    public List<Integer> getFallbackChain() {
        return fallbackChain;
    }

    public List<PortMapping> getMappings() {
        return mappings;
    }

    public boolean isMinecraftRouterEnabled() {
        return minecraftRouterEnabled;
    }

    public int getMinecraftRouterPort() {
        return minecraftRouterPort;
    }

    public Map<String, Integer> getMinecraftRoutes() {
        return minecraftRoutes;
    }

    public int getMinecraftDefaultPort() {
        return minecraftDefaultPort;
    }
}
