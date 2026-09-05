#!/usr/bin/env python3
"""Minimal laptop receiver for testing the Android SUTRA transport."""

from __future__ import annotations

import argparse
import socket
import struct
import subprocess
import sys
import time
import uuid
import zlib
from dataclasses import dataclass


PORT = 38_473
HEADER = struct.Struct(">HBBHBBQQQQI")
MAGIC = 0x5354
VERSION = 1
TYPES = {
    1: "HELLO",
    2: "TEXT_FINAL",
    3: "ALERT_CORE",
    4: "REPAIR_EVIDENCE",
    5: "RECEIVED_ACK",
    6: "PLAYBACK_STARTED",
    7: "PLAYBACK_FINISHED",
    8: "REPAIR_REQUEST",
}
LANGUAGES = [
    "english", "hindi", "gujarati", "marathi", "kannada",
    "malayalam", "tamil", "telugu", "odia", "bengali",
]


@dataclass(frozen=True)
class Message:
    kind: int
    flags: int
    language: int
    message_hi: int
    message_lo: int
    source: int
    destination: int
    payload: bytes

    @property
    def message_id(self) -> str:
        return str(uuid.UUID(int=(self.message_hi << 64) | self.message_lo))


def decode(frame: bytes) -> Message:
    if len(frame) < HEADER.size + 4:
        raise ValueError("truncated SUTRA frame")
    fields = HEADER.unpack_from(frame)
    magic, version, kind, flags, language, header_size, hi, lo, source, destination, size = fields
    if magic != MAGIC or version != VERSION or header_size != HEADER.size:
        raise ValueError("unsupported SUTRA header")
    if len(frame) != HEADER.size + size + 4:
        raise ValueError("SUTRA frame length mismatch")
    expected_crc = struct.unpack_from(">I", frame, len(frame) - 4)[0]
    if zlib.crc32(frame[:-4]) != expected_crc:
        raise ValueError("SUTRA CRC mismatch")
    return Message(kind, flags, language, hi, lo, source, destination, frame[HEADER.size:-4])


def encode(message: Message) -> bytes:
    header = HEADER.pack(
        MAGIC, VERSION, message.kind, message.flags, message.language, HEADER.size,
        message.message_hi, message.message_lo, message.source, message.destination,
        len(message.payload),
    )
    body = header + message.payload
    return body + struct.pack(">I", zlib.crc32(body))


def receive_exact(peer: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        chunk = peer.recv(size - len(chunks))
        if not chunk:
            raise EOFError("phone disconnected")
        chunks.extend(chunk)
    return bytes(chunks)


def send_frame(peer: socket.socket, frame: bytes) -> None:
    peer.sendall(struct.pack(">I", len(frame)) + frame)


def acknowledgement(original: Message, kind: int, laptop_id: int) -> bytes:
    return encode(Message(
        kind=kind,
        flags=1,
        language=original.language,
        message_hi=original.message_hi,
        message_lo=original.message_lo,
        source=laptop_id,
        destination=original.source,
        payload=b"",
    ))


def critical_fields(payload: bytes) -> str:
    kinds = {1: "negation", 2: "number", 3: "emergency", 4: "location"}
    if not payload:
        return "none"
    count, offset, output = payload[0], 1, []
    for _ in range(count):
        if offset + 3 > len(payload):
            raise ValueError("truncated critical field")
        kind, confidence, size = payload[offset:offset + 3]
        offset += 3
        text = payload[offset:offset + size].decode("utf-8", errors="strict")
        offset += size
        output.append(f"{kinds.get(kind, kind)}={text!r} ({confidence / 255:.2f})")
    if offset != len(payload):
        raise ValueError("unexpected critical-field bytes")
    return ", ".join(output) or "none"


def local_addresses() -> list[str]:
    return sorted({
        address
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
        for address in [info[4][0]]
        if not address.startswith("127.")
    })


def preferred_address() -> str | None:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
            route.connect(("192.0.2.1", 9))  # Route lookup only; UDP sends no packet here.
            return route.getsockname()[0]
    except OSError:
        return None


def speak_with_windows(text: str) -> None:
    if sys.platform != "win32":
        raise RuntimeError("--speak currently supports only the Windows laptop harness")
    script = (
        "$reader=New-Object IO.StreamReader([Console]::OpenStandardInput(),[Text.Encoding]::UTF8);"
        "$text=$reader.ReadToEnd();"
        "$voice=New-Object -ComObject SAPI.SpVoice;"
        "[void]$voice.Speak($text)"
    )
    subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
        input=text.encode("utf-8"),
        check=True,
    )


def handle_peer(peer: socket.socket, laptop_id: int, speak: bool, seen: set[str]) -> None:
    while True:
        try:
            size = struct.unpack(">I", receive_exact(peer, 4))[0]
            if not 1 <= size <= 66_048:
                raise ValueError(f"invalid outer frame size: {size}")
            message = decode(receive_exact(peer, size))
        except EOFError as error:
            print(error)
            return
        except ValueError as error:
            print(f"Rejected frame: {error}")
            continue

        language = LANGUAGES[message.language] if message.language < len(LANGUAGES) else "undetermined"
        print(f"{TYPES.get(message.kind, message.kind)} | {language} | {message.message_id} | {size} bytes")
        if message.kind == 2:
            text = message.payload.decode("utf-8", errors="strict")
            duplicate = message.message_id in seen
            print("Duplicate already processed" if duplicate else f"Transcript: {text}")
            send_frame(peer, acknowledgement(message, 5, laptop_id))
            print("Sent RECEIVED_ACK")
            if not duplicate:
                seen.add(message.message_id)
                if speak:
                    send_frame(peer, acknowledgement(message, 6, laptop_id))
                    started = time.perf_counter()
                    print("Sent PLAYBACK_STARTED; laptop TTS is speaking")
                    try:
                        speak_with_windows(text)
                    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
                        print(f"Laptop TTS failed; PLAYBACK_FINISHED not sent: {error}")
                    else:
                        elapsed = (time.perf_counter() - started) * 1_000
                        send_frame(peer, acknowledgement(message, 7, laptop_id))
                        print(f"Sent PLAYBACK_FINISHED after {elapsed:.0f} ms")
        elif message.kind in (3, 4):
            print(f"Critical fields: {critical_fields(message.payload)}")
        print()


def serve(speak: bool) -> None:
    laptop_id = uuid.uuid4().int & ((1 << 64) - 1)
    seen: set[str] = set()
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", PORT))
        server.listen(1)
        preferred = preferred_address()
        print(f"Listening on port {PORT}. Preferred laptop IP: {preferred or 'run ipconfig'}")
        print(f"All laptop IP(s): {', '.join(local_addresses()) or 'unavailable'}")
        print("On the phone: enter the laptop IPv4 address, then tap Manual connect.")
        while True:
            peer, address = server.accept()
            with peer:
                print(f"Connected: {address[0]}\n")
                handle_peer(peer, laptop_id, speak, seen)
            print("Waiting for the phone to reconnect...\n")


def self_test() -> None:
    sample = Message(2, 5, 1, 1, 2, 3, 0, "मदद चाहिए".encode())
    assert decode(encode(sample)) == sample
    print("SUTRA laptop peer self-test passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--speak", action="store_true", help="use actual Windows TTS and return playback ACKs")
    args = parser.parse_args()
    self_test() if args.self_test else serve(args.speak)


if __name__ == "__main__":
    main()
