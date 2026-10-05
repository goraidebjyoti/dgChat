# Threat model and security limits

## Protected assets

Private message/media content, private identity keys, local message history, user consent to networking, authenticity of peer identities, receipt integrity and availability within bounded device resources.

## Adversaries

An eavesdropper on BLE or an internet relay; an untrusted intermediary/courier; a malicious participant generating valid identities; a party replaying or altering packets; a compromised internet relay; a person who gains access to a device or copies its app database; topology churn and accidental radio failures.

A rooted/compromised endpoint, malicious OS, coerced user, side-channel attacker or compromised cryptographic dependency is outside the confidentiality guarantee. This project has not undergone independent cryptographic or penetration review.

## Security design

| Concern | Implementation | Practical limit |
|---|---|---|
| Packet authenticity | SHA256withECDSA signatures; peer ID hashes signing + Noise public keys | Self-authenticating IDs establish key ownership, not real-world identity |
| Trust | First known key pin; explicit full fingerprint comparison; QR import never auto-verifies | First contact can be impersonated under a different ID until compared in person |
| Offline confidentiality | Standard Noise X with X25519/AES-GCM/SHA-256; sender static key checked against signed identity; bound prologue | Recipient static key compromise permits decryption of captured durable X envelopes; no recipient forward secrecy |
| Live confidentiality | Standard Noise XX, remote static key pin, memory-only session keys, sequence checks, 10-minute / 4,096-message session lifetime | Durable retry falls back to X. This is not an audited double ratchet and does not provide universal forward secrecy |
| Local confidentiality | Keystore AES-256-GCM wraps Noise private key and stored message bodies, per-row AAD | Room metadata is visible; Keystore protection varies by device hardware |
| Replay | Signed ID/expiry, in-memory forwarding dedup, durable private receive ledger until packet expiry | Ledger capacity refuses new messages rather than evicting still-valid private replay IDs; public history is bounded |
| Receipt spoofing | Receipt signature verified; expected source must equal the original recipient | A malicious recipient can acknowledge/read without actually showing a message; transport acceptance alone is never delivery |
| Key loss | Existing protected identity fails closed; backups disabled | Clearing app data destroys the identity/history. Contacts must re-verify a replacement identity |
| Quick Clear | Local history/queue/cache removal, transport pause, generation gate and persisted recovery/cutoff | Retained identity keys; no cryptographic shredding, remote retraction or forensic erase guarantee; see `quick-clear.md` |
| Relay opacity | Only original signed X envelopes in outbox/courier queues | Header IDs, destinations, sizes and timings remain observable |
| Flood/resource control | TTL, fixed fan-out, jitter, dedup, transfer bounds, rate buckets, queue/route/peer limits | Signed new Sybil identities and malicious TTL reset can still cause denial of service |
| Courier copies | Origin allocates at most three distinct custodians; custodians don't spray further | Cannot stop a malicious custodian copying ciphertext or refusing delivery |
| Optional internet | Disabled by default; only configured WSS URLs; platform TLS verification; no default backend | Relays see IP addresses and packet metadata. WSS isn't a decentralized route discovery service |
| Public chat | Signed public payloads; BLE-only cache and gossip | Public text and profiles are intentionally readable by mesh participants |

## Deliberate decisions

No cryptographic primitives are implemented by dgChat. Noise protocol processing is delegated to the established Noise-Java library (Signal fork); platform JCA/Keystore handles signing and local AES-GCM. Library pedigree and primitive selection do not constitute an independent review of this application's integration.

The offline and live channels are distinct. Reliable persistence requires an envelope decryptable after process restart and after sessions expire. Keeping only ephemeral XX keys would make queued deliveries unrecoverable. The implementation preserves X ciphertext in the outbox and optionally wraps it in an XX session for the initial live send. It does not claim the durable fallback has the forward-secrecy properties of the interactive session. A future audited asynchronous prekey/ratchet system could improve this; no homemade ratchet is included.

All private message plaintext is transient during encryption/decryption/rendering. Encrypted message bodies are stored locally. Audio capture and playback necessarily create temporary app-private plaintext media files; they are deleted after use and on next launch. This is disclosed explicitly rather than claiming zero plaintext persistence. Exporting a file through Android's document picker writes plaintext to the destination the user chooses.

Packets require signed timestamps within a two-minute future skew and an expiry ≤24 hours from creation. Incorrect device clocks can prevent communication. No internet time service is required; users should align device clocks for testing.

## Required hardening before production

1. Compile and pass Android lint, Noise tests, Room/Keystore instrumentation and UI tests in CI.
2. Validate BLE on several manufacturers and Android versions; test permissions, revocation, Bluetooth off/on and foreground service restrictions.
3. Commission independent review of Noise identity binding, handshake replacement/replay, local storage and receipt transactions.
4. Add audited asynchronous forward secrecy if the product requires it for stored offline deliveries.
5. Perform battery/thermal testing, larger malformed-input campaigns and adversarial Sybil/load testing.
6. Review pinned/transitive dependencies and produce dependency locks/checksum verification and an SBOM.
7. Test key loss/reset, deletion and migration policy before promising long-term recoverability.

## Primary references

- Noise specification: https://noiseprotocol.org/noise.html
- Noise-Java upstream: https://github.com/rweather/noise-java
- Signal fork: https://github.com/signalapp/noise-java
- Android Keystore: https://developer.android.com/privacy-and-security/keystore
- Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Android BLE background guidance: https://developer.android.com/develop/connectivity/bluetooth/ble/background
