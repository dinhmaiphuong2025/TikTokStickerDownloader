# AGENTS.md - TikTokStickerDownloader

LSPosed module that hooks TikTok (`com.ss.android.ugc.trill`, `com.zhiliaoapp.musically`) comments to download animated stickers and convert them to Telegram Video Stickers (WebM VP9), animated GIFs (GIF89a), or raw WebP, with a Tux-styled BottomSheet.

## Development & Build

- **Toolchain**: JDK 17, Gradle 8.8+, AGP 8.8.0, Kotlin 2.0.21 (`compileSdk = 34`, `minSdk = 26`).
- **Local Environment Note**: The local Linux container lacks JDK/Android SDK. Builds run on GitHub Actions CI or ephemeral cloud runners.
- **Build Command**:
  ```bash
  ./gradlew assembleDebug --stacktrace
  ```
- **Output Artifact**: `app/build/outputs/apk/debug/*.apk`

## Architecture & File Layout

All source code resides in `app/src/main/java/dev/lsposed/tiktoksticker/`:

- `MainHook.kt`: Entry point (`xposed_init`). Uses DexKit 2.0.2 and reflection to locate obfuscated TikTok comment/sticker views, intercept image URLs, and capture long-press events.
- `TikTokBottomSheet.kt`: Programmatic UI mimicking TikTok Tux Design System (#161823 Dark Obsidian, 16dp radius, pill handle).
- `WebpToWebmConverter.kt`: Converts animated WebP to WebM VP9 via Android `MediaCodec` + `MediaMuxer` for Telegram video stickers.
- `WebpToGifConverter.kt`: Extracts frames using TikTok's internal `com.facebook.animated.webp.WebPImage` via reflection.
- `AnimatedGifEncoder.kt`: Pure Kotlin GIF89a encoder with NeuQuant color quantization.

## Conventions

- **Commit Format**: Conventional commits (`feat: ...`, `fix: ...`, `debug: ...`).
- **UI Elements**: Pure programmatic Android views (`LinearLayout`, `TextView`, `GradientDrawable`); do not rely on app XML layouts inside the injected host process.
- **Logging**: Dual logging via `Log.d("TikTokStickerHook", msg)` and `XposedBridge.log("[$TAG] $msg")`.
- **Target Scope**: Scoped to TikTok packages declared in `app/src/main/res/values/arrays.xml`.
- **Reflection & DexKit**: Always guard class and method lookups (`findClassIfExists`, `runCatching`) to handle TikTok bytecode obfuscation across updates.

## Pitfalls & Telegram Specs

- **Telegram WebM VP9 Sticker Constraints**:
  - Codec: `video/x-vnd.on2.vp9` in `.webm` container.
  - Dimensions: Max dimension 512px (must be multiple of 16 for hardware encoder compatibility).
  - Duration: Max 3.0 seconds (`MAX_DURATION_US = 3_000_000L`).
  - Bitrate: 256 kbps (`BITRATE = 256 * 1024`) to stay within Telegram's 256 KB file limit.
- **GIF Color Quantization**: NeuQuant requires strict RGB channel mapping. Do not invert channel ordering or alter transparent color fallback logic without verifying color rendering.
- **Xposed Lifecycle**: Avoid static initialization of UI `Handler` or `Looper` during Zygote fork before class loading is complete.
