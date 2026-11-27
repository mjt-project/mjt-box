package com.mjt.box.network;

import com.mjt.box.ui.OutputLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ định tuyến Minecraft đa giao thức (Java TCP + Bedrock/MCPE UDP).
 *
 * Thuộc tính "type" hỗ trợ:
 *   - "tcp"      : Chỉ mở router TCP (Virtual Host cho Minecraft Java)
 *   - "udp"      : Chỉ mở router UDP (MCPE RakNet + NetherNet WebRTC ICE/DTLS)
 *   - "both" / "tcp/udp" : Mở ĐỒNG THỜI cả TCP lẫn UDP
 */
public class MinecraftRouter {

    private final int listenPort;
    private final String bindAddress;
    private volatile String routerType;
    private volatile Map<String, Integer> routeTable;
    private volatile List<Integer> udpTargets;
    private volatile int defaultPort;
    private final int muxTcpBoundPort; // Để tránh xung đột nếu SmartMultiplexer đã bind cùng port TCP

    private ServerSocket tcpServerSocket;
    private DatagramSocket udpSocket;
    private ExecutorService threadPool;
    private volatile boolean running;

    private int actualTcpPort = -1;
    private int actualUdpPort = -1;

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger udpRoundRobinCounter = new AtomicInteger(0);
    private final Map<String, UdpSession> udpSessions = new ConcurrentHashMap<>();

    private static final int TCP_BUFFER_SIZE = 8192;
    private static final int UDP_BUFFER_SIZE = 65535;
    private static final int MAX_HANDSHAKE_SIZE = 1024;
    private static final long UDP_SESSION_TIMEOUT_MS = 60_000;

    public MinecraftRouter(int listenPort, String bindAddress, String routerType,
                           Map<String, Integer> routeTable, List<Integer> udpTargets,
                           int defaultPort, int muxTcpBoundPort) {
        this.listenPort = listenPort;
        this.bindAddress = bindAddress;
        this.routerType = routerType != null ? routerType.trim().toLowerCase(Locale.ROOT) : "tcp";
        this.routeTable = new ConcurrentHashMap<>(routeTable);
        this.udpTargets = udpTargets != null ? new ArrayList<>(udpTargets) : new ArrayList<>();
        this.defaultPort = defaultPort;
        this.muxTcpBoundPort = muxTcpBoundPort;
    }

    /**
     * Khởi động Minecraft Router theo cấu hình "type" (tcp / udp / both)
     */
    public void start() throws IOException {
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MJT-MCRouter");
            t.setDaemon(true);
            return t;
        });

        boolean enableTcp = routerType.contains("tcp") || "both".equals(routerType) || routerType.isEmpty();
        boolean enableUdp = routerType.contains("udp") || "both".equals(routerType);

        if (!enableTcp && !enableUdp) {
            enableTcp = true;
        }

        List<Integer> portsToTry = buildCandidateBindPorts();
        List<String> addressesToTry = buildCandidateAddresses();
        this.running = true;

        int startedTransports = 0;
        IOException lastError = null;

        // ===== 1. KHỞI ĐỘNG TCP ROUTER (MINECRAFT JAVA) =====
        if (enableTcp) {
            for (int port : portsToTry) {
                if (muxTcpBoundPort > 0 && port == muxTcpBoundPort) {
                    OutputLogger.info("MC Router [TCP]: Bỏ qua TCP:" + port
                            + " (đã do Smart Multiplexer đảm nhiệm)");
                    break;
                }
                for (String addr : addressesToTry) {
                    try {
                        this.tcpServerSocket = new ServerSocket(port, 50, InetAddress.getByName(addr));
                        this.actualTcpPort = port;
                        threadPool.submit(this::acceptTcpLoop);
                        OutputLogger.success("Minecraft Router [TCP]: " + addr + ":" + port);
                        printTcpRoutes();
                        startedTransports++;
                        break;
                    } catch (IOException e) {
                        lastError = e;
                    }
                }
                if (actualTcpPort > 0) break;
            }
        }

        // ===== 2. KHỞI ĐỘNG UDP ROUTER (MCPE / BEDROCK / NETHERNET UDP) =====
        if (enableUdp) {
            for (int port : portsToTry) {
                for (String addr : addressesToTry) {
                    try {
                        this.udpSocket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(addr), port));
                        this.actualUdpPort = port;
                        threadPool.submit(this::receiveUdpLoop);
                        threadPool.submit(this::udpSessionCleanupLoop);
                        OutputLogger.success("Minecraft Router [UDP/MCPE]: " + addr + ":" + port
                                + " → localhost:" + getEffectiveUdpTargets());
                        startedTransports++;
                        break;
                    } catch (IOException e) {
                        lastError = e;
                    }
                }
                if (actualUdpPort > 0) break;
            }
        }

        if (startedTransports == 0 && !(enableTcp && muxTcpBoundPort > 0 && !enableUdp)) {
            this.running = false;
            throw new IOException("Không thể mở Minecraft Router (" + routerType + ") trên cổng "
                    + listenPort + (lastError != null ? ": " + lastError.getMessage() : ""));
        }
    }

    /**
     * Cập nhật bảng định tuyến realtime khi Hot-Reload
     */
    public void updateRouting(Map<String, Integer> newRoutes, List<Integer> newUdpTargets, int newDefaultPort) {
        this.routeTable = new ConcurrentHashMap<>(newRoutes);
        this.udpTargets = newUdpTargets != null ? new ArrayList<>(newUdpTargets) : new ArrayList<>();
        this.defaultPort = newDefaultPort;
        OutputLogger.info("MC Router: Đã cập nhật bảng định tuyến (default=" + newDefaultPort
                + ", udpTargets=" + getEffectiveUdpTargets() + ")");
    }

    private void printTcpRoutes() {
        for (Map.Entry<String, Integer> route : routeTable.entrySet()) {
            OutputLogger.info("  [TCP] → '" + route.getKey() + "' → localhost:" + route.getValue());
        }
        OutputLogger.info("  [TCP] → (mặc định) → localhost:" + defaultPort);
    }

    private List<Integer> buildCandidateBindPorts() {
        List<Integer> ports = new ArrayList<>();
        if (muxTcpBoundPort > 0 && listenPort <= 0) {
            ports.add(muxTcpBoundPort);
        }
        if (listenPort > 0) {
            ports.add(listenPort);
        }
        String[] envNames = {"SERVER_PORT", "P_SERVER_PORT", "SERVER_ALLOCATION_PORT", "PORT"};
        for (String env : envNames) {
            String v = System.getenv(env);
            if (v != null && !v.isEmpty()) {
                try {
                    int p = Integer.parseInt(v.trim());
                    if (p > 0 && p <= 65535 && !ports.contains(p)) {
                        ports.add(p);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        if (muxTcpBoundPort > 0 && !ports.contains(muxTcpBoundPort)) {
            ports.add(muxTcpBoundPort);
        }
        return ports;
    }

    private List<String> buildCandidateAddresses() {
        List<String> addrs = new ArrayList<>();
        addrs.add(bindAddress);
        if (!"0.0.0.0".equals(bindAddress)) addrs.add("0.0.0.0");
        if (!"127.0.0.1".equals(bindAddress)) addrs.add("127.0.0.1");
        return addrs;
    }

    public void stop() {
        running = false;
        try {
            if (tcpServerSocket != null && !tcpServerSocket.isClosed()) {
                tcpServerSocket.close();
            }
        } catch (IOException ignored) {}
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        for (UdpSession s : udpSessions.values()) {
            s.close();
        }
        udpSessions.clear();

        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        OutputLogger.info("Minecraft Router đã dừng.");
    }

    // ==========================================
    // LUỒNG UDP (MCPE RAKNET + NETHERNET WEBRTC)
    // ==========================================

    private List<Integer> getEffectiveUdpTargets() {
        List<Integer> targets = new ArrayList<>(udpTargets);
        if (defaultPort > 0 && !targets.contains(defaultPort)) {
            targets.add(defaultPort);
        }
        return targets;
    }

    private int selectUdpTargetPort() {
        List<Integer> targets = getEffectiveUdpTargets();
        if (targets.isEmpty()) return 19132;
        if (targets.size() == 1) return targets.get(0);
        int idx = Math.floorMod(udpRoundRobinCounter.getAndIncrement(), targets.size());
        return targets.get(idx);
    }

    private void receiveUdpLoop() {
        byte[] buffer = new byte[UDP_BUFFER_SIZE];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                int len = packet.getLength();
                byte[] data = new byte[len];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, len);

                String clientKey = packet.getAddress().getHostAddress() + ":" + packet.getPort();
                UdpSession session = udpSessions.get(clientKey);

                if (session == null) {
                    int targetPort = selectUdpTargetPort();
                    String packetKind = identifyMcpeUdpPacket(data, len);
                    try {
                        session = createUdpSession(packet.getAddress(), packet.getPort(), targetPort);
                        udpSessions.put(clientKey, session);
                        OutputLogger.info(String.format(
                                "MC Router [UDP] ✓ /%s [%s] → localhost:%d",
                                clientKey, packetKind, targetPort));
                    } catch (IOException e) {
                        OutputLogger.error("MC Router [UDP] Không thể tạo phiên relay: " + e.getMessage());
                        continue;
                    }
                }

                session.updateLastActivity();
                DatagramPacket forward = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName("127.0.0.1"), session.targetPort);
                session.relaySocket.send(forward);

            } catch (SocketException e) {
                if (running) {
                    OutputLogger.error("MC Router [UDP] Lỗi Socket: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    OutputLogger.error("MC Router [UDP] Lỗi I/O: " + e.getMessage());
                }
            }
        }
    }

    private UdpSession createUdpSession(InetAddress clientAddr, int clientPort, int targetPort) throws IOException {
        DatagramSocket relaySocket = new DatagramSocket();
        relaySocket.setSoTimeout(5000);
        UdpSession session = new UdpSession(relaySocket, clientAddr, clientPort, targetPort);
        threadPool.submit(() -> relayUdpResponseLoop(session));
        return session;
    }

    private void relayUdpResponseLoop(UdpSession session) {
        byte[] buffer = new byte[UDP_BUFFER_SIZE];

        while (running && !session.relaySocket.isClosed()) {
            try {
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                session.relaySocket.receive(response);

                byte[] data = new byte[response.getLength()];
                System.arraycopy(response.getData(), response.getOffset(), data, 0, response.getLength());

                DatagramPacket clientPacket = new DatagramPacket(
                        data, data.length, session.clientAddress, session.clientPort);
                udpSocket.send(clientPacket);
                session.updateLastActivity();
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) break;
            }
        }
    }

    private void udpSessionCleanupLoop() {
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

    private static String identifyMcpeUdpPacket(byte[] data, int len) {
        if (len >= 17 && ((data[0] & 0xFF) == 0x01 || (data[0] & 0xFF) == 0x02)) {
            return "MCPE RakNet Ping";
        }
        if (len >= 17 && ((data[0] & 0xFF) == 0x05 || (data[0] & 0xFF) == 0x07)) {
            return "MCPE RakNet Handshake";
        }
        // WebRTC STUN magic cookie 0x2112A442 tại byte 4..7
        if (len >= 20 && data[4] == 0x21 && data[5] == 0x12
                && (data[6] & 0xFF) == 0xA4 && data[7] == 0x42) {
            return "MCPE NetherNet STUN/ICE";
        }
        // WebRTC DTLS (RFC 5764): byte đầu 20..63
        if (len >= 13 && (data[0] & 0xFF) >= 20 && (data[0] & 0xFF) <= 63) {
            return "MCPE NetherNet DTLS";
        }
        return "UDP Datagram";
    }

    // ==========================================
    // LUỒNG TCP (MINECRAFT JAVA VIRTUAL HOST)
    // ==========================================

    private void acceptTcpLoop() {
        while (running) {
            try {
                Socket client = tcpServerSocket.accept();
                threadPool.submit(() -> routeTcpConnection(client));
            } catch (SocketException e) {
                if (running) {
                    OutputLogger.error("MC Router [TCP]: Lỗi chấp nhận kết nối: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    OutputLogger.error("MC Router [TCP]: Lỗi I/O: " + e.getMessage());
                }
            }
        }
    }

    private void routeTcpConnection(Socket client) {
        activeConnections.incrementAndGet();

        try {
            client.setSoTimeout(5000);
            InputStream clientIn = client.getInputStream();

            HandshakeResult handshake = parseHandshake(clientIn);
            if (handshake == null) {
                OutputLogger.warning("MC Router [TCP]: Gói Handshake không hợp lệ.");
                client.close();
                return;
            }

            int targetPort = resolveTarget(handshake.serverAddress);
            client.setSoTimeout(0);

            Socket target;
            try {
                target = new Socket("127.0.0.1", targetPort);
            } catch (IOException e) {
                OutputLogger.warning("MC Router [TCP]: Không thể kết nối localhost:" + targetPort
                        + " cho '" + handshake.serverAddress + "'");
                client.close();
                return;
            }

            OutputStream targetOut = target.getOutputStream();
            targetOut.write(handshake.rawBytes);
            targetOut.flush();

            Thread c2t = new Thread(() -> pipe(client, target), "MJT-MC-C2T");
            Thread t2c = new Thread(() -> pipe(target, client), "MJT-MC-T2C");
            c2t.setDaemon(true);
            t2c.setDaemon(true);
            c2t.start();
            t2c.start();

            c2t.join();
            t2c.join();
        } catch (Exception ignored) {
        } finally {
            closeQuietly(client);
            activeConnections.decrementAndGet();
        }
    }

    private int resolveTarget(String serverAddress) {
        if (serverAddress == null || serverAddress.isEmpty()) {
            return defaultPort;
        }
        String cleanAddress = serverAddress.split("\0")[0].toLowerCase(Locale.ROOT).trim();
        for (Map.Entry<String, Integer> route : routeTable.entrySet()) {
            String hostname = route.getKey().toLowerCase(Locale.ROOT);
            if (cleanAddress.startsWith(hostname + ".") || cleanAddress.equals(hostname)) {
                return route.getValue();
            }
        }
        return defaultPort;
    }

    private HandshakeResult parseHandshake(InputStream in) {
        try {
            int packetLength = readVarInt(in);
            if (packetLength <= 0 || packetLength > MAX_HANDSHAKE_SIZE) {
                return null;
            }

            byte[] payload = new byte[packetLength];
            int totalRead = 0;
            while (totalRead < packetLength) {
                int read = in.read(payload, totalRead, packetLength - totalRead);
                if (read == -1) return null;
                totalRead += read;
            }

            int offset = 0;
            VarIntResult packetIdResult = readVarIntFromBytes(payload, offset);
            if (packetIdResult.value != 0x00) return null;
            offset = packetIdResult.newOffset;

            VarIntResult protocolVersion = readVarIntFromBytes(payload, offset);
            offset = protocolVersion.newOffset;

            VarIntResult addressLength = readVarIntFromBytes(payload, offset);
            offset = addressLength.newOffset;
            if (offset + addressLength.value > payload.length) return null;

            String serverAddress = new String(payload, offset, addressLength.value, StandardCharsets.UTF_8);
            offset += addressLength.value;
            if (offset + 2 > payload.length) return null;

            int serverPort = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
            offset += 2;
            VarIntResult nextState = readVarIntFromBytes(payload, offset);

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

    private VarIntResult readVarIntFromBytes(byte[] data, int offset) {
        int value = 0;
        int bitOffset = 0;
        for (int i = 0; i < 5; i++) {
            if (offset + i >= data.length) break;
            int b = data[offset + i] & 0xFF;
            value |= (b & 0x7F) << bitOffset;
            if ((b & 0x80) == 0) return new VarIntResult(value, offset + i + 1);
            bitOffset += 7;
        }
        return new VarIntResult(value, offset);
    }

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

    private void pipe(Socket source, Socket target) {
        byte[] buffer = new byte[TCP_BUFFER_SIZE];
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
    public int getActualUdpPort() { return actualUdpPort; }
    public int getActualTcpPort() { return actualTcpPort; }
    public String getRouterType() { return routerType; }

    private static class UdpSession {
        final DatagramSocket relaySocket;
        final InetAddress clientAddress;
        final int clientPort;
        final int targetPort;
        volatile long lastActivity;

        UdpSession(DatagramSocket relaySocket, InetAddress clientAddress, int clientPort, int targetPort) {
            this.relaySocket = relaySocket;
            this.clientAddress = clientAddress;
            this.clientPort = clientPort;
            this.targetPort = targetPort;
            this.lastActivity = System.currentTimeMillis();
        }

        void updateLastActivity() { this.lastActivity = System.currentTimeMillis(); }
        void close() {
            if (!relaySocket.isClosed()) relaySocket.close();
        }
    }

    private static class HandshakeResult {
        final String serverAddress;
        final int serverPort;
        final int protocolVersion;
        final int nextState;
        final byte[] rawBytes;

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
