package com.mjt.core.network;

/**
 * Lớp dữ liệu đại diện cho một ánh xạ cổng ảo (Virtual Port Mapping).
 * Mỗi đối tượng mô tả việc chuyển tiếp từ cổng công khai → cổng nội bộ Linux.
 */
public class PortMapping {

    private final String name;
    private final int publicPort;
    private final int internalPort;
    private final String protocol; // "tcp", "udp", "both"
    private boolean enabled;

    public PortMapping(String name, int publicPort, int internalPort, String protocol, boolean enabled) {
        this.name = name;
        this.publicPort = publicPort;
        this.internalPort = internalPort;
        this.protocol = protocol.toLowerCase();
        this.enabled = enabled;
    }

    public String getName() { return name; }
    public int getPublicPort() { return publicPort; }
    public int getInternalPort() { return internalPort; }
    public String getProtocol() { return protocol; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isTcp() {
        return "tcp".equals(protocol) || "both".equals(protocol);
    }

    public boolean isUdp() {
        return "udp".equals(protocol) || "both".equals(protocol);
    }

    @Override
    public String toString() {
        return String.format("[%s] %s | :%d → localhost:%d (%s)",
            enabled ? "ON " : "OFF", name, publicPort, internalPort, protocol.toUpperCase());
    }
}
