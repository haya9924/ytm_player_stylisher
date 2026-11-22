package app.morphe.extension.ym;

import java.nio.file.Path;

/**
 * Tracks one playback session at a time and records it once its fixed listening threshold is
 * verified.
 *
 * <p>The tracker is generation driven: the caller supplies a positive, monotonically increasing
 * {@code generation} for every new playback attempt. The tracker never derives progress from a
 * clock, a player position, or any other host state; the only progress input is the cumulative
 * verified listening time passed to {@link #progress(long, long)}. It is also not wired into any
 * host code: callers use {@link #begin}, {@link #progress}, and {@link #end} explicitly.
 *
 * <p>Contract:
 * <ul>
 *   <li>Only the latest generation is tracked. A strictly newer generation replaces (invalidates)
 *       the previous session; older or equal generations are ignored unless the call is an exact
 *       duplicate of the active {@code begin}.</li>
 *   <li>An exact duplicate {@code begin} (same generation, identifiers, duration, and criteria)
 *       never resets progress or the threshold.</li>
 *   <li>Invalid input on a strictly newer {@code begin} invalidates the previous session and
 *       records nothing.</li>
 *   <li>The listening threshold is computed once, at {@code begin}, from the supplied duration and
 *       {@link CacheRetentionCriteria}, and never changes for that generation.</li>
 *   <li>{@code progress} must be non-negative and non-decreasing for the active generation. Equal
 *       values are accepted so a retry is possible after
 *       {@link Result#UNAVAILABLE}.</li>
 *   <li>A terminal {@link QualifiedPlayCounter.RecordResult} ({@code RECORDED},
 *       {@code ALREADY_RECORDED}, {@code SATURATED}) stops further store writes for that
 *       generation. {@code UNAVAILABLE} is not terminal: the same session may retry.</li>
 * </ul>
 *
 * <p>Pure Java on purpose: the durable store is a {@link QualifiedPlayCounter} over a supplied
 * {@link Path}, so the whole state machine can be exercised on the JVM with a real file.
 */
final class PlaybackSessionTracker {
    /** Outcome of a tracker operation. */
    public enum Result {
        /** No active session, or the generation does not match the tracked session. */
        IGNORED,
        /** Invalid input; on {@code begin} any previously tracked session was invalidated. */
        REJECTED,
        /** Session is tracked but its threshold has not been reached (or was never written). */
        PENDING,
        /** The session was newly added to the durable store. */
        RECORDED,
        /** The durable store already held this exact session. */
        ALREADY_RECORDED,
        /** The account/track pair already holds the maximum number of sessions. */
        SATURATED,
        /** The durable store could not be read or written; the same session may retry. */
        UNAVAILABLE
    }

    private final QualifiedPlayCounter counter;

    private Session session;
    private long lastGeneration;

    /**
     * @param file snapshot file used for durable counting; created on demand
     */
    public PlaybackSessionTracker(Path file) {
        this.counter = new QualifiedPlayCounter(file);
    }

    /**
     * Starts tracking the session identified by {@code generation}.
     *
     * @param generation strictly increasing positive generation of the playback attempt
     * @param account opaque account identifier
     * @param track opaque track identifier
     * @param session opaque session identifier, recorded at most once per account and track
     * @param durationMs track duration in milliseconds; non-positive means unknown
     * @param criteria threshold criteria captured now and fixed for this generation
     * @return {@link Result#PENDING} when the session is now tracked,
     *         the current terminal result for an exact duplicate,
     *         {@link Result#IGNORED} for a stale generation,
     *         or {@link Result#REJECTED} for invalid input (which also invalidates any previous
     *         session)
     */
    public synchronized Result begin(long generation, String account, String track, String session,
            long durationMs, CacheRetentionCriteria criteria) {
        if (generation <= 0L) return Result.IGNORED;
        Session current = this.session;
        if (current != null && generation == current.generation) {
            if (current.matches(account, track, session, durationMs, criteria)) {
                // Exact duplicate: keep progress and the fixed threshold untouched.
                return current.status;
            }
            // Same generation, different session: not newer, so it must not disturb the state.
            return Result.IGNORED;
        }
        if (generation <= lastGeneration) {
            // Older or already consumed generation; never invalidates the current session.
            return Result.IGNORED;
        }
        lastGeneration = generation;
        if (criteria == null || !QualifiedPlayCounter.isValidId(account)
                || !QualifiedPlayCounter.isValidId(track) || !QualifiedPlayCounter.isValidId(session)) {
            this.session = null;
            return Result.REJECTED;
        }
        this.session = new Session(generation, account, track, session, durationMs, criteria);
        return Result.PENDING;
    }

    /**
     * Advances the verified cumulative listening time of the tracked generation.
     *
     * <p>Negative or decreasing values are ignored; nothing is estimated from them. Reaching the
     * threshold attempts exactly one durable record. A terminal outcome prevents any further write
     * for the same generation, while {@link Result#UNAVAILABLE} leaves the session open so the
     * same session can retry with an equal or larger value.
     *
     * @param generation generation of the session being reported
     * @param verifiedCumulativeMillis non-negative cumulative verified listening time
     * @return the session status; {@link Result#IGNORED} for an unknown generation or an invalid
     *         value
     */
    public synchronized Result progress(long generation, long verifiedCumulativeMillis) {
        Session current = this.session;
        if (current == null || generation != current.generation) return Result.IGNORED;
        if (verifiedCumulativeMillis < 0L) return Result.IGNORED;
        if (verifiedCumulativeMillis < current.listenedMillis) return Result.IGNORED;
        current.listenedMillis = verifiedCumulativeMillis;
        if (current.status != Result.PENDING) return current.status;
        if (current.listenedMillis < current.requiredMillis) return Result.PENDING;

        QualifiedPlayCounter.RecordResult record =
                counter.recordOnce(current.account, current.track, current.session);
        switch (record) {
            case RECORDED:
                current.status = Result.RECORDED;
                break;
            case ALREADY_RECORDED:
                current.status = Result.ALREADY_RECORDED;
                break;
            case SATURATED:
                current.status = Result.SATURATED;
                break;
            case UNAVAILABLE:
                // Not terminal: keep progress and allow the same session to retry.
                return Result.UNAVAILABLE;
            default:
                throw new AssertionError("unhandled record result: " + record);
        }
        return current.status;
    }

    /**
     * Ends the tracked generation without recording anything.
     *
     * @param generation generation of the session being ended
     * @return the final status of that session ({@link Result#PENDING} when it never counted), or
     *         {@link Result#IGNORED} when the generation is not the tracked one
     */
    public synchronized Result end(long generation) {
        Session current = this.session;
        if (current == null || generation != current.generation) return Result.IGNORED;
        this.session = null;
        return current.status;
    }

    private static final class Session {
        final long generation;
        final String account;
        final String track;
        final String session;
        final long durationMs;
        final int playCount;
        final int seconds;
        final int percent;
        final long requiredMillis;
        long listenedMillis;
        Result status = Result.PENDING;

        Session(long generation, String account, String track, String session, long durationMs,
                CacheRetentionCriteria criteria) {
            this.generation = generation;
            this.account = account;
            this.track = track;
            this.session = session;
            this.durationMs = durationMs;
            this.playCount = criteria.playCount;
            this.seconds = criteria.seconds;
            this.percent = criteria.percent;
            // Fixed at start: later progress cannot move the threshold.
            this.requiredMillis = criteria.requiredListenedMillis(durationMs);
        }

        boolean matches(String account, String track, String session, long durationMs,
                CacheRetentionCriteria criteria) {
            return this.durationMs == durationMs
                    && this.account.equals(account)
                    && this.track.equals(track)
                    && this.session.equals(session)
                    && criteria != null
                    && playCount == criteria.playCount
                    && seconds == criteria.seconds
                    && percent == criteria.percent;
        }
    }
}
