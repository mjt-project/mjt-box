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

        // Lấy thư mục hiện tại của Host
        String hostWorkspace = System.getProperty("user.dir");

        // ==========================================
        // [AUTO-PATCH] only for ubuntu / debian
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

        // ==========================================
        // [AUTO-PATCH] for alpine (musl libc Java libjli.so fix)
        // ==========================================
        if (rootfsDir.contains("alpine")) {
            try {
                // Tự động cấu hình dynamic linker musl để tìm thấy libjli.so của OpenJDK
                File etcDir = new File(rootfsDir, "etc");
                if (etcDir.exists()) {
                    String[] arches = {"x86_64", "aarch64", "armhf", "armv7"};
                    String ldPaths = "/lib\n/usr/local/lib\n/usr/lib\n/usr/lib/jvm/default-jvm/lib/jli\n/usr/lib/jvm/default-jvm/lib\n";
                    for (String arch : arches) {
                        File ldPathFile = new File(etcDir, "ld-musl-" + arch + ".path");
                        if (!ldPathFile.exists()) {
                            java.nio.file.Files.writeString(ldPathFile.toPath(), ldPaths);
                        }
                    }
                }
            } catch (Exception e) {
                OutputLogger.warning("Không thể áp dụng bản vá tự động cho Alpine musl dynamic loader.");
            }
        }
        // 1. Khởi tạo danh sách lệnh với các cờ (Options) của PRoot TRƯỚC
        List<String> command = new ArrayList<>(Arrays.asList(
                "./proot",
                "-0",                   
                "-r", rootfsDir,        
                "-w", "/root",          
                "-b", hostWorkspace + ":/workspace"
        ));

        // 2. Chèn các hệ thống tệp cốt lõi từ Host (CỰC KỲ QUAN TRỌNG CHO DOCKER / PROOT)
        // JVM và musl libc bắt buộc cần /proc (để đọc /proc/self/exe tìm libjli.so) và /dev
        String[] essentialBinds = {"/proc", "/dev", "/sys", "/dev/pts", "/dev/shm"};
        for (String b : essentialBinds) {
            File f = new File(b);
            if (f.exists()) {
                command.add("-b");
                command.add(b + ":" + b);
            }
        }

        // 3. Chèn tiếp các cấu hình mạng (Nếu có)
        File resolvConf = new File("/etc/resolv.conf");
        if (resolvConf.exists()) {
            command.add("-b");
            command.add("/etc/resolv.conf:/etc/resolv.conf");
        }

        // 4. [CHỐT HẠ]: Nạp đường dẫn Shell
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
        
        if (rootfsDir.contains("alpine")) {
            pb.environment().put("LD_LIBRARY_PATH", "/lib:/usr/local/lib:/usr/lib:/usr/lib/jvm/default-jvm/lib/jli:/usr/lib/jvm/default-jvm/lib");
        }
        
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

        OutputLogger.success("Đã truy cập thành công! Bàn giao Terminal cho Kernel ảo...\n");

        // Treo luồng chính của Java để chờ phiên Linux kết thúc (khi người dùng gõ 'exit')
        this.prootProcess.waitFor();

        OutputLogger.info("Phiên làm việc kết thúc. Đã đóng gói và giải phóng vùng nhớ ảo.");
    }
}