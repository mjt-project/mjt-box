package com.mjt.core.config;

import com.mjt.core.ui.ConsoleColors;
import com.mjt.core.ui.OutputLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class ConfigManager {

    private static final String CONFIG_FILE = "mjt.conf";
    private static final Properties properties = new Properties();

    /**
     * Nạp cấu hình từ bên ngoài. Nếu file chưa tồn tại, tự động tạo mới.
     */
    public static void loadConfig() {
        File configFile = MjtPaths.resolve(CONFIG_FILE);

        if (!configFile.exists()) {
            OutputLogger.warning("Không tìm thấy tệp cấu hình ngoại vi. Đang khởi tạo " + CONFIG_FILE + "...");
            createDefaultConfig(configFile);
        }

        try (FileInputStream fis = new FileInputStream(configFile)) {
            properties.load(fis);
            OutputLogger.success("Đã nạp thành công cấu hình từ " + CONFIG_FILE);
        } catch (IOException e) {
            OutputLogger.error("Lỗi đọc tệp cấu hình: " + e.getMessage());
        }
    }

    /**
     * Thuật toán Tự sinh (Auto-generation): Tạo file với các chú thích (Comment) hướng dẫn
     */
    private static void createDefaultConfig(File file) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            String defaultConfig = 
                "# ==================================\n" +
                "# MJT CORE - SYSTEM CONFIGURATION\n" +
                "# ==================================\n\n" +
                
                "# 1. Tự động khởi động (Auto-boot)\n" +
                "# Nếu điền ID hệ điều hành vào đây, hệ thống sẽ bỏ qua menu chọn.\n" +
                "# Ví dụ: ubuntu-22.04 hoặc alpine-latest (Để trống nếu muốn tự chọn)\n" +
                "auto-boot-os=\n\n" +
                
                "# 2. Vô hiệu hóa Bảng hỏi Cấu hình (Debian/Ubuntu)\n" +
                "# Giúp lệnh 'apt install' không bị treo khi đòi cấu hình múi giờ/bàn phím.\n" +
                "debian-frontend-noninteractive=true\n\n" +
                
                "# 3. Ép buộc Kiến trúc (Architecture Override)\n" +
                "# Chỉ dùng khi hệ thống nhận diện sai CPU. Hỗ trợ: x86_64, aarch64, auto\n" +
                "force-architecture=auto\n";

            // Ghi mảng byte ra file vật lý
            fos.write(defaultConfig.getBytes("UTF-8"));
        } catch (IOException e) {
            OutputLogger.error("Không thể ghi tệp cấu hình vật lý: " + e.getMessage());
        }
    }

    /**
     * Truy xuất giá trị cấu hình an toàn (Có Fallback)
     */
    public static String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue).trim();
    }
    
    public static boolean getBoolean(String key, boolean defaultValue) {
        String val = get(key, "");
        if (val.isEmpty()) return defaultValue;
        return Boolean.parseBoolean(val);
    }
}