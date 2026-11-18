"""Read-only dump of the Charmera's SPI flash through a Pico running pico-serprog, without flashrom.

    python -m charmera_toolkit.serprog --out DIR [--port COM5] [--reads 3] [--khz 1000]

It talks the serprog protocol (flashrom's doc/serprog-protocol) straight over the Pico's USB serial port,
reads the chip's JEDEC ID, then reads the whole 2 MiB chip --reads times and compares the reads.

Safety rules:

- It can only send the flash opcodes in SAFE_OPCODES (ID, read, read status, release from power-down).
  Write enable, program and erase opcodes are refused in code before a byte reaches the programmer.
- A read is kept as <label>_<UTC time>.bin only when at least two reads are byte-identical. Every read is
  written first as <label>_<UTC time>.unverified-read<n>.bin; reads that match nothing keep that name.
- It stops before reading when the ID looks like a floating or shorted data line (all 0xFF or all 0x00),
  is not Puya (0x85) or is not 2 MiB, unless --any-chip is given.
- Electrical rules software cannot enforce: 3.3 V only (Pico pin 36, never pin 40), camera switched off,
  microSD out. See docs/02-RECOVERY.md.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import os
import sys

ACK, NAK = 0x06, 0x15

# serprog commands (flashrom serprog-protocol.txt)
S_CMD_NOP = 0x00
S_CMD_Q_IFACE = 0x01
S_CMD_Q_CMDMAP = 0x02
S_CMD_Q_PGMNAME = 0x03
S_CMD_SYNCNOP = 0x10
S_CMD_S_BUSTYPE = 0x12
S_CMD_O_SPIOP = 0x13
S_CMD_S_SPI_FREQ = 0x14
S_CMD_S_PIN_STATE = 0x15
BUS_SPI = 0x08

# SPI flash opcodes this tool may send. Nothing that writes, erases or changes a register.
SAFE_OPCODES = {
    0x9F: "read JEDEC ID",
    0x03: "read data",
    0x05: "read status register",
    0xAB: "release from deep power-down",
}

FLASH_SIZE = 2 * 1024 * 1024  # PY25D16HB
PUYA = 0x85
CHUNK = 4096
MAGIC = b"SPII"


class SerprogError(Exception):
    pass


class Serprog:
    """Minimal serprog client. `port` needs write(), read(n) (returning up to n bytes, timing out) and
    reset_input_buffer(), which pyserial's Serial has."""

    def __init__(self, port):
        self.port = port
        self.cmdmap = bytes(32)
        self.name = ""
        self.hz = None

    def _read(self, n, what):
        got = b""
        while len(got) < n:
            part = self.port.read(n - len(got))
            if not part:
                raise SerprogError(f"{what}: timed out after {len(got)} of {n} bytes")
            got += part
        return got

    def _cmd(self, op, params=b"", rlen=0, what=None):
        what = what or f"serprog command 0x{op:02X}"
        self.port.write(bytes([op]) + bytes(params))
        status = self._read(1, what)[0]
        if status != ACK:
            raise SerprogError(f"{what}: programmer answered 0x{status:02X} instead of ACK")
        return self._read(rlen, what) if rlen else b""

    def supports(self, op):
        return bool((self.cmdmap[op // 8] >> (op % 8)) & 1)

    def sync(self):
        for _ in range(8):
            self.port.reset_input_buffer()
            self.port.write(bytes([S_CMD_SYNCNOP]))
            if self.port.read(2) == bytes([NAK, ACK]):
                self.port.reset_input_buffer()
                return
        raise SerprogError("no answer to serprog sync. Is the Pico running pico_serprog.uf2, and is this its port?")

    def setup(self, hz):
        self.sync()
        iface = int.from_bytes(self._cmd(S_CMD_Q_IFACE, rlen=2, what="interface version"), "little")
        if iface != 1:
            raise SerprogError(f"serprog interface version {iface}, expected 1")
        self.cmdmap = self._cmd(S_CMD_Q_CMDMAP, rlen=32, what="command map")
        if self.supports(S_CMD_Q_PGMNAME):
            raw = self._cmd(S_CMD_Q_PGMNAME, rlen=16, what="programmer name")
            self.name = raw.split(b"\0", 1)[0].decode("ascii", "replace")
        if not self.supports(S_CMD_O_SPIOP):
            raise SerprogError("this programmer has no SPI operation command")
        if self.supports(S_CMD_S_BUSTYPE):
            self._cmd(S_CMD_S_BUSTYPE, bytes([BUS_SPI]), what="set bus type")
        if self.supports(S_CMD_S_SPI_FREQ):
            got = self._cmd(S_CMD_S_SPI_FREQ, hz.to_bytes(4, "little"), rlen=4, what="set SPI clock")
            self.hz = int.from_bytes(got, "little")
        if self.supports(S_CMD_S_PIN_STATE):
            self._cmd(S_CMD_S_PIN_STATE, b"\x01", what="enable outputs")

    def release(self):
        """Let go of the SPI lines so the programmer stops driving the chip."""
        if self.supports(S_CMD_S_PIN_STATE):
            self._cmd(S_CMD_S_PIN_STATE, b"\x00", what="disable outputs")

    def spi(self, out, rlen):
        out = bytes(out)
        if not out or out[0] not in SAFE_OPCODES:
            op = f"0x{out[0]:02X}" if out else "nothing"
            raise SerprogError(f"refused SPI opcode {op}: this tool only reads")
        params = len(out).to_bytes(3, "little") + rlen.to_bytes(3, "little") + out
        return self._cmd(S_CMD_O_SPIOP, params, rlen, what=SAFE_OPCODES[out[0]])

    def jedec_id(self):
        return self.spi([0x9F], 3)

    def read(self, addr, n):
        return self.spi([0x03, (addr >> 16) & 0xFF, (addr >> 8) & 0xFF, addr & 0xFF], n)


def check_id(ident, any_chip):
    """Return (size, problem). problem is None when the ID is the expected Puya 2 MiB part."""
    hexid = " ".join(f"{b:02X}" for b in ident)
    if ident == b"\xff\xff\xff":
        return None, (f"ID {hexid}: the data line from the chip (MISO, leg 2) reads all ones. The chip is not "
                      "answering: check the clip is seated square, leg 1 on the dot, and VCC (leg 8) is 3.3 V.")
    if ident == b"\x00\x00\x00":
        return None, (f"ID {hexid}: the data line reads all zeros. Usually a missing ground or VCC, a clip "
                      "contact not touching, or the camera's own chip still holding the bus.")
    size = 1 << ident[2] if 0x10 <= ident[2] <= 0x1F else None
    if any_chip:
        return size, None
    if ident[0] != PUYA:
        return size, f"ID {hexid}: maker 0x{ident[0]:02X} is not Puya (0x85). Stopped; --any-chip reads anyway."
    if size != FLASH_SIZE:
        return size, f"ID {hexid}: not a 2 MiB chip. Stopped; --any-chip reads anyway."
    return size, None


def read_all(sp, size, progress=None):
    data = bytearray()
    for addr in range(0, size, CHUNK):
        data += sp.read(addr, min(CHUNK, size - addr))
        if progress and (addr + CHUNK) % (256 * 1024) == 0:
            progress(addr + CHUNK, size)
    return bytes(data)


def keep_reads(reads, out_dir, label, stamp):
    """Write every read as unverified, then promote a content that two or more reads share.
    Returns (verified_path or None, list of unverified paths left, sha of verified)."""
    base = os.path.join(out_dir, f"{label}_{stamp}")
    paths = []
    for i, data in enumerate(reads, 1):
        p = f"{base}.unverified-read{i}.bin"
        with open(p, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        paths.append(p)
    shas = [hashlib.sha256(d).hexdigest() for d in reads]
    best = max(set(shas), key=shas.count)
    if shas.count(best) < 2:
        return None, paths, None
    verified = f"{base}.bin"
    data = reads[shas.index(best)]
    with open(verified, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    with open(verified + ".sha256", "w", encoding="ascii") as f:
        f.write(f"{best}  {os.path.basename(verified)}\n")
    left = []
    for p, s in zip(paths, shas):
        if s == best:
            os.remove(p)
        else:
            left.append(p)
    return verified, left, best


def describe(data):
    notes = []
    if data[:4] == MAGIC:
        notes.append('starts with the "SPII" firmware header, as a Charmera image should')
    else:
        notes.append(f'does NOT start with "SPII" (first bytes {data[:4].hex()}): treat it as suspect')
    if data.count(0xFF) == len(data):
        notes.append("is entirely 0xFF (blank or not read)")
    elif data.count(0) == len(data):
        notes.append("is entirely 0x00 (not read)")
    return notes


def find_port():
    from serial.tools import list_ports
    ports = list(list_ports.comports())
    likely = [p for p in ports if p.vid == 0x2E8A or "serprog" in (p.description or "").lower()
              or "pico" in (p.description or "").lower()]
    if len(likely) == 1:
        return likely[0].device, ports
    return None, ports


def main(argv=None, opener=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", required=True, help="folder for the dump files")
    ap.add_argument("--port", help="the Pico's serial port, e.g. COM5 (found automatically when there is one)")
    ap.add_argument("--reads", type=int, default=3, help="whole-chip reads to compare (default 3, at least 2)")
    ap.add_argument("--khz", type=int, default=1000, help="SPI clock in kHz (default 1000; lower it if reads differ)")
    ap.add_argument("--label", default="charmera_flash", help="file name prefix")
    ap.add_argument("--any-chip", action="store_true", help="read even when the ID is not the expected Puya 2 MiB")
    a = ap.parse_args(argv)
    if a.reads < 2:
        ap.error("--reads must be at least 2: a single read proves nothing")
    os.makedirs(a.out, exist_ok=True)

    if opener is None:
        import serial
        port = a.port
        if not port:
            port, ports = find_port()
            if not port:
                print("Could not pick the Pico's port. Ports seen:")
                for p in ports:
                    print(f"  {p.device}: {p.description}")
                print("Run again with --port COMn.")
                return 2
        print(f"port: {port}")
        dev = serial.Serial(port, 115200, timeout=3)
    else:
        dev = opener()

    sp = Serprog(dev)
    try:
        sp.setup(a.khz * 1000)
        print(f"programmer: {sp.name or '(no name)'}, SPI clock {sp.hz or '(default)'} Hz")
        sp.spi([0xAB], 0)
        first, second = sp.jedec_id(), sp.jedec_id()
        print("chip ID: " + " ".join(f"{b:02X}" for b in first))
        if first != second:
            print(f"The ID changed between two reads ({first.hex()} then {second.hex()}): the connection is "
                  "unreliable. Reseat the clip, shorten the wires or lower --khz.")
            return 1
        size, problem = check_id(first, a.any_chip)
        if problem:
            print(problem)
            return 1
        size = size or FLASH_SIZE
        reads = []
        for i in range(1, a.reads + 1):
            print(f"read {i} of {a.reads} ({size // 1024} KiB)...")
            data = read_all(sp, size, lambda done, total: print(f"  {done * 100 // total}%", flush=True))
            print(f"  sha256 {hashlib.sha256(data).hexdigest()}")
            reads.append(data)
    except SerprogError as e:
        print(f"error: {e}")
        return 1
    finally:
        try:
            sp.release()
        except Exception:
            pass
        dev.close()

    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    verified, left, sha = keep_reads(reads, a.out, a.label, stamp)
    if verified:
        print(f"\nVERIFIED: {sum(hashlib.sha256(r).hexdigest() == sha for r in reads)} of {len(reads)} reads "
              f"are identical.\n  {verified}\n  sha256 {sha}")
        for n in describe(open(verified, "rb").read()):
            print(f"  The image {n}.")
    else:
        print("\nNOT VERIFIED: no two reads match. Nothing is a backup yet. Reseat the clip, check the wires "
              "and try again, with a lower --khz.")
    for p in left:
        print(f"  left unverified: {p}")
    return 0 if verified else 1


if __name__ == "__main__":
    sys.exit(main())
