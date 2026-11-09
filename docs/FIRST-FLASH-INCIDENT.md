# The first flash: what was done and what went wrong

Written 2026-11-06, and brought up to date by a second session that evening and into 11-07. Times are UTC.

## Where things stand

The camera, an original-series Charmera in the 1987 blind-box design, bought in Japan in November 2026, boots
normally. Menus, playback and settings work. Photo and video modes freeze, with or without a card: the screen stays
on the mode menu, the shutter sound plays, then the camera locks up until it switches itself off. Its original
firmware was overwritten by the first flash, and no backup of it exists. The card updater still works, so the camera
can still be flashed from a card if the right firmware turns up.

The most likely cause is firmware that does not fit this camera's hardware. The evidence, as of the evening of
2026-11-06:

- The camera took photos on its own firmware before the first flash, and it was never opened or dropped (your
  account). So its sensor and the ribbon to it worked then, and a loose ribbon is unlikely.
- Seven photos it took on that firmware survive unedited. Their JPEG header is the bare 624-byte kind, with no EXIF
  block. Neither known build writes a still photo that way: both write every still photo with the 0x590-byte EXIF
  header, at every size, quality and clock setting, and use the bare header only for thumbnails, video frames and
  a Wi-Fi stream. That is VERIFIED by two independent static analyses of the public dump, and the film-mods build is
  the same code there. So the camera's original firmware was a third build, one nobody has published (INFERRED,
  strongly). The photos' header is itself the known builds' bare template with the size and quality filled in, so
  this rests on that analysis of where the template is used, not on anything foreign in the photos.
- Another owner, phobits, wrote on 2026-09-06 that their Charmera writes no EXIF block at all (REPORTED, in
  [a pull request to an EXIF fixer](https://github.com/RAIT-09/kodak-charmera-exif-fixer/pull/3)). If that camera
  was never flashed, a build like this camera's was already in other units by September (INFERRED).
- The public dump froze the camera the same way twice, once modified (candidate C) and once unmodified (the stock
  restore file). Millennium and CLOT owners report the same symptom with the same image.
- The two known builds are identical in everything that touches the sensor: power, reset, clock, the bus pins and
  address, the driver, both register tables, the image processing and the preview path (VERIFIED by a byte-level
  diff, checked a second time independently). They differ only in shutter-sound timing, one playback guard, build
  strings and network code that never runs. So neither has a program for another sensor or other wiring, and flashing
  either one gives this camera the same result.

What is not known: whether this unit's sensor is a different part, or the same part wired, powered or clocked
differently. The firmware drives exactly one sensor, with no check of which one is fitted: it writes a fixed list of
61 register settings to a sensor at bus address 0x78, never reads the chip's ID, and carries on when a write fails.
Only the marking on the camera module's flex, or a dump of a camera like this one, can tell more.

The known builds all carry the version stamp `202505201029`, the one the updater compares, so the stamp identifies
none of them:

| Build | Source tree | Compiled | Copies | Sensor table |
|---|---|---|---|---|
| The public dump's | `v006` | Oct 14 2025 | benbadio/keychain-camera-plus, Khimira/Project-Sirius and aschenbecherwespe/recharmera: three cameras, the same bytes up to 0x170000 | `aea3976b…` |
| The film-mods author's | `v008_test` | Nov 19 2025 | Hyperlite-Media/charmera-film-mods, release Charmera_Restore (code and resources only) | `aea3976b…`, the same |
| This camera's original | unknown | unknown | gone; its photos show it differed in the photo code | unknown |

So the factory rebuilt the firmware under one stamp at least twice within five weeks in 2025, and a camera sold in
November 2026 can carry a later build.

The next step on the camera is to find out which image sensor it has, without opening it. The app (below) can build a
**sensor-ID probe**: the public build with a small reviewed patch that, when the camera next enters photo mode, walks
the sensor bus across every device address (0x08..0x77), records which answer, reads the first responder's ID
registers, and writes the result to `SENSOR.TXT` on the card, then stops as the camera does now. A sensor at 0x3C
(bus 0x78) reading `14 0A` is the SP140A the firmware already drives; a sensor at another address is a different part
the firmware does not drive, which a firmware could then be built for; nothing answering anywhere means the sensor is
not responding at all. It is a diagnostic, not a repair, and needs no backup: its boot and update code are the stock
restore file's byte for byte, and the app makes that file to reverse it. Run on the camera 2026-11-08; see below.

## What was built

- **The toolkit** (Python, Windows and Linux): the research report, `pack`, `verify` and `stage` for update files,
  settings, sounds, frames and resources, card checks and repair, EXIF repair, CH341A backup and restore scripts,
  start-up tweaks, the donor route for a camera whose own firmware is gone, and `photo-id`, which reads a photo's
  header and says which kind of firmware wrote it. CI runs the tests on Windows and Ubuntu, with the public dump and
  the film-mods restore file fetched at fixed versions and checked by hash. Until the evening of 2026-11-06 CI had no
  dump, so every test on real firmware was skipped there.
- **Three flash candidates**, all built from benbadio's public stock dump (commit 209c25a), all withdrawn. C has stock
  code and resources with a factory settings record and blank internal photos. A is C with the interface sounds off.
  B is A with the sound files silenced.
- **Charmera Card**, the Android app. It downloads the dump, checks its hash and makes the stock restore file. It
  writes that file to the camera's card over the camera's USB-C cable, then walks through the flash and cleanup.
  Builds are signed with a permanent key and published by GitHub Actions. Version 0.4 has no backup step and must
  not be used to flash. When the repository went public on 11-06, its release was turned back into a draft, so its
  download link is offline until 0.5 is published. Version 0.5, with the backup step, the write gate and the review
  fixes, is on the branch `claude/charmera-firmware-toolkit-0kilzl` and has not been published.

## Timeline

- **11-05 08:38.** You asked for a review: "Make sure I'm not going to break my camera first and foremost." The
  review raised 36 issues and fixed 35. I concluded that nothing in the first-flash path should break the camera.
  The risks I named were a battery dying mid-update, a camera from another edition, and the cleanup steps.
- **11-06 00:05.** You asked whether I was confident. I answered: "Yes, for candidate C. I'm confident it won't
  hurt your camera." I again named only the battery and another edition as risks.
- **11-06 about 05:39.** C was flashed through the app and reached 100%. The camera booted to the logo and the
  menu.
- **Cleanup.** You took the card out, switched off and put the card back. Plugging in the cable then switched the
  camera on, and it flashed C a second time. My instructions had not allowed for that.
- **The app removed the file**, but its result showed far from the button you had pressed, so it read as no
  confirmation.
- **05:53.** On the normal start, the camera stuck on the mode menu and froze when the shutter was pressed.
- **06:01 to 06:16.** I first suspected the card's file system. Then I blamed C's settings ("The diff confirms the
  cause is in the settings area"). I withdrew C, A and B, shipped 0.4 offering the stock restore file, and had
  you flash the camera a second time on purpose.
- **06:35.** After the restore flash the symptom was the same. Playback and settings opened; photo and video
  froze. That pointed at the firmware not matching the sensor.
- **07:48.** I said I didn't know a "1987" edition. It is one of the original-series blind-box designs.
- **10:44 to 11:02.** I drafted the community post for Project-Sirius issue #2. You posted it.
- **11:40.** You found github.com/Hyperlite-Media/charmera-film-mods, which I had not seen. It went public on
  11-04 at 16:29, a few hours before my survey finished, and I never searched again before the flash.
- **11-06, later.** You opened Hyperlite-Media/charmera-film-mods#1. It asked the author to compare their sensor
  init table with the public dump's, and to share an update file if the two differed.
- **11-06, the author's reply.** In their dump the table is at 0xF5D34, which is +0xC8 as predicted. It is 124
  bytes long, and its sha256 `aea3976b…` is identical to the public dump's, so their build won't help. They said
  they would rather not give you anything to flash, because it can't fix a sensor difference and could make
  things worse. Their advice is to confirm the sensor part first, and they offered to keep helping from there.
- **11-06, evening: a second session.**
  - An audit of the work the first session had marked done found 110 problems that a second reviewer could not
    refute, 3 of them critical and 18 high. Among them: `stage` wrote to a card without a backup while the docs said
    nothing would; `restore` rewrote the chip through the clip with no fresh read first; a chip read taken after a
    flash passed as proof that the public firmware fits; the app could enable Write with a check made against a
    different backup than the one it showed; and the app's headline said "OK to put on the card" when nothing had
    been compared. All of them are fixed on the branch.
  - You decided: `stage` warns when no backup is given but still writes; the donor route is built now, on the
    computer only, and the app never takes it.
  - You told me the camera took photos before the first flash and was never opened or dropped, that you have no
    clip programmer, and you copied seven of those photos to the computer.
  - Research: no official firmware exists anywhere (Reto, Kodak and the Japanese distributor SAEDA publish none); no
    public dump comes from a camera with another sensor; the reference module marking is KE28-V1, not KC28; the
    film-mods build is public as that project's restore file; a third camera's dump (recharmera) is the public build.
  - Your photos: neither known build writes their header for a still photo, so the original firmware was a third
    build.
  - The two known builds were diffed byte for byte: they drive the sensor identically.
  - What a working camera reveals without being opened: roughly which build it runs, through its photos, its videos
    and its Setup > Version screen; never its firmware or its sensor.
- **11-07, early: the same session.**
  - You made the repository public. Its history held nothing private: every commit uses the noreply address, and no
    photo, dump, archive or APK was ever committed. Since 0.4 and `main`'s app README were now public, the 0.4
    release was turned back into a draft at your request.
  - Your follow-up on Hyperlite-Media/charmera-film-mods#1 was posted at 04:44. It asks the author when and where they
    bought their camera.
  - You bought a second, unmodified camera and would rather not open it. Photos copied straight off its card can show
    whether it writes the same bare header as this camera did; its firmware can only be had by opening it.
  - The search of GitHub found no Charmera firmware beyond the two known builds and copies of them. Four of its ten
    passes came back empty and were run again, and the re-run found nothing that could restore the camera either. It
    did find one more owner who offers untouched photos, of a Millennium unit. Not searched: anything outside
    GitHub, such as Reddit (blocked here), Discord and Chinese forums.
  - Neither known build has the card-triggered whole-flash backup that some older firmware from the same factory has
    (no `FirmwareUpgrade_Msg.ini` or `Firmware_Backup` string in either; VERIFIED by string search).
  - At 06:54 your three asks for photos straight off the card went up, to arjunbazinga, Seabass567 and phobits
    ([Routes, 3](#routes-to-a-working-camera)). Each was checked for its facts and its wording first. At 08:23 a
    fourth went to alicanadal, who has a Millennium unit and had offered untouched samples.

## What I got wrong

1. **I said the flash was safe when nobody knew what your camera needed.** This is the failure that cost you the
   camera. My tools checked the file being flashed, never the camera receiving it. I already knew the public
   image boots on Millennium and CLOT units but can't take pictures on them, so it depends on the hardware. I
   still assumed every original-series unit matched it, with no evidence about production batches. The
   first-flash guide mentioned the risk but didn't make it a stop point, and I told you I was confident. The safe
   advice was to read your own chip with a CH341A before flashing anything, or not to flash. Without that
   backup, a mismatch can't be undone.
2. **The review answered the wrong question.** It hunted for ways the file or the procedure could brick the
   camera: truncated downloads, the updater's final erase, the cleanup steps. It never asked whether this
   firmware would drive this camera's parts.
3. **The first flash used a modified file.** C was not the plain stock file. It also reset the settings and
   erased the internal photos. Its program code was identical, so this didn't cause the freeze, but a first
   flash should change as little as possible.
4. **The update file overwrites more than it needs to.** Our files are 0x1E0000 bytes long. They rewrite the
   firmware, the resources, the settings bank and the internal photo area, and then the camera's updater erases the
   64 KiB block at 0x1F0000. The film-mods files stop after the resources, at 0x169000, so the updater's erase lands
   on the settings block instead. So the flash replaced your camera's settings and photo area with another camera's
   and erased its last block. Anything your unit kept there is gone. One block survived every flash: 0x1E0000 to
   0x1EFFFF, which a 0x1E0000-byte file does not write and its final erase does not reach. It is blank on every known
   camera, so it probably holds nothing, but a first read of the chip should keep it.
5. **I didn't foresee that the cable powers the camera on.** Plugging the cable into a switched-off camera started it
   and ran the updater again from the card. The instructions caused an unintended second flash of C. It did no harm,
   but it showed the instructions had never been checked against the real hardware.
6. **The app's cleanup was unclear.** The result appeared away from the button, and Android doesn't let an app
   eject a drive, so you had to find the eject screen yourself.
7. **I diagnosed wrongly and stated it too strongly.** First I blamed the card, then C's settings, and I claimed
   the diff "confirms" it. On that basis I withdrew the candidates, shipped a release and had you flash the
   camera again.
8. **I missed the film-mods repo.** I didn't search again in the day between my survey and your flash. It also
   shows that two different firmware builds carry the same version stamp, 202505201029. So a matching edition, boot
   screen or version string doesn't prove a camera matches the public dump.
9. **I got the edition wrong.** I said I didn't know a 1987 edition, when it is the original series.
10. **I put the blame on you.** The first community draft said "lesson learned", as though the missing backup
    were your mistake. It was mine.
11. **Smaller process problems:**
    - The first plan needed both the Steam Deck and the phone, when the phone could do everything.
    - One APK release was lost to a GitHub error between deleting and recreating it, and another got stuck as a
      draft.
    - Version 0.2 changed the signing key and had to be uninstalled before the update.

The second session found these in the first follow-up:

12. **Work marked done that was not done.** These notes said "no backup of your own camera, no flash" was a hard
    stop in the docs and the app. On the computer, `stage`, `restore`, `pack` and the mod commands could all write
    without one, and the docs said they could not. The app's README said 0.5 was "built and tested on a computer";
    it had never been built.
13. **A check that pointed the wrong way.** The sensor comparison treated any backup as the camera's own firmware.
    A backup read after a flash holds whatever was flashed, so a read of this camera would have "proved" that the
    public firmware fits it, and the check would have refused the one kind of file that could fix it.
14. **Facts without a source, or wrong.** "Official updates are 0x169000 bytes" was the film-mods author's reading;
    no official update file has ever been published. The module marking "KC28" came from an AI-assisted reading of
    a teardown photo that shows "KE28-V1". "The compile date does not tell the builds apart" was false: Oct 14 and
    Nov 19 2025. The EXIF notes said the camera writes an `IDIT` date chunk into its videos; the public build writes
    none. The test counts quoted were from before the code they described.
15. **CI did not test what it seemed to.** Without the dump, every test on real firmware was skipped in CI, so a
    green check said less than it looked.

## Routes to a working camera

1. **The seller.** The camera was bought in Japan in November 2026. Japanese stock comes through three importers:
   on and on Co. (since October 2025), Hilight Brands Japan (Kodak Apparel stores) and SAEDA (Reto's official
   distributor since February 2026). The warranty card in the box names which one. Their terms exchange faulty units
   within 30 days and exclude modified ones; say plainly that it was reflashed.
2. **A dump from a camera like this one.** It has to come from a camera running a later build than the two known
   ones, ideally bought in Japan in late 2026, ideally with the same module marking. Before anyone opens it, two
   checks show whether it is a candidate:
   - its photos: still photos with the same bare 624-byte header as this camera's come from neither known build
     (`charmera photo-id <photo>`, on a photo copied straight off its card);
   - its Setup > Version screen: both known builds show `JH5408  20250520`, so a different string means a different
     build (the same string proves nothing).
   Its firmware can then only be had by reading its chip with a clip: a stock camera gives none of it up over USB
   (VERIFIED: its memory-read commands need an authorisation level that nothing sets). The donor route takes that
   read ([Recovery, section D](02-RECOVERY.md#the-donor-route)).
   The first candidate is your second camera, unmodified and yours. If its photos match, a read of its chip is the
   only known copy of a firmware that should fit this camera; whether it does stays unverified until it is tried.
3. **The community.** Project-Sirius #2 has no reply yet. The film-mods author has a working camera and a reader, but
   their build is a known one; they advised against their files, and your question about when and where they bought
   theirs is open. aschenbecherwespe (recharmera) also runs a known build. isaackwok, a CLOT owner, offered on
   2026-10-04 to post a CLOT dump; nothing since. Photos straight off the card are the first thing to ask anyone
   for, and on 11-07 you asked for them from arjunbazinga, whose camera has never been flashed
   ([discussion #62](https://github.com/arjunbazinga/arjunbazinga.github.io/discussions/62)), Seabass567, who works
   from photos and asked owners for dump hashes
   ([comment](https://github.com/mombotro/Godox-C100-GHDK/issues/1#issuecomment-6703607185)), and phobits, whose
   camera writes no EXIF
   ([comment](https://github.com/RAIT-09/kodak-charmera-exif-fixer/pull/3#issuecomment-6703607354)). alicanadal, who has
   an orange Millennium unit, offered untouched sample photos in RickCaleg/charmera-importer#18; their header would
   show whether that edition's firmware writes photos the way this camera's did. On 11-07 you asked them to attach a
   few, zipped ([comment](https://github.com/RickCaleg/charmera-importer/issues/18#issuecomment-6704447245)). What to ask
   anyone: one unedited photo straight off the card, a photo of the camera module's flex, when and in which country
   they bought the camera, and, from anyone with a clip, a dump.
4. **Identify the sensor.** Open the camera ([Recovery, section B](02-RECOVERY.md#opening-the-camera)) and
   photograph the camera module's flex (byjp's unit reads `KE28-V1`), the board silkscreen (`DC28 5408A` there) and
   the processor's marking with its date code. A different flex code points to a different module. The same code
   points to a difference in wiring, power or clock between builds, not to the ribbon, since the camera worked
   before. The lens is glued to the sensor: do not try to read the die.
5. **A programmer before any flash.** Every route ends in a flash of firmware that has never run on this camera. If
   it does not start far enough to run the card updater, only a clip write can bring the camera back. So a 3.3 V
   programmer (a CH341A switched to 3.3 V, a CH347 or a Pico) and a SOIC-8 clip come first, and with them a read of
   the chip as it is now.
6. **Nothing from the film-mods repo goes on this camera's card.** Their loader and payload are written for their
   build's addresses; their restore file carries the stamp this camera now runs, so the camera would ignore it; and
   their build drives the sensor exactly as the public one does.

## Open work in the toolkit

Status on the morning of 2026-11-07, on the branch `claude/charmera-firmware-toolkit-0kilzl`:

- Done: "no backup of your own camera, no flash" in every tool that writes. The app writes to the card only while a
  dump of the camera's own chip is on record and the update file was checked against that very dump; `restore` reads
  the chip twice before it writes and keeps that read; `pack` and the mod commands refuse camera cards and
  update-file names; `stage` writes without `--stock` only with a warning, as you decided.
- Done: a read taken after a flash is never taken as proof of fit. A backup whose firmware is a known public build
  gives a warning, not a pass, when its sensor table matches.
- Done: the donor route (`verify` and `stage` with `--donor`), the film-mods fingerprint, the compile date and the
  source-tree folder as build marks, only the two update sizes the camera is known to take, the vector-table check,
  refusal of several sensor programs, the withdrawn candidates refused by `verify` as by the app, and `photo-id`.
- Done: `docs/03-FLASH-CANDIDATES.md` says the likely cause is firmware that does not fit the camera, not C's
  settings, and why the candidates stay withdrawn.
- Done: two more review rounds, each with its fixes checked by a reviewer who changed nothing. The donor route checks
  the vector table and refuses several sensor programs in either file; `restore` refuses an image with several and
  asks a second time when the chip has none or several; a file counts as a known build "with changes" only with that
  build's stamp, sensor table, source-tree folder and compile date, in `verify` and the app alike; the app refuses a
  read named as unverified as its backup; `photo-id` says no more than a header shows. On the branch, 400 Python
  tests pass (39 skipped for a missing tool or another system) and the four JVM suites pass 153, 22, 43 and 18; CI
  passes them on Windows and Ubuntu with Python 3.9 and 3.12, and builds a release-signed 0.5.
- Not verified on hardware: the official-size file's final erase (factory settings and an empty photo list at the
  next start), `restore`'s new read-first flow on a real chip, and the donor route. Not yet run on a phone: version
  0.5 of the app. It is published only when the branch is merged into `main`, which is your call.

Later on 2026-11-07, version 0.7 of the app adds the sensor-ID probe and rebuilds its screen as a column of cards:

- The app builds the probe (step 1, "Make the sensor-ID probe") and reads its result back (step 4, "Read the sensor
  result from the card"), decoding `SENSOR.TXT` as above. The probe is the one file the app writes without a backup on
  record; every file-level check still holds (name, size, the SPII start, the sum and the SHA-256), and the waiver is
  keyed to the reviewed SHA-256 of the probe's bytes, so it can reach no other file.
- A review of the probe wiring found one edge (finding 7): with a backup of a *different* camera already on record,
  the app checked the probe against it, the two sensor tables differed, and the probe was refused with a message about
  the backup -- the one file that needs no backup, refused for not matching one. It is fixed: the probe is now checked
  with no reference on every path by which a file reaches the card (one rule, `Candidates.check`), so no backup on
  record can refuse it, and the re-check machine leaves the probe alone. For a camera with no backup on record (this
  one), the behaviour was already correct and is unchanged.
- Verified this session on the JVM and against the public dump: the four suites pass 151, 22, 41 and 26, with the
  probe's reference-free rule exercised in CandidatesTest and WriteGateTest against the real probe built from the
  dump; the app's source checks (`tests/test_android.py`) pass 31, and the Java typechecks against android-35. Not run
  on a phone, and the probe is not tested on a camera; 0.7 is signed with a throwaway key for testing, and nothing is
  published.

On 2026-11-08 the probe was run on the camera. The first probe, which read a single address (0x78), wrote `FF FF`;
that result was then re-examined and the probe rebuilt to scan the whole bus.

- Getting the probe to run meant working around a re-flash loop. With the update file on the card the camera
  re-flashed it on every power-on and then sat at "Upgrade 100%" without booting the firmware it had just written, so
  the probe never ran. The method that worked: flash the probe, take the update file off the card (a card reader, or
  the app's remove step), then boot with the card in. With no update file the updater has nothing to flash, so the
  camera runs the probe it already holds, and entering photo mode wrote `SENSOR.TXT`. The loop is inherent to this
  probe design: the probe's firmware still carries the stock build stamp, but its update filename carries a later
  stamp, and the updater re-flashes whenever the two differ -- so it re-flashes every boot. The app's step-4 wording
  and this doc were corrected to tell the owner to remove the update file before the probe will run.
- `FF FF` was first written up here as a dead or disconnected sensor. That was overstated, and is corrected. The
  firmware's bus read routine (VA 0x2d9b8) returns its own `FF` sentinel when a device does not acknowledge, so
  `FF FF` means only that nothing answered at the one address that first probe tried -- 0x78, the SP140A's. A
  different sensor at a different address would read exactly the same. This camera worked on its original firmware,
  whose photo code differed from the public build, so a sensor the restored firmware does not drive is at least as
  likely as a dead sensor. The single-address probe could not tell the two apart; the `FF FF` is a wrong scope, not a
  proven hardware fault.
- The probe was rebuilt as a bus scan (app 0.8). It walks 7-bit addresses 0x08..0x77 with the firmware's own read
  routine, records which answer in a 16-byte bitmap, and reads the first responder's registers 0x02/0x03, so one run
  separates the cases: nothing anywhere (a sensor that is truly not responding -- disconnected, unpowered or dead), a
  sensor at an address other than 0x3C (a part the firmware does not drive, which a firmware could be built for), or
  0x3C reading `14 0A` (the SP140A the firmware expects, so a freeze is elsewhere). It is the stock restore file with
  a reviewed 310-byte patch over a routine the firmware never calls; the stock restore file reverses it. File
  `JH_5408202611081200_0F065399.bin`, SHA-256
  `7909e9d97edb7815408d69c272949d31c20b926a77d8e73d362363774db0f05f`. Run on the camera 2026-11-08.
- The scan found two devices on the bus and nothing at 0x78. `SENSOR.TXT` was the 21-byte scan record (magic
  `A5 01`), its bitmap marking 7-bit 0x36 (bus 0x6C, the first responder) and 7-bit 0x48 (bus 0x90); the first
  responder's registers 0x02/0x03 read 0x00 0x00. So this camera has a live image sensor at bus 0x6C, not the
  SP140A at 0x78 the public and restore firmware drive: a sensor-address mismatch, not a hardware fault. It is why
  the restore froze this camera -- the firmware talks to 0x78, gets no answer, and cannot bring the sensor up -- and
  it matches the camera's original firmware differing in its photo code (it drove the 0x6C sensor). The public and
  restore firmware cannot work on this unit. A fix needs firmware that drives the 0x6C sensor: a dump from a camera
  with the same sensor (the donor route), or this firmware's sensor address and init table adapted to the part at
  0x6C. Naming that part is the next diagnostic -- reading its real ID registers (commonly 0x00/0x01, 0x0A/0x0B or
  0xFC..0xFF) at 0x6C and 0x90; register 0x02/0x03 is SP140A-specific and read 0x00 here, so it does not identify it.
- To name the part, the probe was extended (app 0.9) to dump a window of each responder's 8-bit ID registers
  (0x00..0x1F and 0xF0..0xFF), not just the first responder's 0x02/0x03. File `JH_5408202611082100_0F065AEE.bin`,
  SHA-256 `190a8d0fba3b3c6fa643692f9a86f69dbd53944436dcc523908a68b11ecd1dda`. Run on the camera 2026-11-08.
- The 0.9 result was all zeros: both responders (0x36 and 0x48) answered, but every one of their 48 ID-register
  bytes read 0x00. That is not a dead read -- the probe pre-fills 0xFF and only overwrites on a successful read, so
  0x00 means each read ACKed and returned 0. Disassembly of the probe's store path (it reads the byte back from the
  buffer the bus routine fills, not the routine's status) and of the bus read routine, cross-checked by adversarial
  review, ruled out a probe bug: the zeros are real wire data. The cause is the register-address width. Bus 0x6C
  (7-bit 0x36) is the canonical OmniVision/SmartSens camera address, and those sensors use 16-bit register
  sub-addresses with their chip ID in the 0x30xx block; the firmware (built for the SP140A, an 8-bit part) sends one
  sub-address byte, which the sensor ACKs (SCCB's 9th bit is don't-care) but which never reaches the real ID
  registers, so the 8-bit-addressable space reads 0x00. The 0xF0/0xF1 window the probe also read -- where the one
  common 8-bit family, GalaxyCore, keeps its ID -- was 0x00 too, which rules out a correctly-read 8-bit part.
- The probe was rebuilt to read at 16-bit sub-addressing (app 0.10). The scan stays at 8-bit (so it still enumerates
  both responders as 0.8/0.9 did); only the per-responder reads switch to 16-bit, covering the chip-ID windows
  0x3000..0x301F (OmniVision 0x300A/0x300B/0x300C) and 0x3100..0x310F (SmartSens 0x3107/0x3108), and restoring the
  width afterward. The app names the part from those bytes -- OV2680, the 2 MP OmniVision at bus 0x6C, is the leading
  candidate; OV5647/5648/8865 and the SmartSens SC-series are covered. It is the stock restore file with a reviewed
  330-byte patch over the routine the firmware never calls; the stock restore file reverses it, and the change was
  verified against the proven-good 0.9 stub and by adversarial review before release. File
  `JH_5408202611082300_0F065C4D.bin`, SHA-256
  `6a7aa6d66914805e741d7da3ae743a5250f0f1ef90622b3acc9da4bb75d2547b`. Run on the camera 2026-11-08.
- The 0.10 result named the sensor. Both responders (0x36 / bus 0x6C and 0x48 / bus 0x90) answered, and each returned
  chip-ID registers 0x300A=0x97, 0x300B=0x32 at 16-bit sub-addressing -- `0x9732`, the OmniVision **OV9732** (1 MP,
  1280x720, 1-lane MIPI, 10-bit RAW), not the OV2680 that had been the leading guess. The 0x48 responder returned the
  same fingerprint and is almost certainly the one OV9732 aliased to a second address, not a second sensor. Confirmed
  against the Ingenic/Dafang OV9732 driver's detect routine (reads 0x300A/0x300B, expects 0x97/0x32) and an independent
  OpenIPC trace. So the fitted sensor (OV9732, 16-bit registers, bus 0x6C) differs from the one the firmware drives
  (SuperPix SP140A, 8-bit registers, bus 0x78): a same-class part at a different address and register width. See
  [14-OV9732-REPAIR-SCOPE.md](14-OV9732-REPAIR-SCOPE.md).
- A first repair was built (app 0.11, "Make the OV9732 bring-up"). It is the same patch shape as the probe -- the hook
  over the dead routine, the SP140A table left in place -- but the stub points a 16-bit control context at bus 0x6C and
  walks a 1-lane MIPI RAW10 1280x720 OV9732 start-up table (from the Ingenic T31 ov9732.c driver, 53 writes) through the
  firmware's own write routine (0x2d828), then returns so the capture pipeline runs. It is a reviewed patch written
  without a backup, like the probe, and the stock restore file reverses it. Verified by disassembly (the write wrapper
  0x2d828 and primitive 0x2d6bc preserve the ctx and table-pointer registers across every write; the bus-grab 0x2d784
  touches neither), by the in-JVM candidate tests against the real dump, and by adversarial review. That review also
  checked placement: the patched region (VA 0x61F20, the same one the 0.10 probe used) is not blank padding but an
  unreferenced SSV WiFi-logging routine (the camera has no WiFi). No branch or pointer anywhere in the 2 MB image
  targets it, so it is unreachable; and the 0.10 probe already overwrote this exact region (a superset, 330 bytes to
  the bring-up's 242) and ran -- the camera booted, entered photo mode and wrote SENSOR.TXT -- so overwriting it is
  validated statically and empirically, in the boot-to-photo path the bring-up runs in. File
  `JH_5408202611090011_0F062D96.bin`, SHA-256 `bf22fb31157661387233fdfa20c8467e2a0cc9274af7b47d048886d9119b1fcd`. The
  open risk is whether the OV9732's 1-lane MIPI output timing matches the camera's receiver (configured for the SP140A),
  which can only be confirmed on the bench; the worst case is no picture, as now. Not yet run on the camera.
- If the scan finds nothing at any address, the reseat test still applies: open the camera, reseat the image sensor's
  ribbon, look for a cracked flex or cold joint, and scan again. A reading that turns into a real address proves the
  sensor is alive and it was a connection fault; nothing, still, points to a dead sensor or its wiring, which no
  firmware can fix.

## Evidence from the camera's own photos

Seven photos taken on the original firmware were copied straight off the card. Two more were rotated and saved again
by the phone; they say nothing about the camera and are left out.

- All seven have the same 624-byte header: SOF0 first, 1440x1080, Y sampling 4:2:0, a comment segment of length 11
  holding exactly `GPEncoder`, two quantisation tables that are the standard IJG tables at quality 80, a restart
  interval of 0, four Huffman tables, then the scan. No APP1, so no EXIF.
- The public build writes the same frame size (its default), the same sampling, the same quality-80 tables (its
  quality setting 1, which the dump's own settings hold) and the same Huffman tables, but always inside its 0x590-byte
  EXIF header, with a comment of length 17 (`GPEncoder` and six spaces) and Make `Generalplus`, Model `CBB3`. Its one
  path that could write a bare header into a photo file, a snapshot from a video frame, is never triggered by anything
  in the firmware and could not carry quality-80 tables.
- The header is byte for byte the firmware's bare template, apart from the bytes its encoder fills in, so the camera
  wrote it: no phone or editor makes that exact comment segment. `tests/test_photo_id.py` holds this against the
  dump: the template filled with 1440x1080, 4:2:0 and the quality-80 tables gives the photos' header exactly.
- Nothing in a photo names the sensor. The photos show that the build differed, not how.

## Build comparison details

- **Fingerprints over 0x000000–0x168FFF.**
  - Public dump: SHA-256 `bffddd53…`, SHA-1 `d1f21227ab402bc842134070acc29a77041b4a63`, byte sum 0x0852B369.
  - film-mods build: SHA-256 `5b352e46…` (the whole restore file), SHA-1 `91499bd3bff3acde053cb8c8255a73cdeb6e3d33`,
    byte sum 0x0852C329.
  - Both carry the stamp 202505201029: at file 0x218C4 in the public dump, 0x218D0 in film-mods'. Both carry the build
    string `JH5408  20250520` (0x18964 and 0x1897C). The compile dates differ (`Oct 14 2025` at 0xF3978, `Nov 19 2025`
    at 0xF3604), and so do the source-tree folders in their 27 source paths (`v006`, `v008_test`). The ARM vector
    tables (0x200..0x240) are identical.
- **Offsets.** Public file offset minus film-mods file offset, measured from the bytes: 0 up to 0xAF80; −0x18 to
  0x1D6FC; −0xC to 0x3A1A4; then rising through the network code to +0x374 by 0x5EE14; +0x374 to 0xF3FD1; falling to
  −0xC8 by 0xF579C; −0xC8 to 0x109688 (sensor and image tables, read-only and initialised data); −0xC0 after that and
  for RAM variables. The resource pack at 0x120000–0x169000 is byte-identical at the same offsets. Film-mods
  addresses in that project's notes are RAM addresses, and file = RAM + 0x200.
- **What differs.** The photo state's shutter sequence (film-mods plays the sound, then waits 200 ms; the public build
  waits 20 ms, plays the sound, then waits 100 ms, and waits another 100 ms after a capture), a 50 ms wait-and-repost
  guard in playback, the build strings and their placement, and small differences in network code that never runs.
  The video state is identical.
- **What is the same, byte for byte after relocation.** The sensor driver and its call tree (403 functions), the
  bring-up order with its single 20 ms wait, the reset and init tables, the table writer (10 attempts per register),
  the bus (bit-banged, clock on GPIO 0x11, data on GPIO 0x12, write address 0x78, 8-bit registers, page select
  through register 0xFD), the boot GPIO table, the clock select, the sensor interface and its configuration, the image
  processing, the exposure stepper, the tone and edge tables, and the preview path. Which GPIO powers down or resets
  the sensor, and which register is its regulator, are UNKNOWN; every byte involved is identical.
- **Sensor tables.** A reset table (`fd 00 20 00 ff ff`) at public file 0xF5BFC, then the 61-write init table at
  0xF5C6C: 124 bytes, sha256 `aea3976b…`, starting `fd 00 2f 1b` and ending `ff ff`. Its first six writes are those
  of the open-source SuperPix SP140A driver for 1-lane MIPI 1280x720, whose chip ID (0x14, 0x0A) sits at page-0
  registers 0x02/0x03; this firmware never reads them. There is one 1280x720 mode. Exposure goes to page 1 registers
  0x03/0x04 and gain to 0x24, latched by 0x01 = 1.
- **One sensor only.** One sensor is registered at boot, with no probe. The read routines are never called, so no
  ID is read. The "Check chip id fail" string belongs to the unused Wi-Fi driver.
