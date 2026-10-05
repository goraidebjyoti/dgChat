# Verification report

Delivery prepared 2026-10-05 (Asia/Kolkata). This report records observed results; it does not infer a successful Android build from source inspection.

## Executed and passing

| Check | Observed result |
|---|---|
| Java 17 core compilation | Passed using installed JDK compiler module |
| Core fault injection | **71 assertions passed**, plus **2,000 malformed packet inputs** |
| Routing cases | Direct / two-hop / three-hop; TTL; duplicate suppression; failed route invalidation; partition and same-logical-message recovery using a fresh signed transmission ID |
| Bridge policy | Unknown BLE route also sends an eligible internet copy; direct BLE preferred; public room withheld from internet |
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
- Quick Clear confirmation/cancel, on-disk deletion/reopen and peer preservation, recovery-marker instrumentation, actual transport/service shutdown and force-stop recovery on Android.
- Real Android BLE advertising, GATT negotiation, radio multi-hop and background/permission behavior.
- Public TLS relay deployment and Android transport switching.
- Battery/thermal/long-run behavior and independent security audit.

The authoring environment has Java but no Android SDK, no Gradle installation and restricted external dependency-download access. The project supplies a clean-checkout GitHub Actions workflow to perform Android compilation, lint, tests and APK generation. That workflow **has not been launched from a GitHub repository in this task**. No build run, artifact URL or Android radio test result is fabricated.

## Known scope limits

Read `coverage.md` and the threat model. In particular: small bounded media, whole-packet retries, first-contact identity verification, conditional live forward secrecy with durable X fallback, metadata exposure, user-configured shared relay reachability and required physical-device testing.

This delivery is an **engineering implementation awaiting Android/device validation**, not an independently audited production messenger.
