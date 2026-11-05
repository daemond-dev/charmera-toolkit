# Charmera Card for Android

A small Android app (7.0 or later) that puts a Charmera update file on an SD card from the phone. The camera
then flashes itself from the card, as described in [The first flash](../docs/01-FIRST-FLASH.md). The app needs no
permissions and no network: it reaches the card through Android's own folder picker, which grants access to the
one folder you choose.

Status: built and its checks tested on a computer; it has not yet been run on a phone.

## Install

On the phone, download
[charmera-card.apk](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk)
from the [android-app release](https://github.com/daemond-dev/charmera-toolkit/releases/tag/android-app), open it,
and allow your browser or file manager to install apps when Android asks. Play Protect may warn that it does not
know the app; that is because it is not from the Play Store.

## Use

1. **Choose update file.** Pick the `JH_5408…bin` file that `charmera pack` wrote, or open it from a file manager
   with "Open with" or "Share". The app checks it and lists each check, the way `charmera verify` does.
2. **Choose the SD card.** Put the camera's card in the phone's slot or a USB card reader. In the picker, open the
   card itself and tap "Use this folder" at its top level. The app remembers the card.
3. **Write the file to the card.** The app refuses a card that already holds an update file, writes the file under
   a temporary name, renames it to the exact name the camera looks for, and reads it back to compare SHA-256.
4. Eject the card in Android's storage settings, put it in the camera, and flash
   ([step 5 of The first flash](../docs/01-FIRST-FLASH.md#5-flash-the-point-of-no-return)).
5. Afterwards, put the card back in the phone and tap **Remove update files**. The camera does not delete the file
   and would flash it again at every power-on.

## What it checks

The same checks as `charmera verify`, from `src/.../UpdateCheck.java`. A FAIL blocks the write and a WARN does not.

- **Name:** the name must contain `JH_5408<12-digit stamp>_<8 hex digits>`. A copy renamed by the phone, such
  as `... (1).bin`, is accepted and written to the card under the correct name.
- **Checksum:** the sum in the name must equal the byte sum of the file.
- **Stamp:** the stamp must differ from the camera's own.
- **Size:** the size must be exactly 0x1E0000 bytes.
- **Build:** the file must start with `SPII` and carry the original 2025 series build stamps.
- **Settings and photos:** a damaged settings record or stored photos are warnings. A settings record next to an
  erased photo directory is a failure.
- **Mods:** the app says whether the file is stock or a mod, and whether a mod changes the code.

It is stricter than `verify` in two places:

- **Other file sizes:** it refuses any size other than 0x1E0000 bytes. Use the computer for anything unusual.
- **Sensor table:** it refuses a file whose image-sensor set-up table differs from stock. No toolkit mod
  changes that table, and the wrong table is what stops other editions from taking pictures.

## What it cannot do

- **Unreadable cards:** it cannot make Android read a card the camera has marked dirty. Format the card in the
  phone, or run `charmera card-fix` on a computer ([SD card problems](../docs/05-CARDS.md)).
- **Building files:** it does not build update files or mods. Make them with the toolkit on a computer, or in
  Termux ([Using an Android phone](../docs/06-ANDROID.md)).
- **Read-back:** the comparison may read Android's cached copy rather than the card itself. Ejecting the card
  before removing it is what makes sure the data reached the card.
- **The CH341A route:** it cannot back up or restore the chip ([Recovery, section B](../docs/02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)).
- **Card root on Android 11 and later:** Android blocks the picker from granting access to the root of internal
  storage. For removable SD cards and USB readers the root is expected to be allowed, but this is not tested on
  your phone. If "Use this folder" is greyed out at the card's top level, copy the file with the Files app as in
  [Using an Android phone](../docs/06-ANDROID.md).

## Build

`./build.sh [versionName] [versionCode]` with `SDK` pointing at an Android SDK that has `platforms;android-35`
and `build-tools;35.0.0`, a JDK 17 or newer, and `zip`. It runs the checker's tests on the plain JVM first
(`test/UpdateCheckTest.java`; set `CHARMERA_UPDATE_FILES` to real update files to check those too), then builds and
signs `out/charmera-card-<version>.apk`. No Gradle.

GitHub Actions builds it on every push to `main` that touches `android/` and publishes it as the `android-app`
release (`.github/workflows/android.yml`). Without the repo secrets `SIGNING_KEYSTORE_B64`, `SIGNING_ALIAS` and
`SIGNING_PASS`, each build is signed with a new throwaway key. A new build then cannot install over an old one:
uninstall the app first. The only thing lost is the remembered card.
