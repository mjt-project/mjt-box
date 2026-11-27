# Changelog

## [0.1.0] - 2026-11-27 (Initial Release)

Phiên bản phát hành đầu tiên **v0.1.0** — kiến trúc Zero-Dependency chạy song song ở cả chế độ **Standalone** và **Minecraft Server Plugin (Paper / Spigot / Purpur / Folia)**.

### Tính năng chính
- **Dual-Mode Architecture (Standalone + Paper/Folia Plugin)**:
  - Đóng gói thành một tệp `server.jar` chuẩn dùng chung (không đóng gói kèm thư viện Bukkit/Spigot; compile-only stubs được loại bỏ khi build).
  - Tự động tránh xung đột `server-port` của Paper bằng cách dịch cổng Smart Multiplexer khi phát hiện trùng cổng.
  - Hỗ trợ tệp thực thi Polyglot Standalone `mjt-core` và `start.sh` tự động tải Portable Temurin JRE 21 (`.mjt-jre/`) nếu hệ thống Linux chưa cài sẵn Java.
- **Rootless Linux Orchestrator (PRoot)**:
  - Hỗ trợ cấp phát tự động `ubuntu-22.04`, `alpine-3.24`, `debian-bullseye` (kèm ánh xạ bí danh `ubuntu`, `alpine`, `debian`).
  - Tải file qua `.part` và giải nén nguyên tử qua thư mục `.tmp`, kiểm tra tính toàn vẹn `isRootfsHealthy(...)` (bao gồm cấu trúc `usrmerge`), tự sửa chữa nếu rootfs cũ bị hỏng/rỗng.
  - Tự dự phòng giải nén `.tar.xz` trên host thiếu `xz` qua `busybox unxz` → `python3 lzma` → static BusyBox x86_64.
- **Startup Commands & Restart Confirmation**:
  - Tự tạo và chuẩn hóa Unix LF cho `startup-commands.sh` (chạy tự động khi PRoot khởi động rồi vẫn giữ nguyên shell hoạt động).
  - Yêu cầu khởi động lại bằng `mjt-restart` (trong PRoot) hoặc `/mjt restart` (trên Paper) luôn yêu cầu xác nhận Có/Không (`y/n` hoặc `/mjt confirm` / `/mjt cancel`).
- **Smart Multiplexer v4 + Mini AI + MCPE NetherNet**:
  - Chia sẻ 1 cổng chung cho TCP + UDP: Tầng 1 (`ssh`/`sftp`/`ftp`), Tầng 2 (`tcp.web_port` theo path/group/`web_main`), Tầng 3 (Round-Robin + Mini AI Q-Score cho `minecraft_pc`, `minecraft_pe`, Other).
  - Hỗ trợ RakNet và viết lại SDP ICE (`NetherNetHelper`) + relay UDP động cho Minecraft Bedrock.
  - Tự động điều chỉnh log tiến trình sạch theo mốc 10%/25% trên Web Panel (`OuiPanel`, `Pterodactyl`) và Paper console.
