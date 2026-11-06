# Flash candidates (original 2025 series only)

Reading order: 3 of 13 · previous: [Recovery](02-RECOVERY.md) · next: [Putting the file on the card](04-STAGE.md) · [all docs](README.md)

**Withdrawn after a hardware test.** Candidate C was flashed on an original-series camera. The update itself
completed and the camera boots, but it then stays on the mode menu: no mode can be chosen, and pressing the shutter
there froze it until it switched itself off. That happened with and without a card. C differs from the published
stock restore file below only in the settings bank (C keeps one record, the factory one; the restore file keeps the
16 records of a working camera, and the record the camera loads differs in bytes +0x03 and +0x53) and in the stored
photos. A and B use the same settings as C. Do not flash C, A or B. To get a camera back, flash the published stock
restore file.

**Stock restore (the recovery file).** `JH_5408202610261427_0F065D55.bin` from
https://github.com/benbadio/keychain-camera-plus (commit 209c25a, `release/stock/`), SHA-256
`b10a2f1c6302afbad8c205bd2f1e23c0ed244f6f3c553b513f2bd41f4b446906`. Several owners have flashed it with working
cameras. It is exactly the first 0x1E0000 bytes of the stock dump below, so the Charmera Card app makes it from
that dump and checks this hash.

Three update files built from the public original-series dump, reviewed statically and by an independent
pass. They are reproducible byte for byte from the recipe below, so they are not
committed: build them yourself from the upstream dump and compare the hashes.

Input: `charmera_stock_latest.bin` from https://github.com/benbadio/keychain-camera-plus (commit 209c25a),
SHA-256 `cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f`. The Project-Sirius dump gives the same
software region; its settings bank and photos differ, so the hashes below are for the keychain dump.

Before you flash any of these: an original 2025 series body (matte, yellow Kodak boot screen; a Millennium or
CLOT body must never receive these) and a fully charged battery. A verified full-flash backup of your own
camera (`backup`) is recommended, as in
[The first flash, step 1](01-FIRST-FLASH.md#1-back-up-the-stock-firmware-recommended-or-accept-the-public-stock-image);
that step also says what you accept by flashing without one.

All three erase the photos the camera stores in its own memory (up to two, taken with no card in), because
they are packed with `--blank-photos`; any file you pack with that option does the same. Only your own backup,
packed without `--blank-photos`, puts back the photos that were in the camera when you made it. If they matter
and you have no backup, get them off the camera before you flash.

| Candidate | What differs from stock | Update file | SHA-256 |
|---|---|---|---|
| A: settings-only mute | Settings bank holds one record: the factory record with UI sound 0 (CRC recomputed). Photo directory zeroed, photo slots erased. Code and resources byte-identical to stock. | `JH_5408202611050001_0FB74D14.bin` | `c4822cd7eed63169d80c5105811ab6492444793fafcc7a96e809869f2e5ccda7` |
| B: settings mute + silent WAVs | As A, plus the PCM of all five WAV resources replaced by silence (headers untouched). Survives a settings reset. | `JH_5408202611050002_0FB0BD3C.bin` | `32a7f54e5090050d099681c33378e3d23822a736279ea78f9f3442a45edc3979` |
| C: clean stock restore | Stock software, the factory settings record only, photo directory zeroed, photo slots erased. No other owner's settings or photos. | `JH_5408202611050003_0FB74C24.bin` | `9b8841d7b0674ca047a67b06594b92e67d17c387b6fab11f4b5191abc644c3ec` |

What the camera will do with each (VERIFIED from the firmware's loader and updater, not yet on hardware):

- The updater accepts the file (name sum matches), erases and programs blocks 0 to 29 ("Upgrade NN%" advances once per 64 KiB block), then erases block 31 (empty). At 100% the camera stays on its last screen; nothing reboots by itself, and the file is neither deleted nor renamed. Take the card out, then hold the power key about a second to switch the camera off.
- On the next boot the settings loader stops at sector 1 (erased) and loads sector 0; its CRC passes, and the camera runs with those values: UI sound 0 (A, B) or 5 (C), playback volume 5, auto-off code 2, language and date-format bytes as in the factory record. (Sector 120, the zeroed photo directory, is not erased either, but the loader never gets that far.) Two bytes differ from what the dumped camera was last running: +0x03 is 1 instead of 0 and +0x53 is 0 instead of 2; both values occur in other live records of the two dumps, so they are known-good states, and your own choices (frame/style selector) start from the factory state.
- The photo directory reads as two unused entries, so playback without a card shows nothing.
- A and B: the event-sound player returns before playing for every event (power on, shutter, keys, power off). B also has silent audio data if a later settings reset turns the gate back on.

Known limits:

- A settings-only mute is undone if the camera ever falls back to defaults: CRC failure, the stock bug after about 120 settings saves (a fresh bank like these has 119 free sectors before it; the published stock restore has 104), or the SDK's Sys.Reset menu item and network set-by-id paths, which this camera cannot reach. B does not have that weakness.
- The speaker amplifier is still switched on around the (now silent) sounds; whether that produces an audible click is unknown until tested.
- Recovery from any of these is the stock restore C, or your own backup.

## Recipe

```
python -m charmera_toolkit sounds mute charmera_stock_latest.bin --out A.bin --method settings --base-record first --i-understand-this-is-a-mod
python -m charmera_toolkit pack --input A.bin --out out/A --blank-photos --stamp 202611050001 --i-understand-this-file-flashes-a-camera

python -m charmera_toolkit sounds mute charmera_stock_latest.bin --out B.bin --method both --base-record first --i-understand-this-is-a-mod
python -m charmera_toolkit pack --input B.bin --out out/B --blank-photos --stamp 202611050002 --i-understand-this-file-flashes-a-camera

python -m charmera_toolkit pack --input charmera_stock_latest.bin --out out/C --factory-settings --blank-photos --stamp 202611050003 --i-understand-this-file-flashes-a-camera

python -m charmera_toolkit verify out/A/JH_5408202611050001_0FB74D14.bin --stock charmera_stock_latest.bin
python -m charmera_toolkit verify out/B/JH_5408202611050002_0FB0BD3C.bin --stock charmera_stock_latest.bin --allow-modified
python -m charmera_toolkit verify out/C/JH_5408202611050003_0FB74C24.bin --stock charmera_stock_latest.bin
```

B's verify needs `--allow-modified` because `verify` refuses any change to bytes 0..0x170000 (the code and the
resource pack) unless it is told the change is intended, and B changes three resource blocks. A and C leave
that region byte-identical to stock and pass without the flag.

The stamps are fixed so the names and hashes reproduce. Any other stamp except `202505201029` works too; only the
checksum part of the name must then be recomputed, which `pack` does.

## Byte-level diff against the stock dump (first 0x1E0000 bytes)

- A: block 0x170000 (7,681 bytes: byte +0x19 of record 0, its CRC, and records 1 to 15 erased to 0xFF; the second page of record 0 was already zero), blocks 0x180000 and 0x1A0000 (the two stored photos erased to 0xFF). The photo directory at 0x17F000 was already zero in this dump.
- B: as A plus blocks 0x120000, 0x150000, 0x160000 (only the PCM bytes inside the five WAVs; 5,182 + 686 + 10,459 bytes).
- C: block 0x170000 (records 1 to 15 erased), blocks 0x180000 and 0x1A0000 (photos erased).

## Flashing order, when you are ready

The full sequence, with backups, staging and undo, is [The first flash](01-FIRST-FLASH.md); this is the short form.

1. Recommended: `backup` your camera and keep the file ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)). Without a clip, `--stock` below is the public dump.
2. `verify` the candidate against your backup (`--stock <backup>`, plus `--allow-modified` for B); read every line.
3. A card the camera has formatted itself (exFAT) or a FAT32 card; either works for the update ([SD card problems](05-CARDS.md)). Reformatting erases the card, so copy its photos off first if you do. Only the candidate in the root (`stage` puts it there: [Putting the file on the card](04-STAGE.md)); eject the card before you take it out of the reader.
4. Original 2025 series body only; battery fully charged and the cable unplugged; the photos stored in the camera saved or given up (see above). Camera off, card in, power on. Wait for "Upgrade NN%" to reach 100%; the camera then stays on its last screen and does not reboot by itself. Take the card out, then hold the power key about a second to switch off. Delete the file from the card before it goes back into the camera.
5. Power on without the card. Expected: no power-on sound (A, B); the live view and the shutter work as before.
6. To revert: C, or `pack` your own backup.

---

Reading order: 3 of 13 · previous: [Recovery](02-RECOVERY.md) · next: [Putting the file on the card](04-STAGE.md) · [all docs](README.md)
