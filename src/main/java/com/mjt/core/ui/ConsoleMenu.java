package com.mjt.core.ui;

import com.mjt.core.registry.DistroManager;
import com.mjt.core.registry.DistroManifest;

import java.util.List;
import java.util.Scanner;

public class ConsoleMenu {

    /**
     * Hiển thị danh sách hệ điều hành và ép buộc người dùng chọn đúng
     * @return ID của hệ điều hành được chọn (VD: "ubuntu-22.04")
     */
    public static String showAndSelect() {
        // [TỬ HUYỆT KỸ THUẬT]: KHÔNG ĐƯỢC đóng Scanner (scanner.close())
        // Nếu đóng, luồng System.in sẽ bị hủy vĩnh viễn, Tầng Engine phía sau sẽ bị liệt bàn phím.
        Scanner scanner = new Scanner(System.in);
        List<DistroManifest> distros;

        try {
            // Yêu cầu Tầng Registry nạp dữ liệu từ tệp JSON
            distros = DistroManager.getAvailableDistros();
        } catch (Exception e) {
            System.err.println("[-] Lỗi đọc Sổ cái Hệ điều hành: " + e.getMessage());
            System.err.println("[-] Hệ thống buộc phải dừng.");
            System.exit(1);
            return null; // Unreachable
        }

        if (distros == null || distros.isEmpty()) {
            System.err.println("[-] Không tìm thấy hệ điều hành nào hỗ trợ kiến trúc CPU của bạn.");
            System.exit(1);
        }
        System.out.println("==================================================");
        // Vẽ giao diện Menu
        System.out.println("\n==================================================");
        System.out.println("   DANH SÁCH HỆ ĐIỀU HÀNH HỖ TRỢ (DỰA TRÊN CPU)   ");
        System.out.println("==================================================");

        for (int i = 0; i < distros.size(); i++) {
            System.out.printf("  [%d] %s%n", i + 1, distros.get(i).getDisplayName());
        }
        System.out.println("==================================================");

        // Vòng lặp Vô tận (Infinite Loop) chống người dùng nhập sai
        int choice = -1;
        while (true) {
            System.out.print("\nVui lòng chọn hệ điều hành (1-" + distros.size() + "): ");
            String input = scanner.nextLine().trim();

            try {
                choice = Integer.parseInt(input);
                if (choice >= 1 && choice <= distros.size()) {
                    break; // Thoát vòng lặp nếu nhập số hợp lệ
                } else {
                    System.out.println("[-] Lựa chọn nằm ngoài danh sách. Vui lòng nhập số từ 1 đến " + distros.size());
                }
            } catch (NumberFormatException e) {
                System.out.println("[-] Sai định dạng. Vui lòng chỉ nhập các chữ số (VD: 1, 2).");
            }
        }

        DistroManifest selectedDistro = distros.get(choice - 1);
        System.out.println("\n[*] Bạn đã chọn khởi tạo: " + selectedDistro.getDisplayName());
        
        return selectedDistro.getId();
    }
}