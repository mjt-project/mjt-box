package com.mjt.core.network;

import com.mjt.core.network.multiplexer.SmartMultiplexer;
import com.mjt.core.ui.ConsoleColors;
import com.mjt.core.ui.OutputLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bộ quản lý mạng ảo MJT (MJT Virtual Network Manager).
 *
 * Chức năng chính:
 *   1. Smart Multiplexer: Dồn kênh đa giao thức (Web, Minecraft, VS Code, SSH) trên 1 cổng duy nhất
 *      kèm cơ chế dò tìm và thử theo chuỗi ưu tiên (Priority Fallback Chain).
 *   2. Port Forwarding: Chuyển tiếp cổng TCP/UDP riêng biệt khi có nhiều cổng công khai.
 *   3. Minecraft Router: Định tuyến virtual host cho nhiều server Minecraft.
 */
public class VirtualNetworkManager {

    private final NetworkConfig config;
    private SmartMultiplexer smartMultiplexer;
    private final List<TcpForwarder> tcpForwarders = new ArrayList<>();
    private final List<UdpForwarder> udpForwarders = new ArrayList<>();
    private MinecraftRouter minecraftRouter;

    private boolean started = false;

    public VirtualNetworkManager(NetworkConfig config) {
        this.config = config;
    }

    /**
     * Khởi động toàn bộ hệ thống mạng ảo
     */
    public void start() {
        if (!config.isEnabled()) {
            OutputLogger.info("Mạng ảo MJT bị vô hiệu hóa (enabled=false trong network-config.json). Bỏ qua.");
            return;
        }

        System.out.println();
        System.out.println(ConsoleColors.CYAN + "==================================================" + ConsoleColors.RESET);
        System.out.println(ConsoleColors.CYAN + "       MJT Virtual Network Manager                " + ConsoleColors.RESET);
        System.out.println(ConsoleColors.CYAN + "==================================================" + ConsoleColors.RESET);

        int successCount = 0;
        int failCount = 0;

        // ===== 1. KHỞI ĐỘNG SMART MULTIPLEXER (1 CỔNG ĐA GIAO THỨC) =====
        if (config.isSmartMultiplexerEnabled()) {
            try {
                this.smartMultiplexer = new SmartMultiplexer(
                    "PrimaryMux",
                    config.getSmartMultiplexerPort(),
                    config.getBindAddress(),
                    config.getMultiplexRules(),
                    config.getFallbackChain()
                );
                smartMultiplexer.start();
                successCount++;
            } catch (IOException e) {
                OutputLogger.error("Không thể khởi động Smart Multiplexer trên cổng "
                    + config.getSmartMultiplexerPort() + ": " + e.getMessage());
                failCount++;
            }
        }

        // ===== 2. KHỞI ĐỘNG PORT FORWARDING RIÊNG BIỆT (NẾU CÓ) =====
        for (PortMapping mapping : config.getMappings()) {
            if (!mapping.isEnabled()) {
                OutputLogger.info("Bỏ qua mapping đã vô hiệu hóa: " + mapping.getName());
                continue;
            }

            if (mapping.isTcp()) {
                try {
                    TcpForwarder tcp = new TcpForwarder(
                        mapping.getName(),
                        mapping.getPublicPort(),
                        mapping.getInternalPort(),
                        config.getBindAddress()
                    );
                    tcp.start();
                    tcpForwarders.add(tcp);
                    successCount++;
                } catch (IOException e) {
                    OutputLogger.error("Không thể khởi động TCP [" + mapping.getName()
                        + "] trên cổng " + mapping.getPublicPort() + ": " + e.getMessage());
                    failCount++;
                }
            }

            if (mapping.isUdp()) {
                try {
                    UdpForwarder udp = new UdpForwarder(
                        mapping.getName(),
                        mapping.getPublicPort(),
                        mapping.getInternalPort(),
                        config.getBindAddress()
                    );
                    udp.start();
                    udpForwarders.add(udp);
                    successCount++;
                } catch (IOException e) {
                    OutputLogger.error("Không thể khởi động UDP [" + mapping.getName()
                        + "] trên cổng " + mapping.getPublicPort() + ": " + e.getMessage());
                    failCount++;
                }
            }
        }

        // ===== 3. KHỞI ĐỘNG MINECRAFT ROUTER ĐỘC LẬP (NẾU CÓ) =====
        if (config.isMinecraftRouterEnabled() && !config.getMinecraftRoutes().isEmpty()) {
            try {
                this.minecraftRouter = new MinecraftRouter(
                    config.getMinecraftRouterPort(),
                    config.getBindAddress(),
                    config.getMinecraftRoutes(),
                    config.getMinecraftDefaultPort()
                );
                minecraftRouter.start();
                successCount++;
            } catch (IOException e) {
                OutputLogger.error("Không thể khởi động Minecraft Router trên cổng "
                    + config.getMinecraftRouterPort() + ": " + e.getMessage());
                failCount++;
            }
        }

        // ===== BÁO CÁO KẾT QUẢ =====
        System.out.println(ConsoleColors.CYAN + "==================================================" + ConsoleColors.RESET);
        if (successCount > 0) {
            OutputLogger.success("Mạng ảo MJT đã sẵn sàng! Dịch vụ hoạt động: " + successCount
                + (failCount > 0 ? " | Thất bại: " + failCount : ""));
        } else if (failCount > 0) {
            OutputLogger.error("Không có dịch vụ mạng nào khởi động được!");
        } else {
            OutputLogger.warning("Không có dịch vụ mạng nào được kích hoạt.");
        }
        System.out.println();

        this.started = true;
    }

    /**
     * Dừng toàn bộ hệ thống mạng ảo và giải phóng tài nguyên
     */
    public void stop() {
        if (!started) return;

        OutputLogger.info("Đang dừng hệ thống mạng ảo MJT...");

        if (smartMultiplexer != null) {
            smartMultiplexer.stop();
            smartMultiplexer = null;
        }

        for (TcpForwarder tcp : tcpForwarders) {
            tcp.stop();
        }
        tcpForwarders.clear();

        for (UdpForwarder udp : udpForwarders) {
            udp.stop();
        }
        udpForwarders.clear();

        if (minecraftRouter != null) {
            minecraftRouter.stop();
            minecraftRouter = null;
        }

        OutputLogger.success("Mạng ảo MJT đã dừng hoàn toàn.");
        this.started = false;
    }

    public boolean isStarted() { return started; }
}
