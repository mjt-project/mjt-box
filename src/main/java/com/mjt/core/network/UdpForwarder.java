package com.mjt.core.network;

import com.mjt.core.ui.OutputLogger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bộ chuyển tiếp UDP (UDP Port Forwarder).
 * Lắng nghe trên cổng công khai và chuyển tiếp gói tin UDP đến cổng nội bộ.
 *
 * Cơ chế: Theo dõi phiên (Session Tracking)
 *   - Mỗi địa chỉ nguồn (client) được ánh xạ với một DatagramSocket riêng để nhận phản hồi.
 *   - Phiên tự động hết hạn sau thời gian không hoạt động (Session Timeout).
 */
public class UdpForwarder {

    private final String name;
    private final int listenPort;
    private final int targetPort;
    private final String bindAddress;

    private DatagramSocket publicSocket;
    private ExecutorService threadPool;
    private volatile boolean running;

    // Bảng phiên: Client Address → Relay Socket (dùng để nhận phản hồi từ target)
    private final Map<String, UdpSession> sessionMap = new ConcurrentHashMap<>();

    private static final int BUFFER_SIZE = 65535; // Kích thước tối đa gói UDP
    private static final long SESSION_TIMEOUT_MS = 60_000; // 60 giây

    public UdpForwarder(String name, int listenPort, int targetPort, String bindAddress) {
        this.name = name;
        this.listenPort = listenPort;
        this.targetPort = targetPort;
        this.bindAddress = bindAddress;
    }

    /**
     * Khởi động bộ chuyển tiếp UDP
     */
    public void start() throws IOException {
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MJT-UDP-" + name);
            t.setDaemon(true);
            return t;
        });

        InetAddress bind = InetAddress.getByName(bindAddress);
        this.publicSocket = new DatagramSocket(new InetSocketAddress(bind, listenPort));
        this.running = true;

        // Luồng nhận gói từ client
        threadPool.submit(this::receiveLoop);

        // Luồng dọn dẹp phiên hết hạn
        threadPool.submit(this::sessionCleanupLoop);

        OutputLogger.success(String.format("UDP Forwarder [%s]: %s:%d → localhost:%d",
            name, bindAddress, listenPort, targetPort));
    }

    /**
     * Dừng bộ chuyển tiếp UDP
     */
    public void stop() {
        running = false;
        if (publicSocket != null && !publicSocket.isClosed()) {
            publicSocket.close();
        }
        // Đóng tất cả relay socket
        for (UdpSession session : sessionMap.values()) {
            session.close();
        }
        sessionMap.clear();

        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        OutputLogger.info("UDP Forwarder [" + name + "] đã dừng.");
    }

    // ==========================================
    // VÒNG LẶP NHẬN GÓI TIN (PUBLIC SOCKET)
    // ==========================================

    private void receiveLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                publicSocket.receive(packet);

                // Xác định phiên dựa trên địa chỉ nguồn
                String clientKey = packet.getAddress().getHostAddress() + ":" + packet.getPort();
                UdpSession session = sessionMap.computeIfAbsent(clientKey, k -> {
                    try {
                        return createSession(packet.getAddress(), packet.getPort());
                    } catch (IOException e) {
                        OutputLogger.error("UDP [" + name + "] Không thể tạo phiên relay: " + e.getMessage());
                        return null;
                    }
                });

                if (session == null) continue;

                session.updateLastActivity();

                // Chuyển tiếp gói đến cổng nội bộ
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());

                DatagramPacket forwardPacket = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName("127.0.0.1"), targetPort
                );
                session.getRelaySocket().send(forwardPacket);

            } catch (SocketException e) {
                if (running) {
                    OutputLogger.error("UDP [" + name + "] Socket bị đóng bất ngờ.");
                }
                break;
            } catch (IOException e) {
                if (running) {
                    OutputLogger.error("UDP [" + name + "] Lỗi I/O: " + e.getMessage());
                }
            }
        }
    }

    // ==========================================
    // TẠO PHIÊN RELAY (NHẬN PHẢN HỒI TỪ TARGET)
    // ==========================================

    private UdpSession createSession(InetAddress clientAddr, int clientPort) throws IOException {
        DatagramSocket relaySocket = new DatagramSocket();
        relaySocket.setSoTimeout(5000);

        UdpSession session = new UdpSession(relaySocket, clientAddr, clientPort);

        // Luồng nền nhận phản hồi từ target và gửi ngược về client
        threadPool.submit(() -> relayResponseLoop(session));

        return session;
    }

    /**
     * Nhận phản hồi từ cổng nội bộ (target) và gửi ngược về client qua publicSocket
     */
    private void relayResponseLoop(UdpSession session) {
        byte[] buffer = new byte[BUFFER_SIZE];

        while (running && !session.getRelaySocket().isClosed()) {
            try {
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                session.getRelaySocket().receive(response);

                // Gửi ngược về client thông qua socket công khai
                byte[] data = new byte[response.getLength()];
                System.arraycopy(response.getData(), response.getOffset(), data, 0, response.getLength());

                DatagramPacket clientPacket = new DatagramPacket(
                    data, data.length,
                    session.getClientAddress(), session.getClientPort()
                );
                publicSocket.send(clientPacket);

            } catch (java.net.SocketTimeoutException ignored) {
                // Timeout đọc → kiểm tra phiên có hết hạn không rồi lặp lại
            } catch (IOException e) {
                if (running) break;
            }
        }
    }

    // ==========================================
    // DỌN DẸP PHIÊN HẾT HẠN (SESSION CLEANUP)
    // ==========================================

    private void sessionCleanupLoop() {
        while (running) {
            try {
                Thread.sleep(10_000); // Quét mỗi 10 giây
            } catch (InterruptedException e) {
                break;
            }

            long now = System.currentTimeMillis();
            sessionMap.entrySet().removeIf(entry -> {
                UdpSession session = entry.getValue();
                if (now - session.getLastActivity() > SESSION_TIMEOUT_MS) {
                    session.close();
                    return true;
                }
                return false;
            });
        }
    }

    public String getName() { return name; }
    public boolean isRunning() { return running; }

    // ==========================================
    // LỚP NỘI BỘ: PHIÊN UDP (UDP Session)
    // ==========================================

    private static class UdpSession {
        private final DatagramSocket relaySocket;
        private final InetAddress clientAddress;
        private final int clientPort;
        private volatile long lastActivity;

        UdpSession(DatagramSocket relaySocket, InetAddress clientAddress, int clientPort) {
            this.relaySocket = relaySocket;
            this.clientAddress = clientAddress;
            this.clientPort = clientPort;
            this.lastActivity = System.currentTimeMillis();
        }

        void updateLastActivity() { this.lastActivity = System.currentTimeMillis(); }
        long getLastActivity() { return lastActivity; }
        DatagramSocket getRelaySocket() { return relaySocket; }
        InetAddress getClientAddress() { return clientAddress; }
        int getClientPort() { return clientPort; }

        void close() {
            if (!relaySocket.isClosed()) {
                relaySocket.close();
            }
        }
    }
}
