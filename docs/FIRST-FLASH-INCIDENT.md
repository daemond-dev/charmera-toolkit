# The first flash: what was done and what went wrong

Written 2026-11-06. Times are UTC.

## Where things stand

The camera, an original-series Charmera in the 1987 blind-box design, boots normally. Menus, playback and
settings work. Photo and video modes freeze, with or without a card: the screen stays on the mode menu, the
shutter sound plays, then the camera locks up until it switches itself off. Its original firmware was overwritten
by the first flash, and no backup of it exists. The card updater still works, so the camera can still be flashed
from a card if the right firmware turns up.

The most likely cause is that this unit's image sensor differs from the one the public stock firmware drives. The
public dump was flashed twice, once modified (candidate C) and once unmodified (the stock restore file), and both
gave the same result. Millennium and CLOT owners report the same symptom with the same image. This is an inference
(about 70 percent at the time), not something measured on the camera.

The public firmware drives exactly one sensor, with no check of which one is fitted. It writes a fixed list of
61 register settings to a sensor at bus address 0x78, never reads the chip's ID, and carries on when a write
fails. So a different sensor and a loose or damaged sensor ribbon look the same from the firmware's side. Only
the sensor's markings or a dump from a matching camera can tell them apart.

Two firmware builds are known: the public dump and the film-mods author's. They contain the same sensor init
table, byte for byte; the film-mods author confirmed this from their own factory dump on 2026-11-06. So neither
build has a program for any other sensor, and flashing either one would give this camera the same result.

## What was built

- **The toolkit** (Python, Windows and Linux): the research report, `pack`, `verify` and `stage` for update
  files, settings, sounds, frames and resources, card checks and repair, EXIF repair, CH341A backup and restore
  scripts, and start-up tweaks. It has 286 tests, and CI runs on Windows and Ubuntu.
- **Three flash candidates**, all built from benbadio's public stock dump (commit 209c25a). C has stock code and
  resources with a factory settings record and blank internal photos. A is C with the interface sounds off. B is A
  with the sound files silenced.
- **Charmera Card**, the Android app. It downloads the dump, checks its hash and makes the update file. It writes
  that file to the camera's card over the camera's USB-C cable, then walks through the flash and cleanup. Builds
  are signed with a permanent key and published by GitHub Actions. Version 0.4 is published. A rewrite of its
  on-screen text is on the branch `claude/charmera-firmware-toolkit-0kilzl` and has not been published.

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
4. **The update file overwrites more than an official update does.** Our files are 0x1E0000 bytes long. They
   rewrite the firmware, the resources, the settings bank and the internal photo area, and then the camera's
   updater erases the 64 KiB block at 0x1F0000. Official updates and the film-mods files stop after the
   resources, at 0x169000, so the updater's erase lands on the settings block. So the flash replaced your
   camera's settings and photo area with another camera's and erased its last block. Anything your unit kept
   there is gone. That is probably not what broke photo mode, but nobody knows, because there is no backup.
5. **I didn't foresee that the cable powers the camera on.** Plugging the cable into a switched-off camera
   started it and ran the updater again from the card. The instructions caused an unintended second flash of C.
   It did no harm, but it showed the instructions had never been checked against the real hardware.
6. **The app's cleanup was unclear.** The result appeared away from the button, and Android doesn't let an app
   eject a drive, so you had to find the eject screen yourself.
7. **I diagnosed wrongly and stated it too strongly.** First I blamed the card, then C's settings, and I claimed
   the diff "confirms" it. On that basis I withdrew the candidates, shipped a release and had you flash the
   camera again. `docs/03-FLASH-CANDIDATES.md` still blames C's settings.
8. **I missed the film-mods repo.** I didn't search again in the day between my survey and your flash. It also
   shows that two different firmware builds carry the same version stamp, 202505201029. The public dump and the
   film-mods author's working camera both have it, but their bytes differ. So a matching edition, boot screen or
   version string doesn't prove a camera matches the public dump.
9. **I got the edition wrong.** I said I didn't know a 1987 edition, when it is the original series.
10. **I put the blame on you.** The first community draft said "lesson learned", as though the missing backup
    were your mistake. It was mine.
11. **Smaller process problems:**
    - The first plan needed both the Steam Deck and the phone, when the phone could do everything.
    - One APK release was lost to a GitHub error between deleting and recreating it, and another got stuck as a
      draft.
    - Version 0.2 changed the signing key and had to be uninstalled before the update.

## Routes to a working camera

1. **The seller.** The camera was bought in Japan in November 2026. Reto's 30-day exchange covers only
   purchases from Reto's own store, so it most likely doesn't apply. The shop it came from is the place to ask.
   Say plainly that it was reflashed.
2. **The community.** The request for a dump from a working original-series unit is posted in Project-Sirius #2.
3. **The film-mods author's build is ruled out.** Their sensor init table is identical to the public dump's.
   - They suggest identifying the sensor part first, from the marking on the sensor module or from an ID
     register. The public firmware never reads a sensor ID, though, so on this camera the marking is the way.
     That means opening the camera.
   - If the part differs from what the firmware targets, the only fix is a dump from a unit carrying that
     sensor.
   - They are willing to keep helping once the sensor is known.
   - Nothing from their repo goes on this camera's card. Their loader and payload are written for their build's
     addresses, not the public dump's.
4. **Identify the sensor.** Read the part marking on the sensor module or its ribbon and compare it with what
   other owners report for the public firmware: an SP140A-family sensor (SuperPix), module marking KC28. A
   different part confirms the mismatch and tells donors what to look for. The same part points to a loose or
   damaged ribbon instead.
5. **A replacement camera.** Read its chip before flashing anything.

## Open work in the toolkit

- Make "no backup of your own camera, no flash" a hard stop in the app and the docs.
- Correct `docs/03-FLASH-CANDIDATES.md`: the likely cause is the firmware not matching the sensor, not C's
  settings.
- Consider building official-size update files (0x169000 bytes) that leave the settings, the photos and the last
  block alone, unless there is a reason to write them.
- Finish the 0.4 review fixes, then build, test and publish the app's text rewrite.
- The toolkit identifies a build by its version stamp and checks the sensor table at a fixed offset
  (`image.py`, 0xF5C6C). The stamp does not identify a build, and the table moves between builds. Find the table
  by its content instead, and stop treating "original series" as proof that a camera matches the public dump.
- The research report says the firmware ignores sensor write failures. More exactly, after 10 failed attempts
  at one register it abandons the rest of that table, and the caller ignores the error. The sensor is started
  from the start-up state at boot, before the card mounts.

## Build comparison details

These come from the public dump plus the addresses in film-mods' `docs/NOTES.md` and `mods/*.c`, and were checked
twice independently. The film-mods author confirmed the sensor table result from their own dump.

- **Fingerprints over 0x000000–0x168FFF.**
  - Public dump: SHA-1 `d1f21227ab402bc842134070acc29a77041b4a63`, byte sum 0x0852B369.
  - film-mods build: SHA-1 `91499bd3bff3acde053cb8c8255a73cdeb6e3d33`, byte sum 0x0852C329.
  - Both carry the stamp 202505201029: at file 0x218C4 in the public dump, 0x218D0 in film-mods'.
- **Address offsets.** Film-mods addresses are RAM, and file = RAM + 0x200. Public file offset minus film file
  offset is:
  - −0x18 in early code;
  - −0xC from about RAM 0x1E240 to 0x30F08;
  - +0x374 from RAM 0x645E4 to 0xF3940;
  - −0xC8 for the sensor and image data from 0xF5764 to 0xF7FCC;
  - −0xC0 for RAM variables.

  The public dump's 0x380 extra bytes lie between film RAM 0x30F08 and 0x645E4, mostly unused Wi-Fi/lwIP code.
- **Sensor bus.** The bus is bit-banged, with clock on GPIO 0x11 and data on GPIO 0x12. The write address is
  0x78 (7-bit 0x3C), with 8-bit registers and page select through register 0xFD.
- **Sensor tables.** A reset table (`fd 00 20 00 ff ff`) at public file 0xF5BFC, then the 61-write init table
  at 0xF5C6C. That table is 124 bytes, sha256 `aea3976b…`, starts `fd 00 2f 1b` and ends `ff ff`. There is one
  1280x720 mode. Exposure goes to page 1 registers 0x03/0x04 and gain to 0x24, latched by 0x01 = 1.
- **One sensor only.** One sensor is registered at boot, with no probe. The read routines are never called, so
  no ID is read. The "Check chip id fail" string belongs to the unused Wi-Fi driver.
