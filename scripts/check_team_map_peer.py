"""ADB/TCP map integration probe. Synthetic TEST nodes, not physical ranging evidence.

Start the phone's Wi-Fi host and forward tcp:38474 to tcp:38473 with adb.
Uses the existing demo mesh key; never changes phone coordinates or system GPS.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import socket
import struct
import time
import uuid

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from sutra_laptop_peer import Message, decode, encode, receive_exact, send_frame


MASTER = hashlib.pbkdf2_hmac("sha256", b"sutra-mesh-offline-2026", b"SIH-SUTRA-v2-master", 60000)
PREFIX = b"SG\x02"


def key(salt: bytes) -> bytes:
    extracted = hmac.digest(salt, MASTER, "sha256")
    return hmac.digest(extracted, b"sutra-v2-frame-key\x01", "sha256")


def seal(plain: bytes) -> bytes:
    salt, nonce = os.urandom(16), os.urandom(12)
    return PREFIX + salt + nonce + AESGCM(key(salt)).encrypt(nonce, plain, PREFIX + salt)


def receive(peer: socket.socket) -> Message:
    size, = struct.unpack(">I", receive_exact(peer, 4))
    assert 0 < size < 70000
    frame = receive_exact(peer, size)
    if frame.startswith(PREFIX):
        salt, nonce = frame[3:19], frame[19:31]
        frame = AESGCM(key(salt)).decrypt(nonce, frame[31:], PREFIX + salt)
    return decode(frame)


def beacon(source: int, sequence: int, name: str, lon: float | None, session: int) -> Message:
    name_bytes = name.encode()
    payload = struct.pack(">IqqBB", 0x53504F31, session, sequence, 0, len(name_bytes)) + name_bytes
    payload += bytes([lon is not None])
    if lon is not None:
        payload += struct.pack(">ddfi", 28.6139, lon, 5.0, 0)
    identity = uuid.uuid4().int
    return Message(1, 0, 255, identity >> 64, identity & ((1 << 64) - 1), source, 0, payload)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=38474)
    parser.add_argument("--hold-seconds", type=int, default=30)
    parser.add_argument("--report", type=Path, default=Path("artifacts/device_check/team_map_tcp_probe.json"))
    args = parser.parse_args()
    session = time.time_ns() & ((1 << 63) - 1)
    results = {"transport": "ADB-forwarded TCP", "coordinates": "synthetic Delhi fixtures, NOT physical device locations", "checks": {}}
    checks = results["checks"]
    with socket.create_connection(("127.0.0.1", args.port), timeout=10) as peer:
        peer.settimeout(15)
        first = beacon(0x54535401, 1, "TEST west", 77.209, session)
        second = beacon(0x54535402, 1, "TEST east", 77.210, session)
        for message in (first, second):
            send_frame(peer, seal(encode(message)))
        expected = {first.message_id, second.message_id}
        deadline = time.monotonic() + 20
        while expected and time.monotonic() < deadline:
            message = receive(peer)
            if message.message_id in expected:
                assert message.payload[20] == 1, "Phone must increment forwarded hop count"
                expected.remove(message.message_id)
        assert not expected, "Missing forwarded test nodes"
        checks["encrypted_two_peer_receive_and_relay"] = True
        send_frame(peer, seal(encode(first)))
        peer.settimeout(2)
        end = time.monotonic() + 2
        while time.monotonic() < end:
            try:
                message = receive(peer)
                assert message.message_id != first.message_id, "Duplicate was relayed"
            except socket.timeout:
                break
        checks["duplicate_not_relayed"] = True
        print("PASS encrypted two-peer relay and duplicate rejection; TEST nodes visible for inspection", flush=True)
        deadline = time.monotonic() + args.hold_seconds
        sequence = 2
        while time.monotonic() < deadline:
            for source, name, lon in [(0x54535401, "TEST west", 77.209), (0x54535402, "TEST east", 77.210)]:
                send_frame(peer, seal(encode(beacon(source, sequence, name, lon, session))))
            sequence += 1
            time.sleep(5)
        for source, name in [(0x54535401, "TEST west"), (0x54535402, "TEST east")]:
            send_frame(peer, seal(encode(beacon(source, sequence, name, None, session))))
        checks["withdrawal_sent"] = True
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(args.report, flush=True)


if __name__ == "__main__":
    main()
