package com.mjt.box.network;

/**
 * Đại diện cho một điểm đích dịch vụ nội bộ trong network-config.json
 * (dùng chung cho service [ssh/sftp/ftp], tcp [web_port/minecraft_pc/minecraft_pe/...], và udp).
 */
public class ServiceEndpoint {

    public enum Category {
        SHELL, // Tầng 1: ssh, sftp, ftp
        WEB,   // Tầng 2: web_port (HTTP/HTTPS/WS/QUIC)
        OTHER  // Tầng 3: minecraft_pc, minecraft_pe, custom TCP/UDP
    }

    private final String name;
    private final Category category;
    private final boolean enabled;
    private final String ip;
    private int port;
    private final boolean portAuto;
    private final String type; // "tcp", "udp", "both"

    // Thuộc tính riêng cho Web Route / Group
    private final boolean webMain;
    private final String group;
    private final String groupMember;
    private final String path;

    public ServiceEndpoint(String name, Category category, boolean enabled, String ip,
                           int port, boolean portAuto, String type,
                           boolean webMain, String group, String groupMember, String path) {
        this.name = name;
        this.category = category;
        this.enabled = enabled;
        this.ip = (ip == null || ip.trim().isEmpty() || "auto".equalsIgnoreCase(ip.trim()))
                ? "127.0.0.1" : ip.trim();
        this.port = port;
        this.portAuto = portAuto;
        this.type = (type == null || type.trim().isEmpty()) ? "tcp" : type.trim().toLowerCase();
        this.webMain = webMain;
        this.group = group != null ? group.trim() : "";
        this.groupMember = groupMember != null ? groupMember.trim() : "auto";
        this.path = (path == null || path.trim().isEmpty()) ? "/" : path.trim();
    }

    public String getName() { return name; }
    public Category getCategory() { return category; }
    public boolean isEnabled() { return enabled; }
    public String getIp() { return ip; }
    public int getPort() { return port; }
    public void setResolvedPort(int port) { this.port = port; }
    public boolean isPortAuto() { return portAuto; }
    public String getType() { return type; }

    public boolean isTcp() { return type.contains("tcp") || "both".equals(type); }
    public boolean isUdp() { return type.contains("udp") || "both".equals(type); }

    public boolean isWebMain() { return webMain; }
    public String getGroup() { return group; }
    public String getGroupMember() { return groupMember; }
    public String getPath() { return path; }

    public String getTargetKey() {
        return ip + ":" + port;
    }

    @Override
    public String toString() {
        return String.format("%s (%s:%s, type=%s%s)",
                name, ip, port > 0 ? String.valueOf(port) : "auto", type,
                category == Category.WEB ? ", path=" + path + (webMain ? ", main" : "") : "");
    }
}
