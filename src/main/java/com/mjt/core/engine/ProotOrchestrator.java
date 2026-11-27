package com.mjt.core.engine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.mjt.core.config.ConfigManager;
import com.mjt.core.config.MjtPaths;
import com.mjt.core.config.StartupScriptManager;
import com.mjt.core.ui.OutputLogger;

public class ProotOrchestrator {

    private static final int MAX_LOG_LINES = 100;

    private volatile Process prootProcess;
    private BufferedWriter processStdin;
    private Thread shutdownHook;
    private final Deque<String> recentLogs = new ArrayDeque<>();

    public void bootEnvironment(String rootfsDir, String shellPath) throws Exception {
        bootEnvironment(rootfsDir, shellPath, true, null);
    }

    public void bootEnvironment(String rootfsDir, String shellPath, boolean interactive, Consumer<String> lineListener)
            throws Exception {
        OutputLogger.info("Đang nạp lõi Engine. Chuẩn bị đoạt quyền I/O...");

        File baseDir = MjtPaths.getBaseDir();
        File prootExe = MjtPaths.resolve("proot");
        if (prootExe.exists() && !prootExe.canExecute()) {
            prootExe.setExecutable(true, false);
        }

        File resolvedRootfs = MjtPaths.resolve(rootfsDir);
        // Lấy thư mục hiện tại của Host / PluginDataFolder
        String hostWorkspace = baseDir.getAbsolutePath();

        // ==========================================
        // [AUTO-PATCH] only for ubuntu / debian
        // ==========================================
        if (rootfsDir.contains("ubuntu") || rootfsDir.contains("debian")) {
            // 1. Trị dứt điểm lỗi "ucf: do not have write privilege"
            File ucfDir = new File(resolvedRootfs, "var/lib/ucf");
            if (!ucfDir.exists()) {
                ucfDir.mkdirs();
            }
            ucfDir.setWritable(true, false); // Cấp quyền ghi tuyệt đối (chmod 777) cho ucf

            // 2. Ép APT triệt sản: Không cài gói rác (Recommends/Suggests)
            File aptConfDir = new File(resolvedRootfs, "etc/apt/apt.conf.d");
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
                File etcDir = new File(resolvedRootfs, "etc");
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
                prootExe.getAbsolutePath(),
                "-0",
                "-r", resolvedRootfs.getAbsolutePath(),
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

        // 4. [CHỐT HẠ]: Nạp đường dẫn Shell + Startup Commands (vẫn giữ phiên shell hoạt động)
        StartupScriptManager.clearRestartMarker();
        installRestartCommand(resolvedRootfs);
        boolean hasStartupScript = StartupScriptManager.hasExecutableCommands();

        command.add(shellPath);

        // ==========================================
        // [TÍNH NĂNG MỚI]: STARTUP COMMANDS & ENTRYPOINT
        // ==========================================
        String startupCmd = ConfigManager.get("startup-command", "");
        if (hasStartupScript || !startupCmd.isEmpty()) {
            StringBuilder script = new StringBuilder();
            if (hasStartupScript) {
                OutputLogger.info("Đang chạy tệp lệnh khởi động: " + StartupScriptManager.STARTUP_FILE
                        + " (" + StartupScriptManager.countExecutableLines() + " dòng lệnh)");
                script.append("[ -f /workspace/").append(StartupScriptManager.STARTUP_FILE)
                        .append(" ] && ").append(shellPath).append(" /workspace/")
                        .append(StartupScriptManager.STARTUP_FILE).append("; ");
            }
            if (!startupCmd.isEmpty()) {
                OutputLogger.info("Kích hoạt lệnh khởi động tự động (Entrypoint): " + startupCmd);
                script.append(startupCmd);
                if (hasStartupScript) {
                    script.append("; exec ").append(shellPath);
                }
            } else {
                script.append("exec ").append(shellPath);
            }
            command.add("-c");
            command.add(script.toString());
        }

        // Khởi tạo Process Builder...
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(baseDir);
        if (interactive) {
            pb.inheritIO();
        } else {
            pb.redirectErrorStream(true);
        }

        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.environment().put("LANG", "C.UTF-8");
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        pb.environment().put("MJT_MODE", interactive ? "standalone" : "plugin");
        pb.environment().put("PROOT_NO_SECCOMP", "1");

        if (rootfsDir.contains("alpine")) {
            pb.environment().put("LD_LIBRARY_PATH", "/lib:/usr/local/lib:/usr/lib:/usr/lib/jvm/default-jvm/lib/jli:/usr/lib/jvm/default-jvm/lib");
        }

        boolean disableInteractive = ConfigManager.getBoolean("debian-frontend-noninteractive", true);
        if (disableInteractive && (rootfsDir.contains("ubuntu") || rootfsDir.contains("debian"))) {
            pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
        }

        Process started = pb.start();
        synchronized (this) {
            this.prootProcess = started;
            if (!interactive) {
                this.processStdin = new BufferedWriter(
                        new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
            } else {
                this.processStdin = null;
            }
        }

        Thread hook = new Thread(() -> {
            Process proc = this.prootProcess;
            if (proc != null && proc.isAlive()) {
                System.out.println("\n[MJT-Engine] Tín hiệu ngắt cấp bách! Đang tiêu diệt tiến trình ngầm...");
                proc.destroyForcibly();
            }
        }, "MJT-PRoot-ShutdownHook");
        this.shutdownHook = hook;
        Runtime.getRuntime().addShutdownHook(hook);

        Thread outputPump = null;
        if (!interactive) {
            outputPump = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendRecentLog(line);
                        if (lineListener != null) {
                            lineListener.accept(line);
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "MJT-PRoot-Output");
            outputPump.setDaemon(true);
            outputPump.start();
        }

        OutputLogger.success("Đã truy cập thành công! Bàn giao Terminal cho Kernel ảo...\n");

        // Treo luồng gọi để chờ phiên Linux kết thúc (khi người dùng gõ 'exit' hoặc plugin disable)
        started.waitFor();
        if (outputPump != null) {
            try {
                outputPump.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized (this) {
            closeStdinQuietly();
            unregisterShutdownHook();
        }

        OutputLogger.info("Phiên làm việc kết thúc. Đã đóng gói và giải phóng vùng nhớ ảo.");
    }

    /**
     * Gửi một dòng lệnh vào stdin của phiên PRoot đang chạy ở chế độ không tương tác.
     */
    public synchronized boolean sendInput(String command) {
        Process proc = this.prootProcess;
        if (proc == null || !proc.isAlive() || this.processStdin == null || command == null) {
            return false;
        }
        try {
            this.processStdin.write(command);
            this.processStdin.newLine();
            this.processStdin.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isAlive() {
        Process proc = this.prootProcess;
        return proc != null && proc.isAlive();
    }

    public List<String> getRecentLogs() {
        synchronized (recentLogs) {
            return new ArrayList<>(recentLogs);
        }
    }

    /**
     * Dừng tiến trình PRoot an toàn khi Plugin unload hoặc server tắt.
     */
    public synchronized void stopEnvironment() {
        closeStdinQuietly();
        Process proc = this.prootProcess;
        if (proc != null && proc.isAlive()) {
            proc.destroy();
            try {
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            } catch (InterruptedException e) {
                proc.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        unregisterShutdownHook();
    }

    private void appendRecentLog(String line) {
        synchronized (recentLogs) {
            if (recentLogs.size() >= MAX_LOG_LINES) {
                recentLogs.removeFirst();
            }
            recentLogs.addLast(line);
        }
    }

    private void closeStdinQuietly() {
        if (this.processStdin != null) {
            try {
                this.processStdin.close();
            } catch (IOException ignored) {
            }
            this.processStdin = null;
        }
    }

    private void unregisterShutdownHook() {
        if (this.shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
            } catch (IllegalStateException ignored) {
            }
            this.shutdownHook = null;
        }
    }

    private void installRestartCommand(File resolvedRootfs) {
        if (resolvedRootfs == null || !resolvedRootfs.exists()) {
            return;
        }
        try {
            File binDir = new File(resolvedRootfs, "usr/local/bin");
            if (!binDir.exists()) {
                binDir.mkdirs();
            }
            File restartBin = new File(binDir, "mjt-restart");
            String script = "#!/bin/sh\n"
                    + "touch /workspace/" + StartupScriptManager.RESTART_MARKER_FILE + "\n"
                    + "if [ \"$MJT_MODE\" = \"plugin\" ]; then\n"
                    + "    echo \"[MJT-RESTART-PENDING] Da ghi nhan yeu cau khoi dong lai. Dung /mjt confirm (Co) hoac /mjt cancel (Khong).\"\n"
                    + "    exit 0\n"
                    + "fi\n"
                    + "echo \"[MJT] Da ghi nhan yeu cau khoi dong lai. Chuyen sang hop thoai xac nhan...\"\n"
                    + "kill -TERM \"$PPID\" 2>/dev/null || true\n"
                    + "exit 0\n";
            Files.writeString(restartBin.toPath(), script, StandardCharsets.UTF_8);
            restartBin.setExecutable(true, false);
        } catch (IOException ignored) {
        }
    }
}
