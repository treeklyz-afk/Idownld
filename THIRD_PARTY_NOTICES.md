# Third-party notices

## Embedded Python and media extraction

The APK bundles CPython 3.13 through Chaquopy 17.2.0, `yt-dlp` 2026.11.23, `yt-dlp-ejs` 0.8.0 and Jsoup 1.24.1. Applicable upstream license and component notices are in `app/src/main/assets/THIRD_PARTY_NOTICES.md` and `app/src/main/assets/third_party_licenses/`. The app uses Python package distributions, not a PyInstaller binary.

`yt-dlp` selects available streams and formats. Site availability varies with extractor coverage, source-site changes, authentication, region, DRM and network conditions. QuickJS supports yt-dlp-ejs challenge execution for compatible sites; it does not guarantee universal extraction. This app does not bypass site access controls.

## Optional native helper packages

QuickJS-NG `qjs` 0.12.1 (MIT) and aria2c 1.37.0 (GPL-2.0-or-later) are built for `arm64-v8a` and `x86_64`, published as versioned GitHub Release assets, and downloaded on first runtime setup or when a newer manifest is available. The app validates the HTTPS repository path, ABI, declared byte size and SHA-256 digest, then runs executable self-tests before switching the active runtime under its app-private `noBackupFilesDir/lib` directory. The matching MIT and GPL license texts are included in both app assets and release assets. aria2 is used only for supported HTTP(S) transfers; yt-dlp's internal downloader handles segmented HLS/DASH.

Upstream source and exact versions:

- QuickJS-NG 0.12.1: https://github.com/quickjs-ng/quickjs/tree/v0.12.1
- aria2 1.37.0: https://github.com/aria2/aria2/tree/release-1.37.0
- Android aria2 build recipe: `scripts/build-aria2-android.sh`

## FFmpeg and media processing

The FFmpegKit AAR was removed because the app did not expose its JNI library as a yt-dlp `ffmpeg` executable or merge adapter. Consequently, the app does not promise separate-stream merging or format conversion. Formats that require merging may be unavailable; prefer muxed formats or audio-only formats. Media3 ExoPlayer remains bundled for user-initiated playback of compatible streams and saved media.

## AndroidX and Coil

AndroidX Compose, Media3, WorkManager and Coil Compose are distributed under Apache License 2.0. The full license text is in `app/src/main/assets/third_party_licenses/Apache-2.0.txt`. Material Icons Extended is no longer a dependency. Codec patent or other jurisdiction-specific rules may apply independently; this notice is not legal advice or patent clearance.
