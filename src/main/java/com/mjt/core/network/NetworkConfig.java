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
 * Quản lý cấu hình mạng ảo MJT v2.
 *
 * Cấu trúc config mới (network-config.json):
 *   {
 *     "enabled": true,
 *     "bind_address": "0.0.0.0",
 *     "auto_reload": true,
 *     "smart_multiplexer": {
 *       "enabled": true,
 *       "listen_port": 25565,
 *       "web_ports": [8080, 8081, 8443],
 *       "shell_ports": [2222],
 *       "other_ports": [25566, 25567]
 *     },
 *     "port_forwarding": [],
 *     "minecraft_router": { ... }
 *   }
 *
 * Hot-Reload: Tự động theo dõi và cập nhật config khi file thay đổi.
 */
public class NetworkConfig {

    private static final String CONFIG_FILE = "./network-config.json";

    // ===== Cấu hình chung =====
    private boolean enabled;
    private String bindAddress;
    private boolean autoReload;

    // ===== SMART MULTIPLEXER v2: 3 nhóm cổng tách biệt =====
    private boolean smartMultiplexerEnabled;
    private int smartMultiplexerPort;
    private List<Integer> webPorts;    // HTTP, HTTPS, WebSocket
    private List<Integer> shellPorts;  // SSH, SFTP, FTP
    private List<Integer> otherPorts;  // Minecraft, game, dịch vụ khác

    // ===== PORT FORWARDING (giữ tương thích cũ) =====
    private List<PortMapping> mappings;

    // ===== MINECRAFT ROUTER (giữ tương thích cũ) =====
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
        this.webPorts = new ArrayList<>();
        this.shellPorts = new ArrayList<>();
        this.otherPorts = new ArrayList<>();

        this.mappings = new ArrayList<>();

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
            String json = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            return parse(json);
        } catch (Exception e) {
            OutputLogger.error("Lỗi đọc cấu hình mạng: " + e.getMessage());
            return new NetworkConfig();
        }
    }

    /**
     * Lấy đường dẫn file config (để hot-reload kiểm tra lastModified)
     */
    public static String getConfigFilePath() {
        return CONFIG_FILE;
    }

    // ==========================================
    // PHÂN TÍCH JSON BẰNG REGEX (Zero-Dependency)
    // ==========================================

    private static NetworkConfig parse(String rawJson) {
        // Loại bỏ comment
        String json = rawJson.replaceAll("(?m)^\\s*//.*$", "")
                .replaceAll("/\\*.*?\\*/", "");

        NetworkConfig config = new NetworkConfig();

        int muxIndex = json.indexOf("\"smart_multiplexer\"");
        int pfIndex = json.indexOf("\"port_forwarding\"");
        int mcIndex = json.indexOf("\"minecraft_router\"");

        // Tìm enabled, bind_address, auto_reload ở đầu file (top-level)
        int firstSection = json.length();
        if (muxIndex > 0) firstSection = Math.min(firstSection, muxIndex);
        if (pfIndex > 0) firstSection = Math.min(firstSection, pfIndex);
        if (mcIndex > 0) firstSection = Math.min(firstSection, mcIndex);

        String topSection = json.substring(0, firstSection);

        Matcher enabledMatcher = Pattern.compile("\"enabled\"\\s*:\\s*(true|false)").matcher(topSection);
        if (enabledMatcher.find()) {
            config.enabled = Boolean.parseBoolean(enabledMatcher.group(1));
        }

        Matcher bindMatcher = Pattern.compile("\"bind_address\"\\s*:\\s*\"([^\"]+)\"").matcher(topSection);
        if (bindMatcher.find()) {
            config.bindAddress = bindMatcher.group(1);
        }

        Matcher reloadMatcher = Pattern.compile("\"auto_reload\"\\s*:\\s*(true|false)").matcher(topSection);
        if (reloadMatcher.find()) {
            config.autoReload = Boolean.parseBoolean(reloadMatcher.group(1));
        }

        // ===== 1. Parse Smart Multiplexer v2 =====
        if (muxIndex >= 0) {
            int muxEnd = json.length();
            if (pfIndex > muxIndex) muxEnd = Math.min(muxEnd, pfIndex);
            if (mcIndex > muxIndex) muxEnd = Math.min(muxEnd, mcIndex);
            String muxSection = json.substring(muxIndex, muxEnd);

            Matcher muxEn = Pattern.compile("\"enabled\"\\s*:\\s*(true|false)").matcher(muxSection);
            if (muxEn.find()) {
                config.smartMultiplexerEnabled = Boolean.parseBoolean(muxEn.group(1));
            }

            Matcher muxPort = Pattern.compile("\"listen_port\"\\s*:\\s*(\\d+)").matcher(muxSection);
            if (muxPort.find()) {
                config.smartMultiplexerPort = Integer.parseInt(muxPort.group(1));
            }

            // Parse web_ports array
            config.webPorts = parseIntArray(muxSection, "web_ports");

            // Parse shell_ports array
            config.shellPorts = parseIntArray(muxSection, "shell_ports");

            // Parse other_ports array
            config.otherPorts = parseIntArray(muxSection, "other_ports");

            // Tương thích ngược: nếu không có web_ports/shell_ports/other_ports,
            // thử đọc priority_rules kiểu cũ và chuyển đổi sang format mới
            if (config.webPorts.isEmpty() && config.shellPorts.isEmpty()
                    && config.otherPorts.isEmpty()) {
                parseLegacyRules(muxSection, config);
            }
        }

        // ===== 2. Parse Port Forwarding Mappings =====
        if (pfIndex >= 0) {
            int pfEnd = json.length();
            if (mcIndex > pfIndex) pfEnd = Math.min(pfEnd, mcIndex);
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
     * Parse một mảng JSON chứa số nguyên theo tên field.
     * Ví dụ: "web_ports": [8080, 8081]  → List<Integer>{8080, 8081}
     */
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

    /**
     * Tương thích ngược: Chuyển đổi priority_rules kiểu cũ sang 3 nhóm cổng mới
     */
    private static void parseLegacyRules(String muxSection, NetworkConfig config) {
        String ruleRegex = "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*?" +
                "\"protocol\"\\s*:\\s*\"([^\"]+)\"[^}]*?" +
                "\"target_port\"\\s*:\\s*(\\d+)[^}]*?" +
                "\"enabled\"\\s*:\\s*(true|false)[^}]*\\}";
        Matcher ruleMatcher = Pattern.compile(ruleRegex, Pattern.DOTALL).matcher(muxSection);

        while (ruleMatcher.find()) {
            String proto = ruleMatcher.group(2).trim().toUpperCase();
            int port = Integer.parseInt(ruleMatcher.group(3));
            boolean enabled = Boolean.parseBoolean(ruleMatcher.group(4));

            if (!enabled) continue;

            if (proto.contains("HTTP") || proto.contains("WEB") || proto.contains("TLS")
                    || proto.contains("HTTPS")) {
                if (!config.webPorts.contains(port)) {
                    config.webPorts.add(port);
                }
            } else if (proto.contains("SSH") || proto.contains("SFTP") || proto.contains("FTP")) {
                if (!config.shellPorts.contains(port)) {
                    config.shellPorts.add(port);
                }
            } else {
                // MINECRAFT, UNKNOWN, hoặc bất kỳ
                if (!config.otherPorts.contains(port)) {
                    config.otherPorts.add(port);
                }
            }
        }

        // Fallback chain cũ → thêm vào other_ports
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

    /**
     * Tạo tệp cấu hình mẫu chuẩn (format v2)
     */
    public static void createDefaultConfig() {
        String defaultJson = "{\n" +
                "  \"enabled\": true,\n" +
                "  \"bind_address\": \"0.0.0.0\",\n" +
                "  \"auto_reload\": true,\n" +
                "\n" +
                "  \"smart_multiplexer\": {\n" +
                "    \"enabled\": true,\n" +
                "    \"listen_port\": 25565,\n" +
                "\n" +
                "    \"web_ports\": [8080, 8081],\n" +
                "    \"shell_ports\": [2222],\n" +
                "    \"other_ports\": [25566, 25567]\n" +
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
    public List<Integer> getWebPorts() { return webPorts; }
    public List<Integer> getShellPorts() { return shellPorts; }
    public List<Integer> getOtherPorts() { return otherPorts; }

    public List<PortMapping> getMappings() { return mappings; }

    public boolean isMinecraftRouterEnabled() { return minecraftRouterEnabled; }
    public int getMinecraftRouterPort() { return minecraftRouterPort; }
    public Map<String, Integer> getMinecraftRoutes() { return minecraftRoutes; }
    public int getMinecraftDefaultPort() { return minecraftDefaultPort; }
}
