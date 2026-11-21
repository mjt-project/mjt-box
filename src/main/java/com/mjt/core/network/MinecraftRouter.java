package com.mjt.core.network;

import com.mjt.core.ui.OutputLogger;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ định tuyến Minecraft dựa trên Virtual Host (Minecraft Protocol Router).
 *
 * Cho phép chạy NHIỀU server Minecraft trên MỘT cổng công khai duy nhất.
 * Cơ chế hoạt động:
 *   1. Client kết nối → MJT đọc gói Handshake (Packet ID 0x00)
 *   2. Trích xuất trường "Server Address" từ gói Handshake
 *   3. Đối chiếu với bảng định tuyến (Route Table) để xác định cổng nội bộ
 *   4. Phát lại (Replay) gói Handshake cho server MC nội bộ
 *   5. Thiết lập đường ống hai chiều (Bidirectional Pipe)
 *
 * Hướng dẫn người chơi:
 *   - Thêm server trong Minecraft: "mc1.1.2.3.4:20002"
 *   - Phần "mc1" sẽ được dùng để tra bảng định tuyến
 *   - Nếu không khớp → chuyển đến cổng mặc định (default_port)
 */
public class MinecraftRouter {

    private final int listenPort;
    private final String bindAddress;
    private final Map<String, Integer> routeTable; // hostname_prefix → target_port
    private final int defaultPort;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    // Kích thước bộ đệm cho pipe
    private static final int BUFFER_SIZE = 8192;
    // Kích thước tối đa gói Handshake (phòng trường hợp client gửi dữ liệu rác)
    private static final int MAX_HANDSHAKE_SIZE = 1024;

    public MinecraftRouter(int listenPort, String bindAddress,
                           Map<String, Integer> routeTable, int defaultPort) {
        this.listenPort = listenPort;
        this.bindAddress = bindAddress;
        this.routeTable = routeTable;
        this.defaultPort = defaultPort;
    }

    /**
     * Khởi động bộ định tuyến Minecraft
     */
    public void start() throws IOException {
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MJT-MCRouter");
            t.setDaemon(true);
            return t;
        });

        this.serverSocket = new ServerSocket(listenPort, 50, InetAddress.getByName(bindAddress));
        this.running = true;

        threadPool.submit(this::acceptLoop);

        OutputLogger.success("Minecraft Router: " + bindAddress + ":" + listenPort);
        for (Map.Entry<String, Integer> route : routeTable.entrySet()) {
            OutputLogger.info("  → '" + route.getKey() + "' → localhost:" + route.getValue());
        }
        OutputLogger.info("  → (mặc định) → localhost:" + defaultPort);
    }

    /**
     * Dừng bộ định tuyến
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
        OutputLogger.info("Minecraft Router đã dừng.");
    }

    // ==========================================
    // VÒNG LẶP CHẤP NHẬN KẾT NỐI
    // ==========================================

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                threadPool.submit(() -> routeConnection(client));
            } catch (SocketException e) {
                if (running) {
                    OutputLogger.error("MC Router: Lỗi chấp nhận kết nối: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    OutputLogger.error("MC Router: Lỗi I/O: " + e.getMessage());
                }
            }
        }
    }

    // ==========================================
    // ĐỊNH TUYẾN KẾT NỐI DỰA TRÊN MC HANDSHAKE
    // ==========================================

    private void routeConnection(Socket client) {
        activeConnections.incrementAndGet();

        try {
            client.setSoTimeout(5000); // Timeout đọc Handshake: 5 giây

            InputStream clientIn = client.getInputStream();

            // ===== ĐỌC VÀ PHÂN TÍCH GÓI HANDSHAKE =====
            HandshakeResult handshake = parseHandshake(clientIn);

            if (handshake == null) {
                // Không phải gói Minecraft hợp lệ → gửi đến default
                OutputLogger.warning("MC Router: Gói Handshake không hợp lệ. Chuyển đến cổng mặc định.");
                client.close();
                return;
            }

            // ===== TRA BẢNG ĐỊNH TUYẾN =====
            int targetPort = resolveTarget(handshake.serverAddress);

            // Reset timeout sau khi đọc handshake thành công
            client.setSoTimeout(0);

            // ===== KẾT NỐI ĐẾN SERVER NỘI BỘ =====
            Socket target;
            try {
                target = new Socket("127.0.0.1", targetPort);
            } catch (IOException e) {
                OutputLogger.warning("MC Router: Không thể kết nối localhost:" + targetPort
                    + " cho '" + handshake.serverAddress + "' (Server nội bộ chưa chạy?)");
                client.close();
                return;
            }

            // ===== PHÁT LẠI GÓI HANDSHAKE CHO SERVER NỘI BỘ =====
            OutputStream targetOut = target.getOutputStream();
            targetOut.write(handshake.rawBytes);
            targetOut.flush();

            // ===== THIẾT LẬP ĐƯỜNG ỐNG HAI CHIỀU =====
            Thread c2t = new Thread(() -> pipe(client, target), "MJT-MC-C2T");
            Thread t2c = new Thread(() -> pipe(target, client), "MJT-MC-T2C");
            c2t.setDaemon(true);
            t2c.setDaemon(true);
            c2t.start();
            t2c.start();

            c2t.join();
            t2c.join();

        } catch (Exception e) {
            // Client ngắt kết nối sớm hoặc lỗi → bỏ qua
        } finally {
            closeQuietly(client);
            activeConnections.decrementAndGet();
        }
    }

    /**
     * Tra bảng định tuyến: Đối chiếu server address với các hostname đã cấu hình
     */
    private int resolveTarget(String serverAddress) {
        if (serverAddress == null || serverAddress.isEmpty()) {
            return defaultPort;
        }

        // Loại bỏ marker FML (Forge Mod Loader thêm "\0FML\0" vào cuối address)
        String cleanAddress = serverAddress.split("\0")[0].toLowerCase().trim();

        // Đối chiếu: Kiểm tra xem address có BẮT ĐẦU bằng hostname trong bảng không
        for (Map.Entry<String, Integer> route : routeTable.entrySet()) {
            String hostname = route.getKey().toLowerCase();
            if (cleanAddress.startsWith(hostname + ".")
                || cleanAddress.equals(hostname)) {
                return route.getValue();
            }
        }

        // Không khớp → trả về cổng mặc định
        return defaultPort;
    }

    // ==========================================
    // PHÂN TÍCH GÓI MINECRAFT HANDSHAKE
    // ==========================================

    /**
     * Đọc và phân tích gói Handshake theo Minecraft Protocol.
     *
     * Cấu trúc gói Handshake (Client → Server, State 0):
     *   [Packet Length - VarInt]
     *   [Packet ID: 0x00 - VarInt]
     *   [Protocol Version - VarInt]
     *   [Server Address - String (VarInt length + UTF-8 bytes)]
     *   [Server Port - Unsigned Short (2 bytes, Big-Endian)]
     *   [Next State - VarInt (1=Status, 2=Login)]
     */
    private HandshakeResult parseHandshake(InputStream in) {
        try {
            // === Bước 1: Đọc Packet Length ===
            int packetLength = readVarInt(in);
            if (packetLength <= 0 || packetLength > MAX_HANDSHAKE_SIZE) {
                return null;
            }

            // Đọc toàn bộ payload của gói
            byte[] payload = new byte[packetLength];
            int totalRead = 0;
            while (totalRead < packetLength) {
                int read = in.read(payload, totalRead, packetLength - totalRead);
                if (read == -1) return null;
                totalRead += read;
            }

            int offset = 0;

            // === Bước 2: Đọc Packet ID (phải là 0x00) ===
            VarIntResult packetIdResult = readVarIntFromBytes(payload, offset);
            if (packetIdResult.value != 0x00) {
                return null; // Không phải gói Handshake
            }
            offset = packetIdResult.newOffset;

            // === Bước 3: Đọc Protocol Version ===
            VarIntResult protocolVersion = readVarIntFromBytes(payload, offset);
            offset = protocolVersion.newOffset;

            // === Bước 4: Đọc Server Address (String) ===
            VarIntResult addressLength = readVarIntFromBytes(payload, offset);
            offset = addressLength.newOffset;

            if (offset + addressLength.value > payload.length) {
                return null;
            }

            String serverAddress = new String(payload, offset, addressLength.value, StandardCharsets.UTF_8);
            offset += addressLength.value;

            // === Bước 5: Đọc Server Port (2 bytes Big-Endian) ===
            if (offset + 2 > payload.length) {
                return null;
            }
            int serverPort = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
            offset += 2;

            // === Bước 6: Đọc Next State ===
            VarIntResult nextState = readVarIntFromBytes(payload, offset);

            // === Tái tạo toàn bộ gói gốc (bao gồm cả Packet Length header) ===
            byte[] lengthBytes = encodeVarInt(packetLength);
            byte[] rawBytes = new byte[lengthBytes.length + payload.length];
            System.arraycopy(lengthBytes, 0, rawBytes, 0, lengthBytes.length);
            System.arraycopy(payload, 0, rawBytes, lengthBytes.length, payload.length);

            return new HandshakeResult(serverAddress, serverPort, protocolVersion.value,
                nextState.value, rawBytes);

        } catch (IOException e) {
            return null;
        }
    }

    // ==========================================
    // CÔNG CỤ ĐỌC/GHI VARINT (Minecraft Protocol)
    // ==========================================

    /**
     * Đọc VarInt từ InputStream (tối đa 5 bytes)
     */
    private int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int bitOffset = 0;

        for (int i = 0; i < 5; i++) {
            int b = in.read();
            if (b == -1) throw new IOException("Hết dữ liệu khi đọc VarInt");

            value |= (b & 0x7F) << bitOffset;
            if ((b & 0x80) == 0) return value;
            bitOffset += 7;
        }
        throw new IOException("VarInt quá dài (>5 bytes)");
    }

    /**
     * Đọc VarInt từ mảng byte tại vị trí offset
     */
    private VarIntResult readVarIntFromBytes(byte[] data, int offset) {
        int value = 0;
        int bitOffset = 0;

        for (int i = 0; i < 5; i++) {
            if (offset + i >= data.length) break;
            int b = data[offset + i] & 0xFF;
            value |= (b & 0x7F) << bitOffset;
            if ((b & 0x80) == 0) {
                return new VarIntResult(value, offset + i + 1);
            }
            bitOffset += 7;
        }
        return new VarIntResult(value, offset);
    }

    /**
     * Mã hóa int thành VarInt bytes
     */
    private byte[] encodeVarInt(int value) {
        byte[] result = new byte[5];
        int length = 0;
        do {
            byte temp = (byte) (value & 0x7F);
            value >>>= 7;
            if (value != 0) temp |= 0x80;
            result[length++] = temp;
        } while (value != 0);

        byte[] trimmed = new byte[length];
        System.arraycopy(result, 0, trimmed, 0, length);
        return trimmed;
    }

    // ==========================================
    // ĐƯỜNG ỐNG DỮ LIỆU (DATA PIPE)
    // ==========================================

    private void pipe(Socket source, Socket target) {
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
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }

    public int getActiveConnections() { return activeConnections.get(); }
    public boolean isRunning() { return running; }

    // ==========================================
    // LỚP NỘI BỘ: KẾT QUẢ PHÂN TÍCH
    // ==========================================

    private static class HandshakeResult {
        final String serverAddress;
        final int serverPort;
        final int protocolVersion;
        final int nextState;
        final byte[] rawBytes; // Toàn bộ gói gốc (để phát lại)

        HandshakeResult(String serverAddress, int serverPort, int protocolVersion,
                        int nextState, byte[] rawBytes) {
            this.serverAddress = serverAddress;
            this.serverPort = serverPort;
            this.protocolVersion = protocolVersion;
            this.nextState = nextState;
            this.rawBytes = rawBytes;
        }
    }

    private static class VarIntResult {
        final int value;
        final int newOffset;

        VarIntResult(int value, int newOffset) {
            this.value = value;
            this.newOffset = newOffset;
        }
    }
}
