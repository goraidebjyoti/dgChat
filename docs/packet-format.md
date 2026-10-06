# dgChat binary protocol v2

All multibyte fields use **big endian**. This is an independently designed protocol. `Packet.java` is the authoritative codec. Source and destination addresses are 32-byte peer IDs; packet and transmission IDs remain 16-byte values; all-zero destination means broadcast. Random IDs are allocated once and preserved across retries.

| Field | Size | Rule |
|---|---:|---|
| Magic | 2 | `0x4447` (`DG`) |
| Version | 1 | `2` |
| Type | 1 | Numeric enum below |
| Remaining TTL | 1 | 0–initial TTL; forwarding requires >1 |
| Initial TTL | 1 | 1–7 |
| Packet ID | 16 | Cryptographically random envelope ID preserved across retries |
| Transmission ID | 16 | Fresh signed ID for each origin retry; unchanged through relays |
| Source | 32 | Complete SHA-256(signing DER key || Noise public key) |
| Destination | 32 | Peer ID; zero for broadcast |
| Created | 8 | Unix epoch milliseconds |
| Expires | 8 | After creation; no more than 24 hours later |
| Signing-key length | 2 | Maximum 128 |
| Signing public key | variable | X.509 DER ECDSA public key |
| Noise public key | 32 | X25519 static public key |
| Payload length | 4 | 0–18,000 |
| Payload | variable | Type-specific; private payload is opaque ciphertext |
| Signature length | 2 | Maximum 80 |
| Signature | variable | DER ECDSA SHA-256 signature |

All fields through payload are signed, with **remaining TTL canonicalized to initial TTL** when signing/verifying. This permits honest relays to decrement TTL without resigning. TTL is consequently an honest-peer resource limit, not protection against a malicious relay deliberately resetting it. Routing dedup uses transmission IDs; durable private-message dedup uses logical message IDs. This lets a later retry traverse a previously seen route while suppressing loops within each transmission. Source identity must match its key hash; signed expiry/future skew limits replay. The parser rejects unknown versions/types, truncation, excess/trailing bytes and out-of-range lengths before allocation.

Full wire packets are limited to 18,500 bytes. Public keys and signatures add overhead to small text packets, trading bandwidth for independently verifiable forwarded identity announcements. Future versions may use a compact cached-key reference after an authenticated introduction.

## Packet types

| Value | Name | Payload |
|---:|---|---|
| 0 | HELLO | Bounded UTF-8 public presence JSON: name, bio, courier capability, version |
| 1 | PEER_SYNC | Mode byte `0`, followed by at most 128 16-byte cached public IDs |
| 2 | PUBLIC_MESSAGE | Content encoding; `name` identifies the public room |
| 3 | PRIVATE_MESSAGE | Standardized Noise X message, bound to ID/source/destination |
| 4 | ACK | 16-byte private envelope packet ID |
| 5 | READ_RECEIPT | 16-byte private envelope packet ID |
| 6 | HANDSHAKE | 16-byte session ID followed by the next Noise XX handshake message |
| 7 | SESSION_MESSAGE | 16-byte session ID, 8-byte sequence, XX ciphertext wrapping an X envelope |
| 8 | FILE_TRANSFER | Reserved; media currently uses bounded private content payloads |
| 9 | DELIVERY_REQUEST | Reserved for future fine-grained fragment/receipt requests |
| 10 | COURIER_ENVELOPE | Original fully signed Noise X private packet; directed 1-hop custody request |

HELLO uses small JSON for human-readable profile extensibility. **The enclosing mesh packet is binary**; private/media bodies and sync ID lists use compact binary encoding.

## Content encoding

Version 2 begins with the four-byte `DGC2` marker. Java `DataOutputStream.writeUTF` strings: text (maximum 8,000 UTF-8 bytes), attachment name (100 characters), MIME (100 characters), followed by a 4-byte attachment length and up to 12,000 bytes. Total encoded content is limited to 14,000 bytes. Three additional writeUTF strings follow: kind (32 characters), thread (100 characters) and metadata (6,000 UTF-8 bytes). The total 14,000-byte limit still applies. The local decoder also accepts the legacy content format for preserved history and upgraded outbox bodies. All group/file kinds travel inside authenticated, encrypted PRIVATE_MESSAGE packets; FILE_TRANSFER stays reserved.

## BLE frames

| Field | Size |
|---|---:|
| Transfer ID (SHA-256 prefix) | 4 |
| Fragment index | 2 |
| Fragment count | 2 |
| Total reassembled byte length | 4 |
| SHA-256 of complete signed packet | 32 |
| Fragment bytes | variable |

The 44-byte frame header is local to one GATT link. Packet fragments are split to `min(MTU-3, 244)` bytes. The adapter requires negotiated MTU ≥80; unsupported devices fail the connection instead of truncating data. The assembly key includes physical link identity. Out-of-order fragments are accepted, equal duplicates are ignored, conflicting duplicates discard a transfer, and complete hash/signature validation precedes routing.

Fragment acknowledgements use GATT write responses and notification completion for link flow control. Whole private packets retry until recipient ACK. Selective missing-GATT-fragment NACK is not implemented. Larger files use a separate persistent 8 KiB content-chunk protocol with receipt bitmaps; whole signed packets still use this GATT framing. Public broadcasts have no guaranteed retransmission; the bounded public gossip cache repairs some losses.

## Wi-Fi TCP frames

Same-LAN links carry a four-byte big-endian signed integer length (1 through `Packet.MAX_WIRE`), followed by exactly that many packet bytes. Partial stream reads are assembled with `readFully`; invalid lengths and incomplete frames fail closed. No GATT fragment headers are added on TCP. First frame must be a valid direct signed HELLO; the message service applies the existing signing/Noise peer key pin before binding the link. Private packets retain the same Noise ciphertext and signed logical/transmission IDs used on BLE/WSS.

## Groups, file controls and identifiers

`groupInvite` and `groupUpdate` contain the owner, title, complete public-key roster, active flag and monotonic roster revision. `groupLeave` goes to the owner. `groupMessage` contains a separate logical-message ID and roster revision; each member receives a distinct envelope/packet ID. ACK and READ_RECEIPT refer to packet IDs, which the sender maps to logical messages and per-recipient delivery rows.

`fileOffer` contains random 16-byte transfer ID, name/MIME, size, chunk count and SHA-256 digest. `fileResume` reports a bounded Base64 receipt bitmap; `fileChunk` contains index and up to 8,192 bytes. `fileProbe`, `filePause`, `fileComplete` and `fileCancel` coordinate recovery. Receipt of an offer is not acceptance; completion requires a verified complete digest. Larger transfers are private peer-to-peer, up to 4 MiB; small group attachments remain bounded content bodies.

Wi-Fi NSD uses a short discovery label (`dgchat-` plus 16 hexadecimal prefix characters) and the full 64-character ID in its TXT `id` attribute. Only the signed HELLO and key hash authenticate the connection. Protocol-v1 packets are explicitly rejected; the relay must also use v2. Noise envelope prologues use `dgChat-envelope-v2` plus packet ID and full source/destination addresses.
