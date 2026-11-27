package com.mjt.core.network;

import com.mjt.core.ui.OutputLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ chuyển tiếp TCP (TCP Port Forwarder).
 * Lắng nghe trên cổng công khai và chuyển tiếp mọi kết nối TCP đến cổng nội bộ.
 *
 * Kiến trúc luồng:
 *   - 1 luồng Accept (chấp nhận kết nối mới)
 *   - 2 luồng mỗi kết nối (Client→Target, Target→Client)
 */
public class TcpForwarder {

    private final String name;
    private final int listenPort;
    private final int targetPort;
    private final String bindAddress;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    // Kích thước bộ đệm sao chép (Buffer Size)
    private static final int BUFFER_SIZE = 8192;

    public TcpForwarder(String name, int listenPort, int targetPort, String bindAddress) {
        this.name = name;
        this.listenPort = listenPort;
        this.targetPort = targetPort;
        this.bindAddress = bindAddress;
    }

    /**
     * Khởi động bộ chuyển tiếp TCP trên luồng nền
     */
    public void start() throws IOException {
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MJT-TCP-" + name);
            t.setDaemon(true);
            return t;
        });

        this.serverSocket = new ServerSocket(listenPort, 50, InetAddress.getByName(bindAddress));
        this.running = true;

        threadPool.submit(this::acceptLoop);
        OutputLogger.success(String.format("TCP Forwarder [%s]: %s:%d → localhost:%d",
            name, bindAddress, listenPort, targetPort));
    }

    /**
     * Dừng bộ chuyển tiếp và giải phóng tài nguyên
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
        OutputLogger.info("TCP Forwarder [" + name + "] đã dừng. Tổng kết nối đang hoạt động: " + activeConnections.get());
    }

    // ==========================================
    // VÒNG LẶP CHẤP NHẬN KẾT NỐI
    // ==========================================

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleConnection(clientSocket));
            } catch (SocketException e) {
                if (running) {
                    OutputLogger.error("TCP [" + name + "] Lỗi chấp nhận kết nối: " + e.getMessage());
                }
                // SocketException khi serverSocket bị đóng → thoát vòng lặp
                break;
            } catch (IOException e) {
                if (running) {
                    OutputLogger.error("TCP [" + name + "] Lỗi I/O: " + e.getMessage());
                }
            }
        }
    }

    // ==========================================
    // XỬ LÝ MỘT KẾT NỐI (BI-DIRECTIONAL PIPE)
    // ==========================================

    private void handleConnection(Socket clientSocket) {
        activeConnections.incrementAndGet();
        String clientAddr = clientSocket.getRemoteSocketAddress().toString();

        try {
            // Kết nối đến cổng nội bộ (Target)
            Socket targetSocket = new Socket("127.0.0.1", targetPort);

            // Tạo 2 bơm luồng song song (Bidirectional Pump)
            Thread clientToTarget = new Thread(() ->
                pumpStream(clientSocket, targetSocket, "→"), "MJT-Pipe-" + name + "-C2T");
            Thread targetToClient = new Thread(() ->
                pumpStream(targetSocket, clientSocket, "←"), "MJT-Pipe-" + name + "-T2C");

            clientToTarget.setDaemon(true);
            targetToClient.setDaemon(true);
            clientToTarget.start();
            targetToClient.start();

            // Chờ một trong hai hướng đóng kết nối
            clientToTarget.join();
            targetToClient.join();

        } catch (IOException e) {
            OutputLogger.warning("TCP [" + name + "] Không thể kết nối đến localhost:" + targetPort
                + " (Dịch vụ nội bộ chưa khởi động?)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(clientSocket);
            activeConnections.decrementAndGet();
        }
    }

    /**
     * Bơm dữ liệu từ nguồn sang đích cho đến khi luồng đóng
     */
    private void pumpStream(Socket source, Socket target, String direction) {
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
            // Kết nối bị đóng bởi phía bên kia → bình thường
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

    public int getActiveConnections() { return activeConnections.get(); }
    public String getName() { return name; }
    public boolean isRunning() { return running; }
}
