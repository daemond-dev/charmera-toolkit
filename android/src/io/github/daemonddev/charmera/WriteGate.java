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
 *
 * The exception: every reviewed file (Candidates.isReviewedPatch): the sensor-ID probe, every Repair version and
 * every build of the recipe table, whatever sensor it drives, the stock restore file among them. Each is a fixed file
 * this project generated and lists by SHA-256, so it may go to the card without a backup on record: whether it fits
 * the camera is the owner's call. The app warns when nothing confirms what the camera runs (Recipe.unconfirmed) and
 * when the backup on record drives the other sensor (Candidates.wrongSensor); it does not refuse. A reviewed SP140A
 * build that was compared with a backup must have been compared with the one on record now (it is checked again when
 * the backup changes). The waiver is recognised only by the reviewed SHA-256 of the bytes that passed the check, which
 * confirms() ties to the data about to be written, so it can never reach any other file; every file-level check still
 * runs, and a file still being checked waits.
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
        // The reviewed files are the ones the app writes without a backup on record (see the class note). They are
        // recognised only by the reviewed SHA-256 of the bytes that passed the check, and confirms() ties the data
        // about to be written to that same check, so this waiver can never reach any other file. A reviewed SP140A
        // build's check must be the one made with the backup on record now (or with none, when there is none).
        if (check != null && Candidates.isReviewedPatch(check.sha256)) {
            if (checking) {
                return "the update file is being checked again; wait for the file step";
            }
            if (!Candidates.checkedAlone(check.sha256)
                    && (backupLoading || !java.util.Objects.equals(backupSha256, check.referenceSha256))) {
                return "the update file was not checked against the backup on record now; the file step checks it again";
            }
            return UpdateCheck.confirms(check, data);
        }
        if (backupLoading) {
            return "a backup is still being read; wait until the backup step says which backup is on record";
        }
        if (backupSha256 == null) {
            return "no backup of this camera is on record; choose it in the backup step first (Choose this camera's backup)";
        }
        if (checking) {
            return "the update file is being checked again, against the backup now on record; wait for the file step";
        }
        String refusal = UpdateCheck.confirms(check, data);
        if (refusal != null) {
            return refusal;
        }
        if (!check.compared || !backupSha256.equals(check.referenceSha256)) {
            return "the update file was not checked against the backup on record now; the file step checks it again";
        }
        return null;
    }
}
