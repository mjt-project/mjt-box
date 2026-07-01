package com.mjt.core;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;

public class Main {

    // Điểm neo tài nguyên lõi (Kiến trúc x86_64)
    private static final String PROOT_URL = "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-x86_64-static";
    private static final String ALPINE_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86_64/alpine-minirootfs-3.19.1-x86_64.tar.gz";

    public static void main(String[] args) {
        System.out.println("[MJT-Kernel] Khởi động Trình điều phối Hệ thống...");
        try {
            provisionResources();
            startIpcSession();
        } catch (Exception e) {
            System.err.println("[MJT-Error] Lỗi hệ thống nghiêm trọng: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Pha 1 & 2: Tự động Tải tài nguyên và Đột biến Môi trường
     */
    private static void provisionResources() throws Exception {
        // 1. Xử lý tệp thực thi PRoot
        File prootFile = new File("proot");
        if (!prootFile.exists()) {
            System.out.println("[-] Thiếu lõi PRoot. Đang cấp phát luồng tải xuống...");
            downloadResource(PROOT_URL, prootFile.toPath());
            
            // Can thiệp API cấp quyền thực thi mà không dùng lệnh chmod (Tránh Seccomp)
            prootFile.setExecutable(true, false); 
            System.out.println("[+] Đã cấp quyền thực thi cho lõi giả lập.");
        }

        // 2. Xử lý Hệ thống tệp gốc Alpine (Rootfs)
        File alpineDir = new File("alpine-fs");
        if (!alpineDir.exists()) {
            File alpineTar = new File("alpine-rootfs.tar.gz");
            if (!alpineTar.exists()) {
                System.out.println("[-] Thiếu Rootfs. Đang kéo Alpine Linux từ máy chủ gốc...");
                downloadResource(ALPINE_URL, alpineTar.toPath());
            }

            System.out.println("[*] Đang bung nén không gian ảo (Virtual Space)...");
            alpineDir.mkdir();
            // Tận dụng công cụ tar nội tại của Container đã được kiểm chứng
            Process tarProcess = new ProcessBuilder("tar", "-xf", alpineTar.getName(), "-C", "alpine-fs").start();
            tarProcess.waitFor();
            System.out.println("[+] Khởi tạo không gian bộ nhớ thành công.");
        }
    }

    /**
     * Module I/O Mạng thuần Java (Sử dụng HttpClient API hiện đại)
     */
    private static void downloadResource(String url, Path target) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
        
        if (response.statusCode() != 200) {
            throw new IOException("Từ chối truy cập HTTP. Mã lỗi: " + response.statusCode());
        }
    }

    /**
     * Pha 3: Đoạt quyền I/O và Thiết lập đường ống giao tiếp (IPC - Inter-Process Communication)
     */
    private static void startIpcSession() throws Exception {
        System.out.println("[MJT-Kernel] Bắt đầu thâm nhập Alpine Shell. Gõ 'exit_mjt' để ngắt kết nối an toàn.\n");

        List<String> command = Arrays.asList("./proot", "-S", "alpine-fs", "/bin/sh");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true); // Hợp nhất stderr vào stdout
        
        Process shellProcess = pb.start();

        // [BẢO MẬT BỘ NHỚ]: Đăng ký Shutdown Hook để tiêu diệt Zombie Process khi máy chủ sập hoặc user tắt đột ngột
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[MJT-Kernel] Tín hiệu ngắt! Đang giải phóng RAM...");
            shellProcess.destroyForcibly();
        }));

        // Luồng Đọc Độc Lập (Daemon Thread): Lắng nghe kết quả từ Alpine trả về
        Thread readerThread = new Thread(() -> {
            try (BufferedReader fromAlpine = new BufferedReader(new InputStreamReader(shellProcess.getInputStream()))) {
                String line;
                while ((line = fromAlpine.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
                // Xử lý im lặng khi stream bị ngắt do tiến trình tắt
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();

        // Luồng Ghi Tương Tác (Main Thread): Lấy lệnh từ bàn phím gõ vào Alpine
        try (BufferedWriter toAlpine = new BufferedWriter(new OutputStreamWriter(shellProcess.getOutputStream()));
             BufferedReader fromUser = new BufferedReader(new InputStreamReader(System.in))) {
            
            String userInput;
            System.out.print("mjt-shell> ");
            while ((userInput = fromUser.readLine()) != null) {
                if (userInput.trim().equalsIgnoreCase("exit_mjt")) {
                    break;
                }
                toAlpine.write(userInput + "\n");
                toAlpine.flush(); // Bắt buộc xả buffer để lệnh chạy ngay lập tức
            }
        }

        // Dọn dẹp sạch sẽ trước khi thoát
        shellProcess.destroyForcibly();
        System.out.println("[MJT-Kernel] Đã đóng không gian ảo.");
    }
}