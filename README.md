# Stario V2

<p align="center">
  <img src="docs/representative.png" alt="Stario Banner" width="100%">
</p>

<p align="center">
  <a href="https://github.com/buddhi-art/Stario-V2/actions/workflows/build.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/buddhi-art/Stario-V2/build.yml?label=Build&style=flat-square" alt="Build Status">
  </a>
  <a href="https://github.com/buddhi-art/Stario-V2/releases/latest">
    <img src="https://img.shields.io/github/v/release/buddhi-art/Stario-V2?label=Release&style=flat-square&color=blue" alt="Latest Release">
  </a>
  <a href="https://github.com/buddhi-art/Stario-V2/blob/main/LICENSE.md">
    <img src="https://img.shields.io/badge/License-GPLv3-green.svg?style=flat-square" alt="License">
  </a>
  <a href="https://android.com">
    <img src="https://img.shields.io/badge/Android-10.0%2B%20(API%2029%2B)-brightgreen.svg?style=flat-square&logo=android&logoColor=white" alt="Android Version">
  </a>
</p>

---

## 🌟 About This Fork

**Stario V2** is an actively maintained, modernized continuation of the Stario minimalist launcher.

When upstream development paused, this fork was created to preserve what makes Stario special—clarity, elegance, and distraction-free usability—while overhauling the underlying engine for modern Android:

* **Apple-Grade Fluid Physics**: Continuous `SpringAnimation` motion for sheets, Glance dialogs, overscroll recovery, and wallpaper zoom with touch velocity handoff and momentum projection.
* **120Hz & Battery Efficiency**: Lifecycle-scoped sensors and redraw loops, pre-allocated draw paths/shaders, and bounded background worker pools.
* **Seamless App Transitions**: Icon-anchored launch animations, synchronized wallpaper zoom restoration, and Landmark Return Morph spring settle, plus optional native `RemoteAnimation` / `RemoteTransition` support when systemized on rooted devices.
* **Stability & Correctness**: Hardened lifecycle observer cleanup, TimSort-compliant category/app comparators, URL-safe search encoding, and an automated JVM unit test suite.

---

## ✨ Features

### 🍏 Fluid Physical Motion
* **Natural Spring Mechanics**: Drawer/side sheets, Glance dialogs, and wallpaper zooms settle via `SpringAnimation` with touch velocity handoff.
* **Hyperbolic Rubber-Banding**: Smooth progressive edge resistance (`f(x) = (x * d * c) / (d + c * |x|)`) without canvas stretching or distortion.
* **Spatial Continuity**: Shared-element transitions follow smooth `ArcMotion` curves (`SharedElementMotion`).

### ⚡ 120Hz Smooth & Lightweight
* **Zero Background Drain**: Gravity sensors and clock/text animations pause automatically when the view is off-screen or the launcher is backgrounded.
* **Zero Draw-Loop Churn**: Reusable paint geometry and shader matrices eliminate per-frame allocations during active rendering.
* **Bounded Concurrency**: Startup and background tasks run on CPU-aware bounded thread pools (`THREAD_PRIORITY_BACKGROUND`) and scoped coroutines.

### 🚀 Icon-Anchored App Transitions & Root Mode
* **Coordinate-Aware Launching**: Launches apps using `ActivityOptions.makeScaleUpAnimation` anchored to the tapped icon's screen bounds.
* **Landmark Return Morph**: Returning home smoothly restores wallpaper zoom and plays a spring rebound on the returning icon or home target.
* **Root / Privileged Mode (Optional, in Settings)**:
  * **Asynchronous Root Detection**: Checks root availability (`su`) off the main thread.
  * **Root Screen Lock**: Fast screen-off action via root without requiring an Accessibility Service.
  * **1-Tap Systemize Module**: Generates or removes a Magisk / KernelSU / APatch `priv-app` module (with `privapp-permissions` XML) so privileged transition permissions (`CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`) can power native `RemoteAnimation` / `RemoteTransition` window animations out of and back into icon bounds on rooted devices.

### 🎨 Minimalist Elegance & Productivity
* **Material You Dynamic Theming**: Adapts colors dynamically from wallpaper and system palette, with built-in accent themes and forced dark mode.
* **Minimalist Home & Glance Bar**: Stylized 12h/24h clock widget, pinned category bar, search shortcut, and Glance bar with a date/calendar app shortcut, weather forecast (`met.no` via IP geolocation or optional coarse location), and active media playback controls.
* **Custom App Drawer & Fuzzy Search**: Automatic and custom categories, custom icon packs and icon shapes, hidden apps controls, fuzzy app matching (`JaroWinklerDistance`), selectable web search engines, and optional Kagi Search Results API integration.
* **Modular Sheets & Page Manager**: Reorderable and toggleable sheets for App Drawer, RSS/Atom **Briefing** feed reader, and Android App **Widgets**.
* **Double-Tap to Lock**: Double-tap lock gesture with radial `ClosingAnimationView` effect (via Accessibility Service or Root Screen Lock).

---

## 📥 Download & Installation

Grab builds from the **[GitHub Releases Page](https://github.com/buddhi-art/Stario-V2/releases/latest)**:

| Build Asset | Recommended For | Description |
|---|---|---|
| **`Stario-vX.XX.apk`** | **Most Users** | Signed and aligned release build (published when repository signing secrets are configured). |
| **`Stario-vX.XX-debug.apk`** | **Developers / Testers** | Debug build with logging and debugging symbols enabled. |
| **`Stario-vX.XX-unsigned.apk`** | **Packagers** | Raw unsigned release APK for custom signing or reproducible build verification. |

### System Requirements
* **Android 10.0+ (API level 29 or higher)**
* Compatible with stock Android, Google Pixel, Samsung One UI, OnePlus/OxygenOS, Motorola, LineageOS, and custom ROMs.

---

## 🛠️ Building & Testing from Source

You can build and verify Stario locally using the Gradle wrapper:

### Prerequisites
* JDK 17 or higher
* Android SDK (API 36 platform & build-tools installed)

### Commands

```bash
# Clone the repository
git clone https://github.com/buddhi-art/Stario-V2.git
cd Stario-V2

# Ensure gradlew has execute permission
chmod +x gradlew

# Run JVM unit tests
./gradlew testDebugUnitTest

# Run Android Lint checks
./gradlew lintDebug

# Build Debug APK
./gradlew assembleDebug

# Build Release APK (unsigned unless keystore properties are provided)
./gradlew assembleRelease
```

Compiled APKs are generated in:
* `app/build/outputs/apk/debug/app-debug.apk`
* `app/build/outputs/apk/release/app-release-unsigned.apk`

For reproducible containerized builds, you can also use the included [`Dockerfile`](Dockerfile) and [`build.sh`](build.sh).

---

## 🤝 Acknowledgements & License

* Originally created by [Răzvan Albu](https://github.com/albu-razvan) and open-source contributors.
* Maintained and modernized by [@buddhi-art](https://github.com/buddhi-art).
* Licensed under the **GNU General Public License v3.0** (see [LICENSE.md](LICENSE.md)).
