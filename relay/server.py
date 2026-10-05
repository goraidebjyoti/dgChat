#!/usr/bin/env python3
"""Optional independent dgChat relay. Broadcasts opaque signed binary packets; no directory or storage."""
import argparse
import asyncio
import ssl
from limits import Admission, Bucket, MAX_PACKET, valid_frame

class Relay:
    def __init__(self):
        self.clients = {}
        self.admission = Admission()

    async def handle(self, websocket):
        ip = websocket.remote_address[0] if websocket.remote_address else 'unknown'
        if not self.admission.acquire(ip):
            await websocket.close(1013, 'Relay capacity reached')
            return
        queue = asyncio.Queue(maxsize=8)
        self.clients[websocket] = queue
        bucket = Bucket()
        async def writer():
            while True:
                packet = await queue.get()
                await asyncio.wait_for(websocket.send(packet), timeout=5)
        task = asyncio.create_task(writer())
        async def close_failed_writer():
            try:
                await task
            except asyncio.CancelledError:
                return
            except Exception:
                await websocket.close(1013, 'Slow connection')
        watcher = asyncio.create_task(close_failed_writer())
        try:
            async for packet in websocket:
                if not valid_frame(packet):
                    await websocket.close(1008, 'Invalid dgChat frame')
                    break
                if not bucket.take():
                    await websocket.close(1008, 'Packet rate exceeded')
                    break
                for peer, target in list(self.clients.items()):
                    if peer is websocket:
                        continue
                    try:
                        target.put_nowait(packet)
                    except asyncio.QueueFull:
                        # Disconnect slow consumers instead of creating an unbounded backlog.
                        await peer.close(1013, 'Slow consumer')
        finally:
            self.clients.pop(websocket, None)
            self.admission.release(ip)
            task.cancel()
            watcher.cancel()
            await asyncio.gather(task, watcher, return_exceptions=True)

async def main(args):
    from websockets.asyncio.server import serve
    tls = None
    if args.cert or args.key:
        if not (args.cert and args.key):
            raise ValueError('Supply both --cert and --key')
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(args.cert, args.key)
    relay = Relay()
    async with serve(relay.handle, args.host, args.port, ssl=tls,
                     max_size=MAX_PACKET, max_queue=8, ping_interval=30, ping_timeout=20):
        print('dgChat relay listening. Use trusted TLS/WSS in the Android app.')
        await asyncio.Future()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--cert')
    parser.add_argument('--key')
    asyncio.run(main(parser.parse_args()))
