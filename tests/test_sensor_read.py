"""sensor-read: the SENSOR.TXT decoder ported from the app, with byte vectors for each layout the probe writes."""
from charmera_toolkit import cli, sensor_read as sr


def record16(addr: int, regs3000: dict = None, regs3100: dict = None, fill: int = 0xFF) -> bytes:
    w1, w2 = bytearray([fill] * 32), bytearray([fill] * 16)
    for k, v in (regs3000 or {}).items():
        w1[k - 0x3000] = v
    for k, v in (regs3100 or {}).items():
        w2[k - 0x3100] = v
    return bytes([addr]) + bytes(w1) + bytes(w2)


def a7(*records: bytes, count=None) -> bytes:
    return bytes([0xA7, 0x01, len(records) if count is None else count]) + b"".join(records)


def test_the_16_bit_read_names_the_ov9732():
    text = sr.decode_sensor(a7(record16(0x36, {0x300A: 0x97, 0x300B: 0x32, 0x300C: 0x00})))
    assert text.startswith("A sensor answered at 0x36 (bus 0x6C), not the 0x3C (bus 0x78) the firmware drives")
    assert "0x36 (bus 0x6C): OmniVision OV9732  [0x300A/B/C = 97 32 00]" in text
    assert "3000-301F: FF FF FF FF FF FF FF FF  FF FF 97 32 00 FF FF FF" in text
    assert sr.OV9732_NEXT in text and "charmera build --base v012 --download" in text   # what to flash next
    assert "Send" not in text
    other = sr.decode_sensor(a7(record16(0x36, {0x300A: 0x26, 0x300B: 0x80})))
    assert sr.OV9732_NEXT not in other and "Keep SENSOR.TXT" in other


def test_the_16_bit_read_other_parts_and_answers():
    assert sr.identify16(record16(0x36, {0x300A: 0x00, 0x300B: 0x88, 0x300C: 0x65}), 0).startswith("OmniVision OV8865")
    assert "OV1234 (not in the list)" in sr.identify16(record16(0x36, {0x300A: 0x12, 0x300B: 0x34}), 0)
    assert sr.identify16(record16(0x30, regs3100={0x3107: 0x20, 0x3108: 0x35}), 0).startswith("SmartSens SC2035")
    assert sr.identify16(record16(0x3C), 0).startswith("no 16-bit register answered (all FF)")
    assert sr.identify16(record16(0x3C, fill=0x00), 0).startswith("answered, but the 16-bit ID registers all read 00")
    # as in the app, a window that reads one value other than 00 or FF throughout is taken as an OmniVision ID first
    assert sr.identify16(record16(0x3C, fill=0x5A), 0).startswith("OmniVision OV5A5A (not in the list)")
    odd = record16(0x3C, {0x3001: 0x12, 0x3002: 0x34})
    assert sr.identify16(odd, 0).startswith("ID not recognised")
    text = sr.decode_sensor(a7(record16(0x3C), record16(0x36, {0x300A: 0x97, 0x300B: 0x32})))
    assert text.startswith("A sensor answered at 0x3C (bus 0x78), the address the firmware drives.")
    assert sr.decode_sensor(a7()) == sr.NOTHING_ANSWERED
    # a count past the end of the file stops at the last whole record
    assert sr.decode_sensor(a7(record16(0x36, {0x300A: 0x97, 0x300B: 0x32}), count=3)).count("OV9732") == 1


def test_the_8_bit_fingerprint():
    regs = bytearray([0xFF] * 48)
    regs[2], regs[3] = 0x14, 0x0A
    text = sr.decode_sensor(bytes([0xA6, 0x01, 1, 0x3C]) + bytes(regs))
    assert "read 0x14 0x0A: the SP140A it expects" in text and "00-1F: FF FF 14 0A" in text
    other = sr.decode_sensor(bytes([0xA6, 0x01, 1, 0x21]) + bytes(48))
    assert other.startswith("A sensor answered at 0x21 (bus 0x42), not the 0x3C (bus 0x78)")
    assert sr.decode_sensor(bytes([0xA6, 0x01, 0])) == sr.NOTHING_ANSWERED


def scan(addrs, first, r2, r3) -> bytes:
    bitmap = bytearray(16)
    for a in addrs:
        bitmap[a >> 3] |= 1 << (a & 7)
    return bytes([0xA5, 0x01]) + bytes(bitmap) + bytes([first, r2, r3])


def test_the_bus_scan():
    text = sr.decode_sensor(scan([0x3C], 0x3C, 0x14, 0x0A))
    assert "its ID reads 0x14 0x0A: the SP140A it expects." in text and text.endswith("Answered at: 0x3C (bus 0x78).")
    wrong = sr.decode_sensor(scan([0x36, 0x50], 0x36, 0x97, 0x32))
    assert wrong.startswith("A sensor answered at 0x36 (bus 0x6C), not the 0x3C")
    assert "registers 0x02/0x03 read 0x97 0x32" in wrong and "Answered at: 0x36 (bus 0x6C), 0x50 (bus 0xA0)." in wrong
    assert sr.decode_sensor(scan([], 0xFF, 0, 0)) == sr.NOTHING_ANSWERED


def test_the_older_two_bytes_and_short_files():
    assert "the SP140A, the sensor the firmware already drives" in sr.decode_sensor(b"\x14\x0a")
    assert "nothing answered at the one address" in sr.decode_sensor(b"\xff\xff")
    assert "a different sensor" in sr.decode_sensor(b"\x97\x32")
    assert sr.decode_sensor(b"\x01").startswith("SENSOR.TXT holds 1 byte,")
    assert sr.decode_sensor(None) == sr.NO_FILE


def test_the_command_line(tmp_path, capsys):
    assert cli.main(["sensor-read", str(tmp_path)]) == 1
    assert "No SENSOR.TXT on the card yet" in capsys.readouterr().out
    (tmp_path / "sensor.txt").write_bytes(a7(record16(0x36, {0x300A: 0x97, 0x300B: 0x32})))
    assert cli.main(["sensor-read", str(tmp_path)]) == 0
    out = capsys.readouterr().out
    assert "sensor.txt (52 bytes)" in out and "OmniVision OV9732" in out
    assert cli.main(["sensor-read", str(tmp_path / "sensor.txt")]) == 0
    capsys.readouterr()
    assert cli.main(["sensor-read", str(tmp_path / "missing")]) == 2
