# TikTok Sticker Downloader

Module LSPosed hỗ trợ tải sticker trực tiếp từ phần bình luận của TikTok, giữ nguyên định dạng chất lượng cao hoặc chuyển đổi sang Video Sticker chuẩn Telegram (WebM VP9).

## Tính năng chính

- **Bắt sticker chuẩn xác:** Hook vào thư viện nạp ảnh nội bộ (ByteDance Lighten / SmartImageView) và phát hiện cử chỉ nhấn giữ (long-press) không làm ảnh hưởng đến thao tác vuốt cuộn màn hình bình luận.
- **Giao diện chuẩn TikTok Tux Design:** BottomSheet tông nền Dark Obsidian (`#161823`), bo góc 16dp, drag handle pill và hệ thống icon Vector SVG phẳng sắc nét (không dùng emoji).
- **Hỗ trợ định dạng gốc linh hoạt:**
  - Tự nhận diện và lưu đúng định dạng của sticker: Animated WebP, Static WebP, PNG, JPG, GIF.
  - Lưu vào thư mục công khai `Pictures/TikTokStickers/` và tự động cập nhật thư viện qua MediaStore.
- **Tự động chuyển đổi Video Sticker Telegram:**
  - Chuyển Animated WebP sang **WebM VP9** (độ phân giải chuẩn cạnh 512px, tốc độ khung hình 30 FPS, thời lượng tối đa 3.0s, bitrate 256 kbps).
  - Khử artifact mảng đen khi chuyển đổi sang định dạng video bằng thuật toán compositing chuẩn (kế thừa toạ độ frame con `xOffset`, `yOffset`, xử lý cờ hòa trộn `blendWithPreviousFrame` và `disposeToBackgroundColor`).
- **Gửi thẳng sang Telegram:**
  - Nút **"Gửi WebM vào Telegram (Bot)"** mở thẳng ứng dụng Telegram/Nekogram để bạn chọn gửi trực tiếp cho `@VideoStickerzBot` (hoặc bot sticker bất kỳ) tạo sticker tức thì.
  - Sử dụng Content URI MediaStore kèm cờ cấp quyền đọc xuyên tiến trình (`FLAG_GRANT_READ_URI_PERMISSION`), không bị lỗi quyền Linux UID.

## Yêu cầu hệ thống

- Android 8.0 (API 26) trở lên.
- Root với Magisk / KernelSU / APatch.
- Framework Zygisk-LSPosed (hoặc phiên bản LSPosed tương thích).
- Ứng dụng TikTok mục tiêu:
  - `com.ss.android.ugc.trill` (TikTok Châu Á / Việt Nam)
  - `com.zhiliaoapp.musically` (TikTok Quốc tế)

## Cài đặt & Sử dụng

1. Tải file APK mới nhất từ mục **Releases** hoặc artifact từ **GitHub Actions**.
2. Cài đặt APK vào thiết bị.
3. Mở ứng dụng **LSPosed Manager**, kích hoạt module **TikTok Sticker Downloader** và chọn phạm vi hoạt động là ứng dụng TikTok.
4. Buộc dừng (Force stop) TikTok rồi mở lại.
5. Vào bất kỳ bình luận nào có sticker, **nhấn giữ** vào sticker để mở menu tùy chọn tải về hoặc chia sẻ.

## Build từ mã nguồn

Dự án sử dụng Gradle 8.8+ và Android Gradle Plugin 8.8.0:

```bash
chmod +x gradlew
./gradlew assembleDebug
```

File APK sau khi build nằm tại `app/build/outputs/apk/debug/app-debug.apk`.
