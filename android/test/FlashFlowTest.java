package io.github.daemonddev.charmera;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.daemonddev.charmera.FlashFlow.Live;
import io.github.daemonddev.charmera.FlashFlow.Route;
import io.github.daemonddev.charmera.FlashFlow.Stage;
import io.github.daemonddev.charmera.FlashFlow.Step;

/**
 * Plain-JVM tests for the flash run: each flashing route's steps through the sequences the review rounds walked (the
 * camera unplugged on purpose mid-flow, ejects the app did not see, cables pulled at each stage, restarts at each
 * stage, files found on the card, a second file, a backup changed, removals confirmed only by a listing), asserting
 * the current step, the locks and their notes, when Remove opens, the synced claim, the drive line and the next-step
 * sentences. Every state reached is also held to the rules that hold for all of them (invariants()), and a seeded walk
 * of random events holds them across many more.
 *
 * describe() writes a route's steps as words, one per step in order: "done", "NOW" (the current step), "open"
 * (another open step), or "lockN" (locked, its note "Do step N first.").
 */
public final class FlashFlowTest {

    private static int passes = 0;
    private static int failures = 0;
    /** 11 Nov 2026 13:02 UTC. */
    private static final long T = 1794402120000L;
    private static final String BRINGUP = "2f0c6f7c3a5b4d9e8a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6071";
    private static final String BRINGUP_NAME = "JH_5408202611100022_0F061D17.bin";
    private static final String PROBE = "9d8c7b6a5f4e3d2c1b0a99887766554433221100ffeeddccbbaa998877665544";
    private static final String PROBE_NAME = "JH_5408202611090001_1A2B3C4D.bin";
    private static final String RESTORE_A = "b10a2f1c00112233445566778899aabbccddeeff00112233445566778899aabb";
    private static final String RESTORE_B = "c20b3e2d00112233445566778899aabbccddeeff00112233445566778899aabb";
    private static final String RESTORE_NAME = "JH_5408202505201029_00000000.bin";
    private static final String OTHER_NAME = "JH_5408202601010101_DEADBEEF.bin";
    private static final String BACKUP_1 = "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String BACKUP_2 = "2222222222222222222222222222222222222222222222222222222222222222";
    private static final Pattern STEP_N = Pattern.compile("[Ss]tep (\\d+)");
    private static final Pattern LOCK = Pattern.compile("Do step (\\d+) first\\.");

    public static void main(String[] args) {
        normalCycleOnEachRoute();
        lockNotesNameTheFirstOpenStep();
        ejectOnAndroidsPageThenUnplugBeforeComingBack();
        cablePulledWithNoEjectAtEachStage();
        theDriveStepStaysDoneAndItsLineFollowsTheStage();
        alreadyOnTheCard();
        secondDifferentFile();
        restoreBackupChangedAfterTheWrite();
        removalConfirmedOnlyByAListing();
        unplugTwiceAfterTheRemoval();
        reconnectWhileTheCardIsBeingRead();
        answersSeenAndNotSeen();
        syncedOnlyAfterAWriteOnThisMount();
        removeBeforeAnyWrite();
        removalsOnOtherRoutes();
        staleAndFinishedRuns();
        aFailedFlashCheck();
        probeSensorFileAcrossARestart();
        processDeathAtEachStage();
        parseIsSafe();
        randomWalk();

        System.out.printf(Locale.ROOT, "FlashFlowTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // --- the scenarios ---------------------------------------------------------------------------------------------

    /** The full cycle on each route: write, eject, flash, Yes, reconnect, Remove, the listing, the check or read. */
    static void normalCycleOnEachRoute() {
        // Flash custom firmware: 1 Before you start, 2 drive, 3 write, 4 eject+flash, 5 Remove, 6 Flash check
        FlashFlow f = new FlashFlow(Route.REPAIR);
        Live l = new Live();
        see("Repair before Continue", f, l, "NOW lock1 lock1 lock1 lock1 lock1");
        l.started = true;
        see("Repair after Continue, no drive", f, l, "done NOW lock2 lock2 lock2 lock2");
        l.driveReady = true;
        see("Repair with the drive listed: the write is current", f, l, "done done NOW lock3 lock3 lock3");
        expect("nothing written: no synced claim, no Remove", !f.syncedClaim() && !f.removeAvailable(l));
        f.written(BRINGUP, BRINGUP_NAME, true, T, "", "v0.23");
        see("Repair written: the flash step is current, Remove waits for the answer", f, l,
                "done done done NOW lock4 lock4");
        expect("a write that worked on this mount may say synced", f.syncedClaim());
        expect("written: the folded write line names the file", f.writeLine().equals("Written: " + BRINGUP_NAME + "."));
        f.listing(BRINGUP_NAME);
        see("the written file listed: Remove stays locked before the flash (round 1 #11)", f, l,
                "done done done NOW lock4 lock4");
        f.ejectSeen();
        expect("an eject keeps the synced claim (Android may take it back as a pull)", f.syncedClaim());
        f.driveLeft();
        Live away = unplugged(l);
        see("ejected and unplugged to flash: same steps", f, away, "done done done NOW lock4 lock4");
        expect("the drive gone ends the synced claim's mount", !f.syncedClaim());
        expect("unplugged to flash: the drive line says to flash as step 4 says (round 5 #0)",
                f.unpluggedHint(away).equals("Unplugged: flash the camera as step 4 says, then answer the question."));
        f.answered(true);
        see("Yes: the flash step is done, Remove is current", f, away, "done done done done NOW lock5");
        expect("Yes: the answer line points at Remove, which is open",
                f.answerLine(away).equals("Logged: flash seen, v0.23. Next: remove the update file."));
        expect("Yes, unplugged: the drive line gives the safe order", f.unpluggedHint(away).equals(FlashFlow.SAFE_ORDER));
        expect("Yes, unplugged: reconnect to remove", f.afterUnseenEject(away).equals(FlashFlow.RECONNECT_TO_REMOVE));
        f.mountChanged();
        f.listing(BRINGUP_NAME);
        see("reconnected, the file listed: Remove is current", f, l, "done done done done NOW lock5");
        f.removalWorked(Route.REPAIR);
        see("removed, no listing yet: Remove stays open, the check waits", f, l, "done done done done NOW lock5");
        expect("removed: the folded flash line says no more 'Next: remove' (round 5 #3, #16)",
                f.flashSummary(l).equals("Logged: flash seen, v0.23.") && f.answerLine(l).equals(f.flashSummary(l)));
        expect("removed, not yet listed: an unplug now says to reconnect so the app can see it",
                f.afterUnseenEject(away).equals("Reconnect the camera, so the app can see that the update file is gone."));
        f.listing();
        see("the listing shows no update file: Remove done, the check is current", f, l,
                "done done done done done NOW");
        expect("removed and listed: go on with the open check", f.afterUnseenEject(away)
                .equals("Go on with step 6, and reconnect the camera when it says so."));
        expect("removed and listed: no update file, so the plain order", f.unpluggedHint(away)
                .equals(FlashFlow.PLAIN_ORDER));
        f.checked(true, "New photo found, written by this firmware family ✓");
        see("check passed: every step done", f, l, "done done done done done done");
        expect("all done: no current step", f.view(l).current() == -1 && f.view(l).currentStep() == null);

        // Identify the image sensor: 1 drive, 2 write, 3 eject+flash, 4 Remove, 5 Read
        FlashFlow p = new FlashFlow(Route.PROBE);
        Live pl = new Live();
        see("Probe with no drive", p, pl, "NOW lock1 lock1 lock1 lock1");
        pl.driveReady = true;
        see("Probe with the drive: Remove and Read name step 2, not the locked step 3 (round 5 #4)", p, pl,
                "done NOW lock2 lock2 lock2");
        p.written(PROBE, PROBE_NAME, true, T, "", "probe");
        p.listing(PROBE_NAME);
        see("Probe written", p, pl, "done done NOW lock3 lock3");
        p.answered(true);
        see("Probe Yes", p, unplugged(pl), "done done done NOW lock4");
        p.removalWorked(Route.PROBE);
        p.listing();
        see("Probe removed: Read is current", p, pl, "done done done done NOW");
        expect("Probe removed: go on with step 5", p.afterUnseenEject(pl)
                .equals("Go on with step 5, and reconnect the camera when it says so."));
        p.checked(true, "A sensor answered");
        see("Probe read: the Read step stays open with its result", p, pl, "done done done done NOW");

        // Restore stock firmware: 1 backup, 2 file, 3 drive, 4 write, 5 eject+flash, 6 Remove
        FlashFlow r = new FlashFlow(Route.RESTORE);
        Live rl = new Live();
        see("Restore with nothing: every step names step 1 (round 5 #4)", r, rl, "NOW lock1 lock1 lock1 lock1 lock1");
        rl.backupOnRecord = true;
        see("Restore with the backup", r, rl, "done NOW lock2 lock2 lock2 lock2");
        rl.fileReady = true;
        see("Restore with the file", r, rl, "done done NOW lock3 lock3 lock3");
        rl.driveReady = true;
        see("Restore with the drive", r, rl, "done done done NOW lock4 lock4");
        r.written(RESTORE_A, RESTORE_NAME, true, T, BACKUP_1, "stock restore");
        r.listing(RESTORE_NAME);
        see("Restore written", r, rl, "done done done done NOW lock5");
        r.answered(true);
        r.removalWorked(Route.RESTORE);
        r.listing();
        see("Restore removed: every step done", r, rl, "done done done done done done");
        expect("Restore removed after Yes: the last step says only what is known, naming no further step (round 3 #7)",
                r.afterUnseenEject(rl).equals("The update file is removed. Switch the camera on to see whether it"
                        + " starts."));
    }

    /** Round 5 #4: before the write, the steps behind the locked flash step name the open write step. */
    static void lockNotesNameTheFirstOpenStep() {
        FlashFlow f = new FlashFlow(Route.REPAIR);
        Live l = ready();
        FlashFlow.View v = f.view(l);
        expect("Repair before the write: Remove says do step 3 first", "Do step 3 first.".equals(v.lockReason(Step.REMOVE)));
        expect("Repair before the write: Flash check says do step 3 first",
                "Do step 3 first.".equals(v.lockReason(Step.CHECK)));
        expect("Repair before the write: the eject step says do step 3 first",
                "Do step 3 first.".equals(v.lockReason(Step.FLASH)));
        expect("an open step has no lock note", v.lockReason(Step.WRITE) == null && v.open(Step.WRITE));
        FlashFlow p = new FlashFlow(Route.PROBE);
        expect("Probe before the write: Read says do step 2 first", "Do step 2 first."
                .equals(p.view(ready()).lockReason(Step.READ)));
        // After a write with an unanswered question, Read (needs the removal) names the open flash step, not Remove.
        p.written(PROBE, PROBE_NAME, true, T, "", "probe");
        expect("Probe written: Read names the open step 3", "Do step 3 first.".equals(p.view(ready())
                .lockReason(Step.READ)));
    }

    /**
     * Round 2 #0, round 3 #0/#2: the owner taps Eject, ejects on Android's page and unplugs before coming back, so the
     * app sees the drive gone but no eject. The answer still counts; reconnecting before it opens nothing.
     */
    static void ejectOnAndroidsPageThenUnplugBeforeComingBack() {
        for (Route route : Route.values()) {
            FlashFlow f = cycleTo(route, Stage.WRITTEN);
            Live l = ready();
            Live away = unplugged(l);
            f.driveLeft();
            expect(route + ": unseen eject before the answer: flash now", f.afterUnseenEject(away)
                    .equals(flashNow(route)));
            expect(route + ": the flash step is current while unplugged", f.view(away).currentStep() == Step.FLASH);
            // reconnecting before the answer (round 3 #2): Remove must not open for a file never flashed
            f.mountChanged();
            f.listing(f.cardName());
            expect(route + ": reconnect before the answer opens no Remove", !f.view(l).open(Step.REMOVE)
                    && f.view(l).currentStep() == Step.FLASH);
            invariants(route + " reconnected before the answer", f, l);
            f.driveLeft();
            f.answered(true);
            expect(route + ": answered with no eject seen: Remove is current", f.view(away).currentStep() == Step.REMOVE
                    && f.view(away).done(Step.FLASH));
            invariants(route + " answered after an unseen eject", f, away);
            // the same answer after a seen eject gives the same steps: the eject is advice, not a gate
            FlashFlow g = cycleTo(route, Stage.WRITTEN);
            g.ejectSeen();
            g.driveLeft();
            g.answered(true);
            expect(route + ": the eject seen or not, the answer gives the same steps",
                    describe(g, away).equals(describe(f, away)));
            expect(route + ": reconnect to remove", f.afterUnseenEject(away).equals(FlashFlow.RECONNECT_TO_REMOVE));
            f.mountChanged();
            f.listing(f.cardName());
            f.removalWorked(route);
            f.listing();
            expect(route + ": removed and listed with no eject anywhere: Remove done", f.view(l).done(Step.REMOVE));
            invariants(route + " removed with no eject", f, l);
        }
    }

    /** The cable pulled with no eject at each stage: nothing moves, and the next-step sentence fits the stage. */
    static void cablePulledWithNoEjectAtEachStage() {
        String[][] want = {
                // stage reached, Repair's sentence, Probe's, Restore's
                // nothing known of the card yet: it may hold an update file, so the safe order
                {"NONE", "When you connect it again: " + FlashFlow.SAFE_ORDER,
                        "When you connect it again: " + FlashFlow.SAFE_ORDER,
                        "When you connect it again: " + FlashFlow.SAFE_ORDER},
                {"WRITTEN", flashNow(Route.REPAIR), flashNow(Route.PROBE), flashNow(Route.RESTORE)},
                {"ANSWERED", FlashFlow.RECONNECT_TO_REMOVE, FlashFlow.RECONNECT_TO_REMOVE,
                        FlashFlow.RECONNECT_TO_REMOVE},
                {"REMOVING", "Reconnect the camera, so the app can see that the update file is gone.",
                        "Reconnect the camera, so the app can see that the update file is gone.",
                        "Reconnect the camera, so the app can see that the update file is gone."},
                {"REMOVED", "Go on with step 6, and reconnect the camera when it says so.",
                        "Go on with step 5, and reconnect the camera when it says so.",
                        "The update file is removed. Switch the camera on to see whether it starts."},
                {"CHECKED", FlashFlow.CONNECT_LATER, FlashFlow.CONNECT_LATER, null},
        };
        for (String[] row : want) {
            Stage stage = Stage.valueOf(row[0]);
            for (int r = 0; r < 3; r++) {
                Route route = Route.values()[r];
                if (row[r + 1] == null) {
                    continue;       // Restore has no check after the removal
                }
                FlashFlow f = cycleTo(route, stage);
                Live l = ready();
                Live away = unplugged(l);
                String before = describe(f, away);
                boolean claim = f.syncedClaim();
                f.driveLeft();      // BAD_REMOVAL: the app reads the synced claim before it takes in the pull
                expect(route + " " + stage + ": a pull moves no step", describe(f, away).equals(before));
                expect(route + " " + stage + ": a pull moves no stage", f.stage() == stage);
                expect(route + " " + stage + ": the next-step sentence", f.afterUnseenEject(away).equals(row[r + 1]));
                expect(route + " " + stage + ": synced is claimed only for a write that worked, before the pull",
                        claim == (stage == Stage.WRITTEN || stage == Stage.ANSWERED) && !f.syncedClaim());
                invariants(route + " pulled at " + stage, f, away);
            }
        }
        // after "not seen", the sentence sends the owner to the open flash step's More info (round 3 #9)
        FlashFlow f = cycleTo(Route.REPAIR, Stage.WRITTEN);
        f.answered(false);
        f.driveLeft();
        expect("not seen, pulled: follow More info in the open step 4, the safe order before any reconnect",
                f.afterUnseenEject(unplugged(ready()))
                        .equals("Open More info in step 4 for the safe order to reconnect the camera."));
    }

    /** Round 5 #0: the drive step is done once the file is written, and its line while unplugged follows the stage. */
    static void theDriveStepStaysDoneAndItsLineFollowsTheStage() {
        FlashFlow f = new FlashFlow(Route.PROBE);
        Live l = ready();
        Live away = unplugged(l);
        expect("before any listing the card may hold an update file: safe order", f.mayHoldUpdateFile()
                && f.unpluggedHint(away).equals(FlashFlow.SAFE_ORDER));
        expect("before the write the drive step follows the drive", !f.view(away).done(Step.DRIVE)
                && f.view(l).done(Step.DRIVE));
        f.listing();
        expect("a clean card before the write: plain order", f.unpluggedHint(away).equals(FlashFlow.PLAIN_ORDER));
        f.listing(OTHER_NAME);
        expect("a card with an update file before the write: safe order", f.unpluggedHint(away)
                .equals(FlashFlow.SAFE_ORDER));
        f.listing();
        f.written(PROBE, PROBE_NAME, true, T, "", "probe");
        expect("written: the drive step stays done unplugged", f.view(away).done(Step.DRIVE));
        expect("written, unplugged: flash as step 3 says, never 'switch on and connect'", f.unpluggedHint(away)
                .equals("Unplugged: flash the camera as step 3 says, then answer the question."));
        expect("written, ejected with the cable still in: unplug first, then flash as step 3 says",
                f.unpluggedHint(away, true).equals("Ejected: unplug the cable, then flash the camera as step 3 says"
                        + " and answer the question."));
        f.answered(true);
        expect("answered, unplugged: safe order", f.unpluggedHint(away).equals(FlashFlow.SAFE_ORDER));
        expect("answered, ejected with the cable in: unplug it", f.unpluggedHint(away, true)
                .equals(FlashFlow.EJECTED_IN) && FlashFlow.unpluggedHint(true, true).equals(FlashFlow.EJECTED_IN));
        f.answered(false);
        expect("not seen, unplugged: safe order (the file is still on the card)", f.unpluggedHint(away)
                .equals(FlashFlow.SAFE_ORDER));
        f.removalWorked(Route.PROBE);
        expect("removed, not yet listed: safe order", f.unpluggedHint(away).equals(FlashFlow.SAFE_ORDER));
        f.listing();
        expect("removed and listed: plain order", f.unpluggedHint(away).equals(FlashFlow.PLAIN_ORDER)
                && f.view(away).done(Step.DRIVE));
        expect("no run (another screen): safe order unless known clean", FlashFlow.unpluggedHint(true)
                .equals(FlashFlow.SAFE_ORDER) && FlashFlow.unpluggedHint(false).equals(FlashFlow.PLAIN_ORDER));
        // Restore: the drive line while unplugged, written but with another file loaded (the flash step is locked)
        FlashFlow r = cycleTo(Route.RESTORE, Stage.WRITTEN);
        Live other = unplugged(ready());
        other.loadedOther = true;
        expect("written, another file loaded, unplugged: the flash step is locked, so the safe order",
                r.view(other).locked(Step.FLASH) && r.unpluggedHint(other).equals(FlashFlow.SAFE_ORDER));
    }

    /** Round 1 #10/#15, round 2 #1/#6, round 5 #1: the same file found on the card starts the flash part over. */
    static void alreadyOnTheCard() {
        // after a restart with the file on the card: the run was lost, and Write finds it there
        FlashFlow f = FlashFlow.parse(null, Route.REPAIR);
        Live l = ready();
        f.listing(BRINGUP_NAME);
        see("restart, nothing on record, file on the card: Remove open before the write", f, l,
                "done done NOW lock3 open lock3");
        f.foundOnCard(BRINGUP, BRINGUP_NAME, T, "", "v0.23");
        f.listing(BRINGUP_NAME);
        see("found on the card: the flash step is current and Remove is open at once", f, l,
                "done done done NOW open lock4");
        expect("found: nothing was written, so no synced claim (round 5 #1)", !f.syncedClaim() && !f.wroteIt());
        expect("found: the folded write line says so", f.writeLine().equals("Found on the card already: " + BRINGUP_NAME
                + "."));
        f.removalWorked(Route.REPAIR);
        f.listing();
        see("found, removed without an answer: the check opens", f, l, "done done done done done NOW");
        expect("found and removed unanswered: the folded flash line says so", f.flashSummary(l)
                .equals("Flash question not answered. The update file is removed."));
        // found after an earlier answer in the same run: that answer does not carry over (round 1 #10)
        FlashFlow g = cycleTo(Route.REPAIR, Stage.WRITTEN);
        g.answered(false);
        g.foundOnCard(BRINGUP, BRINGUP_NAME, T + 60000, "", "v0.23");
        expect("found again: the old answer is gone and the question is asked again", g.seen() == null
                && g.answerLine(l).isEmpty() && g.view(l).open(Step.FLASH) && g.stage() == Stage.WRITTEN);
        expect("found again: the write time is the find's", g.writtenAt() == T + 60000);
        g.ejectSeen();
        expect("found again, then ejected: the flash step is not done by the old answer", !g.view(l).done(Step.FLASH));
        invariants("found after an answer", g, l);
    }

    /** Round 3 #3, round 4 #4: Restore with file A written and file B loaded. */
    static void secondDifferentFile() {
        FlashFlow f = cycleTo(Route.RESTORE, Stage.WRITTEN);
        Live l = ready();
        expect("A written, no other file: Remove locked", !f.removeAvailable(l));
        l.loadedOther = true;
        see("B loaded: B's write is current, the flash for A waits, Remove opens for A", f, l,
                "done done done NOW lock4 open");
        expect("B loaded: the write refusal's 'Tap Remove' points at an open step", f.view(l).open(Step.REMOVE));
        f.removalWorked(Route.RESTORE);
        f.listing();
        see("A removed and listed, B loaded: B's write is current", f, l, "done done done NOW done done");
        f.written(RESTORE_B, RESTORE_NAME, true, T + 60000, BACKUP_1, "stock restore");
        l.loadedOther = false;
        see("B written: a new run", f, l, "done done done done NOW lock5");
        expect("B written: nothing of A's run is left", f.sha().equals(RESTORE_B) && f.seen() == null
                && !f.removalShown());
        // an update file other than the written one on the card opens Remove too
        FlashFlow g = cycleTo(Route.PROBE, Stage.WRITTEN);
        g.listing(PROBE_NAME, OTHER_NAME);
        expect("another update file listed besides the written one: Remove open", g.view(ready()).open(Step.REMOVE));
        FlashFlow h = cycleTo(Route.PROBE, Stage.WRITTEN);
        h.differentFileOnCard();
        expect("a different file under the written name: Remove open", h.view(ready()).open(Step.REMOVE));
    }

    /** Round 5 #5: the backup on record changes (or is refused) after the write, before the flash. */
    static void restoreBackupChangedAfterTheWrite() {
        FlashFlow f = cycleTo(Route.RESTORE, Stage.WRITTEN);
        Live l = ready();
        f.backupChanged(BACKUP_1);
        expect("the same backup chosen again changes nothing", f.stage() == Stage.WRITTEN);
        f.backupChanged(BACKUP_2);
        expect("another backup: the write no longer counts", f.stage() == Stage.NONE);
        see("another backup, the file passes against it: write again (it is found there), Remove open", f, l,
                "done done done NOW lock4 open");
        Live failed = ready();
        failed.fileReady = false;
        // the drive step is done while the drive is listed (done wins over its needs, as on every route)
        see("another backup, the file fails against it: the flash names the open file step, Remove open", f, failed,
                "done NOW done lock2 lock2 open");
        Live none = new Live();
        see("the backup refused: everything names step 1, Remove stays open", f, none, "NOW lock1 lock1 lock1 lock1 open");
        expect("after the change the flash step is locked, not open with 'switch on with the card in'",
                f.view(l).locked(Step.FLASH));
        expect("after the change, unplugged: the safe order (the file is still on the card)", f.unpluggedHint(
                unplugged(l)).equals(FlashFlow.SAFE_ORDER));
        FlashFlow g = cycleTo(Route.RESTORE, Stage.WRITTEN);
        g.backupChanged(null);
        expect("no backup on record any more: the write no longer counts", g.stage() == Stage.NONE
                && g.view(none).open(Step.REMOVE));
        FlashFlow h = cycleTo(Route.RESTORE, Stage.ANSWERED);
        h.backupChanged(BACKUP_2);
        expect("after a seen flash a backup change leaves the run as it is", h.stage() == Stage.ANSWERED);
        // after "not seen" the flash step stays open, so a file checked against the old backup must not stay current
        FlashFlow ns = cycleTo(Route.RESTORE, Stage.WRITTEN);
        ns.answered(false);
        expect("not seen: the flash step is open before the change", ns.view(l).open(Step.FLASH));
        ns.backupChanged(BACKUP_1);
        expect("not seen, the same backup again: nothing changes", ns.stage() == Stage.ANSWERED);
        ns.backupChanged(BACKUP_2);
        expect("not seen, then another backup: the write no longer counts", ns.stage() == Stage.NONE
                && ns.seen() == null && ns.view(l).locked(Step.FLASH) && ns.view(l).open(Step.REMOVE)
                && ns.mayHoldUpdateFile() && ns.unpluggedHint(unplugged(l)).equals(FlashFlow.SAFE_ORDER));
        invariants("not seen, then another backup", ns, l);
        FlashFlow p = cycleTo(Route.PROBE, Stage.WRITTEN);
        p.backupChanged(BACKUP_2);
        expect("the probe needs no backup: a backup change leaves its run alone", p.stage() == Stage.WRITTEN);
    }

    /** Round 3 #6, round 5 #14/#15: a removal counts once a listing shows no update file, and only then. */
    static void removalConfirmedOnlyByAListing() {
        FlashFlow f = cycleTo(Route.REPAIR, Stage.ANSWERED);
        Live l = ready();
        f.removalWorked(Route.REPAIR);
        expect("removed: not done before a listing", f.stage() == Stage.REMOVING && !f.view(l).done(Step.REMOVE));
        f.driveLeft();
        f.mountChanged();
        expect("an unplug and reconnect is no listing", f.stage() == Stage.REMOVING);
        f.listing(BRINGUP_NAME);
        see("the card still lists the file: Remove open, the check waits for it", f, l, "done done done done NOW lock5");
        f.listing();
        see("a listing without it: done", f, l, "done done done done done NOW");
        // round 5 #15: ejected on Android's page, reconnected before coming back: the listing is what counts
        FlashFlow g = cycleTo(Route.PROBE, Stage.ANSWERED);
        g.removalWorked(Route.PROBE);
        g.listing();
        expect("removed and listed after a reconnect the app never saw: done, Read open", g.view(l).done(Step.REMOVE)
                && g.view(l).currentStep() == Step.READ);
    }

    /** Round 5 #14: a second unplug after the removal (to take another photo) moves nothing back. */
    static void unplugTwiceAfterTheRemoval() {
        for (Route route : new Route[]{Route.REPAIR, Route.PROBE}) {
            FlashFlow f = cycleTo(route, Stage.REMOVED);
            Live l = ready();
            Live away = unplugged(l);
            String want = describe(f, l);
            for (int i = 0; i < 3; i++) {
                f.driveLeft();
                expect(route + ": unplug " + (i + 1) + " after the removal moves nothing", describe(f, away).equals(want));
                f.mountChanged();
                expect(route + ": reconnect " + (i + 1) + ", card not yet read, moves nothing",
                        describe(f, l).equals(want));
                f.listing();
                expect(route + ": listed again without an update file, nothing moves", describe(f, l).equals(want));
            }
            f.listing(OTHER_NAME);
            expect(route + ": an update file on the card again opens Remove again", f.stage() == Stage.REMOVING
                    && f.view(l).open(Step.REMOVE));
        }
    }

    /** Round 4 #1, round 5 #8: the steps never flicker while the card is being read again. */
    static void reconnectWhileTheCardIsBeingRead() {
        FlashFlow f = cycleTo(Route.REPAIR, Stage.REMOVED);
        Live l = ready();
        Live reading = unplugged(l);     // the card is being read: not listed, so not ready to write
        String want = describe(f, l);
        f.mountChanged();
        expect("reconnected, the card being read: the steps hold", describe(f, reading).equals(want));
        // after a restart, the probe's Read step opens from the kept run, with no listing at all (round 5 #8)
        FlashFlow p = FlashFlow.parse(cycleTo(Route.PROBE, Stage.REMOVED).serialize(), Route.PROBE);
        expect("restarted after the removal: Read is open before any listing", p.view(reading).currentStep() == Step.READ);
        p.mountChanged();
        expect("re-listing does not lock Read", p.view(reading).open(Step.READ) && !p.view(reading).locked(Step.REMOVE));
    }

    /** Round 5 #2/#6: the answer line after each answer, and where the step stands. */
    static void answersSeenAndNotSeen() {
        FlashFlow f = cycleTo(Route.REPAIR, Stage.WRITTEN);
        Live l = ready();
        f.answered(false);
        see("not seen: the flash step stays open (its More info in sight), Remove open too", f, l,
                "done done done NOW open lock4");
        expect("not seen: the line sends to More info below, in the same open step", f.answerLine(l)
                .equals("Logged: flash not seen. Open More info below for what to do."));
        expect("not seen: the drive line gives the safe order", f.unpluggedHint(unplugged(l)).equals(FlashFlow.SAFE_ORDER));
        f.answered(true);
        see("then Yes (the second try worked): done, Remove current", f, l, "done done done done NOW lock5");
        f.answered(false);
        f.removalWorked(Route.REPAIR);
        f.listing();
        see("not seen, then removed: write it again is current (as More info says), the check opens too", f, l,
                "done done NOW done done open");
        FlashFlow p = cycleTo(Route.PROBE, Stage.WRITTEN);
        p.answered(false);
        p.removalWorked(Route.PROBE);
        p.listing();
        p.checked(true, "A sensor answered");
        see("not seen, removed, then a result read: the write is behind after all", p, l, "done done done done NOW");
        expect("not seen and removed: the folded line has no instruction", f.flashSummary(l)
                .equals("Logged: flash not seen."));
        FlashFlow n = new FlashFlow(Route.PROBE);
        n.answered(true);
        expect("an answer with nothing written is ignored", n.stage() == Stage.NONE && n.seen() == null);
        FlashFlow r = cycleTo(Route.RESTORE, Stage.REMOVED);
        r.answered(false);
        expect("answering after the removal changes the answer, not the stage", r.stage() == Stage.REMOVED
                && Boolean.FALSE.equals(r.seen()) && r.afterUnseenEject(l).equals("The update file is removed. "
                + FlashFlow.CONNECT_LATER));
    }

    /** Round 2 #13, round 3 #1/#8, round 5 #1/#11: "synced" only for a write the app made on the mount seen now. */
    static void syncedOnlyAfterAWriteOnThisMount() {
        FlashFlow f = new FlashFlow(Route.REPAIR);
        expect("no write: no claim", !f.syncedClaim());
        f.written(BRINGUP, BRINGUP_NAME, true, T, "", "v0.23");
        expect("a write that worked: claim", f.syncedClaim());
        f.answered(true);
        expect("answered on the same mount: the claim still holds", f.syncedClaim());
        f.removalWorked(Route.REPAIR);
        expect("after a removal: no claim (the app sees no sync of a deletion)", !f.syncedClaim());
        f.written(BRINGUP, BRINGUP_NAME, true, T, "", "v0.23");
        f.mountChanged();
        expect("mounted anew since the write: no claim", !f.syncedClaim());
        f.written(BRINGUP, BRINGUP_NAME, true, T, "", "v0.23");
        f.driveLeft();
        expect("the drive went: no claim", !f.syncedClaim());
        f.written(BRINGUP, BRINGUP_NAME, true, T, "", "v0.23");
        expect("a restart forgets the claim", !FlashFlow.parse(f.serialize(), Route.REPAIR).syncedClaim());
        f.foundOnCard(BRINGUP, BRINGUP_NAME, T, "", "v0.23");
        expect("found on the card: nothing written, no claim", !f.syncedClaim());
    }

    /** Round 1 #16, round 3 #5: a removal on a route before any write there. */
    static void removeBeforeAnyWrite() {
        FlashFlow r = new FlashFlow(Route.RESTORE);
        Live l = ready();
        r.listing(BRINGUP_NAME);
        see("Restore, an old file on the card: Remove open with the write", r, l, "done done done NOW lock4 open");
        r.removalWorked(Route.RESTORE);
        r.listing();
        see("removed before any write: Remove stays open with its result", r, l, "done done done NOW lock4 open");
        expect("its result and Eject stay in sight", r.removalShown());
        FlashFlow p = new FlashFlow(Route.PROBE);
        p.listing(BRINGUP_NAME);
        p.removalWorked(Route.PROBE);
        p.listing();
        see("Probe, the bring-up removed there: Read names the write step, not Remove (round 3 #5)", p, l,
                "done NOW lock2 open lock2");
        r.written(RESTORE_A, RESTORE_NAME, true, T, BACKUP_1, "stock restore");
        expect("a write afterwards: the old removal is not this run's", !r.removalShown());
    }

    /** A removal made on another route (or Advanced) after this route's write, and a write on another route. */
    static void removalsOnOtherRoutes() {
        FlashFlow f = cycleTo(Route.REPAIR, Stage.WRITTEN);
        f.removalWorked(Route.PROBE);
        expect("removed on another route: this run's removal is pending a listing", f.stage() == Stage.REMOVING);
        f.listing();
        expect("then listed: the flash step is behind, unanswered", f.stage() == Stage.REMOVED
                && f.flashSummary(ready()).equals("Flash question not answered. The update file is removed."));
        FlashFlow g = cycleTo(Route.PROBE, Stage.ANSWERED);
        g.removalWorked(null);
        g.listing();
        expect("removed on Advanced: the same", g.stage() == Stage.REMOVED);
        FlashFlow n = new FlashFlow(Route.RESTORE);
        n.removalWorked(Route.REPAIR);
        expect("a removal on another route is not shown here", !n.removalShown());
        FlashFlow h = cycleTo(Route.PROBE, Stage.ANSWERED);
        h.listing();
        expect("answered, then the card lists no update file (removed elsewhere): done", h.stage() == Stage.REMOVED);
        FlashFlow w = cycleTo(Route.REPAIR, Stage.WRITTEN);
        expect("written and listed: no gone line", w.goneLine().isEmpty());
        w.listing();
        expect("before the answer a card without the file: the write is current again, the flash step locked",
                w.stage() == Stage.WRITTEN && w.view(ready()).currentStep() == Step.WRITE
                        && w.view(ready()).locked(Step.FLASH));
        expect("before the answer a card without the file: the write step says the file is gone",
                w.goneLine().equals("The card no longer lists " + BRINGUP_NAME + ". Write it again in step 3."));
        expect("the file gone, unplugged: the next step is the write, never 'flash now'",
                w.afterUnseenEject(unplugged(ready())).equals(w.goneLine()));
        expect("the file gone, unplugged: still the safe order (the app only knows the last listing)",
                w.unpluggedHint(unplugged(ready())).equals(FlashFlow.SAFE_ORDER));
        invariants("written, then listed without it", w, ready());
        w.listing(BRINGUP_NAME);
        expect("listed with the file again: the flash step is current again", w.goneLine().isEmpty()
                && w.view(ready()).currentStep() == Step.FLASH);
        w.listing();
        w.written(BRINGUP, BRINGUP_NAME, true, T + 60000, "", "v0.23");
        expect("written again: the flash step is current", w.goneLine().isEmpty()
                && w.view(ready()).currentStep() == Step.FLASH);
        FlashFlow done = cycleTo(Route.PROBE, Stage.CHECKED);
        done.reset();
        expect("a write on another route resets the run, and the card now holds that route's file",
                done.stage() == Stage.NONE && done.mayHoldUpdateFile() && done.sha().isEmpty()
                        && done.unpluggedHint(unplugged(ready())).equals(FlashFlow.SAFE_ORDER));
        done.listing();
        expect("then a listing without any update file: the plain order", !done.mayHoldUpdateFile()
                && done.unpluggedHint(unplugged(ready())).equals(FlashFlow.PLAIN_ORDER));
    }

    /** A finished run of another file than the route writes now is stale (MainActivity resets it on route entry). */
    static void staleAndFinishedRuns() {
        String newer = "3e1d7a6b5c4f3e2d1c0b9a8f7e6d5c4b3a291807f6e5d4c3b2a1908f7e6d5c4b";
        for (Route route : Route.values()) {
            String own = route == Route.REPAIR ? BRINGUP : route == Route.PROBE ? PROBE : RESTORE_A;
            for (Stage stage : Stage.values()) {
                if (route == Route.RESTORE && stage == Stage.CHECKED) {
                    continue;
                }
                FlashFlow f = cycleTo(route, stage);
                boolean finished = stage == Stage.REMOVED || stage == Stage.CHECKED;
                expect(route + " at " + stage + ": finished only once removed and listed",
                        FlashFlow.finished(f) == finished);
                expect(route + " at " + stage + ": stale for a newer file only when finished",
                        FlashFlow.staleRun(f, newer) == finished);
                expect(route + " at " + stage + ": never stale for the file it wrote", !FlashFlow.staleRun(f, own));
                expect(route + " at " + stage + ": never stale with no wanted file (Restore)",
                        !FlashFlow.staleRun(f, null));
                expect(route + " at " + stage + ": a restart keeps both answers", FlashFlow.finished(FlashFlow.parse(
                        f.serialize(), route)) == finished && FlashFlow.staleRun(FlashFlow.parse(f.serialize(), route),
                        newer) == finished);
            }
        }
        FlashFlow removedFirst = new FlashFlow(Route.REPAIR);
        removedFirst.listing(OTHER_NAME);
        removedFirst.removalWorked(Route.REPAIR);
        removedFirst.listing();
        expect("nothing written: neither finished nor stale", !FlashFlow.finished(removedFirst)
                && !FlashFlow.staleRun(removedFirst, BRINGUP) && !FlashFlow.finished(null)
                && !FlashFlow.staleRun(null, BRINGUP));
        FlashFlow again = cycleTo(Route.REPAIR, Stage.REMOVED);
        again.listing(OTHER_NAME);
        expect("an update file on the card again: not finished, so not stale", !FlashFlow.finished(again)
                && !FlashFlow.staleRun(again, newer));
    }

    /** A flash check that found the original header: the write step opens again, the check keeps its result. */
    static void aFailedFlashCheck() {
        FlashFlow f = cycleTo(Route.REPAIR, Stage.REMOVED);
        Live l = ready();
        f.checked(false, "This photo has the original firmware's header ✗");
        see("check failed: write again is current, the check stays open with its result", f, l,
                "done done NOW done done open");
        expect("check failed: its text is kept", f.checkText().startsWith("This photo has the original"));
        f.checked(true, "New photo found");
        see("checked again and passed: done", f, l, "done done done done done done");
        FlashFlow early = cycleTo(Route.REPAIR, Stage.ANSWERED);
        early.checked(true, "x");
        expect("a check before the removal is not taken", early.stage() == Stage.ANSWERED);
        f.written(BRINGUP, BRINGUP_NAME, true, T + 60000, "", "v0.23");
        expect("a new write clears the check (round 1 #8)", f.checkText().isEmpty() && !f.view(l).done(Step.CHECK));
    }

    /** Round 1 #14, round 5 #7: the probe's SENSOR.TXT identity at the write survives a restart; unknown stays so. */
    static void probeSensorFileAcrossARestart() {
        FlashFlow f = cycleTo(Route.PROBE, Stage.WRITTEN);
        expect("before the snapshot lands: unknown", f.sensorAtWrite() == null);
        f.sensorRead(T + 1, "123:58:abcdef");
        expect("a snapshot of another write is not taken", f.sensorAtWrite() == null);
        f.sensorRead(T, "1794402000000:58:abcdef");
        FlashFlow back = FlashFlow.parse(f.serialize(), Route.PROBE);
        expect("the identity survives a restart", "1794402000000:58:abcdef".equals(back.sensorAtWrite()));
        f.sensorRead(T, "");
        expect("no SENSOR.TXT at the write survives as none", "".equals(FlashFlow.parse(f.serialize(), Route.PROBE)
                .sensorAtWrite()));
        FlashFlow lost = cycleTo(Route.PROBE, Stage.WRITTEN);
        expect("a snapshot that never landed is unknown after a restart", FlashFlow.parse(lost.serialize(),
                Route.PROBE).sensorAtWrite() == null);
        f.written(PROBE, PROBE_NAME, true, T + 60000, "", "probe");
        expect("a new write forgets the old identity", f.sensorAtWrite() == null);
    }

    /** Round 1 #7: Android ends the process at each stage; the run comes back as it was. */
    static void processDeathAtEachStage() {
        for (Route route : Route.values()) {
            for (Stage stage : Stage.values()) {
                if (route == Route.RESTORE && stage == Stage.CHECKED) {
                    continue;
                }
                FlashFlow f = cycleTo(route, stage);
                f.sensorRead(f.writtenAt(), "7:2:ab|c\\d");
                FlashFlow back = FlashFlow.parse(f.serialize(), route);
                for (Live l : new Live[]{ready(), unplugged(ready()), new Live()}) {
                    expect(route + " restarted at " + stage + ": same steps", describe(back, l).equals(describe(f, l)));
                    expect(route + " restarted at " + stage + ": same sentences",
                            back.afterUnseenEject(l).equals(f.afterUnseenEject(l))
                                    && back.unpluggedHint(l).equals(f.unpluggedHint(l))
                                    && back.answerLine(l).equals(f.answerLine(l))
                                    && back.removeAvailable(l) == f.removeAvailable(l));
                }
                expect(route + " restarted at " + stage + ": the same String again", back.serialize()
                        .equals(f.serialize()));
                invariants(route + " restarted at " + stage, back, ready());
            }
        }
        // the Repair route restarted at each stage: the check opens only after the removal (round 2 #9, round 3 #4)
        FlashFlow w = FlashFlow.parse(cycleTo(Route.REPAIR, Stage.WRITTEN).serialize(), Route.REPAIR);
        expect("restarted before the answer: the check names the open flash step, not Remove",
                "Do step 4 first.".equals(w.view(ready()).lockReason(Step.CHECK)) && !w.view(ready()).open(Step.REMOVE));
        FlashFlow d = FlashFlow.parse(cycleTo(Route.REPAIR, Stage.REMOVED).serialize(), Route.REPAIR);
        expect("restarted after the removal, unplugged: the check is open", d.view(unplugged(ready()))
                .currentStep() == Step.CHECK);
    }

    /** parse() never throws, and reads anything that is not this route's run as a run with nothing done. */
    static void parseIsSafe() {
        FlashFlow good = cycleTo(Route.REPAIR, Stage.ANSWERED);
        String text = good.serialize();
        String[] bad = {null, "", "junk", "charmera-flash-flow 2\n" + text.substring(text.indexOf('\n') + 1),
                text.replace("REPAIR|", "PROBE|"), text.replace("|ANSWERED|", "|LATER|"),
                text.replace("|ANSWERED|", "|ANSWERED||"), text.replace("v0.23", "v0.23\\q"),
                text.replace("|v0.23|", "|v0.23\\|"), text.replace("|y|", "|maybe|"),
                text.replace("|ANSWERED|", "|ANSWERED|").replace("|y|", "|-|"), FlashFlow.HEADER + "\n"};
        for (int i = 0; i < bad.length; i++) {
            FlashFlow f = FlashFlow.parse(bad[i], Route.REPAIR);
            expect("unreadable text " + i + " is a run with nothing done", f.stage() == Stage.NONE && f.sha().isEmpty());
        }
        expect("another route's run is not this one's", FlashFlow.parse(text, Route.PROBE).stage() == Stage.NONE);
        FlashFlow odd = new FlashFlow(Route.RESTORE);
        String nasty = "a|b\\c\nd\re \\p \\n|| — é";
        odd.written(RESTORE_A, nasty, true, T, BACKUP_1, nasty);
        odd.answered(true);
        odd.removalWorked(Route.RESTORE);
        odd.listing();
        FlashFlow back = FlashFlow.parse(odd.serialize(), Route.RESTORE);
        expect("awkward text round-trips", back.cardName().equals(nasty) && back.firmware().equals(nasty)
                && back.serialize().equals(odd.serialize()) && back.stage() == Stage.REMOVED);
        expect("one header line and one line of fields", odd.serialize().split("\n", -1).length == 3);
        boolean threw;
        try {
            new FlashFlow(null);
            threw = false;
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        expect("a run needs a route", threw);
    }

    /**
     * What the card in the walk really holds, kept apart from the model: true while an update file is on it. Writes
     * (this route's, or another route's: reset), a file found there and listings that show one set it; a removal that
     * worked (it deletes every update file) and a listing that shows none clear it; a pull, a mount, an eject, an
     * answer, a check and a restart leave it as it is, so a pull before the listing returns keeps it.
     */
    private static boolean cardHolds;

    /** The flash-stage sentence: the flash is step 4 on Repair, 3 on Probe, 5 on Restore (counted by hand). */
    private static String flashNow(Route r) {
        int n = r == Route.REPAIR ? 4 : r == Route.PROBE ? 3 : 5;
        return FlashFlow.FLASH_NOW_PREFIX + n + ". More info there gives the safe order to reconnect it.";
    }

    /** A seeded walk of random events and screens on each route: every state reached keeps the invariants. */
    static void randomWalk() {
        Random rnd = new Random(20261111L);
        int states = 0;
        int before = failures;
        for (Route route : Route.values()) {
            for (int walk = 0; walk < 400 && failures == before; walk++) {
                FlashFlow f = new FlashFlow(route);
                cardHolds = rnd.nextBoolean();      // a fresh run knows nothing of the card: either may be true
                StringBuilder trail = new StringBuilder(route.name());
                for (int n = 0; n < 24 && failures == before; n++) {
                    String e = randomEvent(rnd, f);
                    if (e.equals("restart")) {
                        f = FlashFlow.parse(f.serialize(), route);
                    }
                    trail.append(' ').append(e);
                    Live l = new Live();
                    l.started = rnd.nextBoolean();
                    l.backupOnRecord = rnd.nextBoolean();
                    l.fileReady = rnd.nextBoolean();
                    l.driveReady = rnd.nextBoolean();
                    l.loadedOther = rnd.nextInt(4) == 0;
                    invariants(trail.toString(), f, l);
                    if (cardHolds) {
                        // checked against the card itself, not against the model's own mayHoldUpdateFile()
                        expect(trail + ": the card holds an update file, so the drive line is never the plain order",
                                !f.unpluggedHint(l).equals(FlashFlow.PLAIN_ORDER)
                                        && !f.unpluggedHint(unplugged(l)).equals(FlashFlow.PLAIN_ORDER));
                        expect(trail + ": the card holds an update file, so the model says it may",
                                f.mayHoldUpdateFile());
                        // Every sentence the model can show that tells the owner to connect must give the safe
                        // order itself or send them to a step (whose text gives it); a bare "connect" would let
                        // them plug in a camera that flashes on power-on.
                        for (Live v : new Live[]{l, unplugged(l)}) {
                            String[] texts = {f.afterUnseenEject(v), f.answerLine(v), f.flashSummary(v),
                                    f.goneLine(), f.writeLine(), f.removeLine(), f.unpluggedHint(v),
                                    f.unpluggedHint(v, true)};
                            for (String t : texts) {
                                expect(trail + ": a connect sentence with an update file on the card is safe: " + t,
                                        safeConnectText(t));
                            }
                        }
                    }
                    states++;
                }
            }
        }
        expect("the walk reached many states", states > 20000);
    }

    /**
     * A text is safe while an update file may be on the card when it does not tell the owner to connect, or it gives
     * the safe order (card out before the camera is switched on), or it points to a step that does.
     */
    static boolean safeConnectText(String t) {
        if (t == null) {
            return true;
        }
        String s = t.toLowerCase(java.util.Locale.ROOT);
        if (!s.contains("connect")) {
            return true;
        }
        return s.contains(FlashFlow.SAFE_ORDER.toLowerCase(java.util.Locale.ROOT)) || s.contains("card out")
                || s.matches("(?s).*\\bstep \\d.*");
    }

    private static String randomEvent(Random rnd, FlashFlow f) {
        String name = f.route == Route.REPAIR ? BRINGUP_NAME : f.route == Route.PROBE ? PROBE_NAME : RESTORE_NAME;
        String sha = f.route == Route.REPAIR ? BRINGUP : f.route == Route.PROBE ? PROBE : RESTORE_A;
        switch (rnd.nextInt(24)) {
            case 0:
                f.written(sha, name, true, T + rnd.nextInt(1000), BACKUP_1, "fw");
                cardHolds = true;
                return "written";
            case 1:
                f.foundOnCard(sha, name, T + rnd.nextInt(1000), BACKUP_1, "fw");
                cardHolds = true;
                return "found";
            case 2:
                f.answered(true);
                return "yes";
            case 3:
                f.answered(false);
                return "no";
            case 4:
                f.removalWorked(f.route);
                cardHolds = false;
                return "removed";
            case 5:
                f.removalWorked(Route.values()[rnd.nextInt(3)]);
                cardHolds = false;
                return "removedOn";
            case 6:
                f.removalWorked(null);
                cardHolds = false;
                return "removedAdvanced";
            case 7:
                f.listing();
                cardHolds = false;
                return "listedClean";
            case 8:
                f.listing(name);
                cardHolds = true;
                return "listedFile";
            case 9:
                f.listing(OTHER_NAME);
                cardHolds = true;
                return "listedOther";
            case 10:
                f.listing(name, OTHER_NAME);
                cardHolds = true;
                return "listedBoth";
            case 20:
                // another route wrote its file, and the drive was pulled before the listing came back
                f.reset();
                f.driveLeft();
                cardHolds = true;
                return "otherWriteThenPull";
            case 21:
                // a removal worked here, then the drive was pulled before the listing came back
                f.removalWorked(f.route);
                f.driveLeft();
                cardHolds = false;
                return "removedThenPull";
            case 11:
                f.ejectSeen();
                return "ejected";
            case 12:
                f.driveLeft();
                return "left";
            case 13:
                f.mountChanged();
                return "mounted";
            case 14:
                f.checked(rnd.nextBoolean(), "result");
                return "checked";
            case 15:
                f.backupChanged(BACKUP_1);
                return "sameBackup";
            case 16:
                f.backupChanged(rnd.nextBoolean() ? BACKUP_2 : null);
                return "otherBackup";
            case 17:
                f.reset();          // a write of another route's file: the card holds that one now
                cardHolds = true;
                return "reset";
            case 18:
                f.sensorRead(f.writtenAt(), rnd.nextBoolean() ? "" : "1:2:3");
                return "sensor";
            case 19:
                f.differentFileOnCard();
                return "differs";
            default:
                return "restart";
        }
    }

    // --- the rules every state keeps -------------------------------------------------------------------------------

    static void invariants(String where, FlashFlow f, Live l) {
        FlashFlow.View v = f.view(l);
        Step[] order = FlashFlow.steps(f.route);
        int firstOpen = -1;
        for (int i = 0; i < order.length; i++) {
            Step s = order[i];
            int states = (v.done(s) ? 1 : 0) + (v.open(s) ? 1 : 0) + (v.locked(s) ? 1 : 0);
            expect(where + ": step " + (i + 1) + " is exactly one of done, open and locked", states == 1);
            if (v.open(s) && firstOpen < 0) {
                firstOpen = i;
            }
            String reason = v.lockReason(s);
            if (v.locked(s)) {
                Matcher m = LOCK.matcher(reason == null ? "" : reason);
                int n = m.matches() ? Integer.parseInt(m.group(1)) : -1;
                boolean named = n >= 1 && n - 1 < i && v.open(order[n - 1]);
                for (int j = 0; named && j < n - 1; j++) {
                    named = !v.open(order[j]);      // the first open step before it
                }
                expect(where + ": step " + (i + 1) + "'s lock names the first open step before it (" + reason + ")",
                        named);
            } else {
                expect(where + ": only a locked step has a lock note", reason == null);
            }
        }
        expect(where + ": the current step is the first open one", v.current() == firstOpen);
        String unseen = f.afterUnseenEject(l);
        String hint = f.unpluggedHint(l);
        String ejectedHint = f.unpluggedHint(l, true);
        String answer = f.answerLine(l);
        String[] texts = {unseen, hint, ejectedHint, answer, f.flashSummary(l)};
        for (int t = 0; t < texts.length; t++) {
            String text = texts[t];
            Matcher m = STEP_N.matcher(text);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                expect(where + ": \"" + text + "\" names an open step", n >= 1 && n <= order.length
                        && v.open(order[n - 1]));
            }
            String lower = text.toLowerCase(Locale.ROOT);
            if (t != 1) {
                // Outside the drive line, the only way to say "switch on and connect" is the safe order, word for
                // word: what is left once it is taken out must not say it again in any other form.
                String rest = text.replace(FlashFlow.SAFE_ORDER, "").toLowerCase(Locale.ROOT);
                expect(where + ": \"" + text + "\" says to switch the camera on and connect it only in the safe order",
                        !(rest.contains("switch") && rest.contains("connect")));
            }
        }
        expect(where + ": the drive line is one of the known ones", hint.equals(FlashFlow.SAFE_ORDER)
                || hint.equals(FlashFlow.PLAIN_ORDER) || hint.startsWith("Unplugged: flash the camera as step "));
        expect(where + ": ejected with the cable in, the line says to unplug it first",
                ejectedHint.equals(FlashFlow.EJECTED_IN) || ejectedHint.startsWith("Ejected: unplug the cable, then"
                        + " flash the camera as step "));
        expect(where + ": the two lines send to the flash step in the same states",
                hint.startsWith("Unplugged") == ejectedHint.startsWith("Ejected: unplug the cable, then"));
        if (f.mayHoldUpdateFile()) {
            expect(where + ": with an update file maybe on the card, never the plain order",
                    !hint.equals(FlashFlow.PLAIN_ORDER));
        }
        if (hint.startsWith("Unplugged")) {
            expect(where + ": 'flash as step N says' only before the answer", f.stage() == Stage.WRITTEN);
        }
        if (answer.contains("Next: remove")) {
            expect(where + ": 'Next: remove' only while Remove is open", v.open(Step.REMOVE));
        }
        if (answer.contains("More info below")) {
            expect(where + ": 'More info below' only while the flash step is open", v.open(Step.FLASH));
        }
        if (unseen.startsWith(FlashFlow.FLASH_NOW_PREFIX)) {
            expect(where + ": 'flash now' only while the flash step is open", v.open(Step.FLASH));
        }
        if (unseen.equals(FlashFlow.RECONNECT_TO_REMOVE) || unseen.contains("update file is gone")) {
            expect(where + ": 'reconnect to remove' only while Remove is open", v.open(Step.REMOVE));
        }
        if (f.syncedClaim()) {
            expect(where + ": synced only for a write the app made, before the removal", f.wroteIt()
                    && (f.stage() == Stage.WRITTEN || f.stage() == Stage.ANSWERED));
        }
        if (v.done(Step.REMOVE)) {
            expect(where + ": Remove is done only once a listing found no update file", !f.mayHoldUpdateFile());
        }
        if (f.past()) {
            expect(where + ": past the write the drive step is done, whatever the drive", v.done(Step.DRIVE));
        }
        FlashFlow back = FlashFlow.parse(f.serialize(), f.route);
        expect(where + ": a restart keeps the steps and sentences", describe(back, l).equals(describe(f, l))
                && back.afterUnseenEject(l).equals(unseen) && back.unpluggedHint(l).equals(hint)
                && back.answerLine(l).equals(answer) && back.serialize().equals(f.serialize()) && !back.syncedClaim());
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** A run of route taken through the normal cycle to stage (Yes answered, removed on the route, a passed check). */
    static FlashFlow cycleTo(Route route, Stage stage) {
        FlashFlow f = new FlashFlow(route);
        if (stage == Stage.NONE) {
            return f;
        }
        String name = route == Route.REPAIR ? BRINGUP_NAME : route == Route.PROBE ? PROBE_NAME : RESTORE_NAME;
        String sha = route == Route.REPAIR ? BRINGUP : route == Route.PROBE ? PROBE : RESTORE_A;
        String fw = route == Route.REPAIR ? "v0.23" : route == Route.PROBE ? "probe" : "stock restore";
        f.written(sha, name, true, T, route == Route.RESTORE ? BACKUP_1 : "", fw);
        f.listing(name);
        if (stage == Stage.WRITTEN) {
            return f;
        }
        f.answered(true);
        if (stage == Stage.ANSWERED) {
            return f;
        }
        f.removalWorked(route);
        if (stage == Stage.REMOVING) {
            return f;
        }
        f.listing();
        if (stage == Stage.REMOVED) {
            return f;
        }
        f.checked(true, "checked");
        return f;
    }

    /** Everything ready before the write: Continue tapped, the backup and the file, the drive listed and mounted. */
    static Live ready() {
        Live l = new Live();
        l.started = true;
        l.backupOnRecord = true;
        l.fileReady = true;
        l.driveReady = true;
        return l;
    }

    /** The same screen with the drive unplugged (or the card not listed now). */
    static Live unplugged(Live l) {
        Live u = new Live();
        u.started = l.started;
        u.backupOnRecord = l.backupOnRecord;
        u.fileReady = l.fileReady;
        u.loadedOther = l.loadedOther;
        u.driveReady = false;
        return u;
    }

    static String describe(FlashFlow f, Live l) {
        FlashFlow.View v = f.view(l);
        StringBuilder sb = new StringBuilder();
        for (Step s : FlashFlow.steps(f.route)) {
            String word;
            if (v.done(s)) {
                word = "done";
            } else if (v.open(s)) {
                word = s == v.currentStep() ? "NOW" : "open";
            } else {
                Matcher m = LOCK.matcher(String.valueOf(v.lockReason(s)));
                word = "lock" + (m.matches() ? m.group(1) : "?");
            }
            sb.append(sb.length() == 0 ? "" : " ").append(word);
        }
        return sb.toString();
    }

    static void see(String what, FlashFlow f, Live l, String steps) {
        String got = describe(f, l);
        expect(what + ": expected [" + steps + "], got [" + got + "]", got.equals(steps));
        invariants(what, f, l);
    }

    static void expect(String what, boolean ok) {
        if (ok) {
            passes++;
        } else {
            failures++;
            System.out.println("FAILED: " + what);
        }
    }
}
