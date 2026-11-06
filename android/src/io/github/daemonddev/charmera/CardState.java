package io.github.daemonddev.charmera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the app found in the card's top folder, and what that allows. No Android imports, so these rules run on a
 * plain JVM in android/test/CardStateTest.java; MainActivity fills it in from Android's document provider.
 *
 * A card that could not be listed is never treated as an empty one: listed stays false and problem says why.
 *
 * Every message is for the owner reading it on a phone: what was found first, then what to do next, with buttons
 * named exactly as MainActivity labels them.
 */
final class CardState {

    /** Names the camera's updater could pick up (it takes the first JH_5408*.bin), and the app's temporary file. */
    static final Pattern UPDATE_FILE = Pattern.compile("(?i)^JH_.*\\.bin$|^charmera-stage.*\\.part$");
    static final String DIR_MIME = "vnd.android.document/directory";
    /** Room for the file plus one 64 KiB cluster of slack. */
    static final long NEEDED_BYTES = UpdateCheck.UPDATE_SIZE + 64 * 1024;

    /** The labels of the two buttons these messages point to; MainActivity labels the buttons with these. */
    static final String CHOOSE_BUTTON = "Choose the camera's drive";
    static final String REMOVE_BUTTON = "Remove update files from the card";

    /** Why the card cannot be found, without what to do next (afterwards() says that itself). */
    static final String NOT_FOUND_CAUSE = "Android cannot find the card right now. The camera or card reader may be"
            + " unplugged, the drive ejected, or the card out.";
    static final String NOT_FOUND = NOT_FOUND_CAUSE + " Connect it, then tap \"" + CHOOSE_BUTTON + "\" and pick the"
            + " drive again, so the app looks at it again.";

    /** One file in the top folder that matches UPDATE_FILE. size is -1 when the provider does not say. */
    static final class Item {
        final String documentId;
        final String name;
        final long size;

        Item(String documentId, String name, long size) {
            this.documentId = documentId;
            this.name = name;
            this.size = size;
        }

        String describe() {
            return name + " (" + (size < 0 ? "? bytes" : UpdateCheck.bytes(size)) + ")";
        }
    }

    String label;
    long available = -1;
    /** True only after the top folder was listed; hasDcim and updateFiles mean nothing before that. */
    boolean listed;
    boolean hasDcim;
    final List<Item> updateFiles = new ArrayList<>();
    /** Why nothing can be written to this card, or null. */
    String problem;

    static CardState unreadable(String why) {
        CardState s = new CardState();
        s.problem = why;
        return s;
    }

    /** One row of the top folder's listing. */
    void addChild(String documentId, String name, String mimeType, long size) {
        boolean dir = DIR_MIME.equals(mimeType);
        if (dir && "DCIM".equalsIgnoreCase(name)) {
            hasDcim = true;
        } else if (!dir && name != null && UPDATE_FILE.matcher(name).matches()) {
            updateFiles.add(new Item(documentId, name, size));
        }
    }

    /** Call once the listing is complete. */
    void listingDone(long availableBytes) {
        listed = true;
        available = availableBytes;
        if (problem == null && available >= 0 && available < NEEDED_BYTES) {
            problem = "Not enough free space on the card: " + size(available) + " free, and the update file needs "
                    + size(NEEDED_BYTES) + ". Make room on the card, for example by moving photos off it, then come"
                    + " back to the app.";
        }
    }

    boolean writable() {
        return listed && problem == null;
    }

    /** The file on the card with check's card name (any case) and the same size, or null. */
    Item sameFile(UpdateCheck.Result check) {
        if (!listed || check == null || check.cardName == null) {
            return null;
        }
        for (Item f : updateFiles) {
            if (f.name.equalsIgnoreCase(check.cardName) && f.size == check.size) {
                return f;
            }
        }
        return null;
    }

    /** Null when an update file may be written to this card now, otherwise why not, as full sentences. */
    String writeRefusal() {
        if (problem != null) {
            return problem;
        }
        if (!listed) {
            return "The app could not look in the card's top folder. Tap \"" + CHOOSE_BUTTON + "\" again.";
        }
        if (!updateFiles.isEmpty()) {
            return "The card already holds " + names() + ". Remove " + (updateFiles.size() == 1 ? "it" : "them")
                    + " first, so that the new update file is the only one on the card. Tap \"" + REMOVE_BUTTON
                    + "\" (under step 5), then write again.";
        }
        return null;
    }

    /** For a message after a failed write: what the card's top folder holds now, and what to do. */
    String afterwards() {
        return afterwards(false);
    }

    /**
     * For a message after a failed write, or (removing) after a removal that did not remove everything: what the
     * card's top folder holds now, and what to do.
     */
    String afterwards(boolean removing) {
        if (!listed) {
            return "The card could not be listed again afterwards, so the app cannot say what is on it now."
                    + (problem == null ? "" : "\n" + (NOT_FOUND.equals(problem) ? NOT_FOUND_CAUSE : problem))
                    + "\nThe card may hold an update file. Do not switch the camera on while this card is in it.\n"
                    + "To check the card:\n"
                    + "• If the camera is off: take the card out, switch the camera on with its power key, wait 10"
                    + " seconds, and put the card back in.\n"
                    + "• Connect the camera to the phone.\n"
                    + "• Tap \"" + CHOOSE_BUTTON + "\" and pick the camera's drive again. Step 2 then shows what is on"
                    + " the card.\n"
                    + "• If it lists an update file, tap \"" + REMOVE_BUTTON + "\".\n"
                    + "With a card reader or a computer: delete any file named JH_….bin or charmera-stage.part in the"
                    + " card's top folder.";
        }
        if (updateFiles.isEmpty()) {
            return "The app looked at the card again: its top folder holds no update file.";
        }
        boolean one = updateFiles.size() == 1;
        return "The app looked at the card again: its top folder " + (removing ? "still holds " : "holds ") + names()
                + ". " + (removing ? "Tap \"" + REMOVE_BUTTON + "\" again. If it fails again, delete "
                + (one ? "the file" : "the files") + " with the Files app or on a computer."
                : "Tap \"" + REMOVE_BUTTON + "\" (under step 5).")
                + " Do not switch the camera on with this card in it until " + (one ? "that file is" : "those files are")
                + " gone.";
    }

    String names() {
        StringBuilder sb = new StringBuilder();
        for (Item f : updateFiles) {
            sb.append(sb.length() == 0 ? "" : ", ").append(f.describe());
        }
        return sb.toString();
    }

    /** For the screen: a one-line verdict first, then the card's name, its free space and any update files on it. */
    String describe() {
        if (!listed) {
            return problem == null ? "The app has not looked at the card yet." : problem;
        }
        StringBuilder sb = new StringBuilder();
        if (problem != null) {
            sb.append(problem);
        } else if (updateFiles.isEmpty()) {
            sb.append("Ready: the app can write to this card.");
        } else {
            sb.append(updateFiles.size() == 1 ? "The card holds an update file."
                    : "The card holds update files.");
        }
        sb.append('\n');
        if (label != null) {
            sb.append("Card name: ").append(label).append('\n');
        }
        if (available >= 0) {
            sb.append("Free space: ").append(size(available)).append('\n');
        }
        sb.append(hasDcim ? "It has a DCIM folder, so it looks like the camera's card.\n"
                : "It has no DCIM folder. Check that this is the camera's card.\n");
        if (updateFiles.isEmpty()) {
            sb.append("No update files in the top folder.\n");
        } else {
            sb.append("Update files in its top folder:\n");
            for (Item f : updateFiles) {
                sb.append("  ").append(f.describe()).append('\n');
            }
            sb.append(updateFiles.size() == 1 ? "Remove it" : "Remove them").append(" with \"").append(REMOVE_BUTTON)
                    .append("\" (under step 5) after the flash, or before you write a different file.\n");
        }
        return sb.toString().trim();
    }

    /** A size for the screen: "7.9 GB", "2.0 MB", or bytes when it is less than a megabyte. */
    static String size(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
        }
        if (bytes >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1e6);
        }
        return bytes + " bytes";
    }
}
