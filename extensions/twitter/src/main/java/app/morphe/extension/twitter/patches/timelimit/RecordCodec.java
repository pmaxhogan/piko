package app.morphe.extension.twitter.patches.timelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Serializes a {@link StateRecord} to one signed line and back.
 *
 * Format (all ASCII, one line, safe for Settings.System and file stores):
 *   VX1|h=at:day:min,...|d=day:spent:mask,...|t=today|a=wall:elapsed:boot|s=<hmac-sha256 hex>
 *
 * The HMAC covers everything before "|s=". The key is a build-embedded secret
 * mixed with a device-bound salt, so a copy edited by hand (or lifted from
 * another phone) fails verification and is ignored by the merge. This is a
 * tamper *detector* for a user who found one of the files, not a cryptographic
 * boundary: the secret ships in the APK. That is the accepted ceiling.
 */
public final class RecordCodec {
    static final String MAGIC = "VX1";
    private static final String BUILD_SECRET = "vantage-x-timelimit:5c7b3d1e9a4f:do-not-edit";

    private final byte[] key;

    public RecordCodec(String deviceSalt) {
        this.key = (BUILD_SECRET + "|" + (deviceSalt == null ? "" : deviceSalt)).getBytes(StandardCharsets.UTF_8);
    }

    public String encode(StateRecord r) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(MAGIC).append("|h=");
        boolean first = true;
        for (StateRecord.LimitChange c : r.changes) {
            if (!first) sb.append(',');
            first = false;
            sb.append(c.atMs).append(':').append(c.dayKey).append(':').append(c.minutes);
        }
        sb.append("|d=");
        first = true;
        for (java.util.Map.Entry<Integer, StateRecord.DayUsage> e : r.days.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append(':').append(e.getValue().spentMs).append(':').append(e.getValue().warningsShown);
        }
        sb.append("|t=").append(r.today);
        sb.append("|a=");
        if (r.anchor != null) {
            sb.append(r.anchor.wallMs).append(':').append(r.anchor.elapsedMs).append(':').append(r.anchor.bootCount);
        }
        String body = sb.toString();
        return body + "|s=" + hmac(body);
    }

    /** @return the record, or null if the text is missing, malformed, or fails the signature. */
    public StateRecord decode(String text) {
        if (text == null) return null;
        text = text.trim();
        int sig = text.lastIndexOf("|s=");
        if (!text.startsWith(MAGIC + "|") || sig < 0) return null;
        String body = text.substring(0, sig);
        String given = text.substring(sig + 3);
        if (!constantTimeEquals(hmac(body), given)) return null;
        try {
            return parseBody(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static StateRecord parseBody(String body) {
        StateRecord r = new StateRecord();
        String[] parts = body.split("\\|");
        for (String p : parts) {
            if (p.startsWith("h=")) {
                for (String item : splitList(p.substring(2))) {
                    String[] f = item.split(":");
                    r.changes.add(new StateRecord.LimitChange(Long.parseLong(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])));
                }
            } else if (p.startsWith("d=")) {
                for (String item : splitList(p.substring(2))) {
                    String[] f = item.split(":");
                    r.days.put(Integer.parseInt(f[0]), new StateRecord.DayUsage(Long.parseLong(f[1]), Integer.parseInt(f[2])));
                }
            } else if (p.startsWith("t=")) {
                r.today = Integer.parseInt(p.substring(2));
            } else if (p.startsWith("a=") && p.length() > 2) {
                String[] f = p.substring(2).split(":");
                r.anchor = new StateRecord.TimeAnchor(Long.parseLong(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2]));
            }
        }
        java.util.Collections.sort(r.changes, StateRecord.CHANGE_ORDER);
        return r;
    }

    private static String[] splitList(String s) {
        return s.isEmpty() ? new String[0] : s.split(",");
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Small helper for stores that need the record as opaque bytes (PNG chunk, WAV chunk). */
    public static byte[] toBytes(String encoded) {
        return Base64.getEncoder().encode(encoded.getBytes(StandardCharsets.UTF_8));
    }

    public static String fromBytes(byte[] b) {
        try {
            return new String(Base64.getDecoder().decode(b), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
