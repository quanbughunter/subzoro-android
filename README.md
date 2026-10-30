# subZoro cho Android (bản thử)

Bong bóng phụ đề nổi trên app YouTube: nghe tiếng video, nhận dạng ngay trên máy (Vosk), hiện câu kèm bản dịch tiếng Việt.
Anh em với extension subZoro trên Chrome (`youtube-subber`).

## Bản thử 0.1 làm được gì
- Bong bóng nổi trên YouTube (quyền "Hiển thị trên ứng dụng khác"): 3 câu gần nhất + câu đang nghe dở, thu nhỏ thành nút tròn.
- Nghe **tiếng phát ra từ YouTube** (Android 10+, phải bấm đồng ý chia sẻ màn hình mỗi lần bật — chỉ lấy tiếng, không ghi hình), hoặc nghe qua **micro** nếu YouTube chặn.
- Nhận dạng ngay trên máy bằng mô hình Vosk nhỏ: tiếng Anh, Nga, Trung (~40–45 MB mỗi thứ tiếng, tải một lần từ alphacephei.com).
- Đọc vị trí video qua phiên phát của YouTube (quyền "Truy cập thông báo"): chạm vào câu để tua lại câu đó, nút ↺ nghe lại câu vừa rồi, nút phát/dừng.
- Dịch từng câu sang tiếng Việt (Google Dịch, cần mạng).
- Tự báo nếu bắt tiếng YouTube mà chỉ được im lặng (YouTube chặn) và cho chuyển sang micro.

## Dựng APK
Không cần cài gì: mỗi lần đẩy code lên GitHub, GitHub Actions (`.github/workflows/build-apk.yml`) dựng APK và đăng vào mục **Releases**.
Muốn dựng trên máy: mở thư mục bằng Android Studio, hoặc `gradlew assembleDebug`.

APK được ký bằng `app/subzoro-debug.keystore` (khoá thử, mật khẩu `android`, không bí mật) để bản mới cài đè được lên bản cũ.
Khi đưa lên Google Play sẽ cần khoá riêng, giữ kín.

## Cấu trúc
- `MainActivity.kt` — màn hình cài đặt: quyền, ngôn ngữ + tải mô hình, nguồn tiếng, bật/tắt.
- `CaptionService.kt` — dịch vụ nền: bắt tiếng (AudioPlaybackCapture / micro) → Vosk → câu + mốc thời gian video → dịch.
- `Overlay.kt` — bong bóng nổi (kéo, thu nhỏ, đổi cỡ chữ).
- `VideoClock.kt` — đọc/điều khiển phiên phát của app YouTube (MediaSession).
- `Models.kt` — tải (có tải tiếp) và giải nén mô hình Vosk.
- `Translator.kt` — dịch câu.

## Giấy phép bên thứ ba
- Vosk (alphacep/vosk-api) và mô hình Vosk small — Apache 2.0.
- JNA — Apache 2.0 / LGPL 2.1.
