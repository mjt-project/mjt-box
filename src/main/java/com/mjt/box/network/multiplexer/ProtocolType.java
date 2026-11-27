package com.mjt.box.network.multiplexer;

/**
 * Các loại giao thức được hỗ trợ nhận diện tự động trên cùng một cổng.
 */
public enum ProtocolType {
    MINECRAFT("Minecraft Protocol"),
    HTTP("HTTP / WebSocket"),
    HTTPS_TLS("HTTPS / TLS"),
    SSH("SSH Protocol"),
    UNKNOWN("Không xác định (Fallback)");

    private final String description;

    ProtocolType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
