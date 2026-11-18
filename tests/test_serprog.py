"""The read-only serprog reader against a simulated Pico running pico-serprog."""
import hashlib
import os
import random

import pytest

from charmera_toolkit import serprog as sp

ACK, NAK = 0x06, 0x15


class FakePico:
    """Byte-level serprog device with a 2 MiB SPI flash behind it. Records every SPI opcode it is sent."""

    def __init__(self, image, ident=b"\x85\x60\x15", corrupt_read=None, pin_state=True):
        self.image = image
        self.ident = ident
        self.inbuf = bytearray()
        self.out = bytearray()
        self.opcodes = []
        self.full_reads = 0
        self.corrupt_read = corrupt_read  # index of the whole-chip read to damage
        self.pins = None
        self.cmds = {0x00, 0x01, 0x02, 0x03, 0x10, 0x12, 0x13, 0x14} | ({0x15} if pin_state else set())
        self.closed = False

    # pyserial-like surface
    def reset_input_buffer(self):
        self.out.clear()

    def close(self):
        self.closed = True

    def read(self, n):
        got = bytes(self.out[:n])
        del self.out[:n]
        return got

    def write(self, data):
        self.inbuf += data
        while self._step():
            pass

    def _need(self, n):
        return len(self.inbuf) >= n

    def _step(self):
        if not self.inbuf:
            return False
        op = self.inbuf[0]
        if op == 0x10:
            del self.inbuf[:1]
            self.out += bytes([NAK, ACK])
        elif op == 0x01:
            del self.inbuf[:1]
            self.out += bytes([ACK, 1, 0])
        elif op == 0x02:
            del self.inbuf[:1]
            m = bytearray(32)
            for c in self.cmds:
                m[c // 8] |= 1 << (c % 8)
            self.out += bytes([ACK]) + m
        elif op == 0x03:
            del self.inbuf[:1]
            self.out += bytes([ACK]) + b"pico-serprog".ljust(16, b"\0")
        elif op == 0x12:
            if not self._need(2):
                return False
            del self.inbuf[:2]
            self.out.append(ACK)
        elif op == 0x14:
            if not self._need(5):
                return False
            hz = self.inbuf[1:5]
            del self.inbuf[:5]
            self.out += bytes([ACK]) + hz
        elif op == 0x15 and 0x15 in self.cmds:
            if not self._need(2):
                return False
            self.pins = self.inbuf[1]
            del self.inbuf[:2]
            self.out.append(ACK)
        elif op == 0x13:
            if not self._need(7):
                return False
            slen = int.from_bytes(self.inbuf[1:4], "little")
            rlen = int.from_bytes(self.inbuf[4:7], "little")
            if not self._need(7 + slen):
                return False
            cmd = bytes(self.inbuf[7:7 + slen])
            del self.inbuf[:7 + slen]
            self.out += bytes([ACK]) + self._spi(cmd, rlen)
        else:
            del self.inbuf[:1]
            self.out.append(NAK)
        return True

    def _spi(self, cmd, rlen):
        self.opcodes.append(cmd[0])
        if cmd[0] == 0x9F:
            return self.ident[:rlen]
        if cmd[0] == 0x03:
            addr = int.from_bytes(cmd[1:4], "big")
            data = bytearray(self.image[addr:addr + rlen])
            if addr == 0:
                self.full_reads += 1
            if self.corrupt_read is not None and self.full_reads - 1 == self.corrupt_read and addr == 0x10000:
                data[5] ^= 0x40
            return bytes(data)
        return bytes(rlen)


def image():
    rnd = random.Random(7)
    return sp.MAGIC + bytes(rnd.getrandbits(8) for _ in range(sp.FLASH_SIZE - 4))


def run(tmp_path, fake, *extra):
    return sp.main(["--out", str(tmp_path), "--port", "X", *extra], opener=lambda: fake)


def test_three_identical_reads_make_one_verified_backup(tmp_path, capsys):
    img = image()
    fake = FakePico(img)
    assert run(tmp_path, fake) == 0
    files = sorted(os.listdir(tmp_path))
    bins = [f for f in files if f.endswith(".bin")]
    assert len(bins) == 1 and ".unverified" not in bins[0]
    assert open(tmp_path / bins[0], "rb").read() == img
    sha = hashlib.sha256(img).hexdigest()
    assert open(tmp_path / (bins[0] + ".sha256")).read().startswith(sha)
    assert set(fake.opcodes) <= set(sp.SAFE_OPCODES)
    assert fake.pins == 0 and fake.closed  # outputs released, port closed
    assert "VERIFIED: 3 of 3" in capsys.readouterr().out


def test_one_bad_read_is_left_unverified_and_the_matching_two_win(tmp_path):
    img = image()
    fake = FakePico(img, corrupt_read=1)
    assert run(tmp_path, fake) == 0
    files = sorted(os.listdir(tmp_path))
    assert any(f.endswith(".unverified-read2.bin") for f in files)
    good = [f for f in files if f.endswith(".bin") and ".unverified" not in f]
    assert len(good) == 1 and open(tmp_path / good[0], "rb").read() == img


def test_two_reads_that_differ_keep_nothing_verified(tmp_path):
    fake = FakePico(image(), corrupt_read=1)
    assert run(tmp_path, fake, "--reads", "2") == 1
    files = os.listdir(tmp_path)
    assert files and all(".unverified-read" in f for f in files)


@pytest.mark.parametrize("ident", [b"\xff\xff\xff", b"\x00\x00\x00", b"\xef\x40\x15", b"\x85\x60\x16"])
def test_bad_or_unexpected_id_stops_before_any_read(tmp_path, ident):
    fake = FakePico(image(), ident=ident)
    assert run(tmp_path, fake) == 1
    assert 0x03 not in fake.opcodes and os.listdir(tmp_path) == []


def test_any_chip_reads_an_unexpected_maker(tmp_path):
    fake = FakePico(image(), ident=b"\xef\x40\x15")
    assert run(tmp_path, fake, "--any-chip") == 0


def test_write_and_erase_opcodes_never_reach_the_programmer():
    fake = FakePico(image())
    s = sp.Serprog(fake)
    s.setup(1_000_000)
    for op in (0x06, 0x02, 0x20, 0x52, 0xD8, 0xC7, 0x60, 0x01, 0xB9):
        with pytest.raises(sp.SerprogError):
            s.spi([op, 0, 0, 0], 0)
    assert fake.opcodes == []


def test_programmer_without_pin_state_still_works(tmp_path):
    fake = FakePico(image(), pin_state=False)
    assert run(tmp_path, fake, "--reads", "2") == 0


def test_single_read_is_refused(tmp_path):
    with pytest.raises(SystemExit):
        run(tmp_path, FakePico(image()), "--reads", "1")
