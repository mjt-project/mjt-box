package com.mjt.core.network;

import com.mjt.core.config.MjtPaths;
import com.mjt.core.ui.OutputLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Quản lý cấu hình mạng ảo MJT v4.
 *
 * Cấu trúc phân tầng rõ ràng:
 *   - smart_multiplexer: Cổng lắng nghe chung (TCP + UDP) & timeout kiểm tra
 *   - service: Tầng 1 — ssh, sftp, ftp (có ip + listen_port riêng)
 *   - ai_assistant: Mini AI Offline (100% miễn phí) + Online fallback tùy chọn
 *   - tcp: Tầng 2 (web_port với web_main/group/group_member/path/auto) + Tầng 3 (minecraft_pc, minecraft_pe, ...)
 *   - udp: Dịch vụ UDP (web_port QUIC, minecraft_pe 19132, ...) chạy song song trên cổng Multiplexer
 *
 * Vẫn giữ tương thích ngược với các cấu hình cũ (web_ports/shell_ports/other_ports, port_forwarding, minecraft_router).
 */
public class NetworkConfig {

    private static final String CONFIG_FILE = "network-config.json";

    // ===== Cấu hình chung =====
    private boolean enabled;
    private String bindAddress;
    private boolean autoReload;

    // ===== SMART MULTIPLEXER =====
    private boolean smartMultiplexerEnabled;
    private int smartMultiplexerPort;
    private String publicHost;
    private int publicPort;
    private int sshCheckTimeoutMs;
    private int webCheckTimeoutMs;

    // ===== NHÓM DỊCH VỤ PHÂN CẤP MỚI =====
    private List<ServiceEndpoint> shellServices;     // service.ssh, sftp, ftp...
    private List<ServiceEndpoint> tcpWebServices;    // tcp.web_port.*
    private List<ServiceEndpoint> tcpOtherServices;  // tcp.minecraft_pc, minecraft_pe...
    private List<ServiceEndpoint> udpWebServices;    // udp.web_port.*
    private List<ServiceEndpoint> udpOtherServices;  // udp.minecraft_pe, ...

    // ===== MINI AI ASSISTANT =====
    private boolean aiEnabled;
    private boolean aiOnlineEnabled;
    private String aiOnlineApiUrl;
    private String aiOnlineApiKey;
    private String aiOnlineModel;

    // ===== TƯƠNG THÍCH NGƯỢC =====
    private List<PortMapping> mappings;
    private boolean minecraftRouterEnabled;
    private String minecraftRouterType;
    private int minecraftRouterPort;
    private Map<String, Integer> minecraftRoutes;
    private List<Integer> minecraftUdpTargets;
    private int minecraftDefaultPort;

    public NetworkConfig() {
        this.enabled = false;
        this.bindAddress = "0.0.0.0";
        this.autoReload = true;

        this.smartMultiplexerEnabled = true;
        this.smartMultiplexerPort = 25565;
        this.publicHost = "auto";
        this.publicPort = -1;
        this.sshCheckTimeoutMs = 2000;
        this.webCheckTimeoutMs = 2500;

        this.shellServices = new ArrayList<>();
        this.tcpWebServices = new ArrayList<>();
        this.tcpOtherServices = new ArrayList<>();
        this.udpWebServices = new ArrayList<>();
        this.udpOtherServices = new ArrayList<>();

        this.aiEnabled = true;
        this.aiOnlineEnabled = false;
        this.aiOnlineApiUrl = "";
        this.aiOnlineApiKey = "";
        this.aiOnlineModel = "gpt-4o-mini";

        this.mappings = new ArrayList<>();
        this.minecraftRouterEnabled = false;
        this.minecraftRouterType = "tcp";
        this.minecraftRouterPort = 25000;
        this.minecraftRoutes = new LinkedHashMap<>();
        this.minecraftUdpTargets = new ArrayList<>();
        this.minecraftDefaultPort = 25565;
    }

    public static NetworkConfig load() {
        File configFile = MjtPaths.resolve(CONFIG_FILE);

        if (!configFile.exists()) {
            OutputLogger.warning("Không tìm thấy tệp cấu hình mạng. Đang khởi tạo " + CONFIG_FILE + "...");
            createDefaultConfig();
            return loadFromExistingFile(configFile);
        }

        return loadFromExistingFile(configFile);
    }

    private static NetworkConfig loadFromExistingFile(File configFile) {
        try {
            String json = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            return parse(json);
        } catch (Exception e) {
            OutputLogger.error("Lỗi đọc cấu hình mạng: " + e.getMessage());
            return new NetworkConfig();
        }
    }

    public static String getConfigFilePath() {
        return MjtPaths.resolve(CONFIG_FILE).getPath();
    }

    // ==========================================
    // PHÂN TÍCH JSON CÓ CẤU TRÚC
    // ==========================================

    private static NetworkConfig parse(String rawJson) {
        Map<String, Object> root = SimpleJsonParser.parseObject(rawJson);
        NetworkConfig config = new NetworkConfig();

        config.enabled = getBool(root, "enabled", config.enabled);
        config.bindAddress = getStr(root, "bind_address", config.bindAddress);
        config.autoReload = getBool(root, "auto_reload", config.autoReload);

        // ===== 1. Smart Multiplexer =====
        Map<String, Object> muxMap = getMap(root, "smart_multiplexer");
        if (muxMap != null) {
            config.smartMultiplexerEnabled = getBool(muxMap, "enabled", config.smartMultiplexerEnabled);
            config.smartMultiplexerPort = getPort(muxMap, "listen_port", config.smartMultiplexerPort);
            config.publicHost = getStr(muxMap, "public_host", config.publicHost);
            config.publicPort = isAutoValue(muxMap.get("public_port")) ? -1 : getPort(muxMap, "public_port", -1);
            config.sshCheckTimeoutMs = getInt(muxMap, "ssh_check_timeout_ms", config.sshCheckTimeoutMs);
            config.webCheckTimeoutMs = getInt(muxMap, "web_check_timeout_ms", config.webCheckTimeoutMs);

            // Tương thích ngược nếu còn mảng cổng trong smart_multiplexer
            addLegacyPorts(getIntList(muxMap, "shell_ports"), ServiceEndpoint.Category.SHELL, "tcp",
                    "legacy-shell", config.shellServices);
            addLegacyPorts(getIntList(muxMap, "web_ports"), ServiceEndpoint.Category.WEB, "tcp",
                    "legacy-web", config.tcpWebServices);
            addLegacyPorts(getIntList(muxMap, "minecraft_pe"), ServiceEndpoint.Category.OTHER, "tcp",
                    "minecraft_pe", config.tcpOtherServices);
            addLegacyPorts(getIntList(muxMap, "other_ports"), ServiceEndpoint.Category.OTHER, "tcp",
                    "legacy-other", config.tcpOtherServices);
        }

        // ===== 2. Service (ssh, sftp, ftp...) =====
        Map<String, Object> serviceMap = getMap(root, "service");
        if (serviceMap != null) {
            for (Map.Entry<String, Object> entry : serviceMap.entrySet()) {
                Map<String, Object> item = asMap(entry.getValue());
                if (item == null) continue;
                boolean en = getBool(item, "enabled", true);
                if (!en) continue;
                String ip = getStr(item, "ip", "127.0.0.1");
                int port = getPort(item, "listen_port", -1);
                if (port <= 0) port = getPort(item, "port", -1);
                if (port > 0) {
                    config.shellServices.add(new ServiceEndpoint(
                            entry.getKey(), ServiceEndpoint.Category.SHELL, true,
                            ip, port, false, "tcp", false, "", "", "/"));
                }
            }
        }

        // ===== 3. AI Assistant =====
        Map<String, Object> aiMap = getMap(root, "ai_assistant");
        if (aiMap != null) {
            config.aiEnabled = getBool(aiMap, "enabled", config.aiEnabled);
            config.aiOnlineEnabled = getBool(aiMap, "online_enabled", config.aiOnlineEnabled);
            config.aiOnlineApiUrl = getStr(aiMap, "online_api_url", config.aiOnlineApiUrl);
            config.aiOnlineApiKey = getStr(aiMap, "online_api_key", config.aiOnlineApiKey);
            config.aiOnlineModel = getStr(aiMap, "online_model", config.aiOnlineModel);
        }

        // ===== 4. TCP Section (web_port + minecraft_pc/pe + dịch vụ khác) =====
        Map<String, Object> tcpMap = getMap(root, "tcp");
        if (tcpMap != null) {
            parseTransportSection(tcpMap, "tcp", config.tcpWebServices,
                    config.tcpOtherServices, config.udpWebServices, config.udpOtherServices);
        }

        // ===== 5. UDP Section (web_port QUIC + minecraft_pe + dịch vụ UDP khác) =====
        Map<String, Object> udpMap = getMap(root, "udp");
        if (udpMap != null) {
            parseTransportSection(udpMap, "udp", config.udpWebServices,
                    config.udpOtherServices, config.tcpWebServices, config.tcpOtherServices);
        }

        // Giải quyết tham chiếu group / web_main / listen_port: "auto" cho Web TCP & UDP
        resolveWebGroups(config.tcpWebServices);
        resolveWebGroups(config.udpWebServices);

        // ===== 6. Legacy Port Forwarding =====
        List<Object> pfList = getList(root, "port_forwarding");
        if (pfList != null) {
            for (Object o : pfList) {
                Map<String, Object> m = asMap(o);
                if (m == null) continue;
                config.mappings.add(new PortMapping(
                        getStr(m, "name", "mapping"),
                        getInt(m, "public_port", 0),
                        getInt(m, "internal_port", 0),
                        getStr(m, "protocol", "tcp"),
                        getBool(m, "enabled", false)));
            }
        }

        // ===== 7. Legacy Minecraft Router =====
        Map<String, Object> mcMap = getMap(root, "minecraft_router");
        if (mcMap != null) {
            config.minecraftRouterEnabled = getBool(mcMap, "enabled", false);
            config.minecraftRouterType = getStr(mcMap, "type", "tcp").trim().toLowerCase(Locale.ROOT);
            config.minecraftRouterPort = getPort(mcMap, "listen_port", 25000);
            config.minecraftDefaultPort = getPort(mcMap, "default_port", 25565);

            List<Object> routes = getList(mcMap, "routes");
            if (routes != null) {
                for (Object o : routes) {
                    Map<String, Object> r = asMap(o);
                    if (r == null) continue;
                    String host = getStr(r, "hostname", "");
                    int targetPort = getInt(r, "target_port", -1);
                    String rType = getStr(r, "type", config.minecraftRouterType).trim().toLowerCase(Locale.ROOT);
                    if (targetPort <= 0) continue;
                    boolean isUdp = rType.contains("udp") || "both".equals(rType);
                    boolean isTcp = rType.contains("tcp") || "both".equals(rType) || rType.isEmpty();
                    if (isTcp && !host.isEmpty()) config.minecraftRoutes.put(host, targetPort);
                    if (isUdp && !config.minecraftUdpTargets.contains(targetPort)) {
                        config.minecraftUdpTargets.add(targetPort);
                    }
                }
            }
        }

        return config;
    }

    /**
     * Phân tích một khối "tcp" hoặc "udp" gồm web_port và các dịch vụ con tùy ý.
     */
    private static void parseTransportSection(Map<String, Object> section, String defaultTransport,
                                              List<ServiceEndpoint> primaryWeb, List<ServiceEndpoint> primaryOther,
                                              List<ServiceEndpoint> crossWeb, List<ServiceEndpoint> crossOther) {
        for (Map.Entry<String, Object> entry : section.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> valMap = asMap(entry.getValue());
            if (valMap == null) continue;

            if ("web_port".equalsIgnoreCase(key) || "web_ports".equalsIgnoreCase(key)) {
                for (Map.Entry<String, Object> webEntry : valMap.entrySet()) {
                    Map<String, Object> w = asMap(webEntry.getValue());
                    if (w == null) continue;
                    if (!getBool(w, "enabled", true)) continue;

                    String rawType = getStr(w, "type", defaultTransport).trim().toLowerCase(Locale.ROOT);
                    boolean portAuto = isAutoValue(w.get("listen_port"));
                    int port = portAuto ? -1 : getPort(w, "listen_port", -1);
                    String ip = getStr(w, "ip", "127.0.0.1");
                    boolean webMain = getBool(w, "web_main", false);
                    String group = getStr(w, "group", "");
                    String groupMember = formatGroupMember(w.get("group_member"));
                    String path = getStr(w, "path", "/");

                    ServiceEndpoint ep = new ServiceEndpoint(
                            webEntry.getKey(), ServiceEndpoint.Category.WEB, true,
                            ip, port, portAuto, defaultTransport, webMain, group, groupMember, path);
                    primaryWeb.add(ep);

                    if ("both".equals(rawType) || "tcp/udp".equals(rawType) || "tcp+udp".equals(rawType)) {
                        crossWeb.add(ep);
                    }
                }
            } else {
                // Bất kỳ dịch vụ nào khác trong tcp / udp (minecraft_pc, minecraft_pe, custom...)
                if (!getBool(valMap, "enabled", true)) continue;
                String rawType = getStr(valMap, "type", defaultTransport).trim().toLowerCase(Locale.ROOT);
                boolean portAuto = isAutoValue(valMap.get("listen_port"));
                int port = portAuto ? -1 : getPort(valMap, "listen_port", -1);
                if (port <= 0) port = getPort(valMap, "port", -1);
                String ip = getStr(valMap, "ip", "127.0.0.1");
                if (port <= 0 && !portAuto) continue;

                ServiceEndpoint ep = new ServiceEndpoint(
                        key, ServiceEndpoint.Category.OTHER, true,
                        ip, port, portAuto, defaultTransport, false, "", "", "/");
                primaryOther.add(ep);

                if ("both".equals(rawType) || "tcp/udp".equals(rawType) || "tcp+udp".equals(rawType)) {
                    crossOther.add(ep);
                }
            }
        }
    }

    /**
     * Giải quyết "listen_port": "auto" trong web_port dựa theo "group", "group_member", và "web_main".
     */
    private static void resolveWebGroups(List<ServiceEndpoint> webList) {
        if (webList == null || webList.isEmpty()) return;

        // Tìm web_main hoặc dịch vụ có cổng cụ thể cho mỗi group
        Map<String, ServiceEndpoint> groupAnchor = new LinkedHashMap<>();
        ServiceEndpoint globalMain = null;

        for (ServiceEndpoint ep : webList) {
            if (ep.getPort() > 0) {
                if (ep.isWebMain() && globalMain == null) {
                    globalMain = ep;
                }
                if (!ep.getGroup().isEmpty()) {
                    ServiceEndpoint existing = groupAnchor.get(ep.getGroup());
                    if (existing == null || (!existing.isWebMain() && ep.isWebMain())) {
                        groupAnchor.put(ep.getGroup(), ep);
                    }
                }
            }
        }

        for (ServiceEndpoint ep : webList) {
            if (ep.getPort() > 0) continue; // Đã có cổng cụ thể

            ServiceEndpoint anchor = !ep.getGroup().isEmpty() ? groupAnchor.get(ep.getGroup()) : null;
            if (anchor == null) anchor = globalMain;

            if (anchor != null && anchor.getPort() > 0) {
                // Kiểm tra group_member của anchor (auto / rỗng = chấp nhận toàn bộ thành viên cùng group)
                String gm = anchor.getGroupMember();
                boolean allowed = gm.isEmpty() || "auto".equalsIgnoreCase(gm)
                        || gm.toLowerCase(Locale.ROOT).contains(ep.getName().toLowerCase(Locale.ROOT));
                if (allowed) {
                    ep.setResolvedPort(anchor.getPort());
                }
            }
        }
    }

    private static void addLegacyPorts(List<Integer> ports, ServiceEndpoint.Category cat,
                                       String transport, String prefix, List<ServiceEndpoint> dest) {
        for (int p : ports) {
            if (p <= 0) continue;
            boolean exists = dest.stream().anyMatch(e -> e.getPort() == p && "127.0.0.1".equals(e.getIp()));
            if (!exists) {
                dest.add(new ServiceEndpoint(prefix + "-" + p, cat, true, "127.0.0.1",
                        p, false, transport, dest.isEmpty() && cat == ServiceEndpoint.Category.WEB,
                        "", "auto", "/"));
            }
        }
    }

    // ==========================================
    // TẠO CẤU HÌNH MẪU CHUẨN JSON
    // ==========================================

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
                "    \"web_check_timeout_ms\": 2500\n" +
                "  },\n" +
                "\n" +
                "  \"service\": {\n" +
                "    \"ssh\": {\n" +
                "      \"enabled\": true,\n" +
                "      \"ip\": \"127.0.0.1\",\n" +
                "      \"listen_port\": 22\n" +
                "    },\n" +
                "    \"sftp\": {\n" +
                "      \"enabled\": true,\n" +
                "      \"ip\": \"127.0.0.1\",\n" +
                "      \"listen_port\": 22\n" +
                "    },\n" +
                "    \"ftp\": {\n" +
                "      \"enabled\": false,\n" +
                "      \"ip\": \"127.0.0.1\",\n" +
                "      \"listen_port\": 21\n" +
                "    }\n" +
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
                "  \"tcp\": {\n" +
                "    \"web_port\": {\n" +
                "      \"web_home\": {\n" +
                "        \"enabled\": true,\n" +
                "        \"web_main\": true,\n" +
                "        \"group\": \"Example_1\",\n" +
                "        \"group_member\": \"auto\",\n" +
                "        \"type\": \"tcp\",\n" +
                "        \"ip\": \"127.0.0.1\",\n" +
                "        \"listen_port\": 8080,\n" +
                "        \"path\": \"/\"\n" +
                "      },\n" +
                "      \"web_about\": {\n" +
                "        \"enabled\": true,\n" +
                "        \"web_main\": false,\n" +
                "        \"group\": \"Example_1\",\n" +
                "        \"type\": \"tcp\",\n" +
                "        \"listen_port\": \"auto\",\n" +
                "        \"path\": \"/about\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"minecraft_pc\": {\n" +
                "      \"enabled\": true,\n" +
                "      \"type\": \"tcp\",\n" +
                "      \"ip\": \"127.0.0.1\",\n" +
                "      \"listen_port\": 25566\n" +
                "    },\n" +
                "    \"minecraft_pe\": {\n" +
                "      \"enabled\": true,\n" +
                "      \"type\": \"tcp\",\n" +
                "      \"ip\": \"127.0.0.1\",\n" +
                "      \"listen_port\": 19132\n" +
                "    }\n" +
                "  },\n" +
                "\n" +
                "  \"udp\": {\n" +
                "    \"web_port\": {},\n" +
                "    \"minecraft_pe\": {\n" +
                "      \"enabled\": true,\n" +
                "      \"type\": \"udp\",\n" +
                "      \"ip\": \"127.0.0.1\",\n" +
                "      \"listen_port\": 19132\n" +
                "    }\n" +
                "  }\n" +
                "}\n";

        File outFile = MjtPaths.resolve(CONFIG_FILE);
        if (outFile.getParentFile() != null) {
            outFile.getParentFile().mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(defaultJson.getBytes(StandardCharsets.UTF_8));
            OutputLogger.success("Đã tạo tệp cấu hình mạng mẫu: " + outFile.getPath());
        } catch (IOException e) {
            OutputLogger.error("Không thể ghi tệp cấu hình mạng: " + e.getMessage());
        }
    }

    // ==========================================
    // TIỆN ÍCH TRÍCH XUẤT JSON AN TOÀN
    // ==========================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        return (obj instanceof Map) ? (Map<String, Object>) obj : null;
    }

    private static Map<String, Object> getMap(Map<String, Object> map, String key) {
        return asMap(map.get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> getList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return (v instanceof List) ? (List<Object>) v : null;
    }

    private static List<Integer> getIntList(Map<String, Object> map, String key) {
        List<Integer> res = new ArrayList<>();
        List<Object> list = getList(map, key);
        if (list != null) {
            for (Object item : list) {
                if (item instanceof Number) {
                    res.add(((Number) item).intValue());
                } else if (item instanceof String) {
                    try { res.add(Integer.parseInt(((String) item).trim())); } catch (NumberFormatException ignored) {}
                }
            }
        }
        return res;
    }

    private static boolean getBool(Map<String, Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean(((String) v).trim());
        return fallback;
    }

    private static String getStr(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : fallback;
    }

    private static int getInt(Map<String, Object> map, String key, int fallback) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt(((String) v).trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static int getPort(Map<String, Object> map, String key, int fallback) {
        return getInt(map, key, fallback);
    }

    private static boolean isAutoValue(Object v) {
        return v instanceof String && "auto".equalsIgnoreCase(((String) v).trim());
    }

    @SuppressWarnings("unchecked")
    private static String formatGroupMember(Object v) {
        if (v == null) return "auto";
        if (v instanceof List) {
            List<String> parts = new ArrayList<>();
            for (Object o : (List<Object>) v) parts.add(String.valueOf(o));
            return String.join(",", parts);
        }
        return String.valueOf(v).trim();
    }

    // ==========================================
    // GETTERS
    // ==========================================

    public boolean isEnabled() { return enabled; }
    public String getBindAddress() { return bindAddress; }
    public boolean isAutoReload() { return autoReload; }

    public boolean isSmartMultiplexerEnabled() { return smartMultiplexerEnabled; }
    public int getSmartMultiplexerPort() { return smartMultiplexerPort; }
    public void setSmartMultiplexerPort(int smartMultiplexerPort) { this.smartMultiplexerPort = smartMultiplexerPort; }
    public String getPublicHost() { return publicHost; }
    public int getPublicPort() { return publicPort; }
    public int getSshCheckTimeoutMs() { return sshCheckTimeoutMs; }
    public int getWebCheckTimeoutMs() { return webCheckTimeoutMs; }

    public List<ServiceEndpoint> getShellServices() { return shellServices; }
    public List<ServiceEndpoint> getTcpWebServices() { return tcpWebServices; }
    public List<ServiceEndpoint> getTcpOtherServices() { return tcpOtherServices; }
    public List<ServiceEndpoint> getUdpWebServices() { return udpWebServices; }
    public List<ServiceEndpoint> getUdpOtherServices() { return udpOtherServices; }

    public boolean isAiEnabled() { return aiEnabled; }
    public boolean isAiOnlineEnabled() { return aiOnlineEnabled; }
    public String getAiOnlineApiUrl() { return aiOnlineApiUrl; }
    public String getAiOnlineApiKey() { return aiOnlineApiKey; }
    public String getAiOnlineModel() { return aiOnlineModel; }

    public List<PortMapping> getMappings() { return mappings; }
    public boolean isMinecraftRouterEnabled() { return minecraftRouterEnabled; }
    public String getMinecraftRouterType() { return minecraftRouterType; }
    public int getMinecraftRouterPort() { return minecraftRouterPort; }
    public void setMinecraftRouterPort(int minecraftRouterPort) { this.minecraftRouterPort = minecraftRouterPort; }
    public Map<String, Integer> getMinecraftRoutes() { return minecraftRoutes; }
    public List<Integer> getMinecraftUdpTargets() { return minecraftUdpTargets; }
    public int getMinecraftDefaultPort() { return minecraftDefaultPort; }
}
