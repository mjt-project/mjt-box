# MJT Core (Managed Java Terminal)

> A lightweight, pure-Java orchestrator for provisioning and managing rootless Linux environments.

MJT Core là một nền tảng điều phối (Orchestrator) được viết hoàn toàn bằng Java, cho phép khởi tạo, quản lý và thao tác I/O trực tiếp với các phân vùng Linux không đặc quyền (Rootless) thông qua giao diện Console được tối ưu hóa.

## 🏗 Kiến trúc Hệ thống
- **Zero-Dependency:** Sử dụng 100% thư viện chuẩn của Java 11+, không yêu cầu Maven dependencies ngoại vi.
- **Dynamic Provisioning:** Tự động tải và cấu hình hạt nhân hệ thống tại thời điểm chạy (Runtime).
- **Stream Hijacking:** Quản trị bất đồng bộ luồng I/O (Standard Input/Output) để ngăn chặn kẹt luồng (Deadlock) trên Console.

## ⚖️ Pháp lý & Ghi nhận (Acknowledgements)
MJT Core là một lớp giao tiếp và điều phối độc lập. Trong quá trình vận hành (Runtime), nền tảng này tự động tải động (dynamic fetch) các công cụ mã nguồn mở sau để tạo dựng không gian ảo hóa:
- **[PRoot](https://proot-me.github.io/):** Licensed under GPL-2.0.
- **[Alpine Linux](https://alpinelinux.org/):** Mini Rootfs.
*Tất cả quyền sở hữu trí tuệ của các công cụ trên thuộc về tác giả gốc. MJT Core không nhúng (link) hay phân phối lại các tệp nhị phân này.*

## 📜 Giấy phép (License)
Dự án được phân phối dưới giấy phép **GPL-3.0**. Xem tệp `LICENSE` để biết thêm chi tiết.