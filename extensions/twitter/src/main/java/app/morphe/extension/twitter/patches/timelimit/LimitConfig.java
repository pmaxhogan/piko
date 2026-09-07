package app.morphe.extension.twitter.patches.timelimit;

/**
 * Build-time configuration. The patch rewrites {@link BuildValues} for each
 * variant; this class turns those raw values into the clamped, validated
 * numbers the engine uses. Nothing here is user-editable at runtime.
 */
public final class LimitConfig {
    /** Hard ceiling: no build can allow more than 2 hours per day. */
    public static final int ABSOLUTE_MAX_MINUTES = 120;
    /** Preferred default; a build whose max is lower than this defaults to its max. */
    public static final int PREFERRED_DEFAULT_MINUTES = 30;
    public static final int MIN_MINUTES = 1;

    public final int maxMinutes;
    public final int defaultMinutes;

    public LimitConfig(int rawMaxMinutes) {
        int max = Math.min(ABSOLUTE_MAX_MINUTES, Math.max(MIN_MINUTES, rawMaxMinutes));
        this.maxMinutes = max;
        this.defaultMinutes = Math.min(PREFERRED_DEFAULT_MINUTES, max);
    }

    public static LimitConfig fromBuild() {
        return new LimitConfig(BuildValues.maxLimitMinutes());
    }

    /** Clamp any stored/requested limit into this build's legal range. */
    public int clamp(int minutes) {
        return Math.min(maxMinutes, Math.max(MIN_MINUTES, minutes));
    }
}
