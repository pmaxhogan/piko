package app.morphe.extension.twitter.patches.timelimit;

import java.util.TimeZone;

final class FakeClock implements Clock {
    long wall;
    long elapsed;
    long boot = 7;
    TimeZone zone = TimeZone.getTimeZone("America/Chicago");

    FakeClock(long wall, long elapsed) {
        this.wall = wall;
        this.elapsed = elapsed;
    }

    /** Real time passes: both clocks advance together. */
    void pass(long ms) {
        wall += ms;
        elapsed += ms;
    }

    @Override public long wallMs() { return wall; }
    @Override public long elapsedMs() { return elapsed; }
    @Override public long bootCount() { return boot; }
    @Override public TimeZone zone() { return zone; }
}
