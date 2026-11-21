# MusicClip Visualizer 🎵🌈

**App Android (APK) tạo visual theo nhạc — mã nguồn mở, nhạc có sẵn, hình ảnh lấy từ các trang miễn phí.**

MusicClip Visualizer phát nhạc (bài có sẵn trong app + nhạc trên máy của bạn) và vẽ **hiệu ứng trực tiếp theo tần số nhạc** (FFT) làm hình nền cho điện thoại. Bạn có thể **chụp ảnh PNG** hoặc **ghi video MP4 full theo thời lượng bài hát** (tối đa 180 phút, dọc 1080×1920@30fps **chuẩn TikTok**) để chia sẻ.

---

## 🌐 Bản web — laptop/PC + điện thoại

**https://zeigarnik-code.github.io/MusicClip/** — mở bằng trình duyệt (Chrome/Edge/Safari…), không cần cài đặt:

- **20 hiệu ứng beat-sync kiểu visual LED đi cảnh**: Tunnel neon · Sóng hình học (synthwave) · Vũ trụ · Thành phố đêm · Thanh phổ · Vòng tròn · Hạt sáng · Mưa mã · Sóng thời gian · Bong bóng LED · Kính vạn hoa · Sàn disco · Laser quét · Sao băng · VU meters · Pháo hoa · Spiral thiên hà · Nhịp tim ECG · Blob neon · Chữ neon bay
- Bấm **tên hiệu ứng** để mở lưới chọn nhanh (20 ô); nút **⏱ Tự đổi** xoay vòng kiểu nonstop
- **⬇ Tải offline**: tải `MusicClip-Visualizer.html` về máy — mở bằng trình duyệt bất cứ lúc nào, không cần mạng
- **Nhạc mẫu Vinahouse 128 BPM** tổng hợp trực tiếp bằng WebAudio, hoặc **chọn file nhạc** (mp3/wav/m4a) từ máy tính / điện thoại
- Tự đổi hiệu ứng mỗi 12 giây, fullscreen, ẩn UI — hợp trình chiếu màn hình LED/TV
- Phím tắt: `Space` phát/dừng · `←/→` đổi hiệu ứng · `F` fullscreen · `H` ẩn UI · `A` tự đổi
- Beat detect bằng spectral flux — cùng thuật toán bản Android
- Nguồn: [`web/index.html`](web/index.html) (1 file, không build) · deploy qua nhánh `gh-pages`

---

## ✨ Tính năng

| Tính năng | Mô tả |
|---|---|
| 🎶 **Nhạc có sẵn** | 3 bài hát tích hợp (`Neon Skyline`, `Pulse Drive`, `Quiet Orbit`) — tổng hợp 100% từ code, bản quyền sạch (CC0) |
| 🎤 **Nhạc trên máy** | Tự động liệt kê bài hát trong thư viện MediaStore của bạn |
| 📊 **6 hiệu ứng** | Thanh phổ · Vòng tròn · Sóng · Hạt sáng · Xung nhịp · **Ma mị** (sương, linh hồn bay, trăng ma) — nhận diện cả **nhịp (beat)** |
| 🖼️ **Nguồn hình miễn phí** | Picsum (Unsplash) · Wikimedia Commons · Openverse · NASA · Ảnh của máy · URL tùy chọn · **Cảnh ma mị** |
| 📷 **Chụp ảnh** | Lưu PNG vào `Pictures/MusicClip` |
| 🎬 **Ghi video** | Export MP4 **full theo bài hát** (tối đa 180 phút), **1080×1920@30fps dọc 9:16 chuẩn TikTok** (H.264 + AAC ~10 Mbps) vào `Movies/MusicClip` |
| 🔒 **Zero tracking, zero ads, zero third-party SDK** | Chỉ dùng framework Android |

---

## 📦 Cài đặt

1. Tải `dist/MusicClip-Visualizer-v1.1.1.apk` từ repo.
2. Cài đặt lên điện thoại Android 7.0+ (API 24+). Bật **"Cho phép cài từ nguồn không xác định"** nếu được hỏi.
3. Mở app → chọn nhạc + nguồn hình → **BẮT ĐẦU**.

> APK đã ký bằng keystore trong repo (`tools/release.keystore`, mật khẩu `musicclip`) — keystore này chỉ phục vụ sideload/test. Nếu phát hành riêng, hãy tự tạo keystore của bạn.

### Quyền giải thích

| Quyền | Lý do |
|---|---|
| `INTERNET` | Tải hình từ các trang miễn phí |
| `RECORD_AUDIO` | **Bắt buộc** để `Visualizer` đọc FFT của luồng phát nhạc (xem nhạc không ghi âm). Nếu từ chối, hiệu ứng vẫn chạy ở chế độ mô phỏng |
| `READ_MEDIA_AUDIO` | Liệt kê nhạc trên máy |
| `WRITE_EXTERNAL_STORAGE` (≤ Android 9) | Lưu ảnh/video cũ |

---

## 🖼️ Nguồn hình ảnh miễn phí (không cần API key)

| Nguồn | Giấy phép | Ghi chú |
|---|---|---|
| [Lorem Picsum](https://picsum.photos) | Ảnh Unsplash miễn phí | Nguồn mặc định, luôn hoạt động |
| [Wikimedia Commons](https://commons.wikimedia.org) | CC BY-SA / CC0 / public domain | Tìm theo chủ đề, hiển thị license trên thanh tiêu đề |
| [Openverse](https://openverse.org) | CC / public domain | Gộp ảnh từ nhiều dự án mở |
| [NASA Image Library](https://images.nasa.gov) | Public domain | Không gian, Trái Đất, sao hỏa… |
| Ảnh của tôi | — | Chọn từ bộ sưu tập (không cần cấp quyền) |
| URL tùy chọn | — | Dán link ảnh trực tiếp bất kỳ trang nào |
| **Cảnh ma mị** | Wikimedia Commons | Sương, đêm, mây bão, trăng — tạo vibe ma mị |

App tự động đổi hình nền mỗi 20 giây; bấm **Hình nền** để đổi ngay. Nếu nguồn lỗi, app tự chuyển về Picsum.

---

## 🔨 Build

### Cách A — Android Studio (khuyến nghị cho người dùng cuối)

```bash
git clone https://github.com/ZeiGarNik-code/MusicClip.git
cd MusicClip
./gradlew assembleDebug        # hoặc mở bằng Android Studio → Run
```

Yêu cầu: Android Studio / JDK 11+, SDK 33. *(Cấu hình Gradle chuẩn, không phụ thuộc thư viện ngoài.)*

### Cách B — Script build offline, KHÔNG cần Gradle / Android SDK

```bash
tools/fetch_toolchain.sh       # 1 lần: tải toolchain vào .toolchain/ (JDK, android.jar, aapt2, d8, apksigner)
tools/build_apk.sh             # build -> dist/MusicClip-Visualizer-v1.1.1.apk
```

Pipeline: `aapt2 compile/link → javac → d8 → zipalign → apksigner`. Xem chi tiết từng công cụ trong [`tools/README-toolchain.md`](tools/README-toolchain.md).

### Tạo lại nhạc & icon

```bash
python3 tools/make_music.py    # tổng hợp 3 bài WAV (cần numpy, scipy)
python3 tools/make_icons.py    # vẽ icon PNG (cần pillow)
```

---

## 🗂️ Cấu trúc dự án

```
app/src/main/
├── AndroidManifest.xml
├── java/com/musicclip/visualizer/
│   ├── MainActivity.java            # chọn nhạc + nguồn hình
│   ├── VisualizerActivity.java      # màn hình visual, chụp ảnh, ghi video
│   ├── Track.java / BuiltInTracks.java
│   ├── audio/AudioEngine.java       # MediaPlayer + Visualizer FFT (32 band, beat detect)
│   ├── render/RenderEngine.java     # 6 hiệu ứng vẽ bằng Canvas
│   ├── render/VisualizerView.java   # SurfaceView + render thread
│   ├── source/…                     # Picsum, Wikimedia, Openverse, NASA, Gallery, URL, Cảnh ma mị
│   ├── export/VideoExporter.java    # PCM→AAC + Canvas→GL→H.264 + MediaMuxer
│   └── util/ImageStore.java         # lưu PNG vào MediaStore
├── res/…                            # layout, strings (tiếng Việt), drawable, icon
└── assets/music/*.wav               # 3 bài có sẵn (tạo bởi tools/make_music.py)

tools/                               # scripts build + sinh tài nguyên
dist/MusicClip-Visualizer-v1.1.1.apk # APK đã ký, cài được ngay
```

**Quy trình xuất video:** đọc PCM (WAV asset hoặc giải mã bài trên máy bằng MediaCodec) → mã hóa AAC; đồng thời RenderEngine vẽ từng khung vào Bitmap → nạp texture GL → encoder H.264 (input surface, PTS qua `eglPresentationTimeANDROID`) → gộp bằng `MediaMuxer`.

---

## 🧪 Ghi chú kỹ thuật

- **Ngôn ngữ:** Java 8 thuần — không Kotlin, không androidx, không thư viện bên thứ ba (dễ audit, APK nhỏ).
- **Phân tích âm thanh:** `android.media.audiofx.Visualizer` → 32 band log-scale, auto-gain, smoothing, phát hiện beat bằng EMA trên dải bass.
- **Hiệu ứng chạy được cả khi export:** exporter tự tính FFT (Goertzel) từ PCM nên video tái hiện đúng tiết tấu.
- **Min SDK 24 / Target SDK 33**, orientations khóa portrait, giao diện tối.

---

## 📜 License

- **Source code:** [MIT](LICENSE)
- **Nhạc tích hợp:** tổng hợp tự động bởi `tools/make_music.py` → CC0 / MIT
- **Icon:** vẽ bằng `tools/make_icons.py` → CC0
- **Ảnh nền:** theo giấy phép từng nguồn (xem credit trên thanh tiêu đề)

---

## English summary

**MusicClip Visualizer** is an open-source Android app (pure Java, zero dependencies) that turns music into live visuals: 3 built-in royalty-free tracks + your device library, 6 beat-reactive effects (including a foggy "Ma mị" phantom mode) driven by real FFT analysis, free image backgrounds from Picsum / Wikimedia Commons / Openverse / NASA (no API key), PNG screenshots and full-song MP4 export (up to 180 minutes, TikTok-ready vertical 1080×1920@30fps). Build with Android Studio (`./gradlew assembleDebug`) or with the fully offline script pipeline (`tools/build_apk.sh`) that needs no Gradle and no Android SDK.
