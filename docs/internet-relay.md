# Optional independent internet relays

BLE never requires this relay. Every operator can run a separate compatible relay; the application can connect to three configured endpoints. This is a federable set of user-selected transports, not a blockchain/DHT/account service, and the supplied relay does not implement a distributed topology or automatic global peer discovery.

## Start behind a TLS reverse proxy

Use Python 3.12 and the pinned dependency in a separate environment:

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python server.py --host 127.0.0.1 --port 8765
```

Run from `relay/`. Place a TLS reverse proxy on a domain you control. `Caddyfile.example` is a small configuration example. Configure `wss://your-domain/` in dgChat Settings on both endpoints and enable the bridge. Valid TLS certificates and normal system trust are required; the app does not bypass certificate validation. There are no API keys, centralized user accounts or embedded credentials.

Alternatively, supply certificate paths directly:

```bash
python server.py --host 0.0.0.0 --port 8765 --cert /secure/fullchain.pem --key /secure/privatekey.pem
```

Never add the certificate private key to the dgChat repository. Follow the host's normal operational security and use process/service isolation. Docker is optional; the supplied image runs as a non-root user and should sit behind a TLS proxy.

## Transport semantics

Each WebSocket carries complete `DG` v1 binary packets. The relay broadcasts each valid-sized frame to other currently connected clients. Clients verify source signatures, destinations, expiry and dedup. No relay-side directory, centralized topology, persistent cache, plaintext decryption or receipt generation exists.

The relay caps concurrent clients at 64 and at 4 per IP, frames at 18,500 bytes, send queues at 8 frames and per-client input at 4 packets/sec with a burst of 20. Slow consumers are disconnected rather than accumulating memory. A shared NAT may hit the per-IP limit; change policy only after assessing load. Public rooms and public history gossip are withheld by the Android router. Signed public presence and private routing metadata can reach relays when bridging is enabled.

## Connectivity limits

- Both distant peers need a common relay or a connected chain of bridge participants. Internet availability alone does not imply deliverability.
- dgChat retries configured relay connections with bounded exponential backoff.
- WebSocket closure does not delete the sender's outbox.
- The relay itself does not persist offline messages; encrypted outbox/courier behavior belongs to endpoints.
- Relay acceptance is not recipient delivery. Only a recipient-signed ACK marks `DELIVERED`.

## Verify the relay

```bash
python3 -m unittest discover -s relay -p 'test_*.py' -v
```

Run that command from the repository root. Local smoke checks for opaque forwarding are recorded separately in `verification.md`; they do not verify public TLS deployment or Android switching.
