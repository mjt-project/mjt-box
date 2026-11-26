package com.mjt.core.network.multiplexer;

import com.mjt.core.ui.OutputLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Smart Port Multiplexer v3 — Bộ định tuyến xác minh chủ động theo từng phiên (Per-Session Active Verifier).
 *
 * Quy trình xử lý BẮT BUỘC cho MỖI SESSION (#001, #002, ...):
 *   1. Đọc mồi ban đầu từ Client (hỗ trợ ghép gói TCP phân mảnh, không làm mất dữ liệu).
 *   2. Tầng 1 — Kiểm tra SSH / Shell (chờ tối đa 2 giây xác nhận phản hồi hợp lệ từ backend).
 *      -> Nếu xác nhận thành công: Dừng kiểm tra, giữ nguyên kết nối!
 *   3. Tầng 2 — Kiểm tra HTTP / HTTPS (nếu Tầng 1 không xác nhận thành công):
 *      -> Thử lần lượt từng cổng Web và xác minh backend thực sự phản hồi HTTP/TLS hợp lệ.
 *      -> Nếu xác nhận thành công: Dừng kiểm tra, giữ nguyên kết nối!
 *   4. Tầng 3 — Nhóm Other Round-Robin + Mini AI Offline (Optional Online):
 *      -> Nếu cả SSH và Web đều không xác nhận được, chuyển vào vòng lặp Round-Robin của Other.
 *      -> Mini AI phân loại gói tin, kiểm chứng phản hồi backend chống nối nhầm và tự học Q-Score.
 */
public class SmartMultiplexer {

    private final String name;
    private final int listenPort;
    private final String bindAddress;

    // ===== 3 NHÓM CỔNG TÁCH BIỆT (volatile cho Hot-Reload) =====
    private volatile List<Integer> webPorts;    // Tầng 2: HTTP, HTTPS, WebSocket
    private volatile List<Integer> shellPorts;  // Tầng 1: SSH, SFTP, FTP
    private volatile List<Integer> otherPorts;  // Tầng 3: Minecraft, game, dịch vụ khác

    private volatile int sshCheckTimeoutMs;
    private volatile int webCheckTimeoutMs;

    private final MiniAiEngine miniAi;

    // Bộ đếm Round-Robin cho nhóm Other & bộ đếm Session
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private final AtomicLong sessionCounter = new AtomicLong(0);
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running;

    private int actualBoundPort = -1;
    private String actualBoundAddress = null;

    // Hằng số mạng
    private static final int BUFFER_SIZE = 8192;
    private static final int PEEK_TIMEOUT_MS = 1500;
    private static final int FRAGMENT_WAIT_MS = 100;
    private static final int PROBE_CONNECT_TIMEOUT_MS = 400;
    private static final int OTHER_VERIFY_TIMEOUT_MS = 1000;

    /**
     * Kết quả xác minh kết nối thành công tới một cổng backend
     */
    private static class VerifiedRoute {
        final int port;
        final Socket backendSocket;
        final byte[] backendReply;
        final String stageLabel;
        final String aiLabel; // Khác null nếu đi qua Tầng 3 (Mini AI)

        VerifiedRoute(int port, Socket backendSocket, byte[] backendReply,
                      String stageLabel, String aiLabel) {
            this.port = port;
            this.backendSocket = backendSocket;
            this.backendReply = backendReply != null ? backendReply : new byte[0];
            this.stageLabel = stageLabel;
            this.aiLabel = aiLabel;
        }
    }

    /**
     * Kết quả đọc thử từ backend khi xác minh
     */
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

    public SmartMultiplexer(String name, int listenPort, String bindAddress,
                            List<Integer> webPorts, List<Integer> shellPorts,
                            List<Integer> otherPorts, int sshCheckTimeoutMs,
                            int webCheckTimeoutMs, MiniAiEngine miniAi) {
        this.name = name;
        this.listenPort = listenPort;
        this.bindAddress = bindAddress;
        this.webPorts = webPorts != null ? new ArrayList<>(webPorts) : new ArrayList<>();
        this.shellPorts = shellPorts != null ? new ArrayList<>(shellPorts) : new ArrayList<>();
        this.otherPorts = otherPorts != null ? new ArrayList<>(otherPorts) : new ArrayList<>();
        this.sshCheckTimeoutMs = sshCheckTimeoutMs > 0 ? sshCheckTimeoutMs : 2000;
        this.webCheckTimeoutMs = webCheckTimeoutMs > 0 ? webCheckTimeoutMs : 2500;
        this.miniAi = miniAi != null ? miniAi : new MiniAiEngine(true, false, "", "", "");
    }

    // ==========================================
    // HOT-RELOAD: Cập nhật cấu hình cổng realtime
    // ==========================================

    public void updateConfig(List<Integer> newWebPorts, List<Integer> newShellPorts,
                             List<Integer> newOtherPorts, int newSshTimeout, int newWebTimeout) {
        List<Integer> oldWeb = this.webPorts;
        List<Integer> oldShell = this.shellPorts;
        List<Integer> oldOther = this.otherPorts;

        this.webPorts = newWebPorts != null ? new ArrayList<>(newWebPorts) : new ArrayList<>();
        this.shellPorts = newShellPorts != null ? new ArrayList<>(newShellPorts) : new ArrayList<>();
        this.otherPorts = newOtherPorts != null ? new ArrayList<>(newOtherPorts) : new ArrayList<>();
        if (newSshTimeout > 0) this.sshCheckTimeoutMs = newSshTimeout;
        if (newWebTimeout > 0) this.webCheckTimeoutMs = newWebTimeout;

        boolean changed = !oldWeb.equals(this.webPorts)
                || !oldShell.equals(this.shellPorts)
                || !oldOther.equals(this.otherPorts);

        if (changed) {
            OutputLogger.success("Mux [" + name + "] Hot-Reload: Đã cập nhật cấu hình cổng!");
            printPortGroups();
        }
    }

    // ==========================================
    // KHỞI ĐỘNG VÀ DỪNG
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

        int[] commonPorts = {25565, 8080, 3000, 9090};
        for (int p : commonPorts) {
            if (!portsToTry.contains(p)) {
                portsToTry.add(p);
            }
        }

        List<String> addressesToTry = new ArrayList<>();
        addressesToTry.add(bindAddress);
        if (!"0.0.0.0".equals(bindAddress) && !"127.0.0.1".equals(bindAddress)) {
            addressesToTry.add("0.0.0.0");
        }
        if (!"127.0.0.1".equals(bindAddress)) {
            addressesToTry.add("127.0.0.1");
        }

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
                } catch (BindException e) {
                    lastError = e;
                } catch (IOException e) {
                    lastError = e;
                }
            }
            if (serverSocket != null && serverSocket.isBound()) {
                break;
            }
        }

        if (serverSocket == null || !serverSocket.isBound()) {
            throw new IOException("Không thể bind bất kỳ cổng nào! Đã thử: " + portsToTry
                    + " trên " + addressesToTry + ". Lỗi cuối: "
                    + (lastError != null ? lastError.getMessage() : "unknown"));
        }

        this.running = true;
        threadPool.submit(this::acceptLoop);

        OutputLogger.success(String.format("Smart Multiplexer v3 [%s] đang mở trên %s:%d",
                name, actualBoundAddress, actualBoundPort));
        printPortGroups();
    }

    private void printPortGroups() {
        OutputLogger.info(String.format("  [Tầng 1 - SSH/Shell (%dms)]: %s",
                sshCheckTimeoutMs, shellPorts.isEmpty() ? "(trống)" : shellPorts));
        OutputLogger.info(String.format("  [Tầng 2 - HTTP/HTTPS (%dms)]: %s",
                webCheckTimeoutMs, webPorts.isEmpty() ? "(trống)" : webPorts));
        OutputLogger.info("  [Tầng 3 - Other (Round-Robin + Mini AI)]: "
                + (otherPorts.isEmpty() ? "(trống)" : otherPorts));
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        OutputLogger.info("Smart Multiplexer [" + name + "] đã dừng.");
    }

    private int detectPortFromEnvironment() {
        String[] envNames = {
            "SERVER_PORT", "P_SERVER_PORT", "SERVER_ALLOCATION_PORT",
            "PORT", "PRIMARY_PORT", "ALLOCATED_PORT"
        };
        for (String envName : envNames) {
            String val = System.getenv(envName);
            if (val != null && !val.isEmpty()) {
                try {
                    int port = Integer.parseInt(val.trim());
                    if (port > 0 && port <= 65535) {
                        OutputLogger.info(String.format(
                                "Smart Bind: Phát hiện cổng từ biến môi trường %s=%d", envName, port));
                        return port;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    // ==========================================
    // VÒNG LẶP TIẾP NHẬN KẾT NỐI
    // ==========================================

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleClient(clientSocket));
            } catch (SocketException e) {
                if (running) {
                    OutputLogger.error("Mux [" + name + "] Lỗi Socket: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    OutputLogger.error("Mux [" + name + "] Lỗi I/O: " + e.getMessage());
                }
            }
        }
    }

    // ==========================================
    // CORE DISPATCHER — VÒNG LẶP XÁC MINH CHO MỖI SESSION
    // ==========================================

    private void handleClient(Socket clientSocket) {
        activeConnections.incrementAndGet();
        long sid = sessionCounter.incrementAndGet();
        String sessionTag = String.format("S-%03d", sid);
        String clientInfo = clientSocket.getRemoteSocketAddress().toString();
        long sessionStartMs = System.currentTimeMillis();

        try {
            // Bước 0: Đọc mồi dữ liệu ban đầu từ Client (ghép gói nếu TCP phân mảnh)
            byte[] initialData = peekInitialBytes(clientSocket);
            ProtocolType clientHint = ProtocolSniffer.detect(initialData, initialData.length);

            // Bước 1: Kiểm tra tuần tự từng tầng cho ĐÚNG SESSION NÀY
            // Tầng 1: SSH / Shell (chờ tối đa 2 giây xác nhận phản hồi)
            VerifiedRoute verified = tryShellStage(sessionTag, initialData, clientHint);

            // Nếu gói mồi ban đầu rỗng (0 byte) và Tầng 1 chưa khớp, tranh thủ đọc thêm 150ms
            // phòng trường hợp trình duyệt gửi HTTP trễ trước khi vào Tầng 2
            if (verified == null && initialData.length == 0) {
                byte[] lateData = tryQuickExtraPeek(clientSocket, 150);
                if (lateData.length > 0) {
                    initialData = lateData;
                    clientHint = ProtocolSniffer.detect(initialData, initialData.length);
                }
            }

            // Tầng 2: HTTP / HTTPS (nếu Tầng 1 không xác nhận thành công)
            if (verified == null) {
                verified = tryWebStage(sessionTag, initialData, clientHint);
            }

            // Tầng 3: Nhóm Other (Round-Robin + Mini AI xác minh & học thích ứng)
            if (verified == null) {
                verified = tryOtherStage(sessionTag, initialData, clientHint);
            }

            if (verified == null) {
                OutputLogger.warning(String.format(
                        "Mux [%s|%s] ✗ %s [%s] → Đã kiểm tra cả 3 tầng (SSH → HTTP/S → Other) nhưng không dịch vụ nào xác nhận thành công.",
                        name, sessionTag, clientInfo, clientHint.getDescription()));
                closeQuietly(clientSocket);
                return;
            }

            OutputLogger.info(String.format(
                    "Mux [%s|%s] ✓ %s → localhost:%d (%s)",
                    name, sessionTag, clientInfo, verified.port, verified.stageLabel));

            // Phát lại phản hồi xác minh từ backend (nếu có) về cho client
            if (verified.backendReply.length > 0) {
                OutputStream clientOut = clientSocket.getOutputStream();
                clientOut.write(verified.backendReply);
                clientOut.flush();
            }

            // Reset timeout của cả 2 socket để phiên làm việc diễn ra bình thường
            clientSocket.setSoTimeout(0);
            verified.backendSocket.setSoTimeout(0);

            // Nối đường ống dữ liệu hai chiều và đo lưu lượng phục vụ Mini AI học kết quả phiên
            final Socket finalTarget = verified.backendSocket;
            final AtomicLong bytesC2T = new AtomicLong(initialData.length);
            final AtomicLong bytesT2C = new AtomicLong(verified.backendReply.length);

            Thread c2t = new Thread(() -> pumpStream(clientSocket, finalTarget, bytesC2T), "Mux-C2T-" + sessionTag);
            Thread t2c = new Thread(() -> pumpStream(finalTarget, clientSocket, bytesT2C), "Mux-T2C-" + sessionTag);
            c2t.setDaemon(true);
            t2c.setDaemon(true);
            c2t.start();
            t2c.start();

            c2t.join();
            t2c.join();

            // Cập nhật kinh nghiệm phiên cho Mini AI nếu phiên đi qua Tầng 3 (Other)
            if (verified.aiLabel != null) {
                long durationMs = System.currentTimeMillis() - sessionStartMs;
                miniAi.recordSessionOutcome(verified.aiLabel, verified.port, durationMs,
                        bytesT2C.get(), bytesC2T.get());
            }

        } catch (Exception e) {
            // Phiên kết nối kết thúc hoặc ngắt
        } finally {
            closeQuietly(clientSocket);
            activeConnections.decrementAndGet();
        }
    }

    // ==========================================
    // TẦNG 1: KIỂM TRA SSH / SHELL (CHỜ PHẢN HỒI 2 GIÂY)
    // ==========================================

    private VerifiedRoute tryShellStage(String sessionTag, byte[] initialData, ProtocolType clientHint) {
        List<Integer> ports = this.shellPorts;
        if (ports == null || ports.isEmpty()) {
            return null;
        }

        // Nếu client đã gửi rõ gói tin Web (HTTP/HTTPS) hoặc Minecraft,
        // Tầng SSH chắc chắn không tương thích → bỏ qua ngay sang Tầng 2 để không tốn 2s cho mỗi file ảnh/CSS.
        if (clientHint == ProtocolType.HTTP || clientHint == ProtocolType.HTTPS_TLS
                || clientHint == ProtocolType.MINECRAFT) {
            return null;
        }

        for (int port : ports) {
            Socket backend = tryConnectTarget(port);
            if (backend == null) {
                continue;
            }

            try {
                if (initialData.length > 0) {
                    OutputStream out = backend.getOutputStream();
                    out.write(initialData);
                    out.flush();
                }

                // Chờ tối đa 2 giây (sshCheckTimeoutMs) phản hồi xác nhận từ SSH/Shell server
                ProbeReadResult reply = readBackendVerification(backend, sshCheckTimeoutMs);
                if (!reply.eof && reply.data.length > 0
                        && ProtocolSniffer.isShellResponse(reply.data, reply.data.length)) {
                    String banner = new String(reply.data, 0, Math.min(reply.data.length, 24)).trim();
                    return new VerifiedRoute(port, backend, reply.data,
                            "Tầng 1: SSH/Shell xác nhận (" + banner + ")", null);
                }

                // Không có dữ liệu xác nhận SSH hợp lệ → đóng thử nghiệm này
                closeQuietly(backend);
            } catch (IOException e) {
                closeQuietly(backend);
            }
        }

        return null;
    }

    // ==========================================
    // TẦNG 2: KIỂM TRA HTTP / HTTPS (XÁC NHẬN PHẢN HỒI WEB)
    // ==========================================

    private VerifiedRoute tryWebStage(String sessionTag, byte[] initialData, ProtocolType clientHint) {
        List<Integer> ports = this.webPorts;
        if (ports == null || ports.isEmpty()) {
            return null;
        }

        // Nếu client gửi rõ SSH hoặc Minecraft hoặc vẫn 0 byte (HTTP server không bao giờ trả lời 0 byte)
        if (clientHint == ProtocolType.SSH || clientHint == ProtocolType.MINECRAFT
                || initialData.length == 0) {
            return null;
        }

        int slowAliveHttpPort = -1;

        for (int port : ports) {
            Socket backend = tryConnectTarget(port);
            if (backend == null) {
                continue;
            }

            try {
                OutputStream out = backend.getOutputStream();
                out.write(initialData);
                out.flush();

                ProbeReadResult reply = readBackendVerification(backend, webCheckTimeoutMs);
                if (reply.data.length > 0) {
                    boolean validWeb = (clientHint == ProtocolType.HTTPS_TLS)
                            ? ProtocolSniffer.isTlsResponse(reply.data, reply.data.length)
                            : ProtocolSniffer.isHttpResponse(reply.data, reply.data.length);

                    if (validWeb) {
                        String protoLabel = (clientHint == ProtocolType.HTTPS_TLS) ? "HTTPS/TLS" : "HTTP";
                        return new VerifiedRoute(port, backend, reply.data,
                                "Tầng 2: " + protoLabel + " xác nhận có dữ liệu", null);
                    }
                    // Backend trả lời nhưng không phải HTTP/TLS (vd trả nhầm SSH/rác) → thử cổng web tiếp
                    closeQuietly(backend);
                } else if (!reply.eof && reply.timedOut && clientHint == ProtocolType.HTTP) {
                    // API web xử lý chậm (>2.5s) nhưng socket vẫn mở sạch sẽ: ghi nhớ để không rơi nhầm sang Other
                    if (slowAliveHttpPort == -1) {
                        slowAliveHttpPort = port;
                    }
                    closeQuietly(backend);
                } else {
                    closeQuietly(backend);
                }
            } catch (IOException e) {
                closeQuietly(backend);
            }
        }

        // Fallback an toàn cho API HTTP xử lý chậm quá 2.5s (chỉ khi client 100% là HTTP)
        if (slowAliveHttpPort > 0) {
            Socket backend = tryConnectTarget(slowAliveHttpPort);
            if (backend != null) {
                try {
                    OutputStream out = backend.getOutputStream();
                    out.write(initialData);
                    out.flush();
                    return new VerifiedRoute(slowAliveHttpPort, backend, new byte[0],
                            "Tầng 2: HTTP (giữ kết nối xử lý chậm)", null);
                } catch (IOException e) {
                    closeQuietly(backend);
                }
            }
        }

        return null;
    }

    // ==========================================
    // TẦNG 3: NHÓM OTHER (ROUND-ROBIN + MINI AI)
    // ==========================================

    private VerifiedRoute tryOtherStage(String sessionTag, byte[] initialData, ProtocolType clientHint) {
        List<Integer> ports = this.otherPorts;
        if (ports == null || ports.isEmpty()) {
            return null;
        }

        // 1. Gọi Mini AI phân loại gói tin (Offline 100% mặc định, Online fallback nếu bật)
        MiniAiEngine.AiClassification aiClass = miniAi.classify(initialData);

        // 2. Xoay vòng Round-Robin cho đúng Session này, sau đó để AI sắp xếp thích ứng nếu có cổng lỗi nặng
        List<Integer> rotated = buildRoundRobinList(ports);
        List<Integer> candidates = miniAi.applyAdaptiveOrder(aiClass.label, rotated);

        int silentAliveFallbackPort = -1;

        for (int port : candidates) {
            Socket backend = tryConnectTarget(port);
            if (backend == null) {
                continue;
            }

            try {
                if (initialData.length > 0) {
                    OutputStream out = backend.getOutputStream();
                    out.write(initialData);
                    out.flush();
                }

                ProbeReadResult reply = readBackendVerification(backend, OTHER_VERIFY_TIMEOUT_MS);

                if (reply.eof && reply.data.length == 0) {
                    // Backend đóng kết nối ngay lập tức không phản hồi → thử cổng Round-Robin tiếp theo
                    miniAi.recordVerificationFailure(aiClass.label, port);
                    closeQuietly(backend);
                    continue;
                }

                if (reply.data.length > 0) {
                    MiniAiEngine.BackendVerdict verdict =
                            miniAi.verifyBackendResponse(aiClass, reply.data, reply.data.length);

                    if (verdict == MiniAiEngine.BackendVerdict.MISMATCH_REJECT) {
                        // AI phát hiện nối nhầm dịch vụ (ví dụ trả về HTTP/SSH cho gói game)
                        miniAi.recordVerificationFailure(aiClass.label, port);
                        closeQuietly(backend);
                        continue;
                    }

                    // Backend phản hồi dữ liệu hợp lệ!
                    miniAi.recordVerificationSuccess(aiClass.label, port);
                    String info = String.format(Locale.ROOT,
                            "Tầng 3: Other [%s|%s %.0f%%] xác nhận phản hồi",
                            aiClass.label, aiClass.source, aiClass.confidence * 100);
                    return new VerifiedRoute(port, backend, reply.data, info, aiClass.label);
                }

                // Backend không đóng socket nhưng im lặng trong lúc chờ xác minh
                if (!reply.eof && reply.timedOut) {
                    if (!aiClass.expectsImmediateReply) {
                        // Giao thức dạng stream thụ động: giữ nguyên socket luôn
                        String info = String.format(Locale.ROOT,
                                "Tầng 3: Other [%s|%s %.0f%%] giữ luồng",
                                aiClass.label, aiClass.source, aiClass.confidence * 100);
                        return new VerifiedRoute(port, backend, new byte[0], info, aiClass.label);
                    } else {
                        // Giao thức cần phản hồi ngay (vd Minecraft Ping): ghi nhớ dự phòng, thử nốt các cổng còn lại
                        if (silentAliveFallbackPort == -1) {
                            silentAliveFallbackPort = port;
                        }
                        closeQuietly(backend);
                    }
                } else {
                    closeQuietly(backend);
                }

            } catch (IOException e) {
                miniAi.recordVerificationFailure(aiClass.label, port);
                closeQuietly(backend);
            }
        }

        // Nếu không cổng nào trả lời chủ động nhưng có cổng mở im lặng (vd Minecraft login bị trễ)
        if (silentAliveFallbackPort > 0) {
            Socket backend = tryConnectTarget(silentAliveFallbackPort);
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
                    return new VerifiedRoute(silentAliveFallbackPort, backend, new byte[0],
                            info, aiClass.label);
                } catch (IOException e) {
                    closeQuietly(backend);
                }
            }
        }

        return null;
    }

    // ==========================================
    // ROUND-ROBIN & TIỆN ÍCH ĐỌC/NỐI MẠNG
    // ==========================================

    private List<Integer> buildRoundRobinList(List<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return new ArrayList<>();
        }
        int size = ports.size();
        int startIndex = Math.floorMod(roundRobinCounter.getAndIncrement(), size);
        List<Integer> rotated = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rotated.add(ports.get((startIndex + i) % size));
        }
        return rotated;
    }

    /**
     * Đọc dữ liệu mồi từ Client với cơ chế chống phân mảnh TCP qua proxy/FRP.
     */
    private byte[] peekInitialBytes(Socket clientSocket) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];

        try {
            clientSocket.setSoTimeout(PEEK_TIMEOUT_MS);
            InputStream in = clientSocket.getInputStream();

            int read = in.read(buffer);
            if (read <= 0) {
                return baos.toByteArray();
            }
            baos.write(buffer, 0, read);

            // Chống phân mảnh TCP: nếu gói đầu tiên quá ngắn (<16 byte), đọc thêm tối đa vài lần ngắn
            long deadline = System.currentTimeMillis() + 250;
            while (baos.size() < 16 && System.currentTimeMillis() < deadline) {
                clientSocket.setSoTimeout(FRAGMENT_WAIT_MS);
                int more = in.read(buffer);
                if (more <= 0) break;
                baos.write(buffer, 0, more);
            }

            byte[] current = baos.toByteArray();
            // Nếu là HTTP mà header chưa hoàn chỉnh (\r\n\r\n), gom thêm để backend HTTP trả lời ngay
            if (ProtocolSniffer.isHttp(current, current.length)
                    && !ProtocolSniffer.isCompleteHttpHeader(current, current.length)) {
                long httpDeadline = System.currentTimeMillis() + 200;
                while (baos.size() < BUFFER_SIZE && System.currentTimeMillis() < httpDeadline) {
                    clientSocket.setSoTimeout(60);
                    int more = in.read(buffer);
                    if (more <= 0) break;
                    baos.write(buffer, 0, more);
                    current = baos.toByteArray();
                    if (ProtocolSniffer.isCompleteHttpHeader(current, current.length)) {
                        break;
                    }
                }
            } else {
                // Nếu là Minecraft Handshake mà chưa có Packet 2 (Status Request / Login Start), đợi thêm 80ms
                ProtocolSniffer.MinecraftHandshakeInfo mc =
                        ProtocolSniffer.inspectMinecraftHandshake(current, current.length);
                if (mc != null && !mc.hasExtraPacketBytes) {
                    clientSocket.setSoTimeout(80);
                    int more = in.read(buffer);
                    if (more > 0) {
                        baos.write(buffer, 0, more);
                    }
                }
            }
        } catch (SocketTimeoutException e) {
            // Hết thời gian chờ mồi/phân mảnh là bình thường
        } catch (IOException ignored) {}

        return baos.toByteArray();
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

    /**
     * Đọc phản hồi xác minh từ backend trong khoảng timeout chỉ định.
     */
    private ProbeReadResult readBackendVerification(Socket backend, int timeoutMs) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            backend.setSoTimeout(timeoutMs);
            InputStream in = backend.getInputStream();
            int read = in.read(buffer);
            if (read == -1) {
                return new ProbeReadResult(new byte[0], true, false);
            }
            byte[] reply = new byte[read];
            System.arraycopy(buffer, 0, reply, 0, read);
            return new ProbeReadResult(reply, false, false);
        } catch (SocketTimeoutException e) {
            return new ProbeReadResult(new byte[0], false, true);
        } catch (IOException e) {
            return new ProbeReadResult(new byte[0], true, false);
        }
    }

    private Socket tryConnectTarget(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), PROBE_CONNECT_TIMEOUT_MS);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
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

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }

    // ==========================================
    // GETTERS
    // ==========================================

    public int getActiveConnections() { return activeConnections.get(); }
    public String getName() { return name; }
    public boolean isRunning() { return running; }
    public int getActualBoundPort() { return actualBoundPort; }
    public String getActualBoundAddress() { return actualBoundAddress; }
}
