package com.mjt.core.network.multiplexer;

import com.mjt.core.ui.ConsoleColors;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ dồn kênh và định tuyến thông minh trên MỘT CỔNG DUY NHẤT (Smart Port
 * Multiplexer).
 *
 * Tính năng chính:
 * 1. Lắng nghe trên 1 cổng công khai (ví dụ 20002).
 * 2. Tự động nhận diện giao thức (Minecraft, HTTP, WebSocket, SSH...) từ gói
 * tin đầu tiên.
 * 3. Phân cấp mức độ ưu tiên (Priority Probing Chain):
 * - Thử kết nối dịch vụ ưu tiên cao nhất tương ứng với giao thức phát hiện
 * được.
 * - Nếu dịch vụ đó chưa khởi động hoặc từ chối (Connection Refused),
 * tự động rơi xuống (Fallback) các dịch vụ tiếp theo trong chuỗi ưu tiên đã cấu
 * hình.
 * - Khi kết nối thành công tới bất kỳ cổng nào thì dừng kiểm tra và bàn giao
 * luồng ngay lập tức.
 * 4. Bảo toàn dữ liệu (Zero Data Loss): Các byte đã đọc mồi (peek bytes) được
 * phát lại đầy đủ cho backend.
 */
public class SmartMultiplexer {

    private final String name;
    private final int listenPort;
    private final String bindAddress;
    private final List<MultiplexRule> rules;
    private final List<Integer> fallbackChain;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    // Kích thước bộ đệm và thời gian chờ đọc dữ liệu mồi
    private static final int BUFFER_SIZE = 8192;
    private static final int PEEK_TIMEOUT_MS = 1500; // Chờ tối đa 1.5 giây để client gửi gói tin đầu tiên
    private static final int PROBE_TIMEOUT_MS = 400; // Timeout thử kết nối tới từng cổng nội bộ

    public SmartMultiplexer(String name, int listenPort, String bindAddress,
            List<MultiplexRule> rules, List<Integer> fallbackChain) {
        this.name = name;
        this.listenPort = listenPort;
        this.bindAddress = bindAddress;
        this.rules = rules != null ? rules : new ArrayList<>();
        this.fallbackChain = fallbackChain != null ? fallbackChain : new ArrayList<>();
    }

    /** Port thực tế đã bind thành công (có thể khác listenPort nếu auto-detect) */
    private int actualBoundPort = -1;
    private String actualBoundAddress = null;

    /**
     * Khởi động máy chủ Smart Multiplexer.
     * Hỗ trợ Smart Bind: tự động thử nhiều cổng và địa chỉ nếu cổng cấu hình bị từ
     * chối.
     */
    public void start() throws IOException {
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MJT-Mux-" + name);
            t.setDaemon(true);
            return t;
        });

        // Xây dựng danh sách cổng cần thử bind
        List<Integer> portsToTry = new ArrayList<>();
        portsToTry.add(listenPort);

        // Tự động phát hiện port từ biến môi trường (Pterodactyl, Docker, ...)
        int envPort = detectPortFromEnvironment();
        if (envPort > 0 && envPort != listenPort) {
            portsToTry.add(0, envPort); // Ưu tiên port từ env var lên đầu
        }

        // Thêm port phổ biến để fallback nếu cần
        int[] commonFallbackPorts = { 25565, 8080, 3000, 9090 };
        for (int p : commonFallbackPorts) {
            if (!portsToTry.contains(p)) {
                portsToTry.add(p);
            }
        }

        // Xây dựng danh sách địa chỉ cần thử
        List<String> addressesToTry = new ArrayList<>();
        addressesToTry.add(bindAddress);
        if (!"0.0.0.0".equals(bindAddress) && !"127.0.0.1".equals(bindAddress)) {
            addressesToTry.add("0.0.0.0");
        }
        if (!"127.0.0.1".equals(bindAddress)) {
            addressesToTry.add("127.0.0.1");
        }

        // Smart Bind: thử lần lượt (port × address) cho đến khi thành công
        IOException lastError = null;
        for (int port : portsToTry) {
            for (String addr : addressesToTry) {
                try {
                    InetAddress bind = InetAddress.getByName(addr);
                    this.serverSocket = new ServerSocket(port, 50, bind);
                    this.actualBoundPort = port;
                    this.actualBoundAddress = addr;

                    // Bind thành công!
                    if (port != listenPort || !addr.equals(bindAddress)) {
                        OutputLogger.warning(String.format(
                                "Smart Bind: Không thể mở %s:%d, đã tự động chuyển sang %s:%d",
                                bindAddress, listenPort, addr, port));
                    }
                    break;
                } catch (BindException e) {
                    lastError = e;
                    // Port/address bị từ chối, thử tiếp
                } catch (IOException e) {
                    lastError = e;
                    // "Cannot assign requested address" hoặc lỗi khác, thử tiếp
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

        OutputLogger.success(String.format("Smart Multiplexer [%s] đang mở trên %s:%d",
                name, actualBoundAddress, actualBoundPort));
        OutputLogger.info("  [Quy tắc nhận diện ưu tiên]:");
        for (MultiplexRule rule : rules) {
            if (rule.isEnabled()) {
                OutputLogger.info(String.format("    - Giao thức %s → localhost:%d",
                        rule.getProtocol().getDescription(), rule.getDefaultTargetPort()));
                for (java.util.Map.Entry<String, Integer> hr : rule.getHostRoutes().entrySet()) {
                    OutputLogger.info(String.format("      + Host '%s' → localhost:%d", hr.getKey(), hr.getValue()));
                }
                for (java.util.Map.Entry<String, Integer> pr : rule.getPathRoutes().entrySet()) {
                    OutputLogger.info(String.format("      + Path '%s' → localhost:%d", pr.getKey(), pr.getValue()));
                }
            }
        }
        if (!fallbackChain.isEmpty()) {
            OutputLogger.info("  [Chuỗi ưu tiên thử kết nối Fallback]: " + fallbackChain);
        }
    }

    /**
     * Tự động phát hiện cổng được phân bổ từ biến môi trường.
     * Pterodactyl: SERVER_PORT, P_SERVER_PORT
     * Docker/Generic: PORT, PRIMARY_PORT, ALLOCATED_PORT
     */
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
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    /**
     * Dừng Multiplexer và giải phóng tài nguyên
     */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }

        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        OutputLogger.info("Smart Multiplexer [" + name + "] đã dừng.");
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
    // XỬ LÝ VÀ PHÂN LUỒNG KẾT NỐI (CORE DISPATCHER)
    // ==========================================

    private void handleClient(Socket clientSocket) {
        activeConnections.incrementAndGet();
        String clientInfo = clientSocket.getRemoteSocketAddress().toString();

        try {
            // Bước 1: Đọc mồi dữ liệu ban đầu từ Client mà không làm mất byte nào
            byte[] initialData = peekInitialBytes(clientSocket);

            // Bước 2: Nhận diện giao thức và phân tích chi tiết (Host / Path)
            ProtocolType detectedProtocol = ProtocolSniffer.detect(initialData, initialData.length);
            String host = null;
            String path = null;

            if (detectedProtocol == ProtocolType.MINECRAFT) {
                host = ProtocolSniffer.extractMinecraftHostname(initialData, initialData.length);
            } else if (detectedProtocol == ProtocolType.HTTP) {
                host = ProtocolSniffer.extractHttpHost(initialData, initialData.length);
                path = ProtocolSniffer.extractHttpPath(initialData, initialData.length);
            }

            // Bước 3: Xây dựng danh sách cổng ứng viên theo thứ tự ưu tiên (Priority
            // Candidate Chain)
            List<Integer> candidatePorts = buildCandidatePorts(detectedProtocol, host, path);

            // Bước 4: Thử kết nối lần lượt theo mức độ ưu tiên (Active Probing / Fallback)
            Socket targetSocket = null;
            int connectedPort = -1;

            for (int candidatePort : candidatePorts) {
                targetSocket = tryConnectTarget(candidatePort);
                if (targetSocket != null) {
                    connectedPort = candidatePort;
                    break; // ĐÃ KẾT NỐI THÀNH CÔNG! DỪNG VIỆC KIỂM TRA
                }
            }

            if (targetSocket == null) {
                OutputLogger.warning(String.format(
                        "Mux [%s] Từ chối kết nối từ %s: Đã thử toàn bộ danh sách cổng %s nhưng không dịch vụ nội bộ nào phản hồi.",
                        name, clientInfo, candidatePorts));
                closeQuietly(clientSocket);
                return;
            }

            OutputLogger.info(String.format(
                    "Mux [%s] %s (%s) → Kết nối thành công localhost:%d",
                    name, clientInfo, detectedProtocol.getDescription(), connectedPort));

            // Bước 5: Phát lại toàn bộ dữ liệu mồi vào dịch vụ backend đã chọn
            if (initialData.length > 0) {
                OutputStream targetOut = targetSocket.getOutputStream();
                targetOut.write(initialData);
                targetOut.flush();
            }

            // Reset timeout của client socket để phiên làm việc diễn ra bình thường
            clientSocket.setSoTimeout(0);

            // Bước 6: Nối đường ống dữ liệu hai chiều (Bi-directional Pipe)
            final Socket finalTarget = targetSocket;
            Thread c2t = new Thread(() -> pumpStream(clientSocket, finalTarget), "Mux-Pipe-C2T");
            Thread t2c = new Thread(() -> pumpStream(finalTarget, clientSocket), "Mux-Pipe-T2C");
            c2t.setDaemon(true);
            t2c.setDaemon(true);
            c2t.start();
            t2c.start();

            c2t.join();
            t2c.join();

        } catch (Exception e) {
            // Lỗi đóng phiên làm việc hoặc ngắt kết nối
        } finally {
            closeQuietly(clientSocket);
            activeConnections.decrementAndGet();
        }
    }

    /**
     * Đọc mồi các byte đầu tiên từ client trong thời gian chờ ngắn
     */
    private byte[] peekInitialBytes(Socket clientSocket) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];

        try {
            clientSocket.setSoTimeout(PEEK_TIMEOUT_MS);
            InputStream in = clientSocket.getInputStream();

            int read = in.read(buffer);
            if (read > 0) {
                baos.write(buffer, 0, read);
            }
        } catch (SocketTimeoutException e) {
            // Client không gửi gì trong thời gian mồi (ví dụ: SSH server gửi trước, hoặc
            // kết nối chờ)
        } catch (IOException ignored) {
        }

        return baos.toByteArray();
    }

    /**
     * Xây dựng danh sách các cổng cần thử kết nối theo thứ tự ưu tiên
     */
    private List<Integer> buildCandidatePorts(ProtocolType protocol, String host, String path) {
        Set<Integer> candidates = new LinkedHashSet<>();

        // 1. Ưu tiên cao nhất: Cổng được chỉ định rõ bởi quy tắc khớp với Protocol
        for (MultiplexRule rule : rules) {
            if (rule.isEnabled() && rule.getProtocol() == protocol) {
                int resolvedPort = rule.resolveTargetPort(host, path);
                if (resolvedPort > 0) {
                    candidates.add(resolvedPort);
                }
            }
        }

        // 2. Nếu là UNKNOWN (client không gửi mồi hoặc giao thức lạ):
        // Thử lần lượt các cổng mặc định của từng quy tắc đã bật
        if (protocol == ProtocolType.UNKNOWN) {
            for (MultiplexRule rule : rules) {
                if (rule.isEnabled() && rule.getDefaultTargetPort() > 0) {
                    candidates.add(rule.getDefaultTargetPort());
                }
            }
        }

        // 3. Chuỗi Fallback do người dùng cấu hình rõ ràng trong network-config.json
        for (int fallbackPort : fallbackChain) {
            if (fallbackPort > 0) {
                candidates.add(fallbackPort);
            }
        }

        return new ArrayList<>(candidates);
    }

    /**
     * Thử kết nối đến cổng nội bộ (Probe).
     * 
     * @return Socket nếu kết nối thành công, null nếu cổng chưa mở hoặc từ chối.
     */
    private Socket tryConnectTarget(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            return null; // Kết nối thất bại -> Chuyển sang cổng tiếp theo
        }
    }

    /**
     * Bơm luồng dữ liệu hai chiều
     */
    private void pumpStream(Socket source, Socket target) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            InputStream in = source.getInputStream();
            OutputStream out = target.getOutputStream();
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
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
        } catch (IOException ignored) {
        }
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }

    public String getName() {
        return name;
    }

    public boolean isRunning() {
        return running;
    }
}
