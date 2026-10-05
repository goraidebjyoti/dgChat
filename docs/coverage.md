# Implementation coverage

“Implemented” means source is supplied, not that physical-device or Android build validation has passed. See `verification.md`.

| Specification area | Supplied implementation | Limit / further work |
|---|---|---|
| Native Android Kotlin / Compose | Activity, MVVM view model, Material 3 UI, manual composition root | Core is intentionally Java 17; no Hilt dependency |
| Identity / Keystore | P-256 signing key; wrapped X25519 key; public ID, full fingerprint, fail-closed key handling | Explicit identity reset uses Android clear-app-data; no key backup |
| Private encryption | Established Noise X envelopes and XX live session wrapper | Durable offline fallback has no recipient forward secrecy; independent crypto review required |
| BLE | Dual advertiser/scanner, peripheral/central GATT, canonical initiator, acknowledged operation queues | MTU ≥80 and advertiser support required; manufacturer/device testing pending |
| Discovery / health | Signed periodic HELLO, RSSI threshold, recent peers, idle link timeout, reconnect backoff | No ranging/location claims; RSSI approximate |
| Mesh routing | Direct, learned next hop, bounded flood, TTL, jitter, transmission dedup and route expiry | No full source-route protocol or DHT |
| Local persistence | Room encrypted message bodies, transactionally saved outbox, durable private receive IDs | Metadata not encrypted; bounded 10,000-row local history; latest 1,000 rows observed in UI |
| Quick Clear | Confirmation, transport pause, transactional history deletion, UI/media reset, retained identity/peers, durable recovery marker and cutoff | Local deletion only; exported/remote copies unaffected; no forensic secure-erasure guarantee; Android/device checks pending |
| Store-and-forward | Bounded retries/lifetime, encrypted outbox, receipts | Whole-packet retries; no selective missing-fragment retransmission |
| Couriers | Opt-in opaque handoff, 3 persisted allocations, 2 MB / 256 envelopes, ≤1-hour custody | Origin→custodian→recipient only; no recursive spray; malicious custody copying cannot be prevented |
| Public rooms / gossip | Room tags, signed broadcasts, missing-ID exchange, bounded cached packet sharing | BLE only; no delivery guarantee or complete historical sync |
| Internet | Opt-in multiple configurable WSS links, automatic router selection, reconnect backoff, independent relay | Common reachable relay/bridge path required; no automatic worldwide discovery or relay federation directory |
| Media | Text, compressed images, ≤12 KB files, short recorded voice, document export/playback | Intentionally small; no resumable large-file protocol |
| Fragments | Bounded split/reassemble, out-of-order, duplicate/conflict, timeout and SHA-256 integrity | GATT flow control plus whole-message retry; no NACK bitmap protocol |
| Themes | Central semantic Light/Dark/System palette, persisted preference, all Compose screens | Tests supplied; Android theme/layout execution pending |
| Profiles / verification | Display name, public bio, optional encrypted local avatar, identity QR and QR image import, explicit verification | Avatar is local only; no in-app live camera QR scanner |
| Battery | Scanning duty cycles, thresholds, reconnect backoff, bounded relay rate and slower saver transmissions | No measured battery claims; no full connected-link duty-cycling protocol |
| Diagnostics | Opt-in screen: peers/IDs/RSSI, route/hops, counts, duplicate/invalid/failure, outbox/courier | No packet plaintext logging; no production logging backend |
| Lifecycle | Foreground service, runtime permission gate, Bluetooth state receiver, stop/restart cleanup | Android background limits still apply; no silent boot autostart |
| CI/APK | Debug tests/lint/APK, release secrets/signing/APK, artifact/report upload and summary | Supplied workflow not run in this delivery; no built APK supplied |
| Test/security hardening | Executed JVM fault tests and relay tests; supplied Noise/Room/vault/Compose tests; threat model and device matrix | Production readiness requires remaining builds, hardware tests, adversarial load and independent audit |

The architecture implements the phases together at an engineering validation level. Phases 6–7 are not certified complete solely because tests/workflows exist. The README clearly separates supplied code from executed validation.
