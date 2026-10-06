# Verification report

Delivery prepared 2026-10-06 (Asia/Kolkata). This report records observed results; it does not infer a successful Android build from source inspection.

## Latest CI result and Compose lint correction (2026-10-06)

The user supplied a GitHub Actions run after the Noise version correction: core tests, Android main/test Kotlin and Java compilation, Android unit tests, and debug instrumentation APK assembly completed. `:app:lintDebug` failed with 2 errors and 34 warnings; the excerpt identifies StateFlowValueCalledInComposition in the pending-delivery queue. Physical instrumentation tests were not run by this command.

Corrected direct peer/group StateFlow reads across QueueDialog, SearchDialog and Conversation using collectAsStateWithLifecycle. Peer/request/group labels now observe changes. Corrected three annotated BLE assignment expressions to explicit setter calls and migrated identity copying from LocalClipboardManager to LocalClipboard with a composition coroutine scope. No lint baseline or error suppression was added.

After these edits, local repository static checks and Kotlin syntax parsing of all 31 files passed. The requested Gradle command was attempted but stopped before task execution with UnknownHostException: services.gradle.org. Android compilation, lint and unit tests of this correction require another GitHub run. Historical sections below describe earlier snapshots.

## Executed and passing

| Check | Observed result |
|---|---|
| Java 17 core compilation | Passed using installed JDK compiler module |
| Core fault injection | **125 assertions passed**, plus **2,000 malformed packet inputs** |
| Routing cases | Direct / two-hop / three-hop; TTL; duplicate suppression; failed route invalidation; partition and same-logical-message recovery using a fresh signed transmission ID |
| Bridge policy | Unknown BLE route also sends an eligible internet copy; direct local link preference; BLE/Wi-Fi/internet fallbacks; public room withheld even with learned internet route |
| TCP framing | Coalesced frames, three-byte partial reads, invalid/oversized lengths, truncation and signed packet over a real loopback socket |
| Mixed transport routes | Three-hop BLE→Wi-Fi→internet; TTL; return to BLE with the same identity; transport-specific invalidation; adapter exception fallback |
| Signed packet tests | Round-trip; expiry; forwarding signature validity; signature/ciphertext tamper; recipient change; source-key ID binding; defensive copies; all wire truncations; trailing bytes |
| Fragment tests | Single fragment; shuffled multi-fragment; missing timeout; equal/conflicting duplicates; corruption; allocation/transfer bounds |
| Queue/control policies | Bounded dedup/routes; retry count/expiry/backoff; courier copy quota; relay token bucket |
| Quick Clear core | Stale asynchronous send rejected after resume; clear blocks new work; old timestamps rejected; incomplete-clear restart blocked; cutoff restored; clock rollback cannot restore work; routes/fragment assemblies reset |
| Independent relay tests | **5 tests passed**: frame validation, burst/rate policy, per-IP/global admission and real local WebSocket opaque forwarding / invalid-frame disconnection / cleanup |
| Relay runtime | Local integration executed using the installed `websockets 16.0`; the relay requirement is pinned to the same version |
| Python compilation | Relay modules compile |
| Bootstrap | Independent Java launcher compiled; JAR loads and reports the pinned Gradle version/checksum |
| Repository static checks | XML parses; no Kotlin reserved field names or unimplemented sentinels; no signing-key files; bootstrap/workflow files present |
| Workflow YAML | Parsed and required build/test/signing/artifact steps inspected |
| Packaging | Source archive tested for integrity, required paths and excluded secret/build files |

Actual command logs are available in `docs/verification-log.txt`. Core/relay tests are repeatable using README commands.

## Supplied, not executed here

- Android Gradle build and installable APK generation.
- Kotlin application compilation, Android lint, R8 release shrinking/signing.
- Noise-Java dependency resolution and application-level Noise unit tests.
- Android Room/Keystore instrumentation and Compose theme/contrast instrumentation.
- Quick Clear confirmation/cancel, production HistoryCleaner path including PRAGMA, optional compaction and temp files; on-disk deletion/reopen and peer preservation; recovery-page Back/retry; immediate outbox retry, recovery-marker instrumentation, actual transport/service shutdown and force-stop recovery on Android.
- Real Android BLE advertising, GATT negotiation, radio multi-hop and background/permission behavior.
- Public TLS relay deployment, Android NSD/Wi-Fi/hotspot behavior and actual transport switching.
- Battery/thermal/long-run behavior and independent security audit.

The authoring environment has Java but no Android SDK, no Gradle installation and restricted external dependency-download access. The project supplies a clean-checkout GitHub Actions workflow to perform Android compilation, lint, tests and APK generation. The first user-run GitHub build failed during SDK setup: setup-android@v3 requested the removed `tools` package. Both workflows now explicitly request `platform-tools`. YAML/package-policy checks pass locally. The next user-run GitHub build passed SDK setup, core tests and KSP, then failed at Kotlin compilation on an ambiguous ConcurrentHashMap `in` lookup. That lookup is now an explicit `containsKey` call. The full Android build after this correction remains pending. No successful build, APK artifact or Android radio result is claimed.

## Known scope limits

Read `coverage.md` and the threat model. In particular: small bounded media, whole-packet retries, first-contact identity verification, conditional live forward secrecy with durable X fallback, metadata exposure, user-configured shared relay reachability and required physical-device testing.

This delivery is an **engineering implementation awaiting Android/device validation**, not an independently audited production messenger.

## 0.1.2 connection and clear fixes

The user screenshot shows Quick Clear failing, without the underlying exception. Source inspection found `execSQL("PRAGMA secure_delete=ON")` using the non-result SQL API for a result-returning statement: a likely cause, requiring device confirmation. Cleanup now uses `query`, required deletion is separated from optional compaction, and recovery provides Back/Home while preserving the block on networking until deletion succeeds. Android tests now call production HistoryCleaner instead of only the DAO transaction.

Local attempted command: `./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`. It failed before Gradle initialization with `UnknownHostException: services.gradle.org`. There is no local Android SDK/Gradle installation; these Android checks were not executed. The 95-assertion Java suite, 2,000 malformed packets, five relay tests and repository static checks passed.

## 0.1.3 identity review and appearance

Source review confirms identity keys are generated on first launch and not bundled into the APK. At version 0.1.3 IDs were 128-bit key hashes; version 0.2.0 expands them to the full 256 bits. QR exports only public identity information, and the full fingerprint uses 256 bits. Appearance adds seven saved colour choices independently of Light/Dark/System mode and replaces the app mark with a circular vector/adaptive/round/monochrome icon.

Executed: numerical sRGB contrast checks across the seven light/dark palettes (minimum checked text contrast 6.46:1), new resource XML parsing/reference checks and preview inspection. Supplied Android instrumentation now checks every palette in both modes and the real picker→settings persistence path with older preferences. Android compilation, instrumentation and launcher rendering remain pending because this workspace has no Android SDK/Gradle and cannot resolve the Gradle download host.


## 0.2.0 feature and protocol upgrade (2026-10-06)

Executed locally: core suite **125 assertions plus 2,000 malformed packet inputs**; all **5 relay tests**; XML/static repository checks; supplementary Kotlin syntax parsing of 31 source/test files. New core cases cover full 256-bit addressing and tail-bit binding, explicit v1 rejection, transfer/chunk/bitmap bounds, reconstructed checksums after duplicate/out-of-order chunks, and group outsider/join/close/revision rules. Syntax parsing does not type-check dependencies or replace Gradle/Android lint.

Added, **not executed here**: Room schema-v1 migration with preserved encrypted body/keys and expanded references; group invitation/join/owner enforcement; accepted transfer pause/resume from encrypted saved chunks, checksum/export and late-ACK terminal-state preservation; Noise group recipient isolation; new/legacy content decoding. Existing vault, Quick Clear and theme instrumentation remains supplied.

The requested Gradle command was attempted again. It stopped during bootstrap with `java.net.UnknownHostException: services.gradle.org`; no Kotlin/Android task ran. Android SDK, Android lint, unit/instrumentation compilation, biometric behavior, camera behavior, real BLE/Wi-Fi/WSS switching and APK generation remain unverified here. Run CI, then the device matrix before distributing a release.
