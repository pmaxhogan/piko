package app.morphe.extension.twitter.patches.timelimit;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Wraps the record in a real 1x1 PNG so the copy in Pictures looks like, and
 * opens as, a thumbnail. The payload rides in a tEXt chunk keyed "Comment".
 */
final class PngCarrier {
    private static final byte[] SIG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final String KEYWORD = "Comment";

    private PngCarrier() {}

    static byte[] wrap(String encoded) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        out.write(SIG);
        // IHDR: 1x1, 8-bit greyscale
        byte[] ihdr = {0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0};
        chunk(out, "IHDR", ihdr);
        byte[] text = (KEYWORD + "\0" + encoded).getBytes(StandardCharsets.ISO_8859_1);
        chunk(out, "tEXt", text);
        Deflater d = new Deflater();
        d.setInput(new byte[]{0, (byte) 0x80});   // filter 0 + one mid-grey pixel
        d.finish();
        byte[] buf = new byte[64];
        int n = d.deflate(buf);
        d.end();
        byte[] idat = new byte[n];
        System.arraycopy(buf, 0, idat, 0, n);
        chunk(out, "IDAT", idat);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** @return the payload, or null if this is not one of our PNGs. */
    static String unwrap(byte[] png) {
        if (png == null || png.length < 8 + 12) return null;
        for (int i = 0; i < 8; i++) if (png[i] != SIG[i]) return null;
        int pos = 8;
        while (pos + 12 <= png.length) {
            int len = readInt(png, pos);
            String type = new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if (len < 0 || pos + 12 + len > png.length) return null;
            if (type.equals("tEXt")) {
                String s = new String(png, pos + 8, len, StandardCharsets.ISO_8859_1);
                int nul = s.indexOf('\0');
                if (nul > 0 && s.substring(0, nul).equals(KEYWORD)) return s.substring(nul + 1);
            }
            if (type.equals("IEND")) break;
            pos += 12 + len;
        }
        return null;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(data.length);
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        d.write(t);
        d.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        d.writeInt((int) crc.getValue());
        d.flush();
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }
}
