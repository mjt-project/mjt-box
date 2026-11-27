package com.mjt.core.network.multiplexer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quy tắc nhận diện và định tuyến giao thức (Multiplex Rule).
 * Xác định khi giao thức khớp thì chuyển tiếp đến cổng nội bộ nào,
 * kèm theo danh sách định tuyến phụ dựa trên tên miền (Host Routing) hoặc đường dẫn (Path Routing).
 */
public class MultiplexRule {

    private final String name;
    private final ProtocolType protocol;
    private final int defaultTargetPort;
    private final boolean enabled;
    private final Map<String, Integer> hostRoutes; // "web.domain.com" hoặc "mc1" -> target_port
    private final Map<String, Integer> pathRoutes; // "/code" hoặc "/admin" -> target_port

    public MultiplexRule(String name, ProtocolType protocol, int defaultTargetPort, boolean enabled) {
        this.name = name;
        this.protocol = protocol;
        this.defaultTargetPort = defaultTargetPort;
        this.enabled = enabled;
        this.hostRoutes = new LinkedHashMap<>();
        this.pathRoutes = new LinkedHashMap<>();
    }

    public void addHostRoute(String host, int targetPort) {
        this.hostRoutes.put(host.toLowerCase(), targetPort);
    }

    public void addPathRoute(String pathPrefix, int targetPort) {
        this.pathRoutes.put(pathPrefix.toLowerCase(), targetPort);
    }

    public String getName() { return name; }
    public ProtocolType getProtocol() { return protocol; }
    public int getDefaultTargetPort() { return defaultTargetPort; }
    public boolean isEnabled() { return enabled; }
    public Map<String, Integer> getHostRoutes() { return hostRoutes; }
    public Map<String, Integer> getPathRoutes() { return pathRoutes; }

    /**
     * Xác định cổng đích cụ thể dựa trên host hoặc path (nếu có cấu hình phụ)
     */
    public int resolveTargetPort(String host, String path) {
        // 1. Kiểm tra host routing (Virtual Host)
        if (host != null && !hostRoutes.isEmpty()) {
            String lowerHost = host.toLowerCase();
            for (Map.Entry<String, Integer> entry : hostRoutes.entrySet()) {
                if (lowerHost.equals(entry.getKey()) || lowerHost.startsWith(entry.getKey() + ".")) {
                    return entry.getValue();
                }
            }
        }

        // 2. Kiểm tra path routing (Chỉ áp dụng cho HTTP)
        if (path != null && !pathRoutes.isEmpty()) {
            String lowerPath = path.toLowerCase();
            for (Map.Entry<String, Integer> entry : pathRoutes.entrySet()) {
                if (lowerPath.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        return defaultTargetPort;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s (%s) → localhost:%d",
            enabled ? "ON" : "OFF", name, protocol.name(), defaultTargetPort);
    }
}
