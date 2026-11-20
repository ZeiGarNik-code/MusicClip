# Toolchain (công cụ build offline)

`tools/build_apk.sh` dựng APK **không cần Gradle và không cần Android SDK**.
Toàn bộ công cụ nằm trong `.toolchain/` (gitignored). Khởi tạo bằng:

```bash
tools/fetch_toolchain.sh
```

## Bảng thành phần

| Công cụ | Phiên bản | Nguồn | Dùng để |
|---|---|---|---|
| JRE (`java`, `keytool`) | Temurin 25 | PyPI [`jdk4py`](https://pypi.org/project/jdk4py/) | chạy d8 / apksigner / javac, tạo keystore |
| `javac` | OpenJDK 8 (`tools.jar`, `com.sun.tools.javac.Main`) | npm [`dataslope-tools-jar`](https://www.npmjs.com/package/dataslope-tools-jar) | biên dịch `.java` → `.class` (chạy trên JRE mới hơn) |
| `android.jar` | API 33 | github.com/`Sable/android-platforms` | bootclasspath + `--lib` cho d8/aapt2 |
| `aapt2` | build-tools 33.0.2 | github.com/`lipeedev/gendroid` (commit sẵn binary SDK) | compile/link resource, sinh `R.java`, đóng gói manifest |
| `d8` | R8/D8 3.3.20 (`lib/d8.jar`) | như trên | `.class` → `classes.dex` (JVM version, không phải bản Termux) |
| `apksigner` | 0.9 (`lib/apksigner.jar`) | như trên | ký APK (v1/v2/v3) |
| `zipalign` | build-tools 33 | như trên (cần `libc++.so` kề bên) | căn chỉnh 4-byte cho APK |
| Python venv | numpy, scipy, pillow | PyPI | sinh nhạc WAV tổng hợp + icon PNG |

## Vì sao chọn các nguồn này?

Môi trường build bị giới hạn host mạng (chỉ `github.com`, `api.github.com`,
`codeload.github.com`, `registry.npmjs.org`, `pypi.org`, `files.pythonhosted.org`):

- **Không** lấy được JDK từ Adoptium/temurin hay SDK từ `dl.google.com` (bị chặn).
- `jdk4py` đóng gói JRE đầy đủ (`java`, `keytool`) trong wheel PyPI.
- `javac` là file `.jar` thuần (OpenJDK 8) nên chạy được trên JRE 25 và compile
  với `-bootclasspath android.jar` — đúng cách build Android cổ điển.
- GitHub **release assets** redirect sang `objects.githubusercontent.com` (bị chặn),
  nên các binary phải lấy qua **Contents API** (`api.github.com`) từ các repo
  commit sẵn SDK: `Sable/android-platforms` và `lipeedev/gendroid`.

## Tự kiểm chứng sau khi fetch

```bash
.toolchain/jdk/bin/java -version
.toolchain/aapt2 version
.toolchain/jdk/bin/java -cp .toolchain/lib/d8.jar com.android.tools.r8.D8 --version
.toolchain/jdk/bin/java -jar .toolchain/lib/apksigner.jar --version
.toolchain/jdk/bin/java -cp .toolchain/toolsjar/tools.jar com.sun.tools.javac.Main -version
```

Nếu bạn có Android SDK thật trên máy thường, chỉ cần đặt `aapt2`, `zipalign`,
`d8`, `apksigner` vào `PATH` và tạo biến `ANDROID_JAR` trỏ tới `android.jar` —
`build_apk.sh` tự động dùng bản hệ thống khi không thấy `.toolchain/`.
