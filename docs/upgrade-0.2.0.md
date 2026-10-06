# dgChat 0.2.0: upgrade and features

## Upgrade every participating device

Peer addresses now use all 256 bits of SHA-256 over the two public identity keys: 32 bytes / 64 hexadecimal characters. Packet, transmission, session, group nonce and transfer nonce IDs remain independently random 128-bit values. This changes address length, not the underlying P-256/X25519 algorithms.

Wire protocol v2 deliberately rejects v1 packets. Update phones and any WSS relay together. BLE/Wi-Fi discovery and public/private communication with an old app will not work. Version-1 public identity QR codes remain importable, deriving the new ID from the same keys.

Room schema 1→2 retains identity keys, profile, settings, peer verification and encrypted local chats. It maps peer/conversation/source/destination/outbox references to full IDs and resets stale connectivity. Recoverable unexpired locally queued messages are re-encrypted for v2; an unrecoverable/expired old queued item is marked failed. Original local message IDs/body encryption labels stay unchanged. Opaque foreign signed courier envelopes and the old public gossip cache are dropped because their senders' signatures cannot be rewritten. Do not uninstall or clear Android app data merely to upgrade.

## Using the additions

| Feature | Where / behavior |
|---|---|
| Message notifications | Settings → Privacy and alerts. Android notification permission/channel settings apply; alerts never include sender or message content. |
| Camera QR scanner | Peers / Nearby → Import peer identity → Scan with camera. Frames are decoded locally. Image/paste import also remains. |
| Requests / block / mute | Requests appear on Home and Chats. Accept to read an unknown sender; Block and delete rejects its history. Open a peer's identity dialog to mute/block/unblock. |
| Pending deliveries | Home / Chats → Pending deliveries. See packet purpose, route-wait/receipt-wait reason, attempts and expiry; retry one message or all pending packets. Cancellation is allowed only before any transport acceptance. |
| Search / selective deletion | Toolbar search works across local chats or within the open conversation. Trash in a conversation deletes its messages, pending deliveries and chunk files locally. |
| Mesh health | Home and peer dialogs show link count, reachable identities, selected transport, next hops and hop count. An active path is still required. |
| App lock | Settings → App lock. Authenticate with strong biometric or device PIN/pattern/password. Returning from the background, scanner or file picker requires unlocking before actions continue. Mesh service can continue while locked. |
| Private groups | Chats → Private groups → New group. Select accepted saved peers. Invitees join explicitly. Messages use separate encrypted copies for members. Owner can close the group; members can leave. |
| Resumable files | Open a private peer conversation → Attach → File. Recipient accepts, then encrypted chunks transfer; controls show progress/pause/resume/cancel/save. |

## Limits and recovery

Groups contain at most 12 members including the owner, with at most 128 saved groups. Roster changes propagate asynchronously to offline members. Pending group messages are stopped when a local roster changes; already accepted remote copies cannot be retracted. The current UI supports creation, invitations, joining, leaving and closing; use a new group for a different roster. Group inline attachments and voice/image content stay at most 12 KB; resumable files are private one-to-one transfers.

Files are at most 4 MiB each, 8 KiB per chunk, with a three-chunk in-flight window per file. At most eight retained transfers and 32 MiB of declared aggregate size are stored. Delete older attachment conversations to free their encrypted chunks and transfer rows. Completed transfers count toward that limit. Wi-Fi is usually preferable for larger files; radio/device throughput has not been benchmarked. A resumed sender re-offers/probes; an already accepted receiver reports its saved bitmap. A recipient that cleared/deleted its transfer must accept the renewed offer again. A local pause preserves that user's decision until resumed; remote pauses are shown separately. Expired chunk/control retries pause the transfer for a fresh manual resume. Missing/corrupt stored chunks can prevent recovery; choose the original file again as a new transfer.

Selective deletion keeps the bounded receive-ID ledger until expiry so old ACK/retries cannot restore deleted content. Quick Clear deletes all local chats, requests, pending envelopes, file transfer rows/chunks and temporary media, while keeping identity, profile, settings, saved peers/controls and group memberships. Exported files and remote copies remain. Rejoin the mesh manually after Quick Clear succeeds.

## Validation status

Core checks and relay tests ran; Android compilation and device tests did not. Gradle bootstrap cannot resolve its distribution host in the authoring environment. New Android tests are supplied for Room migration, group enforcement and file recovery. Run the GitHub workflow and device checks in `verification.md` / `device-testing.md` before releasing an APK.
