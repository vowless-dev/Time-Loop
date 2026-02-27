package com.vltno.timeloop.voicechat;

/**
 * Audio analysis utilities for voice chat signals.
 * <p>
 * Provides dB-level calculation used to determine if a voice signal is loud
 * enough to trigger game events (sculk sensors, warden). Algorithm sourced from
 * <a href="https://github.com/henkelmax/voicechat-interaction">voicechat-interaction</a>
 * by Max Henkel.
 */
public final class AudioUtils {

    private AudioUtils() {}

    /**
     * Calculates the audio level of a PCM signal in decibels.
     * <p>
     * Uses the same RMS formula as vcinteraction's {@code AudioUtils.calculateAudioLevel()}.
     *
     * @param samples 16-bit PCM samples (mono or interleaved stereo)
     * @return audio level in dB (range −127 to 0)
     */
    public static double calculateAudioLevelDb(short[] samples) {
        double rms = 0.0;

        for (short sample : samples) {
            double normalized = (double) sample / (double) Short.MAX_VALUE;
            rms += normalized * normalized;
        }

        // vcinteraction uses samples.length / 2 — this accounts for the
        // interleaved stereo layout of Opus-decoded PCM.
        int sampleCount = samples.length / 2;
        rms = (sampleCount == 0) ? 0.0 : Math.sqrt(rms / sampleCount);

        if (rms > 0.0) {
            return Math.min(Math.max(20.0 * Math.log10(rms), -127.0), 0.0);
        }
        return -127.0;
    }

    /**
     * Checks whether a PCM audio frame exceeds the given dB threshold.
     *
     * @param samples   16-bit PCM samples
     * @param thresholdDb minimum activation level in dB (e.g. −50)
     * @return {@code true} if the audio level meets or exceeds the threshold
     */
    public static boolean isAboveThreshold(short[] samples, double thresholdDb) {
        return calculateAudioLevelDb(samples) >= thresholdDb;
    }

    /**
     * Returns {@code true} if the given PCM frame is entirely silent
     * (all zero samples).
     */
    public static boolean isSilent(short[] samples) {
        for (short s : samples) {
            if (s != 0) return false;
        }
        return true;
    }
}
