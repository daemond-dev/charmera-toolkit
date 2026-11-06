package io.github.daemonddev.charmera;

import java.util.Locale;

/**
 * Plain-JVM tests for what the app makes of a card's top folder (CardState). The Android side, which fills it in
 * from the document provider, needs a phone; these are the rules it applies to what it found. In the app's package,
 * because CardState is package-private.
 */
public final class CardStateTest {

    private static int failures = 0;
    private static int passes = 0;

    public static void main(String[] args) {
        // A card that is not there: the provider answers with no cursor, and the app must not call that empty.
        CardState gone = new CardState();
        gone.problem = CardState.NOT_FOUND;
        expect("a card that cannot be found is not writable", !gone.writable());
        expect("and does not read as an empty card", !gone.describe().contains("No update files"), gone.describe());
        expect("and says it cannot be found", gone.describe().contains("cannot find the card"), gone.describe());
        expect("and refuses a write", gone.writeRefusal() != null);

        CardState unreadable = CardState.unreadable("The card cannot be read (I/O error).");
        expect("an unreadable card is not listed", !unreadable.listed && !unreadable.writable());
        expect("and its message is shown", unreadable.describe().contains("cannot be read"), unreadable.describe());

        CardState empty = listed();
        expect("a listed empty card is writable", empty.writable() && empty.writeRefusal() == null, empty.describe());
        expect("and reads as empty", empty.describe().contains("No update files in the top folder."), empty.describe());
        expect("and says so afterwards", empty.afterwards().contains("holds no update file"), empty.afterwards());

        CardState stale = listed();
        stale.addChild("A:JH_5408202610261427_0F065D55.bin", "JH_5408202610261427_0F065D55.bin", "application/octet-stream", 1966080);
        stale.addChild("A:charmera-stage.part", "charmera-stage.part", "application/octet-stream", 1024);
        stale.addChild("A:jh_old.BIN", "jh_old.BIN", "application/octet-stream", -1);
        stale.addChild("A:DCIM", "DCIM", CardState.DIR_MIME, -1);
        stale.addChild("A:JH_dir.bin", "JH_dir.bin", CardState.DIR_MIME, -1);
        stale.addChild("A:notes.txt", "notes.txt", "text/plain", 10);
        expect("update files, the temporary file and any case are found", stale.updateFiles.size() == 3, stale.describe());
        expect("a folder is never an update file", !stale.names().contains("JH_dir"), stale.names());
        expect("DCIM is seen", stale.hasDcim, stale.describe());
        expect("a card holding update files refuses a write", stale.writeRefusal() != null
                && stale.writeRefusal().contains("JH_5408202610261427_0F065D55.bin (1,966,080 bytes)")
                && stale.writeRefusal().contains("Remove update files"), stale.writeRefusal());
        expect("an unknown size is shown as ?", stale.names().contains("jh_old.BIN (? bytes)"), stale.names());
        expect("afterwards names what is still there", stale.afterwards().contains("charmera-stage.part")
                && stale.afterwards().contains("Remove update files"), stale.afterwards());
        expect("after a removal that left files, it says to try again and how else", stale.afterwards(true).contains(
                "still holds") && stale.afterwards(true).contains("Files app"), stale.afterwards(true));

        UpdateCheck.Result checked = new UpdateCheck.Result(new java.util.ArrayList<>(),
                "JH_5408202610261427_0F065D55.bin", "00", 1966080);
        expect("the same file name in another case and the same size is found for comparison",
                stale.sameFile(checked) != null && stale.sameFile(checked).documentId.startsWith("A:JH_5408"));
        UpdateCheck.Result otherSize = new UpdateCheck.Result(new java.util.ArrayList<>(),
                "JH_5408202610261427_0F065D55.bin", "00", 1966079);
        expect("a file of another size is not taken for the same one", stale.sameFile(otherSize) == null);
        expect("a card that could not be listed has no same file", gone.sameFile(checked) == null);

        CardState full = new CardState();
        full.listingDone(1000);
        expect("a full card is listed but not writable", full.listed && !full.writable()
                && full.writeRefusal().contains("Not enough free space"), full.describe());

        CardState notListed = new CardState();
        expect("afterwards, a card that could not be listed says to look by hand", notListed.afterwards().contains(
                "could not be listed again") && notListed.afterwards().contains("JH_….bin"), notListed.afterwards());
        notListed.problem = CardState.NOT_FOUND;
        expect("and gives the reason", notListed.afterwards().contains("cannot find the card"), notListed.afterwards());

        System.out.printf(Locale.ROOT, "CardStateTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    static CardState listed() {
        CardState s = new CardState();
        s.label = "CHARMERA";
        s.listingDone(8_000_000_000L);
        return s;
    }

    static void expect(String what, boolean ok) {
        expect(what, ok, "");
    }

    static void expect(String what, boolean ok, String shown) {
        if (ok) {
            passes++;
        } else {
            failures++;
            System.out.println("FAILED: " + what + "\n" + shown);
        }
    }
}
