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
 */
final class CardState {

    /** Names the camera's updater could pick up (it takes the first JH_5408*.bin), and the app's temporary file. */
    static final Pattern UPDATE_FILE = Pattern.compile("(?i)^JH_.*\\.bin$|^charmera-stage.*\\.part$");
    static final String DIR_MIME = "vnd.android.document/directory";
    /** Room for the file plus one 64 KiB cluster of slack. */
    static final long NEEDED_BYTES = UpdateCheck.UPDATE_SIZE + 64 * 1024;

    static final String NOT_FOUND = "Android cannot find the card right now: it is not in the phone, the card reader"
            + " or the camera is not connected, or the card is not mounted. Connect it and come back to the app; if"
            + " this stays, choose the card again.";

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
            return name + " (" + (size < 0 ? "?" : String.valueOf(size)) + " bytes)";
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
            problem = "Not enough free space on the card (" + available + " bytes free).";
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

    /** Null when an update file may be written to this card now, otherwise why not. */
    String writeRefusal() {
        if (problem != null) {
            return problem;
        }
        if (!listed) {
            return "The card's top folder could not be listed.";
        }
        if (!updateFiles.isEmpty()) {
            return "The card's top folder holds " + names() + ". Two update files on one card is not a situation the"
                    + " camera is known to handle: tap \"Remove update files\" first.";
        }
        return null;
    }

    /** For a message after a failed write or removal: what the card's top folder holds now. */
    String afterwards() {
        if (!listed) {
            return "The card could not be listed again afterwards" + (problem == null ? "" : " (" + problem + ")")
                    + ". Before it goes into the camera, look in its top folder with the Files app or a computer and"
                    + " delete any JH_….bin or charmera-stage.part file there.";
        }
        if (updateFiles.isEmpty()) {
            return "Listed again: the card's top folder holds no update file.";
        }
        return "Listed again: the card's top folder holds " + names() + ". Tap \"Remove update files\" before the card"
                + " goes into the camera.";
    }

    String names() {
        StringBuilder sb = new StringBuilder();
        for (Item f : updateFiles) {
            sb.append(sb.length() == 0 ? "" : ", ").append(f.describe());
        }
        return sb.toString();
    }

    String describe() {
        StringBuilder sb = new StringBuilder();
        if (label != null) {
            sb.append("Card: ").append(label).append('\n');
        }
        if (available >= 0) {
            sb.append(String.format(Locale.ROOT, "Free: %.1f MB\n", available / 1e6));
        }
        if (listed) {
            sb.append(hasDcim ? "Has a DCIM folder: looks like the camera's card.\n"
                    : "No DCIM folder: make sure this is the card you mean.\n");
            if (updateFiles.isEmpty()) {
                sb.append("No update files in the top folder.\n");
            } else {
                sb.append("Update files already in the top folder:\n");
                for (Item f : updateFiles) {
                    sb.append("  ").append(f.describe()).append('\n');
                }
            }
        }
        if (problem != null) {
            sb.append('\n').append(problem);
        }
        return sb.toString().trim();
    }
}
