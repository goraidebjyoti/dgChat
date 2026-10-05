import asyncio
import importlib.util
import unittest
from server import Relay

@unittest.skipUnless(importlib.util.find_spec('websockets'), 'Install relay/requirements.txt for integration tests')
class RelayIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def test_opaque_forwarding_invalid_disconnect_and_cleanup(self):
        from websockets.asyncio.server import serve
        from websockets.asyncio.client import connect
        relay = Relay()
        async with serve(relay.handle, '127.0.0.1', 0, max_size=18500) as server:
            port = server.sockets[0].getsockname()[1]
            async with connect(f'ws://127.0.0.1:{port}') as sender, connect(f'ws://127.0.0.1:{port}') as recipient:
                payload = b'DG\x01' + bytes(250)
                await sender.send(payload)
                self.assertEqual(payload, await asyncio.wait_for(recipient.recv(), 2))
                await sender.send('not binary')
                await asyncio.wait_for(sender.wait_closed(), 2)
                self.assertEqual(1008, sender.close_code)
        await asyncio.sleep(0)
        self.assertEqual(0, relay.admission.total)
