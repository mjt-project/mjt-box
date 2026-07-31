mjt-core/
├── pom.xml
├── .gitignore
├── README.md
└── src/
    └── main/
        ├── resources/                   # [MỚI] TẦNG DỮ LIỆU TĨNH
        │   └── distro-registry.json     # Chứa danh sách link tải Ubuntu, Debian, Arch...
        └── java/
            └── com/mjt/core/
                ├── ServerMain.java      # Điểm neo khởi chạy (Bắt tham số 'nogui')
                │
                ├── ui/                  # TẦNG 1: GIAO TIẾP & MENU TƯƠNG TÁC
                │   ├── ConsoleMenu.java # Hiển thị danh sách HĐH, bắt phím chọn (1, 2, 3)
                │   └── OutputLogger.java# Quản lý màu sắc và thanh tiến trình (Progress bar)
                │
                ├── boot/                # TẦNG 2: NỘI SOI & KHỞI TẠO
                │   └── HardwareProber.java # Lấy kiến trúc CPU (x86_64, aarch64)
                │
                ├── registry/            # [MỚI] TẦNG 3: QUẢN LÝ BẢN PHÂN PHỐI
                │   ├── DistroManager.java  # Đọc file JSON, lọc HĐH theo CPU
                │   └── DistroManifest.java # Class đại diện cho cấu trúc của 1 HĐH
                │
                ├── provision/           # TẦNG 4: CUNG CẤP TÀI NGUYÊN
                │   ├── ProotFetcher.java   # Tải PRoot
                │   ├── RootfsFetcher.java  # Tải Rootfs (Ubuntu/Debian) được người dùng chọn
                │   └── Extractor.java      # Xử lý bung nén tệp .tar.gz / .tar.xz
                │
                └── engine/              # TẦNG 5: LÕI THAO TÚNG LUỒNG (IPC)
                    ├── ProotOrchestrator.java # Chạy ngầm PRoot với đường dẫn Rootfs động
                    └── IpcStreamHandler.java  # Đoạt quyền và duy trì luồng I/O