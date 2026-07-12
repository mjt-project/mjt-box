package com.mjt.core.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mjt.core.config.ConfigManager;
import com.mjt.core.ui.OutputLogger;

public class ProotOrchestrator {

    private Process prootProcess;

    public void bootEnvironment(String rootfsDir, String shellPath) throws Exception {
        OutputLogger.info("Đang nạp lõi Engine. Chuẩn bị đoạt quyền I/O...");

        File prootExe = new File("proot");
        if (prootExe.exists() && !prootExe.canExecute()) {
            prootExe.setExecutable(true, false);
        }

        // Lấy thư mục hiện tại của Host (Trên Pterodactyl, nó chính là /home/container)
        String hostWorkspace = System.getProperty("user.dir");

        // ==========================================
        // [AUTO-PATCH] only for ubuntu
        // ==========================================
        if (rootfsDir.contains("ubuntu") || rootfsDir.contains("debian")) {
            // 1. Trị dứt điểm lỗi "ucf: do not have write privilege"
            File ucfDir = new File(rootfsDir, "var/lib/ucf");
            if (!ucfDir.exists()) {
                ucfDir.mkdirs();
            }
            ucfDir.setWritable(true, false); // Cấp quyền ghi tuyệt đối (chmod 777) cho ucf

            // 2. Ép APT triệt sản: Không cài gói rác (Recommends/Suggests)
            File aptConfDir = new File(rootfsDir, "etc/apt/apt.conf.d");
            if (aptConfDir.exists()) {
                try {
                    File aptOptimize = new File(aptConfDir, "99-mjt-optimize");
                    java.nio.file.Files.writeString(aptOptimize.toPath(), 
                        "APT::Install-Recommends \"false\";\nAPT::Install-Suggests \"false\";\n");
                } catch (Exception e) {
                    OutputLogger.warning("Không thể ghi cấu hình tối ưu APT.");
                }
            }
        }
        // 1. Khởi tạo danh sách lệnh với các cờ (Options) của PRoot TRƯỚC
        List<String> command = new ArrayList<>(Arrays.asList(
                "./proot",
                "-0",                   
                "-S", rootfsDir,        
                "-w", "/root",          
                "-b", hostWorkspace + ":/workspace"
        ));

        // 2. Chèn tiếp các cấu hình mạng (Nếu có)
        File resolvConf = new File("/etc/resolv.conf");
        if (resolvConf.exists()) {
            command.add("-b");
            command.add("/etc/resolv.conf:/etc/resolv.conf");
        }

        // 3. [CHỐT HẠ]: Nạp đường dẫn Shell
        command.add(shellPath);

        // ==========================================
        // [TÍNH NĂNG MỚI]: ENTRYPOINT (TỰ ĐỘNG THỰC THI LỆNH)
        // ==========================================
        String startupCmd = ConfigManager.get("startup-command", "");
        
        if (!startupCmd.isEmpty()) {
            OutputLogger.info("Kích hoạt lệnh khởi động tự động (Entrypoint): " + startupCmd);
            command.add("-c");         // Báo cho Bash biết: "Đừng mở màn hình chờ, chạy lệnh sau đây rồi thoát!"
            command.add(startupCmd);   // Bơm lệnh vào
        }

        // Khởi tạo Process Builder...
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();

        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.environment().put("LANG", "C.UTF-8");
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("PROOT_NO_SECCOMP", "1");
        
        boolean disableInteractive = ConfigManager.getBoolean("debian-frontend-noninteractive", true);
        if (disableInteractive && (rootfsDir.contains("ubuntu") || rootfsDir.contains("debian"))) {
            pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
        }
        
        this.prootProcess = pb.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (prootProcess != null && prootProcess.isAlive()) {
                System.out.println("\n[MJT-Engine] Tín hiệu ngắt cấp bách! Đang tiêu diệt tiến trình ngầm...");
                prootProcess.destroyForcibly();
            }
        }));

        OutputLogger.success("Thâm nhập thành công! Bàn giao Terminal cho Kernel ảo...\n");

        // Treo luồng chính của Java để chờ phiên Linux kết thúc (khi người dùng gõ 'exit')
        this.prootProcess.waitFor();

        OutputLogger.info("Phiên làm việc kết thúc. Đã đóng gói và giải phóng vùng nhớ ảo.");
    }
}