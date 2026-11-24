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

When upstream development paused, this fork was created to preserve what makes Stario special—clarity, elegance, and distraction-free usability—while completely overhauling the underlying engine for modern Android:

* **Apple-Grade Fluid Physics**: Upgraded from rigid, time-based animators to continuous physical `SpringAnimation` engines with touch velocity handoff.
* **120Hz & Zero-Jank Efficiency**: Eradicated background polling loops, redundant invalidations, and unbounded thread pools.
* **Seamless Window Transitions**: Implemented icon-anchored scale-up launches and the Landmark Return Morph spring bounce.
* **Lifecycle & Stability Hardening**: Resolved static observer leaks, TimSort contract bugs, and concurrency races.

---

## ✨ Features

### 🍏 Fluid Physical Motion
* **Natural Spring Mechanics**: Sheets, dismissals, and wallpaper zooms calculate motion dynamically based on touch velocity and momentum ($\Delta v = 0$).
* **Logarithmic Rubber-Banding**: Replaced canvas stretching with smooth, organic edge resistance following true Apple iOS curves.
* **Spatial Continuity**: Replaced random trajectory wobbles with smooth, predictable `ArcMotion` paths.

### ⚡ 120Hz Buttery Smooth & Lightweight
* **Zero Background Drain**: Sensors and clock redraw loops are strictly paused whenever the screen is off or the launcher is in the background.
* **Optimized View Recycling**: ViewHolders and child layout managers in pinned widgets and search are cached and reused, eliminating frame drops.
* **Bounded Resource Usage**: Replaced unbounded startup thread storms with CPU-aware thread pools running with background priority.

### 🚀 Icon-Anchored App Transitions
* **Coordinate-Aware Launching**: Launches applications using `ActivityOptions.makeScaleUpAnimation` anchored to the clicked icon's screen position.
* **Landmark Return Morph**: When swiping or tapping Home (`onNewIntent`), the returning icon plays a subtle spring rebound alongside wallpaper zoom restoration, masking system window pop-in.

### 🎨 Minimalist Elegance & Productivity
* **Material You Dynamic Theming**: Colors dynamically adapt to your wallpaper and Android palette.
* **Custom App Drawer**: Categorize and group applications cleanly with custom icons, shapes, and hide options.
* **Glance Extension**: Built-in weather forecast, calendar events, and media controls without battery-draining polling loops.
* **Integrated Feed & Search**: Built-in RSS/Atom feed reader and fast global web/app search.

---

## 📥 Download & Installation

Grab the latest build directly from the **[GitHub Releases Page](https://github.com/buddhi-art/Stario-V2/releases/latest)**:

| Build Asset | Recommended For | Description |
|---|---|---|
| **`Stario-vX.XX.apk`** | **Most Users** | Fully signed and aligned release build. Tap to install directly on any device. |
| **`Stario-vX.XX-debug.apk`** | **Developers / Testers** | Debug build with logging and symbols enabled for testing. |
| **`Stario-vX.XX-unsigned.apk`** | **Packagers** | Raw unsigned release build for custom signing or repository packagers. |

### System Requirements
* **Android 10.0+ (API level 29 or higher)**
* Compatible with stock Android, Google Pixel, Samsung One UI, OnePlus/OxygenOS, Motorola, LineageOS, and custom ROMs.

---

## 🛠️ Building from Source

You can build Stario locally using the Gradle wrapper:

### Prerequisites
* JDK 17 or higher
* Android SDK (API 36 build-tools & platform installed)

### Build Commands

```bash
# Clone the repository
git clone https://github.com/buddhi-art/Stario-V2.git
cd Stario-V2

# Ensure gradlew has execute permission
chmod +x gradlew

# Build Debug APK (installs immediately via adb)
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

Compiled APKs will be generated in:
* `app/build/outputs/apk/debug/app-debug.apk`
* `app/build/outputs/apk/release/app-release-unsigned.apk`

---

## 🤝 Acknowledgements & License

* Originally created by [Razvan Albu](https://github.com/albu-razvan) and wonderful open-source contributors.
* Maintained and modernized by [@buddhi-art](https://github.com/buddhi-art).
* Licensed under the **GNU General Public License v3.0** (see [LICENSE.md](LICENSE.md)).
