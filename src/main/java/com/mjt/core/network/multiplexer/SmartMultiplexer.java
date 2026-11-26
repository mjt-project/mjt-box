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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Smart Port Multiplexer v2 — Bộ dồn kênh thông minh trên MỘT CỔNG DUY NHẤT.
 *
 * Kiến trúc mới: TÁCH BIỆT 3 NHÓM CỔNG
 *
 *   ┌─────────────────────────────────────────────┐
 *   │         KẾT NỐI TỪ BÊN NGOÀI               │
 *   │         (node11.zampto.net:28029)            │
 *   └──────────────────┬──────────────────────────┘
 *                      │ peek bytes
 *                      ▼
 *   ┌──────────────────────────────────────────────┐
 *   │         PROTOCOL SNIFFER (Nhận diện)         │
 *   │  HTTP? │ SSH? │ UNKNOWN (MC, game, v.v.)?    │
 *   └───┬────┴───┬──┴──────────┬───────────────────┘
 *       │        │             │
 *       ▼        ▼             ▼
 *   ┌───────┐ ┌───────┐ ┌──────────────────┐
 *   │ WEB   │ │ SHELL │ │   OTHER PORTS    │
 *   │ PORTS │ │ PORTS │ │  (Round-Robin)   │
 *   │ 8080  │ │ 2222  │ │  25565 → 25566   │
 *   │ 8081  │ │       │ │  → 25567 → ...   │
 *   └───────┘ └───────┘ └──────────────────┘
 *
 * Nguyên tắc:
 *   1. HTTP/HTTPS/WebSocket → CHỈ thử web_ports (KHÔNG BAO GIỜ nhảy sang nhóm khác)
 *   2. SSH/SFTP/FTP → CHỈ thử shell_ports (KHÔNG BAO GIỜ nhảy sang nhóm khác)
 *   3. UNKNOWN/Minecraft/game → Round-Robin xoay vòng qua other_ports
 *      (Kết nối 1 → thử port[0], kết nối 2 → thử port[1], kết nối 3 → thử port[2]...)
 *   4. Zero Data Loss: byte đã peek được phát lại đầy đủ cho backend
 *   5. Hot-Reload: Danh sách port có thể cập nhật realtime mà không cần restart
 */
public class SmartMultiplexer {

    private final String name;
    private final int listenPort;
    private final String bindAddress;

    // ===== 3 NHÓM CỔNG TÁCH BIỆT (volatile cho hot-reload thread-safe) =====
    private volatile List<Integer> webPorts;    // HTTP, HTTPS, WebSocket
    private volatile List<Integer> shellPorts;  // SSH, SFTP, FTP
    private volatile List<Integer> otherPorts;  // Minecraft, game server, dịch vụ lạ

    // Round-Robin counter cho nhóm "other" (xoay vòng mỗi kết nối mới)
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    // Port/Address thực tế sau Smart Bind
    private int actualBoundPort = -1;
    private String actualBoundAddress = null;

    // Hằng số
    private static final int BUFFER_SIZE = 8192;
    private static final int PEEK_TIMEOUT_MS = 1500;
    private static final int PROBE_TIMEOUT_MS = 400;

    public SmartMultiplexer(String name, int listenPort, String bindAddress,
                            List<Integer> webPorts, List<Integer> shellPorts,
                            List<Integer> otherPorts) {
        this.name = name;
        this.listenPort = listenPort;
        this.bindAddress = bindAddress;
        this.webPorts = webPorts != null ? new ArrayList<>(webPorts) : new ArrayList<>();
        this.shellPorts = shellPorts != null ? new ArrayList<>(shellPorts) : new ArrayList<>();
        this.otherPorts = otherPorts != null ? new ArrayList<>(otherPorts) : new ArrayList<>();
    }

    // ==========================================
    // HOT-RELOAD: Cập nhật danh sách cổng realtime
    // ==========================================

    /**
     * Cập nhật danh sách cổng ngay lập tức mà KHÔNG cần restart.
     * Thread-safe nhờ volatile reference swap.
     */
    public void updatePortConfig(List<Integer> newWebPorts, List<Integer> newShellPorts,
                                 List<Integer> newOtherPorts) {
        List<Integer> oldWeb = this.webPorts;
        List<Integer> oldShell = this.shellPorts;
        List<Integer> oldOther = this.otherPorts;

        this.webPorts = newWebPorts != null ? new ArrayList<>(newWebPorts) : new ArrayList<>();
        this.shellPorts = newShellPorts != null ? new ArrayList<>(newShellPorts) : new ArrayList<>();
        this.otherPorts = newOtherPorts != null ? new ArrayList<>(newOtherPorts) : new ArrayList<>();

        // Chỉ log nếu thực sự thay đổi
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

    /**
     * Khởi động Smart Multiplexer với Smart Bind (tự động thử nhiều cổng/địa chỉ).
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
            portsToTry.add(0, envPort);
        }

        // Fallback ports phổ biến
        int[] commonPorts = {25565, 8080, 3000, 9090};
        for (int p : commonPorts) {
            if (!portsToTry.contains(p)) {
                portsToTry.add(p);
            }
        }

        // Danh sách địa chỉ cần thử
        List<String> addressesToTry = new ArrayList<>();
        addressesToTry.add(bindAddress);
        if (!"0.0.0.0".equals(bindAddress) && !"127.0.0.1".equals(bindAddress)) {
            addressesToTry.add("0.0.0.0");
        }
        if (!"127.0.0.1".equals(bindAddress)) {
            addressesToTry.add("127.0.0.1");
        }

        // Smart Bind: thử lần lượt cho đến khi thành công
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

        OutputLogger.success(String.format("Smart Multiplexer [%s] đang mở trên %s:%d",
            name, actualBoundAddress, actualBoundPort));
        printPortGroups();
    }

    /**
     * In ra 3 nhóm cổng hiện tại
     */
    private void printPortGroups() {
        OutputLogger.info("  [Nhóm Web   - HTTP/HTTPS/WS]: " + (webPorts.isEmpty() ? "(trống)" : webPorts));
        OutputLogger.info("  [Nhóm Shell - SSH/SFTP/FTP ]: " + (shellPorts.isEmpty() ? "(trống)" : shellPorts));
        OutputLogger.info("  [Nhóm Other - MC/Game/Khác ]: " + (otherPorts.isEmpty() ? "(trống)" : otherPorts)
            + " (Round-Robin)");
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
        } catch (IOException ignored) {}

        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        OutputLogger.info("Smart Multiplexer [" + name + "] đã dừng.");
    }

    /**
     * Tự động phát hiện cổng từ biến môi trường
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
    // CORE DISPATCHER — XỬ LÝ VÀ PHÂN LUỒNG KẾT NỐI
    // ==========================================

    private void handleClient(Socket clientSocket) {
        activeConnections.incrementAndGet();
        String clientInfo = clientSocket.getRemoteSocketAddress().toString();

        try {
            // Bước 1: Đọc mồi dữ liệu ban đầu
            byte[] initialData = peekInitialBytes(clientSocket);

            // Bước 2: Nhận diện giao thức
            ProtocolType detectedProtocol = ProtocolSniffer.detect(initialData, initialData.length);

            // Bước 3: CHỌN NHÓM CỔNG ĐÚNG (KHÔNG LẪN LỘN GIỮA CÁC NHÓM)
            List<Integer> candidatePorts;
            String groupName;

            switch (detectedProtocol) {
                case HTTP:
                case HTTPS_TLS:
                    // ★ HTTP/HTTPS → CHỈ thử web_ports, KHÔNG BAO GIỜ nhảy sang nhóm khác
                    candidatePorts = resolveWebPorts(initialData);
                    groupName = "Web";
                    break;

                case SSH:
                    // ★ SSH → CHỈ thử shell_ports, KHÔNG BAO GIỜ nhảy sang nhóm khác
                    candidatePorts = new ArrayList<>(shellPorts);
                    groupName = "Shell";
                    break;

                case MINECRAFT:
                    // ★ Minecraft → Thử other_ports theo round-robin
                    candidatePorts = buildRoundRobinList(otherPorts);
                    groupName = "Other(MC)";
                    break;

                case UNKNOWN:
                default:
                    // ★ Không nhận diện được → Round-Robin qua other_ports
                    candidatePorts = buildRoundRobinList(otherPorts);
                    groupName = "Other(?)";
                    break;
            }

            // Bước 4: Thử kết nối đến các cổng trong nhóm đã chọn
            Socket targetSocket = null;
            int connectedPort = -1;

            for (int candidatePort : candidatePorts) {
                targetSocket = tryConnectTarget(candidatePort);
                if (targetSocket != null) {
                    connectedPort = candidatePort;
                    break;
                }
            }

            if (targetSocket == null) {
                OutputLogger.warning(String.format(
                    "Mux [%s] ✗ %s [%s/%s] → Không dịch vụ nào phản hồi trong nhóm %s",
                    name, clientInfo, detectedProtocol.getDescription(), groupName, candidatePorts));
                closeQuietly(clientSocket);
                return;
            }

            OutputLogger.info(String.format(
                "Mux [%s] ✓ %s [%s] → localhost:%d (nhóm %s)",
                name, clientInfo, detectedProtocol.getDescription(), connectedPort, groupName));

            // Bước 5: Phát lại dữ liệu mồi
            if (initialData.length > 0) {
                OutputStream targetOut = targetSocket.getOutputStream();
                targetOut.write(initialData);
                targetOut.flush();
            }

            // Reset timeout
            clientSocket.setSoTimeout(0);

            // Bước 6: Nối đường ống hai chiều (Bi-directional Pipe)
            final Socket finalTarget = targetSocket;
            Thread c2t = new Thread(() -> pumpStream(clientSocket, finalTarget), "Mux-C2T");
            Thread t2c = new Thread(() -> pumpStream(finalTarget, clientSocket), "Mux-T2C");
            c2t.setDaemon(true);
            t2c.setDaemon(true);
            c2t.start();
            t2c.start();

            c2t.join();
            t2c.join();

        } catch (Exception e) {
            // Phiên kết nối bị ngắt hoặc lỗi
        } finally {
            closeQuietly(clientSocket);
            activeConnections.decrementAndGet();
        }
    }

    // ==========================================
    // PHÂN GIẢI CỔNG THEO NHÓM
    // ==========================================

    /**
     * Phân giải cổng web — có thể dùng HTTP path/host để route tới port cụ thể
     * trong tương lai. Hiện tại trả về danh sách web_ports theo thứ tự.
     */
    private List<Integer> resolveWebPorts(byte[] initialData) {
        // Hiện tại đơn giản: thử lần lượt theo thứ tự config
        return new ArrayList<>(webPorts);
    }

    /**
     * Xây dựng danh sách Round-Robin cho nhóm "other".
     * Mỗi kết nối mới sẽ bắt đầu thử từ port KHÁC NHAU trong danh sách.
     *
     * Ví dụ: other_ports = [25565, 25566, 25567]
     *   Kết nối 1 → thử 25565, 25566, 25567
     *   Kết nối 2 → thử 25566, 25567, 25565
     *   Kết nối 3 → thử 25567, 25565, 25566
     *   Kết nối 4 → thử 25565, 25566, 25567  (lặp lại)
     */
    private List<Integer> buildRoundRobinList(List<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return new ArrayList<>();
        }

        int size = ports.size();
        int startIndex = roundRobinCounter.getAndIncrement() % size;

        List<Integer> rotated = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rotated.add(ports.get((startIndex + i) % size));
        }
        return rotated;
    }

    // ==========================================
    // TIỆN ÍCH MẠNG
    // ==========================================

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
            // Client không gửi dữ liệu mồi (ví dụ: giao thức server-speaks-first)
        } catch (IOException ignored) {}
        return baos.toByteArray();
    }

    private Socket tryConnectTarget(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            return null;
        }
    }

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
