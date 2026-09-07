package app.morphe.extension.twitter.patches.timelimit;

/** One place a signed record copy lives. Implementations must never throw on a missing copy. */
public interface BlobStore {
    String name();

    /** @return the stored text, or null when there is none / it is unreadable. */
    String read();

    /** @return false if the write failed (permission not yet granted, disk error). */
    boolean write(String encoded);
}
