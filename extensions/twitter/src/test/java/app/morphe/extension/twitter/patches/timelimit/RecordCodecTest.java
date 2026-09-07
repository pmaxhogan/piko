package app.morphe.extension.twitter.patches.timelimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecordCodecTest {
    static StateRecord sample() {
        StateRecord r = new StateRecord();
        r.addChange(new StateRecord.LimitChange(1000, 20260906, 45));
        r.addChange(new StateRecord.LimitChange(2000, 20260907, 30));
        r.usage(20260906).spentMs = 1234567;
        r.usage(20260907).spentMs = 89000;
        r.usage(20260907).warningsShown = 0b11;
        r.today = 20260907;
        r.anchor = new StateRecord.TimeAnchor(5000, 6000, 7);
        return r;
    }

    @Test
    public void roundTrips() {
        RecordCodec codec = new RecordCodec("device-salt");
        String s = codec.encode(sample());
        assertTrue(s.startsWith("VX1|"));
        assertEquals(-1, s.indexOf('\n'));
        StateRecord back = codec.decode(s);
        assertNotNull(back);
        assertEquals(2, back.changes.size());
        assertEquals(45, back.changes.get(0).minutes);
        assertEquals(1234567, back.spentMs(20260906));
        assertEquals(0b11, back.usage(20260907).warningsShown);
        assertEquals(20260907, back.today);
        assertEquals(6000, back.anchor.elapsedMs);
        assertEquals(s, codec.encode(back));
    }

    @Test
    public void emptyRecordRoundTrips() {
        RecordCodec codec = new RecordCodec("x");
        StateRecord back = codec.decode(codec.encode(new StateRecord()));
        assertNotNull(back);
        assertEquals(0, back.today);
        assertNull(back.anchor);
    }

    @Test
    public void tamperedTextIsRejected() {
        RecordCodec codec = new RecordCodec("device-salt");
        String s = codec.encode(sample());
        String edited = s.replace(":89000:", ":1:");            // shave today's spent time
        assertNull(codec.decode(edited));
        assertNull(codec.decode(s.substring(0, s.length() - 2) + "zz"));
        assertNull(codec.decode("garbage"));
        assertNull(codec.decode(""));
        assertNull(codec.decode(null));
    }

    @Test
    public void recordFromAnotherDeviceIsRejected() {
        String s = new RecordCodec("phone-a").encode(sample());
        assertNull(new RecordCodec("phone-b").decode(s));
        assertNotNull(new RecordCodec("phone-a").decode(s));
    }

    @Test
    public void bytesHelpersRoundTrip() {
        String s = new RecordCodec("a").encode(sample());
        assertEquals(s, RecordCodec.fromBytes(RecordCodec.toBytes(s)));
        assertNull(RecordCodec.fromBytes(new byte[]{(byte) 0xff, 1, 2}));
    }
}
