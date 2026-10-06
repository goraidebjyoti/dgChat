import unittest
from limits import Admission, Bucket, valid_frame

class RelayLimitsTest(unittest.TestCase):
    def test_backpressure_rate(self):
        now = [0.0]
        bucket = Bucket(2, 1, lambda: now[0])
        self.assertTrue(bucket.take())
        self.assertTrue(bucket.take())
        self.assertFalse(bucket.take())
        now[0] += 1
        self.assertTrue(bucket.take())

    def test_ip_admission_is_bounded_and_recovers(self):
        admission = Admission()
        for _ in range(4):
            self.assertTrue(admission.acquire('ip'))
        self.assertFalse(admission.acquire('ip'))
        admission.release('ip')
        self.assertTrue(admission.acquire('ip'))

    def test_global_admission(self):
        admission = Admission()
        for n in range(64):
            self.assertTrue(admission.acquire(str(n)))
        self.assertFalse(admission.acquire('overflow'))
        for n in range(64):
            admission.release(str(n))
        self.assertEqual(0, admission.total)
        self.assertEqual({}, admission.by_ip)

    def test_frames(self):
        self.assertTrue(valid_frame(b'DG\x02' + bytes(200)))
        self.assertFalse(valid_frame('plaintext'))
        self.assertFalse(valid_frame(b'DG\x01' + bytes(200)))
        self.assertFalse(valid_frame(b'XX\x01' + bytes(200)))
        self.assertFalse(valid_frame(b'DG\x02' + bytes(19000)))
        self.assertFalse(valid_frame(b'DG\x02'))

if __name__ == '__main__':
    unittest.main()
