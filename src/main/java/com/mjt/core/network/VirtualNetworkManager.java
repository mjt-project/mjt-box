package com.mjt.core.network;

import com.mjt.core.ui.ConsoleColors;
import com.mjt.core.ui.OutputLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bộ quản lý mạng ảo MJT (MJT Virtual Network Manager).
 *
 * Chức năng chính:
 *   1. Port Forwarding: Chuyển tiếp cổng TCP/UDP từ cổng công khai → cổng nội bộ Linux
 *   2. Minecraft Router: Định tuyến nhiều server MC trên 1 cổng duy nhất
 *
 * Kiến trúc:
 *   - Mỗi PortMapping tạo 1 TcpForwarder và/hoặc 1 UdpForwarder
 *   - MinecraftRouter là thành phần độc lập, lắng nghe trên cổng riêng
 *   - Tất cả chạy trên luồng nền (Daemon Threads) và dừng khi PRoot thoát
 */
public class VirtualNetworkManager {

    private final NetworkConfig config;
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
            OutputLogger.info("Mạng ảo MJT bị vô hiệu hóa (enabled=false). Bỏ qua khởi tạo.");
            return;
        }

        System.out.println();
        System.out.println(ConsoleColors.CYAN + "==================================================" + ConsoleColors.RESET);
        System.out.println(ConsoleColors.CYAN + "       MJT Virtual Network Manager                " + ConsoleColors.RESET);
        System.out.println(ConsoleColors.CYAN + "==================================================" + ConsoleColors.RESET);

        int successCount = 0;
        int failCount = 0;

        // ===== KHỞI ĐỘNG PORT FORWARDING =====
        for (PortMapping mapping : config.getMappings()) {
            if (!mapping.isEnabled()) {
                OutputLogger.info("Bỏ qua mapping đã vô hiệu hóa: " + mapping.getName());
                continue;
            }

            // TCP Forwarder
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

            // UDP Forwarder
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

        // ===== KHỞI ĐỘNG MINECRAFT ROUTER =====
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
            OutputLogger.success("Mạng ảo MJT đã sẵn sàng. Dịch vụ hoạt động: " + successCount
                + (failCount > 0 ? " | Thất bại: " + failCount : ""));
        } else if (failCount > 0) {
            OutputLogger.error("Không có dịch vụ mạng nào khởi động được!");
        } else {
            OutputLogger.warning("Không có mapping nào được cấu hình hoặc kích hoạt.");
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

    /**
     * Hiển thị trạng thái các dịch vụ mạng đang chạy
     */
    public void printStatus() {
        System.out.println("\n--- MJT Virtual Network Status ---");

        if (tcpForwarders.isEmpty() && udpForwarders.isEmpty() && minecraftRouter == null) {
            System.out.println("  Không có dịch vụ nào đang chạy.");
            return;
        }

        for (TcpForwarder tcp : tcpForwarders) {
            System.out.printf("  [TCP] %s | Trạng thái: %s | Kết nối: %d%n",
                tcp.getName(),
                tcp.isRunning() ? ConsoleColors.GREEN + "RUNNING" + ConsoleColors.RESET : "STOPPED",
                tcp.getActiveConnections());
        }

        for (UdpForwarder udp : udpForwarders) {
            System.out.printf("  [UDP] %s | Trạng thái: %s%n",
                udp.getName(),
                udp.isRunning() ? ConsoleColors.GREEN + "RUNNING" + ConsoleColors.RESET : "STOPPED");
        }

        if (minecraftRouter != null) {
            System.out.printf("  [MC]  Minecraft Router | Trạng thái: %s | Kết nối: %d%n",
                minecraftRouter.isRunning() ? ConsoleColors.GREEN + "RUNNING" + ConsoleColors.RESET : "STOPPED",
                minecraftRouter.getActiveConnections());
        }

        System.out.println("----------------------------------\n");
    }

    public boolean isStarted() { return started; }
}
