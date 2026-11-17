# WristBrowser

A companion-assisted browser for Xiaomi Watch S4 (Xiaomi Vela).

WristBrowser is designed around a practical limitation: the watch is not treated as a full Android browser. The Android companion fetches and simplifies web pages; the Vela app displays readable content and lets the user navigate links.

## Planned architecture

- **Android companion (Kotlin + Jetpack Compose):** accept a URL, fetch the page over HTTPS, extract readable text and links, keep navigation/history, and transfer a compact page document to the watch.
- **Transport:** Xiaomi XMS Wearable SDK, using the same proven Notify for Xiaomi interconnect path as WristBook. The SDK artifact is downloaded from Xiaomi's original Open-Vela repository in CI; it is not silently replaced with a third-party fork.
- **Watch app (Xiaomi Vela Quick App):** receive a versioned message protocol, validate/reassemble chunks, show page title/text/links, and report transfer status.
- **Build:** GitHub Actions builds the Android APK and Vela RPK separately and uploads distinct artifacts.

## Reliability rules

1. Keep WristBook untouched; this repository is independent.
2. UTF-8 end-to-end. Split text only at Unicode code-point boundaries.
3. Every transfer has an ID, protocol version, sequence numbers, total size and checksum.
4. The watch acknowledges receipt; the phone does not report success merely because it queued messages.
5. Bound page size and chunk count; reject malformed or incomplete transfers.
6. Use explicit connection, transfer and parsing states. Never display missing values as the literal string `undefined`.
7. Build and validate each platform separately. Do not publish an APK/RPK as tested until CI succeeds.
8. Never execute page scripts on the watch. Display sanitized readable text and links only.

## MVP milestones

- [ ] Bootstrap reproducible Android and Vela builds.
- [ ] Confirm XMS connection and round-trip acknowledgement with a small test payload.
- [ ] Send and display a UTF-8 test page, including Cyrillic and emoji.
- [ ] Add URL entry and page extraction on Android.
- [ ] Add watch reading view, scrolling and link selection.
- [ ] Add back/forward navigation and a small history.

## Current status

Project bootstrap. No installable build is available yet.
