package com.mjt.box.provision;

import com.mjt.box.boot.HardwareProber;
import com.mjt.box.config.MjtPaths;
import com.mjt.box.registry.DistroManager;
import com.mjt.box.registry.DistroManifest;
import com.mjt.box.ui.OutputLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

public class DistroProvisioner {

    private static final String STATIC_BUSYBOX_X64_URL =
            "https://busybox.net/downloads/binaries/1.35.0-x86_64-linux-musl/busybox";

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
        if (!prootFile.exists() || prootFile.length() == 0) {
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

        if (rootfsDir.exists() && !isRootfsHealthy(rootfsDir, manifest.getShellPath())) {
            OutputLogger.warning("Phát hiện thư mục Rootfs bị lỗi hoặc giải nén dở ("
                    + targetDirName + "). Đang dọn sạch để cài đặt lại...");
            deleteRecursively(rootfsDir);
        }

        if (!isRootfsHealthy(rootfsDir, manifest.getShellPath())) {
            // Lấy URL và trích xuất tên file (VD: ubuntu-base.tar.gz)
            String rootfsUrl = manifest.getDownloadUrl();
            String fileName = rootfsUrl.substring(rootfsUrl.lastIndexOf('/') + 1);
            File archiveFile = MjtPaths.resolve(fileName);

            if (!archiveFile.exists() || archiveFile.length() == 0) {
                OutputLogger.info("Đang kết nối đến máy chủ kho lưu trữ gốc...");
                downloadFile(rootfsUrl, archiveFile.toPath(), manifest.getDisplayName());
            }

            // Giải nén vào thư mục tạm .tmp, chỉ đổi tên thành rootfsDir sau khi xác minh thành công
            OutputLogger.info("Đang bung nén không gian bộ nhớ (Quá trình này có thể mất vài phút)...");
            File stagingDir = MjtPaths.resolve(targetDirName + ".tmp");
            deleteRecursively(stagingDir);
            stagingDir.mkdirs();

            boolean extractedOk = false;
            try {
                extractArchive(archiveFile, stagingDir);
                if (!isRootfsHealthy(stagingDir, manifest.getShellPath())) {
                    throw new RuntimeException("Giải nén kết thúc nhưng không tìm thấy hệ thống tệp gốc hoặc shell ("
                            + manifest.getShellPath() + ").");
                }
                Files.move(stagingDir.toPath(), rootfsDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
                extractedOk = true;
            } finally {
                if (!extractedOk) {
                    deleteRecursively(stagingDir);
                }
            }
            OutputLogger.success("Khởi tạo không gian hệ điều hành thành công.");

            // Dọn dẹp rác (Xóa file nén sau khi bung xong để tiết kiệm dung lượng)
            archiveFile.delete();
        }

        return new String[]{targetDirName, manifest.getShellPath()};
    }

    /**
     * Kiểm tra một thư mục Rootfs có chứa đầy đủ cấu trúc tối thiểu (/etc và shell đích) hay không.
     * Dùng LinkOption.NOFOLLOW_LINKS để không bị lỗi với symlink tuyệt đối (VD: Debian/Alpine) ngoài chroot.
     */
    public static boolean isRootfsHealthy(File rootfsDir, String shellPath) {
        if (rootfsDir == null || !rootfsDir.isDirectory()) {
            return false;
        }
        File etcDir = new File(rootfsDir, "etc");
        if (!etcDir.isDirectory()) {
            return false;
        }
        String relShell = (shellPath == null || shellPath.trim().isEmpty())
                ? "bin/sh"
                : (shellPath.startsWith("/") ? shellPath.substring(1) : shellPath);

        if (existsNoFollow(rootfsDir, relShell) || existsNoFollow(rootfsDir, "usr/" + relShell)) {
            return true;
        }
        return existsNoFollow(rootfsDir, "bin/sh") || existsNoFollow(rootfsDir, "usr/bin/sh");
    }

    private static boolean existsNoFollow(File base, String relativePath) {
        return Files.exists(new File(base, relativePath).toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Xóa đệ quy an toàn, không bao giờ đi theo symbolic link ra ngoài Rootfs.
     */
    public static void deleteRecursively(File file) {
        if (file == null) {
            return;
        }
        Path path = file.toPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isSymbolicLink(path) && Files.isDirectory(path)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    // ==========================================
    // CÁC HÀM HỖ TRỢ (HELPER METHODS)
    // ==========================================

    private static void extractArchive(File archiveFile, File stagingDir) throws Exception {
        String archiveName = archiveFile.getName();
        String stagingName = stagingDir.getName();
        String lower = archiveName.toLowerCase(Locale.ROOT);

        ProcessBuilder pb;
        if (lower.endsWith(".tar.xz") || lower.endsWith(".txz")) {
            pb = buildTarXzExtractor(archiveName, stagingName);
        } else {
            pb = new ProcessBuilder("tar", "-xf", archiveName, "-C", stagingName);
        }

        pb.directory(MjtPaths.getBaseDir()).redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 1500) {
                    output.append(line).append('\n');
                }
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String detail = output.toString().trim();
            if (detail.contains("truncated") || detail.contains("unexpected EOF")
                    || detail.contains("File format not recognized")
                    || detail.contains("corrupt")) {
                archiveFile.delete();
            }
            throw new RuntimeException("Lỗi bung nén Rootfs! Mã thoát: " + exitCode
                    + (detail.isEmpty() ? "" : " | " + detail));
        }
    }

    private static ProcessBuilder buildTarXzExtractor(String archiveName, String stagingName) throws Exception {
        if (hasHostBinary("xz")) {
            return new ProcessBuilder("tar", "-xf", archiveName, "-C", stagingName);
        }

        if (hasHostCommand("busybox unxz --help >/dev/null 2>&1")) {
            OutputLogger.info("Host không có lệnh 'xz' — chuyển sang 'busybox unxz' để giải nén .tar.xz...");
            return new ProcessBuilder("/bin/sh", "-c",
                    "busybox unxz -c \"$1\" | tar -xf - -C \"$2\"",
                    "_", archiveName, stagingName);
        }

        if (hasHostCommand("python3 -c \"import lzma\" >/dev/null 2>&1")) {
            OutputLogger.info("Host không có lệnh 'xz' — chuyển sang 'python3 lzma' để giải nén .tar.xz...");
            return new ProcessBuilder("/bin/sh", "-c",
                    "python3 -c 'import lzma,sys,shutil; f=lzma.open(sys.argv[1]); shutil.copyfileobj(f,sys.stdout.buffer)' \"$1\" | tar -xf - -C \"$2\"",
                    "_", archiveName, stagingName);
        }

        String arch = HardwareProber.getSystemArchitecture();
        if ("x86_64".equals(arch)) {
            File busyboxBin = MjtPaths.resolve(".mjt-busybox");
            if (!busyboxBin.exists() || busyboxBin.length() == 0) {
                OutputLogger.warning("Host thiếu 'xz'. Đang tải static BusyBox (~1 MB) để giải nén file .tar.xz...");
                downloadFile(STATIC_BUSYBOX_X64_URL, busyboxBin.toPath(), "BusyBox (unxz)");
                busyboxBin.setExecutable(true, false);
            } else if (!busyboxBin.canExecute()) {
                busyboxBin.setExecutable(true, false);
            }
            return new ProcessBuilder("/bin/sh", "-c",
                    "\"$1\" unxz -c \"$2\" | tar -xf - -C \"$3\"",
                    "_", busyboxBin.getAbsolutePath(), archiveName, stagingName);
        }

        throw new RuntimeException(
                "Host thiếu công cụ 'xz' (và 'busybox unxz' / 'python3 lzma') để giải nén file .tar.xz! "
                        + "Hãy chọn Ubuntu 22.04 hoặc Alpine 3.24 (dùng .tar.gz).");
    }

    private static boolean hasHostBinary(String binName) {
        return hasHostCommand("command -v " + binName + " >/dev/null 2>&1");
    }

    private static boolean hasHostCommand(String shellTest) {
        try {
            Process p = new ProcessBuilder("/bin/sh", "-c", shellTest)
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

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
        String familyPrefix;
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
        Path tempTarget = target.resolveSibling(target.getFileName().toString() + ".part");
        OutputLogger.resetProgress();

        boolean completed = false;
        try {
            try (java.io.InputStream in = response.body();
                 java.io.OutputStream out = Files.newOutputStream(tempTarget)) {
                byte[] buffer = new byte[8192];
                long downloadedBytes = 0;
                int bytesRead;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloadedBytes += bytesRead;
                    OutputLogger.printProgress(taskName, downloadedBytes, totalBytes);
                }
            }
            if (Files.size(tempTarget) <= 0) {
                throw new IOException("File tải về bị rỗng: " + taskName);
            }
            Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
            completed = true;
        } finally {
            if (!completed) {
                try {
                    Files.deleteIfExists(tempTarget);
                } catch (IOException ignored) {
                }
            }
        }

        OutputLogger.finishProgress();
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
