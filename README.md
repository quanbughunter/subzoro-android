# subZoro cho Android (bản thử)

Bong bóng phụ đề nổi trên app YouTube: nghe tiếng video, nhận dạng ngay trên máy, hiện câu kèm bính âm (tiếng Trung) và nghĩa tiếng Việt.
Anh em với extension subZoro trên Chrome (`youtube-subber`).

## Làm được gì (0.1.3)
- Bong bóng nổi trên YouTube: 3 câu gần nhất + câu đang nghe dở; tiếng Trung có **bính âm** phía trên và **nghĩa tiếng Việt** nhỏ phía dưới.
- **Đổi ngôn ngữ ngay trên bong bóng** (nút EN / RU / 中文), hoặc **tự đoán theo tên video** (chữ Hán → Trung, chữ Nga → Nga, còn lại → Anh). Thiếu mô hình thì tải ngay trên bong bóng.
- Nghe **tiếng phát ra từ YouTube** (Android 10+, đồng ý chia sẻ màn hình mỗi lần bật — chỉ lấy tiếng) hoặc **micro**.
- **Nghe hai lượt**: Vosk small cho chữ hiện ngay theo từng từ; xong mỗi câu, đoạn tiếng đó được nghe lại bằng mô hình chính xác (tuỳ chọn tải):
  SenseVoice (Trung + Anh, có dấu câu) hoặc GigaAM v2 (Nga), chạy bằng sherpa-onnx ngay trên máy.
- **Dịch sang tiếng Việt ngay trên máy** bằng ML Kit (gói dịch ~30 MB tự tải lần đầu); không được thì Google Dịch qua mạng. Bong bóng báo rõ khi không dịch được.
- Chạm vào câu để tua lại câu đó (đọc phiên phát của YouTube qua quyền "Truy cập thông báo"), ↺ nghe lại, phát/dừng, đổi cỡ chữ, thu nhỏ.
- App văng thì lần mở sau hiện lỗi để chụp gửi.

## Dựng APK
Mỗi lần đẩy code lên GitHub, GitHub Actions (`.github/workflows/build-apk.yml`) dựng APK và đăng vào mục **Releases**.
Thư viện sherpa-onnx (.aar, ~50 MB) được tải tự động từ GitHub khi dựng (`app/libs/`, không đưa vào git).
APK được ký bằng `app/subzoro-debug.keystore` (khoá thử, mật khẩu `android`) để bản mới cài đè được lên bản cũ.

## Cấu trúc
- `MainActivity.kt` — màn hình cài đặt: quyền, tải/xoá mô hình, nguồn tiếng, dịch, bật/tắt.
- `CaptionService.kt` — dịch vụ nền: bắt tiếng → Vosk → câu + mốc thời gian video → nghe lại bằng mô hình chính xác → bính âm → dịch; đổi ngôn ngữ.
- `Overlay.kt` — bong bóng nổi (chọn ngôn ngữ, kéo, thu nhỏ, cỡ chữ).
- `VideoClock.kt` — đọc/điều khiển phiên phát của app YouTube (MediaSession).
- `Models.kt` — các gói mô hình (Vosk .zip, sherpa-onnx .tar.bz2), tải có tải tiếp, giải nén.
- `Refiner.kt` — nghe lại từng câu bằng SenseVoice / GigaAM (sherpa-onnx).
- `Pinyin.kt` + `assets/zh_pinyin.txt.gz` — bính âm theo từ (từ điển pypinyin rút gọn).
- `Translator.kt` — dịch (ML Kit trên máy, Google Dịch dự phòng).

## Giấy phép bên thứ ba
- Vosk (alphacep/vosk-api) và mô hình Vosk small — Apache 2.0. JNA — Apache 2.0 / LGPL 2.1.
- sherpa-onnx (k2-fsa) — Apache 2.0; ONNX Runtime — MIT.
- SenseVoice-Small (FunAudioLLM) — FunASR Model License; GigaAM v2 (Sber) — MIT. Tải về khi người dùng bấm, không đóng gói sẵn.
- pypinyin — MIT (dữ liệu bính âm rút gọn trong `assets/zh_pinyin.txt.gz`).
- Apache Commons Compress — Apache 2.0. ML Kit Translation — điều khoản ML Kit của Google.
