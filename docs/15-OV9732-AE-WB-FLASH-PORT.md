# OV9732 exposure, colour and white balance

Reading order: 15 of 15 · previous: [OV9732 repair scope](14-OV9732-REPAIR-SCOPE.md) · [all docs](README.md)

Status on 2026-11-13: v0.25 (§11) is built on the factory OV9732 firmware read from the owner's second camera, with
v0.24's Volume and LED rows, and v0.26 (§12) adds EXIF dates to it. Neither had been flashed at that date.

AE is auto-exposure and WB is white balance. v0.21 (§7) built and flashed the AE redirect of §2, and it was confirmed
working on the camera. v0.22 (§8) fixed two defects a review found in it, crushed shadows (black level) and the
exposure cap. It was flashed on 2026-11-10, and the shadows are fixed on the camera. v0.24 (§10) was flashed on the
owner's camera on 2026-11-11. Its Volume and LED rows work there, and Volume 8 has not been tried. It carries v0.23's
colour and bright-light exposure tuning (§9), which was measured against the stock camera on 2026-11-12 (end of §10).

The as-built offsets, bytes and on-camera result are in §7. The hook that shipped is at VA `0x7b378` (file `0x7B578`).
§2 below and the earlier scope docs (docs/14, FIRST-FLASH-INCIDENT) name `0x7b340`; §7 explains the difference. The
white-balance values of §4 are not used as they stand (v0.23 fits its own colour matrix, §9), and the LED row of §10
replaces the flash lead of §5.

This started as a check of research notes someone else had collected. On 2026-11-10 each source was fetched and checked
directly: the K230 driver and XML, the GPCV2247F teardown blog, two AE-loop repos, chromacrank, and a cross-pipeline
CCM and init check. The driver math is confirmed with one correction, the CCM and AWB values are exact, the init table
is independently validated, and the flash lead is downgraded.

## 1. Actuator: OV9732 exposure and gain writes (K230 `ov9732_drv.c`, verbatim)

Source: `kendryte/k230_sdk` `src/big/mpp/kernel/sensor/src/ov9732_drv.c` (read byte for byte).

Gain (`set_again`):
```
again = round(gain * 16)            // 10-bit code
0x350a = (again & 0x0300) >> 8      // bits[9:8]
0x350b =  again & 0x00ff            // bits[7:0]
```
- `0x350b=0x10` → 1.0×, `0x20` → 2.0×. So v0.19's `0x350B=0x20` is correct (`0x350a=0x00` holds for any gain under
  16×).
- The digital gain path writes nothing (it is commented out), so all real gain is analog, through `0x350a/0x350b`.

Exposure (`set_intg_time`, linear):
```
exp_line = integration_time / one_line_exp_time ; clamp to [1, max_integration_line]
0x3501 = (exp_line >> 4) & 0xff
0x3502 = (exp_line << 4) & 0xff     // register unit = 1/16 line; driver uses whole lines (exp[3:0]=0)
```
- A correction to the notes: the driver writes only `0x3501`/`0x3502`, not `0x3500`. `0x3500` is written only in init
  (`{0x3500,0x00}`), so this path caps at a 12-bit line count (4095 lines). Going past that would need
  `0x3500=(exp_line>>12)&0x0f` added. With our VTS (under 4095) the two registers are enough.
- The actuator has no VTS clamp: software clamps to `max_integration_line`, and VTS is fixed in init. There is no group
  hold or latch, so writes take effect at once. Update during vblank to avoid tearing.

## 2. AE redirect: reuse the stock brain

The GPCV2247F teardown ("Better Taste for a Tiny Camera", arjunsriva.com) is confirmed to be about our exact SoC, the
ARM7 GPCV2247F. It shows the stock firmware already runs a full AE engine every frame:
- It moves an index along a program line in steps of about 1/20 stop. Each step is (integration time, analog gain,
  digital gain).
- The brightness target comes from a 13-step table scaling 205 → 47, default about 120. Luma is gamma-weighted before
  the comparison (128 packed gamma words → 1024 → every 4th → a 256-entry table).
- It writes the sensor as an SP140A: `FD` page select, then page-1 `03`/`04` for integration and `24` for analog gain
  (bare hex, no `0x`).

On our camera that engine runs, but its writes land on SP140A registers that nothing answers. The redirect hooks its
register-write tail and translates the computed values for the OV9732. As built, the hook is at VA `0x7b378` (§7). The
`0x7b340` first written here was a locate of the same tail inside the updater at `0x7b2e4`, made before the
implementation.

| stock (SP140A) | → OV9732 | translation |
|---|---|---|
| gain, page-1 reg `24` (Q4.4 = gain×16) | `0x350b` (+`0x350a`) | about 1:1: `0x350b = reg24`, `0x350a = (code>>8)&0x03` (both encode gain×16) |
| integration `N_sp` lines, reg `03`/`04` | `0x3501`/`0x3502` | `N_ov = round(N_sp · K)`; `0x3501=(N_ov>>4)&0xff`, `0x3502=(N_ov<<4)&0xff`; clamp `[1, VTS_ov−margin]` |

`K` = SP140A line time / OV9732 line time (≈ `(HTS_sp/PCLK_sp)/(HTS_ov/PCLK_ov)`).

`K` only has to be roughly right and monotonic, because the AE is a closed loop reading real luma from the ISP. The
loop drives `N_sp` until luma reaches the target, and whatever `K` maps that to on the OV9732, the loop corrects for it.
Start at `K≈1` and refine it only for convergence speed. So the redirect needs no precise line-time math and no ported
AE loop.

- To confirm on hardware: that the GP ISP computes its luma statistics on the OV9732 frames. This is very likely,
  because the ISP stages are downstream and don't depend on the sensor (the same reason the CCM at `0xF7582` already
  applies). If those statistics are dead, fall back to an external loop (below). §7 confirms they are live.
- Fallback, only if needed: `open-tx-isp` (Ingenic T31) ships a readable clean-room AE scalar model
  (`tests/t31_ae_step_host_test.c settle_frames()`): 15-tap weighted-mean luma, ±1 deadband, an asymmetric
  multiplicative step (`dc=154/1024`, `e0=51/1024`), and an EV→(integration, gain) split with a 50/60 Hz flicker
  floor. `esp_ipa`'s AGC is a closed ESP32-P4 blob, not portable source. The notes overstated it. Only its parameter
  model is open.

## 3. Init table: independently validated

A cross-check against Ingenic T31 (`themactep/ingenic-sdk`) and K230 shows our `BRINGUP_STUB` init is the canonical
OV9732 1280×720 init. The analog/private block (`0x3600,0x3601,0x3610,0x3612,0x3700–0x3704,0x3782,0x3014`, final
`0x3703=0x0b`/`0x3705=0x51`) is byte-identical across two independent vendors. Chip ID: `0x300a=0x97`, `0x300b=0x32` →
`0x9732`. The pipeline and clock registers (`0x5000`, `0x3800–0x380f` HTS/VTS) legitimately differ per platform and are
ours to set.

## 4. White balance and CCM (the secondary step)

The K230 `ov9732-1280x720.xml` values are confirmed exact (5 illuminants: A, D50, D65, F11/TL84, F12; no CWF). D65:
- AWB gains `[R,G,G,B] = [2.02878, 1, 1, 1.4414]` (green-normalized).
- CCM row-major `[1.4296875, -0.296875, -0.1328125,  -0.1328125, 1.3671875, -0.234375,  0.0078125, -0.375, 1.3671875]`.
- BLS/black level `[64,64,64,64]` (one global vector, not per illuminant; the notes called it "BLC").

How far they carry over, comparing pipelines (K230, BL808 `sipeed/M1s_BL808_SDK`, GrainMedia OpenIPC):
- The AWB gains are portable. They agree within about 10% in daylight and about 25% under warm illuminants. Target
  ≈ R/G 1.9–2.0, B/G 1.4–1.6 at D65, then fine-tune for our IR-cut filter.
- The CCM is not drop-in. The diagonal strength varies by 25–45% across pipelines and the off-diagonals by up to about
  2×, and the representations differ (K230 float, unity 1.0; BL808 integer, unity 512; GrainMedia is RGB→YCbCr, not an
  RGB CCM). A published CCM encodes a module's filter, lens and look, not a pure sensor property. Use K230's as a
  structural template (signs and shape, gray-preserving) and re-fit the magnitude against the GP ISP's gamma and colour
  space.

## 5. Flash light: a weak lead (superseded)

Superseded: v0.24 found the two shutter calls that light the flash in dim light and put them behind an LED setting;
see [§10](#10-v024-volume-and-led-rows-in-the-setup-menu). v0.24 does not use the lead below.

`Qooaga/chromacrank` `RESEARCH.md` is keyed to ROM `cc3b4b2f…` (our dump) and uses the same `file = runtime + 0x200`
rule. Its own words: *"Candidate runtime address 0x1E844 selects a resource and sends a UI message"*, *"not a proven
white capture-light driver; Flash Off is not implemented."*

`0x1E844` is a runtime address, so the file offset is `0x1EA44`, not `0x1E844`. Confidence is low: there is no GPIO or
register evidence, and the code reads like UI or resource handling (the [Boot and power
notes](12-NOTES-boot-and-power.md) list file `0x1EA44` as the play-WAV-by-index routine). It was treated as an
unverified lead, to confirm on hardware before any patch.

## 6. Corrections to the research notes

1. Exposure "`0x3500–0x3502`": the real driver uses only `0x3501`/`0x3502` (`0x3500` is the unused top nibble below
   4095 lines).
2. Flash "firmware offset `0x1E844`": that is a runtime address; the file offset is `0x1EA44`.
3. `esp_ipa` "portable AE loop": a closed ESP32-P4 binary, not source that can be lifted.
4. "TL84/CWF": K230 has F11 (labelled TL84) only, and no CWF profile.
5. D65 AWB/CCM and BLS values: accurate (rounded from exact binary fractions).

## 7. As built (firmware v0.21): what shipped and what the camera showed

Implemented in `android/src/io/github/daemonddev/charmera/Candidates.java` (`AE_HOOK` / `AE_STUB`, applied by `build()`
for `Kind.BRINGUP`). It was reviewed against the disassembly and is gated by a SHA-256 check, so a wrong offset or byte
cannot produce a file the app will flash. It was flashed and tested on the camera on 2026-11-10.

The hook and stub, exactly as in the committed code:
- `AE_HOOK_OFFSET = 0x7B578` (VA `0x7b378`) is the updater's register-write tail. The 4-byte `AE_HOOK` (`42 9b ff ea`)
  replaces the `cmp r0,r1` instruction there with a `b` into the stub.
- `AE_STUB_OFFSET = 0x62288` (VA `0x62088`) is a 174-byte routine in a region the firmware never calls (the dead
  SSV-wifi cave). It loads the firmware's own per-frame integration `A = [r4+8]` and gain `C = [r4+0xc]`, clamps them
  (`A ∈ [1, 0x310]`, gain code `∈ [0x10, 0xf0]`), and writes them to the OV9732 through the firmware's own sensor-write
  routine `SCCB_write` (VA `0x2d828`, OV9732 context, bus `0x6C`) as:
  - exposure `0x3501 = (A>>4) & 0xff`, `0x3502 = (A<<4) & 0xff` (register unit = 1/16 line);
  - gain `0x350b = (C>>4) & 0xff`, `0x350a = ((C>>4)>>8) & 3` (gain code = gain×16; `C>>4` is bit-exact to the
    firmware's own SP140A reg-`0x24` packer).

  It then branches back to the updater's epilogue, so exposure follows the scene instead of holding the fixed init
  value. These four register writes and the clamps were re-disassembled from the committed `AE_STUB` bytes and match
  this description exactly.

Why the hook is at `0x7b378` and not the plan's `0x7b340`: the per-frame AE loop is reached through an indirect
callback, apply task `0x65330` → fn-ptr `[BASE+0xEB8]` → wrapper `0x7b6e0` → tail branch into the updater `0x7b2e4`. A
naive BL or word-reference scan of `0x7b2e4` finds only the one-time set-mode call, so it looks as if there is no
per-frame AE (an earlier mistake the verification pass caught). The live per-frame write tail, the `cmp r0,r1`, is at
`0x7b378`. Any review should check this first: confirm `0x7b378` is on the live per-frame path through that indirect
callback, not on a one-shot setup path.

Why a redirect at all: the OV9732 has no internal AEC. It is manual only (`0x3503` "auto" is Reserved, and there is no
`0x3a` block), and every reference driver drives it with an external loop. Reusing the stock AE and translating its
output is the only way. There's no sensor-side AEC to switch on.

On the camera (scene-matched against the stock camera, 2026-11-10) the closed loop converges. A dim bottle-and-mug scene
that read luma ≈5 (black) under v0.19's static 2× gain now reads L118 against stock's L132. Muffins read L101 against
114, and the water bottle L148 against 143. Real scenes average L122 against stock's L130 (about 6% under), exposure
adapts from scene to scene, and highlight clipping is 0.5% or less on bright real scenes. That settles the point §2 left
open: the GP ISP computes its luma statistics on the OV9732 frames, or the loop could not have converged.

This patch didn't cause the flashed unit's louder, cracklier beeps or the screen flashes during menu navigation. They
predate v0.21. They have been there since the first flash, when both cameras were still identical. A separate read-only
forensics pass tied them to the cosmetic tail of the variant mismatch (a settings-default sound level, plus an
unresolved per-unit clock/analog interaction), not to this redirect. Don't read them as AE regressions.

## 8. v0.22: two defects in v0.21 found by an independent review (flashed, shadows confirmed fixed)

First, corrections to §7. The hook is live, but it runs once per AE step, not once per frame: the apply task calls it
only when the computed step is non-zero, at most once per 3 stats interrupts (the AE task's divider, `mov r1,#3` at
`0x665d4`). The "L5 → L118" comparison can't be same-light: v0.21 can raise exposure at most 3.5× over v0.19 (784 lines
at 7× against 784 at 2×), about 1.8 stops. The scene pairs do support convergence: in linear light, v0.21's 50th–90th
percentiles match the stock camera's within about ±5% in all three kitchen scenes. The "~6% under" mean came from
crushed shadows, not underexposure: 6–29% of pixels sit at luma 2, against ≈0% on stock.

Defect 1 was the black level, and it caused the crushed shadows. The GP ISP subtracts a fixed black level of 64
(10-bit). The SP140A profile at `0xF7118` (halfwords +8 = 1, +0xA = 0x40) is applied by `0x981bc` as `[0xD0800018] =
0x8040`. The OV9732's own black-level target `{0x4002[1:0], 0x4003}` (datasheet table 4-4) defaults to 0x010 = 16, and
v0.21 never set it, so the bottom 48 codes of every frame were clipped. On the calibration-card ramp, v0.21 sits at
luma 2 for the bottom 6–9% and then overtakes stock in the midtones (AE making up the lost mean). A photometric fit
through the firmware's own gamma table measured a 25–44-code offset, which agrees. Reference ISPs fit this reading:
BL808 subtracts `16 << 2` and K230/XiongMai "64" in 12-bit units, i.e. 16 at 10 bits. The fix was to make the bring-up
init write `0x4002=0x00`, `0x4003=0x40` before stream-on (auto BLC stays on: `0x4001=0xE0`, `0x5000` bit 0). The stock
ISP profile is untouched.

Defect 2 was the exposure cap. AE_STUB clamped integration at `0x310` = 784, which was v0.19's static init exposure,
not a VTS limit. The active program line (`0xf5ae8`) snaps integration to anti-banding multiples of 240.5 lines
(241/481/722/962) and asks for 962 in rows 157–213. The clamp cut those rows to 784. That made exposure fall about 18%
from row 156 to row 157 (non-monotonic until about row 163), which can stall or hunt the loop in dim light, and it
cost about 0.3 stop at the top. The OV9732 limit is VTS − 4 (Ingenic T20/T31 drivers). VTS is `0x3ce` = 974: the
init's doubled `0x380f` write is copied from Ingenic's T31 table and is harmless, because `0x380e` resets to `0x03`
(datasheet default VTS `0x0322`). The fix was to clamp at `0x3c4` = 964 (`mov r1,#0x3c4`, `e3a01ff1`), which covers
962 and stays under 970.

The review also found, with no change needed: the gain mapping is exact, and the table tops out at 7×, so the 15× clamp
never binds and `0x350a` is always 0. The stub's register use is safe, since r4–r6 are restored by the updater's own
`pop`. The table's digital-gain column is still applied ISP-side (`0xea0 → 0xe76 → 0x98988 → [0xd08000d4]`). The
datasheet line time is about 41.5 µs (HTS `0x5c6`, 25 fps) against the SP140A's 41.6 µs per table quantum, so K≈1 is
close to exact if the PLL matches Ingenic's (UNCONFIRMED). A lead for §5: the AE low-light flag `[0x1a1888]` gates
`0x30df4(1,2)` (MMIO `0xD0000050`) at capture.

The v0.22 file is `JH_5408202611100022_0F061D17.bin`, SHA-256 `a0544813…fb2eb3cc`. It differs from v0.21 only in the
init table's tail and the clamp immediate, and from stock only inside the dead cave (`0x62121–0x62336`) and the two
hooks.

On the camera (flashed 2026-11-10, scene-matched against the stock camera):
- The black level is fixed. Pixels at the luma floor fell from 28.5% to 0.1% (muffins) and from 6.3% to 0.0% (jars).
  The oven behind the muffins is back with full detail.
- True black still reaches the floor. The 1st percentile is still 2 on the card shots, so the target of 64 does not
  lift blacks into a grey pedestal.
- Midtones and highlights sit 3–10% above stock in linear light, which is within the framing differences between the
  two cameras' shots.
- Highlight clipping is slightly higher: 4.1% against 2.3% on the muffins scene, 0.4% against 0.1% on the jars.
- White balance under warm indoor light:
  - White paper renders near neutral (R/G 1.05, B/G 0.99), where stock keeps a warm cast (B/G 0.84).
  - The tile wall shows the same pattern: B/G 0.92–0.96 on v0.22 against 0.79–0.88 on stock.
  - The two paper shots were taken under the same light, so they don't show yet whether AWB tracks a change of
    illuminant. A daylight shot is still needed.

## 9. v0.23: colour and bright-light exposure, fitted on a colour card (flashed as part of v0.24, measured in §10)

For the measurement, a DGK Kolor Kard (white, 4 greys, black, 12 colours) was shot by both cameras, 3 frames each, in
window daylight and under a warm indoor lamp. Patches were sampled from hand-located card corners, with every sample
box checked on an overlay.
- Daylight white balance already matches. Greys are neutral on both cameras.
- Under warm light, v0.22 neutralises fully (white R/G 1.00, B/G 1.00), while the stock build keeps some warmth (1.04,
  0.95). A fixed WB bias can't reproduce that, because it would also warm daylight. Not changed here.
- Colour: v0.22 is clearly less saturated (chroma −10 to −21 on red, green, magenta and orange), and cyan and the skin
  tones are hue-shifted.
- The tone difference is exposure, not a curve. In daylight v0.22's mid and dark greys are lighter than stock's, but
  indoors the two match (white L 48.5 against 51, mid-grey 29.5 against 29.4).
  - A fitted contrast curve was rejected, because it would have darkened indoor shots (white 115 → 81).
  - One exposure factor of 0.76 (−0.40 EV), pushed through the camera's own gamma, reproduces all six stock daylight
    greys (mean ΔE 2.6).
  - So the original build aims about 0.4 EV lower in bright light.

Which tables are live (traced, then checked by a second pass that tried to disprove it, which found the first pass had
misread the message dispatcher by one). The
dispatcher is `0x68D14`, and it indexes the jump table by `msg − 0x30000001`.
- Gamma `0xF7138` is loaded at boot into the CDSP gamma SRAM (`0xD0800800`). AE also meters through it (`ae+0x40C`).
  Don't edit it to change contrast: AE re-anchors.
- The Y LUT `0xF7E04` is live and does not feed AE.
- The CCM `0xF7582` is loaded at boot through msg `0x3000000D`. It is static.
- `0xF7338` and `0xF7F04` are dead.
- The base AE target is 120 (config byte `0xF5A04`). The 13-step EV table is at `0xF585C`, with a default index of 6.
- The per-band target table at `0xF7110` (bands come from the program-line row: 50 Hz thresholds 165/180/194/209)
  ships disabled (byte 0 = 0).

The changes (`Candidates.TUNE_OFFSETS`). The build differs from v0.22 in exactly these four places:

| What | File offset | Old → new |
|---|---|---|
| Colour matrix | `0xF7782` | `104,-23,-17 / -27,114,-22 / -1,-38,104` → `144,-54,-26 / -43,146,-39 / -8,-61,133` |
| AE per-band target table | `0xF7310` | `00 00 08 10 18 20 26 26` → `01 12 00 …` |
| Set-target handler (msg `0x1D`) | `0x69960` | 4 instructions |
| EV handler (msg `0x20`) | `0x69A1C` | 3 instructions |

- Colour matrix: the rows sum to 64, so greys stay grey in any light. It was fitted on daylight with the exposure
  model: colour ΔE 13.5 → 6.5. Indoor, which was not fitted, improves 10.1 → 7.7 and chroma 12.8 → 17.7 (stock 20.8).
  The skin tones remain the largest residuals (ΔE 11–15).
- AE per-band table: enabled, with −18 for the brightest band only. Target 120 → 102 is −0.40 EV through `0xF7138`
  when the program-line row is below 165, about 1/25 s at 2.2× gain. Dimmer bands are unchanged.
- The two handlers now call the band-aware setter `0x66C28` instead of `0x93498`, so the offset survives the power-on
  and EV messages.
- The table and the handler changes only work together. Without the enabled table, `0x66C28` returns early and no
  target is set.

Why indoor shots should stay unchanged: the indoor mid-grey grain is 4.7× daylight's on both cameras, and the mid grey
sits at 72, far below target. AE was at maximum exposure there, in the dim bands.

Expected on the camera:
- Daylight is about 0.4 EV darker, with mid and dark greys close to stock.
- Colours are more saturated in both lights.
- Indoor exposure and white balance are unchanged.

Caveats:
- AE has a dead band of about ±0.16 EV, so compare several frames.
- Clipped skies can pull daylight more than 0.4 EV darker.
- Bright indoor scenes below row 165 also get −0.4 EV.

The v0.23 file is `JH_5408202611110023_0F061F28.bin`, SHA-256 `f0cced2e…572507b2`.

## 10. v0.24: Volume and LED rows in the setup menu

v0.24 is v0.23 plus two rows after FORMAT in the setup menu. Page 1 is unchanged. Page 2 shows FORMAT, Volume and
LED. Each row shows its label, and its value shows in the list that opens on OK, with the cursor on the current value.
Photos of the stock menus: [The camera's buttons and screens](CAMERA-UI.md).

- Volume is the SDK's own Volume item (string ID 0x3A, handler 0x1966C), already in the firmware but not in the
  Charmera's list. It sets record +0x19 (0..8, default 5) and the speaker gain. The list now offers 0..8 (stock code
  offered 0..7). Choosing a value also sets playback volume (+0x05) to the same level, and 0 mutes every sound.
- LED turns the automatic flash light Off, or leaves it On (the stock behaviour: it lights only in dim light). It is
  stored in record +0x60, which no stock code uses and which is 0 in every stock record and in the defaults template,
  so 0 = On and a fall-back to defaults gives On. The two shutter calls that light it in dim light (VA 0x1DEF8,
  0x1DF54: `blne 0x30DF4` with r0=1, r1=2) now call a check that goes on to 0x30DF4 only when +0x60 is 0. The five
  calls that switch it off (0x1D5C8, 0x1D7B0, 0x1E008, 0x2137C, 0x360B8) are untouched, so it can't stay lit. The
  label is the firmware's existing "LED" text, and the values are its "Off" and "On".

| VA | Change |
|---|---|
| 0xFFBF9 | setup list gets 0x3A (Volume) and 0x3F (LED) after FORMAT (the 10th byte stays 0) |
| 0x16E10, 0x16E34 | list count 7 → 9 |
| 0x174C0, 0x174D8, 0x17574, 0x17634, 0x177FC | page drawer 0x173EC now decides by the absolute row number (r7) instead of the number of rows on the page or the row's position on it, for: first-row x, scroll bar, date-format red, camera/video icons, footer |
| 0x17758 | `bllo` a 5-instruction routine: a page's second row at x 0x28 on page 1, 0x12 after (flags restored) |
| 0x17964 | `bl` a 4-instruction routine: the cursor arrow's row taken mod 6 |
| 0x193C0 | ID 0x3F (LED) goes to a new handler case instead of the default |
| 0x19688 | Volume list count 8 → 9 |
| 0x1DEF8, 0x1DF54 | flash-on calls go through the LED check |
| 0x62184..0x6221F | the new code, in the dead ssv_wifi block 0x62136..0x62708 |

How it was checked before it was flashed:
- The builder checks v0.23's SHA-256 and every patch's original bytes, assembles with keystone and prints each patch
  back through capstone. A byte diff against v0.23 shows only these sites.
- Whole-image scans (ARM B/BL/BLX, Thumb BL/B, pointers with and without the Thumb bit, pc-relative loads and adds)
  find nothing outside 0x62136..0x62708 branching or pointing into the new code. The one live reference into that
  block is a literal at 0x6241C (an error-message pointer, used at 0x63028/0x63060). The new code ends before it, and
  any later use of the block must keep it.
- The new routines were run under the Unicorn emulator (35 checks: arrow rows 0..11, second-row x and flags, the LED
  check for +0x60 = 0/1/2/255, every handler mode). An independent review also ran the real drawer, cursor and OK
  paths emulated: page 1 draws as v0.23 in 36 combinations, page 2 draws FORMAT, Volume and LED at (18,4), (18,24),
  (18,44) with the OK footer, Up/Down wrap 8 ↔ 0, OK saves, and RETURN cancels without a save.

The settings-bank wrap fix was left out. The settings ring and the internal-photo directory (sector 120) share one
64 KiB erase block, so any fix either leaves a moment where a power cut would wipe the directory, or needs to move
storage that photo code shares. See [Image layout](11-FORMAT.md).

To test on the camera:
1. Page 1 looks as before: icons, toggles, red date format, SET DATE, arrow, scroll bar.
2. Page 2: FORMAT, Volume, LED aligned on the left; the arrow follows; OK footer.
3. Up/Down through all nine rows and the wrap both ways; FORMAT's Cancel still works.
4. Volume 0..8 changes the beeps; 8 does not distort; 0 mutes; playback volume follows.
5. LED Off: no light in a dim shot. LED On: lights in dim light as before, not in bright light.
6. Both values survive switching off and on.

The owner flashed it on 2026-11-11 and reported that page 1 looks as before, the new rows work, Volume 0 mutes, LED Off
stays dark in a dim shot and LED On lights only in dim light, and both values survive switching off and on. Volume 8
(distortion) has not been tried yet.

The image tuning was measured on the camera on 2026-11-12, v0.24 against the stock camera. Both cameras shot the DGK
card three times outdoors and three times under a warm indoor lamp. Each patch is the median of a box placed from
hand-read card corners (analysis scripts kept privately, not in the repo). The outdoor card was in blue light,
and both cameras' greys came out blue.

| | v0.24 | stock |
|---|---|---|
| Outdoor grey cast (mean Lab a*, b* of the four greys) | +10.7, −39.9 (blue-magenta) | −7.6, −33.3 (blue-cyan) |
| Outdoor mid-grey luma | 158 | 174 (v0.24 about 0.3 EV darker; darker greys more) |
| Outdoor colour difference, raw / both balanced on grey | 20.0 / 8.3 ΔE76 | |
| Indoor grey cast | +2.3, +1.1 (neutral) | −2.7, +6.1 (keeps the lamp's warmth) |
| Indoor mid-grey luma | 153 | 152 |
| Indoor colour difference, raw / both balanced on grey | 9.0 / 5.1 ΔE76 | |

Shot to shot, the mid grey varies by 2 levels or less on both cameras, so these are differences between the cameras,
not noise. Once white balance is taken out, colours are close to stock (5 to 8 ΔE). What's left is the white balance
itself: blue-magenta in blue daylight, where stock is blue-cyan, and neutral in warm light, where stock stays warm.
Daylight is about 0.3 EV darker than stock, which is more than the −0.4 EV target in the brightest band was meant to
give relative to v0.22.

On normal scenes in direct sun (the owner's photos), v0.24 holds highlight detail and colour on a brown and a black dog,
where stock clips. That is judged by eye, not measured.

The owner decided on 2026-11-12 to keep v0.24's neutral rendering of warm indoor light, and not to tune it back toward
stock's warmth.

The v0.24 file is `JH_5408202611120024_0F0613AC.bin`, SHA-256 `0919c5d8…85b1c69`.

## 11. v0.25: the factory OV9732 build with the Volume and LED rows

Built 2026-11-13. It hadn't been flashed when this section was written. It has since been flashed and works.

The owner's second camera, never flashed, was read through its flash chip on 2026-11-13. It runs a build nobody had
published then (it's public now as the cleaned v012 copy): the same stamp `202505201029`, source folder `v012`,
compiled `Jan 12 2026`. That build drives the OV9732 natively. Of about 4,590 functions, 4,542 match stock v006 apart
from where they sit in memory. These are the real changes (VERIFIED from the bytes, each claim checked by a second,
independent reading):

- Sensor driver:
  - Bus 0x6C with 16-bit register addresses.
  - A software reset, then a 167-write init table.
  - A per-frame exposure writer for `0x3502/0x3501/0x350B` that writes only when a value changes.
  - A chip-ID routine, which nothing calls.
- Calibration:
  - AE target 110, the same in every brightness band.
  - Eight white-balance grey zones fitted to the OV9732 (stock has five, fitted to the SP140A).
  - New lens-shading tables, with the optical centre near (610, 270) instead of (640, 350).
  - A colour matrix close to stock's.
  - Chroma 35–50% higher.
  - An OV9732 profile for 60 Hz lighting.
- No EXIF block: still photos get a bare 0x270-byte header.
- The shutter sound plays before capture instead of after.

Measured against it, v0.21..v0.24 drive the sensor the same way: exposure lines 1:1, gain × 16, the 180° flip. They
differ in the calibration, which they could not reconstruct:

- the SP140A's white-balance zones and lens shading;
- a colour matrix about 40% stronger, compensating for those;
- an init table that leaves 116 registers at their defaults;
- `0x380F` written twice and `0x380E` never (INFERRED to be a typo).

v0.25 is the factory image with only v0.24's Volume and LED patches, moved to the v012 addresses:

- The setup-menu code moves by +0x18.
- The LED-on routine is at 0x30E00.
- The new code sits in the same dead ssv_wifi function, now at 0x62190.
- The two low-light LED-on calls are at 0x1DF0C and 0x1DF68.
- The settings record is at RAM 0x11059C.

None of v0.21..v0.23 is carried over (the bus redirect, init table, AE redirect, colour matrix and band target),
because the factory build does all of that natively.

The builder, `build-v025/build_v025.py`, is kept with the private firmware, not in this repo. It checks every site's
original bytes before it patches anything, and it blanks the two internal photo slots so this camera's photos don't
travel. The Unicorn runs of the new code pass (35), and an independent review accounted for every changed byte (172) and
found no defect.

This file is another camera's firmware with a change, so on 2026-11-13 it went through the donor route:
`verify FILE --donor <the 2026-11-13 read> --allow-modified`. It passed with warnings and nothing blocking. The camera
it went into wasn't opened for a read, because it's the only working one. Its way back was the v0.24 file it ran,
flashed from a card. `stage FILE --donor <the 2026-11-13 read>` copied it to the card, with the same warnings.

That was the procedure before reviewed builds existed. Today this file, and the v0.25 that `charmera build --base v012
--mods volume-led --download` makes from the cleaned v012 copy (`JH_5408202611130025_0FB76FE9.bin`), are reviewed
files. `verify` and `stage` know them by SHA-256 and accept them without `--donor` and without the 2026-11-13 read,
with a warning when no backup confirms what the camera runs.

What to expect:

- After the flash the camera starts with Volume 5 and LED On, which are the settings records in the read.
- Photos carry no EXIF date.
- The factory's white balance replaces v0.24's. The owner's 2026-11-12 choice to keep warm indoor light neutral was
  made on v0.24's balance, and how the factory's balance renders warm light has not been measured.

The v0.25 file is `JH_5408202611130025_0FA3BF37.bin`, SHA-256 `a5495ef4…bd1e3d0`.

## 12. v0.26: EXIF dates in still photos

Built 2026-11-13. It hadn't been flashed when this section was written. It has since been flashed and works.

v0.26 is v0.25 with an EXIF block in every still photo, on the card and in internal memory.

What it writes:

- The header is 0x340 bytes: SOI, then an APP1 written for this project, then the factory's own still template from
  SOF0 on (copied from the image, never shipped).
- The EXIF fields are Make `Kodak`, Model `Charmera`, Software `v0.26`, and DateTime, DateTimeOriginal and
  DateTimeDigitized from the camera clock, in `YYYY:MM:DD HH:MM:SS`.
- The new code and template sit in a dead MMIO driver at VA 0x89440..0x897F4.
- The date fill is one routine, entered in place of the card-check call at VA 0x6E188. It reuses the firmware's clock
  read (0x7D4) and date formatter (0xE868), then replaces the formatter's ':' between date and time with the space
  EXIF needs. A clock that was never set gives 2025:01:01.
- The SOF, DQT and DRI fills, the DCIM write size and the internal-slot writer all move to the new header size.

Video and the playback re-encoder keep their bare headers.

81 emulator tests pass, Pillow decodes a v0.26 photo pixel for pixel like the factory's and reads the EXIF, and
two independent reviews found no blocking issue.

It had not been tested on the camera when this was written. After the flash, take one shot with a card and one without,
and play both back on the camera.

The tags that are optional in practice (resolution, colour space, pixel size) are left out.

The app builds v0.25 and v0.26 (and later v0.27) from the factory dump: the owner's read, or the public cleaned copy
([Using an Android phone](06-ANDROID.md#firmware-v025-the-cameras-own-factory-firmware)).

The v0.26 file is `JH_5408202611130026_0FA38C7C.bin`, SHA-256 `617770c3…fa06d2`.

## Sources

- K230 driver: `raw.githubusercontent.com/kendryte/k230_sdk/main/src/big/mpp/kernel/sensor/src/ov9732_drv.c`
- K230 tuning XML: `…/src/big/mpp/userapps/src/sensor/config/ov9732-1280x720.xml`
- GPCV2247F teardown: `arjunsriva.com/posts/tiny-camera-better-taste/`
- AE loops: `opensensor/open-tx-isp` (`aperto`); `espressif/esp-video-components` (`esp_ipa`, closed blob)
- Flash lead: `github.com/Qooaga/chromacrank` `RESEARCH.md`
- Cross-check CCM/init: `sipeed/M1s_BL808_SDK`, OpenIPC GrainMedia `isp328_ov9732.cfg`, `themactep/ingenic-sdk` `t31/ov9732.c`

---

Reading order: 15 of 15 · previous: [OV9732 repair scope](14-OV9732-REPAIR-SCOPE.md) · [all docs](README.md)
