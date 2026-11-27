package com.mjt.core.paper;

import com.mjt.core.ServerMain;
import com.mjt.core.config.ConfigManager;
import com.mjt.core.config.MjtPaths;
import com.mjt.core.config.StartupScriptManager;
import com.mjt.core.engine.ProotOrchestrator;
import com.mjt.core.network.NetworkConfig;
import com.mjt.core.network.VirtualNetworkManager;
import com.mjt.core.registry.DistroManager;
import com.mjt.core.registry.DistroManifest;
import com.mjt.core.ui.OutputLogger;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Entrypoint cho chế độ Minecraft Plugin (Paper / Spigot / Purpur / Folia).
 * Sử dụng JVM và Bukkit/Paper API có sẵn của máy chủ Minecraft — không đóng gói
 * thêm thư viện Java ngoài.
 */
public class MjtPaperPlugin extends JavaPlugin {

    private static final long RESTART_CONFIRM_TTL_MS = 60_000L;

    private VirtualNetworkManager networkManager;
    private NetworkConfig networkConfig;
    private ProotOrchestrator orchestrator;
    private Thread containerThread;
    private volatile boolean shuttingDown = false;
    private volatile String activeRootfs = null;
    private final Map<String, Long> pendingRestartRequests = new ConcurrentHashMap<>();

    @Override
    public void onLoad() {
        initPathsAndLogger();
        ConfigManager.loadConfig();
        StartupScriptManager.ensureStartupFile();
        OutputLogger.info("MJTCore v" + DistroManager.getMjtCoreVersion()
                + " đã nạp cấu hình tại: " + MjtPaths.getBaseDir().getAbsolutePath());
    }

    @Override
    public void onEnable() {
        this.shuttingDown = false;
        initPathsAndLogger();
        ConfigManager.loadConfig();
        StartupScriptManager.ensureStartupFile();

        PluginCommand mjtCmd = getCommand("mjt");
        if (mjtCmd != null) {
            mjtCmd.setExecutor(this);
            mjtCmd.setTabCompleter(this);
        }

        startVirtualNetwork();
        maybeAutoStartContainer();
    }

    @Override
    public void onDisable() {
        this.shuttingDown = true;

        if (orchestrator != null) {
            orchestrator.stopEnvironment();
        }
        if (containerThread != null && containerThread.isAlive()) {
            containerThread.interrupt();
            try {
                containerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (networkManager != null) {
            networkManager.stop();
            networkManager = null;
        }

        OutputLogger.setLogSink(null);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("mjt.admin")) {
            sender.sendMessage("[MJT] Bạn không có quyền sử dụng lệnh này (mjt.admin).");
            return true;
        }

        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sendStatus(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload":
                reloadPluginRuntime(sender);
                return true;
            case "setup":
            case "boot":
                String defaultId = DistroManager.getDefaultDistroId();
                String configuredId = ConfigManager.get("auto-boot-os", "");
                String distroArg = args.length > 1 ? args[1] : (configuredId.isEmpty() ? defaultId : configuredId);
                startContainerAsync(distroArg, sender);
                return true;
            case "restart":
            case "rerun":
                if (args.length > 1) {
                    if (StartupScriptManager.isAffirmative(args[1]) || "confirm".equalsIgnoreCase(args[1])) {
                        handleConfirmRestart(sender);
                        return true;
                    }
                    if (StartupScriptManager.isNegative(args[1])) {
                        handleCancelRestart(sender);
                        return true;
                    }
                }
                requestRestartConfirmation(sender);
                return true;
            case "confirm":
            case "yes":
            case "y":
                handleConfirmRestart(sender);
                return true;
            case "cancel":
            case "no":
            case "n":
                handleCancelRestart(sender);
                return true;
            case "exec":
                if (args.length < 2) {
                    sender.sendMessage("[MJT] Cú pháp: /mjt exec <lệnh shell>");
                    return true;
                }
                if (orchestrator == null || !orchestrator.isAlive()) {
                    sender.sendMessage("[MJT] Môi trường Linux (PRoot) chưa khởi chạy.");
                    return true;
                }
                String shellCmd = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                if (orchestrator.sendInput(shellCmd)) {
                    sender.sendMessage("[MJT] Đã gửi lệnh vào container: " + shellCmd);
                } else {
                    sender.sendMessage("[MJT] Không thể gửi lệnh vào stdin của PRoot.");
                }
                return true;
            case "log":
                sendRecentLogs(sender, args.length > 1 ? parsePositiveInt(args[1], 10) : 10);
                return true;
            case "stop-os":
                if (orchestrator != null && orchestrator.isAlive()) {
                    orchestrator.stopEnvironment();
                    sender.sendMessage("[MJT] Đã gửi tín hiệu dừng môi trường PRoot.");
                } else {
                    sender.sendMessage("[MJT] Không có phiên PRoot nào đang chạy.");
                }
                return true;
            default:
                sender.sendMessage(
                        "[MJT] Lệnh hỗ trợ: /mjt <status|reload|setup [distro]|restart|confirm|cancel|exec <cmd>|log [n]|stop-os>");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = Arrays.asList("status", "reload", "setup", "boot", "restart", "confirm", "cancel",
                    "exec", "log", "stop-os");
            List<String> matches = new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : subs) {
                if (s.startsWith(prefix)) {
                    matches.add(s);
                }
            }
            return matches;
        }
        if (args.length == 2 && ("setup".equalsIgnoreCase(args[0]) || "boot".equalsIgnoreCase(args[0]))) {
            List<String> matches = new ArrayList<>();
            String prefix = args[1].toLowerCase(Locale.ROOT);
            try {
                for (DistroManifest d : DistroManager.getAvailableDistros()) {
                    if (d.getId().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        matches.add(d.getId());
                    }
                }
            } catch (Exception ignored) {
            }
            return matches;
        }
        return Collections.emptyList();
    }

    private void initPathsAndLogger() {
        File dataFolder = getDataFolder();
        if (dataFolder != null) {
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            MjtPaths.init(dataFolder);
        }

        final Logger logger = getLogger();
        if (logger != null) {
            OutputLogger.setLogSink((level, msg) -> {
                if ("WARN".equals(level)) {
                    logger.warning(msg);
                } else if ("ERROR".equals(level)) {
                    logger.severe(msg);
                } else {
                    logger.info(msg);
                }
            });
        }
    }

    private void startVirtualNetwork() {
        if (networkManager != null && networkManager.isStarted()) {
            networkManager.stop();
        }
        this.networkConfig = NetworkConfig.load();
        protectPaperServerPort(this.networkConfig);
        this.networkManager = new VirtualNetworkManager(this.networkConfig);
        this.networkManager.start();
    }

    private void protectPaperServerPort(NetworkConfig cfg) {
        Server server = getServer();
        int paperPort = (server != null) ? server.getPort() : -1;
        if (paperPort <= 0) {
            return;
        }

        if (cfg.isSmartMultiplexerEnabled() && cfg.getSmartMultiplexerPort() == paperPort) {
            int safePort = paperPort + 1;
            cfg.setSmartMultiplexerPort(safePort);
            OutputLogger.warning("Phát hiện Paper sử dụng cổng server-port=" + paperPort
                    + ". MJT tự động chuyển SmartMultiplexer sang cổng " + safePort
                    + " để tránh xung đột với Paper (có thể đổi trong "
                    + MjtPaths.resolve("network-config.json").getPath() + ").");
        }

        if (cfg.isMinecraftRouterEnabled() && cfg.getMinecraftRouterPort() == paperPort) {
            int safeRouterPort = cfg.isSmartMultiplexerEnabled() ? cfg.getSmartMultiplexerPort() + 1 : paperPort + 1;
            cfg.setMinecraftRouterPort(safeRouterPort);
            OutputLogger.warning("Paper chiếm cổng " + paperPort
                    + "; Minecraft Router của MJT được chuyển sang cổng " + safeRouterPort + ".");
        }
    }

    private void maybeAutoStartContainer() {
        if (!isLinuxHost()) {
            OutputLogger.warning("Chế độ Plugin: Hệ điều hành host không phải Linux ("
                    + System.getProperty("os.name") + "), bỏ qua PRoot container và chỉ chạy Mạng ảo MJT.");
            return;
        }

        boolean hasProfile = MjtPaths.resolve(".mjt-profile").exists();
        String autoBoot = ConfigManager.get("auto-boot-os", "");
        if (!hasProfile && autoBoot.isEmpty()) {
            OutputLogger.info(
                    "Chế độ Plugin: Mạng ảo MJT đã bật. Chưa cấu hình auto-boot-os — dùng '/mjt setup <distroId>' hoặc đặt auto-boot-os trong mjt.conf nếu muốn chạy PRoot Linux nền.");
            return;
        }

        startContainerAsync(autoBoot.isEmpty() ? DistroManager.getDefaultDistroId() : autoBoot, null);
    }

    private synchronized void startContainerAsync(String distroId, CommandSender notifyTarget) {
        if (!isLinuxHost()) {
            String msg = "[MJT] PRoot Linux chỉ chạy được trên host Linux (hiện tại: " + System.getProperty("os.name")
                    + ").";
            if (notifyTarget != null)
                notifyTarget.sendMessage(msg);
            else
                OutputLogger.warning(msg);
            return;
        }

        if (containerThread != null && containerThread.isAlive()) {
            String msg = "[MJT] Tiến trình PRoot đang hoạt động sẵn rồi.";
            if (notifyTarget != null)
                notifyTarget.sendMessage(msg);
            return;
        }

        if (orchestrator == null) {
            orchestrator = new ProotOrchestrator();
        }

        final String fallbackDistro = (distroId != null && !distroId.trim().isEmpty())
                ? distroId.trim()
                : DistroManager.getDefaultDistroId();
        this.containerThread = new Thread(() -> {
            try {
                String[] env = ServerMain.ensureEnvironmentProvisioned(false, fallbackDistro);
                if (shuttingDown || !isEnabled()) {
                    return;
                }
                this.activeRootfs = env[0];
                orchestrator.bootEnvironment(env[0], env[1], false, line -> {
                    if (line.startsWith("[MJT-RESTART-PENDING]")) {
                        StartupScriptManager.consumeRestartMarker();
                        pendingRestartRequests.put("*", System.currentTimeMillis());
                        OutputLogger.warning("[MJT] Nhận yêu cầu chạy lại '" + StartupScriptManager.STARTUP_FILE
                                + "' từ trong container. Gõ '/mjt confirm' (Có) hoặc '/mjt cancel' (Không) để xác nhận!");
                    } else {
                        OutputLogger.info("[PRoot] " + line);
                    }
                });
            } catch (Exception e) {
                OutputLogger.error("Lỗi khởi chạy môi trường PRoot trong Plugin: " + e.getMessage());
            }
        }, "MJT-Paper-PRoot");
        this.containerThread.setDaemon(true);
        this.containerThread.start();

        if (notifyTarget != null) {
            notifyTarget.sendMessage("[MJT] Đang khởi tạo/khởi động PRoot trong luồng nền (" + fallbackDistro + ")...");
        }
    }

    private void requestRestartConfirmation(CommandSender sender) {
        StartupScriptManager.ensureStartupFile();
        String key = getSenderKey(sender);
        pendingRestartRequests.put(key, System.currentTimeMillis());
        int cmdCount = StartupScriptManager.countExecutableLines();
        sender.sendMessage("[MJT] XÁC NHẬN: Bạn muốn khởi động lại PRoot để chạy lại '"
                + StartupScriptManager.STARTUP_FILE + "' (" + cmdCount + " dòng lệnh) không?");
        sender.sendMessage("[MJT] Nhập '/mjt confirm' (Có) hoặc '/mjt cancel' (Không) trong vòng 60 giây.");
    }

    private void handleConfirmRestart(CommandSender sender) {
        String key = getSenderKey(sender);
        Long ts = pendingRestartRequests.remove(key);
        if (ts == null) {
            ts = pendingRestartRequests.remove("*");
        } else {
            pendingRestartRequests.remove("*");
        }

        if (ts == null || (System.currentTimeMillis() - ts) > RESTART_CONFIRM_TTL_MS) {
            sender.sendMessage(
                    "[MJT] Không có yêu cầu khởi động lại nào đang chờ xác nhận (hoặc đã hết hạn 60s). Hãy gõ '/mjt restart' trước.");
            return;
        }

        sender.sendMessage("[MJT] Đã xác nhận Có! Đang khởi động lại PRoot và chạy lại '"
                + StartupScriptManager.STARTUP_FILE + "'...");
        restartContainerAsync(sender);
    }

    private void handleCancelRestart(CommandSender sender) {
        String key = getSenderKey(sender);
        boolean removedSender = pendingRestartRequests.remove(key) != null;
        boolean removedGlobal = pendingRestartRequests.remove("*") != null;
        if (removedSender || removedGlobal) {
            sender.sendMessage("[MJT] Đã hủy yêu cầu khởi động lại PRoot.");
        } else {
            sender.sendMessage("[MJT] Không có yêu cầu khởi động lại nào đang chờ hủy.");
        }
    }

    private void restartContainerAsync(CommandSender sender) {
        if (!isLinuxHost()) {
            sender.sendMessage("[MJT] PRoot Linux chỉ chạy được trên host Linux (hiện tại: "
                    + System.getProperty("os.name") + ").");
            return;
        }

        Thread restartThread = new Thread(() -> {
            if (orchestrator != null && orchestrator.isAlive()) {
                orchestrator.stopEnvironment();
            }
            Thread prev = this.containerThread;
            if (prev != null && prev.isAlive()) {
                try {
                    prev.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (shuttingDown || !isEnabled()) {
                return;
            }
            ConfigManager.loadConfig();
            StartupScriptManager.normalizeAndRead();
            String configuredId = ConfigManager.get("auto-boot-os", "");
            String distro = configuredId.isEmpty() ? DistroManager.getDefaultDistroId() : configuredId;
            startContainerAsync(distro, sender);
        }, "MJT-Paper-Restart");
        restartThread.setDaemon(true);
        restartThread.start();
    }

    private String getSenderKey(CommandSender sender) {
        return (sender == null) ? "*" : sender.getClass().getName() + ":" + System.identityHashCode(sender);
    }

    private void reloadPluginRuntime(CommandSender sender) {
        ConfigManager.loadConfig();
        StartupScriptManager.normalizeAndRead();
        startVirtualNetwork();
        sender.sendMessage("[MJT] Đã nạp lại mjt.conf, " + StartupScriptManager.STARTUP_FILE
                + " và network-config.json (Mux port="
                + (networkManager != null ? networkManager.getActiveMultiplexerPort() : -1) + ").");
    }

    private void sendStatus(CommandSender sender) {
        String version = getDescription() != null ? getDescription().getVersion() : DistroManager.getMjtCoreVersion();
        int muxPort = networkManager != null ? networkManager.getActiveMultiplexerPort() : -1;
        boolean netStarted = networkManager != null && networkManager.isStarted();
        boolean udpActive = networkManager != null && networkManager.isMultiplexerUdpActive();
        boolean prootAlive = orchestrator != null && orchestrator.isAlive();
        int startupLines = StartupScriptManager.countExecutableLines();

        sender.sendMessage("=== MJT Core Plugin v" + version + " ===");
        sender.sendMessage("- Thư mục dữ liệu: " + MjtPaths.getBaseDir().getPath());
        sender.sendMessage("- Tệp khởi động: " + StartupScriptManager.getStartupFile().getPath()
                + " (" + startupLines + " dòng lệnh)");
        sender.sendMessage("- Mạng ảo MJT: " + (netStarted ? "Đang chạy" : "Tắt")
                + (muxPort > 0 ? " (Mux TCP:" + muxPort + (udpActive ? " + UDP" : "") + ")" : ""));
        sender.sendMessage("- PRoot Linux: " + (prootAlive ? "Đang chạy (" + activeRootfs + ")" : "Không hoạt động"));
    }

    private void sendRecentLogs(CommandSender sender, int limit) {
        if (orchestrator == null) {
            sender.sendMessage("[MJT] Chưa có log PRoot.");
            return;
        }
        List<String> logs = orchestrator.getRecentLogs();
        if (logs.isEmpty()) {
            sender.sendMessage("[MJT] Chưa có dòng output nào từ PRoot.");
            return;
        }
        int start = Math.max(0, logs.size() - limit);
        sender.sendMessage("=== PRoot Logs (" + (logs.size() - start) + " dòng gần nhất) ===");
        for (int i = start; i < logs.size(); i++) {
            sender.sendMessage(logs.get(i));
        }
    }

    private static boolean isLinuxHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static int parsePositiveInt(String text, int fallback) {
        try {
            int v = Integer.parseInt(text.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
