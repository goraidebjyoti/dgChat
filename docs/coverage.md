# Implementation coverage

“Implemented” means source is supplied, not that physical-device or Android build validation has passed. See `verification.md`.

| Specification area | Supplied implementation | Limit / further work |
|---|---|---|
| Native Android Kotlin / Compose | Activity, MVVM view model, Material 3 UI, manual composition root | Core is intentionally Java 17; no Hilt dependency |
| Identity / Keystore | P-256 signing key; wrapped X25519 key; 256-bit public ID, full fingerprint, key-preserving v1→v2 migration, fail-closed key handling | Explicit identity reset uses Android clear-app-data; no key backup |
| Private encryption | Established Noise X envelopes and XX live session wrapper | Durable offline fallback has no recipient forward secrecy; independent crypto review required |
| BLE | Dual advertiser/scanner, peripheral/central GATT, canonical initiator, acknowledged operation queues | MTU ≥80 and advertiser support required; manufacturer/device testing pending |
| Wi-Fi LAN | Same-LAN/hotspot NSD, framed TCP, signed peer identity, bounded links/queues | AP isolation/multicast filtering can block discovery; no Wi-Fi Direct or automatic hotspot creation; device tests pending |
| Discovery / health | Signed periodic HELLO, RSSI threshold, recent peers, idle link timeout, reconnect backoff | No ranging/location claims; RSSI approximate |
| Mesh routing | Direct, learned next hop, bounded flood, TTL, jitter, transmission dedup and route expiry | No full source-route protocol or DHT |
| Local persistence | Room encrypted message bodies, transactionally saved outbox, durable private receive IDs | Metadata not encrypted; bounded 10,000-row local history; latest 1,000 rows observed in UI |
| Quick Clear | Confirmation, transport pause, transactional history deletion, UI/media reset, retained identity/peers, durable recovery marker and cutoff | Local deletion only; exported/remote copies unaffected; no forensic secure-erasure guarantee; Android/device checks pending |
| Store-and-forward | Bounded retries/lifetime, encrypted outbox, receipts, retry-now, expiry/reason display and cancel-before-transmission | Whole-packet retries; no selective missing-fragment retransmission |
| Couriers | Opt-in opaque handoff, 3 persisted allocations, 2 MB / 256 envelopes, ≤1-hour custody | Origin→custodian→recipient only; no recursive spray; malicious custody copying cannot be prevented |
| Public rooms / gossip | Room tags, signed broadcasts, missing-ID exchange, bounded cached packet sharing | BLE and Wi-Fi LAN only; no delivery guarantee or complete historical sync |
| Internet | Opt-in multiple configurable WSS links, automatic router selection, reconnect backoff, network handover, setup timeout, independent relay | Common reachable relay/bridge path required; no automatic worldwide discovery or relay federation directory |
| Media | Text, compressed images, ≤12 KB files, short recorded voice, document export/playback | Intentionally small; disk-backed resumable private files up to 4 MiB; group inline files remain ≤12 KB |
| Fragments | Bounded split/reassemble, out-of-order, duplicate/conflict, timeout and SHA-256 integrity | GATT flow control plus whole-message retry; no NACK bitmap protocol |
| Themes | Seven semantic colour palettes × Light/Dark/System mode, persisted preference, all Compose screens | Tests supplied; Android theme/layout execution pending |
| Profiles / verification | Display name, public bio, optional encrypted local avatar, identity QR, live camera scanner and QR image import, explicit verification | Avatar is local only; camera permission and hardware validation pending |
| Battery | Scanning duty cycles, thresholds, reconnect backoff, bounded relay rate and slower saver transmissions | No measured battery claims; no full connected-link duty-cycling protocol |
| Diagnostics | Opt-in screen: peers/IDs/RSSI, route/hops, counts, duplicate/invalid/failure, outbox/courier | No packet plaintext logging; no production logging backend |
| Lifecycle | Foreground service, BLE permission handling independent of Wi-Fi, Bluetooth/network state callbacks, stop/restart cleanup | Android background limits still apply; no silent boot autostart |
| CI/APK | Debug tests/lint/APK, release secrets/signing/APK, artifact/report upload and summary | Supplied workflow not run in this delivery; no built APK supplied |
| Requests / peer controls | Unknown-private-sender inbox, explicit acceptance, block-and-delete, peer mute/block | Blocking cannot erase remote copies or prevent a new identity from sending a request |
| Local search / deletion | Up to 100 matches over 10,000 local messages; per-conversation deletion/cutoff, pending/chunk cleanup | Local-only deletion; replay ledger kept until expiry; no global plaintext search index |
| App lock / notifications | Biometric or Android device credential, return-to-app lock, screenshot protection; generic permission-aware message alerts | Locks UI access; background mesh still operates; OS authentication/device validation pending |
| Private groups | Owner-approved roster, invite/join/leave/close, per-recipient Noise encryption and delivery aggregation | At most 12 members / 128 saved groups; asynchronous membership updates; no MLS/group forward-secrecy claim |
| Resumable files | Accept-before-transfer, encrypted disk chunks, persistent bitmap, pause/resume/cancel/export and digest validation | 4 MiB per private file; eight retained transfers / 32 MiB aggregate; at most three in-flight chunks per file |
| Test/security hardening | Executed JVM fault tests and relay tests; supplied Noise/Room/vault/Compose tests; threat model and device matrix | Production readiness requires remaining builds, hardware tests, adversarial load and independent audit |

The architecture implements the phases together at an engineering validation level. Phases 6–7 are not certified complete solely because tests/workflows exist. The README clearly separates supplied code from executed validation.
