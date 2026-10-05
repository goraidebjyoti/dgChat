# dgChat

**Decentralized Messaging**  
**Created by Debjyoti Gorai**

Native Android peer-to-peer messaging with nearby BLE mesh communication, ciphertext-only store-and-forward queues, and an optional internet bridge through independently operated WSS relays.

- **Author:** Debjyoti Gorai
- **Website:** https://goraidebjyoti.github.io
- **GitHub:** https://github.com/goraidebjyoti/
- **Email:** debjyotigorai@outlook.com

dgChat is an independently developed decentralized messaging application.

## Delivery status

This is a substantial **0.1.1 engineering implementation for validation**, not a security-audited production release. The Android app, real BLE/GATT adapter, routing, Noise integration, Room persistence, Compose UI, internet adapter, relay and CI configuration are supplied. **No APK or successful Android build is claimed in this delivery.** The authoring environment lacks the Android SDK and dependency-download access. See [verification.md](docs/verification.md) for exactly what ran and what remains unverified.

The project targets Android 12–15 (minimum API 31; compile/target API 35). Airplane mode works only when the user separately re-enables Bluetooth. Android devices need BLE advertising/peripheral support for the mesh implementation.

## Get an APK using GitHub Actions

1. Create a GitHub repository, for example `dgChat`. Public or private both work with Actions, subject to your GitHub plan's usage limits.
2. Upload **the contents of this `dgChat` directory to the repository root**. Include `.github/workflows/`; do not upload only the ZIP. You can also use ordinary Git push.
3. Open **Actions → Build dgChat APK → Run workflow** and choose **debug**. Pushes to `main`/`master` and pull requests also build debug automatically.
4. Wait for the complete run to succeed. Open the run and scroll to **Artifacts**. Download `dgChat-debug-<run number>`, unzip it, and find `app-debug.apk`.
5. Copy that APK to an Android 12+ device. Enable “Install unknown apps” for the app opening the APK, install it, choose a display name and tap **Join nearby mesh**. Grant Nearby devices access. Notification permission is optional on Android 13+; the foreground service still needs its notification.
6. Install the same APK on another supported device, turn Bluetooth on, and start its mesh. Use **Nearby** to open a conversation. Compare full fingerprints before marking identities verified.

A failed workflow does not produce an installable artifact. Read the failing step and the validation reports; do not treat a successful core test as evidence that Android compilation succeeded.

Workflow checks: pinned JDK/Gradle/SDK setup; official wrapper generation with distribution SHA-256 verification; JVM core tests; Noise security tests; debug lint; instrumentation-APK compilation; relay policy tests; debug APK build; optional signed-release build; APK and report artifacts; signing-file cleanup and run summary.

## Release signing

Never use a debug APK as a production release. Create and securely retain a release signing key **outside the repository**. Add these repository **Actions Secrets**:

| Secret | Value |
|---|---|
| `DGCHAT_KEYSTORE_BASE64` | Base64 of your keystore bytes |
| `DGCHAT_STORE_PASSWORD` | Keystore password |
| `DGCHAT_KEY_ALIAS` | Signing key alias |
| `DGCHAT_KEY_PASSWORD` | Key password |

Run the workflow with `build_type: release`, or push a version tag beginning with `v`. Release requests fail clearly if any signing secret is missing. Release credentials are available only to the signing steps. The temporary keystore is deleted in an `always()` cleanup step. The installable release artifact is `dgChat-release-<run number>`. Retain the original signing key for future updates; debug and release are separate app IDs and therefore separate local identities.

The local `assembleRelease` task without signing configuration produces an **unsigned** APK. It is not installable as a signed release; CI release requests require the four secrets above.

## Local Android Studio build

Install JDK 17 and an Android SDK with Android 35 / Build Tools 35.0.0. Set `ANDROID_HOME` or create your own ignored `local.properties` with `sdk.dir=...`.

```bash
chmod +x gradlew
# First run installs Gradle with a pinned checksum and creates the standard official wrapper.
./gradlew wrapper --gradle-version 8.11.1 --distribution-type bin \
  --gradle-distribution-sha256-sum f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows use `gradlew.bat`. Then open this directory in Android Studio and sync Gradle. Direct dependency versions and the Gradle distribution are fixed; the Compose BOM pins Compose versions. This is reproducible toolchain/dependency selection, **not a claim of byte-for-byte deterministic APKs**. Transitive dependency locking and full dependency checksum verification are future hardening work.

`gradle/wrapper/dgchat-bootstrap.jar` is an independently written bootstrap, with its source at `tools/bootstrap/GradleBootstrap.java`. It is **not** the official Gradle wrapper. It downloads the pinned Gradle 8.11.1 ZIP, verifies the official SHA-256 and launches Gradle. CI creates the official wrapper before building. `gradlew` uses the official wrapper if it has been generated locally. This avoids shipping an unverifiable upstream binary in this restricted authoring environment.

Core checks can also run without Android or Gradle on a JDK with the compiler module:

```bash
./tools/test-core.sh
python3 -m unittest discover -s relay -p 'test_*.py' -v
python3 tools/static-check.py
```

## Using dgChat

- **Home:** start/stop foreground mesh networking, inspect current network status and enter `#local`.
- **Chats:** private conversations and public rooms. Create additional public rooms by name.
- **Nearby:** recently seen peers, connection status, approximate signal and hop count.
- **Peers:** saved public identities; import an identity code or decode a QR image. Import never automatically verifies a person.
- **Settings:** display name, optional public bio and local avatar, theme, activity mode, courier opt-in, relay opt-in and developer diagnostics.
- **Quick Clear:** tap the clear-history icon in the top bar or **Settings → Quick Clear → Open Quick Clear**, then **Clear now**. Stops networking and deletes local chats, outbox, courier envelopes, public cache, private receipt records and temporary media. Identity, profile/settings and saved verified peers remain. Networking resumes only when you join the mesh yourself. See [Quick Clear](docs/quick-clear.md) for recovery and deletion limits.
- **Identity:** public QR and full fingerprint. **About:** author credits and project links.
- **Private conversation:** text, compressed images, files up to 12 KB, short voice clips. Save attachments using Android's document picker. Audio playback uses temporary app-private cache files that are deleted on completion, leaving the screen/backgrounding, or next launch.

Private state meanings: **Queued** = awaiting a route; **Sent** = accepted by a transport, unconfirmed; **Delivered** = authenticated recipient ACK; **Read** = authenticated read receipt; **Failed/Expired** = delivery unconfirmed after retry/lifetime limits. Public broadcasts do not promise per-recipient delivery.

## Networking and security

`MessageRouter` is independent of Android/Bluetooth. Direct BLE and reliable recently learned routes are preferred. Unknown routes use bounded BLE flooding, with an optional WSS bridge for eligible packets. Public room messages and gossip remain on BLE. Only opted-in internet traffic reaches configured relays.

All routable packets are signed and bind the source to its ECDSA + Noise public keys. Private content is encrypted with the established Noise protocol implementation `org.signal.forks:noise-java:0.1.1`. Online endpoints negotiate **Noise XX** sessions. Persistent offline envelopes use standardized **Noise X** encryption; X envelopes do **not** provide recipient forward secrecy. Live wrapping is conditional and the durable retry may fall back to X; the app therefore displays “Encrypted” and never promises universal forward secrecy. This distinction and metadata exposure are covered in [the threat model](docs/threat-model.md).

Android Keystore protects the signing key and local AES-GCM key. The Noise static key is wrapped by that AES key. Local message bodies are encrypted before Room insertion; the UI observes the latest 1,000 messages while retaining up to 10,000 local records; relay/courier/outbox queues contain signed ciphertext envelopes. No centralized account, phone number, email or password is required.

## Optional internet relays

Internet bridging is disabled initially. No default third-party endpoint is configured. Two distant users must share at least one reachable relay, or participate in a connected bridge path. Merely having internet does not create a route.

Run an independent relay using [relay instructions](docs/internet-relay.md). Configure its **wss://** address in Settings and opt in. You can configure up to three independently operated relays. The included relay forwards opaque signed packets, maintains no topology directory and stores no message history. It is not required for BLE.

## Project map

```text
app/src/main/java/io/github/goraidebjyoti/dgchat/
  ui/                 Compose screens, view model, semantic Material 3 themes
  crypto/             Keystore vault, identity, Noise X envelopes and XX sessions
  data/               Room entities/DAO, encrypted message content, preferences
  network/ble/        Discovery, advertising, GATT queues, reconnection and framing
  network/internet/   Optional multiple-WSS-relay adapter
  services/           Foreground lifecycle, messaging, retries, courier and gossip
core/src/main/java/.../core/
                      Signed packet codec, router, TTL/dedup, topology, fragmentation
core/src/test/        Hardware-independent fault injection and JUnit entrypoint
app/src/test/         Noise and content tests
app/src/androidTest/ Room, vault and theme instrumentation
relay/                Independent optional Python relay and bounded-resource tests
.github/workflows/   Debug/release APK build and optional emulator instrumentation
```

The core intentionally uses Java 17 so it can be compiled and exercised independently in a JDK-only environment. The Android application and orchestration use Kotlin. Manual dependency composition in `DgChatApp` provides a clear composition root without an additional DI framework.

## Documentation

- [Architecture](docs/architecture.md)
- [Packet format](docs/packet-format.md)
- [Threat model](docs/threat-model.md)
- [Device validation and test matrix](docs/device-testing.md)
- [Verification results](docs/verification.md)
- [Implementation coverage](docs/coverage.md)
- [Internet relay operation](docs/internet-relay.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

No explicit open-source license was selected on the author's behalf. See [LICENSE](LICENSE).
