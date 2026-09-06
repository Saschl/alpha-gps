import unittest

from intervalometer import (
    AF_ON_DOWN,
    AF_ON_UP,
    SHUTTER_FULL_DOWN,
    SHUTTER_FULL_UP,
    SHUTTER_HALF_DOWN,
    SHUTTER_HALF_UP,
    ShutterTiming,
    parse_ready_status,
    trigger_shutter,
)


class FakeClient:
    def __init__(self):
        self.writes = []

    async def write_gatt_char(self, handle, payload):
        self.writes.append((handle, payload))


class TestIntervalometer(unittest.IsolatedAsyncioTestCase):
    async def test_trigger_shutter_sequence_without_af(self):
        client = FakeClient()
        await trigger_shutter(client, 42, ShutterTiming(pre_focus_ms=0, full_press_ms=0, settle_ms=0))

        self.assertEqual(
            client.writes,
            [
                (42, SHUTTER_HALF_DOWN),
                (42, SHUTTER_FULL_DOWN),
                (42, SHUTTER_FULL_UP),
                (42, SHUTTER_HALF_UP),
            ],
        )

    async def test_trigger_shutter_sequence_with_af(self):
        client = FakeClient()
        await trigger_shutter(
            client,
            7,
            ShutterTiming(pre_focus_ms=0, full_press_ms=0, settle_ms=0),
            use_af_on=True,
            af_on_ms=0,
        )

        self.assertEqual(
            client.writes,
            [
                (7, AF_ON_DOWN),
                (7, AF_ON_UP),
                (7, SHUTTER_HALF_DOWN),
                (7, SHUTTER_FULL_DOWN),
                (7, SHUTTER_FULL_UP),
                (7, SHUTTER_HALF_UP),
            ],
        )

    def test_parse_ready_status(self):
        self.assertTrue(parse_ready_status(b"\x02\xA0\x00"))
        self.assertFalse(parse_ready_status(b"\x02\xA0\x20"))
        self.assertIsNone(parse_ready_status(b"\x00\x01\x02"))


if __name__ == "__main__":
    unittest.main()

