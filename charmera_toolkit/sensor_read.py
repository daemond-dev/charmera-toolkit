"""`sensor-read`: what the sensor-ID probe's SENSOR.TXT says. Read-only.

A port of the app's decoder (android/src/io/github/daemonddev/charmera/MainActivity.java: decodeSensor,
decodeScan, decodeFingerprint, decodeFingerprint16, identify16 and ovName). The probe writes SENSOR.TXT to the top
folder of the card when Photo mode opens. Four layouts are known, newest first:

- 0xA7 0x01 (16-bit ID read): a responder count, then per responder its 7-bit address and two windows of 16-bit
  registers, 0x3000..0x301F (32 bytes) then 0x3100..0x310F (16 bytes). OmniVision parts keep their chip ID at
  0x300A/0x300B (or 0x300B/0x300C when 0x300A reads 0), SmartSens parts at 0x3107/0x3108. A register that did not
  answer reads 0xFF (the probe pre-fills 0xFF and the bus routine leaves it on a NACK), not 0x00;
- 0xA6 0x01 (8-bit fingerprint): a responder count, then per responder its address and 8-bit registers 0x00..0x1F
  then 0xF0..0xFF;
- 0xA5 0x01 (bus scan): a 16-byte bitmap of the 7-bit addresses that answered, the first responder's address (0xFF
  for none), then its registers 0x02 and 0x03;
- an older single-address probe's two bytes: registers 0x02 and 0x03 at the SP140A's address.

The firmware the probe is built on drives the SP140A at 7-bit address 0x3C (bus 0x78), ID 14 0A. The factory build
v012 drives the OV9732 at bus address 0x6C (7-bit 0x36), whose chip ID reads 97 32 at 0x300A/0x300B. What the file
says is what the probe read when it ran; this module does not check when that was.
"""
from __future__ import annotations

from pathlib import Path
from typing import List, Optional

SP140A_ADDR = 0x3C          # 7-bit; bus write address 0x78
SP140A_ID = (0x14, 0x0A)
REC = 49                    # one address byte and 48 register bytes, in the 0xA6 and 0xA7 layouts

# Known OmniVision chip IDs (0x300A/0x300B, or 0x300B/0x300C when 0x300A reads 0), small point-and-shoot parts: the
# app's list.
OV_PARTS = {
    0x2680: "OV2680", 0x2685: "OV2685", 0x2640: "OV2640", 0x5640: "OV5640", 0x5647: "OV5647", 0x5648: "OV5648",
    0x8865: "OV8865", 0x9732: "OV9732", 0x7670: "OV7670",
}

NOTHING_ANSWERED = ("The probe scanned every address on the sensor bus and nothing answered.\n\nThat points to a "
                    "sensor that is not responding at all (disconnected, unpowered, or dead), not a wrong-sensor "
                    "mismatch. Reseat the sensor's ribbon and scan again; if it still answers nowhere, the sensor or "
                    "its wiring is a hardware fault no firmware can fix.")
NO_FILE = ("No SENSOR.TXT on the card yet.\n\nFlash the sensor-ID probe and remove the update file, then open Photo "
           "mode on the camera: it stops there as before, but writes the file first. Then bring the card back and "
           "run sensor-read again.")


def ov_name(chip_id: int) -> Optional[str]:
    return OV_PARTS.get(chip_id)


def hex_addr(a: int) -> str:
    """'0x36 (bus 0x6C)' for a 7-bit address."""
    return f"0x{a:02X} (bus 0x{(a << 1) & 0xFF:02X})"


def hex_range(data: bytes, start: int, n: int) -> str:
    """n bytes from data[start] as hex, two spaces every 8."""
    return "".join(("" if i == 0 else "  " if i % 8 == 0 else " ") + f"{data[start + i]:02X}" for i in range(n))


def _responders(b: bytes) -> List[int]:
    """The record offsets of the 0xA6/0xA7 layouts' responders, stopping at a record the file cuts off."""
    out = []
    for i in range(b[2]):
        off = 3 + i * REC
        if off + REC > len(b):
            break
        out.append(off)
    return out


def identify16(b: bytes, off: int) -> str:
    """The part at one 0xA7 responder (record at off), named from its 16-bit chip-ID registers, or why it cannot be."""
    w1, w2 = off + 1, off + 1 + 32          # 0x3000..0x301F, 0x3100..0x310F
    r300a, r300b, r300c = b[w1 + 0x0A], b[w1 + 0x0B], b[w1 + 0x0C]
    r3107, r3108 = b[w2 + 0x07], b[w2 + 0x08]
    ov = -1
    if r300a not in (0x00, 0xFF):
        ov = (r300a << 8) | r300b                  # e.g. 0x9732, 0x2680
    elif r300a == 0x00 and r300b not in (0x00, 0xFF):
        ov = (r300b << 8) | r300c                  # OV8865-style: 0x300A reads 0x00
    if ov > 0:
        name = ov_name(ov) or f"OV{ov:04X} (not in the list)"
        return f"OmniVision {name}  [0x300A/B/C = {r300a:02X} {r300b:02X} {r300c:02X}]"
    if r3107 not in (0x00, 0xFF):
        return f"SmartSens SC{r3107:02X}{r3108:02X}  [0x3107/8 = {r3107:02X} {r3108:02X}]"
    regs = b[w1: w1 + 48]
    if all(v == 0xFF for v in regs):
        return ("no 16-bit register answered (all FF): the part likely uses 8-bit register addresses; the probe can be "
                "switched to an 8-bit ID read of 0x00..0x1F and 0xF0..0xFF.")
    if all(v == 0x00 for v in regs):
        return ("answered, but the 16-bit ID registers all read 00: the sensor's core may be held unclocked, or its ID "
                "is at a register other than 0x300A/0x3107.")
    if all(v == regs[0] for v in regs):
        return f"all registers read {regs[0]:02X}: likely the wrong sub-address width for this part."
    return "ID not recognised at 0x300A/0x300B (OmniVision) or 0x3107/0x3108 (SmartSens): send the file."


def decode_fingerprint16(b: bytes) -> str:
    offs = _responders(b)
    if not offs:
        return NOTHING_ANSWERED
    addrs = [b[o] for o in offs]
    if SP140A_ADDR in addrs:
        out = "A sensor answered at 0x3C (bus 0x78), the address the firmware drives.\n\n"
    else:
        out = (f"A sensor answered at {hex_addr(addrs[0])}, not the 0x3C (bus 0x78) the firmware drives: alive, just "
               "at another address.\n\n")
    out += "".join(f"{hex_addr(b[o])}: {identify16(b, o)}\n" for o in offs)
    out += "\nSend SENSOR.TXT so the fingerprint can be confirmed against a datasheet."
    out += "\n\nRegister fingerprint (16-bit reads; FF = no answer):"
    for o in offs:
        out += (f"\n\n{hex_addr(b[o])}\n  3000-301F: {hex_range(b, o + 1, 32)}"
                f"\n  3100-310F: {hex_range(b, o + 1 + 32, 16)}")
    return out


def decode_fingerprint(b: bytes) -> str:
    offs = _responders(b)
    if not offs:
        return NOTHING_ANSWERED
    addrs = [b[o] for o in offs]
    if SP140A_ADDR in addrs:
        o = offs[addrs.index(SP140A_ADDR)]
        r2, r3 = b[o + 1 + 0x02], b[o + 1 + 0x03]
        match = (r2, r3) == SP140A_ID
        out = (f"A sensor answered at 0x3C (bus 0x78), the address the firmware drives, and its registers 0x02/0x03 "
               f"read 0x{r2:02X} 0x{r3:02X}" + (": the SP140A it expects.\n\n" if match else ", not the SP140A's 14 0A.\n\n")
               + ("So the sensor is present and is the exact part the firmware expects; a freeze is not a sensor "
                  "mismatch." if match else "Something answers at the SP140A address but reports a different ID."))
    else:
        out = (f"A sensor answered at {hex_addr(addrs[0])}, not the 0x3C (bus 0x78) the firmware drives.\n\n"
               "The camera has a different sensor than the firmware targets: it is alive, just at another address. "
               "That is the fixable case: a firmware built for this sensor could work.")
    out += "\n\nRegister fingerprint (send SENSOR.TXT so the exact part can be named):"
    for o in offs:
        out += f"\n\n{hex_addr(b[o])}\n  00-1F: {hex_range(b, o + 1, 32)}\n  F0-FF: {hex_range(b, o + 1 + 32, 16)}"
    return out


def decode_scan(b: bytes) -> str:
    hits = [a for a in range(128) if b[2 + (a >> 3)] & (1 << (a & 7))]
    if not hits:
        return NOTHING_ANSWERED
    listed = ", ".join(hex_addr(a) for a in hits)
    r2, r3 = b[19], b[20]
    id_text = f"0x{r2:02X} 0x{r3:02X}"
    if SP140A_ADDR in hits:
        id_read = b[18] == SP140A_ADDR
        match = id_read and (r2, r3) == SP140A_ID
        out = "A sensor answered at 0x3C (bus 0x78), the address the firmware drives"
        if id_read:
            out += f", and its ID reads {id_text}" + (": the SP140A it expects." if match else ", not the SP140A's 14 0A.")
        else:
            out += "."
        out += "\n\n" + ("So the sensor is present and is the exact part the firmware expects. The freeze is not a sensor "
                         "mismatch: the cause is elsewhere (power, clock, or a fault past the ID read)." if match else
                         "A sensor is present at the expected address, so this is not a missing or wrong-address sensor. "
                         "If the ID is not 14 0A, note it down.")
    else:
        a = b[18] if b[18] != 0xFF else hits[0]
        out = (f"A sensor answered at {hex_addr(a)}, not the 0x3C (bus 0x78) the firmware drives.\n\nThe camera has a "
               "different sensor than the firmware targets: it is alive, just at another address. That is the fixable "
               f"case: a firmware built for this sensor could work. The first responder's registers 0x02/0x03 read "
               f"{id_text}. Note the address and these bytes down.")
    return out + f"\n\nAnswered at: {listed}."


def decode_sensor(b: Optional[bytes]) -> str:
    """What SENSOR.TXT means, in plain words; b is its bytes, or None when there is no such file."""
    if b is None:
        return NO_FILE
    if len(b) >= 3 and b[0] == 0xA7 and b[1] == 0x01:
        return decode_fingerprint16(b)
    if len(b) >= 3 and b[0] == 0xA6 and b[1] == 0x01:
        return decode_fingerprint(b)
    if len(b) >= 21 and b[0] == 0xA5 and b[1] == 0x01:
        return decode_scan(b)
    if len(b) < 2:
        return (f"SENSOR.TXT holds {len(b)} {'byte' if len(b) == 1 else 'bytes'}, not a result the probe writes. The "
                "probe may not have finished. Flash it again, open Photo mode, and read it again.")
    hi, lo = b[0], b[1]
    id_text = f"0x{hi:02X} 0x{lo:02X}"
    if (hi, lo) in ((0xFF, 0xFF), (0x00, 0x00)):
        return (f"Sensor ID {id_text}: nothing answered at the one address this older probe tried. Flash the current "
                "probe, which scans every address and tells a wrong address apart from a sensor that is truly silent.")
    if (hi, lo) in ((0x14, 0x0A), (0x0A, 0x14)):
        return f"Sensor ID {id_text}: the SP140A, the sensor the firmware already drives."
    return f"Sensor ID {id_text}: a different sensor from the one the firmware drives. Note this pair down."


def find_sensor_file(path: Path) -> Optional[Path]:
    """SENSOR.TXT itself, or the one in this folder's top level (any case); None when there is none."""
    if path.is_file():
        return path
    try:
        for child in sorted(path.iterdir()):
            if child.name.lower() == "sensor.txt" and child.is_file():
                return child
    except OSError:
        return None
    return None


def handler(args) -> int:
    given = Path(args.path)
    if not given.exists():
        print(f"error:       {given}: no such file or folder")
        return 2
    found = find_sensor_file(given)
    if found is None:
        print(decode_sensor(None))
        return 1
    try:
        data = found.read_bytes()
    except OSError as exc:
        print(f"error:       cannot read {found}: {exc}")
        return 2
    print(f"file:        {found} ({len(data)} bytes)")
    print()
    print(decode_sensor(data))
    return 0


def register(subparsers) -> None:
    p = subparsers.add_parser(
        "sensor-read",
        help="say what the sensor-ID probe's SENSOR.TXT means (read-only)",
        description=(
            "Read SENSOR.TXT, which the sensor-ID probe writes to the top folder of the card when Photo mode opens, "
            "and say what it means: which addresses on the sensor bus answered, whether one is the SP140A's 0x3C "
            "(bus 0x78) the firmware drives, and, for the current probe's 16-bit read, which part the chip-ID "
            "registers name (OV9732 reads 97 32 at 0x300A/0x300B). The same decoder as the app's."
        ),
        epilog=("Give it the card's root folder or the SENSOR.TXT file. Exit code 0 when a file was decoded, 1 when "
                "there is no SENSOR.TXT, 2 when it cannot be read. The file says what the probe read when it ran, "
                "not when that was."),
    )
    p.add_argument("path", metavar="PATH", help="the card's root folder, or SENSOR.TXT itself")
    p.set_defaults(func=handler)
