package com.mjt.core.network;

import com.mjt.core.network.multiplexer.MiniAiEngine;
import com.mjt.core.network.multiplexer.SmartMultiplexer;
import com.mjt.core.ui.ConsoleColors;
import com.mjt.core.ui.OutputLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bộ quản lý mạng ảo MJT v3 (MJT Virtual Network Manager).
 *
 * Chức năng chính:
 *   1. Smart Multiplexer v3: Kiểm tra xác minh chủ động từng tầng theo mỗi session
 *      (SSH 2s → HTTP/HTTPS → Other Round-Robin + Mini AI Offline/Online).
 *   2. Port Forwarding: Chuyển tiếp cổng TCP/UDP riêng biệt.
 *   3. Minecraft Router: Định tuyến virtual host.
 *   4. Hot-Reload: Tự động theo dõi network-config.json và cập nhật realtime.
 */
public class VirtualNetworkManager {

    private NetworkConfig config;
    private MiniAiEngine miniAi;
    private SmartMultiplexer smartMultiplexer;
    private final List<TcpForwarder> tcpForwarders = new ArrayList<>();
    private final List<UdpForwarder> udpForwarders = new ArrayList<>();
    private MinecraftRouter minecraftRouter;

    private volatile boolean started = false;
    private Thread hotReloadThread;
    private volatile boolean hotReloadRunning = false;

    public VirtualNetworkManager(NetworkConfig config) {
        this.config = config;
    }

    public void start() {
        if (!config.isEnabled()) {
            OutputLogger.info("Mạng ảo MJT bị vô hiệu hóa (enabled=false trong network-config.json). Bỏ qua.");
            return;
        }

        System.out.println();
        System.out.println(ConsoleColors.CYAN + "==================================================" + ConsoleColors.RESET);
        System.out.println(ConsoleColors.CYAN + "       MJT Virtual Network Manager v3             " + ConsoleColors.RESET);
        System.out.println(ConsoleColors.CYAN + "==================================================" + ConsoleColors.RESET);

        int successCount = 0;
        int failCount = 0;

        // ===== 1. KHỞI ĐỘNG MINI AI + SMART MULTIPLEXER v3 =====
        if (config.isSmartMultiplexerEnabled()) {
            this.miniAi = new MiniAiEngine(
                config.isAiEnabled(),
                config.isAiOnlineEnabled(),
                config.getAiOnlineApiUrl(),
                config.getAiOnlineApiKey(),
                config.getAiOnlineModel()
            );

            OutputLogger.info("Mini AI Engine: " + (config.isAiEnabled()
                ? (config.isAiOnlineEnabled() ? "Offline + Online Fallback" : "Offline 100% (Zero-Dependency)")
                : "Đã tắt"));

            try {
                this.smartMultiplexer = new SmartMultiplexer(
                    "PrimaryMux",
                    config.getSmartMultiplexerPort(),
                    config.getBindAddress(),
                    config.getWebPorts(),
                    config.getShellPorts(),
                    config.getOtherPorts(),
                    config.getSshCheckTimeoutMs(),
                    config.getWebCheckTimeoutMs(),
                    miniAi
                );
                smartMultiplexer.start();
                successCount++;
            } catch (IOException e) {
                OutputLogger.error("Không thể khởi động Smart Multiplexer trên cổng "
                    + config.getSmartMultiplexerPort() + ": " + e.getMessage());
                failCount++;
            }
        }

        // ===== 2. KHỞI ĐỘNG PORT FORWARDING (NẾU CÓ) =====
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

        if (config.isAutoReload()) {
            startHotReload();
        }
    }

    private void startHotReload() {
        final File configFile = new File(NetworkConfig.getConfigFilePath());
        if (!configFile.exists()) return;

        this.hotReloadRunning = true;

        this.hotReloadThread = new Thread(() -> {
            long lastModified = configFile.lastModified();
            long lastSize = configFile.length();

            OutputLogger.info("Hot-Reload: Đang theo dõi " + configFile.getName()
                + " (cập nhật tự động mỗi 3 giây)");

            while (hotReloadRunning) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }

                long currentModified = configFile.lastModified();
                long currentSize = configFile.length();

                if (currentModified != lastModified || currentSize != lastSize) {
                    lastModified = currentModified;
                    lastSize = currentSize;

                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        break;
                    }

                    reloadConfig();
                }
            }
        }, "MJT-HotReload");

        hotReloadThread.setDaemon(true);
        hotReloadThread.start();
    }

    private void reloadConfig() {
        try {
            NetworkConfig newConfig = NetworkConfig.load();

            if (miniAi != null) {
                miniAi.updateConfig(
                    newConfig.isAiEnabled(),
                    newConfig.isAiOnlineEnabled(),
                    newConfig.getAiOnlineApiUrl(),
                    newConfig.getAiOnlineApiKey(),
                    newConfig.getAiOnlineModel()
                );
            }

            if (smartMultiplexer != null && smartMultiplexer.isRunning()) {
                smartMultiplexer.updateConfig(
                    newConfig.getWebPorts(),
                    newConfig.getShellPorts(),
                    newConfig.getOtherPorts(),
                    newConfig.getSshCheckTimeoutMs(),
                    newConfig.getWebCheckTimeoutMs()
                );
            }

            this.config = newConfig;
        } catch (Exception e) {
            OutputLogger.error("Hot-Reload: Lỗi đọc config mới: " + e.getMessage());
        }
    }

    public void stop() {
        if (!started) return;

        OutputLogger.info("Đang dừng hệ thống mạng ảo MJT...");

        hotReloadRunning = false;
        if (hotReloadThread != null) {
            hotReloadThread.interrupt();
        }

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
