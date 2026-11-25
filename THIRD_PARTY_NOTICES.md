# Third-party notices

## FFmpegKit maintained fork

This application packages `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` as an Android AAR. The artifact's Maven metadata identifies LGPL-3.0. The AAR includes the FFmpeg shared libraries and embedded notices; copies of its 33 `res/raw/license*.txt` notices are also stored under `app/src/main/assets/ffmpegkit-licenses/` and are included in the APK as app assets.

The inspected Maven AAR SHA-256 was `c3cbc81d498175fd2aa69ee2dfe7dafbf519052a96283c2568fe5b3b16618456`.

The artifact is distributed for `arm64-v8a` and `x86_64`. The archive was inspected before integration and contains `libavcodec`, `libavformat`, `libavutil`, `libavfilter`, `libswscale`, and `libswresample` for both ABIs. Other FFmpeg external components may be statically linked or enabled as build features rather than appearing as standalone `.so` files. The app's native-runtime screen checks the packaged core objects and probes FFmpeg encoders, filters, and build options at runtime.

The exact Maven artifact is available at [Maven Central](https://repo1.maven.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-full/8.1.7/ffmpeg-kit-full-8.1.7.aar). The corresponding maintained-fork source and build instructions are available at [ffmpegkit-maintained/ffmpeg-kit](https://github.com/ffmpegkit-maintained/ffmpeg-kit); consult its 8.1.7 release/tag and build configuration when rebuilding or redistributing. This project is an independent community continuation and is not affiliated with the original FFmpegKit project.

This app uses the `full` LGPL variant, not a `full-gpl` variant. `libx264` and `libx265` are therefore not enabled. `libfdk-aac` is also excluded because redistribution terms have not been cleared. The app does not bundle Python, yt-dlp, Deno, Node.js, or aria2. Codec patent rules may apply independently of software copyright licenses; this notice is not legal advice or a patent clearance.

The complete copyright, license, and disclaimer text supplied by the AAR is in `app/src/main/assets/ffmpegkit-licenses/`. Preserve those files when redistributing this application. FFmpeg and every third-party component remain subject to their respective license terms; consult the included texts for the applicable obligations.
