package com.mjt.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import com.mjt.core.config.ConfigManager;
import com.mjt.core.config.MjtPaths;
import com.mjt.core.config.StartupScriptManager;
import com.mjt.core.engine.ProotOrchestrator;
import com.mjt.core.network.NetworkConfig;
import com.mjt.core.network.VirtualNetworkManager;
import com.mjt.core.provision.DistroProvisioner;
import com.mjt.core.registry.DistroManager;
import com.mjt.core.ui.ConsoleMenu;
import com.mjt.core.ui.OutputLogger;

public class ServerMain {

    // Tệp lưu trữ trạng thái vòng đời (State Marker)
    private static final String PROFILE_FILE = ".mjt-profile";

    public static void main(String[] args) {
        MjtPaths.init(new File("."));

        System.out.println("==================================================");
        System.out.println("       MJT Core - Managed Java Terminal           ");
        System.out.println("       Rootless Containerization Framework        ");
        System.out.println("==================================================");

        System.out.println("   Phiên bản hiện tại của MJT Proot: " + DistroManager.getMjtCoreVersion());
        System.out.println("\n==================================================");
        ConfigManager.loadConfig();
        File startupFile = StartupScriptManager.ensureStartupFile();
        System.out.println("[*] Tệp lệnh khởi động: " + startupFile.getPath());

        // Xử lý tham số khởi động (Argument Parsing)
        boolean isNoGui = Arrays.asList(args).contains("nogui");
        if (!isNoGui) {
            // Tương lai: Nếu em tích hợp JFrame/JavaFX, logic rẽ nhánh UI sẽ nằm ở đây.
            // Hiện tại, dù người dùng có gõ 'nogui' hay không, ta vẫn ép chạy nền Console.
            System.out.println("[*] Chế độ UI chưa khả dụng. Ép buộc chạy nền Console (nogui).");
        }

        try {
            String[] env = ensureEnvironmentProvisioned(true, null);
            String targetRootfs = env[0];
            String targetShell = env[1];

            // ==========================================
            // KHỞI ĐỘNG MẠNG ẢO MJT (Virtual Network Manager)
            // ==========================================
            NetworkConfig netConfig = NetworkConfig.load();
            VirtualNetworkManager networkManager = new VirtualNetworkManager(netConfig);
            networkManager.start();

            // ==========================================
            // CHUYỂN GIAO QUYỀN LỰC CHO TẦNG ENGINE (+ XÁC NHẬN RESTART)
            // ==========================================
            ProotOrchestrator engine = new ProotOrchestrator();
            boolean keepRunning = true;
            while (keepRunning) {
                System.out.println("[+] Đang đoạt quyền luồng I/O trực tiếp...\n");
                engine.bootEnvironment(targetRootfs, targetShell);

                boolean requestedByCmd = StartupScriptManager.consumeRestartMarker();
                if (requestedByCmd) {
                    OutputLogger.info("Nhận được yêu cầu chạy lại '" + StartupScriptManager.STARTUP_FILE
                            + "' từ lệnh 'mjt-restart'.");
                } else {
                    OutputLogger.info("Phiên làm việc PRoot vừa kết thúc.");
                }

                if (askRestartConfirmation(System.in, System.out)) {
                    OutputLogger.info("Đã xác nhận! Đang nạp lại cấu hình và khởi động lại PRoot...");
                    ConfigManager.loadConfig();
                    StartupScriptManager.normalizeAndRead();
                } else {
                    keepRunning = false;
                }
            }

            // ==========================================
            // DỌN DẸP: Dừng mạng ảo khi người dùng chọn thoát
            // ==========================================
            networkManager.stop();

        } catch (Exception e) {
            System.err.println("\n[MJT-Fatal] Lỗi hệ thống nghiêm trọng: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Hỏi xác nhận Có/Không trước khi khởi động lại PRoot và chạy lại
     * startup-commands.sh.
     * Không đóng luồng đầu vào để bảo vệ System.in.
     */
    public static boolean askRestartConfirmation(InputStream in, PrintStream out) {
        out.println("\n==================================================");
        out.println("[?] Bạn có muốn khởi động lại PRoot để chạy lại '"
                + StartupScriptManager.STARTUP_FILE + "' không?");
        out.print("    Nhập 'y' / 'co' (Có) hoặc 'n' / 'khong' (Không) [y/N]: ");
        out.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    return false;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || StartupScriptManager.isNegative(trimmed)) {
                    return false;
                }
                if (StartupScriptManager.isAffirmative(trimmed)) {
                    return true;
                }
                out.print("[-] Lựa chọn không hợp lệ. Vui lòng nhập 'y' (Có) hoặc 'n' (Không) [y/N]: ");
                out.flush();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Đảm bảo PRoot + Rootfs đã được cài đặt và đồng bộ hóa với .mjt-profile trong
     * MjtPaths.
     * Dùng chung cho cả chế độ Standalone và chế độ Plugin (Paper).
     */
    public static String[] ensureEnvironmentProvisioned(boolean allowInteractiveMenu, String fallbackDistroId)
            throws Exception {
        File profile = MjtPaths.resolve(PROFILE_FILE);
        String targetRootfs = null;
        String targetShell = null;

        boolean needsProvisioning = true;

        if (profile.exists()) {
            String profileData = Files.readString(profile.toPath()).trim();
            String[] parts = profileData.split("\\|");
            targetRootfs = parts[0];
            targetShell = parts.length > 1 ? parts[1] : "/bin/sh";

            File rootfsDir = MjtPaths.resolve(targetRootfs);
            File prootBinary = MjtPaths.resolve("proot");

            if (DistroProvisioner.isRootfsHealthy(rootfsDir, targetShell)
                    && prootBinary.exists() && prootBinary.length() > 0) {
                needsProvisioning = false;
                OutputLogger.success("Tìm thấy Profile hợp lệ: " + targetRootfs);
            } else {
                OutputLogger.warning("Phát hiện dữ liệu vật lý bị mất hoặc chưa giải nén hoàn chỉnh (Rootfs/PRoot)!");
                OutputLogger.info("Đang dọn dẹp cấu hình rác và tiến hành Tự chữa lành (Self-Healing)...");
                profile.delete();
            }
        }

        if (needsProvisioning) {
            OutputLogger.info("Kích hoạt Setup Wizard...");

            String autoBootConfig = ConfigManager.get("auto-boot-os", "");
            String distroId;

            if (!autoBootConfig.isEmpty()) {
                OutputLogger.info("Chế độ Auto-boot được kích hoạt: " + autoBootConfig);
                distroId = autoBootConfig;
            } else if (allowInteractiveMenu) {
                distroId = ConsoleMenu.showAndSelect();
            } else {
                distroId = (fallbackDistroId != null && !fallbackDistroId.isEmpty())
                        ? fallbackDistroId
                        : DistroManager.getDefaultDistroId();
                OutputLogger.warning("Chế độ không tương tác (Plugin): sử dụng distro mặc định '" + distroId
                        + "' (có thể đổi bằng auto-boot-os trong mjt.conf).");
            }

            String[] provisionResult = DistroProvisioner.install(distroId);
            targetRootfs = provisionResult[0];
            targetShell = provisionResult[1];

            Files.writeString(profile.toPath(), targetRootfs + "|" + targetShell);
            OutputLogger.success("Đã lưu cấu hình vòng đời mới vào " + profile.getPath());
        }

        return new String[] { targetRootfs, targetShell };
    }
}