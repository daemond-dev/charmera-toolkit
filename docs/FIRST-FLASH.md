# The first flash, end to end

The order of operations for putting a modified firmware on a Charmera for the first time, with the point of
no return marked. Every step refers to a longer document; read those before the step, not during it.

Only the original 2025 series is covered (matte body, yellow Kodak boot screen). A Millennium Edition or a CLOT
edition must not be flashed with any file from this toolkit: see docs/RECOVERY.md section D.

## 0. Decide what you can afford to lose

Two ways to get a camera back after a bad flash exist. The SD updater needs a camera that still boots far
enough to read the card. The CH341A programmer needs the camera opened and a clip on the flash chip, and it
works whatever the firmware does. Nobody has yet published a recovery from a camera that does not boot
without opening it.

So the question is: are you willing to open the camera if the first try goes wrong? If not, stop at step 2
and keep the stock files; the risk of the mods below is small but it is not zero, and the first person to
try each of them is you.

## 1. Back up the stock firmware (recommended) or accept the public stock image

With a CH341A clip (docs/RECOVERY.md section B):

```
charmera probe
charmera backup --out backups/
charmera inspect backups/<file>.bin
```

`backup` reads the chip twice and keeps the file only when the reads agree. `inspect` must report
`series: original-2025` and `check software_sha256 yes`. That file is your way back.

Without a clip, the published stock dump (hash in docs/PHASE1-REPORT.md, reproduced by `pack` from the
published restore file) is identical to every original-series camera read so far in the first 0x170000 bytes.
Your settings and your two internally stored photos are the only things that differ, and the candidates below
blank those anyway. The risk you accept is that your camera is the first one whose firmware differs.

## 2. Pick the file

docs/FLASH-CANDIDATES.md lists the three reviewed files, in the order to try them:

- C: stock, settings reset to factory, photos blanked. Changes nothing in the code or resources. Try this
  first if you only want to know that the updater path works on your camera.
- A: C plus UI sounds set to 0 in the settings record. Fully reversible by flashing C.
- B: A plus silenced sound data in the resource pack, for the case where the settings are ever reset.

Build your own instead with `sounds`, `settings`, `frames`, `resources` or `tweak`, then `pack` it. Each of
those commands prints what it will change and refuses to write until you pass its gate flag.

## 3. Verify

```
charmera verify out/JH_5408<stamp>_<sum>.bin --stock <your backup or the stock dump>
charmera diff <your backup> out/JH_5408<stamp>_<sum>.bin
```

`verify` fails closed. A FAIL line means the file does not go on a card. `diff` shows every region that
differs from your backup, so you can confirm the change is the one you intended and nothing else moved.

## 4. Put it on the card

`stage` copies the file to the card root, after running `verify` again, refusing a card that already holds
another update file, and reading the copy back to compare hashes. It asks you to type the card path before
it writes.

```
charmera stage out/JH_5408<stamp>_<sum>.bin --card E:          # Windows
charmera stage out/JH_5408<stamp>_<sum>.bin --card /run/media/deck/<label>   # Steam Deck
```

Use a card the camera has formatted itself, or a FAT32 card (docs/CARDS.md). Take the card out of the
reader only after `stage` reports the read-back matched.

## 5. Flash (the point of no return)

1. Camera off. Insert the card.
2. Hold the power key. The camera finds the file within its first five seconds and shows the update progress.
3. Do not touch anything until the camera is back at a normal screen or has powered itself off. If the
   screen shows "Program error", the name checksum did not match; nothing was erased, remove the card and
   re-run `verify`.
4. Power off, remove the card, and delete the update file from it before using that card in the camera
   again. The camera does not delete it, and it would flash again at every power-on.

## 6. Check

Power on without a card: does the boot screen appear, does the live view come up, does a shutter press
take a picture? With a card: does a photo save and play back? For the mute candidates: no sound at power-on,
shutter or power-off. Note anything that differs from the stock behaviour, because nobody has run these
files on hardware before you.

## 7. Undo

Flash C the same way to return to stock behaviour with factory settings, or `pack` your own backup
(`--factory-settings --blank-photos` optional) to return to exactly what you had. If the camera does not
boot far enough to read the card: docs/RECOVERY.md section B.
