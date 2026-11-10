# OV9732 AE / WB / flash: verified port reference

Primary-source verification of a research corpus (peer-provided, independently checked 2026-11-10 by fetching each
source directly — K230 driver/XML, the GPCV2247F teardown blog, two AE-loop repos, chromacrank, plus a cross-pipeline
CCM/init check). Verdicts: driver math **confirmed with one correction**; CCM/AWB values **confirmed exact**; init
table **independently validated**; flash lead **downgraded**. This is a plan + vetted data for the next firmware steps,
not yet built or flashed.

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
tail (VA `0x7b340`, inside the updater at `0x7b2e4`) and translate the computed values to the OV9732:**

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

`Qooaga/chromacrank` `RESEARCH.md` (keyed to ROM `cc3b4b2f…` = our dump; uses the same `file = runtime + 0x200` rule):
- Verbatim: *"Candidate runtime address 0x1E844 selects a resource and sends a UI message"*, *"not a proven white capture-light driver; Flash Off is not implemented."*
- **Corrections:** `0x1E844` is a **runtime address** → **file offset `0x1EA44`** (not `0x1E844`). Low confidence, no GPIO/register evidence, behavior reads like UI/resource handling. **Treat as an unverified lead; confirm on hardware before any patch.**

## 6. Peer-claim corrections (caught by verification)

1. Exposure "`0x3500–0x3502`" → real driver uses only `0x3501`/`0x3502` (`0x3500` = unused top nibble < 4095 lines).
2. Flash "firmware offset `0x1E844`" → runtime address; file offset is `0x1EA44`.
3. `esp_ipa` "portable AE loop" → closed ESP32-P4 binary; not liftable source.
4. "TL84/CWF" → K230 has F11 (labeled TL84) only; no CWF profile.
5. D65 AWB/CCM and BLS values → **accurate** (rounded from exact binary fractions).

## Sources
- K230 driver: `raw.githubusercontent.com/kendryte/k230_sdk/main/src/big/mpp/kernel/sensor/src/ov9732_drv.c`
- K230 tuning XML: `…/src/big/mpp/userapps/src/sensor/config/ov9732-1280x720.xml`
- GPCV2247F teardown: `arjunsriva.com/posts/tiny-camera-better-taste/`
- AE loops: `opensensor/open-tx-isp` (`aperto`); `espressif/esp-video-components` (`esp_ipa`, closed blob)
- Flash lead: `github.com/Qooaga/chromacrank` `RESEARCH.md`
- Cross-check CCM/init: `sipeed/M1s_BL808_SDK`, OpenIPC GrainMedia `isp328_ov9732.cfg`, `themactep/ingenic-sdk` `t31/ov9732.c`
