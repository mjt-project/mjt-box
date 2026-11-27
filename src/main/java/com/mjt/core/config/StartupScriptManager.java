package com.mjt.core.config;

import com.mjt.core.ui.OutputLogger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Quản lý tệp câu lệnh khởi động (startup-commands.sh) cho cả chế độ Standalone và Plugin.
 * File nằm tại thư mục làm việc của MjtPaths (./ hoặc plugins/MJTCore/).
 */
public class StartupScriptManager {

    public static final String STARTUP_FILE = "startup-commands.sh";
    public static final String RESTART_MARKER_FILE = ".mjt-restart-request";

    public static File getStartupFile() {
        return MjtPaths.resolve(STARTUP_FILE);
    }

    public static File getRestartMarkerFile() {
        return MjtPaths.resolve(RESTART_MARKER_FILE);
    }

    /**
     * Đảm bảo tệp startup-commands.sh tồn tại; nếu chưa có sẽ tự tạo mẫu hướng dẫn.
     */
    public static File ensureStartupFile() {
        File scriptFile = getStartupFile();
        File parent = scriptFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (!scriptFile.exists()) {
            String template =
                    "#!/bin/sh\n"
                    + "# =====================================================================\n"
                    + "# MJT CORE - STARTUP COMMANDS (Chạy tự động bên trong PRoot Linux)\n"
                    + "# =====================================================================\n"
                    + "# Các câu lệnh bạn viết dưới đây sẽ chạy tự động mỗi khi PRoot khởi động\n"
                    + "# (hỗ trợ cả chế độ Standalone và Minecraft Paper Plugin), sau đó vẫn giữ\n"
                    + "# nguyên phiên shell Linux hoạt động.\n"
                    + "#\n"
                    + "# Yêu cầu khởi động lại để chạy lại file này (luôn hỏi xác nhận Có/Không):\n"
                    + "#   - Trong container Linux: gõ lệnh  mjt-restart\n"
                    + "#   - Trên console/chat Paper: gõ lệnh  /mjt restart  ->  /mjt confirm\n"
                    + "#\n"
                    + "# Ví dụ câu lệnh:\n"
                    + "#   echo \"[MJT-Startup] Hệ thống Linux đã khởi động!\"\n"
                    + "#   cd /workspace && ls -la\n"
                    + "#   # python3 -m http.server 8081 &\n"
                    + "# =====================================================================\n\n";
            try {
                Files.writeString(scriptFile.toPath(), template, StandardCharsets.UTF_8);
                scriptFile.setExecutable(true, false);
                OutputLogger.success("Đã tạo tệp lệnh khởi động mẫu: " + scriptFile.getPath());
            } catch (IOException e) {
                OutputLogger.error("Không thể tạo " + STARTUP_FILE + ": " + e.getMessage());
            }
        }
        return scriptFile;
    }

    /**
     * Đọc nội dung tệp startup-commands.sh và chuẩn hóa xuống dòng Unix (LF) để chạy an toàn trong PRoot.
     */
    public static String normalizeAndRead() {
        File scriptFile = ensureStartupFile();
        try {
            String raw = Files.readString(scriptFile.toPath(), StandardCharsets.UTF_8);
            String normalized = raw.replace("\r\n", "\n").replace("\r", "\n");
            if (!normalized.equals(raw)) {
                Files.writeString(scriptFile.toPath(), normalized, StandardCharsets.UTF_8);
            }
            scriptFile.setExecutable(true, false);
            return normalized;
        } catch (IOException e) {
            OutputLogger.warning("Không thể đọc " + STARTUP_FILE + ": " + e.getMessage());
            return "";
        }
    }

    /**
     * Kiểm tra tệp startup-commands.sh có chứa câu lệnh thực thi (bỏ qua dòng trống & comment '#') hay không.
     */
    public static boolean hasExecutableCommands() {
        return countExecutableLines() > 0;
    }

    public static int countExecutableLines() {
        String content = normalizeAndRead();
        if (content.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                count++;
            }
        }
        return count;
    }

    public static boolean consumeRestartMarker() {
        File marker = getRestartMarkerFile();
        if (marker.exists()) {
            marker.delete();
            return true;
        }
        return false;
    }

    public static void clearRestartMarker() {
        File marker = getRestartMarkerFile();
        if (marker.exists()) {
            marker.delete();
        }
    }

    public static boolean isAffirmative(String input) {
        String norm = normalizeAnswer(input);
        return "y".equals(norm) || "yes".equals(norm) || "co".equals(norm)
                || "1".equals(norm) || "true".equals(norm) || "ok".equals(norm);
    }

    public static boolean isNegative(String input) {
        String norm = normalizeAnswer(input);
        return "n".equals(norm) || "no".equals(norm) || "khong".equals(norm)
                || "0".equals(norm) || "false".equals(norm) || "cancel".equals(norm);
    }

    private static String normalizeAnswer(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim().toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(trimmed, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "");
    }
}
