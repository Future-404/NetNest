# NetNest

NetNest is a premium Android aggregator shell app that acts as a unified portal for progressive web applications (PWAs). With NetNest, users can save website URLs, automatically fetch their manifests, extract icons and theme colors, and launch them in a full-featured, sandbox-isolated WebView, offering a native-app-like user experience.

## ✨ Features

- 📱 **Desktop Grid Portal**: A clean, Material 3 grid layout showing all added web apps with high-resolution icons.
- ⚡ **Auto PWA Manifest Extraction**: Automatically fetches URLs, parses `<link rel="manifest">` tags, and retrieves names, icons (selecting the largest available), and theme colors.
- 🔄 **Robust Fallbacks**: Falls back to `apple-touch-icon`, standard favicon arrays, or `/favicon.ico` at the domain root if no manifest is present.
- 🔒 **Sandbox-Isolated Webviews**: Prevents cookies and local storage bleeding between different websites through the same-origin policy.
- ⚙️ **Advanced WebView Settings**: Full JavaScript support, DOM Storage, HTML5 Database, file chooser uploads, and custom web clients.
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
