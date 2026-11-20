# NFCReader

Turn your Android smartphone into an NFC reader. The app reads NFC tags (UID, technologies, NDEF messages) and can write text/URI records to tags. All data is sent to a PC over TCP (typically via USB using `adb forward`), where a Python console client receives and logs the events.

> **Status:** Working prototype, now hardened for open-source publication.

## Architecture

```
PC (reader.py)  TCP commands  -->  Android MainActivity.ServerSocket (port 18765)
Android                                     <--  JSON events  --  PC
      |
      | NFC tag touch (enableReaderMode callback)
      v
  Ndef.read()  -->  JSONObject  -->  sendJson()  -->  PC
```

- **Android app** (`app/src/main/java/com/nirkzxc/nfcreader/MainActivity.kt`): single Activity that initializes `NfcAdapter`, starts a TCP server on port 18765 (loopback only), accepts commands from the PC, handles NFC tag touches via reader mode, and sends JSON events back.
- **PC client** (`reader.py`): console client that connects to the phone over TCP, sends `SCAN` / `WRITE_TEXT|...` / `WRITE_URI|...` / `CANCEL_WRITE` / `PING` / `quit`, receives JSON events, and logs scans to `scans.jsonl`.
- **Protocol** (`CommandHandler.kt`): plain-text, newline-delimited commands; JSON events (one per line), UTF-8 encoded.

## Prerequisites

- Android device with NFC hardware (NFC-A/B/F/V support)
- Android Studio or command-line Gradle with JDK 17+
- Android SDK (compileSdk 37, minSdk 26)
- `adb` with USB debugging enabled on the device
- Python 3.8+ on the PC

## Building the Android App

```bash
# Clone the repository
git clone https://github.com/your-username/NFCReader.git
cd NFCReader

# Build the debug APK
./gradlew assembleDebug

# The APK is at: app/build/outputs/apk/debug/app-debug.apk
```

### Release Build

Release builds require a proper signing configuration. Do **not** commit signing secrets to the repository.

1. Create a `signing.properties` file in the project root (it is git-ignored):

```properties
storeFile=/path/to/release-keystore.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

2. Generate a release key:

```bash
keytool -genkey -v -keystore release-keystore.jks -alias nfcreader -keyalg RSA -keysize 2048 -validity 10000
```

3. Build the release APK:

```bash
./gradlew assembleRelease
```

> The release `buildType` requires an explicit release signing configuration. Without a `signing.properties` file (or CI equivalent), release builds fail with a clear error — the debug certificate is never used for release.

## Connecting PC to Android (USB)

The app binds its TCP server to `127.0.0.1:18765` (loopback only). To reach it from your PC over USB, set up an adb forward tunnel:

```bash
adb forward tcp:18765 tcp:18765
```

After this, `reader.py` connects to `127.0.0.1:18765` and traffic is forwarded to the phone.

> The tunnel must be re-established after each `adb` restart or device reboot. It is **not** documented in the original code — this README fixes that gap.

## Running the PC Client

```bash
# Basic usage (auth token must match BuildConfig.AUTH_TOKEN on Android).
# The token must be configured on both sides — there is no default token.
python reader.py --auth-token your-secret-token

# Custom host/port
python reader.py --host 127.0.0.1 --port 18765 --auth-token your-secret-token

# Custom scan log file
python reader.py --auth-token your-secret-token --log-file my_scans.jsonl
```

### Interactive Commands

| Command | Action |
|---------|--------|
| `scan` | Enable scan mode; wait for an NFC tag touch |
| `text <text>` | Arm a text write; touch a writable NDEF tag |
| `uri <url>` | Arm a URI write; touch a writable NDEF tag |
| `cancel` | Cancel a pending write |
| `ping` | Send a ping; expect `pong` |
| `quit` | Disconnect and exit |

Scan events are printed to the console and appended to `scans.jsonl` (JSON Lines format, one JSON object per line).

## Security Model

- **Loopback-only binding:** The TCP server listens on `127.0.0.1` only, not on all interfaces (`0.0.0.0`). The port is not exposed to Wi-Fi/LAN.
- **Authentication:** Every TCP connection must authenticate with `AUTH|<token>` before any other command is accepted. The token is configured via `BuildConfig.AUTH_TOKEN` on Android and `--auth-token` on the PC.
- **Command validation:** `WRITE_URI` is validated against `Patterns.WEB_URL`; empty text/URI commands are rejected.
- **No encryption:** Traffic is still plaintext. Use this tool only in trusted environments (single PC + single phone over USB/adb). For production use, add TLS or a VPN tunnel.

## Known Limitations

- NFC only works while the app is in the foreground (`enableReaderMode` / `disableReaderMode` lifecycle).
- Devices without NFC hardware will not crash (null check added), but NFC reading is disabled.
- Requires a physical NFC tag for write testing — unit tests cover protocol logic without hardware.
- `scans.jsonl` is written with default file permissions; no encryption at rest.

## Testing

### Android Unit Tests

```bash
./gradlew testDebugUnitTest
```

Tests cover command parsing (SCAN, WRITE_TEXT, WRITE_URI, CANCEL_WRITE, PING, AUTH), URI validation, and JSON event construction.

### Python Tests

```bash
pip install pytest
python -m pytest tests/ -v
```

Tests cover `send()`, `save_scan()`, CLI argument parsing, receiver event dispatch, and socket timeout configuration.

### CI

GitHub Actions runs on every push and pull request:

- **Android:** Build debug APK + run unit tests (JDK 17, Ubuntu)
- **Python:** Run pytest suite (Python 3.11, Ubuntu)

## Project Structure

```
NFCReader/
├── app/
│   ├── src/main/java/com/nirkzxc/nfcreader/
│   │   ├── MainActivity.kt        # NFC + TCP server + command handling
│   │   └── CommandHandler.kt      # Testable command parsing logic
│   ├── src/test/...               # JUnit unit tests (JVM)
│   └── src/androidTest/...        # Instrumented test stubs
├── reader.py                      # PC-side TCP client
├── tests/test_reader.py           # Python pytest tests
├── .github/workflows/ci.yml       # GitHub Actions CI
├── signing.properties             # (git-ignored) release signing config
├── scans.jsonl                    # (git-ignored) PC scan log
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## License

MIT License — see [LICENSE](LICENSE).

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add or update tests
5. Ensure CI passes
6. Submit a pull request
