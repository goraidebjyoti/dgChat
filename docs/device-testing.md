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
| Wi-Fi LAN | BLE off; both devices on same Wi-Fi/hotspot, internet unavailable | NSD finds signed peer, private delivery/ACK, public room and identity verification preserved |
| Wi-Fi isolation | Router client isolation/multicast blocking; enable/disable Nearby Wi-Fi | Honest searching/unavailable state; BLE/relay still usable; sockets/NSD release on disable |
| Mixed hops | A–BLE–B–Wi-Fi–C–WSS–D with no endpoint shortcut | One private delivery, signed ACK, bounded TTL; public room does not reach D |
| Switching | BLE→Wi-Fi→WSS→BLE, switch Wi-Fi to mobile data, change hotspot/SSID | Outbox retained; automatic delivery through available transport; no false Delivered state |
| No shared route | Internet present but different disconnected relays | Queued/unconfirmed; app does not fabricate reachability |
| Media | JPEG, 12 KB file, short voice clip | Decrypt, save/play; SHA/AEAD integrity; oversized payload rejected |
| Fragment loss | Disconnect half way through media | Incomplete transfer expires; whole private packet retry can recover |
| Background | Screen off, restricted battery mode, foreground Stop action | Notification ownership, bounded radio activity, service resource release |
| Themes | Seven colours × Light/Dark/System; restart and upgrade saved preferences; rotate; large fonts; soft keyboard | All screens readable and usable; dialogs and composer fit |
| Verification | QR photo import, malformed code, compare full fingerprint | No automatic verification on import; correct pin; malformed input fails safely |
| Quick Clear | Queue private/public/media messages and courier data, open a draft/play voice, clear from top bar | Chats/outbox/couriers/cache empty; playback/recording and foreground notification stop; same identity/theme and verified peers; Home completion notice |
| Clear interruption | Force-stop during clear; restart; inject disk/cleanup failure | Recovery resumes or Retry is shown; networking remains blocked until completion; no partial history exposed |
| Clear replay/race | Clear during retries/gossip/attachment callback; rejoin and replay pre-clear signed packets | No stale send/relay or ordinary old history restored; newly composed message works; clock skew behavior documented |
| Clear controls | Cancel, confirm, rotate, recovery-page toolbar/hardware Back, Back to Home, Light/Dark/System and large font | Cancel has no effect; clear requires confirmation; drafts and media dialogs do not reappear; accessible clear icon; Home keeps retry visible and Join blocked on required deletion failure |
| Identity distribution | Install the same APK on two fresh devices; restart/update/Quick Clear on each | Different IDs and fingerprints on fresh devices; keys/fingerprints survive update and Quick Clear; v1 addresses expand once to 256 bits |
| Launcher logo | Android 12/13/15; round/squircle masks; themed launcher icons; foreground notification | Circular artwork remains legible without clipping; monochrome icon and white notification glyph render correctly |
| Key loss/reset | Test-only installation: remove keys or clear app data | Existing encrypted identity fails closed; clear-data reset produces a new identity requiring re-verification |
| Battery | 1–4 peers, Performance/Balanced/Battery Saver, 2-hour runs | Measure current/temperature/packet count; no unsupported battery claims |
| Security | Wrong static key, bad signature/AEAD, replay, malformed length | Invalid packet counter; no UI delivery, no unbounded memory or process crash |

## Emulator/JVM checks

The build workflow compiles Android instrumentation tests. **Actions → Android storage and theme tests → Run workflow** runs Quick Clear confirmation, database deletion/peer preservation and recovery-marker checks, Room duplicate/receipt tests, local vault persistence/tamper tests theme-switch/contrast, schema-v1 migration, group membership and file-recovery checks on an API 35 emulator. This does not replace the table above.

JVM `NoiseSecurityTest` exercises standardized X envelope authentication/context/wrong-recipient corruption and XX handshake/replay behavior. Core `CoreChecks` simulates routes, partitions, TTL, duplicate forwarding, fresh-transmission retry, bridge preference/public withholding, signed packet corruption, fragmentation and bounds. Python tests exercise independent relay limits and real local WebSocket forwarding.


## 0.2.0 upgrade and feature cases

| Area | Scenario | Expected evidence |
|---|---|---|
| Migration | Upgrade an installed 0.1.3 with verified peers, encrypted chats and queued messages | Same keys/fingerprint; IDs expand to 64 hex characters; old rows/references map correctly; recoverable queues re-encrypt; courier/public cache resets |
| Compatibility | New→old app and old relay, then update all peers/relay | Explicit v1 rejection; connectivity returns with v2; old public QR imports into new ID |
| Scanner | Camera grant/deny/revoke; rotated QR, unrelated/malformed QR, return/cancel | Valid public identity import, no automatic verification; no frame upload or crash |
| Notifications | Private/group/public/request while foreground/background/locked; deny permission, mute peer | Generic alert with no sender/body; OS permission respected; muted sender does not alert |
| Requests | New unknown sender, accept/block-and-delete; group/file request; restart | Ordinary chats stay hidden until acceptance; block rejects subsequent packets; controls/requests persist |
| Queue | No shared route, path recovery, retry-now, expiry; cancel before/after transport acceptance | Accurate pending/receipt states; attempts/lifetime stay bounded; cancellation never claims to retract accepted data |
| Search/deletion | Search text and filenames, delete private/public/group history, replay old packet | Matching local content only; messages/pending/chunks removed; old receipts do not restore content; group membership kept |
| App lock | Enable/disable using biometrics/PIN, cancel/fail auth; background/rotate/process restart; picker/scanner return | Chats/settings stay gated; FLAG_SECURE protects recents/screenshots; queued picker actions resume only after unlock; background mesh can receive |
| Groups | Owner invites B/C; B joins and C declines; outsider sends; owner closes; offline revisions/reconnect | Explicit join required; per-member envelope delivery; owner/roster/revision checks; leave/close propagates; old membership does not imply timely reachability |
| Files | 1 byte, 8 KiB boundary, 4 MiB, oversized/invalid bitmap; accept/reject/pause/resume/cancel | Size/window/storage bounds enforced; no chunks before acceptance; digest verified before Save |
| File recovery | BLE→Wi-Fi→WSS, drop ACK/complete, force-stop/restart, receiver clear then sender resume | Persistent missing-chunk bitmap; probes repair lost confirmation; renewed offer needs acceptance after deletion; terminal state survives late ACK |
| Clear races | Clear while new group/file/search/delete/retry/import is waiting; clock rollback and repeated clear | No pre-clear work mutates newly created history; chunks/controls disappear; UI resets every time |
