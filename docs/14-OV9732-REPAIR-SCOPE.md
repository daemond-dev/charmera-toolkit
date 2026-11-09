# 14 — OV9732 Repair Scope

**Status:** scoping complete; bring-up build in progress.
**Date:** 2026-11-08.
**Stock image under analysis:** `stock.bin`, sha256 `cc3b4b2f…` (the public dump). All file offsets below use `file_offset = VA + 0x200`.

---

## Summary

The Charmera's photo path is dead because of a **sensor mismatch**, not a fault:

- The **stock firmware was built to drive a SuperPix SP140A** (1 MP, 1280×720, 1-lane MIPI, 10-bit RAW), over bit-banged SCCB at **8-bit register addressing, bus 0x78** (7-bit 0x3C), with `0xFD` paged register banks.
- The **hardware actually carries an OmniVision OV9732** (same class: 1 MP, 720p, 1-lane MIPI, 10-bit RAW), which answers at **16-bit register addressing, bus 0x6C** (7-bit 0x36), chip ID `0x9732` at registers `0x300A`/`0x300B`.

The firmware never speaks to the sensor that is present, so the sensor is never initialized and never streams. The repair is to **redirect the firmware's sensor control channel to the OV9732 and give it an OV9732 init sequence** — the MIPI data path (D-PHY → ISP → hardware MJPEG) is shared between the two parts and should carry over unchanged.

---

## The two sensors

| | **SuperPix SP140A** (firmware's target) | **OmniVision OV9732** (installed) |
|---|---|---|
| Resolution | 1 MP, 1280×720 | 1 MP, 1280×720 — same |
| Output | 10-bit RAW, 1-lane MIPI CSI-2 | 10-bit RAW, 1-lane MIPI CSI-2 — same |
| Optical format | ~1/4.5" | 1/4" (larger) |
| Pixel size | smaller (undocumented) | 3.0 µm |
| Low-light | not published | 2.066 V/lux-s, 39 dB SNR, 72 dB DR |
| SCCB control | 8-bit regs, bus 0x78, `0xFD` paging | 16-bit regs, bus 0x6C |
| Chip ID | `0x140A` @ page-0 regs `0x02`/`0x03` | `0x9732` @ regs `0x300A`/`0x300B` |
| Docs / support | obscure, no public datasheet | public datasheet + many open drivers |

### How the original sensor was identified

The firmware reads **no** chip ID (see root cause below), so identity rests on the init-table fingerprint. The recovered 61-write init table matches the public OpenIPC/Ingenic **SP140A** driver on **51 of 61 register writes, byte-for-byte**, in the same `0xFD`-paged page-0/1/2 structure; the runtime exposure/gain code is a semantic match too. Adversarial cross-check excluded the only same-family rival (OmniVision OV2735) at 6/36 (16%). Confidence: SuperPix SP-series **certain**; SP140A specifically **very high** (software fingerprint — lens glued, no die read possible).

### Is the OV9732 "better"?

Same class — **not a resolution upgrade** (both 720p/1 MP, 10-bit RAW, 1-lane MIPI). The OV9732 wins on **light-gathering** (larger 1/4" format, 3.0 µm pixels → better low-light/SNR) and, enormously, on **serviceability** (mainstream, fully documented, many open drivers). So: modestly better pictures, same resolution, a far better part to build firmware against.

---

## Root cause: blind, detect-less bring-up

The firmware performs **no sensor detection**. The entire SCCB *read* path is dead code — the read routine `0x2d9b8` has one caller (wrapper `0x2d850`), and that wrapper has zero callers anywhere in the 2 MB image. Sensor bring-up is **write-only and blind**:

```
reset/preamble table → ~20 ms delay → 61-write init table → stream on
```

So when the hardware changed from SP140A to OV9732, the firmware kept blindly writing SP140A registers to bus `0x78`. The OV9732 (bus `0x6C`, 16-bit) never heard them; every write NACKed, was retried 10× and abandoned, and the error was ignored. No detection meant no failure was ever surfaced.

---

## Firmware sensor-driver map (verified against `stock.bin`)

| Element | VA | file | notes |
|---|---|---|---|
| SCCB ctx struct | `0x1083A4` | `0x1085A4` | `11 00 12 00 00 00 00 08 08 78` — SCL=GPIO17, SDA=GPIO18, regbits=`08`, databits=`08`, busaddr=`78` |
| SCCB read primitive | `0x2d9b8` | `0x2dbb8` | width-agnostic: sends `(ctx[7]&~7)/8` addr bytes MSB-first |
| SCCB write primitive | `0x2d6bc` | `0x2d8bc` | mirror of read; same width-agnostic loop |
| write wrapper(ctx,reg,val) | `0x2d828` | `0x2da28` | |
| read wrapper | `0x2d850` | `0x2da50` | **dead code** — repurpose for an ID check |
| `sensor_write(reg,val)` thunk | `0x7b6d0` | `0x7b8d0` | loads ctx ptr, tail-calls `0x2d828` |
| table applier | `0x7b28c` | `0x7b48c` | reads `{reg8,val8}`, 10 retries/reg, `FF FF` terminator |
| reset/preamble table | `0xF59FC` | `0xF5BFC` | `fd 00 20 00 ff ff` (2 writes) |
| main init table | `0xF5A6C` | `0xF5C6C` | 61 writes + `ff ff`, 124 bytes, sha256 `aea3976b…` |
| bring-up / SET-MODE | `0x7b5a0` | `0x7b7a0` | programs capture path; applies reset table @`0x7b688`, delay, init table @`0x7b698` |
| **init-table apply call** | `0x7b698` | `0x7b898` | the injection point (the 0.10 probe already hooks here) |
| exposure/gain updater | `~0x7b340` | `~0x7b540` | SP140A `0xFD` page + `0x04`/`0x03` expo + `0x24` gain + `0x01` latch |
| sensor object / vtable | `0xf70cc` | `0xf72cc` | ifmode byte[0x24]=3; width[0x34]=`0x500`=1280; height[0x36]=`0x2d0`=720 |
| 2-entry sensor-profile table | `0x7b468` | `0x7b668` | key `0x32`→SP140A (active); key `0x3c`→id `0x9b` table `0xf64f0` (looks like a GC0308) |
| capture / MIPI engine | — | MMIO `0xD0800000` | reset strobe `+0x17c`, mode `+0x228`, enable/DMA `+0x600/0x610/0x63c/0x664`; likely D-PHY block ~`0xD0810000` |
| clock / PMU | — | MMIO `0xD0000000` | MCLK sel/div `+0x08` (helper `0x976c4`); gates `+0x0C`, `+0x58` |
| ISP / encode | — | — | Comi AE/AWB lib (`0x97348`), demosaic + H/V mirror (`0xf80b6`), hardware MJPEG (`Disp_Mjpeg_Init` `0x104c8`) |

There is **no code-controlled sensor reset / power-down GPIO** — the only sensor-path GPIOs are the bit-banged SCCB pair (17/18). Sensor reset is via regulator power-on-reset or the capture-engine strobe.

---

## Repair plan

The two sensors share interface (1-lane MIPI), format (RAW10), and resolution (720p), so the **capture engine, ISP, and MJPEG stages should not need changes**. Work is concentrated on the SCCB control channel and the init sequence.

### 1. Control plane (small, well-understood)

Patch the ctx struct at file `0x1085A4`:
- `byte[7] 0x08 → 0x10` (16-bit register address)
- `byte[8] = 0x08` (keep — 8-bit data)
- `byte[9] 0x78 → 0x6C` (OV9732 at 7-bit 0x36)

Both SCCB primitives already honor `ctx[7]` as a bit count and emit the address MSB-first, so **16-bit addressing needs no primitive code change** — confirmed by disassembly of both `0x2d9b8` (read) and `0x2d6bc` (write).

### 2. Data plane (the main work)

Replace the SP140A init with an **OV9732 1-lane-MIPI RAW10 720p** init sequence. The OV9732 uses 16-bit register addresses, so each entry is 3 bytes `{reg_hi, reg_lo, val}` instead of SP140A's 2-byte `{reg8, val8}`. Two options:
- **(a) In-place:** widen the table applier `0x7b28c` to read 3-byte entries (change its `ldrb`/`add #2` to a 2-byte reg read + `add #3`) and replace the tables at `0xF5BFC`/`0xF5C6C`.
- **(b) Injected (preferred first attempt):** hook the init-table apply call at `0x7b698` (as the 0.10 probe already does) and run an injected OV9732 bring-up stub that walks an embedded 16-bit-reg table through the firmware's own write primitive, then streams on (`0x0100 = 0x01`). This reuses the proven probe/stub + apk workflow and leaves the stock applier untouched.

### 3. Runtime exposure/gain

Rewrite the updater at `~0x7b340`: SP140A's `0xFD`-paged `0x04`/`0x03` (exposure) and `0x24` (gain) → OV9732's `0x3500`/`0x3501`/`0x3502` (exposure, 3 bytes) and `0x350A`/`0x350B` (gain), no paging. (Not required for a first "does it stream" test; required for correct AE.)

### 4. Recommended: add a real ID check

Repurpose the dead read path (wrapper `0x2d850`) to read `0x300A`/`0x300B` and confirm `0x9732` before applying the init — so bring-up verifies the sensor instead of writing blind. This directly fixes the class of bug that caused the failure.

---

## Open risks / unverified (cannot be resolved from the binary alone)

**Resolved by the 0.11 flash (2026-11-09):** the bring-up produced real frames — three 1440×1080 JPEGs and a 14 MB AVI — so risks 1, 2 and 3 below are **confirmed OK**: the OV9732 streams 1-lane MIPI, the SoC's capture/ISP/MJPEG path receives it unchanged, the module is wired to the flex, and the MCLK is in range. The "deepest unknown" did not bite.

1. ~~**MIPI D-PHY / timing**~~ — **RESOLVED**: frames received on the first bring-up; the OV9732's MIPI timing matches the SP140A-configured receiver with no engine reconfiguration.
2. ~~**Module wiring**~~ — **RESOLVED**: the installed OV9732 is the MIPI-output variant on the flex (it streams).
3. ~~**MCLK**~~ — **RESOLVED**: the emitted clock drives the OV9732 fine.
4. **Bayer / CFA order and mirror/flip phase** — being tuned empirically. The first frames came out **rotated 180°** (module mounted rotated vs the SP140A); 0.12 sets `0x3820=0x16`/`0x3821=0x06` to flip both axes with the Bayer order preserved (array bit1 + ISP bit2). The color was a smooth cast, not a Bayer maze, so the demosaic phase is likely already correct — the flip must not disturb it.
5. **White balance** — the should-be-neutral bright regions measured **R≈G, B low by 1.2–1.5×** across three scenes: a consistent **blue deficit**, i.e. the SoC WB is fixed for the SP140A's spectral response (an auto-WB would vary by scene). 0.12 lifts blue ~1.31× in the OV9732's own digital-gain block (`0x5184/0x5185`), upstream of the SoC. May need a second pass, and a missing IR-cut filter on the donor module could cap how neutral it gets.
6. **Exposure / AE** — not adaptive: bright scenes clip, dim ones crush, because the firmware's auto-exposure loop still writes **SP140A** exposure registers, not the OV9732's (`0x3500–0x3502`, `0x350A/0x350B`). Fixing this needs a second hook on the exposure/gain updater (~`0x7b340`) — deferred past 0.12.
7. **Flash safety** — the restore path reverts to the **public** stock image, not a backup of this specific unit's flash. A true safety net means dumping this unit's own flash first.

---

## Verification done

- Both init-table analyses and three adversarial agents independently re-verified the ctx struct, reset table, and 61-write init table against `stock.bin` (sha256 `cc3b4b2f`); init table sha256 `aea3976b`.
- Write-side symmetry (that `0x2d6bc` emits multiple address bytes when `ctx[7]=0x10`) confirmed by disassembly.
- OV9732 register map / address / ID / MCLK / Bayer confirmed against the Ingenic T20 `ov9732.c` driver and the live register probe (0.10 apk).

---

## References

- Original-sensor driver: OpenIPC/Ingenic SP140A — `sp140a_t23.c` (scratchpad `probe5/`).
- Target-sensor driver: Ingenic T20 OV9732 — `ov9732_t20.c` (scratchpad). *Note: this is the DVP variant; a 1-lane-MIPI init must be used for this board.*
- Prior analysis: [13-PHASE1-REPORT.md](13-PHASE1-REPORT.md), [FIRST-FLASH-INCIDENT.md](FIRST-FLASH-INCIDENT.md).
- OV9732 product brief: https://ovt.com/products/ov9732/
