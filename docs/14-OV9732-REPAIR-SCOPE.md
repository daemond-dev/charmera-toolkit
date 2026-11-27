# OV9732 repair scope

Reading order: 14 of 15 · previous: [Research report](13-PHASE1-REPORT.md) · next: [OV9732 exposure, colour and white balance](15-OV9732-AE-WB-FLASH-PORT.md) · [all docs](README.md)

Written 2026-11-08, when the scoping was complete and the bring-up build was in progress, and updated with the results
of the v0.11 to v0.14 flashes. The stock image analysed is `stock.bin`, sha256 `cc3b4b2f…` (the public dump). All file
offsets below use `file_offset = VA + 0x200`.

---

## Summary

On the camera flashed on 2026-11-06, photo mode is dead because of a sensor mismatch. No hardware is broken:

- The stock firmware was built to drive a SuperPix SP140A (1 MP, 1280×720, 1-lane MIPI, 10-bit RAW), over bit-banged
  SCCB with 8-bit register addresses on bus 0x78 (7-bit 0x3C), with `0xFD`-paged register banks.
- The hardware carries an OmniVision OV9732 (same class: 1 MP, 720p, 1-lane MIPI, 10-bit RAW). It answers
  with 16-bit register addresses on bus 0x6C (7-bit 0x36), chip ID `0x9732` at registers `0x300A`/`0x300B`.

The firmware never talks to the sensor that is fitted, so the sensor is never initialized and never streams. The repair
is to point the firmware's sensor control channel at the OV9732 and give it an OV9732 init sequence. The MIPI data path
(D-PHY → ISP → hardware MJPEG) is shared by the two parts. It carried over unchanged on the v0.11 bring-up.

---

## The two sensors

| | SuperPix SP140A (firmware's target) | OmniVision OV9732 (installed) |
|---|---|---|
| Resolution | 1 MP, 1280×720 | 1 MP, 1280×720 (same) |
| Output | 10-bit RAW, 1-lane MIPI CSI-2 | 10-bit RAW, 1-lane MIPI CSI-2 (same) |
| Optical format | ~1/4.5" | 1/4" (larger) |
| Pixel size | smaller (undocumented) | 3.0 µm |
| Low-light | not published | 2.066 V/lux-s, 39 dB SNR, 72 dB DR |
| SCCB control | 8-bit regs, bus 0x78, `0xFD` paging | 16-bit regs, bus 0x6C |
| Chip ID | `0x140A` @ page-0 regs `0x02`/`0x03` | `0x9732` @ regs `0x300A`/`0x300B` |
| Docs / support | obscure, no public datasheet | public datasheet + many open drivers |

### How the original sensor was identified

The firmware reads no chip ID (see the root cause below), so the identification rests on the init table. The recovered
61-write init table matches the public OpenIPC/Ingenic SP140A driver on 51 of 61 register writes, byte for byte, in the
same `0xFD`-paged page-0/1/2 structure, and the runtime exposure/gain code matches it in meaning too. A second check,
looking for any rival from the same family, ruled out the OmniVision OV2735, which matched 6 of 36 (16%). That makes
a SuperPix SP-series part certain and the SP140A specifically highly likely. It is a software fingerprint only: the lens
is glued, so the die can't be read.

### Is the OV9732 "better"?

It's the same class, so there's no resolution upgrade (both are 720p/1 MP, 10-bit RAW, 1-lane MIPI). The OV9732 gathers
more light (larger 1/4" format, 3.0 µm pixels, so better low-light and SNR), and it is far easier to work with: it's a
mainstream part with a full datasheet and many open drivers. Expect somewhat better pictures at the same resolution, and
a much better part to build firmware against.

---

## Root cause: blind bring-up with no detection

The firmware does no sensor detection. The whole SCCB read path is dead code: the read routine `0x2d9b8` has one caller
(wrapper `0x2d850`), and that wrapper has no callers anywhere in the 2 MB image. Sensor bring-up only writes, blind:

```
reset/preamble table → ~20 ms delay → 61-write init table → stream on
```

So when the hardware changed from SP140A to OV9732, the firmware kept writing SP140A registers to bus `0x78`. The
OV9732 (bus `0x6C`, 16-bit) never heard them. Every write NACKed, was retried 10 times and abandoned, and the error
was ignored. With no detection, no failure ever surfaced.

---

## Firmware sensor-driver map (verified against `stock.bin`)

| Element | VA | file | notes |
|---|---|---|---|
| SCCB ctx struct | `0x1083A4` | `0x1085A4` | `11 00 12 00 00 00 00 08 08 78`: SCL=GPIO17, SDA=GPIO18, regbits=`08`, databits=`08`, busaddr=`78` |
| SCCB read primitive | `0x2d9b8` | `0x2dbb8` | width-agnostic: sends `(ctx[7]&~7)/8` addr bytes MSB-first |
| SCCB write primitive | `0x2d6bc` | `0x2d8bc` | mirror of read; same width-agnostic loop |
| write wrapper(ctx,reg,val) | `0x2d828` | `0x2da28` | |
| read wrapper | `0x2d850` | `0x2da50` | dead code; can be reused for an ID check |
| `sensor_write(reg,val)` thunk | `0x7b6d0` | `0x7b8d0` | loads ctx ptr, tail-calls `0x2d828` |
| table applier | `0x7b28c` | `0x7b48c` | reads `{reg8,val8}`, 10 retries/reg, `FF FF` terminator |
| reset/preamble table | `0xF59FC` | `0xF5BFC` | `fd 00 20 00 ff ff` (2 writes) |
| main init table | `0xF5A6C` | `0xF5C6C` | 61 writes + `ff ff`, 124 bytes, sha256 `aea3976b…` |
| bring-up / SET-MODE | `0x7b5a0` | `0x7b7a0` | programs capture path; applies reset table @`0x7b688`, delay, init table @`0x7b698` |
| init-table apply call | `0x7b698` | `0x7b898` | the injection point (the 0.10 probe already hooks here) |
| exposure/gain updater | `~0x7b340` | `~0x7b540` | SP140A `0xFD` page + `0x04`/`0x03` expo + `0x24` gain + `0x01` latch |
| sensor object / vtable | `0xf70cc` | `0xf72cc` | ifmode byte[0x24]=3; width[0x34]=`0x500`=1280; height[0x36]=`0x2d0`=720 |
| 2-entry sensor-profile table | `0x7b468` | `0x7b668` | key `0x32`→SP140A (active); key `0x3c`→id `0x9b` table `0xf64f0` (looks like a GC0308) |
| capture / MIPI engine | n/a | MMIO `0xD0800000` | reset strobe `+0x17c`, mode `+0x228`, enable/DMA `+0x600/0x610/0x63c/0x664`; likely D-PHY block ~`0xD0810000` |
| clock / PMU | n/a | MMIO `0xD0000000` | MCLK sel/div `+0x08` (helper `0x976c4`); gates `+0x0C`, `+0x58` |
| ISP / encode | n/a | n/a | Comi AE/AWB lib (`0x97348`), demosaic + H/V mirror (`0xf80b6`), hardware MJPEG (`Disp_Mjpeg_Init` `0x104c8`) |

No GPIO under code control resets or powers down the sensor. The only GPIOs on the sensor path are the bit-banged SCCB
pair (17/18). The sensor is reset by the regulator's power-on reset or by the capture engine's strobe.

---

## Repair plan

The two sensors share the interface (1-lane MIPI), the format (RAW10) and the resolution (720p), so the capture engine,
ISP and MJPEG stages need no changes. The v0.11 bring-up confirmed this. The work is in the SCCB control channel and the init sequence.

### 1. Control plane

Patch the ctx struct at file `0x1085A4`:
- `byte[7] 0x08 → 0x10` (16-bit register address)
- `byte[8] = 0x08` (keep: 8-bit data)
- `byte[9] 0x78 → 0x6C` (OV9732 at 7-bit 0x36)

Both SCCB primitives already treat `ctx[7]` as a bit count and send the address MSB-first, so 16-bit addressing needs
no change to the primitive code. Disassembly of both `0x2d9b8` (read) and `0x2d6bc` (write) confirms it.

### 2. Data plane (the main work)

Replace the SP140A init with an OV9732 1-lane-MIPI RAW10 720p init sequence. The OV9732 uses 16-bit register addresses,
so each entry is 3 bytes, `{reg_hi, reg_lo, val}`, where the SP140A's are 2 bytes, `{reg8, val8}`. There are two ways to
do it:
- (a) In place: widen the table applier `0x7b28c` to read 3-byte entries (change its `ldrb`/`add #2` to a 2-byte reg
  read + `add #3`) and replace the tables at `0xF5BFC`/`0xF5C6C`.
- (b) Injected, the one to try first: hook the init-table apply call at `0x7b698` (as the 0.10 probe already does) and
  run an injected OV9732 bring-up stub that walks an embedded 16-bit-reg table through the firmware's own write
  primitive, then starts streaming (`0x0100 = 0x01`). This reuses the probe/stub and apk workflow that already works,
  and leaves the stock applier untouched.

### 3. Runtime exposure/gain

Rewrite the updater at `~0x7b340` to translate SP140A's `0xFD`-paged `0x04`/`0x03` (exposure) and `0x24` (gain) into
the OV9732's `0x3500`/`0x3501`/`0x3502` (exposure, 3 bytes) and `0x350A`/`0x350B` (gain), with no paging. A first
"does it stream" test doesn't need this; correct AE does. (The hook that shipped in v0.21, the first stage of our
OV9732 port on v006, sits at VA `0x7b378`, and the real driver writes only `0x3501`/`0x3502`; see [OV9732 exposure,
colour and white balance](15-OV9732-AE-WB-FLASH-PORT.md).)

### 4. Add a real ID check (recommended)

Reuse the dead read path (wrapper `0x2d850`) to read `0x300A`/`0x300B` and confirm `0x9732` before the init is applied,
so bring-up checks the sensor instead of writing blind. That fixes the kind of bug that caused this failure.

---

## Open risks (not resolvable from the binary alone)

The v0.11 flash (2026-11-09) settled risks 1, 2 and 3. The bring-up produced real frames, three 1440×1080 JPEGs and a
14 MB AVI, so the OV9732 streams 1-lane MIPI, the SoC's capture/ISP/MJPEG path receives it unchanged, the module is
wired to the flex, and the MCLK is in range. The deepest unknown didn't bite.

1. ~~MIPI D-PHY / timing~~: resolved. Frames arrived on the first bring-up. The OV9732's MIPI timing matches the
   receiver as configured for the SP140A, with no engine reconfiguration.
2. ~~Module wiring~~: resolved. The installed OV9732 is the MIPI-output variant on the flex (it streams).
3. ~~MCLK~~: resolved. The clock the SoC puts out drives the OV9732 fine.
4. ~~Mirror/flip~~: resolved by v0.14. The frames came out rotated 180°, because the module is mounted rotated
   compared with the SP140A. v0.12 guessed the OV5640-family flip bits (`0x3820=0x16`/`0x3821=0x06`) and sheared every
   frame into lines. On the OV9732, `0x3820` bit1 is horizontal binning and `0x3821` is the sub-sample register
   (datasheet `OV9732_CSP5_DS_1.0`), so those writes decimated the line width against the fixed-1280 ISP. The correct
   180° flip is `0x3820=0x1C` (default 0x10 | Flip bit2 0x04 | Mirror bit3 0x08) and `0x3821=0x00`, with no companion
   writes. The datasheet and two production OV9732 drivers (CVITEK, XiongMai) agree, and both drivers write only
   `0x3820`. v0.13 reverted the flip to get back v0.11's clean geometry, and the flip moved to v0.14 because its
   effect on colour was unknown. A 180° array flip turns even-by-even BGGR into RGGB, and the fixed BGGR ISP can't
   re-align (the reference drivers rely on a host-side ISP reconfigure this SoC lacks), so the flip could have swapped
   R and B, which would have been worse than v0.11's blue deficit. v0.14 flashed `0x3820=0x1C` alone, and the colour
   did not swap: blue read pure blue `(0,0,235)` against a colour-bar target across three frames. The Bayer order
   survived the flip and needed no re-phase. (Had it swapped, the fix ready to go was a `±1` ISP-offset
   (`0x3811`/`0x3813`) nudge keeping the output `0x3808–0x380b` at 1280×720.)
5. White balance: the regions that are neutral in the scene measured R≈G with B low by 1.2–1.5× across three scenes. A blue
   deficit that consistent means the SoC's WB is fixed for the SP140A's spectral response (an auto-WB would vary by
   scene). v0.12 and v0.13 lift blue about 1.31× in the OV9732's own digital-gain block (`0x5184/0x5185`), upstream of
   the SoC. v0.13 was the first flash that could test this, because v0.12's lines hid the colour. It may need a second
   pass. A missing IR-cut filter on the donor module could limit how neutral it gets, and the blue gain would have to
   move to red if a later flip swapped the phase to RGGB.
6. Exposure / AE: not adaptive. Bright scenes clip and dim ones crush, because the firmware's auto-exposure loop still
   writes the SP140A's exposure registers. The OV9732's are `0x3500–0x3502` and `0x350A/0x350B`. Fixing this needs a
   second hook on the exposure/gain updater (~`0x7b340`), deferred past v0.13. (The `ov9732_exposurefw` `.rfw` the owner
   supplied is a UVC-webcam-bridge blob for a different chip and can't be applied to the GP SoC, but it may hold OV9732
   AE register hints worth mining.)
7. Flash safety: at this point the restore path went back to the public stock image, not to a backup of this unit's own
   flash. A real safety net means dumping this unit's own flash first.

---

## Verification done

- Both init-table analyses and three independent reviews, each trying to disprove them, re-checked the ctx struct, the
  reset table and the 61-write init table against `stock.bin` (sha256 `cc3b4b2f`); init table sha256 `aea3976b`.
- Disassembly confirmed the write side matches the read side (`0x2d6bc` sends more than one address byte when
  `ctx[7]=0x10`).
- The OV9732 register map, address, ID, MCLK and Bayer order were checked against the Ingenic T20 `ov9732.c` driver and
  the live register probe (0.10 apk).

---

## References

- Original-sensor driver: OpenIPC/Ingenic SP140A, `sp140a_t23.c`.
- Target-sensor driver: Ingenic T20 OV9732, `ov9732_t20.c`. This is the DVP variant, and this board needs a
  1-lane-MIPI init.
- Earlier analysis: [13-PHASE1-REPORT.md](13-PHASE1-REPORT.md), [FIRST-FLASH-INCIDENT.md](FIRST-FLASH-INCIDENT.md).
- OV9732 product brief: https://ovt.com/products/ov9732/

---

Reading order: 14 of 15 · previous: [Research report](13-PHASE1-REPORT.md) · next: [OV9732 exposure, colour and white balance](15-OV9732-AE-WB-FLASH-PORT.md) · [all docs](README.md)
