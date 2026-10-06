# Quick Clear

The user-facing name is **Quick Clear**. Available from the top-bar clear-history icon in every main screen, and **Settings → Quick Clear → Open Quick Clear**. The confirmation explains irreversible local deletion; **Cancel** changes nothing and **Clear now** starts the operation.

## Result

- Pauses BLE/Wi-Fi/internet traffic, scanning, advertising, session handshakes, retries and courier forwarding. Foreground service is stopped.
- Deletes all messages (including media), outbox entries, carried courier envelopes, public gossip cache and private receipt/replay records, logical delivery rows and file transfer records in a single Room transaction. Encrypted chunk directories are also removed.
- Stops recording/playback, discards the draft/composer and attachment/export callbacks, deletes app-private recording/playback files and resets routes, router dedup/counters, gossip state and partial fragment assemblies.
- Retains signing/Noise/vault keys, fingerprint, name/bio/avatar, settings, saved peers, block/mute/verification flags and private group memberships. Saved peers become disconnected.
- Returns to Home with a completion notice. Networking stays paused until **Join nearby mesh** is pressed.

## Interruption and races

A synchronous generation gate immediately blocks new operations. Messaging database operations share a mutex, so pre-clear transactions finish before deletion. Queued receive/delivery events and delayed relays carry a generation token; a token from before deletion remains invalid even if networking is resumed. UI sends capture the token before being scheduled. The UI tree and saved drafts are keyed by the persisted history cutoff.

A synchronous SharedPreferences commit records an incomplete-clear marker and cutoff before history is changed. A required deletion failure keeps messaging and networking blocked and offers **Retry Quick Clear**. The recovery screen has toolbar/hardware back navigation and **Back to Home**. Home retains a retry panel and keeps Join disabled until deletion finishes. The application retries an incomplete clear on its next process start. The recovery marker is removed only after transactional history deletion and temporary-media deletion finish. Optional secure-delete/WAL compaction/VACUUM failure shows a completion notice and does not keep the app locked after successful required deletion. Cancellation of the Activity/ViewModel does not cancel the application-owned cleanup job. The clear itself does not rotate identity or change schema.

The persisted timestamp cutoff rejects signed public/private packets and inner courier packets created at or before the clear, helping prevent ordinary cached gossip and durable retries from restoring old history after rejoining. This is a local timestamp policy: incorrect peer clocks can reject otherwise new messages, and a sender can create a newly signed message containing old content. Live-session packets are accepted only with a current valid session; old session keys are discarded. Quick Clear cannot prevent another participant from resending content as a new message.

## Deletion limits

Quick Clear deletes this installation's history. Copies at recipients/couriers, screenshots, clipboard contents, Android/system snapshots, backups made outside the app, or attachments previously exported through the document picker remain outside its control. Networking cannot retract packets already accepted by a remote device.

SQLite secure-delete is set using the result-returning query API; WAL truncation and VACUUM are requested to reduce local database remnants. Keys are retained to preserve identity, so this is **not cryptographic shredding or a guarantee against forensic recovery from flash, process memory, OS caches or a compromised device**. The app must not advertise forensic secure erasure.

## Validation status

Executed Java checks cover stale asynchronous work after resume, blocking during an incomplete clear, timestamp replay rejection, persisted-cutoff restoration, clock rollback, route reset and fragment reset. Android instrumentation is supplied for confirmation/cancel behavior, on-disk history deletion after database reopen, verified-peer preservation and recovery-marker persistence. Android compilation, instrumentation, actual Bluetooth shutdown and interruption/device tests remain pending.
