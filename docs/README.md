# Documentation

Start with The first write. It links to each of the others at the step that needs it. You don't need the rest to write firmware.

## Before and during a write

1. [The first write](01-FIRST-FLASH.md). Start here. The whole path from knowing what the camera runs to the finished write, with the point of no return marked. You find out what it runs from a chip backup or with Identify.
2. [Recovery](02-RECOVERY.md). Read "Which situation are you in?" before you write, and go there if a write went wrong. How to get a camera back through the SD updater or a chip programmer, how to fix firmware for the other sensor from the card, and the donor route.
3. [Candidate files](03-FLASH-CANDIDATES.md). Three withdrawn files from the first write, and the stock restore file. History only.
4. [Putting the file on the card](04-STAGE.md). How the `stage` command copies an update file to the card, and what each refusal means.
5. [SD card problems](05-CARDS.md). Why Android phones reject a card the camera has used, and the fix.
6. [Using an Android phone](06-ANDROID.md). What a phone can do (the SD route) and what needs a computer (the CH341A route).

## Commands and mods

7. [Command reference](07-USAGE.md). Every command with examples, grouped by what it writes to.
8. [Photo frames](08-FRAMES.md). Replacing the four photo frames with your own PNGs.
9. [Resource pack and boot logo](09-RESOURCES.md). Everything in the resource pack, and how to make a boot logo that fits.
10. [Photo dates (EXIF)](10-EXIF.md). What's wrong with the camera's photo and video dates, and how `exif-fix` repairs copies.

## Reference and background

11. [Image layout](11-FORMAT.md). What is where in the flash, the settings fields, the update-file rules.
12. [Boot and power notes](12-NOTES-boot-and-power.md). The boot sequence, and the auto-off and standby analysis behind `tweak` and `settings`.
13. [Research report](13-PHASE1-REPORT.md). The research everything here is built on, with each claim labelled verified, reported or inferred.

## Repairing a camera that got firmware for the other sensor

14. [OV9732 repair scope](14-OV9732-REPAIR-SCOPE.md). Why v006 can't drive an OV9732 camera, and the plan the bring-up follows.
15. [OV9732 exposure, colour and white balance](15-OV9732-AE-WB-FLASH-PORT.md). The bring-up builds, what each changes, and what each measured on camera.

## Incident notes and the camera's controls

- [The first flash: what was done and what went wrong](FIRST-FLASH-INCIDENT.md). Notes on the 2026-11-06 incident, kept as written.
- [The camera's buttons and screens](CAMERA-UI.md). Photos of every control and stock screen. Check it before you assume a button or menu exists.

Back to the [project README](../README.md).
