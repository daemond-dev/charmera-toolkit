# Documentation

Read in this order. Start with the first one: it walks through a flash and links to each of the others at the step that needs it. The first group is what to read before flashing anything; the second covers the commands and mods; the third is reference.

## Before and during a flash

1. [The first flash](01-FIRST-FLASH.md): Start here. The whole path from backup to flashed camera, with the point of no return marked; links to everything else when you need it.
2. [Recovery](02-RECOVERY.md): Read before you flash. How to get a camera back through the SD updater or a CH341A clip, and which editions must never be flashed.
3. [Flash candidates](03-FLASH-CANDIDATES.md): The first three files to try, what each changes, their hashes, and the order to try them.
4. [Putting the file on the card](04-STAGE.md): The `stage` command: copying an update file to the card safely, and what each refusal means.
5. [SD card problems](05-CARDS.md): Why phones and some computers reject a card the camera has used, and the fix.
6. [Using an Android phone](06-ANDROID.md): What a phone can do (the SD route) and what needs a computer (the CH341A route).

## Commands and mods

7. [Command reference](07-USAGE.md): Every command with examples, grouped by what it writes to.
8. [Photo frames](08-FRAMES.md): Replacing the four photo frames with your own PNGs.
9. [Resource pack and boot logo](09-RESOURCES.md): Everything in the resource pack, and how to make a boot logo that fits.
10. [Photo dates (EXIF)](10-EXIF.md): What is wrong with the camera's photo and video dates and how `exif-fix` repairs copies.

## Reference and background

11. [Image layout](11-FORMAT.md): Reference: what is where in the flash, the settings fields, the update-file rules.
12. [Boot and power notes](12-NOTES-boot-and-power.md): Background: the boot sequence, auto-off and standby analysis behind `tweak` and `settings`.
13. [Research report](13-PHASE1-REPORT.md): Background: the research everything is built on, each claim labelled verified, reported or inferred.

Back to the [project README](../README.md).
