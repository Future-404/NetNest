# NetNest

NetNest is a premium Android aggregator shell app that acts as a unified portal for progressive web applications (PWAs). With NetNest, users can save website URLs, automatically fetch their manifests, extract icons and theme colors, and launch them in a full-featured, sandbox-isolated WebView, offering a native-app-like user experience.

## ✨ Features

- 📱 **Desktop Grid Portal**: A clean, Material 3 grid layout showing all added web apps with high-resolution icons.
- ⚡ **Auto PWA Manifest Extraction**: Automatically fetches URLs, parses `<link rel="manifest">` tags, and retrieves names, icons (selecting the largest available), and theme colors.
- 🔄 **Robust Fallbacks**: Falls back to `apple-touch-icon`, standard favicon arrays, or `/favicon.ico` at the domain root if no manifest is present.
- 🔒 **Selectable PWA Profiles**: Choose shared login data or an independent WebView profile when creating each PWA, with a visible compatibility fallback on unsupported providers.
- 🗂️ **Warm App Switching**: A configurable side handle switches among recent PWAs while retaining up to four live WebViews with bounded idle, background, and memory-pressure cleanup.
- ⚙️ **Advanced WebView Settings**: Full JavaScript support, DOM Storage, HTML5 Database, file chooser uploads, and custom web clients.
- 📥 **Confirmed Browser Downloads**: Supports regular and page-generated downloads after an explicit confirmation, saving them under `Download/NetNest`.
- 🔔 **Per-PWA Notifications**: Bridges foreground Web Notifications to Android channels with independent permission and rate limiting. Generic Web Push after NetNest is fully closed is not supported.
- 🔄 **Reordering and Context Actions**: Drag-and-swap ordering options, editing, deleting (with local cache cleanup), and manual icon refreshing.
- 🎨 **Fallback Typography Icons**: Dynamic letter-based fallback icons styled with site colors when an icon cannot be retrieved.

## 🛠️ Technology Stack

- **UI Framework**: Jetpack Compose
- **Programming Language**: Kotlin (JVM Target 17)
- **Local Storage**: Room Database (with Kotlin Symbol Processing `KSP`)
- **HTTP Client**: OkHttp 4
- **HTML Parsing**: Jsoup
- **Image Loading & Decoding**: Coil (configured with SvgDecoder for vector graphics support)
- **Serialization**: kotlinx.serialization (JSON decoder)

## 🏗️ Building and Compilation

This project is set up to build automatically using a GitHub Actions workflow.

To compile locally, ensure you have **Java 17+** and **Android SDK (API 34)** installed, configure `local.properties` with your SDK path, and run:

```bash
./gradlew :app:assembleDebug
```

To run unit tests:

```bash
./gradlew :app:testDebugUnitTest
```
