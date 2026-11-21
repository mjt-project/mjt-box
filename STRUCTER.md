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
                ├── network/                # TẦNG 6: MẠNG ẢO (Virtual Network Manager)
                │   ├── VirtualNetworkManager.java # Bộ điều phối mạng ảo tổng
                │   ├── NetworkConfig.java   # Đọc/ghi cấu hình mạng (network-config.json)
                │   ├── PortMapping.java     # Lớp dữ liệu ánh xạ cổng ảo
                │   ├── TcpForwarder.java    # Chuyển tiếp TCP (cổng → cổng)
                │   ├── UdpForwarder.java    # Chuyển tiếp UDP (cổng → cổng)
                │   ├── MinecraftRouter.java # Định tuyến MC Protocol (nhiều server/1 cổng)
                │   │
                │   └── multiplexer/         # [MỚI] BỘ DỒN KÊNH ĐA GIAO THỨC TRÊN 1 PORT
                │       ├── SmartMultiplexer.java # Lắng nghe 1 cổng, dò tìm và thử theo chuỗi ưu tiên
                │       ├── ProtocolSniffer.java  # Nhận diện HTTP, Minecraft, SSH, TLS
                │       ├── MultiplexRule.java    # Quy tắc định tuyến theo giao thức/host/path
                │       └── ProtocolType.java     # Định danh loại giao thức
                │
                └── engine/                 # TẦNG 7: LÕI THAO TÚNG LUỒNG (IPC)
                    └── ProotOrchestrator.java # Chạy ngầm PRoot với đường dẫn Rootfs động
