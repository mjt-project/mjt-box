package com.mjt.core.provision;

import com.mjt.core.boot.HardwareProber;
import com.mjt.core.registry.DistroManager;
import com.mjt.core.registry.DistroManifest;
import com.mjt.core.ui.OutputLogger;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DistroProvisioner {

    /**
     * Hàm lõi điều phối toàn bộ luồng cài đặt
     * @return Mảng 2 phần tử: [Tên_Thư_mục_Rootfs, Đường_dẫn_Shell]
     */
    public static String[] install(String distroId) throws Exception {
        // 1. Đối chiếu ID để lấy khuôn mẫu (Manifest)
        DistroManifest manifest = getManifestById(distroId);
        if (manifest == null) {
            throw new RuntimeException("Lỗi cấu trúc: Không tìm thấy dữ liệu cho ID " + distroId);
        }

        OutputLogger.info("Bắt đầu tiến trình cấp phát tài nguyên cho: " + manifest.getDisplayName());

        // 2. Chuẩn bị lõi thực thi PRoot
        File prootFile = new File("proot");
        if (!prootFile.exists()) {
            OutputLogger.warning("Thiếu lõi PRoot. Đang truy xuất phiên bản mới nhất từ GitHub...");
            String prootUrl = fetchDynamicProotUrl();
            downloadFile(prootUrl, prootFile.toPath(), "Lõi PRoot");
            
            // Cấp quyền thực thi mức hệ thống (Tránh lệnh chmod)
            prootFile.setExecutable(true, false);
            OutputLogger.success("Đã cấp quyền thực thi cho lõi ảo hóa.");
        }

        // 3. Chuẩn bị Hệ thống tệp gốc (Rootfs)
        String targetDirName = distroId + "-fs";
        File rootfsDir = new File(targetDirName);
        
        if (!rootfsDir.exists()) {
            // Lấy URL và trích xuất tên file (VD: ubuntu-base.tar.gz)
            String rootfsUrl = manifest.getDownloadUrl();
            String fileName = rootfsUrl.substring(rootfsUrl.lastIndexOf('/') + 1);
            File archiveFile = new File(fileName);

            if (!archiveFile.exists()) {
                OutputLogger.info("Đang kết nối đến máy chủ kho lưu trữ gốc...");
                downloadFile(rootfsUrl, archiveFile.toPath(), manifest.getDisplayName());
            }

            // Giải nén bằng công cụ tar của Container
            OutputLogger.info("Đang bung nén không gian bộ nhớ (Quá trình này có thể mất vài phút)...");
            rootfsDir.mkdir();
            
            // Lệnh tar tự động nhận diện cả .tar.gz và .tar.xz
            Process tarProcess = new ProcessBuilder("tar", "-xf", fileName, "-C", targetDirName).start();
            int exitCode = tarProcess.waitFor();
            
            if (exitCode != 0) {
                throw new RuntimeException("Lỗi bung nén Rootfs! Mã thoát: " + exitCode);
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

    private static DistroManifest getManifestById(String id) throws Exception {
        List<DistroManifest> distros = DistroManager.getAvailableDistros();
        for (DistroManifest d : distros) {
            if (d.getId().equals(id)) {
                return d;
            }
        }
        return null;
    }

    private static void downloadFile(String url, Path target, String taskName) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
                
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        
        // Đoạt quyền theo dõi luồng byte tải về
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));

        if (response.statusCode() != 200) {
            throw new IOException("Máy chủ từ chối kết nối HTTP: " + response.statusCode());
        }
        OutputLogger.success("Tải hoàn tất: " + taskName);
    }


    /**
     * Dò tìm URL PRoot tĩnh dựa trên CPU hiện tại
     * (Đã khóa cứng ở bản v5.3.0 để chống lỗi API thiếu tệp nhị phân)
     */
    private static String fetchDynamicProotUrl() {
        String arch = HardwareProber.getSystemArchitecture();
        
        // Trỏ thẳng về bản v5.3.0 (Bản ổn định cuối cùng có đính kèm file tĩnh)
        if (arch.equals("x86_64")) {
            return "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-x86_64-static";
        } else if (arch.equals("aarch64")) {
            return "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static";
        }
        
        throw new RuntimeException("Không tìm thấy tệp lõi PRoot cho kiến trúc: " + arch);
    }
}