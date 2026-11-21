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
 * Quản lý cấu hình mạng ảo (Virtual Network Configuration).
 * Đọc/ghi tệp network-config.json bằng Regex (Zero-Dependency).
 */
public class NetworkConfig {

    private static final String CONFIG_FILE = "network-config.json";

    // ===== Cấu hình Port Forwarding (Chuyển tiếp cổng) =====
    private boolean enabled;
    private String bindAddress;
    private List<PortMapping> mappings;

    // ===== Cấu hình Minecraft Router (Bộ định tuyến MC) =====
    private boolean minecraftRouterEnabled;
    private int minecraftRouterPort;
    private Map<String, Integer> minecraftRoutes; // hostname → target_port
    private int minecraftDefaultPort;

    public NetworkConfig() {
        this.enabled = false;
        this.bindAddress = "0.0.0.0";
        this.mappings = new ArrayList<>();
        this.minecraftRouterEnabled = false;
        this.minecraftRouterPort = 20002;
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

    private static NetworkConfig parse(String json) {
        NetworkConfig config = new NetworkConfig();

        // Parse trường enabled cấp cao nhất (trước section port_forwarding)
        int pfIndex = json.indexOf("\"port_forwarding\"");
        int mcIndex = json.indexOf("\"minecraft_router\"");

        // Tìm enabled ở đầu file (top-level)
        String topSection = pfIndex > 0 ? json.substring(0, pfIndex) : json;
        Matcher enabledMatcher = Pattern.compile("\"enabled\"\\s*:\\s*(true|false)").matcher(topSection);
        if (enabledMatcher.find()) {
            config.enabled = Boolean.parseBoolean(enabledMatcher.group(1));
        }

        // Parse bind_address
        Matcher bindMatcher = Pattern.compile("\"bind_address\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (bindMatcher.find()) {
            config.bindAddress = bindMatcher.group(1);
        }

        // ===== Parse Port Forwarding Mappings =====
        if (pfIndex >= 0) {
            String pfEnd;
            if (mcIndex > pfIndex) {
                pfEnd = json.substring(pfIndex, mcIndex);
            } else {
                pfEnd = json.substring(pfIndex);
            }

            String mappingRegex =
                "\"name\"\\s*:\\s*\"([^\"]+)\".*?" +
                "\"public_port\"\\s*:\\s*(\\d+).*?" +
                "\"internal_port\"\\s*:\\s*(\\d+).*?" +
                "\"protocol\"\\s*:\\s*\"([^\"]+)\".*?" +
                "\"enabled\"\\s*:\\s*(true|false)";

            Matcher mMatcher = Pattern.compile(mappingRegex, Pattern.DOTALL).matcher(pfEnd);
            while (mMatcher.find()) {
                config.mappings.add(new PortMapping(
                    mMatcher.group(1),
                    Integer.parseInt(mMatcher.group(2)),
                    Integer.parseInt(mMatcher.group(3)),
                    mMatcher.group(4),
                    Boolean.parseBoolean(mMatcher.group(5))
                ));
            }
        }

        // ===== Parse Minecraft Router =====
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

            // Parse routes
            String routeRegex = "\"hostname\"\\s*:\\s*\"([^\"]+)\".*?\"target_port\"\\s*:\\s*(\\d+)";
            Matcher rMatcher = Pattern.compile(routeRegex, Pattern.DOTALL).matcher(mcSection);
            while (rMatcher.find()) {
                config.minecraftRoutes.put(rMatcher.group(1), Integer.parseInt(rMatcher.group(2)));
            }
        }

        return config;
    }

    /**
     * Tạo tệp cấu hình mẫu với chú thích hướng dẫn
     */
    public static void createDefaultConfig() {
        String defaultJson =
            "{\n" +
            "  // =============================================\n" +
            "  // MJT VIRTUAL NETWORK MANAGER - CONFIGURATION\n" +
            "  // =============================================\n" +
            "\n" +
            "  \"enabled\": false,\n" +
            "  \"bind_address\": \"0.0.0.0\",\n" +
            "\n" +
            "  // ===== CHẾ ĐỘ 1: CHUYỂN TIẾP CỔNG (Port Forwarding) =====\n" +
            "  // Mỗi mapping tạo một cổng lắng nghe riêng, chuyển tiếp TCP/UDP\n" +
            "  // đến cổng nội bộ bên trong môi trường Linux.\n" +
            "  \"port_forwarding\": [\n" +
            "    {\n" +
            "      \"name\": \"minecraft-survival\",\n" +
            "      \"public_port\": 20002,\n" +
            "      \"internal_port\": 25565,\n" +
            "      \"protocol\": \"tcp\",\n" +
            "      \"enabled\": true\n" +
            "    },\n" +
            "    {\n" +
            "      \"name\": \"web-panel\",\n" +
            "      \"public_port\": 20003,\n" +
            "      \"internal_port\": 8080,\n" +
            "      \"protocol\": \"tcp\",\n" +
            "      \"enabled\": false\n" +
            "    }\n" +
            "  ],\n" +
            "\n" +
            "  // ===== CHẾ ĐỘ 2: ĐỊNH TUYẾN MINECRAFT (Minecraft Virtual Host Router) =====\n" +
            "  // Cho phép chạy NHIỀU server Minecraft trên MỘT cổng công khai duy nhất.\n" +
            "  // Hệ thống đọc gói Handshake của MC Protocol để xác định server đích.\n" +
            "  // Người chơi chỉ cần gõ: mc1.1.2.3.4:20002 hoặc mc2.1.2.3.4:20002\n" +
            "  \"minecraft_router\": {\n" +
            "    \"enabled\": false,\n" +
            "    \"listen_port\": 25000,\n" +
            "    \"default_port\": 25565,\n" +
            "    \"routes\": [\n" +
            "      { \"hostname\": \"mc1\", \"target_port\": 25565 },\n" +
            "      { \"hostname\": \"mc2\", \"target_port\": 25566 },\n" +
            "      { \"hostname\": \"creative\", \"target_port\": 25567 }\n" +
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

    public boolean isEnabled() { return enabled; }
    public String getBindAddress() { return bindAddress; }
    public List<PortMapping> getMappings() { return mappings; }
    public boolean isMinecraftRouterEnabled() { return minecraftRouterEnabled; }
    public int getMinecraftRouterPort() { return minecraftRouterPort; }
    public Map<String, Integer> getMinecraftRoutes() { return minecraftRoutes; }
    public int getMinecraftDefaultPort() { return minecraftDefaultPort; }
}
