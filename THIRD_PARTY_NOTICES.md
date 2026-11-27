# Third-party notices

## FFmpegKit maintained fork

This application packages `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` as an Android AAR. The artifact's Maven metadata identifies LGPL-3.0. The AAR includes the FFmpeg shared libraries and embedded notices; copies of its 33 `res/raw/license*.txt` notices are also stored under `app/src/main/assets/ffmpegkit-licenses/` and are included in the APK as app assets.

The inspected Maven AAR SHA-256 was `c3cbc81d498175fd2aa69ee2dfe7dafbf519052a96283c2568fe5b3b16618456`.

The artifact is distributed for `arm64-v8a` and `x86_64`. The archive was inspected before integration and contains `libavcodec`, `libavformat`, `libavutil`, `libavfilter`, `libswscale`, and `libswresample` for both ABIs. Other FFmpeg external components may be statically linked or enabled as build features rather than appearing as standalone `.so` files. The app's native-runtime screen checks the packaged core objects and probes FFmpeg encoders, filters, and build options at runtime.

The exact Maven artifact is available at [Maven Central](https://repo1.maven.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-full/8.1.7/ffmpeg-kit-full-8.1.7.aar). The corresponding maintained-fork source and build instructions are available at [ffmpegkit-maintained/ffmpeg-kit](https://github.com/ffmpegkit-maintained/ffmpeg-kit); consult its 8.1.7 release/tag and build configuration when rebuilding or redistributing. This project is an independent community continuation and is not affiliated with the original FFmpegKit project.

This app uses the `full` LGPL variant, not a `full-gpl` variant. `libx264` and `libx265` are therefore not enabled. `libfdk-aac` is also excluded because redistribution terms have not been cleared. QuickJS-NG 0.12.1 is bundled under MIT; aria2 1.37.0 is bundled under GPL-2.0-or-later. Deno and Node.js are not bundled. Codec patent rules may apply independently of software copyright licenses; this notice is not legal advice or a patent clearance.

The complete copyright, license, and disclaimer text supplied by the AAR is in `app/src/main/assets/ffmpegkit-licenses/`. Preserve those files when redistributing this application. FFmpeg and every third-party component remain subject to their respective license terms; consult the included texts for the applicable obligations.


## Embedded CPython, yt-dlp, yt-dlp-ejs and Jsoup
This app now packages CPython 3.13 through Chaquopy 17.2.0, the yt-dlp Python distribution 2026.11.23, yt-dlp-ejs 0.8.0, and Jsoup 1.24.1. Their applicable notices and license texts are shipped at `app/src/main/assets/THIRD_PARTY_NOTICES.md`. Python is under the PSF License Version 2; Chaquopy and Jsoup are MIT; yt-dlp and yt-dlp-ejs code are Unlicense, with the EJS wheel also containing ISC-licensed Meriyah and MIT-licensed Astring. The app uses the Python package distributions, not yt-dlp's PyInstaller executable.

The EJS solver scripts and QuickJS-NG `qjs` runtime are bundled for arm64-v8a and x86_64; yt-dlp is configured to invoke the runtime for challenge solving. Site access rules, authentication, regional availability and upstream changes may still prevent extraction or downloading. aria2 1.37.0 is also bundled for both ABIs under GPL-2.0-or-later and is selected by yt-dlp for supported HTTP(S) transfers; yt-dlp's internal downloader remains in use for segmented HLS/DASH. The complete GPL license is in `app/src/main/assets/third_party_licenses/GPL-2.0.txt`. Corresponding aria2 source is at the exact `release-1.37.0` tag: https://github.com/aria2/aria2/tree/release-1.37.0 . The Android build recipe is in `scripts/build-aria2-android.sh`; it uses the upstream Android Docker build instructions with the target ABI and maintained OpenSSL 3.5.9. The current FFmpeg JNI AAR does not expose an `ffmpeg` CLI to yt-dlp, so the app selects muxed single-file video or audio-only streams instead of separate-stream merging. `libx264` and `libx265` remain excluded from this LGPL artifact; `libfdk-aac` remains excluded pending license and patent review. These statements are not legal advice or patent clearance.

## Media3 ExoPlayer 1.5.1 and Coil Compose 2.7.0
The app packages AndroidX Media3 ExoPlayer/Media3 UI and Coil Compose under Apache License 2.0. The full Apache-2.0 license text is included in `app/src/main/assets/third_party_licenses/Apache-2.0.txt` and in the APK. Upstream projects: https://github.com/androidx/media and https://github.com/coil-kt/coil . Media3 is used for user-initiated stream/history playback; Coil displays remote thumbnails when the user preference is enabled. These libraries do not remove source-site access restrictions.

## AndroidX WorkManager KTX 2.10.1 and Compose Material Icons Extended
These AndroidX libraries are distributed under Apache License 2.0; the full license text is included in `app/src/main/assets/third_party_licenses/Apache-2.0.txt`. WorkManager provides durable scheduling and foreground progress for user-initiated downloads; Material Icons Extended provides vector UI icons. Upstream: https://developer.android.com/jetpack/androidx/releases/work and https://developer.android.com/jetpack/compose.
