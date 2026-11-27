mjt-box/
├── pom.xml
├── .gitignore
├── README.md
├── CHANGELOG.md
├── start.sh
├── build-binary.sh
├── STRUCTER.md
└── src/
    └── main/
        ├── resources/                       # TẦNG DỮ LIỆU TĨNH
        │   ├── app.properties               # Phiên bản đồng bộ từ Maven (${project.version})
        │   ├── distro-registry.json         # Chứa danh sách link tải Ubuntu, Debian, Alpine...
        │   └── plugin.yml                   # Khai báo entrypoint Bukkit/SpigotMC/Paper/Purpur (MJTBox)
        └── java/
            ├── org/bukkit/                  # STUB COMPILE-ONLY (bị loại khỏi JAR khi build)
            │   ├── Server.java
            │   ├── command/                 # Command, CommandSender, CommandExecutor, PluginCommand...
            │   └── plugin/                  # Plugin, PluginDescriptionFile, JavaPlugin
            └── com/mjt/box/
                ├── ServerMain.java          # Điểm neo Standalone (Bắt tham số 'nogui')
                │
                ├── paper/                   # TẦNG TÍCH HỢP MINECRAFT SERVER
                │   └── MjtPaperPlugin.java  # Plugin Bukkit/SpigotMC/Paper/Purpur (/mjt, tự tránh đụng cổng)
                │
                ├── ui/                      # TẦNG 1: GIAO TIẾP & MENU TƯƠNG TÁC
                │   ├── ConsoleMenu.java     # Hiển thị danh sách HĐH, bắt phím chọn (1, 2, 3)
                │   ├── ConsoleColors.java   # Hằng số mã màu ANSI dùng chung
                │   └── OutputLogger.java    # Quản lý màu sắc, log sink & progress không spam web panel
                │
                ├── boot/                    # TẦNG 2: NỘI SOI & KHỞI TẠO
                │   └── HardwareProber.java  # Lấy kiến trúc CPU (x86_64, aarch64)
                │
                ├── config/                  # TẦNG 3: QUẢN LÝ CẤU HÌNH
                │   ├── ConfigManager.java   # Đọc/ghi tệp cấu hình mjt.conf
                │   ├── MjtPaths.java        # Chuẩn hóa thư mục gốc (./ hoặc plugins/MJTBox)
                │   └── StartupScriptManager.java # Tệp lệnh khởi động startup-commands.sh & xác nhận restart
                │
                ├── registry/                # TẦNG 4: QUẢN LÝ BẢN PHÂN PHỐI
                │   ├── DistroManager.java   # Đọc file JSON, lọc HĐH theo CPU & phiên bản MJT Box
                │   └── DistroManifest.java  # Class đại diện cho cấu trúc của 1 HĐH
                │
                ├── provision/               # TẦNG 5: CUNG CẤP TÀI NGUYÊN
                │   └── DistroProvisioner.java # Tải PRoot, Rootfs nguyên tử & dự phòng giải nén .tar.xz
                │
                ├── network/                 # TẦNG 6: MẠNG ẢO (Virtual Network Manager)
                │   ├── VirtualNetworkManager.java # Bộ điều phối mạng ảo tổng
                │   ├── NetworkConfig.java   # Đọc/ghi cấu hình mạng (service, tcp, udp, ai_assistant)
                │   ├── SimpleJsonParser.java # Bộ phân tích JSON đệ quy thuần Java (Zero-Dependency)
                │   ├── ServiceEndpoint.java # Mô hình điểm đích dịch vụ (ip, port/auto, path, group, type)
                │   ├── PortMapping.java     # Lớp dữ liệu ánh xạ cổng ảo
                │   ├── TcpForwarder.java    # Chuyển tiếp TCP (cổng → cổng)
                │   ├── UdpForwarder.java    # Chuyển tiếp UDP (cổng → cổng)
                │   ├── MinecraftRouter.java # Định tuyến MC Protocol (nhiều server/1 cổng)
                │   │
                │   └── multiplexer/         # BỘ DỒN KÊNH ĐA GIAO THỨC TRÊN 1 PORT
                │       ├── SmartMultiplexer.java # Xác minh chủ động 3 tầng + luồng UDP tích hợp
                │       ├── NetherNetHelper.java  # Ánh xạ SDP ICE & cổng UDP động cho MCPE Bedrock
                │       ├── MiniAiEngine.java     # AI mini offline/online phân loại & Q-Score cho Other
                │       ├── ProtocolSniffer.java  # Nhận diện & xác minh HTTP, Minecraft, SSH, TLS
                │       ├── MultiplexRule.java    # Quy tắc định tuyến theo giao thức/host/path
                │       └── ProtocolType.java     # Định danh loại giao thức
                │
                └── engine/                  # TẦNG 7: LÕI THAO TÚNG LUỒNG (IPC)
                    └── ProotOrchestrator.java # Chạy PRoot tương tác/không tương tác + startup-commands.sh
