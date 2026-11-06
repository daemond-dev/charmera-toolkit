# Charmera Card for Android

A small Android app (7.0 or later) that does the whole first flash from the phone, with no computer: it downloads
the public stock firmware dump, makes one of the three reviewed update files from it, writes it to the camera's
card over the camera's USB-C cable, and walks you through flashing and cleaning up. The camera flashes itself from
the card, as described in [The first flash](../docs/01-FIRST-FLASH.md).

It uses the network only to download the stock dump from its upstream repository on GitHub, at a fixed commit, and
accepts the download only if its SHA-256 is that of the public dump. No firmware is shipped inside the app. It
reaches the card through Android's own folder picker, which grants access to the one folder you choose, so it needs
no storage permission.

Status: built and its checks tested on a computer; it has not yet been run on a phone.

## Before you flash

- **Original 2025 series only:** matte body, yellow Kodak boot screen. Never flash a Millennium Edition (blue boot
  screen) or a CLOT edition with these files: owners report that the firmware boots there but the live view freezes
  or the camera cannot take a picture ([Recovery, section D](../docs/02-RECOVERY.md#d-wrong-edition)).
- **Charge the battery fully first.** Programming takes 15 to 20 seconds, and a power loss in that window leaves a
  camera that only a chip programmer can bring back ([Recovery](../docs/02-RECOVERY.md)).
- **Stored photos:** the [flash candidates](../docs/03-FLASH-CANDIDATES.md) erase the two photos the camera keeps in
  its own memory.

## Install

The repository is private, so first sign in to GitHub in the phone's browser, with an account that can see the
repository. Signed out, GitHub answers the download link with 404 (Not Found). Then download
[charmera-card.apk](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk)
from the [android-app release](https://github.com/daemond-dev/charmera-toolkit/releases/tag/android-app), open it,
and allow your browser or file manager to install apps when Android asks. Play Protect may warn that it does not
know the app; that is because it is not from the Play Store. Or download the APK on a computer and copy it to the
phone over USB.

## Use

The app's screen is the same five steps.

1. **Make the update file.** Tap **Download and make the file**, then choose C, A or B
   ([what each changes](../docs/03-FLASH-CANDIDATES.md)); start with C. The app checks the download, makes the file
   the same way the computer toolkit's recipe does, and uses it only if its SHA-256 equals the reviewed file's, so
   it can make nothing else. The result is checked again the way `charmera verify` checks a file, and each check is
   listed. Offline, or made a file on a computer? **Use a file on the phone** takes the stock dump (downloaded in a
   browser from [its page](https://github.com/benbadio/keychain-camera-plus/blob/209c25a47ce91c805b663edb6e18861b367a5a1b/dumps/charmera_stock_latest.bin))
   or a `JH_5408…bin` file that `charmera pack` wrote.
2. **Connect the camera.** Card in the camera, connect the camera with a USB-C data cable: it shows its card as a
   drive. Tap **Choose the camera's drive**, open that drive in the picker and tap "Use this folder" at its top
   level. A USB card reader works the same way. The phone's own SD slot works only on Android 7 to 10: from
   Android 11 on, Android does not let an app choose the top level of a card in the phone's slot ("Use this folder"
   is greyed out there). The app remembers the drive and looks at it again whenever you come back to the app.
3. **Write.** The app refuses a card that already holds an update file, and lists the card again just before it
   writes. It writes the file under a temporary name, renames it to the exact name the camera looks for, and reads
   it back to compare SHA-256. If any of that fails, it lists the card again and says what is on it now.
4. **Flash.** Eject the drive in Android's storage settings and unplug the cable. Switch the camera off, then on
   again with the card in: it looks for the file only in its first five seconds and shows the update progress. At
   100% it stays on its last screen. Take the card out first, then hold the power key about a second to switch off.
5. **Clean up.** The camera does not delete the file and would flash it again at every power-on. With the card
   still out, switch the camera on, wait 10 seconds, insert the card, connect the camera to the phone, and tap
   **Remove update files**. Then switch the camera off and on normally and check the boot screen, the live view and
   a test photo.

## Through the camera's USB-C cable

The card can stay in the camera. Connected by USB, the camera presents its SD card as a USB drive (USB ID 1B3F:8301,
"GENPLUS USB-MSDC DISK A"), and the owner has confirmed that the drive appears on an Android phone connected over
USB-C. Writing an update file this way and then flashing from it has not been tried yet.

1. Connect the camera to the phone with a USB-C data cable. The camera's card shows up as a USB drive.
2. In the app, choose the update file as usual. Under **Choose the SD card**, pick the camera's drive in the picker
   (it is listed with the phone's other storage, like a USB stick) and tap "Use this folder" at its top level.
3. Tap **Write the file to the card**.
4. Eject the drive in Android (Settings, Storage, the USB drive, Eject), then unplug the cable.
5. Switch the camera off, then on again, with a fully charged battery. The camera only looks for the update file in
   the first five seconds after power-on, so it does not notice a file added while it is running. Whether the
   updater runs when the camera starts up while still plugged in is unknown, so unplug first.
6. After 100% the camera stays on its final screen and does not delete the file. Take the card out of the camera,
   then hold the power key about a second to switch it off.
7. To remove the file through the cable: switch the camera on without its card and wait 10 seconds (the updater
   looks at the card only in the first five seconds after power-on), insert the card, connect the camera to the
   phone, and tap **Remove update files**. A card reader works as well. Never switch the camera on with the update
   file on its card except to flash.

The same limits apply as with a card reader. If the camera last wrote to the card, Android may refuse the drive
(the dirty flag, [SD card problems](../docs/05-CARDS.md)). If the drive does not appear at all, try another
cable; a charge-only cable has no data lines.

## What it checks

The checks `charmera verify` makes for a 0x1E0000-byte file, from `src/.../UpdateCheck.java`. A FAIL blocks the
write and a WARN does not.

- **Name:** the name must contain `JH_5408<12-digit stamp>_<8 hex digits>`. A copy renamed by the phone, such
  as `... (1).bin`, is accepted and written to the card under the correct name.
- **Checksum:** the sum in the name must equal the byte sum of the file.
- **Stamp:** the stamp must differ from the camera's own.
- **Size:** the size must be exactly 0x1E0000 bytes.
- **Build:** the file must start with `SPII` and carry the original 2025 series build stamps.
- **Sensor table:** the image-sensor set-up table must be the stock one. No toolkit mod changes that table, and
  the wrong table is what stops other editions from taking pictures.
- **Resource pack:** every entry of the pack must lie inside the pack and the file, must not be erased, and must
  hold what its name says: a `.WAV` is a RIFF WAVE with fmt and data chunks that fits its slot, a `.JPG` runs from
  FF D8 to an FF D9 inside its slot, a `.GPZP` or `.GPZ` inflates, matches its CRC-32 and length trailer and uses
  fixed-Huffman blocks only (as every stock stream does), and `GPRS.PAK` starts with `GPRS`. The camera checks
  none of this, and it opens the boot logo at every boot before it mounts the card, so a damaged entry could leave
  a camera that the SD updater cannot reach. A cut-off file padded out to full size fails here.
- **Settings and photos:** a damaged settings record or stored photos are warnings. A settings record next to an
  erased photo directory is a failure.
- **Mods:** the app says whether bytes 0..0x170000 are stock, whether a mod keeps bytes 0..0x120000 (boot header,
  code and data) stock and changes only the resources, or whether it changes the boot header, the code or its data.

Where it differs from `verify`:

- **Other file sizes:** it refuses any size other than 0x1E0000 bytes. Use the computer for anything unusual.
- **Mods:** a file whose software differs from stock is a WARN here, as in `charmera stage`. `verify` without
  `--allow-modified` fails it.

## What it cannot do

- **Unreadable cards:** it cannot make Android read a card the camera has marked dirty. Format the card in the
  phone, or run `charmera card-fix` on a computer ([SD card problems](../docs/05-CARDS.md)).
- **Building files:** it does not build update files or mods. Make them with the toolkit on a computer, or in
  Termux ([Using an Android phone](../docs/06-ANDROID.md)). Because the repository is private, `pip install` from
  its GitHub URL in Termux needs a GitHub personal access token; or download the source on the phone while signed
  in to GitHub and run `pip install` on the unpacked folder.
- **Read-back:** the comparison may read Android's cached copy rather than the card itself. Ejecting the card
  before removing it is what makes sure the data reached the card.
- **The CH341A route:** it cannot back up or restore the chip ([Recovery, section B](../docs/02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)).
- **Card root on Android 11 and later:** Android blocks the picker from granting access to the top level of the
  phone's internal storage and of an SD card in the phone's own slot. USB card readers and the camera's USB drive
  are not blocked. On those phones use a reader or the camera's cable, or copy the file with the Files app and have
  the app check the copy (step 2 of [Use](#use)).

## Build

`./build.sh [versionName] [versionCode]` with `SDK` pointing at an Android SDK that has `platforms;android-35`
and `build-tools;35.0.0`, a JDK 17 or newer, and `zip`. The version name defaults to the one in `VERSION`. It runs
the checker's and the card rules' tests on the plain JVM first (`test/UpdateCheckTest.java` and
`test/CardStateTest.java`; set `CHARMERA_UPDATE_FILES` to real update files to check those too), then builds and
signs `out/charmera-card-<version>.apk`. No Gradle.

GitHub Actions builds it on every push to `main` that touches `android/` and publishes it as the `android-app`
release (`.github/workflows/android.yml`), one run at a time. Every published build is signed with the same release
key, so a new version installs over the old one. The key lives only in the repo secrets `SIGNING_KEYSTORE_B64`,
`SIGNING_ALIAS` and `SIGNING_PASS`. Before publishing, the workflow checks that the APK's certificate matches
`SIGNING_CERT_SHA256` in this folder, and it refuses to publish anything else. Without the secrets a build is
signed with a throwaway key and kept only as a run artifact.

Keep a copy of the three secret values somewhere safe: GitHub never shows a secret again once it is saved. If the
key is lost, make a new one, save it in the secrets, put its certificate SHA-256 (from `apksigner verify
--print-certs`) in `SIGNING_CERT_SHA256`, and uninstall the app once before installing the first build signed with
it.
