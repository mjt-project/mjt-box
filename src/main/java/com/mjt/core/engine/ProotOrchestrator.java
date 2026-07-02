package com.mjt.core.engine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mjt.core.config.ConfigManager;
import com.mjt.core.ui.OutputLogger;

public class ProotOrchestrator {

    private Process prootProcess;

    /**
     * Khởi động không gian ngầm và chuyển giao quyền lực Terminal
     */

    public void bootEnvironment(String rootfsDir, String shellPath) throws Exception {
        OutputLogger.info("Đang nạp lõi Engine. Chuẩn bị đoạt quyền I/O...");

        // [TỰ CHỮA LÀNH QUYỀN TRUY CẬP]: Ép buộc quyền thực thi (chmod +x) trước khi kích nổ
        File prootExe = new File("proot");
        if (prootExe.exists() && !prootExe.canExecute()) {
            prootExe.setExecutable(true, false);
        }

        // 1. Cấu hình luồng thực thi (Execution Builder)
        // ... (Code cũ của em giữ nguyên)
        List<String> command = new ArrayList<>(Arrays.asList(
                "./proot",
                "-S", rootfsDir,        // Chroot và chia sẻ hệ thống
                "-w", "/root",          // Đặt thư mục làm việc mặc định khi vào là /root
                "-b", "/dev:/dev",      // Bind mount: Bơm thiết bị ảo (urandom, null, zero)
                "-b", "/sys:/sys",      // Bind mount: Bơm thông tin phần cứng
                "-b", "/proc:/proc"     // Bind mount: Bơm bảng quản lý tiến trình
        ));

        // [TỬ HUYỆT MẠNG]: Nếu hệ thống vật lý có file phân giải DNS, phải ép nó vào Sandbox
        File resolvConf = new File("/etc/resolv.conf");
        if (resolvConf.exists()) {
            command.add("-b");
            command.add("/etc/resolv.conf:/etc/resolv.conf");
        }

        // Chốt hạ bằng lệnh gọi Shell (/bin/sh hoặc /bin/bash)
        command.add(shellPath);

        ProcessBuilder pb = new ProcessBuilder(command);
        
        // [VŨ KHÍ CHỐNG SEGFAULT]: Ép PRoot bỏ qua lớp khiên bảo vệ Seccomp
        pb.environment().put("PROOT_NO_SECCOMP", "1");
        
        // (Khối code cũ của Ubuntu/Debian vẫn giữ nguyên bên dưới)
        boolean disableInteractive = ConfigManager.getBoolean("debian-frontend-noninteractive", true);
        if (disableInteractive && (rootfsDir.contains("ubuntu") || rootfsDir.contains("debian"))) {
            pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
        }
        
        pb.redirectErrorStream(true);
        // 2. Kích nổ tiến trình ngầm
        this.prootProcess = pb.start();

        // [BẢO MẬT BỘ NHỚ]: Vệ sĩ dọn dẹp Zombie Process
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (prootProcess != null && prootProcess.isAlive()) {
                System.out.println("\n[MJT-Engine] Tín hiệu ngắt cấp bách! Đang tiêu diệt tiến trình ngầm...");
                prootProcess.destroyForcibly();
            }
        }));

        OutputLogger.success("Thâm nhập thành công! Bàn giao Terminal cho Kernel ảo...\n");

        // 3. Đoạt quyền Luồng Dữ liệu (Stream Hijacking)
        // Luồng Đọc (Daemon): Chạy nền, liên tục lấy output từ Linux in ra màn hình
        Thread outputPumper = new Thread(this::pumpOutput);
        outputPumper.setDaemon(true);
        outputPumper.start();

        // Luồng Ghi (Main Thread): Bị khóa (Block) ở đây để chờ người dùng gõ phím
        pumpInput();

        // 4. Thoát an toàn
        // Khi người dùng gõ lệnh 'exit' trong Shell, Linux sẽ tự đóng -> vòng lặp pumpInput kết thúc
        OutputLogger.info("Phiên làm việc kết thúc. Đã đóng gói và giải phóng vùng nhớ ảo.");
    }

    /**
     * Bơm dữ liệu xuất: Từ Linux -> Màn hình (Java)
     */
    private void pumpOutput() {
        try (InputStream is = prootProcess.getInputStream()) {
            // [KỸ THUẬT LÕI]: Không dùng BufferedReader hay readLine() vì nó làm mất định dạng màu ANSI
            // Phải đọc bằng mảng Byte thô (Raw Bytes) để giữ nguyên tốc độ và định dạng
            byte[] buffer = new byte[2048];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                System.out.write(buffer, 0, bytesRead);
                System.out.flush(); // Ép xả màn hình lập tức
            }
        } catch (IOException e) {
            // Luồng bị ngắt khi tiến trình kết thúc
        }
    }

    /**
     * Bơm dữ liệu nhập: Từ Bàn phím (Java) -> Linux
     */
    private void pumpInput() {
        try (OutputStream os = prootProcess.getOutputStream()) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            // System.in.read() sẽ treo máy (block) chờ người dùng nhấn Enter
            while (prootProcess.isAlive() && (bytesRead = System.in.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                os.flush(); // Ép bơm lệnh vào Shell lập tức
            }
        } catch (IOException e) {
            OutputLogger.error("Lỗi mất kết nối luồng nhập liệu: " + e.getMessage());
        }
    }
}