package io.github.daemonddev.charmera;

/**
 * Whether the update file in step 1 may go to the card now, written by the app or saved as a copy for the card. No
 * Android imports, so the rule runs on a plain JVM in android/test/WriteGateTest.java, with the states each race in
 * MainActivity can leave behind; MainActivity asks it for the buttons, when a confirmation opens, when Write or Save
 * is tapped, and the worker asks it once more just before it writes.
 *
 * Warn, don't block: a file goes to the card unless it is a known brick or a check is not settled. Always refused:
 * any FAIL from the file-level checks (UpdateCheck.confirms: a 2 MB dump fails its size line, so a backup never goes
 * on the card as it is), the withdrawn candidates (Candidates.withdrawn), and the race states: a backup still being
 * read, a check of the file still running (wait, then the file step checks again). A check names the backup it was
 * made against by SHA-256 (UpdateCheck.Result.referenceSha256).
 *
 * Every reviewed file (Candidates.isReviewedPatch): the sensor-ID probe, every Repair version and every build of the
 * recipe table, whatever sensor it drives, the stock restore file among them. Each is a fixed file this project
 * generated and lists by SHA-256, so it may go to the card without a backup on record: whether it fits the camera is
 * the owner's call. The app warns when nothing confirms what the camera runs (Recipe.unconfirmed) and when the backup
 * on record drives the other sensor (Candidates.wrongSensor); it does not refuse. A reviewed SP140A build that was
 * compared with a backup must have been compared with the one on record now (it is checked again when the backup
 * changes). Reviewed is recognised only by the reviewed SHA-256 of the bytes that passed the check, which confirms()
 * ties to the data about to be written.
 *
 * Any other file (an unknown file) may go to the card too, with no backup on record: the app warns loudly that it is
 * not one of this project's reviewed builds and was not compared with this camera's firmware (MainActivity's
 * UNKNOWN_FILE, shown when unknownNoBackup). When a backup is on record, an unknown file must have been compared with that backup, the one on
 * record now; a check made against another backup, or before one was on record, waits for the file step's new check.
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
        // The withdrawn candidates are refused whatever their checks say (and whatever backup is on record).
        if (check != null && Candidates.withdrawn(check.sha256) != null) {
            return "this is an early test build the project has withdrawn, so don't put it on the card";
        }
        // The reviewed files are the ones the app writes without a backup on record (see the class note). They are
        // recognised only by the reviewed SHA-256 of the bytes that passed the check, and confirms() ties the data
        // about to be written to that same check, so this waiver can never reach any other file. A reviewed SP140A
        // build's check must be the one made with the backup on record now (or with none, when there is none).
        if (check != null && Candidates.isReviewedPatch(check.sha256)) {
            if (checking) {
                return "the update file is being checked again, so wait for that to finish";
            }
            if (!Candidates.checkedAlone(check.sha256)
                    && (backupLoading || !java.util.Objects.equals(backupSha256, check.referenceSha256))) {
                return "the update file wasn't checked against the backup chosen now, so the app checks it again";
            }
            return UpdateCheck.confirms(check, data);
        }
        if (backupLoading) {
            return "a backup is still being read, so wait until the backup step shows which one is chosen";
        }
        if (checking) {
            return "the update file is being checked again against the backup you chose, so wait for that to finish";
        }
        String refusal = UpdateCheck.confirms(check, data);
        if (refusal != null) {
            return refusal;
        }
        // An unknown file is written with no backup on record (a loud warning on screen, not a refusal). With one on
        // record, its check must be the comparison with that backup; with none, the check made with none.
        if (backupSha256 == null ? check.referenceSha256 != null
                : !check.compared || !backupSha256.equals(check.referenceSha256)) {
            return "the update file wasn't checked against the backup chosen now, so the app checks it again";
        }
        return null;
    }
}
