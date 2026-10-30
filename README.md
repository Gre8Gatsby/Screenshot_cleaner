# 📸 Screenshot Auto-Expirer (Android + Rust)

[![Android CI](https://github.com/Gre8Gatsby/Screenshot_cleaner/actions/workflows/ci.yml/badge.svg)](https://github.com/Gre8Gatsby/Screenshot_cleaner/actions)
[![Rust](https://img.shields.io/badge/Rust-1.75%2B-orange.svg?logo=rust)](https://www.rust-lang.org/)
[![Android Min SDK](https://img.shields.io/badge/Min%20SDK-29%20(Android%2010)-green.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35%20(Android%2015)-blue.svg)](https://developer.android.com)
[![APK Size](https://img.shields.io/badge/Release%20APK-~1.5%20MB-success.svg)](#-apk-size-budget--optimization)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> An open-source, privacy-first, ultra-lightweight Android app that automatically detects and tags temporary & sensitive screenshots (**OTPs**, **UPI / Payment confirmations**, **Boarding Passes**, and **Tickets**) and hard-deletes them after **24 hours** to keep your gallery clean and your credentials safe.

---

## 📑 Table of Contents

- [Why This Project Exists](#-why-this-project-exists)
- [Key Features](#-key-features)
- [Architecture & Tech Stack](#-architecture--tech-stack)
- [Classification Engine & Supported Tags](#-classification-engine--supported-tags)
- [APK Size Budget & Optimization](#-apk-size-budget--optimization)
- [👩‍💻 Developer Guide](#-developer-guide)
  - [Prerequisites](#prerequisites)
  - [Local Setup](#local-setup)
  - [Building Rust Core](#building-rust-core)
  - [Building the Android App](#building-the-android-app)
  - [Adding New Detection Patterns](#adding-new-detection-patterns)
- [🧪 Tester & QA Runbook](#-tester--qa-runbook)
  - [Testing on a Physical Phone](#testing-on-a-physical-phone)
  - [Verification Test Scenarios](#verification-test-scenarios)
  - [Sample Text Snippets for Quick Testing](#sample-text-snippets-for-quick-testing)
- [🤝 Contributing Guidelines](#-contributing-guidelines)
- [🔒 Privacy & Security Commitment](#-privacy--security-commitment)
- [License](#-license)

---

## 💡 Why This Project Exists

Every day, smartphone users take dozens of screenshots of:
- **One-Time Passwords (OTPs) & 2FA codes**
- **UPI, GPay, Paytm, and credit card payment receipts**
- **Flight boarding passes, bus passes, and train tickets (PNR)**
- **Guest Wi-Fi passcodes and order confirmations**

These screenshots clutter your gallery and, worse, **leave sensitive financial and credential data sitting indefinitely on storage**.

**Screenshot Auto-Expirer** solves this without annoying manual cleanup. It works quietly in the background, tags sensitive screenshots, gives you an instant notification with **[Keep Forever]** or **[Delete Now]** buttons, and hard-deletes unkept screenshots once their retention period expires.

---

## ✨ Key Features

| Feature | Description |
|---|---|
| **Privacy First (100% On-Device)** | Zero network calls. No internet permission in `AndroidManifest.xml`. OCR, regex parsing, and SQLite storage stay strictly on your phone. |
| **Tiny Footprint (< 1.5 MB APK)** | Written in **Rust** (JNI `.so` compiled with `opt-level = "z"`, LTO, stripped) + lightweight View system (no 3MB Compose runtime). |
| **Zero-Bloat ML OCR** | Uses Google Play Services ML Kit dynamic delivery (`play-services-mlkit-text-recognition`). The OCR model is loaded from Google Play Services, avoiding an 8–15 MB bundled OCR binary. |
| **Smart 3-Tier Classification** | Tier 1 (Filename heuristic, 0ms) ➔ Tier 2 (On-device OCR + Regex patterns) ➔ Tier 3 (Multi-cue confidence scoring). |
| **Interactive Notifications** | Heads-up notification on detection with one-tap action buttons: **[Keep Forever]** (preserves file) and **[Delete Now]** (instant scrub). |
| **Day-1 "Scan Gallery"** | Want to clean older screenshots immediately after installation? The **"Scan Gallery"** button retroactively analyzes the last 30 days of screenshots with full preview. |
| **Battery Friendly** | Uses Android's native `ContentObserver` and AndroidX `WorkManager`. Consumes `< 0.5%` battery per day. |

---

## 🏗 Architecture & Tech Stack

```
┌────────────────────────────────────────────────────────────────────────┐
│                        ANDROID APPLICATION LAYER                       │
│                                                                        │
│   MainActivity ─────── ScreenshotService ─────── NotificationHelper    │
│   (Dashboard & UI)    (Foreground Watcher)      (Actions & Alerts)     │
│          │                     │                         │             │
│          │              ContentObserver                  │             │
│          ▼                     ▼                         ▼             │
│    ExpireWorker ──────►  ExpirerJni.kt  ◄────── NotificationAction     │
│   (WorkManager 15m)            │                       Receiver        │
└────────────────────────────────┼───────────────────────────────────────┘
                                 │ JNI (Java Native Interface)
┌────────────────────────────────▼───────────────────────────────────────┐
│                     RUST NATIVE CORE (libexpirer.so)                   │
│                                                                        │
│   jni_bridge.rs  ──► Safe Mutex with Poison Recovery                   │
│         │                                                              │
│   ┌─────┴───────────────┬───────────────────┬──────────────┐           │
│   ▼                     ▼                   ▼              ▼           │
│ patterns.rs       classifier.rs           db.rs       expirer.rs       │
│ (Regex & Cues)   (Scoring & TTL)        (SQLite)    (File & FS Engine) │
└────────────────────────────────────────────────────────────────────────┘
```

- **Android Layer**: Kotlin, AndroidX, WorkManager, Google Play Services ML Kit.
- **Native Core**: Rust 2021 edition, `rusqlite` (bundled zero-system-dependency SQLite), `regex`, `chrono`, `serde`, `jni 0.21`.
- **IPC**: Native JNI bindings (`Java_com_expirer_core_ExpirerJni_*`).

---

## 🏷 Classification Engine & Supported Tags

| Tag | Matched Keywords & Patterns | Default TTL |
|---|---|---|
| **`OTP`** | 4–8 digit verification codes, "one-time password", "verification code", "do not share", "security code", "valid for", "expires in" | **24 Hours** |
| **`PAYMENT`** | "paid successfully", "payment successful", "transaction id", "upi ref no", `₹`, `$`, `€`, `INR`, "debited from", "credited to", "PhonePe", "GPay", "Paytm" | **24 Hours** |
| **`BOARDING_PASS`** | "boarding pass", "flight ticket", "gate", "seat no", "terminal", "pnr", "departure time", "irctc", "coach", "berth", "passenger name" | **48 Hours** |
| **`RECEIPT`** | "tax invoice", "receipt #", "order confirmed", "grand total", "subtotal", "bill amount" | **72 Hours** |
| **`TEMPORARY`** | "temporary password", "wifi password", "guest wifi", "passcode", "access code" | **24 Hours** |
| **`OTHER`** | Normal chats, social media memes, landscapes, selfies | **Never Auto-Expired** |

---

## 📦 APK Size Budget & Optimization

| Component | Target Size | Optimization Tactic |
|---|---|---|
| **`libexpirer.so` (Rust)** | **~350–450 KB** | `opt-level = "z"`, `lto = true`, `codegen-units = 1`, `panic = "abort"`, `strip = true` |
| **SQLite (Bundled)** | **~500 KB** | Zero-copy `rusqlite` statically linked into `.so` |
| **DEX Bytecode** | **~350 KB** | R8 Full Mode (`android.enableR8.fullMode=true`), dead-code stripping, no Compose runtime |
| **ML OCR Model** | **0 MB (in APK)** | Google Play Services dynamic thin client (downloaded on demand via Play Services) |
| **Total Release APK** | **~1.5 MB** | **Over 70% smaller** than typical cross-platform or Go/Flutter alternatives |

---

## 👩‍💻 Developer Guide

### Prerequisites
1. **Rust Toolchain**: `rustup` with Rust 1.75+
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   ```
2. **Android Targets for Rust**:
   ```bash
   rustup target add aarch64-linux-android x86_64-linux-android
   ```
3. **`cargo-ndk`**:
   ```bash
   cargo install cargo-ndk
   ```
4. **JDK 17** & **Android Studio** (or Android SDK command-line tools with NDK `r26b+`).

---

### Local Setup

```bash
# 1. Clone the repository
git clone https://github.com/Gre8Gatsby/Screenshot_cleaner.git
cd screenshot-auto-expirer

# 2. Run Rust unit & integration tests
cd rust-core
cargo test
cargo clippy -- -D warnings
cd ..
```

---

### Building Rust Core

You can build the native `.so` files using the included build script:

```bash
# Build native binaries for local host testing and verification
./build-native.sh

# Or cross-compile for Android arm64 & x86_64 via cargo-ndk
cd rust-core
cargo ndk -t arm64-v8a -t x86_64 -o ../app/src/main/jniLibs build --release
cd ..
```

---

### Building the Android App

```bash
# Debug build (for rapid device testing)
./gradlew assembleDebug

# Release build (with full R8 code shrinking)
./gradlew assembleRelease
```
The output APKs are located at:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

---

### Adding New Detection Patterns

Want to add support for a new payment gateway, transit authority, or language?

1. Open [`rust-core/src/patterns.rs`](rust-core/src/patterns.rs).
2. Add your keywords or regex pattern to `PatternMatcher::evaluate`:
   ```rust
   // Example: Adding French OTP terms
   let otp_keywords_fr = ["code de vérification", "ne partagez pas", "mot de passe temporaire"];
   for kw in otp_keywords_fr {
       if lower_text.contains(kw) {
           otp_score += 0.25;
           cues.push(format!("otp_fr:{}", kw));
       }
   }
   ```
3. Add a unit test to verify your pattern in [`rust-core/src/patterns.rs`](rust-core/src/patterns.rs):
   ```rust
   #[test]
   fn test_french_otp() {
       let matcher = PatternMatcher::new();
       let (tag, conf, _) = matcher.evaluate("Votre code de vérification est 829104.", "Screenshot.png");
       assert_eq!(tag, TagType::Otp);
       assert!(conf >= 0.5);
   }
   ```
4. Run `cargo test` to ensure zero regressions!

---

## 🧪 Tester & QA Runbook

### Testing on a Physical Phone

1. **Enable Developer Options on Android**:
   - Go to **Settings > About Phone** -> Tap **Build Number** 7 times.
   - Go to **Settings > System > Developer Options** -> Enable **USB Debugging** (and "Install via USB" if on MIUI/ColorOS/Funtouch).
2. **Connect via USB and install**:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. **Launch the App & Grant Permissions**:
   - Grant Photos/Media (`READ_MEDIA_IMAGES`) and Notification permissions.
   - Observe the dashboard status pill turns **"Watcher Active"**.

---

### Verification Test Scenarios

| Test Case | Steps | Expected Result |
|---|---|---|
| **TC-01: OTP Screenshot Detection** | Take a screenshot of an SMS or note containing: *"Your Google verification code is 492018. Do not share."* | Within 1–2 seconds, a heads-up notification pops up: `Temporary Screenshot: OTP · Will auto-delete in 24h` with `[Keep Forever]` and `[Delete Now]` actions. |
| **TC-02: Action [Keep Forever]** | Tap `[Keep Forever]` on the notification from TC-01. | Notification dismisses; Toast appears: *"Screenshot marked as Kept Forever"*; item is marked as `KEPT` in SQLite and never deleted. |
| **TC-03: Action [Delete Now]** | Take a mock payment screenshot, then tap `[Delete Now]` on the notification. | Image file is immediately deleted from disk and MediaStore, and removed from database. |
| **TC-04: Non-Sensitive Image (Negative Test)** | Take a screenshot of an Instagram meme, a WhatsApp chat conversation, or a webpage article. | No notification is triggered; screenshot is labeled `OTHER` and remains completely untouched. |
| **TC-05: Retroactive "Scan Gallery" (First-Day Test)** | Tap the **"Scan Gallery"** button on the home dashboard. | Scans screenshots taken over the past 30 days; surfaces past OTPs and receipts with countdowns and action buttons. |
| **TC-06: Manual "Clean Now"** | In app, tap **"Clean Now"**. | Triggers `ExpireWorker` immediately. Any item with `expires_at <= now` is deleted, and the **"Space Freed"** counter updates. |
| **TC-07: Reboot Survival** | Reboot the Android phone. | `BootReceiver` restarts `ScreenshotService` and re-registers the `ContentObserver` automatically without needing manual app launch. |
| **TC-08: Duplicate / Edit Shield** | Take a screenshot and crop or edit it in Google Photos. | Deduplication logic prevents duplicate alerts for the same MediaStore ID. |

---

### Sample Text Snippets for Quick Testing

You can paste any of these snippets into Google Keep, WhatsApp, or Notes and take a screenshot to test the classifier:

#### 1. OTP / 2FA Test Snippet:
```
Your Uber verification code is 582910.
Never share this OTP with anyone. Valid for 10 minutes.
```

#### 2. UPI / Payment Test Snippet:
```
Paid successfully ₹450.00 to Starbucks Coffee
UPI Transaction ID: 394829104928
Debited from HDFC Bank A/c **4029
```

#### 3. Boarding Pass Test Snippet:
```
IndiGo Boarding Pass
Flight: 6E 204 | Seat: 12A | Gate: 14B | Terminal: 2
PNR: W8XZ9Q | Passenger: John Doe
```

---

## 🤝 Contributing Guidelines

We welcome contributions from developers and testers of all skill levels!

### Ways to Contribute
1. **Add Regional Payment & Transit Patterns**: Help expand support for regional apps (e.g., Mercado Pago, Pix, Swish, Vipps, Venmo, CashApp, Alipay, WeChat Pay, JR Rail, Deutsche Bahn).
2. **UI & Localization**: Translate strings into Spanish, Hindi, French, German, Japanese, etc.
3. **Android Jetpack Glance Widget**: Help build a lightweight home screen widget showing active countdowns.
4. **Bug Reports & QA**: Test on different Android OEM skins (Samsung OneUI, Xiaomi MIUI/HyperOS, ColorOS) and submit bug reports with logcat outputs.

### Contribution Workflow
1. Fork the repository.
2. Create a feature branch: `git checkout -b feature/awesome-pattern`.
3. Format & check code:
   ```bash
   cd rust-core && cargo fmt --check && cargo clippy -- -D warnings && cargo test
   ```
4. Commit with a descriptive message: `git commit -m "feat(patterns): add Pix payment pattern support"`.
5. Open a Pull Request!

---

## 🔒 Privacy & Security Commitment

- **No Network Requests**: This application does not declare `android.permission.INTERNET`. It is physically impossible for the app to transmit screenshot bytes or OCR text off your device.
- **No Cloud Analytics**: Zero third-party analytics trackers, zero crash reporters sending telemetry.
- **Audit-Friendly**: All classification logic is contained in open, human-readable Rust code in [`rust-core/src/patterns.rs`](rust-core/src/patterns.rs).

---

## 📄 License

This project is open-source under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.
