# subZoro cho Android (bản thử)

Bong bóng phụ đề nổi trên app YouTube: nghe tiếng video, nhận dạng ngay trên máy, hiện câu kèm bính âm (tiếng Trung) và nghĩa tiếng Việt.
Anh em với extension subZoro trên Chrome (`youtube-subber`).

## Làm được gì
- Bong bóng nổi trên YouTube: **mọi câu đã nghe của video**, xếp theo thời gian trong video, cuộn được (vuốt, hoặc kéo thanh trượt bên phải); kéo vạch dưới đáy để đổi chiều cao. Tiếng Trung có **bính âm** phía trên và **nghĩa tiếng Việt** nhỏ phía dưới.
- **Không lặp câu khi tua lại**: chạm vào câu cũ hoặc tua về đoạn đã nghe thì không nghe lại thành câu mới; câu đang phát được **tô sáng theo từng lời** (bính âm sáng theo chữ Hán, nghĩa tiếng Việt đậm lên). Tua tới đoạn chưa nghe thì câu mới được chèn đúng chỗ. Mỗi video giữ danh sách câu riêng (6 video gần nhất).
- **Đổi ngôn ngữ ngay trên bong bóng** (nút EN / RU / 中文), hoặc **tự đoán theo tên video** (chữ Hán → Trung, chữ Nga → Nga; tên ghi "tiếng Trung/Nga/Anh", HSK, IELTS… thì theo đó; tên tiếng Việt không ghi rõ thì dùng ngôn ngữ mặc định; còn lại → Anh). Thiếu mô hình thì tải ngay trên bong bóng.
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
- `CaptionService.kt` — dịch vụ nền: bắt tiếng → Vosk → lọc phần đã có → câu + mốc thời gian video → nghe lại bằng mô hình chính xác → bính âm → dịch; đổi ngôn ngữ, danh sách câu theo từng video.
- `Timing.kt` — mốc từng từ (từ Vosk hoặc token của mô hình chính xác), những đoạn đã có câu, chia nhóm từ mới.
- `Overlay.kt` — bong bóng nổi (danh sách cuộn + thanh trượt, tô câu đang phát theo từng lời, chọn ngôn ngữ, kéo, thu nhỏ, cỡ chữ).
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
