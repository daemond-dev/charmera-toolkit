package io.github.daemonddev.charmera;

/**
 * Whether the update file in step 1 may go to the card now, written by the app or saved as a copy for the card. No
 * Android imports, so the rule runs on a plain JVM in android/test/WriteGateTest.java, with the states each race in
 * MainActivity can leave behind; MainActivity asks it for the buttons, when a confirmation opens, when Write or Save
 * is tapped, and the worker asks it once more just before it writes.
 *
 * No backup of the camera, no flash: the file must have passed its checks against the backup on record now, not
 * against an earlier one, and nothing may be about to change that: no backup still being read, no check of the file
 * still running. A check names the backup it was made against by SHA-256 (UpdateCheck.Result.referenceSha256).
 */
final class WriteGate {

    private WriteGate() {
    }

    /**
     * Null when data may go to the card, otherwise why not, as a plain clause in lower case without a final full stop
     * (as UpdateCheck.confirms gives it), so the screen can show it after "Nothing was written to the card: ".
     *
     * @param check         the check the file passed, or null
     * @param data          the bytes that would go to the card, or null
     * @param backupSha256  the SHA-256 of the backup on record now, or null when there is none
     * @param backupLoading a backup is being read, and will replace the one on record
     * @param checking      the file is being checked again, against the backup on record now
     */
    static String refusal(UpdateCheck.Result check, byte[] data, String backupSha256, boolean backupLoading,
                          boolean checking) {
        if (backupLoading) {
            return "a backup is still being read in step 0; wait until step 0 says which backup is on record";
        }
        if (backupSha256 == null) {
            return "no backup of this camera is on record; choose it in step 0 first";
        }
        if (checking) {
            return "the update file is being checked again, against the backup now on record; wait for step 1";
        }
        String refusal = UpdateCheck.confirms(check, data);
        if (refusal != null) {
            return refusal;
        }
        if (!check.compared || !backupSha256.equals(check.referenceSha256)) {
            return "the update file was not checked against the backup on record now; step 1 checks it again";
        }
        return null;
    }
}
