package com.mjt.core.registry;

import com.mjt.core.boot.HardwareProber;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DistroManager {

    /**
     * Đọc tệp cấu hình JSON và trả về danh sách HĐH đã được đối chiếu với CPU hiện
     * tại
     */
    public static List<DistroManifest> getAvailableDistros() throws Exception {
        // 1. Đọc tệp distro-registry.json từ thư mục resources
        InputStream is = DistroManager.class.getResourceAsStream("/distro-registry.json");
        if (is == null) {
            throw new RuntimeException("Lỗi: Không tìm thấy tệp distro-registry.json trong resources.");
        }

        // Chuyển luồng byte thành chuỗi String nguyên bản
        String jsonContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 2. Lấy kiến trúc CPU để chuẩn bị lọc link tải (Chỉ lấy link x86_64 HOẶC
        // aarch64)
        String currentCpuArch = HardwareProber.getSystemArchitecture();
        List<DistroManifest> distros = new ArrayList<>();

        // 3. Sử dụng Regex để quét và bóc tách từng khối dữ liệu HĐH trong JSON
        // Regex này bỏ qua dấu ngoặc nhọn, dấu phẩy và gom đúng các giá trị cần thiết.
        String regex = "\"id\"\\s*:\\s*\"([^\"]+)\".*?" +
                "\"display_name\"\\s*:\\s*\"([^\"]+)\".*?" +
                "\"shell_path\"\\s*:\\s*\"([^\"]+)\".*?" +
                "\"x86_64\"\\s*:\\s*\"([^\"]+)\".*?" +
                "\"aarch64\"\\s*:\\s*\"([^\"]+)\"";

        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(jsonContent);

        while (matcher.find()) {
            String id = matcher.group(1);
            String displayName = matcher.group(2);
            String shellPath = matcher.group(3);
            String urlX86 = matcher.group(4);
            String urlArm = matcher.group(5);

            // Thuật toán rẽ nhánh tải: CPU là gì thì lấy link đó, vứt link còn lại
            String targetUrl = currentCpuArch.equals("x86_64") ? urlX86 : urlArm;

            // Đóng gói thành Object và đưa vào danh sách
            distros.add(new DistroManifest(id, displayName, shellPath, targetUrl));
        }

        if (distros.isEmpty()) {
            throw new RuntimeException("Không bóc tách được dữ liệu. Kiểm tra lại cấu trúc file JSON.");
        }

        return distros;
    }

    /**
     * Trả về ID bản phân phối mặc định từ distro-registry.json (ưu tiên Alpine gọn nhẹ).
     */
    public static String getDefaultDistroId() {
        try {
            List<DistroManifest> distros = getAvailableDistros();
            for (DistroManifest d : distros) {
                if (d.getId().toLowerCase().startsWith("alpine")) {
                    return d.getId();
                }
            }
            if (!distros.isEmpty()) {
                return distros.get(0).getId();
            }
        } catch (Exception ignored) {
        }
        return "alpine-3.24";
    }

    /**
     * Truy xuất phiên bản động từ cấu hình Maven (pom.xml)
     */
    public static String getMjtCoreVersion() {
        try (java.io.InputStream is = DistroManager.class.getResourceAsStream("/app.properties")) {
            if (is != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(is);
                return props.getProperty("version", "Unknown");
            }
        } catch (Exception e) {
            // Nuốt lỗi nếu không đọc được file
        }
        return "0.1.0"; // mjt-core fallback version
    }
}