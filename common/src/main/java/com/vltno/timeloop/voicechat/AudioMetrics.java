package com.vltno.timeloop.voicechat;

/**
 * Audio quality analysis utilities for PCM data.
 * <p>
 * Provides RMS calculation, silence trimming, duration metrics,
 * and quality filtering for recorded voice segments.
 */
public final class AudioMetrics {

    private static final int SAMPLE_RATE = 48000;

    /**
     * Default silence threshold (absolute sample value below which a sample
     * is considered silence). Matches simple-voice-chat-recording's default.
     */
    public static final int SILENCE_THRESHOLD = 700;

    private AudioMetrics() {} // utility class

    /* ───────────── FILTER RESULT ───────────── */

    /**
     * Result of quality filtering on an audio segment.
     */
    public enum FilterResult {
        /** Audio passes all quality checks. */
        PASSED,
        /** Audio is too short (≤ 0.9 s). */
        TOO_SHORT,
        /** Audio is too long (> 10 s per segment — unusual for a single speech burst). */
        TOO_LONG,
        /** No active (above-threshold) samples found. */
        NO_ACTIVE_AUDIO,
        /** RMS of active region is below minimum loudness threshold. */
        LOW_RMS
    }

    /* ───────────── ANALYSIS ───────────── */

    /**
     * Computes the duration of a PCM buffer in seconds.
     */
    public static double getDuration(short[] pcm) {
        return (double) pcm.length / SAMPLE_RATE;
    }

    /**
     * Finds the first and last sample indices that exceed the silence threshold.
     *
     * @return an int[2] of {start, end} (inclusive), or null if entirely silent
     */
    public static int[] getActiveRegion(short[] pcm) {
        return getActiveRegion(pcm, SILENCE_THRESHOLD);
    }

    /**
     * Finds the first and last sample indices that exceed the given threshold.
     *
     * @return an int[2] of {start, end} (inclusive), or null if entirely silent
     */
    public static int[] getActiveRegion(short[] pcm, int threshold) {
        int start = 0;
        while (start < pcm.length && Math.abs(pcm[start]) < threshold) {
            start++;
        }
        if (start >= pcm.length) return null; // entirely silent

        int end = pcm.length - 1;
        while (end > start && Math.abs(pcm[end]) < threshold) {
            end--;
        }

        return new int[]{start, end};
    }

    /**
     * Returns the number of active (non-silent) samples — the length of the
     * region between the first and last above-threshold samples.
     */
    public static int getActiveSamples(short[] pcm) {
        int[] region = getActiveRegion(pcm);
        if (region == null) return 0;
        return region[1] - region[0] + 1;
    }

    /**
     * Returns the active duration in seconds (trimmed of leading/trailing silence).
     */
    public static double getActiveDuration(short[] pcm) {
        return (double) getActiveSamples(pcm) / SAMPLE_RATE;
    }

    /**
     * Computes the RMS (root mean square) of the active region.
     * Returns 0 if the audio is entirely silent.
     */
    public static double getRms(short[] pcm) {
        int[] region = getActiveRegion(pcm);
        if (region == null) return 0;

        long sumSquares = 0;
        for (int i = region[0]; i <= region[1]; i++) {
            long s = pcm[i];
            sumSquares += s * s;
        }
        int count = region[1] - region[0] + 1;
        return Math.sqrt((double) sumSquares / count);
    }

    /**
     * Runs a quality filter on the PCM segment.
     *
     * @param minDurationSec  minimum acceptable duration in seconds (default 0.9)
     * @param maxDurationSec  maximum acceptable duration in seconds (default 10.0)
     * @param minRms          minimum RMS for the active region (default 500)
     */
    public static FilterResult filter(short[] pcm,
                                      double minDurationSec,
                                      double maxDurationSec,
                                      double minRms) {
        double duration = getDuration(pcm);
        if (duration <= minDurationSec) return FilterResult.TOO_SHORT;
        if (duration > maxDurationSec) return FilterResult.TOO_LONG;
        if (getActiveSamples(pcm) <= 0) return FilterResult.NO_ACTIVE_AUDIO;
        return getRms(pcm) >= minRms ? FilterResult.PASSED : FilterResult.LOW_RMS;
    }

    /**
     * Runs the quality filter with default thresholds
     * (min 0.9s, max 10s, min RMS 500).
     */
    public static FilterResult filter(short[] pcm) {
        return filter(pcm, 0.9, 10.0, 500.0);
    }

    /**
     * Returns a human-readable summary of the audio's metrics.
     */
    public static String describe(short[] pcm) {
        return String.format(
                "duration=%.3fs, active=%.3fs, rms=%.1f, samples=%d",
                getDuration(pcm),
                getActiveDuration(pcm),
                getRms(pcm),
                pcm.length
        );
    }
}
