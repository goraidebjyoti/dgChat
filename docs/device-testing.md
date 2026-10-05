# Device validation matrix

These are **required physical-device checks**, not a report that they have passed. Emulators cannot validate real BLE advertising, GATT behavior, mesh range, battery cost or manufacturer background policies.

Use at least four Android 12+ devices, preferably different manufacturers. Install the same debug APK. Start each mesh, align clocks, record diagnostics and compare recipient message IDs and states. For multi-hop tests, physically separate endpoints or shield radios so the intended links are measurable; placing all devices on one desk does not demonstrate multi-hop.

| Area | Scenario | Expected evidence |
|---|---|---|
| First launch | No internet, no SIM; Bluetooth enabled | Local name setup, generated fingerprint, no remote account calls |
| Permission | Deny Nearby devices, then grant via Android Settings | Clear status, no crash; mesh can subsequently start |
| Revocation | Revoke Bluetooth permission while networking | Controlled shutdown/status; no unhandled SecurityException |
| Bluetooth | Disabled on launch; turn on after starting; turn off mid-chat | Clear state; no data loss; reconnect and outbox recovery |
| Radio capability | Device without peripheral advertising or adequate MTU | Explicit unavailable status; no truncated frames |
| Direct | A↔B one-to-one encrypted text | Same ID/body at B; A moves Sent→Delivered after recipient ACK |
| Two hops | A↔B↔C, A cannot see C | C receives; hop status ≥2; B relays only ciphertext |
| Three hops | A↔B↔C↔D | D receives through the chain; relays bounded |
| TTL | Longer chain than configured budget | Packet stops; no infinite forwarding; delivery remains unconfirmed |
| Topology | Disconnect B/C, restore later | Route expires/invalidates; fresh signed transmission of same logical ID recovers |
| Duplicate | Repeat identical wire packet and later retry | One local message; no repeated relay of the same transmission; recipient can re-ACK |
| Persistence | Force-stop/restart sender and recipient | Same identity and protected rows; outbox resumes only after mesh is explicitly restarted |
| Courier | Sender A sees custodian B; C offline; A leaves; B later meets C | Only opaque envelope at B; C decrypts without A present; custody expires/bounds hold |
| Courier copies | Origin encounters >3 custodians | At most 3 distinct allocations persist across restarts; custodians never re-spray |
| Receipts | Lose initial ACK; open recipient conversation | Retry receives fresh ACK/read receipt; Read is never demoted |
| Public rooms | Two rooms, new mesh participant | Signed room messages remain separate; bounded missing-ID cache sync |
| Internet | Distant peers on common configured WSS relay | Private delivery and authenticated ACK; no public-room text sent to relay |
| Switching | Drop WSS while a BLE route appears, and reverse | Outbox retained; automatic delivery through available transport; no false Delivered state |
| No shared route | Internet present but different disconnected relays | Queued/unconfirmed; app does not fabricate reachability |
| Media | JPEG, 12 KB file, short voice clip | Decrypt, save/play; SHA/AEAD integrity; oversized payload rejected |
| Fragment loss | Disconnect half way through media | Incomplete transfer expires; whole private packet retry can recover |
| Background | Screen off, restricted battery mode, foreground Stop action | Notification ownership, bounded radio activity, service resource release |
| Themes | Light, Dark, System; rotate; large fonts; soft keyboard | All screens readable and usable; dialogs and composer fit |
| Verification | QR photo import, malformed code, compare full fingerprint | No automatic verification on import; correct pin; malformed input fails safely |
| Quick Clear | Queue private/public/media messages and courier data, open a draft/play voice, clear from top bar | Chats/outbox/couriers/cache empty; playback/recording and foreground notification stop; same identity/theme and verified peers; Home completion notice |
| Clear interruption | Force-stop during clear; restart; inject disk/cleanup failure | Recovery resumes or Retry is shown; networking remains blocked until completion; no partial history exposed |
| Clear replay/race | Clear during retries/gossip/attachment callback; rejoin and replay pre-clear signed packets | No stale send/relay or ordinary old history restored; newly composed message works; clock skew behavior documented |
| Clear controls | Cancel, confirm, rotate, Light/Dark/System and large font | Cancel has no effect; clear requires confirmation; drafts and media dialogs do not reappear; accessible clear icon |
| Key loss/reset | Test-only installation: remove keys or clear app data | Existing encrypted identity fails closed; clear-data reset produces a new identity requiring re-verification |
| Battery | 1–4 peers, Performance/Balanced/Battery Saver, 2-hour runs | Measure current/temperature/packet count; no unsupported battery claims |
| Security | Wrong static key, bad signature/AEAD, replay, malformed length | Invalid packet counter; no UI delivery, no unbounded memory or process crash |

## Emulator/JVM checks

The build workflow compiles Android instrumentation tests. **Actions → Android storage and theme tests → Run workflow** runs Quick Clear confirmation, database deletion/peer preservation and recovery-marker checks, Room duplicate/receipt tests, local vault persistence/tamper tests and theme-switch/contrast checks on an API 35 emulator. This does not replace the table above.

JVM `NoiseSecurityTest` exercises standardized X envelope authentication/context/wrong-recipient corruption and XX handshake/replay behavior. Core `CoreChecks` simulates routes, partitions, TTL, duplicate forwarding, fresh-transmission retry, bridge preference/public withholding, signed packet corruption, fragmentation and bounds. Python tests exercise independent relay limits and real local WebSocket forwarding.
