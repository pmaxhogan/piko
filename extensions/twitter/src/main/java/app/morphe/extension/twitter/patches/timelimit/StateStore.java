package app.morphe.extension.twitter.patches.timelimit;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.util.List;

/** Loads the merged record from every store and fans a save out to all of them. */
public final class StateStore {
    private static final String TAG = "VantageX/State";

    private final List<BlobStore> stores;
    private final RecordCodec codec;
    private String lastWritten;

    public StateStore(Context context) {
        this.stores = Stores.all(context);
        this.codec = new RecordCodec(deviceSalt(context));
    }

    /**
     * ANDROID_ID is stable per (device, signing key, user) across reinstalls, so
     * a record copied from another phone fails the signature and is ignored.
     */
    static String deviceSalt(Context c) {
        try {
            String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
            return id == null ? "" : id;
        } catch (Throwable t) {
            return "";
        }
    }

    public StateRecord load() {
        StateRecord merged = new StateRecord();
        int valid = 0;
        for (BlobStore s : stores) {
            String text = s.read();
            if (text == null) continue;
            StateRecord r = codec.decode(text);
            if (r == null) {
                Log.w(TAG, "store " + s.name() + " holds an invalid/tampered record - ignored");
                continue;
            }
            merged.mergeFrom(r);
            valid++;
        }
        Log.i(TAG, "loaded record from " + valid + "/" + stores.size() + " stores");
        return merged;
    }

    /** @return how many stores accepted the write. */
    public int save(StateRecord r) {
        String text = codec.encode(r);
        lastWritten = text;
        int ok = 0;
        for (BlobStore s : stores) {
            if (s.write(text)) ok++;
        }
        if (ok < stores.size()) Log.w(TAG, "record written to " + ok + "/" + stores.size() + " stores");
        return ok;
    }

    /** Re-write the last saved text to any store that lost it (cheap self-heal, no re-encode). */
    public void heal() {
        if (lastWritten == null) return;
        for (BlobStore s : stores) {
            String cur = s.read();
            if (cur == null || !cur.equals(lastWritten)) s.write(lastWritten);
        }
    }

    public int storeCount() {
        return stores.size();
    }
}
