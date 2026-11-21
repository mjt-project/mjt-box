package com.mjt.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import com.mjt.core.config.ConfigManager;
import com.mjt.core.engine.ProotOrchestrator;
import com.mjt.core.provision.DistroProvisioner;
import com.mjt.core.registry.DistroManager;
import com.mjt.core.ui.ConsoleColors;
import com.mjt.core.ui.ConsoleMenu;

public class ServerMain {

    // Tệp lưu trữ trạng thái vòng đời (State Marker)
    private static final String PROFILE_FILE = ".mjt-profile";

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("       MJT Core - Managed Java Terminal           ");
        System.out.println("       Rootless Containerization Framework        ");
        System.out.println("==================================================");

        System.out.println("   Phiên bản hiện tại của MJT Proot: " + DistroManager.getMjtCoreVersion());
        System.out.println("\n==================================================");
        ConfigManager.loadConfig();

        // Xử lý tham số khởi động (Argument Parsing)
        boolean isNoGui = Arrays.asList(args).contains("nogui");
        if (!isNoGui) {
            // Tương lai: Nếu em tích hợp JFrame/JavaFX, logic rẽ nhánh UI sẽ nằm ở đây.
            // Hiện tại, dù người dùng có gõ 'nogui' hay không, ta vẫn ép chạy nền Console.
            System.out.println("[*] Chế độ UI chưa khả dụng. Ép buộc chạy nền Console (nogui).");
        }

        try {
            File profile = new File(PROFILE_FILE);
            String targetRootfs = null;
            String targetShell = null;
            
            // Cờ đánh dấu hệ thống có cần cài đặt lại từ đầu không
            boolean needsProvisioning = true; 

            if (profile.exists()) {
                String profileData = Files.readString(Paths.get(PROFILE_FILE)).trim();
                String[] parts = profileData.split("\\|");
                targetRootfs = parts[0];
                targetShell = parts.length > 1 ? parts[1] : "/bin/sh";

                // ==========================================
                // BƯỚC MỚI: SANITY CHECK (KIỂM TRA TOÀN VẸN VẬT LÝ)
                // ==========================================
                File rootfsDir = new File(targetRootfs);
                File prootBinary = new File("proot");

                if (rootfsDir.exists() && prootBinary.exists()) {
                    // Mọi thứ hoàn hảo, không cần cài lại
                    needsProvisioning = false;
                    System.out.println("[+] Tìm thấy Profile hợp lệ: " + targetRootfs);
                } else {
                    // Cảnh báo khi bị xóa trộm
                    System.err.println(ConsoleColors.YELLOW + "[!] Phát hiện dữ liệu vật lý bị mất (Môi trường hoặc PRoot đã bị xóa)!" + ConsoleColors.RESET);
                    System.err.println("[*] Đang dọn dẹp cấu hình rác và tiến hành Tự chữa lành (Self-Healing)...");
                    
                    // Xóa thẳng tay cái file profile báo cáo láo này đi
                    profile.delete(); 
                }
            }

                // ==========================================
                // QUY TRÌNH CÀI ĐẶT (Kích hoạt khi chưa cài, hoặc file bị xóa)
                // ==========================================
                if (needsProvisioning) {
                    System.out.println("[-] Kích hoạt Setup Wizard...");

                    // Đọc biến tự động khởi động từ file cấu hình
                    String autoBootConfig = ConfigManager.get("auto-boot-os",   "");
                    String distroId;

                    if (!autoBootConfig.isEmpty()) {
                        System.out.println("[*] Chế độ Auto-boot được kích hoạt: " +    autoBootConfig);
                        distroId = autoBootConfig; // Lấy luôn ID từ file conf, bỏ qua Menu
                    } else {
                        // Nếu người dùng để trống, mới bật Menu lên cho họ chọn
                        distroId = ConsoleMenu.showAndSelect();
                    }

                    String[] provisionResult = DistroProvisioner.install(distroId);

                    targetRootfs = provisionResult[0];
                    targetShell = provisionResult[1];

                    Files.writeString(Paths.get(PROFILE_FILE), targetRootfs + "|" + targetShell);
                    System.out.println("[+] Đã lưu cấu hình vòng đời mới vào " + PROFILE_FILE);
                }

            // ==========================================
            // CHUYỂN GIAO QUYỀN LỰC CHO TẦNG ENGINE
            // ==========================================
            System.out.println("[+] Đang đoạt quyền luồng I/O trực tiếp...\n");
            ProotOrchestrator engine = new ProotOrchestrator();
            engine.bootEnvironment(targetRootfs, targetShell);

        } catch (Exception e) {
            System.err.println("\n[MJT-Fatal] Lỗi hệ thống nghiêm trọng: " + e.getMessage());
            e.printStackTrace();
        }
    }
}