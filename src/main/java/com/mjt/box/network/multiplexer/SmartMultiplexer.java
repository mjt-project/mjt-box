package com.mjt.box.network.multiplexer;

import com.mjt.box.network.ServiceEndpoint;
import com.mjt.box.ui.OutputLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Smart Port Multiplexer v4 — Bộ điều phối TCP + UDP phân tầng theo Session & Nhóm dịch vụ.
 *
 * Kiến trúc:
 *   - TCP Listener (ServerSocket):
 *       Tầng 1: service (ssh, sftp, ftp) — chờ phản hồi tối đa 2 giây
 *       Tầng 2: tcp.web_port (khớp path dài nhất, group + web_main + listen_port=auto)
 *       Tầng 3: tcp Other (minecraft_pc, minecraft_pe, ...) — Round-Robin + Mini AI
 *   - UDP Listener (DatagramSocket trên cùng cổng nếu có cấu hình udp):
 *       Định tuyến udp.minecraft_pe (RakNet / NetherNet STUN/ICE, DTLS), udp.web_port (QUIC), v.v.
 */
public class SmartMultiplexer {

    private final String name;
    private final int listenPort;
    private final String bindAddress;

    // ===== CẤU HÌNH DỊCH VỤ PHÂN TẦNG (volatile cho Hot-Reload) =====
    private volatile List<ServiceEndpoint> shellServices;
    private volatile List<ServiceEndpoint> tcpWebServices;
    private volatile List<ServiceEndpoint> tcpOtherServices;
    private volatile List<ServiceEndpoint> udpWebServices;
    private volatile List<ServiceEndpoint> udpOtherServices;

    private volatile int sshCheckTimeoutMs;
    private volatile int webCheckTimeoutMs;

    private final MiniAiEngine miniAi;

    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private final AtomicInteger udpRoundRobinCounter = new AtomicInteger(0);
    private final AtomicLong sessionCounter = new AtomicLong(0);
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    private volatile String configuredPublicHost = "auto";
    private volatile int configuredPublicPort = -1;
    private volatile ServiceEndpoint dynamicMcpeUdpEndpoint = null;
    private volatile long dynamicMcpeUdpExpiresAtMs = 0L;

    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;
    private final Map<String, UdpRelaySession> udpSessions = new ConcurrentHashMap<>();
    private final Set<String> warnedSelfLoops = ConcurrentHashMap.newKeySet();

    private ExecutorService threadPool;
    private volatile boolean running;

    private int actualBoundPort = -1;
    private String actualBoundAddress = null;
    private boolean udpActive = false;

    private static final int BUFFER_SIZE = 16384;
    private static final int UDP_BUFFER_SIZE = 65535;
    private static final int PEEK_TIMEOUT_MS = 1500;
    private static final int FRAGMENT_WAIT_MS = 100;
    private static final int PROBE_CONNECT_TIMEOUT_MS = 400;
    private static final int OTHER_VERIFY_TIMEOUT_MS = 1500;
    private static final int OTHER_MCPE_VERIFY_TIMEOUT_MS = 6000;
    private static final long UDP_SESSION_TIMEOUT_MS = 60_000L;
    private static final long DYNAMIC_MCPE_UDP_TTL_MS = 300_000L;

    private static class VerifiedRoute {
        final ServiceEndpoint endpoint;
        final Socket backendSocket;
        final byte[] backendReply;
        final String stageLabel;
        final String aiLabel;

        VerifiedRoute(ServiceEndpoint endpoint, Socket backendSocket, byte[] backendReply,
                      String stageLabel, String aiLabel) {
            this.endpoint = endpoint;
            this.backendSocket = backendSocket;
            this.backendReply = backendReply != null ? backendReply : new byte[0];
            this.stageLabel = stageLabel;
            this.aiLabel = aiLabel;
        }
    }

    private static class ProbeReadResult {
        final byte[] data;
        final boolean eof;
        final boolean timedOut;

        ProbeReadResult(byte[] data, boolean eof, boolean timedOut) {
            this.data = data != null ? data : new byte[0];
            this.eof = eof;
            this.timedOut = timedOut;
        }
    }

    private static class WebFallbackCandidate {
        ServiceEndpoint softNotFoundEndpoint = null;
        ServiceEndpoint slowAliveEndpoint = null;
    }

    private static class UdpRelaySession {
        final DatagramSocket relaySocket;
        final InetAddress clientAddress;
        final int clientPort;
        final ServiceEndpoint target;
        volatile long lastActivity;

        UdpRelaySession(DatagramSocket relaySocket, InetAddress clientAddress,
                        int clientPort, ServiceEndpoint target) {
            this.relaySocket = relaySocket;
            this.clientAddress = clientAddress;
            this.clientPort = clientPort;
            this.target = target;
            this.lastActivity = System.currentTimeMillis();
        }

        void touch() { this.lastActivity = System.currentTimeMillis(); }
        void close() { if (!relaySocket.isClosed()) relaySocket.close(); }
    }

    public SmartMultiplexer(String name, int listenPort, String bindAddress,
                            List<ServiceEndpoint> shellServices,
                            List<ServiceEndpoint> tcpWebServices,
                            List<ServiceEndpoint> tcpOtherServices,
                            List<ServiceEndpoint> udpWebServices,
                            List<ServiceEndpoint> udpOtherServices,
                            int sshCheckTimeoutMs, int webCheckTimeoutMs,
                            MiniAiEngine miniAi) {
        this.name = name;
        this.listenPort = listenPort;
        this.bindAddress = bindAddress;
        this.shellServices = copyList(shellServices);
        this.tcpWebServices = copyList(tcpWebServices);
        this.tcpOtherServices = copyList(tcpOtherServices);
        this.udpWebServices = copyList(udpWebServices);
        this.udpOtherServices = copyList(udpOtherServices);
        this.sshCheckTimeoutMs = sshCheckTimeoutMs > 0 ? sshCheckTimeoutMs : 2000;
        this.webCheckTimeoutMs = webCheckTimeoutMs > 0 ? webCheckTimeoutMs : 2500;
        this.miniAi = miniAi != null ? miniAi : new MiniAiEngine(true, false, "", "", "");
    }

    /**
     * Constructor tương thích ngược với code cũ dùng danh sách int port
     */
    public SmartMultiplexer(String name, int listenPort, String bindAddress,
                            List<Integer> webPorts, List<Integer> shellPorts,
                            List<Integer> otherPorts, int sshCheckTimeoutMs,
                            int webCheckTimeoutMs, MiniAiEngine miniAi) {
        this(name, listenPort, bindAddress,
                toEndpoints(shellPorts, ServiceEndpoint.Category.SHELL, "tcp", "shell"),
                toEndpoints(webPorts, ServiceEndpoint.Category.WEB, "tcp", "web"),
                toEndpoints(otherPorts, ServiceEndpoint.Category.OTHER, "tcp", "other"),
                new ArrayList<>(), new ArrayList<>(),
                sshCheckTimeoutMs, webCheckTimeoutMs, miniAi);
    }

    private static List<ServiceEndpoint> toEndpoints(List<Integer> ports, ServiceEndpoint.Category cat,
                                                     String type, String prefix) {
        List<ServiceEndpoint> list = new ArrayList<>();
        if (ports != null) {
            for (int p : ports) {
                list.add(new ServiceEndpoint(prefix + "-" + p, cat, true, "127.0.0.1",
                        p, false, type, list.isEmpty() && cat == ServiceEndpoint.Category.WEB, "", "auto", "/"));
            }
        }
        return list;
    }

    private static List<ServiceEndpoint> copyList(List<ServiceEndpoint> src) {
        return src != null ? new ArrayList<>(src) : new ArrayList<>();
    }

    // ==========================================
    // HOT-RELOAD: Cập nhật cấu hình realtime
    // ==========================================

    public void setPublicEndpointOverride(String publicHost, int publicPort) {
        this.configuredPublicHost = (publicHost != null && !publicHost.trim().isEmpty())
                ? publicHost.trim() : "auto";
        this.configuredPublicPort = publicPort;
    }

    public void updateEndpoints(List<ServiceEndpoint> shell, List<ServiceEndpoint> tcpWeb,
                                List<ServiceEndpoint> tcpOther, List<ServiceEndpoint> udpWeb,
                                List<ServiceEndpoint> udpOther, int newSshTimeout, int newWebTimeout) {
        this.shellServices = copyList(shell);
        this.tcpWebServices = copyList(tcpWeb);
        this.tcpOtherServices = copyList(tcpOther);
        this.udpWebServices = copyList(udpWeb);
        this.udpOtherServices = copyList(udpOther);
        if (newSshTimeout > 0) this.sshCheckTimeoutMs = newSshTimeout;
        if (newWebTimeout > 0) this.webCheckTimeoutMs = newWebTimeout;

        // Khởi động thêm UDP listener nếu trước đó chưa mở mà config mới vừa thêm dịch vụ UDP
        if (running && !udpActive && (!this.udpWebServices.isEmpty() || !this.udpOtherServices.isEmpty())) {
            tryStartUdpMux();
        }

        OutputLogger.success("Mux [" + name + "] Hot-Reload: Đã cập nhật cấu hình dịch vụ!");
        printServiceGroups();
    }

    // ==========================================
    // KHỞI ĐỘNG VÀ DỪNG (TCP + UDP)
    // ==========================================

    public void start() throws IOException {
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MJT-Mux-" + name);
            t.setDaemon(true);
            return t;
        });

        List<Integer> portsToTry = new ArrayList<>();
        portsToTry.add(listenPort);
        int envPort = detectPortFromEnvironment();
        if (envPort > 0 && envPort != listenPort) {
            portsToTry.add(0, envPort);
        }
        for (int p : new int[]{25565, 8080, 3000, 9090}) {
            if (!portsToTry.contains(p)) portsToTry.add(p);
        }

        List<String> addressesToTry = new ArrayList<>();
        addressesToTry.add(bindAddress);
        if (!"0.0.0.0".equals(bindAddress) && !"127.0.0.1".equals(bindAddress)) addressesToTry.add("0.0.0.0");
        if (!"127.0.0.1".equals(bindAddress)) addressesToTry.add("127.0.0.1");

        IOException lastError = null;
        for (int port : portsToTry) {
            for (String addr : addressesToTry) {
                try {
                    InetAddress bind = InetAddress.getByName(addr);
                    this.serverSocket = new ServerSocket(port, 50, bind);
                    this.actualBoundPort = port;
                    this.actualBoundAddress = addr;

                    if (port != listenPort || !addr.equals(bindAddress)) {
                        OutputLogger.warning(String.format(
                                "Smart Bind: Không thể mở %s:%d → Tự động chuyển sang %s:%d",
                                bindAddress, listenPort, addr, port));
                    }
                    break;
                } catch (IOException e) {
                    lastError = e;
                }
            }
            if (serverSocket != null && serverSocket.isBound()) break;
        }

        if (serverSocket == null || !serverSocket.isBound()) {
            throw new IOException("Không thể bind bất kỳ cổng TCP nào! Đã thử: " + portsToTry
                    + ". Lỗi cuối: " + (lastError != null ? lastError.getMessage() : "unknown"));
        }

        this.running = true;
        threadPool.submit(this::acceptTcpLoop);

        OutputLogger.success(String.format("Smart Multiplexer v4 [%s] đang mở TCP trên %s:%d",
                name, actualBoundAddress, actualBoundPort));

        if (!udpWebServices.isEmpty() || !udpOtherServices.isEmpty()) {
            tryStartUdpMux();
        }

        printServiceGroups();
    }

    private void tryStartUdpMux() {
        try {
            InetAddress bind = InetAddress.getByName(actualBoundAddress);
            this.udpSocket = new DatagramSocket(new InetSocketAddress(bind, actualBoundPort));
            this.udpActive = true;
            threadPool.submit(this::receiveUdpLoop);
            threadPool.submit(this::udpCleanupLoop);
            OutputLogger.success(String.format("Smart Multiplexer v4 [%s] đang mở UDP trên %s:%d",
                    name, actualBoundAddress, actualBoundPort));
        } catch (IOException e) {
            OutputLogger.warning("Không thể mở UDP cùng cổng " + actualBoundPort + ": " + e.getMessage());
        }
    }

    private void printServiceGroups() {
        OutputLogger.info(String.format("  [Tầng 1 - Service SSH/SFTP/FTP (%dms)]: %s",
                sshCheckTimeoutMs, formatEndpointList(shellServices)));
        OutputLogger.info(String.format("  [Tầng 2 - TCP Web Path/Group (%dms)]: %s",
                webCheckTimeoutMs, formatEndpointList(tcpWebServices)));
        OutputLogger.info("  [Tầng 3 - TCP Other (Round-Robin + Mini AI)]: "
                + formatEndpointList(tcpOtherServices));
        if (!udpWebServices.isEmpty() || !udpOtherServices.isEmpty()) {
            OutputLogger.info("  [Luồng UDP - MCPE/Web QUIC]: Web=" + formatEndpointList(udpWebServices)
                    + " | Other=" + formatEndpointList(udpOtherServices));
        }
    }

    private static String formatEndpointList(List<ServiceEndpoint> list) {
        if (list == null || list.isEmpty()) return "(trống)";
        return list.toString();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        for (UdpRelaySession s : udpSessions.values()) s.close();
        udpSessions.clear();

        if (threadPool != null) threadPool.shutdownNow();
        OutputLogger.info("Smart Multiplexer [" + name + "] đã dừng.");
    }

    private int detectPortFromEnvironment() {
        for (String env : new String[]{"SERVER_PORT", "P_SERVER_PORT", "SERVER_ALLOCATION_PORT", "PORT"}) {
            String val = System.getenv(env);
            if (val != null && !val.isEmpty()) {
                try {
                    int port = Integer.parseInt(val.trim());
                    if (port > 0 && port <= 65535) return port;
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    private boolean isSelfLoopEndpoint(ServiceEndpoint ep) {
        if (ep.getPort() != actualBoundPort) return false;
        String ip = ep.getIp();
        return "127.0.0.1".equals(ip) || "localhost".equalsIgnoreCase(ip)
                || "0.0.0.0".equals(ip) || "::1".equals(ip);
    }

    private boolean guardSelfLoop(ServiceEndpoint ep) {
        if (ep.getPort() <= 0) return true;
        if (isSelfLoopEndpoint(ep)) {
            if (warnedSelfLoops.add(ep.getName() + "@" + ep.getPort())) {
                OutputLogger.warning(String.format(
                        "Bỏ qua dịch vụ [%s] vì cổng đích %s:%d trùng với cổng Multiplexer (tránh vòng lặp vô hạn)!",
                        ep.getName(), ep.getIp(), ep.getPort()));
            }
            return true;
        }
        return false;
    }

    // ==========================================
    // LUỒNG XỬ LÝ TCP — 3 TẦNG XÁC MINH CHO MỖI SESSION
    // ==========================================

    private void acceptTcpLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleTcpClient(clientSocket));
            } catch (SocketException e) {
                if (running) OutputLogger.error("Mux [" + name + "] Lỗi Socket TCP: " + e.getMessage());
                break;
            } catch (IOException e) {
                if (running) OutputLogger.error("Mux [" + name + "] Lỗi I/O TCP: " + e.getMessage());
            }
        }
    }

    private void handleTcpClient(Socket clientSocket) {
        activeConnections.incrementAndGet();
        long sid = sessionCounter.incrementAndGet();
        String sessionTag = String.format("S-%03d", sid);
        String clientInfo = clientSocket.getRemoteSocketAddress().toString();
        long sessionStartMs = System.currentTimeMillis();

        try {
            byte[] initialData = peekInitialBytes(clientSocket);
            ProtocolType clientHint = ProtocolSniffer.detect(initialData, initialData.length);

            // Tầng 1: Service SSH / SFTP / FTP (chờ tối đa 2 giây)
            VerifiedRoute verified = tryShellStage(initialData, clientHint);

            if (verified == null && initialData.length == 0) {
                byte[] lateData = tryQuickExtraPeek(clientSocket, 150);
                if (lateData.length > 0) {
                    initialData = lateData;
                    clientHint = ProtocolSniffer.detect(initialData, initialData.length);
                }
            }

            // Tầng 2: TCP Web (theo Path dài nhất / Group / WebMain)
            WebFallbackCandidate webFallback = new WebFallbackCandidate();
            if (verified == null) {
                verified = tryWebStage(initialData, clientHint, webFallback);
            }

            // Tầng 3: TCP Other (Round-Robin + Mini AI)
            if (verified == null) {
                verified = tryOtherStage(sessionTag, initialData, clientHint);
            }

            // Dự phòng Web (nếu Other không nhận và Web từng trả 404 / xử lý chậm)
            if (verified == null) {
                verified = resolveWebFallback(initialData, webFallback);
            }

            if (verified == null) {
                OutputLogger.warning(String.format(
                        "Mux [%s|%s] ✗ %s [%s] → Đã kiểm tra cả 3 tầng (Service → Web → Other) nhưng không dịch vụ nào xác nhận thành công.",
                        name, sessionTag, clientInfo, clientHint.getDescription()));
                closeQuietly(clientSocket);
                return;
            }

            OutputLogger.info(String.format(
                    "Mux [%s|%s] ✓ %s → %s:%d [%s] (%s)",
                    name, sessionTag, clientInfo,
                    verified.endpoint.getIp(), verified.endpoint.getPort(),
                    verified.endpoint.getName(), verified.stageLabel));

            byte[] replyToSend = verified.backendReply;
            boolean isMcpeSession = "MCPE_NETHERNET".equals(verified.aiLabel);
            NetherNetHelper.PublicEndpoint pubEp = isMcpeSession
                    ? NetherNetHelper.resolvePublicEndpoint(initialData,
                            configuredPublicHost, configuredPublicPort, actualBoundPort)
                    : null;

            if (isMcpeSession && replyToSend.length > 0) {
                verified.backendSocket.setSoTimeout(600);
                final ServiceEndpoint matchedEp = verified.endpoint;
                replyToSend = NetherNetHelper.finalizeAndRewriteHttpResponse(
                        sessionTag, replyToSend, verified.backendSocket.getInputStream(), pubEp,
                        bdsPort -> registerDynamicMcpeUdpTarget(matchedEp.getIp(), bdsPort));
            }

            if (replyToSend.length > 0) {
                OutputStream clientOut = clientSocket.getOutputStream();
                clientOut.write(replyToSend);
                clientOut.flush();
            }

            clientSocket.setSoTimeout(0);
            verified.backendSocket.setSoTimeout(0);

            final Socket finalTarget = verified.backendSocket;
            final AtomicLong bytesC2T = new AtomicLong(initialData.length);
            final AtomicLong bytesT2C = new AtomicLong(replyToSend.length);
            final ServiceEndpoint finalEndpoint = verified.endpoint;

            Thread c2t = new Thread(() -> pumpStream(clientSocket, finalTarget, bytesC2T), "Mux-C2T-" + sessionTag);
            Thread t2c = new Thread(() -> {
                if (isMcpeSession) {
                    pumpMcpeResponseStream(sessionTag, finalTarget, clientSocket,
                            finalEndpoint, pubEp, bytesT2C);
                } else {
                    pumpStream(finalTarget, clientSocket, bytesT2C);
                }
            }, "Mux-T2C-" + sessionTag);
            c2t.setDaemon(true);
            t2c.setDaemon(true);
            c2t.start();
            t2c.start();

            c2t.join();
            t2c.join();

            if (verified.aiLabel != null) {
                long durationMs = System.currentTimeMillis() - sessionStartMs;
                miniAi.recordSessionOutcome(verified.aiLabel, verified.endpoint.getPort(),
                        durationMs, bytesT2C.get(), bytesC2T.get());
            }

        } catch (Exception ignored) {
        } finally {
            closeQuietly(clientSocket);
            activeConnections.decrementAndGet();
        }
    }

    // ==========================================
    // TẦNG 1: SERVICE (SSH / SFTP / FTP) — CHỜ 2 GIÂY
    // ==========================================

    private VerifiedRoute tryShellStage(byte[] initialData, ProtocolType clientHint) {
        List<ServiceEndpoint> services = this.shellServices;
        if (services == null || services.isEmpty()) return null;

        if (clientHint == ProtocolType.HTTP || clientHint == ProtocolType.HTTPS_TLS
                || clientHint == ProtocolType.MINECRAFT) {
            return null;
        }

        Set<String> probedKeys = new LinkedHashSet<>();

        for (ServiceEndpoint ep : services) {
            if (guardSelfLoop(ep)) continue;
            if (!probedKeys.add(ep.getTargetKey())) continue; // Tránh probe trùng ip:port (vd ssh + sftp cùng 22)

            Socket backend = tryConnectTarget(ep.getIp(), ep.getPort());
            if (backend == null) continue;

            try {
                if (initialData.length > 0) {
                    OutputStream out = backend.getOutputStream();
                    out.write(initialData);
                    out.flush();
                }

                ProbeReadResult reply = readBackendVerification(backend, sshCheckTimeoutMs);
                if (!reply.eof && reply.data.length > 0
                        && ProtocolSniffer.isShellResponse(reply.data, reply.data.length)) {
                    String banner = new String(reply.data, 0, Math.min(reply.data.length, 24),
                            StandardCharsets.US_ASCII).trim();
                    return new VerifiedRoute(ep, backend, reply.data,
                            "Tầng 1: Xác nhận (" + banner + ")", null);
                }
                closeQuietly(backend);
            } catch (IOException e) {
                closeQuietly(backend);
            }
        }
        return null;
    }

    // ==========================================
    // TẦNG 2: TCP WEB (PATH ROUTING + GROUP + WEB_MAIN)
    // ==========================================

    private VerifiedRoute tryWebStage(byte[] initialData, ProtocolType clientHint,
                                      WebFallbackCandidate fallback) {
        List<ServiceEndpoint> rawWeb = this.tcpWebServices;
        if (rawWeb == null || rawWeb.isEmpty()) return null;

        if (clientHint == ProtocolType.SSH || clientHint == ProtocolType.MINECRAFT
                || initialData.length == 0) {
            return null;
        }

        String reqPath = (clientHint == ProtocolType.HTTP)
                ? ProtocolSniffer.extractHttpPath(initialData, initialData.length) : null;
        boolean isMcpeJoin = ProtocolSniffer.isMcpeNetherNetRequest(initialData, initialData.length);
        if (isMcpeJoin && this.tcpOtherServices != null && !this.tcpOtherServices.isEmpty()) {
            return null;
        }

        List<ServiceEndpoint> orderedWeb = orderWebCandidatesByPath(rawWeb, reqPath);
        Set<String> probedKeys = new LinkedHashSet<>();

        for (ServiceEndpoint ep : orderedWeb) {
            if (guardSelfLoop(ep)) continue;
            if (!probedKeys.add(ep.getTargetKey())) continue;

            Socket backend = tryConnectTarget(ep.getIp(), ep.getPort());
            if (backend == null) continue;

            try {
                OutputStream out = backend.getOutputStream();
                out.write(initialData);
                out.flush();

                ProbeReadResult reply = readBackendVerification(backend, webCheckTimeoutMs);
                if (reply.data.length > 0) {
                    if (clientHint == ProtocolType.HTTPS_TLS) {
                        if (ProtocolSniffer.isTlsResponse(reply.data, reply.data.length)) {
                            return new VerifiedRoute(ep, backend, reply.data,
                                    "Tầng 2: HTTPS/TLS xác nhận", null);
                        }
                        closeQuietly(backend);
                        continue;
                    }

                    if (ProtocolSniffer.isHttpConfirmedSuccess(reply.data, reply.data.length)) {
                        if (isMcpeJoin && ProtocolSniffer.isHtmlHttpResponse(reply.data, reply.data.length)) {
                            closeQuietly(backend);
                            continue;
                        }
                        int code = ProtocolSniffer.parseHttpStatusCode(reply.data, reply.data.length);
                        String codeTag = code > 0 ? " [" + code + "]" : "";
                        return new VerifiedRoute(ep, backend, reply.data,
                                "Tầng 2: HTTP" + codeTag + " path=" + ep.getPath(), null);
                    }

                    if (ProtocolSniffer.isHttpSoftNotFound(reply.data, reply.data.length)
                            && fallback.softNotFoundEndpoint == null) {
                        fallback.softNotFoundEndpoint = ep;
                    }
                    closeQuietly(backend);
                } else if (!reply.eof && reply.timedOut && clientHint == ProtocolType.HTTP && !isMcpeJoin) {
                    if (fallback.slowAliveEndpoint == null) {
                        fallback.slowAliveEndpoint = ep;
                    }
                    closeQuietly(backend);
                } else {
                    closeQuietly(backend);
                }
            } catch (IOException e) {
                closeQuietly(backend);
            }
        }
        return null;
    }

    /**
     * Sắp xếp ứng viên Web theo:
     *   1. Khớp "path" dài nhất (ví dụ "/about" ưu tiên hơn "/")
     *   2. Cổng có "web_main": true
     *   3. Các cổng Web còn lại theo thứ tự config
     */
    private List<ServiceEndpoint> orderWebCandidatesByPath(List<ServiceEndpoint> webList, String requestPath) {
        String lowerReq = requestPath != null ? requestPath.toLowerCase(Locale.ROOT) : "";
        List<ServiceEndpoint> sorted = new ArrayList<>();
        for (ServiceEndpoint ep : webList) {
            if (ep.getPort() > 0) sorted.add(ep);
        }

        sorted.sort(Comparator.comparingInt((ServiceEndpoint ep) -> {
            String epPath = ep.getPath().toLowerCase(Locale.ROOT);
            int score = 0;
            if (!lowerReq.isEmpty() && lowerReq.startsWith(epPath)) {
                score = 1000 + epPath.length();
            }
            if (ep.isWebMain()) {
                score += 10;
            }
            return -score; // Điểm cao xếp trước
        }));
        return sorted;
    }

    private VerifiedRoute resolveWebFallback(byte[] initialData, WebFallbackCandidate fallback) {
        ServiceEndpoint ep = fallback.slowAliveEndpoint != null
                ? fallback.slowAliveEndpoint : fallback.softNotFoundEndpoint;
        if (ep == null) return null;

        Socket backend = tryConnectTarget(ep.getIp(), ep.getPort());
        if (backend != null) {
            try {
                OutputStream out = backend.getOutputStream();
                out.write(initialData);
                out.flush();
                String label = fallback.slowAliveEndpoint != null
                        ? "Tầng 2: HTTP (giữ kết nối xử lý chậm)"
                        : "Tầng 2: HTTP (404 từ Web server)";
                return new VerifiedRoute(ep, backend, new byte[0], label, null);
            } catch (IOException e) {
                closeQuietly(backend);
            }
        }
        return null;
    }

    // ==========================================
    // TẦNG 3: TCP OTHER (ROUND-ROBIN + MINI AI)
    // ==========================================

    private VerifiedRoute tryOtherStage(String sessionTag, byte[] initialData, ProtocolType clientHint) {
        List<ServiceEndpoint> services = this.tcpOtherServices;
        if (services == null || services.isEmpty()) return null;

        MiniAiEngine.AiClassification aiClass = miniAi.classify(initialData);
        List<ServiceEndpoint> rotated = buildRoundRobinEndpoints(services, roundRobinCounter);
        List<ServiceEndpoint> candidates = miniAi.applyEndpointOrder(aiClass, rotated);

        ServiceEndpoint silentAliveFallback = null;
        StringBuilder trace = new StringBuilder();
        Set<String> probedKeys = new LinkedHashSet<>();

        for (ServiceEndpoint ep : candidates) {
            if (guardSelfLoop(ep)) continue;
            if (!probedKeys.add(ep.getTargetKey())) continue;

            Socket backend = tryConnectTarget(ep.getIp(), ep.getPort());
            if (backend == null) {
                trace.append(ep.getName()).append("(").append(ep.getPort()).append(")=Refused, ");
                continue;
            }

            try {
                if (initialData.length > 0) {
                    OutputStream out = backend.getOutputStream();
                    out.write(initialData);
                    out.flush();
                }

                int verifyTimeout = "MCPE_NETHERNET".equals(aiClass.label)
                        ? OTHER_MCPE_VERIFY_TIMEOUT_MS : OTHER_VERIFY_TIMEOUT_MS;
                ProbeReadResult reply = readBackendVerification(backend, verifyTimeout);

                if (reply.eof && reply.data.length == 0) {
                    trace.append(ep.getName()).append("(").append(ep.getPort()).append(")=Closed, ");
                    miniAi.recordVerificationFailure(aiClass.label, ep.getPort());
                    closeQuietly(backend);
                    continue;
                }

                if (reply.data.length > 0) {
                    MiniAiEngine.BackendVerdict verdict =
                            miniAi.verifyBackendResponse(aiClass, reply.data, reply.data.length);

                    if (verdict == MiniAiEngine.BackendVerdict.MISMATCH_REJECT) {
                        trace.append(ep.getName()).append("(").append(ep.getPort()).append(")=Mismatch, ");
                        miniAi.recordVerificationFailure(aiClass.label, ep.getPort());
                        closeQuietly(backend);
                        continue;
                    }

                    miniAi.recordVerificationSuccess(aiClass.label, ep.getPort());
                    String info = String.format(Locale.ROOT,
                            "Tầng 3: Other [%s|%s %.0f%%] xác nhận phản hồi",
                            aiClass.label, aiClass.source, aiClass.confidence * 100);
                    return new VerifiedRoute(ep, backend, reply.data, info, aiClass.label);
                }

                if (!reply.eof && reply.timedOut) {
                    if (!aiClass.expectsImmediateReply && miniAi.allowsSilentFallback(aiClass)) {
                        String info = String.format(Locale.ROOT,
                                "Tầng 3: Other [%s|%s %.0f%%] giữ luồng",
                                aiClass.label, aiClass.source, aiClass.confidence * 100);
                        return new VerifiedRoute(ep, backend, new byte[0], info, aiClass.label);
                    } else if (miniAi.allowsSilentFallback(aiClass)) {
                        trace.append(ep.getName()).append("(").append(ep.getPort()).append(")=Silent, ");
                        if (silentAliveFallback == null) silentAliveFallback = ep;
                        closeQuietly(backend);
                    } else {
                        trace.append(ep.getName()).append("(").append(ep.getPort()).append(")=Timeout, ");
                        miniAi.recordVerificationFailure(aiClass.label, ep.getPort());
                        closeQuietly(backend);
                    }
                } else {
                    trace.append(ep.getName()).append("(").append(ep.getPort()).append(")=NoReply, ");
                    closeQuietly(backend);
                }
            } catch (IOException e) {
                trace.append(ep.getName()).append("(").append(ep.getPort()).append(")=Err, ");
                miniAi.recordVerificationFailure(aiClass.label, ep.getPort());
                closeQuietly(backend);
            }
        }

        if (silentAliveFallback == null && trace.length() > 0) {
            OutputLogger.info(String.format("Mux [%s|%s] Trace Other [%s]: %s",
                    name, sessionTag, aiClass.label, trace.substring(0, trace.length() - 2)));
        }

        if (silentAliveFallback != null) {
            Socket backend = tryConnectTarget(silentAliveFallback.getIp(), silentAliveFallback.getPort());
            if (backend != null) {
                try {
                    if (initialData.length > 0) {
                        OutputStream out = backend.getOutputStream();
                        out.write(initialData);
                        out.flush();
                    }
                    String info = String.format(Locale.ROOT,
                            "Tầng 3: Other [%s|%s] fallback cổng đang mở",
                            aiClass.label, aiClass.source);
                    return new VerifiedRoute(silentAliveFallback, backend, new byte[0],
                            info, aiClass.label);
                } catch (IOException e) {
                    closeQuietly(backend);
                }
            }
        }

        return null;
    }

    // ==========================================
    // LUỒNG UDP TÍCH HỢP (MCPE RAKNET / NETHERNET / WEB QUIC)
    // ==========================================

    private void receiveUdpLoop() {
        byte[] buffer = new byte[UDP_BUFFER_SIZE];

        while (running && udpSocket != null && !udpSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                int len = packet.getLength();
                byte[] data = new byte[len];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, len);

                String clientKey = packet.getAddress().getHostAddress() + ":" + packet.getPort();
                UdpRelaySession session = udpSessions.get(clientKey);

                if (session == null) {
                    String packetKind = identifyUdpPacket(data, len);
                    ServiceEndpoint target = selectUdpEndpoint(packetKind);
                    if (target == null || guardSelfLoop(target)) {
                        continue;
                    }
                    try {
                        DatagramSocket relay = new DatagramSocket();
                        relay.setSoTimeout(5000);
                        session = new UdpRelaySession(relay, packet.getAddress(), packet.getPort(), target);
                        udpSessions.put(clientKey, session);
                        final UdpRelaySession finalSession = session;
                        threadPool.submit(() -> relayUdpResponseLoop(finalSession));
                        OutputLogger.info(String.format(
                                "Mux [%s|UDP] ✓ /%s [%s] → %s:%d [%s]",
                                name, clientKey, packetKind,
                                target.getIp(), target.getPort(), target.getName()));
                    } catch (IOException e) {
                        OutputLogger.error("Mux [" + name + "|UDP] Lỗi mở phiên relay: " + e.getMessage());
                        continue;
                    }
                }

                session.touch();
                DatagramPacket fwd = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName(session.target.getIp()), session.target.getPort());
                session.relaySocket.send(fwd);

            } catch (SocketException e) {
                if (running) OutputLogger.error("Mux [" + name + "|UDP] Lỗi Socket: " + e.getMessage());
                break;
            } catch (IOException e) {
                if (running) OutputLogger.error("Mux [" + name + "|UDP] Lỗi I/O: " + e.getMessage());
            }
        }
    }

    private ServiceEndpoint selectUdpEndpoint(String packetKind) {
        List<ServiceEndpoint> webUdp = this.udpWebServices;
        List<ServiceEndpoint> otherUdp = this.udpOtherServices;

        // Nếu nhận diện QUIC/HTTP3 và có cấu hình udp.web_port -> ưu tiên web_port
        if ("QUIC_HTTP3".equals(packetKind) && webUdp != null && !webUdp.isEmpty()) {
            List<ServiceEndpoint> sorted = orderWebCandidatesByPath(webUdp, "/");
            if (!sorted.isEmpty()) return sorted.get(0);
        }

        // Nếu NetherNet SDP vừa tiết lộ cổng UDP WebRTC thực tế của BDS → ưu tiên định tuyến đúng cổng đó
        ServiceEndpoint dyn = this.dynamicMcpeUdpEndpoint;
        if (dyn != null && System.currentTimeMillis() < dynamicMcpeUdpExpiresAtMs
                && (packetKind.contains("NetherNet") || packetKind.contains("UDP Datagram"))) {
            return dyn;
        }

        if (otherUdp != null && !otherUdp.isEmpty()) {
            List<ServiceEndpoint> rotated = buildRoundRobinEndpoints(otherUdp, udpRoundRobinCounter);
            if (packetKind.contains("MCPE")) {
                for (ServiceEndpoint ep : rotated) {
                    String n = ep.getName().toLowerCase(Locale.ROOT);
                    if (n.contains("pe") || n.contains("bedrock") || ep.getPort() == 19132) {
                        return ep;
                    }
                }
            }
            return rotated.get(0);
        }

        if (webUdp != null && !webUdp.isEmpty()) {
            return webUdp.get(0);
        }
        return null;
    }

    private void registerDynamicMcpeUdpTarget(String ip, int bdsUdpPort) {
        if (bdsUdpPort <= 0 || bdsUdpPort == actualBoundPort) return;
        this.dynamicMcpeUdpEndpoint = new ServiceEndpoint(
                "mcpe-nethernet-dynamic", ServiceEndpoint.Category.OTHER, true,
                ip, bdsUdpPort, false, "udp", false, "", "", "/");
        this.dynamicMcpeUdpExpiresAtMs = System.currentTimeMillis() + DYNAMIC_MCPE_UDP_TTL_MS;
    }

    private static String identifyUdpPacket(byte[] data, int len) {
        if (len >= 17 && ((data[0] & 0xFF) == 0x01 || (data[0] & 0xFF) == 0x02)) {
            return "MCPE RakNet Ping";
        }
        if (len >= 17 && ((data[0] & 0xFF) == 0x05 || (data[0] & 0xFF) == 0x07)) {
            return "MCPE RakNet Handshake";
        }
        if (len >= 20 && data[4] == 0x21 && data[5] == 0x12
                && (data[6] & 0xFF) == 0xA4 && data[7] == 0x42) {
            return "MCPE NetherNet STUN/ICE";
        }
        if (len >= 13 && (data[0] & 0xFF) >= 20 && (data[0] & 0xFF) <= 63) {
            return "MCPE NetherNet DTLS";
        }
        // QUIC long header (RFC 9000): Bit 7=1 (0x80), Bit 6=1 (0x40), version != 0
        if (len >= 1200 && (data[0] & 0xC0) == 0xC0) {
            return "QUIC_HTTP3";
        }
        return "UDP Datagram";
    }

    private void relayUdpResponseLoop(UdpRelaySession session) {
        byte[] buffer = new byte[UDP_BUFFER_SIZE];
        while (running && !session.relaySocket.isClosed() && udpSocket != null && !udpSocket.isClosed()) {
            try {
                DatagramPacket resp = new DatagramPacket(buffer, buffer.length);
                session.relaySocket.receive(resp);

                byte[] data = new byte[resp.getLength()];
                System.arraycopy(resp.getData(), resp.getOffset(), data, 0, resp.getLength());

                DatagramPacket back = new DatagramPacket(
                        data, data.length, session.clientAddress, session.clientPort);
                udpSocket.send(back);
                session.touch();
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) break;
            }
        }
    }

    private void udpCleanupLoop() {
        while (running) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                break;
            }
            long now = System.currentTimeMillis();
            udpSessions.entrySet().removeIf(entry -> {
                if (now - entry.getValue().lastActivity > UDP_SESSION_TIMEOUT_MS) {
                    entry.getValue().close();
                    return true;
                }
                return false;
            });
        }
    }

    // ==========================================
    // ROUND-ROBIN & TIỆN ÍCH MẠNG
    // ==========================================

    private List<ServiceEndpoint> buildRoundRobinEndpoints(List<ServiceEndpoint> list, AtomicInteger counter) {
        if (list == null || list.isEmpty()) return new ArrayList<>();
        int size = list.size();
        int startIndex = Math.floorMod(counter.getAndIncrement(), size);
        List<ServiceEndpoint> rotated = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rotated.add(list.get((startIndex + i) % size));
        }
        return rotated;
    }

    private byte[] peekInitialBytes(Socket clientSocket) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];

        try {
            clientSocket.setSoTimeout(PEEK_TIMEOUT_MS);
            InputStream in = clientSocket.getInputStream();

            int read = in.read(buffer);
            if (read <= 0) return baos.toByteArray();
            baos.write(buffer, 0, read);

            long deadline = System.currentTimeMillis() + 250;
            while (baos.size() < 16 && System.currentTimeMillis() < deadline) {
                clientSocket.setSoTimeout(FRAGMENT_WAIT_MS);
                int more = in.read(buffer);
                if (more <= 0) break;
                baos.write(buffer, 0, more);
            }

            byte[] current = baos.toByteArray();
            if (ProtocolSniffer.isHttp(current, current.length)) {
                if (!ProtocolSniffer.isCompleteHttpHeader(current, current.length)) {
                    long httpDeadline = System.currentTimeMillis() + 200;
                    while (baos.size() < BUFFER_SIZE && System.currentTimeMillis() < httpDeadline) {
                        clientSocket.setSoTimeout(60);
                        int more = in.read(buffer);
                        if (more <= 0) break;
                        baos.write(buffer, 0, more);
                        current = baos.toByteArray();
                        if (ProtocolSniffer.isCompleteHttpHeader(current, current.length)) break;
                    }
                }
                int expectedTotalLen = computeExpectedHttpBytes(current);
                if (expectedTotalLen > current.length && expectedTotalLen <= BUFFER_SIZE) {
                    long bodyDeadline = System.currentTimeMillis() + 200;
                    while (baos.size() < expectedTotalLen && System.currentTimeMillis() < bodyDeadline) {
                        clientSocket.setSoTimeout(60);
                        int more = in.read(buffer);
                        if (more <= 0) break;
                        baos.write(buffer, 0, more);
                    }
                }
            } else {
                ProtocolSniffer.MinecraftHandshakeInfo mc =
                        ProtocolSniffer.inspectMinecraftHandshake(current, current.length);
                if (mc != null && !mc.hasExtraPacketBytes) {
                    clientSocket.setSoTimeout(80);
                    int more = in.read(buffer);
                    if (more > 0) baos.write(buffer, 0, more);
                }
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException ignored) {}

        return baos.toByteArray();
    }

    private static int computeExpectedHttpBytes(byte[] data) {
        String text = new String(data, StandardCharsets.ISO_8859_1);
        int headerEnd = text.indexOf("\r\n\r\n");
        int sepLen = 4;
        if (headerEnd < 0) {
            headerEnd = text.indexOf("\n\n");
            sepLen = 2;
        }
        if (headerEnd < 0) return -1;
        String headersLower = text.substring(0, headerEnd).toLowerCase(Locale.ROOT);
        int clIdx = headersLower.indexOf("content-length:");
        if (clIdx < 0) return headerEnd + sepLen;
        int lineEnd = headersLower.indexOf('\n', clIdx);
        String val = (lineEnd > clIdx ? headersLower.substring(clIdx + 15, lineEnd)
                : headersLower.substring(clIdx + 15)).trim();
        try {
            int bodyLen = Integer.parseInt(val);
            return bodyLen > 0 ? headerEnd + sepLen + bodyLen : headerEnd + sepLen;
        } catch (NumberFormatException e) {
            return headerEnd + sepLen;
        }
    }

    private byte[] tryQuickExtraPeek(Socket clientSocket, int timeoutMs) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            clientSocket.setSoTimeout(timeoutMs);
            int read = clientSocket.getInputStream().read(buffer);
            if (read > 0) {
                byte[] out = new byte[read];
                System.arraycopy(buffer, 0, out, 0, read);
                return out;
            }
        } catch (IOException ignored) {}
        return new byte[0];
    }

    private ProbeReadResult readBackendVerification(Socket backend, int timeoutMs) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            backend.setSoTimeout(timeoutMs);
            InputStream in = backend.getInputStream();
            int read = in.read(buffer);
            if (read == -1) return new ProbeReadResult(new byte[0], true, false);
            byte[] reply = new byte[read];
            System.arraycopy(buffer, 0, reply, 0, read);
            return new ProbeReadResult(reply, false, false);
        } catch (SocketTimeoutException e) {
            return new ProbeReadResult(new byte[0], false, true);
        } catch (IOException e) {
            return new ProbeReadResult(new byte[0], true, false);
        }
    }

    private Socket tryConnectTarget(String ip, int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), PROBE_CONNECT_TIMEOUT_MS);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            if ("127.0.0.1".equals(ip) || "localhost".equalsIgnoreCase(ip)) {
                Socket v6 = new Socket();
                try {
                    v6.connect(new InetSocketAddress("::1", port), PROBE_CONNECT_TIMEOUT_MS);
                    return v6;
                } catch (IOException e2) {
                    closeQuietly(v6);
                }
            }
            return null;
        }
    }

    private void pumpStream(Socket source, Socket target, AtomicLong byteCounter) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            InputStream in = source.getInputStream();
            OutputStream out = target.getOutputStream();
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
                byteCounter.addAndGet(bytesRead);
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(source);
            closeQuietly(target);
        }
    }

    /**
     * Bơm phản hồi từ backend MCPE NetherNet về client qua HTTP Keep-Alive,
     * tự động bắt và viết lại SDP Answer ở các yêu cầu POST /v1/join/{id} kế tiếp trên cùng kết nối.
     */
    private void pumpMcpeResponseStream(String sessionTag, Socket backend, Socket client,
                                        ServiceEndpoint endpoint, NetherNetHelper.PublicEndpoint pubEp,
                                        AtomicLong byteCounter) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            InputStream in = backend.getInputStream();
            OutputStream out = client.getOutputStream();
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                backend.setSoTimeout(600);
                byte[] processed = NetherNetHelper.finalizeAndRewriteHttpResponse(
                        sessionTag, chunk, in, pubEp,
                        bdsPort -> registerDynamicMcpeUdpTarget(endpoint.getIp(), bdsPort));
                backend.setSoTimeout(0);
                out.write(processed);
                out.flush();
                byteCounter.addAndGet(processed.length);
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(backend);
            closeQuietly(client);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }

    public int getActiveConnections() { return activeConnections.get(); }
    public String getName() { return name; }
    public boolean isRunning() { return running; }
    public int getActualBoundPort() { return actualBoundPort; }
    public String getActualBoundAddress() { return actualBoundAddress; }
    public boolean isUdpActive() { return udpActive; }
}
