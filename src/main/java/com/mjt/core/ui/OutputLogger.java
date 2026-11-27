package com.mjt.core.ui;

import java.util.function.BiConsumer;

public class OutputLogger {

    // ==========================================
    // BẢNG MÃ MÀU ANSI (ANSI Escape Codes)
    // ==========================================
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_CYAN = "\u001B[36m";

    private static volatile BiConsumer<String, String> logSink = null;
    private static volatile int lastProgressBucket = -1;

    /**
     * Gắn kênh nhận log tùy chỉnh (dùng cho chế độ Plugin như Minecraft Paper).
     * Truyền null để khôi phục chế độ xuất Terminal trực tiếp (Standalone).
     */
    public static void setLogSink(BiConsumer<String, String> sink) {
        logSink = sink;
        lastProgressBucket = -1;
    }

    public static void resetProgress() {
        lastProgressBucket = -1;
    }

    public static void finishProgress() {
        if (logSink == null && System.console() != null) {
            System.out.println();
        }
    }

    /**
     * Ghi log thông tin hệ thống thông thường
     */
    public static void info(String message) {
        BiConsumer<String, String> sink = logSink;
        if (sink != null) {
            sink.accept("INFO", message);
            return;
        }
        System.out.println(ANSI_CYAN + "[*] " + ANSI_RESET + message);
    }

    /**
     * Ghi log thành công
     */
    public static void success(String message) {
        BiConsumer<String, String> sink = logSink;
        if (sink != null) {
            sink.accept("INFO", "[+] " + message);
            return;
        }
        System.out.println(ANSI_GREEN + "[+] " + ANSI_RESET + message);
    }

    /**
     * Ghi log cảnh báo
     */
    public static void warning(String message) {
        BiConsumer<String, String> sink = logSink;
        if (sink != null) {
            sink.accept("WARN", message);
            return;
        }
        System.out.println(ANSI_YELLOW + "[!] " + ANSI_RESET + message);
    }

    /**
     * Ghi log lỗi nghiêm trọng
     */
    public static void error(String message) {
        BiConsumer<String, String> sink = logSink;
        if (sink != null) {
            sink.accept("ERROR", message);
            return;
        }
        System.err.println(ANSI_RED + "[-] " + ANSI_RESET + message);
    }

    /**
     * Vẽ thanh tiến trình động trên cùng một dòng lệnh (Không bị trôi dòng)
     * * @param taskName Tên tác vụ (VD: "Đang tải Ubuntu")
     * @param currentBytes Số byte đã xử lý
     * @param totalBytes Tổng số byte (-1 nếu máy chủ không trả về Content-Length)
     */
    public static void printProgress(String taskName, long currentBytes, long totalBytes) {
        BiConsumer<String, String> sink = logSink;
        if (sink != null) {
            if (totalBytes > 0) {
                int percent = (int) ((currentBytes * 100) / totalBytes);
                int bucket = percent / 25;
                if (currentBytes == 0 || bucket > lastProgressBucket || currentBytes >= totalBytes) {
                    lastProgressBucket = bucket;
                    double currentMb = currentBytes / (1024.0 * 1024.0);
                    double totalMb = totalBytes / (1024.0 * 1024.0);
                    sink.accept("INFO", String.format("%s: %d%% (%.2f/%.2f MB)", taskName, percent, currentMb, totalMb));
                }
            }
            return;
        }

        // Trong môi trường web panel / pipe (Pterodactyl, OuiPanel: System.console() == null),
        // ký tự \r không ghi đè dòng mà gây spam log → xuất log sạch mỗi mốc 10%.
        if (System.console() == null) {
            if (totalBytes > 0) {
                int percent = (int) ((currentBytes * 100) / totalBytes);
                int bucket = percent / 10;
                if (currentBytes == 0 || bucket > lastProgressBucket || currentBytes >= totalBytes) {
                    lastProgressBucket = bucket;
                    double currentMb = currentBytes / (1024.0 * 1024.0);
                    double totalMb = totalBytes / (1024.0 * 1024.0);
                    info(String.format("%s: %d%% (%.2f/%.2f MB)", taskName, percent, currentMb, totalMb));
                }
            }
            return;
        }
        if (totalBytes <= 0) {
            // Trường hợp máy chủ ẩn dung lượng file (Chunked Transfer Encoding)
            double megabytes = currentBytes / (1024.0 * 1024.0);
            System.out.print("\r" + ANSI_YELLOW + "[~] " + taskName + ": " 
                    + String.format("%.2f MB", megabytes) + " đã tải..." + ANSI_RESET);
            return;
        }

        // Tính toán phần trăm và dung lượng
        int percent = (int) ((currentBytes * 100) / totalBytes);
        double currentMb = currentBytes / (1024.0 * 1024.0);
        double totalMb = totalBytes / (1024.0 * 1024.0);

        // Vẽ thanh Block (Độ dài 40 ký tự)
        int barLength = 40;
        int filledLength = (int) (barLength * currentBytes / totalBytes);
        
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barLength; i++) {
            if (i < filledLength) {
                bar.append("=");
            } else if (i == filledLength) {
                bar.append(">");
            } else {
                bar.append(" ");
            }
        }
        bar.append("]");

        // Dấu \r (Carriage Return) lùi con trỏ về đầu dòng để ghi đè lên dòng cũ
        System.out.print("\r" + ANSI_CYAN + "[*] " + taskName + " " + bar.toString() 
                + " " + percent + "% (" + String.format("%.2f/%.2f MB", currentMb, totalMb) + ")" + ANSI_RESET);
                
        // Khi đạt 100%, tự động ngắt dòng để các log sau không bị in đè lên
        if (currentBytes >= totalBytes) {
            System.out.println();
        }
    }
}