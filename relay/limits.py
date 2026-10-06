"""Resource policy kept separate from the optional WebSocket dependency."""
from collections import defaultdict
from time import monotonic

MAX_PACKET = 18500
MAX_CLIENTS = 64
MAX_PER_IP = 4

class Bucket:
    def __init__(self, capacity=20, rate=4, clock=monotonic):
        self.capacity, self.rate, self.clock = capacity, rate, clock
        self.tokens = float(capacity)
        self.last = clock()

    def take(self):
        now = self.clock()
        self.tokens = min(self.capacity, self.tokens + max(0, now-self.last)*self.rate)
        self.last = now
        if self.tokens < 1:
            return False
        self.tokens -= 1
        return True

class Admission:
    def __init__(self):
        self.total = 0
        self.by_ip = defaultdict(int)

    def acquire(self, ip):
        if self.total >= MAX_CLIENTS or self.by_ip.get(ip, 0) >= MAX_PER_IP:
            return False
        self.total += 1
        self.by_ip[ip] += 1
        return True

    def release(self, ip):
        if self.by_ip.get(ip, 0) > 0:
            self.total -= 1
            self.by_ip[ip] -= 1
            if self.by_ip[ip] == 0:
                del self.by_ip[ip]

def valid_frame(data):
    # Cryptographic signatures, recipient binding and expiry are verified by dgChat clients.
    return isinstance(data, bytes) and 160 <= len(data) <= MAX_PACKET and data[:3] == b'DG\x02'
