package com.mjt.core.provision;

import com.mjt.core.boot.HardwareProber;
import com.mjt.core.registry.DistroManager;
import com.mjt.core.registry.DistroManifest;
import com.mjt.core.config.MjtPaths;
import com.mjt.core.ui.OutputLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

public class DistroProvisioner {

    /**
     * Hàm lõi điều phối toàn bộ luồng cài đặt
     * @return Mảng 2 phần tử: [Tên_Thư_mục_Rootfs, Đường_dẫn_Shell]
     */
    public static String[] install(String distroId) throws Exception {
        // 1. Đối chiếu ID để lấy khuôn mẫu (Manifest)
        DistroManifest manifest = getManifestById(distroId);

        OutputLogger.info("Bắt đầu tiến trình cấp phát tài nguyên cho: " + manifest.getDisplayName());

        // 2. Chuẩn bị lõi thực thi PRoot
        File prootFile = MjtPaths.resolve("proot");
        if (!prootFile.exists()) {
            OutputLogger.warning("Thiếu lõi PRoot. Đang truy xuất phiên bản mới nhất từ GitHub...");
            String prootUrl = fetchDynamicProotUrl();
            downloadFile(prootUrl, prootFile.toPath(), "Lõi PRoot");
            
            // Cấp quyền thực thi mức hệ thống (Tránh lệnh chmod)
            prootFile.setExecutable(true, false);
            OutputLogger.success("Đã cấp quyền thực thi cho lõi ảo hóa.");
        }

        // 3. Chuẩn bị Hệ thống tệp gốc (Rootfs)
        String targetDirName = manifest.getId() + "-fs";
        File rootfsDir = MjtPaths.resolve(targetDirName);
        
        if (!rootfsDir.exists()) {
            // Lấy URL và trích xuất tên file (VD: ubuntu-base.tar.gz)
            String rootfsUrl = manifest.getDownloadUrl();
            String fileName = rootfsUrl.substring(rootfsUrl.lastIndexOf('/') + 1);
            File archiveFile = MjtPaths.resolve(fileName);

            if (!archiveFile.exists()) {
                OutputLogger.info("Đang kết nối đến máy chủ kho lưu trữ gốc...");
                downloadFile(rootfsUrl, archiveFile.toPath(), manifest.getDisplayName());
            }

            // Giải nén bằng công cụ tar của Container
            OutputLogger.info("Đang bung nén không gian bộ nhớ (Quá trình này có thể mất vài phút)...");
            rootfsDir.mkdirs();
            
            // Lệnh tar tự động nhận diện cả .tar.gz và .tar.xz
            Process tarProcess = new ProcessBuilder("tar", "-xf", fileName, "-C", targetDirName)
                    .directory(MjtPaths.getBaseDir())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder tarOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(tarProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (tarOutput.length() < 1024) {
                        tarOutput.append(line).append('\n');
                    }
                }
            }
            int exitCode = tarProcess.waitFor();
            
            if (exitCode != 0) {
                String detail = tarOutput.toString().trim();
                throw new RuntimeException("Lỗi bung nén Rootfs! Mã thoát: " + exitCode
                        + (detail.isEmpty() ? "" : " | " + detail));
            }
            OutputLogger.success("Khởi tạo không gian hệ điều hành thành công.");
            
            // Dọn dẹp rác (Xóa file nén sau khi bung xong để tiết kiệm dung lượng)
            archiveFile.delete();
        }

        return new String[]{targetDirName, manifest.getShellPath()};
    }

    // ==========================================
    // CÁC HÀM HỖ TRỢ (HELPER METHODS)
    // ==========================================

    public static DistroManifest getManifestById(String id) throws Exception {
        List<DistroManifest> distros = DistroManager.getAvailableDistros();
        String query = (id == null) ? "" : id.trim();
        if (query.isEmpty()) {
            query = DistroManager.getDefaultDistroId();
        }

        // 1. Khớp chính xác theo ID (không phân biệt hoa/thường)
        for (DistroManifest d : distros) {
            if (d.getId().equalsIgnoreCase(query)) {
                return d;
            }
        }

        // 2. Khớp alias thân thiện (alpine / alpine-latest / ubuntu / debian)
        String lower = query.toLowerCase(Locale.ROOT);
        String familyPrefix = null;
        if ("alpine".equals(lower) || "alpine-latest".equals(lower)) {
            familyPrefix = "alpine";
        } else if ("ubuntu".equals(lower) || "ubuntu-latest".equals(lower)) {
            familyPrefix = "ubuntu";
        } else if ("debian".equals(lower) || "debian-latest".equals(lower)) {
            familyPrefix = "debian";
        } else {
            familyPrefix = lower;
        }

        for (DistroManifest d : distros) {
            if (d.getId().toLowerCase(Locale.ROOT).startsWith(familyPrefix)) {
                if (!d.getId().equalsIgnoreCase(query)) {
                    OutputLogger.info("Ánh xạ bí danh '" + query + "' → '" + d.getId() + "'.");
                }
                return d;
            }
        }

        StringBuilder validIds = new StringBuilder();
        for (DistroManifest d : distros) {
            if (validIds.length() > 0) validIds.append(", ");
            validIds.append(d.getId());
        }
        throw new RuntimeException("Lỗi cấu trúc: Không tìm thấy dữ liệu cho ID '" + query
                + "'. Các ID hợp lệ: [" + validIds + "]");
    }

    private static void downloadFile(String url, Path target, String taskName) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
                
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        
        // Tải về dạng InputStream để theo dõi tiến trình
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("Máy chủ từ chối kết nối HTTP: " + response.statusCode());
        }

        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        
        try (java.io.InputStream in = response.body();
             java.io.OutputStream out = java.nio.file.Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long downloadedBytes = 0;
            int bytesRead;
            
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloadedBytes += bytesRead;
                OutputLogger.printProgress(taskName, downloadedBytes, totalBytes);
            }
        }
        
        System.out.println(); // Ngắt dòng sau progress bar
        OutputLogger.success("Tải hoàn tất: " + taskName);
    }

/**
     * Dò tìm URL PRoot tĩnh (Trỏ thẳng vào kho Archive tĩnh của tác giả)
     */
    private static String fetchDynamicProotUrl() {
        String arch = HardwareProber.getSystemArchitecture();
        OutputLogger.info("Đang kết nối kho lưu trữ lõi cho CPU: " + arch);
        
        if (arch.equals("x86_64") || arch.equals("amd64") || arch.equals("x64")) {
            return "https://raw.githubusercontent.com/proot-me/proot-static-build/master/static/proot-x86_64";
        } else if (arch.equals("x86") || arch.equals("i386")) {
            return "https://raw.githubusercontent.com/proot-me/proot-static-build/master/static/proot-x86";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "https://raw.githubusercontent.com/proot-me/proot-static-build/master/static/proot-arm64";
        } else if (arch.equals("armv8") || arch.equals("armv7l") || arch.equals("armv6l") || arch.equals("armhf") || arch.equals("arm") || arch.equals("armv7")) {
            return "https://raw.githubusercontent.com/proot-me/proot-static-build/master/static/proot-arm";
        } 

        throw new RuntimeException("Kiến trúc CPU không được hỗ trợ: " + arch);
    }
}