# OV9732 AE / WB / flash: verified port reference

Reading order: 15 of 15 · previous: [OV9732 repair scope](14-OV9732-REPAIR-SCOPE.md) · [all docs](README.md)

> **Status (2026-11-13): v0.25 (§11) is built on the factory OV9732 firmware read from the owner's second camera,
> with v0.24's Volume and LED rows; it is not yet flashed.** Earlier status (2026-11-12): **firmware v0.24 (§10) was flashed on the owner's camera on 2026-11-11; its Volume and LED rows
> work there, and Volume 8 is not yet tried. v0.24 carries v0.23's colour and bright-light exposure tuning (§9), which
> has not yet been measured on the camera.** Before that, the AE redirect in §2 was built, flashed and confirmed
> working on camera with firmware v0.21 (§7), and v0.22 (§8) fixed two defects the review found, crushed shadows (black
> level) and the exposure cap; it was flashed on 2026-11-10 and the shadows are fixed on camera.
> As-built offsets, bytes, and the on-camera result are in **§7**. The implemented hook is at **VA `0x7b378`** (file
> `0x7B578`) — not the `0x7b340` named in §2 below and in the earlier scope docs (docs/14, FIRST-FLASH-INCIDENT); §7
> explains why. The white-balance values of §4 are not used as they stand (v0.23 fits its own colour matrix, §9), and
> the flash lead of §5 is superseded by the LED row of §10.

Primary-source verification of a research corpus (peer-provided, independently checked 2026-11-10 by fetching each
source directly — K230 driver/XML, the GPCV2247F teardown blog, two AE-loop repos, chromacrank, plus a cross-pipeline
CCM/init check). Verdicts: driver math **confirmed with one correction**; CCM/AWB values **confirmed exact**; init
table **independently validated**; flash lead **downgraded**. The AE redirect below is now built and flashed (§7); the
WB and flash sections remain vetted data for the next firmware steps.

## 1. Actuator — OV9732 exposure/gain writes (K230 `ov9732_drv.c`, verbatim)

Source: `kendryte/k230_sdk` `src/big/mpp/kernel/sensor/src/ov9732_drv.c` (read byte-for-byte).

**Gain** (`set_again`):
```
again = round(gain * 16)            // 10-bit code
0x350a = (again & 0x0300) >> 8      // bits[9:8]
0x350b =  again & 0x00ff            // bits[7:0]
```
- `0x350b=0x10` → 1.0×, `0x20` → 2.0×. **0.19's `0x350B=0x20` is confirmed correct** (`0x350a=0x00` holds for any gain < 16×).
- Digital gain path writes **nothing** (commented out) — all real gain is analog via `0x350a/0x350b`.

**Exposure** (`set_intg_time`, linear):
```
exp_line = integration_time / one_line_exp_time ; clamp to [1, max_integration_line]
0x3501 = (exp_line >> 4) & 0xff
0x3502 = (exp_line << 4) & 0xff     // register unit = 1/16 line; driver uses whole lines (exp[3:0]=0)
```
- **Correction to the peer:** the driver writes **only `0x3501`/`0x3502`**, *not* `0x3500`. `0x3500` is init-only (`{0x3500,0x00}`), so this path caps at a 12-bit line count (4095 lines). To exceed that we must add `0x3500=(exp_line>>12)&0x0f` ourselves. With our VTS (< 4095) only the two registers are needed.
- No VTS clamp in the actuator — software clamps to `max_integration_line`; VTS is fixed in init. No group-hold/latch: writes are immediate, so update during vblank to avoid tearing.

## 2. AE redirect — reuse the stock brain (the real plan)

The GPCV2247F teardown ("Better Taste for a Tiny Camera", arjunsriva.com — **confirmed our exact SoC**, ARM7 GPCV2247F)
shows the stock firmware already runs a full AE engine every frame:
- Moves an index along a **program line** (~1/20-stop steps); each step = (integration time, analog gain, digital gain).
- Brightness target = a **13-step table scaling 205 → 47, default ~120**. Luma is **gamma-weighted** (128 packed gamma words → 1024 → every 4th → a 256-entry table) *before* the comparison.
- Writes the sensor as SP140A: **`FD` page-select; page-1 `03`/`04` = integration, `24` = analog gain** (bare hex, no `0x`).

On our camera that brain runs but its writes land on the dead SP140A registers. **The redirect = hook its register-write
tail (VA `0x7b378` as built — see §7; the `0x7b340` originally written here was a pre-implementation locate of the same
tail inside the updater at `0x7b2e4`) and translate the computed values to the OV9732:**

| stock (SP140A) | → OV9732 | translation |
|---|---|---|
| gain, page-1 reg `24` (Q4.4 = gain×16) | `0x350b` (+`0x350a`) | **~1:1**: `0x350b = reg24`, `0x350a = (code>>8)&0x03` (both encode gain×16) |
| integration `N_sp` lines, reg `03`/`04` | `0x3501`/`0x3502` | `N_ov = round(N_sp · K)`; `0x3501=(N_ov>>4)&0xff`, `0x3502=(N_ov<<4)&0xff`; clamp `[1, VTS_ov−margin]` |

`K` = SP140A line-time / OV9732 line-time (≈ `(HTS_sp/PCLK_sp)/(HTS_ov/PCLK_ov)`).

**Key robustness point:** the AE is a *closed loop* reading real luma from the ISP, so `K` need only be approximate and
monotonic — the loop drives `N_sp` until luma hits target, and whatever `K` maps that to on the OV9732, it self-corrects.
Start `K≈1`, refine only for convergence speed. The redirect therefore needs no precise line-time math and **no ported
AE loop**.

- **Assumption to confirm on hardware:** the GP ISP's luma statistics are computed on the OV9732 frames (very likely — the ISP stages are downstream and sensor-agnostic, same reason the CCM at `0xF7582` already applies). If those stats are dead, fall back to an external loop (below).
- **Fallback only if needed:** `open-tx-isp` (Ingenic T31) ships a readable clean-room AE scalar model (`tests/t31_ae_step_host_test.c settle_frames()`): 15-tap weighted-mean luma, ±1 deadband, asymmetric multiplicative step (`dc=154/1024`, `e0=51/1024`), EV→(integration,gain) split with a 50/60 Hz flicker floor. `esp_ipa`'s AGC is a **closed ESP32-P4 blob** (not portable source) — peer overstated it; only its parameter model is open.

## 3. Init table — independently validated

Cross-check (Ingenic T31 `themactep/ingenic-sdk` + K230) shows our `BRINGUP_STUB` init is the **canonical** OV9732
1280×720 init: the analog/private block (`0x3600,0x3601,0x3610,0x3612,0x3700–0x3704,0x3782,0x3014`, final
`0x3703=0x0b`/`0x3705=0x51`) is **byte-identical across two independent vendors**. Chip-ID `0x300a=0x97`,
`0x300b=0x32` → `0x9732`. Pipeline/clock regs (`0x5000`, `0x3800–0x380f` HTS/VTS) legitimately differ per platform and
are ours to set.

## 4. White balance / CCM (the secondary step)

K230 `ov9732-1280x720.xml` values **confirmed exact** (5 illuminants: A, D50, D65, F11/TL84, F12 — no CWF). D65:
- AWB gains `[R,G,G,B] = [2.02878, 1, 1, 1.4414]` (green-normalized).
- CCM row-major `[1.4296875, -0.296875, -0.1328125,  -0.1328125, 1.3671875, -0.234375,  0.0078125, -0.375, 1.3671875]`.
- BLS/black level `[64,64,64,64]` (one global vector, not per-illuminant; peer called it "BLC").

**Trust bounds (cross-pipeline: K230 vs BL808 `sipeed/M1s_BL808_SDK` vs GrainMedia OpenIPC):**
- **AWB gains — portable.** Converge ~10% at daylight / ~25% at warm illuminants. Target ≈ R/G 1.9–2.0, B/G 1.4–1.6 at D65; fine-tune for our IR-cut filter.
- **CCM — NOT drop-in.** Diagonal strength varies **25–45%**, off-diagonals up to ~2× across pipelines; representations differ (K230 float unity 1.0; BL808 int unity 512; GrainMedia is RGB→YCbCr, not an RGB CCM). A published CCM encodes a module's filter/lens/aesthetic, not a pure sensor property. Use K230's as a **structural template** (signs/shape, gray-preserving) and re-fit magnitude against the GP ISP's gamma/color space.

## 5. Flash (always-on) — weak lead, do not patch yet

Superseded: v0.24 found the two shutter calls that light the flash in dim light and puts them behind an LED setting;
see [§10](#10-v024-volume-and-led-rows-in-the-setup-menu). v0.24 does not use the lead below.

`Qooaga/chromacrank` `RESEARCH.md` (keyed to ROM `cc3b4b2f…` = our dump; uses the same `file = runtime + 0x200` rule):
- Verbatim: *"Candidate runtime address 0x1E844 selects a resource and sends a UI message"*, *"not a proven white capture-light driver; Flash Off is not implemented."*
- **Corrections:** `0x1E844` is a **runtime address** → **file offset `0x1EA44`** (not `0x1E844`). Low confidence, no GPIO/register evidence, behavior reads like UI/resource handling. **Treat as an unverified lead; confirm on hardware before any patch.**

## 6. Peer-claim corrections (caught by verification)

1. Exposure "`0x3500–0x3502`" → real driver uses only `0x3501`/`0x3502` (`0x3500` = unused top nibble < 4095 lines).
2. Flash "firmware offset `0x1E844`" → runtime address; file offset is `0x1EA44`.
3. `esp_ipa` "portable AE loop" → closed ESP32-P4 binary; not liftable source.
4. "TL84/CWF" → K230 has F11 (labeled TL84) only; no CWF profile.
5. D65 AWB/CCM and BLS values → **accurate** (rounded from exact binary fractions).

## 7. As-built (firmware v0.21) — what shipped, and what the camera showed

Implemented in `android/src/io/github/daemonddev/charmera/Candidates.java` (`AE_HOOK` / `AE_STUB`, applied by `build()`
for `Kind.BRINGUP`), reviewed against the disassembly and gated by a SHA-256 check so a wrong offset or byte cannot
produce a file the app will flash. Flashed and tested on the camera 2026-11-10.

**Hook + stub (exact, from the committed code):**
- `AE_HOOK_OFFSET = 0x7B578` (VA `0x7b378`) — the updater's register-write tail. The 4-byte `AE_HOOK` (`42 9b ff ea`)
  replaces the `cmp r0,r1` instruction there with a `b` into the stub.
- `AE_STUB_OFFSET = 0x62288` (VA `0x62088`) — a 174-byte routine placed in a region the firmware never calls (the dead
  SSV-wifi cave). It loads the firmware's own per-frame integration `A = [r4+8]` and gain `C = [r4+0xc]`, clamps them
  (`A ∈ [1, 0x310]`, gain code `∈ [0x10, 0xf0]`), and re-emits them to the OV9732 through the firmware's own
  sensor-write routine `SCCB_write` (VA `0x2d828`, OV9732 context, bus `0x6C`) as:
  - exposure `0x3501 = (A>>4) & 0xff`, `0x3502 = (A<<4) & 0xff` (register unit = 1/16 line);
  - gain `0x350b = (C>>4) & 0xff`, `0x350a = ((C>>4)>>8) & 3` (gain code = gain×16; `C>>4` is bit-exact to the
    firmware's own SP140A reg-`0x24` packer).

  It then branches back to the updater's epilogue, so exposure tracks the scene instead of holding the fixed init value.
  (These four register writes and the clamps were re-disassembled from the committed `AE_STUB` bytes and match this
  description exactly.)

**Why the hook point is `0x7b378`, not the plan's `0x7b340`:** the per-frame AE loop is reached by an *indirect*
callback — apply-task `0x65330` → fn-ptr `[BASE+0xEB8]` → wrapper `0x7b6e0` → tail-branch into the updater `0x7b2e4`.
A naive BL / word-reference scan of `0x7b2e4` finds only the one-time set-mode call and so reads as "no per-frame AE"
(an earlier mistake the verification pass caught). The live per-frame write tail — the `cmp r0,r1` — is at `0x7b378`.
**This is the first thing to re-check in any review: confirm `0x7b378` is on the live per-frame path via that indirect
callback, not a one-shot setup path.**

**Why a redirect at all:** the OV9732 has **no internal AEC** (manual-only; `0x3503` "auto" is Reserved, no `0x3a`
block) — every reference driver drives it with an external loop. Reusing the stock AE brain and translating its output
is therefore the only path; there is no sensor-side AEC to switch on.

**On-camera result (scene-matched against the stock camera, 2026-11-10):** the closed loop converges. A dim bottle+mug
scene that read luma ≈5 (black) under v0.19's static 2× gain now reads **L118 vs stock L132**; muffins L101 vs 114;
water-bottle L148 vs 143; real scenes average **L122 vs stock L130** (~6% under), exposure adapting scene-to-scene with
highlight clip ≤0.5% on bright real scenes. This settles the one point §2 left open — that the GP ISP computes its luma
statistics on the OV9732 frames (if it did not, the loop could not have converged).

**Not caused by this patch:** the flashed unit's louder/cracklier beeps and menu-navigation screen flashes predate
v0.21 — present since the first flash, when both cameras were still identical. A separate read-only forensics pass tied
them to the variant mismatch's cosmetic tail (a settings-default sound level, plus an unresolved per-unit clock/analog
interaction), not to this redirect. Do not read them as AE regressions.

## 8. v0.22 — two defects found in v0.21 by an independent review (flashed, shadows confirmed fixed)

**Corrections to §7.** The hook is live, but it runs per AE *step*, not per frame: the apply task calls it only when
the computed step is non-zero, at most once per 3 stats interrupts (the AE task's divider, `mov r1,#3` at `0x665d4`).
The "L5 → L118" comparison cannot be same-light: v0.21 can raise exposure at most 3.5× over v0.19 (784 lines at 7×
vs 784 at 2×), about 1.8 stops. The scene pairs do support convergence: in linear light, v0.21's 50th–90th
percentiles match the stock camera's within about ±5% in all three kitchen scenes. The "~6% under" mean was **not**
underexposure. It was crushed shadows: 6–29% of pixels sit at luma 2, against ≈0% on stock.

**Defect 1 — black level (the cause of the crushed shadows).** The GP ISP subtracts a fixed black level of 64
(10-bit). The SP140A profile at `0xF7118` (halfwords +8 = 1, +0xA = 0x40) is applied by `0x981bc` as
`[0xD0800018] = 0x8040`. The OV9732's own black-level target `{0x4002[1:0], 0x4003}` (datasheet table 4-4) defaults to
0x010 = 16, and v0.21 never set it, so the bottom 48 codes of every frame were clipped. On the calibration-card ramp,
v0.21 sits at luma 2 for the bottom 6–9% and then overtakes stock in the midtones (AE making up the lost mean). A
photometric fit through the firmware's own gamma table measured a 25–44-code offset, which agrees. Reference ISPs fit
this reading: BL808 subtracts `16 << 2` and K230/XiongMai "64" in 12-bit units, i.e. 16 at 10 bits. **Fix:** the
bring-up init now writes `0x4002=0x00`, `0x4003=0x40` before stream-on (auto BLC stays on: `0x4001=0xE0`, `0x5000`
bit 0). The stock ISP profile is untouched.

**Defect 2 — exposure cap.** AE_STUB clamped integration at `0x310` = 784, which was v0.19's static init exposure, not
a VTS limit. The active program line (`0xf5ae8`) snaps integration to anti-banding multiples of 240.5 lines
(241/481/722/962) and asks for 962 in rows 157–213. The clamp cut those rows to 784. That made exposure *fall* about
18% from row 156 to row 157 (non-monotonic until about row 163), which can stall or hunt the loop in dim light, and it
cost about 0.3 stop at the top. The OV9732 limit is VTS − 4 (Ingenic T20/T31 drivers). VTS is `0x3ce` = 974: the
init's doubled `0x380f` write is copied from Ingenic's T31 table and is harmless, because `0x380e` resets to `0x03`
(datasheet default VTS `0x0322`). **Fix:** clamp at `0x3c4` = 964 (`mov r1,#0x3c4`, `e3a01ff1`), which covers 962
and stays under 970.

**Also from the review (no change):** gain mapping is exact; the table tops out at 7×, so the 15× clamp never binds
and `0x350a` is always 0. Stub register use is safe, since r4–r6 are restored by the updater's own `pop`. The table's
digital-gain column is still applied ISP-side (`0xea0 → 0xe76 → 0x98988 → [0xd08000d4]`). The datasheet line time is
about 41.5 µs (HTS `0x5c6`, 25 fps) against the SP140A's 41.6 µs per table quantum, so K≈1 is close to exact *if*
the PLL matches Ingenic's (UNCONFIRMED). A lead for §5: the AE low-light flag `[0x1a1888]` gates `0x30df4(1,2)`
(MMIO `0xD0000050`) at capture.

**v0.22 file:** `JH_5408202611100022_0F061D17.bin`, SHA-256 `a0544813…fb2eb3cc`. It differs from v0.21 only in
the init table's tail and the clamp immediate, and from stock only inside the dead cave (`0x62121–0x62336`) and the
two hooks.

**On-camera result (flashed 2026-11-10, scene-matched against the stock camera):**
- **Black level fixed.** Pixels at the luma floor fell from 28.5% to 0.1% (muffins) and from 6.3% to 0.0% (jars). The
  oven behind the muffins is back with full detail.
- **True black still reaches the floor.** The 1st percentile is still 2 on the card shots, so the target of 64 does
  not lift blacks into a grey pedestal.
- **Midtones and highlights sit 3–10% above stock in linear light.** That is within the framing differences between
  the two cameras' shots.
- **Highlight clipping is slightly higher:** 4.1% vs 2.3% on the muffins scene, 0.4% vs 0.1% on the jars.
- **White balance under warm indoor light:**
  - White paper renders near neutral (R/G 1.05, B/G 0.99), where stock keeps a warm cast (B/G 0.84).
  - The tile wall shows the same pattern: B/G 0.92–0.96 on v0.22 vs 0.79–0.88 on stock.
  - The two paper shots were taken under the same light, so they do not yet show whether AWB tracks a change of
    illuminant. A daylight shot is still needed.

## 9. v0.23 — colour and bright-light exposure, fitted on a colour card (flashed as part of v0.24, not yet measured)

**Measurement.** A DGK Kolor Kard (white, 4 greys, black, 12 colours) was shot by both cameras, 3 frames each, in window
daylight and under a warm indoor lamp. Patches were sampled from hand-located card corners, with every sample box
checked on an overlay.
- **Daylight white balance already matches.** Greys are neutral on both cameras.
- **Under warm light, v0.22 neutralises fully** (white R/G 1.00, B/G 1.00), while the stock build keeps some warmth
  (1.04, 0.95). A fixed WB bias cannot reproduce that, because it would also warm daylight. Not changed here.
- **Colour:** v0.22 is clearly less saturated (chroma −10 to −21 on red, green, magenta and orange), and cyan and the
  skin tones are hue-shifted.
- **Tone is exposure, not a curve.** In daylight v0.22's mid and dark greys are lighter than stock's, but indoors the
  two match (white L 48.5 vs 51, mid-grey 29.5 vs 29.4).
  - A fitted contrast curve was rejected, because it would have darkened indoor shots (white 115 → 81).
  - One exposure factor of 0.76 (−0.40 EV), pushed through the camera's own gamma, reproduces all six stock daylight
    greys (mean ΔE 2.6).
  - So the original build aims about 0.4 EV lower in bright light.

**Which tables are live** (traced, then adversarially verified; the first pass misread the message dispatcher by one).
The dispatcher is `0x68D14`, and it indexes the jump table by `msg − 0x30000001`.
- Gamma `0xF7138` is loaded at boot into the CDSP gamma SRAM (`0xD0800800`). AE also meters through it (`ae+0x40C`).
  Do not edit it to change contrast: AE re-anchors.
- The Y LUT `0xF7E04` is live and does not feed AE.
- The CCM `0xF7582` is loaded at boot through msg `0x3000000D`. It is static.
- `0xF7338` and `0xF7F04` are dead.
- The base AE target is 120 (config byte `0xF5A04`). The 13-step EV table is at `0xF585C`, with a default index of 6.
- The per-band target table at `0xF7110` (bands come from the program-line row: 50 Hz thresholds 165/180/194/209) ships
  **disabled** (byte 0 = 0).

**Changes** (`Candidates.TUNE_OFFSETS`). The build differs from v0.22 in exactly these four places:

| What | File offset | Old → new |
|---|---|---|
| Colour matrix | `0xF7782` | `104,-23,-17 / -27,114,-22 / -1,-38,104` → `144,-54,-26 / -43,146,-39 / -8,-61,133` |
| AE per-band target table | `0xF7310` | `00 00 08 10 18 20 26 26` → `01 12 00 …` |
| Set-target handler (msg `0x1D`) | `0x69960` | 4 instructions |
| EV handler (msg `0x20`) | `0x69A1C` | 3 instructions |

- **Colour matrix:** rows sum to 64, so greys stay grey in any light. Fitted on daylight with the exposure model:
  colour ΔE 13.5 → 6.5. Indoor, which was not fitted, improves 10.1 → 7.7 and chroma 12.8 → 17.7 (stock 20.8).
  The skin tones remain the largest residuals (ΔE 11–15).
- **AE per-band table:** enabled, with −18 for the brightest band only. Target 120 → 102 is −0.40 EV through
  `0xF7138` when the program-line row is below 165, about 1/25 s at 2.2× gain. Dimmer bands are unchanged.
- **The two handlers** now call the band-aware setter `0x66C28` instead of `0x93498`, so the offset survives the
  power-on and EV messages.
- **The table and the handler changes only work together.** Without the enabled table, `0x66C28` returns early and no
  target is set.

**Evidence the indoor shots stay unchanged.** The indoor mid-grey grain is 4.7× daylight on both cameras, and the
mid-grey sits at 72, far below target. AE was at maximum exposure there, in the dim bands.

**Expected on camera:**
- Daylight is about 0.4 EV darker, with mid and dark greys close to stock.
- Colours are more saturated in both lights.
- Indoor exposure and white balance are unchanged.

**Caveats:**
- AE has a dead band of about ±0.16 EV, so compare several frames.
- Clipped skies can pull daylight more than 0.4 EV darker.
- Bright indoor scenes below row 165 also get −0.4 EV.

**v0.23 file:** `JH_5408202611110023_0F061F28.bin`, SHA-256 `f0cced2e…572507b2`.

## 10. v0.24: Volume and LED rows in the setup menu

v0.24 is v0.23 plus two rows after FORMAT in the setup menu. Page 1 is unchanged; page 2 shows FORMAT, Volume and
LED. The rows show their label; the value shows in the list that opens on OK, with the cursor on the current value.
Photos of the stock menus: [The camera's buttons and screens](CAMERA-UI.md).

- **Volume** is the SDK's own Volume item (string ID 0x3A, handler 0x1966C), already in the firmware but not in the
  Charmera's list. It sets record +0x19 (0..8, default 5) and the speaker gain. The list now offers 0..8 (stock code
  offered 0..7). Choosing a value also sets playback volume (+0x05) to the same level, and 0 mutes every sound.
- **LED** turns the automatic flash light Off, or leaves it On (the stock behaviour: it lights only in dim light). It
  is stored in record +0x60, which no stock code uses and which is 0 in every stock record and in the defaults
  template, so 0 = On and a fall-back to defaults gives On. The two shutter calls that light it in dim light
  (VA 0x1DEF8, 0x1DF54: `blne 0x30DF4` with r0=1, r1=2) now call a check that goes on to 0x30DF4 only when +0x60 is
  0. The five calls that switch it off (0x1D5C8, 0x1D7B0, 0x1E008, 0x2137C, 0x360B8) are untouched, so it cannot
  stay lit. The label is the firmware's existing "LED" text; the values are its "Off" and "On".

| VA | Change |
|---|---|
| 0xFFBF9 | setup list gets 0x3A (Volume) and 0x3F (LED) after FORMAT (the 10th byte stays 0) |
| 0x16E10, 0x16E34 | list count 7 → 9 |
| 0x174C0, 0x174D8, 0x17574, 0x17634, 0x177FC | page drawer 0x173EC decides by the absolute row (r7), not the rows on the page or the row on the page: first-row x, scroll bar, date-format red, camera/video icons, footer |
| 0x17758 | `bllo` a 5-instruction routine: a page's second row at x 0x28 on page 1, 0x12 after (flags restored) |
| 0x17964 | `bl` a 4-instruction routine: the cursor arrow's row taken mod 6 |
| 0x193C0 | ID 0x3F (LED) goes to a new handler case instead of the default |
| 0x19688 | Volume list count 8 → 9 |
| 0x1DEF8, 0x1DF54 | flash-on calls go through the LED check |
| 0x62184..0x6221F | the new code, in the dead ssv_wifi block 0x62136..0x62708 |

**How it was checked before it was flashed:**
- The builder checks v0.23's SHA-256 and every patch's original bytes, assembles with keystone and prints each patch
  back through capstone. A byte diff against v0.23 shows only these sites.
- Whole-image scans (ARM B/BL/BLX, Thumb BL/B, pointers with and without the Thumb bit, pc-relative loads and adds)
  find nothing outside 0x62136..0x62708 branching or pointing into the new code. The one live reference into that
  block is a literal at 0x6241C (an error-message pointer, used at 0x63028/0x63060); the new code ends before it, and
  any later use of the block must keep it.
- The new routines were run under the Unicorn emulator (35 checks: arrow rows 0..11, second-row x and flags, the LED
  check for +0x60 = 0/1/2/255, every handler mode). An independent review also ran the real drawer, cursor and OK
  paths emulated: page 1 draws as v0.23 in 36 combinations, page 2 draws FORMAT, Volume and LED at (18,4), (18,24),
  (18,44) with the OK footer, Up/Down wrap 8 ↔ 0, OK saves, RETURN cancels without a save.

**Left out: the settings-bank wrap fix.** The settings ring and the internal-photo directory (sector 120) share one
64 KiB erase block, so any fix leaves a moment where a power cut would wipe the directory, or needs moving storage
that photo code shares. See [Image layout](11-FORMAT.md).

**To test on the camera:**
1. Page 1 looks as before: icons, toggles, red date format, SET DATE, arrow, scroll bar.
2. Page 2: FORMAT, Volume, LED aligned on the left; the arrow follows; OK footer.
3. Up/Down through all nine rows and the wrap both ways; FORMAT's Cancel still works.
4. Volume 0..8 changes the beeps; 8 does not distort; 0 mutes; playback volume follows.
5. LED Off: no light in a dim shot. LED On: lights in dim light as before, not in bright light.
6. Both values survive switching off and on.

**On the camera (flashed 2026-11-11, owner's report):**
- Page 1 looks as before.
- The new rows work.
- Volume 0 mutes.
- LED Off stays dark in a dim shot; LED On lights only in dim light.
- Both values survive switching off and on.
- Not yet tried: Volume 8 (distortion).

**Image tuning measured on the camera (2026-11-12, v0.24 against the stock camera).** Both cameras shot the DKK card three
times outdoors and three times under a warm indoor lamp. Each patch is the median of a box placed from hand-read card
corners (scratchpad `patches3.py` / `analyze3.py`; not in the repo). The outdoor card was in blue light: both cameras'
greys came out blue.

| | v0.24 | stock |
|---|---|---|
| Outdoor grey cast (mean Lab a*, b* of the four greys) | +10.7, −39.9 (blue-magenta) | −7.6, −33.3 (blue-cyan) |
| Outdoor mid-grey luma | 158 | 174 (v0.24 about 0.3 EV darker; darker greys more) |
| Outdoor colour difference, raw / both balanced on grey | 20.0 / 8.3 ΔE76 | |
| Indoor grey cast | +2.3, +1.1 (neutral) | −2.7, +6.1 (keeps the lamp's warmth) |
| Indoor mid-grey luma | 153 | 152 |
| Indoor colour difference, raw / both balanced on grey | 9.0 / 5.1 ΔE76 | |

- **Shot-to-shot:** the mid grey varies by 2 levels or less on both cameras, so these are differences between the
  cameras, not noise.
- **Reading:**
  - Once white balance is taken out, colours are close to stock (5 to 8 ΔE).
  - What remains is the white balance itself: blue-magenta in blue daylight, where stock is blue-cyan. In warm
    light it is neutral, where stock stays warm.
  - Daylight is about 0.3 EV darker than stock. That is more than the −0.4 EV target in the brightest band was
    meant to give relative to v0.22.
- **Normal scenes in direct sun (owner's photos):** v0.24 holds highlight detail and colour on a brown and a black
  dog, where stock clips. This is judged by eye, not measured.
- **Decision (owner, 2026-11-12):** keep v0.24's neutral rendering of warm indoor light. Do not tune it back toward
  stock's warmth.

**v0.24 file:** `JH_5408202611120024_0F0613AC.bin`, SHA-256 `0919c5d8…85b1c69`.

## 11. v0.25: the factory OV9732 build with the Volume and LED rows (built 2026-11-13, not yet flashed)

The owner's second, never-flashed camera was read through its flash chip on 2026-11-13. It runs a build nobody has
published: the same stamp `202505201029`, source folder `v012`, compiled `Jan 12 2026`. That build drives the OV9732
natively. Of about 4,590 functions, 4,542 match stock v006 apart from where they sit in memory. The real changes are
these (VERIFIED from the bytes, each claim checked by a second, independent reading):

- **Sensor driver:**
  - Bus 0x6C with 16-bit register addresses.
  - A software reset, then a 167-write init table.
  - A per-frame exposure writer for `0x3502/0x3501/0x350B` that writes only when a value changes.
  - It has a chip-ID routine, but nothing calls it.
- **Calibration:**
  - AE target 110, the same in every brightness band.
  - Eight white-balance grey zones fitted to the OV9732 (stock has five, fitted to the SP140A).
  - New lens-shading tables, with the optical centre near (610, 270) instead of (640, 350).
  - A colour matrix close to stock's.
  - Chroma 35–50% higher.
  - An OV9732 profile for 60 Hz lighting.
- **No EXIF block:** still photos get a bare 0x270-byte header.
- **Shutter sound:** played before capture instead of after.

Measured against it, v0.21..v0.24 drive the sensor the same way: exposure lines 1:1, gain × 16, the 180° flip. They
differ in the calibration, which they could not reconstruct:

- the SP140A's white-balance zones and lens shading;
- a colour matrix about 40% stronger, compensating for those;
- an init table that leaves 116 registers at their defaults;
- `0x380F` written twice and `0x380E` never (INFERRED to be a typo).

**What v0.25 is:** the factory image, with only v0.24's Volume and LED patches, moved to the v012 addresses.

- **Moved patches:**
  - Setup-menu code moves by +0x18.
  - The LED-on routine is at 0x30E00.
  - The new code sits in the same dead ssv_wifi function, now at 0x62190.
  - The two low-light LED-on calls are at 0x1DF0C and 0x1DF68.
  - The settings record is at RAM 0x11059C.
- **Dropped:** none of v0.21..v0.23 is carried over (the bus redirect, init table, AE redirect, colour matrix and band
  target), because the factory build does all of that natively.
- **Builder:** `build-v025/build_v025.py`, kept with the private firmware, not in this repo. It checks every site's
  original bytes before it patches anything. It also blanks the two internal photo slots, so this camera's photos do
  not travel.
- **Checks:**
  - The Unicorn runs of the new code pass (35).
  - An independent review accounted for every changed byte (172) and found no defect.

**Checking and flashing.** This file is another camera's firmware with a change, so it goes through the donor route
with `--allow-modified`: `verify FILE --donor <the 2026-11-13 read> --allow-modified`. It passes with warnings and
nothing blocking. The camera it goes into is not opened for a read: it is the only working one, and its way back is
the v0.24 file it runs, flashed from a card. `stage FILE --donor <the 2026-11-13 read>` copies it to the card, with
the same warnings.

**Things to expect:**

- After the flash the camera starts with Volume 5 and LED On, which are the settings records in the read.
- Photos carry no EXIF date.
- The factory's white balance replaces v0.24's. The owner's 2026-11-12 choice to keep warm indoor light neutral was
  made on v0.24's balance. How the factory's balance renders warm light has not been measured.

**v0.25 file:** `JH_5408202611130025_0FA3BF37.bin`, SHA-256 `a5495ef4…bd1e3d0`.

## 12. v0.26: EXIF dates in still photos (built 2026-11-13, not yet flashed)

v0.26 is v0.25 with an EXIF block in every still photo, on the card and in internal memory.

**What it writes:**

- **Header:** 0x340 bytes. It holds SOI, then an APP1 written for this project, then the factory's own still template from
  SOF0 on (copied from the image, never shipped).
- **EXIF fields:**
  - Make `Kodak`, Model `Charmera`, Software `v0.26`;
  - DateTime, DateTimeOriginal and DateTimeDigitized from the camera clock, in `YYYY:MM:DD HH:MM:SS`.
- **Where the new code and template sit:** in a dead MMIO driver at VA 0x89440..0x897F4.
- **Date fill:**
  - It is one routine, entered in place of the card-check call at VA 0x6E188. It reuses the firmware's clock read
    (0x7D4) and date formatter (0xE868), then fixes the formatter's ':' between date and time to the space EXIF
    needs.
  - A clock that was never set gives 2025:01:01.
- **Header-size changes:** the SOF, DQT and DRI fills, the DCIM write size and the internal-slot writer all move to the
  new size.

**What is unchanged:** video and the playback re-encoder keep their bare headers.

**Checks:**

- 81 emulator tests pass.
- Pillow decodes a v0.26 photo pixel for pixel like the factory's, and reads the EXIF.
- Two independent reviews found no blocking issue.

**Not tested on the camera yet.** Check these after the flash:
- one shot with a card and one without;
- playing both back on the camera.

**Missing EXIF tags:** the optional-in-practice tags (resolution, colour space, pixel size) are absent.

The app builds v0.25 and v0.26 from the owner's own factory dump ([Android app](06-ANDROID.md)).

**v0.26 file:** `JH_5408202611130026_0FA38C7C.bin`, SHA-256 `617770c3…fa06d2`.

## Sources
- K230 driver: `raw.githubusercontent.com/kendryte/k230_sdk/main/src/big/mpp/kernel/sensor/src/ov9732_drv.c`
- K230 tuning XML: `…/src/big/mpp/userapps/src/sensor/config/ov9732-1280x720.xml`
- GPCV2247F teardown: `arjunsriva.com/posts/tiny-camera-better-taste/`
- AE loops: `opensensor/open-tx-isp` (`aperto`); `espressif/esp-video-components` (`esp_ipa`, closed blob)
- Flash lead: `github.com/Qooaga/chromacrank` `RESEARCH.md`
- Cross-check CCM/init: `sipeed/M1s_BL808_SDK`, OpenIPC GrainMedia `isp328_ov9732.cfg`, `themactep/ingenic-sdk` `t31/ov9732.c`

Reading order: 15 of 15 · previous: [OV9732 repair scope](14-OV9732-REPAIR-SCOPE.md) · [all docs](README.md)
