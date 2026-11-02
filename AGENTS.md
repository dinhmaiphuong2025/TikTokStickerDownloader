# AGENTS.md - TikTokStickerDownloader

LSPosed module that hooks TikTok (`com.ss.android.ugc.trill`, `com.zhiliaoapp.musically`) comments to download stickers (WebP, PNG, JPG, GIF), convert them to Telegram Video Stickers (WebM VP9), or share directly to Telegram/Nekogram via a Tux-styled BottomSheet.

## Development & Build

- **Toolchain**: JDK 17, Gradle 8.8+, AGP 8.8.0, Kotlin 2.0.21 (`compileSdk = 34`, `minSdk = 26`).
- **Signing**: Keystore `app/release.jks` (alias: `release`, pass: `android`) signs both `release` and `debug` builds.
- **Local Environment Note**: The local Linux container lacks JDK/Android SDK. Builds run on GitHub Actions CI or ephemeral cloud runners.
- **Build Commands**:
  ```bash
  ./gradlew assembleRelease --stacktrace
  ./gradlew assembleDebug --stacktrace
  ```
- **Output Artifacts**: `app/build/outputs/apk/release/*.apk` and `debug/*.apk`

## Architecture & File Layout

All source code resides in `app/src/main/java/dev/lsposed/tiktoksticker/`:

- `MainHook.kt`: Entry point (`xposed_init`). Uses DexKit 2.0.2 and reflection on ByteDance Lighten / SmartImageView to capture sticker URLs, detect long-press events on comment stickers (excluding full-screen video feeds), and save/share media via MediaStore.
- `TikTokBottomSheet.kt`: Programmatic UI mimicking TikTok Tux Design System (#161823 Dark Obsidian, 16dp radius, pill handle, pure SVG vector path icons via `androidx.core.graphics.PathParser` and `ShapeDrawable`).
- `WebpToWebmConverter.kt`: Converts animated WebP to WebM VP9 via Android `MediaCodec` + `MediaMuxer`. Implements multi-frame compositing (handling `xOffset`, `yOffset`, `blendWithPreviousFrame`, and `disposeToBackgroundColor`) and alpha-matting.

## Conventions

- **Commit Format**: Conventional commits (`feat: ...`, `fix: ...`, `docs: ...`, `build: ...`).
- **UI Elements**: Pure programmatic Android views (`LinearLayout`, `TextView`, `ShapeDrawable`, `PathShape`); do not rely on app XML layouts inside the injected host process. Do not use emoji in UI surfaces.
- **Logging**: Dual logging via `Log.d("TikTokStickerHook", msg)` and `XposedBridge.log("[$TAG] $msg")`.
- **Target Scope**: Scoped to TikTok packages declared in `app/src/main/res/values/arrays.xml`.
- **Reflection & DexKit**: Always guard class and method lookups (`findClassIfExists`, `runCatching`) to handle TikTok bytecode obfuscation across updates.

## Pitfalls & Telegram Specs

- **Android 11+ Package Visibility**:
  - Never call `packageManager.getPackageInfo()` to check for external Telegram clients (e.g. Nekogram, Nagram, AyuGram); targetSdk 34 will throw `NameNotFoundException` due to package visibility filtering.
  - Call `activity.startActivity(sendIntent.apply { setPackage(pkg) })` directly inside a try-catch for `ActivityNotFoundException` to bypass package visibility restrictions.
- **Single MediaStore Insertion**:
  - Convert videos into temporary cache files (`activity.cacheDir`) before writing once to `MediaStore` at `Environment.DIRECTORY_PICTURES + "/TikTokStickers"`. Never write both loose files and MediaStore entries, which causes duplicate video files in Gallery.
- **Sticker vs Video Feed Filtering**:
  - Filter target views by size (`view.width <= 280dp`, `< 65%` screen width) and URL blacklist (`cover`, `video_cover`, `tiktok-play`, `feed`, `avatar`) to avoid popping up the module on video feed playback.
- **Telegram WebM VP9 Constraints**:
  - Codec: `video/x-vnd.on2.vp9` in `.webm` container, max dimension 512px (multiple of 16), max 3.0s duration, bitrate 256 kbps.
- **Xposed Lifecycle**: Avoid static initialization of UI `Handler` or `Looper` during Zygote fork before class loading is complete.
