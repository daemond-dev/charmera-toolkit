package io.github.daemonddev.charmera;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Plain-JVM tests for the backup's read-back check and the delete decision (ZipCheck). */
public final class ZipCheckTest {

    private static int failures = 0;
    private static int passes = 0;

    public static void main(String[] args) throws IOException {
        verifyMatchesOnlyWhatWasWritten();
        duplicateNamesNeverVerify();
        deletableNeedsCopyAndOriginal();
        System.out.printf(Locale.ROOT, "ZipCheckTest: %d passed, %d failed%n", passes, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static byte[] zip(Object... nameAndData) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (int i = 0; i < nameAndData.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) nameAndData[i]));
                z.write((byte[]) nameAndData[i + 1]);
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static long[] want(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data, 0, data.length);
        return new long[]{c.getValue(), data.length};
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static void verifyMatchesOnlyWhatWasWritten() throws IOException {
        byte[] a = b("photo A");
        byte[] c = b("video C");
        Map<String, long[]> written = new HashMap<>();
        written.put("DCIM/A.jpg", want(a));
        written.put("DCIM/B.jpg", want(b("photo B")));
        written.put("DCIM/C.avi", want(c));
        written.put("DCIM/D.jpg", want(b("photo D")));
        // B's bytes differ, C is cut short, D is missing, X was never written
        byte[] z = zip("DCIM/A.jpg", a, "DCIM/B.jpg", b("photo b"), "DCIM/C.avi", Arrays.copyOf(c, 3), "X.txt", b("x"));
        Set<String> v = ZipCheck.verify(new ByteArrayInputStream(z), written);
        expect("only the entry that read back the same verifies", v.equals(new HashSet<>(Arrays.asList("DCIM/A.jpg"))),
                v.toString());
        expect("an empty zip verifies nothing", ZipCheck.verify(new ByteArrayInputStream(zip()), written).isEmpty());
        boolean threw = false;
        try {
            ZipCheck.verify(new ByteArrayInputStream(b("not a zip at all, but long enough to read")), written);
        } catch (IOException e) {
            threw = true;
        }
        expect("a file that is not a zip verifies nothing", threw
                || ZipCheck.verify(new ByteArrayInputStream(b("not a zip")), written).isEmpty());
    }

    static void duplicateNamesNeverVerify() throws IOException {
        byte[] a = b("photo A");
        Map<String, long[]> written = new HashMap<>();
        written.put("DCIM/A.jpg", want(a));
        byte[] twice = zipAllowingDuplicates("DCIM/A.jpg", a, "DCIM/A.jpg", a);
        expect("a name twice in the zip is not verified, even when both copies match",
                ZipCheck.verify(new ByteArrayInputStream(twice), written).isEmpty());
    }

    /** ZipOutputStream refuses a duplicate name, so the second copy is written as a different name and renamed. */
    private static byte[] zipAllowingDuplicates(String n1, byte[] d1, String n2, byte[] d2) throws IOException {
        String other = n2.replace('A', 'Q');
        byte[] z = zip(n1, d1, other, d2);
        byte[] from = b(other);
        byte[] to = b(n2);
        for (int i = 0; i + from.length <= z.length; i++) {      // local header and central directory both
            if (Arrays.equals(Arrays.copyOfRange(z, i, i + from.length), from)) {
                System.arraycopy(to, 0, z, i, to.length);
            }
        }
        return z;
    }

    static void deletableNeedsCopyAndOriginal() {
        Set<String> removable = new LinkedHashSet<>(Arrays.asList("DCIM/1.jpg", "DCIM/2.jpg", "DCIM/3.jpg",
                "DCIM/4.avi"));
        Set<String> verified = new HashSet<>(Arrays.asList("DCIM/1.jpg", "DCIM/2.jpg", "originals/DCIM/2.jpg",
                "DCIM/4.avi", "DCIM/5.jpg"));
        Map<String, String> originalOf = new LinkedHashMap<>();
        originalOf.put("DCIM/2.jpg", "originals/DCIM/2.jpg");
        originalOf.put("DCIM/4.avi", "originals/DCIM/4.avi");     // its original did not verify
        Set<String> d = ZipCheck.deletable(removable, verified, originalOf);
        expect("deletable: verified copies, with their originals verified when there are any",
                d.equals(new LinkedHashSet<>(Arrays.asList("DCIM/1.jpg", "DCIM/2.jpg"))), d.toString());
        expect("a copy that did not verify is never deletable", !d.contains("DCIM/3.jpg"));
        expect("a copy whose original did not verify is never deletable", !d.contains("DCIM/4.avi"));
        expect("a verified file that is not removable (not the camera's own, or not whole) is never deletable",
                !d.contains("DCIM/5.jpg"));
        expect("nothing removable: nothing deletable",
                ZipCheck.deletable(new HashSet<String>(), verified, originalOf).isEmpty());
        expect("nothing verified: nothing deletable",
                ZipCheck.deletable(removable, new HashSet<String>(), new HashMap<String, String>()).isEmpty());
        expect("the inputs are not changed", removable.size() == 4 && verified.size() == 5);
    }

    static void expect(String what, boolean ok) {
        expect(what, ok, null);
    }

    static void expect(String what, boolean ok, String shown) {
        if (ok) {
            passes++;
        } else {
            failures++;
            System.out.println("FAILED: " + what + (shown == null ? "" : "\n" + shown));
        }
    }
}
