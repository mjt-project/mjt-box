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
 * Bộ quản lý mạng ảo MJT v4 (MJT Virtual Network Manager).
 *
 * Chức năng chính:
 *   1. Smart Multiplexer v4: Điều phối TCP + UDP theo cấu hình phân cấp:
 *      - Tầng 1 (service): ssh, sftp, ftp (chờ phản hồi 2 giây)
 *      - Tầng 2 (tcp.web_port): path routing + group + web_main + listen_port=auto
 *      - Tầng 3 (tcp other): Round-Robin + Mini AI (minecraft_pc, minecraft_pe...)
 *      - Luồng UDP: tự động mở cùng cổng khi mục "udp" có dịch vụ bật
 *   2. Port Forwarding & Minecraft Router (tương thích ngược).
 *   3. Hot-Reload: Tự động theo dõi network-config.json và cập nhật realtime.
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
        System.out.println(ConsoleColors.CYAN + "       MJT Virtual Network Manager v4             " + ConsoleColors.RESET);
        System.out.println(ConsoleColors.CYAN + "==================================================" + ConsoleColors.RESET);

        int successCount = 0;
        int failCount = 0;

        // ===== 1. KHỞI ĐỘNG MINI AI + SMART MULTIPLEXER v4 =====
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
                    config.getShellServices(),
                    config.getTcpWebServices(),
                    config.getTcpOtherServices(),
                    config.getUdpWebServices(),
                    config.getUdpOtherServices(),
                    config.getSshCheckTimeoutMs(),
                    config.getWebCheckTimeoutMs(),
                    miniAi
                );
                smartMultiplexer.setPublicEndpointOverride(config.getPublicHost(), config.getPublicPort());
                smartMultiplexer.start();
                successCount++;
            } catch (IOException e) {
                OutputLogger.error("Không thể khởi động Smart Multiplexer trên cổng "
                    + config.getSmartMultiplexerPort() + ": " + e.getMessage());
                failCount++;
            }
        }

        // ===== 2. PORT FORWARDING ĐỘC LẬP (NẾU CÓ) =====
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

        // ===== 3. MINECRAFT ROUTER RIÊNG BIỆT (NẾU BẬT Ở CONFIG CŨ) =====
        if (config.isMinecraftRouterEnabled()) {
            if (startMinecraftRouter(config)) {
                successCount++;
            } else {
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

    private boolean startMinecraftRouter(NetworkConfig cfg) {
        int muxPort = (smartMultiplexer != null && smartMultiplexer.isRunning())
                ? smartMultiplexer.getActualBoundPort() : -1;
        // Nếu SmartMultiplexer đã mở UDP trên cùng cổng, bỏ qua mở trùng UDP
        String effectiveType = cfg.getMinecraftRouterType();
        if (smartMultiplexer != null && smartMultiplexer.isUdpActive()
                && cfg.getMinecraftRouterPort() == smartMultiplexer.getActualBoundPort()
                && effectiveType.contains("udp")) {
            OutputLogger.info("MC Router: Luồng UDP trên cổng " + muxPort + " đã do SmartMultiplexer [udp] đảm nhiệm.");
            return true;
        }
        try {
            this.minecraftRouter = new MinecraftRouter(
                cfg.getMinecraftRouterPort(),
                cfg.getBindAddress(),
                effectiveType,
                cfg.getMinecraftRoutes(),
                cfg.getMinecraftUdpTargets(),
                cfg.getMinecraftDefaultPort(),
                muxPort
            );
            minecraftRouter.start();
            return true;
        } catch (IOException e) {
            OutputLogger.error("Không thể khởi động Minecraft Router ("
                + cfg.getMinecraftRouterType() + "): " + e.getMessage());
            this.minecraftRouter = null;
            return false;
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
                smartMultiplexer.setPublicEndpointOverride(newConfig.getPublicHost(), newConfig.getPublicPort());
                smartMultiplexer.updateEndpoints(
                    newConfig.getShellServices(),
                    newConfig.getTcpWebServices(),
                    newConfig.getTcpOtherServices(),
                    newConfig.getUdpWebServices(),
                    newConfig.getUdpOtherServices(),
                    newConfig.getSshCheckTimeoutMs(),
                    newConfig.getWebCheckTimeoutMs()
                );
            }

            if (newConfig.isMinecraftRouterEnabled()) {
                if (minecraftRouter != null && minecraftRouter.isRunning()) {
                    if (!newConfig.getMinecraftRouterType().equals(minecraftRouter.getRouterType())) {
                        minecraftRouter.stop();
                        minecraftRouter = null;
                        startMinecraftRouter(newConfig);
                    } else {
                        minecraftRouter.updateRouting(
                            newConfig.getMinecraftRoutes(),
                            newConfig.getMinecraftUdpTargets(),
                            newConfig.getMinecraftDefaultPort()
                        );
                    }
                } else {
                    startMinecraftRouter(newConfig);
                }
            } else if (minecraftRouter != null) {
                minecraftRouter.stop();
                minecraftRouter = null;
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
        if (hotReloadThread != null) hotReloadThread.interrupt();

        if (smartMultiplexer != null) {
            smartMultiplexer.stop();
            smartMultiplexer = null;
        }

        for (TcpForwarder tcp : tcpForwarders) tcp.stop();
        tcpForwarders.clear();

        for (UdpForwarder udp : udpForwarders) udp.stop();
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
