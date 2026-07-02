package com.mjt.core.boot;

import com.mjt.core.ui.OutputLogger;

public class HardwareProber {

    /**
     * Nội soi kiến trúc CPU và chuẩn hóa định danh (Normalization)
     * @return Chuỗi kiến trúc đã chuẩn hóa ("x86_64" hoặc "aarch64")
     */
    public static String getSystemArchitecture() {
        // Lấy thông tin thô từ JVM
        String rawArch = System.getProperty("os.arch").toLowerCase();
        String normalizedArch;

        // Phân loại và Ánh xạ (Mapping)
        if (rawArch.contains("amd64") || rawArch.contains("x86_64")) {
            normalizedArch = "x86_64";
        } else if (rawArch.contains("aarch64") || rawArch.contains("arm64")) {
            normalizedArch = "aarch64";
        } else {
            // Cơ chế phòng vệ (Fallback Mechanism) cho các chip lạ
            OutputLogger.warning("Phát hiện kiến trúc CPU lạ: " + rawArch);
            OutputLogger.warning("Hệ thống sẽ ép buộc tải bản x86_64. Có thể xảy ra lỗi tương thích!");
            normalizedArch = "x86_64"; 
        }

        return normalizedArch;
    }

    /**
     * Xác thực hệ điều hành vật lý (Host OS)
     * PRoot chỉ hoạt động hoàn hảo trên Kernel Linux. Cần cảnh báo nếu user chạy trên Windows/Mac.
     */
    public static void verifyHostEnvironment() {
        String osName = System.getProperty("os.name").toLowerCase();
        
        if (osName.contains("win")) {
            OutputLogger.warning("Phát hiện môi trường Windows. Đang chạy trong lớp giả lập hệ thống tệp.");
            OutputLogger.warning("Khuyến nghị: Chạy ứng dụng này qua WSL2 để PRoot không bị lỗi Socket.");
        } else if (osName.contains("mac")) {
            OutputLogger.warning("Phát hiện môi trường macOS (Darwin Kernel). PRoot có thể không tương thích hoàn toàn.");
        } else {
            OutputLogger.info("Môi trường gốc: Linux (" + getSystemArchitecture() + "). Đạt điều kiện lý tưởng.");
        }
    }
}