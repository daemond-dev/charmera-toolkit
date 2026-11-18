package io.github.daemonddev.charmera;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The two decisions behind "Delete them from the camera after a verified backup", kept apart from Android so they can
 * be tested on the JVM: which zip entries read back exactly as written, and which camera files may then be deleted.
 * MainActivity supplies the streams and does the deleting (through its one delete()).
 */
final class ZipCheck {

    private ZipCheck() {
    }

    /**
     * Reads a zip back and returns the entries whose CRC-32 and length are what was written ({crc, length} per name).
     * An entry name that appears twice is never verified: that is not the zip the app wrote. Entries not in written
     * are ignored. The stream is closed.
     */
    static Set<String> verify(InputStream raw, Map<String, long[]> written) throws IOException {
        Set<String> verified = new HashSet<>();
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 64 * 1024))) {
            byte[] buf = new byte[64 * 1024];
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                CRC32 crc = new CRC32();
                long length = 0;
                int n;
                while ((n = zin.read(buf)) > 0) {
                    crc.update(buf, 0, n);
                    length += n;
                }
                String entry = e.getName();
                long[] want = written.get(entry);
                if (!seen.add(entry)) {
                    verified.remove(entry);
                } else if (want != null && want[0] == crc.getValue() && want[1] == length) {
                    verified.add(entry);
                }
            }
        }
        return verified;
    }

    /**
     * The camera files that may be deleted: each removable entry (a whole copy of the camera's own file) whose copy
     * verified and, when the zip also holds its original (originalOf: entry to original's entry), whose original
     * verified too. Nothing else.
     */
    static Set<String> deletable(Set<String> removable, Set<String> verified, Map<String, String> originalOf) {
        Set<String> out = new LinkedHashSet<>();
        for (String entry : removable) {
            if (!verified.contains(entry)) {
                continue;
            }
            String original = originalOf.get(entry);
            if (original != null && !verified.contains(original)) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }
}
