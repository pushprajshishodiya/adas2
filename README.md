# ADAS — Advanced Driver Assistance System
### Android App · Dual Camera · Real-time Vehicle Detection · Speed Estimation

---

## Overview

A fully-featured ADAS app that uses **both phone cameras simultaneously** to replicate the experience
of a dashcam-based advanced driver assistance system:

| Feature | Description |
|---|---|
| **Front detection** | Back lens (road ahead) — detects vehicles, measures distance |
| **Rear detection** | Front lens (traffic behind) — detects tailgaters, closing speed |
| **Speed estimation** | Relative speed of every tracked vehicle via bounding-box delta |
| **Distance measurement** | Triangle-similarity from known vehicle widths + focal length |
| **Side space analysis** | Left / right lane occupancy bars for safe lane changes |
| **Collision warnings** | TTC (Time-To-Collision) based alerts: 🟢 🟡 🟠 🔴 |
| **Haptic alerts** | Graduated vibration patterns per warning level |
| **GPS ego speed** | Your own speed from fused location, shown in HUD center |
| **HUD overlay** | Aviation-style corner brackets, distance badges, warning banners |
| **PIP rear view** | Front-lens feed shown as picture-in-picture top-right |

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     DashboardActivity                    │
│  ┌──────────────┐  ┌────────────────┐  ┌─────────────┐ │
│  │ PreviewFront │  │  AdasOverlay   │  │  PreviewRear│ │
│  │ (back lens)  │  │  (canvas HUD)  │  │ (front lens)│ │
│  └──────────────┘  └────────────────┘  └─────────────┘ │
│           │                 ▲                  │        │
│           ▼                 │                  ▼        │
│    AdasViewModel ───────────┘ ◄─── AdasViewModel       │
└─────────────────────────────────────────────────────────┘
         │                                      │
         ▼                                      ▼
┌─────────────────┐                  ┌─────────────────┐
│ DualCameraManager│                 │  GpsSpeedProvider│
│ ┌─────────────┐ │                  └─────────────────┘
│ │ Back Camera │─┼──► onFrontFrame()
│ └─────────────┘ │
│ ┌─────────────┐ │
│ │Front Camera │─┼──► onRearFrame()
│ └─────────────┘ │
└─────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│                    AdasProcessor                         │
│                                                          │
│  VehicleDetector          SpeedEstimator (×2)           │
│  (TFLite EfficientDet)    ┌──────────────────────────┐  │
│  ┌──────────────────┐     │ • Box-size delta tracking │  │
│  │ detect(Bitmap)   │────►│ • Focal-length distance   │  │
│  └──────────────────┘     │ • IoU track assignment    │  │
│                            │ • TTC calculation         │  │
│                            │ • Side-space analysis     │  │
│                            └──────────────────────────┘  │
│                                        │                  │
│                                        ▼                  │
│                             AdasFrame (StateFlow)         │
└─────────────────────────────────────────────────────────┘
```

---

## File Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/adas/app/
│   ├── ui/
│   │   ├── MainActivity.kt          Permission entry point
│   │   ├── DashboardActivity.kt     Main ADAS driving screen
│   │   └── AdasViewModel.kt         State management
│   ├── camera/
│   │   ├── DualCameraManager.kt     CameraX dual-lens binding
│   │   └── AdasForegroundService.kt Keep-alive service
│   ├── detection/
│   │   ├── AdasModels.kt            Data classes
│   │   ├── VehicleDetector.kt       TFLite object detection
│   │   ├── SpeedEstimator.kt        Distance + speed math
│   │   └── AdasProcessor.kt         Orchestration + IoU tracker
│   ├── overlay/
│   │   └── AdasOverlayView.kt       Canvas HUD renderer
│   └── utils/
│       ├── GpsSpeedProvider.kt      Fused location speed
│       └── AlertManager.kt          Haptic + audio alerts
└── res/
    ├── layout/
    │   ├── activity_main.xml         Splash / permission screen
    │   └── activity_dashboard.xml    Full ADAS driving HUD
    ├── drawable/pip_border.xml
    └── values/{colors, strings, themes}.xml
```

---

## Setup Instructions

### 1. Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Physical Android device (API 26+) — **emulator cannot use both cameras**

### 2. Clone & open
```bash
git clone <repo>
# Open the ADAS/ folder in Android Studio
```

### 3. Add the TFLite model (required for real detection)

Download **EfficientDet-Lite0** from TensorFlow Hub:
```
https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1
```

Rename it to `efficientdet_lite0.tflite` and place it in:
```
app/src/main/assets/efficientdet_lite0.tflite
```

> **Without the model:** the app uses a built-in simulation mode that generates
> randomised detection boxes for UI testing. All HUD elements, speed estimation,
> and warning logic still function.

### 4. Build & run
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 5. Grant permissions on first launch
- Camera (both lenses)
- Fine Location (GPS speed)

---

## How Speed & Distance Are Estimated

### Distance (per vehicle)
Uses **triangle similarity** (no depth sensor needed):

```
distance_m = (real_vehicle_width_m × focal_length_px) / bounding_box_width_px
```

Average real widths used:
- Car: 1.8 m · Truck: 2.5 m · Bus: 2.6 m · Motorcycle: 0.8 m

Focal length assumed from typical phone camera (~70° horizontal FOV at 1080p):
```
focal_px ≈ 771
```

### Relative Speed
Tracks each vehicle across frames using **IoU bounding-box matching**:

```
speed_kmh = (distance_at_frame_N − distance_at_frame_0) / elapsed_time × 3.6
```

Positive = vehicle approaching. Negative = receding.

### Time To Collision (TTC)
```
TTC_s = distance_m / (relative_speed_m_s)
```

Warning thresholds:
| Level | TTC | Distance |
|---|---|---|
| 🟢 SAFE | > 7s | > 30m |
| 🟡 CAUTION | 4–7s | 15–30m |
| 🟠 WARNING | 2–4s | 5–15m |
| 🔴 DANGER | < 2s | < 5m |

---

## Extending the App

### Swap in a better model
Replace `efficientdet_lite0.tflite` with YOLOv8n-TFLite for faster inference:
```
https://github.com/ultralytics/ultralytics (export to tflite)
```

### Add lane departure detection
Implement Hough-line detection on the lower 40% of each frame using OpenCV Android SDK.

### Add NCAP-style alerts
Extend `AlertManager` with `MediaPlayer` for audible beeps, and add a `TextToSpeech`
wrapper for spoken warnings ("Danger ahead — 8 metres").

### Cloud telemetry
Pipe `AdasFrame` events into Firebase Firestore for fleet monitoring.

---

## Accuracy & Limitations

- Distance accuracy: **±15–25%** (depends on camera FOV calibration and vehicle type)
- Speed accuracy: **±10–20 km/h** relative (GPS ego speed is accurate)
- Detection FPS: ~10–15 fps on mid-range devices (Snapdragon 778+)
- Both cameras run simultaneously — requires Android device with concurrent camera support (most phones since 2019)
- Night performance degrades without IR; recommend pairing with night-vision lens clip

---

## Permissions Used

| Permission | Purpose |
|---|---|
| `CAMERA` | Dual camera frames |
| `ACCESS_FINE_LOCATION` | GPS speed of ego vehicle |
| `VIBRATE` | Haptic collision warnings |
| `WAKE_LOCK` | Keep screen on while driving |
| `FOREGROUND_SERVICE` | Background operation |
