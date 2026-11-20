# WristAI

A text-first AI assistant for Xiaomi Watch S4 (Xiaomi Vela).

## Architecture

- **Watch:** compact chat interface; sends a text prompt and displays the returned answer.
- **Android phone:** acts as the AI server, receives watch prompts, calls Gemini, and returns the answer.
- **Transport:** the existing Xiaomi XMS / Notify for Xiaomi connection is retained. Pairing, connection setup, transport implementation, and `WearableBridge.kt` are unchanged.
- **Images:** disabled in the first text-only version. Image support is a separate beta milestone.

## API key

Paste the Google Gemini API key into the Android companion app. It is held in app memory only and is not saved to disk or committed.

The requested model ID is configured as `gemini-3.8-flash`. If Google does not expose that ID to the API key, the app will display the API error; the model ID will need to be checked against Google's current model catalogue.

## Scope

- The watch sends `ai_prompt` as an application-level message over the existing connection.
- The phone returns assistant text through the existing page-transfer envelope.
- No Bluetooth pairing, transport, protocol implementation, identifiers, or `WearableBridge.kt` code are changed.
- `WristBook` is not modified.

## Build

GitHub Actions builds the Android APK and Xiaomi Vela RPK. A build is ready only after the workflow succeeds and artifacts are present.
