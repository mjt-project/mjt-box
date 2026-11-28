# MJT Box — Release `v0.1.0`

> Multi-Protocol Java Tunnel & Rootless Linux Sandbox — Zero-Dependency, Dual-Mode (Standalone + Bukkit / SpigotMC / Paper / Purpur Plugin).

**MJT Box** là nền tảng điều phối hạ tầng gọn nhẹ viết hoàn toàn bằng Java 11+ (0 Maven dependencies bên ngoài), biến một runtime Java hoặc Minecraft Server (Bukkit / SpigotMC / Paper / Purpur) bất kỳ thành hộp môi trường đa năng gồm **Rootless Linux (PRoot)** và **Smart Port Multiplexer TCP/UDP**.

---

## Tính năng nổi bật

- **Dual-Mode Runtime (`mjt-box_<version>.jar`)**:
  - **Standalone**: Chạy trực tiếp bằng `java -jar mjt-box_0.1.0.jar nogui` hoặc `./start.sh` trên Linux (tự tải Portable Temurin JRE 21 vào `.mjt-jre/` nếu máy chưa có `java`).
  - **Minecraft Plugin (`MJTBox`)**: Hỗ trợ dòng **Bukkit / CraftBukkit / SpigotMC / Paper / Purpur** (không hỗ trợ Folia). Thả `mjt-box_0.1.0.jar` vào `plugins/` — dùng trực tiếp JVM của server, không cần cài thêm thư viện Java ngoài, tự né `server-port`.
- **Rootless Linux Provisioning**:
  - Tự tải PRoot tĩnh và hệ điều hành (`ubuntu-22.04`, `alpine-3.24`, `debian-bullseye`), giải nén nguyên tử (`.part` / `.tmp`), tự chữa lành thư mục hỏng và tự dự phòng `.tar.xz` qua `busybox unxz` / `python3 lzma` / static BusyBox khi host thiếu `xz`.
- **Startup Script & Xác nhận Restart**:
  - Hỗ trợ `startup-commands.sh` tự động chạy lúc khởi động mà vẫn giữ phiên shell Linux hoạt động.
  - Lệnh `mjt-restart` (Standalone/Container) và `/mjt restart` → `/mjt confirm` | `/mjt cancel` (Paper) luôn yêu cầu xác nhận Có/Không trước khi khởi động lại PRoot.
- **Smart Port Multiplexer v4 & Mini AI**:
  - Một cổng chung cho TCP + UDP: Tầng 1 (`service`: SSH/SFTP/FTP), Tầng 2 (`tcp.web_port`: path/group/auto), Tầng 3 (`Other` với Round-Robin + Mini AI Offline Q-Score), kèm hỗ trợ Minecraft Java & Bedrock (`RakNet` / `NetherNet` SDP ICE).

---

## Hướng dẫn nhanh

### 1. Build từ mã nguồn
```bash
mvn package -DskipTests
```
Sản phẩm sau khi build duy nhất tại:
- `target/mjt-box_<phiên_bản>.jar` (ví dụ: `target/mjt-box_0.1.0.jar`): dùng trực tiếp cho cả chế độ Standalone và Plugin Minecraft.

### 2. Chế độ Standalone
```bash
java -jar mjt-box_0.1.0.jar nogui
# Hoặc trên Linux (tự tải JRE nếu chưa cài Java):
chmod +x start.sh && ./start.sh
```
Các tệp cấu hình tự sinh tại thư mục chạy: `mjt.conf`, `network-config.json`, `startup-commands.sh`.

### 3. Chế độ Minecraft Plugin — Bukkit / SpigotMC / Paper / Purpur (`MJTBox`)
1. Chép `target/mjt-box_0.1.0.jar` vào `plugins/` và khởi động server.
2. Thư mục dữ liệu plugin nằm tại `plugins/MJTBox/` (trên PatchBukkit nằm tại `plugins/data/patchbukkit/paper-runtime/plugins/MJTBox/`).
3. Các lệnh quản trị (quyền `mjt.admin`, mặc định OP):
   - `/mjt status` — xem trạng thái mạng ảo, PRoot và đường dẫn `startup-commands.sh`
   - `/mjt setup [ubuntu-22.04|alpine-3.24|debian-bullseye]` — tải và khởi tạo PRoot Linux
   - `/mjt exec <lệnh>` / `/mjt log [số dòng]` — chạy lệnh shell và xem log PRoot
   - `/mjt restart` → `/mjt confirm` hoặc `/mjt cancel` — xác nhận Có/Không trước khi khởi động lại PRoot
   - `/mjt reload` / `/mjt stop-os` — nạp lại cấu hình / dừng container PRoot

---

# Pháp lý & Ghi nhận (Acknowledgements)
**MJT Box** là một nền tảng điều phối và quản lý môi trường Linux độc lập. Trong quá trình vận hành (Runtime), hệ thống có thể tự động tải (dynamic fetch) các thành phần mã nguồn mở từ nguồn phát hành chính thức để tạo môi trường làm việc. **MJT Box không nhúng (embed), không liên kết (link), không chỉnh sửa và không phân phối lại** các thành phần này.

Các dự án và tài nguyên được sử dụng bao gồm:

| Thành phần | Giấy phép | Nguồn chính thức |
|------------|-----------|------------------|
| PRoot | GPL-2.0 | https://github.com/proot-me/proot |
| Alpine Linux Mini RootFS | MIT License | https://alpinelinux.org/downloads/ |
| Debian Minimal RootFS | Debian Free Software Guidelines (DFSG) | https://www.debian.org/ |
| Ubuntu 22.04 Minimal RootFS | Ubuntu / Canonical Licensing | https://ubuntu.com/download/server |

Toàn bộ bản quyền, nhãn hiệu, giấy phép và quyền sở hữu trí tuệ của các thành phần trên thuộc về tác giả hoặc tổ chức phát triển tương ứng. MJT Box chỉ tự động tải các thành phần này từ nguồn phát hành chính thức trong quá trình cài đặt và vận hành.

---

# Giấy phép (License)

Mã nguồn của **MJT Box** được phát hành theo giấy phép được chỉ định trong tệp **LICENSE** của dự án.

Các thành phần mã nguồn mở được MJT Box sử dụng vẫn tuân theo giấy phép riêng của từng dự án và **không bị thay đổi hoặc thay thế** bởi giấy phép của MJT Box.
