# MJT Core (Managed Java Terminal)

> A lightweight, pure-Java orchestrator for provisioning and managing rootless Linux environments.

MJT Core là một nền tảng điều phối (Orchestrator) được viết hoàn toàn bằng Java, cho phép khởi tạo, quản lý và thao tác I/O trực tiếp với các phân vùng Linux không đặc quyền (Rootless) thông qua giao diện Console được tối ưu hóa.

## 🏗 Kiến trúc Hệ thống
- **Zero-Dependency:** Sử dụng 100% thư viện chuẩn của Java 11+, không yêu cầu Maven dependencies ngoại vi.
- **Dynamic Provisioning:** Tự động tải và cấu hình hạt nhân hệ thống tại thời điểm chạy (Runtime).
- **Stream Hijacking:** Quản trị bất đồng bộ luồng I/O (Standard Input/Output) để ngăn chặn kẹt luồng (Deadlock) trên Console.

# ⚖️ Pháp lý & Ghi nhận (Acknowledgements)
MJT Core là một nền tảng điều phối và quản lý môi trường Linux độc lập. Trong quá trình vận hành (Runtime), hệ thống có thể tự động tải (dynamic fetch) các thành phần mã nguồn mở từ nguồn phát hành chính thức để tạo môi trường làm việc. MJT Core **không nhúng (embed), không liên kết (link), không chỉnh sửa và không phân phối lại** các thành phần này.

Các dự án và tài nguyên được sử dụng bao gồm:

| Thành phần | Giấy phép | Nguồn chính thức |
|------------|-----------|------------------|
| PRoot | GPL-2.0 | https://github.com/proot-me/proot |
| Alpine Linux Mini RootFS | MIT License | https://alpinelinux.org/downloads/ |
| Debian Minimal RootFS | Debian Free Software Guidelines (DFSG) | https://www.debian.org/ |
| Ubuntu 22.04 Minimal RootFS | Ubuntu / Canonical Licensing | https://ubuntu.com/download/server |

Toàn bộ bản quyền, nhãn hiệu, giấy phép và quyền sở hữu trí tuệ của các thành phần trên thuộc về tác giả hoặc tổ chức phát triển tương ứng. MJT Core chỉ tự động tải các thành phần này từ nguồn phát hành chính thức trong quá trình cài đặt và vận hành.

---

# 📜 Giấy phép (License)

Mã nguồn của **MJT Core** được phát hành theo giấy phép được chỉ định trong tệp **LICENSE** của dự án.

Các thành phần mã nguồn mở được MJT Core sử dụng vẫn tuân theo giấy phép riêng của từng dự án và **không bị thay đổi hoặc thay thế** bởi giấy phép của MJT Core.