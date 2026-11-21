mjt-core/
├── pom.xml
├── .gitignore
├── README.md
├── start.sh
├── STRUCTER.md
└── src/
    └── main/
        ├── resources/                       # TẦNG DỮ LIỆU TĨNH
        │   └── distro-registry.json         # Chứa danh sách link tải Ubuntu, Debian, Alpine...
        └── java/
            └── com/mjt/core/
                ├── ServerMain.java          # Điểm neo khởi chạy (Bắt tham số 'nogui')
                │
                ├── ui/                      # TẦNG 1: GIAO TIẾP & MENU TƯƠNG TÁC
                │   ├── ConsoleMenu.java     # Hiển thị danh sách HĐH, bắt phím chọn (1, 2, 3)
                │   ├── ConsoleColors.java   # Hằng số mã màu ANSI dùng chung
                │   └── OutputLogger.java    # Quản lý màu sắc và thanh tiến trình (Progress bar)
                │
                ├── boot/                    # TẦNG 2: NỘI SOI & KHỞI TẠO
                │   └── HardwareProber.java  # Lấy kiến trúc CPU (x86_64, aarch64)
                │
                ├── config/                  # TẦNG 3: QUẢN LÝ CẤU HÌNH
                │   └── ConfigManager.java   # Đọc/ghi tệp cấu hình mjt.conf
                │
                ├── registry/               # TẦNG 4: QUẢN LÝ BẢN PHÂN PHỐI
                │   ├── DistroManager.java   # Đọc file JSON, lọc HĐH theo CPU
                │   └── DistroManifest.java  # Class đại diện cho cấu trúc của 1 HĐH
                │
                ├── provision/              # TẦNG 5: CUNG CẤP TÀI NGUYÊN
                │   └── DistroProvisioner.java # Tải PRoot, Rootfs và bung nén
                │
                └── engine/                 # TẦNG 6: LÕI THAO TÚNG LUỒNG (IPC)
                    └── ProotOrchestrator.java # Chạy ngầm PRoot với đường dẫn Rootfs động
